# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) or other agentic coding harnesses when working with code in this repository.

## Status

A working agent harness covering milestones **M0–M7**, plus subagents (sync or async), MCP,
streaming on every provider, lease-based crash recovery, a per-thread run lock, context
compaction with model summaries, vector memory, configurable denials, egress lists and per-tool
rate limits, injection heuristics, auth and process gates with expiry, a scheduler behind
`submit`/`worker`, an HTTP surface (`serve`) with run projections and SSE, attachments, store
retention, a container sandbox for the shell lane, extension registries with versioned
upgrades and profiles, channel adapters, OIDC login, a WASM lane, subprocess secret
staging, per-concept SQL tables, trace propagation, hooks on prompt assembly and gate
raising, MCP OAuth and sampling, and filesystem and fan-out triggers. `jclaw run "..."` completes a real turn end
to end; runs park on gates and resume across process boundaries; memory, skills, and scheduled
routines work. Ships as an uber jar and a GraalVM native image, both verified — including
subprocess spawning for MCP servers and subagent child runs.

jclaw is a Java/Spring Boot reimplementation of the **architecture** of
[IronClaw](https://github.com/nearai/ironclaw) (a ~1.4M-line Rust agent harness, internally
"Reborn"). It is an architectural clone, not a port: the layering, the turn/run lifecycle, the
untrusted-`LoopExit` trust model, and the `CapabilityHost` authority boundary are faithful; the
feature surface is a fraction of IronClaw's. See **Not built yet** for the honest list.

501 tests pass across seven modules with tests, including 16 machine-checked architecture rules.

## Commands

- Build everything: `mvn clean install`
- Test (full suite): `mvn test`
- Test (single): `mvn test -Dtest=ClassName#methodName -pl <module>` (add `-am` if deps are stale)
- Run (jar): `java -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar <command>`
- Static analysis (opt-in): `mvn -Panalysis verify` — see below
- Coverage: any `mvn verify`; `./scripts/coverage.sh` prints the total
- Source-integrity guard: `./scripts/byte-verify.sh scan`
- Licence-header guard: `./scripts/license-check.sh`
- README flavours: `./scripts/readme-sync.sh` (and `--check`, which CI runs)
- Browser-UI key handling: `./scripts/web-ui-keys.sh` (needs a local Chrome; not in `mvn test`)

### Static analysis

`mvn -Panalysis verify` adds three layers to the ordinary build. They are a profile rather than
the default because they are slow, and because a dependency upgrade should not be able to fail
`mvn test` on somebody else's deprecation.

| | sees | configured by |
|---|---|---|
| `javac -Xlint:all,-serial,-processing` | the compiler's own opinion, always current with the language | the profile, with `failOnWarning` |
| SpotBugs + FindSecBugs | bytecode patterns: null paths, dead stores, injection and crypto families | `config/spotbugs-exclude.xml` |
| PMD 7 | source patterns javac has no opinion about, including dead code | `config/pmd-ruleset.xml` |

**The tree is clean under all three, and the guards fail the build.** Both filter files justify
every exclusion in prose: an exclusion without a reason is indistinguishable from a finding
somebody got tired of. Add to them only with the reason attached.

Four things about the wiring are load-bearing, and three of them fail silently:

- **A child module's `<compilerArgs>` replaces the parent's, it does not extend it.**
  `jclaw-app` declares its own for the picocli annotation processor, so the lint flags never
  reached the module with the most code in it — the profile passed while linting seven modules out
  of eight. Fixed with `combine.children="append"` in `jclaw-app/pom.xml`; check with
  `mvn -Panalysis help:effective-pom -pl jclaw-app`, not by reading the parent.
- **`${maven.multiModuleProjectDirectory}` resolves to `$HOME`** without a `.mvn` directory at the
  root, so the config paths use `${session.executionRootDirectory}`. Run the profile from the
  repository root.
- **SpotBugs needs the 4.10 line** to read class files from a JDK newer than 25; 4.9 dies with
  `Unsupported class file major version` on the JDK's own classes, not on ours.
- **PMD's `UnusedLocalVariable` stays quiet when the initializer is a method call**, which makes it
  a poor choice for proving the ruleset fires. `EmptyCatchBlock` and `UnusedPrivateMethod` are the
  ones to plant.

### PostgreSQL

`storage=sql` defaults to embedded H2, and everything built on it — four migrations, a table per
busy store, materialised projections, the pooled connections, the cross-host thread lock — was
written against H2 alone. `SqlSchema` claims its DDL is "written in the dialect H2 and PostgreSQL
share"; `PostgresStorageIntegrationTest` is what actually checks that claim, on a real server
under Testcontainers.

```bash
mvn test -Dtest=PostgresStorageIntegrationTest -pl jclaw-app   # needs a Docker daemon
docker compose run --rm jclaw run "hello"                      # the same thing by hand (jar)
docker compose -f docker-compose.native.yml run --rm jclaw run "hello"   # the native image
```

**The native image talks to PostgreSQL**, and CI checks it. The binary had been verified against
embedded H2 only, and the PostgreSQL driver has no captured native-image metadata of its own. It
turns out not to need any — Boot's AOT processing registers the driver Spring resolves from the
configured URL — but that is exactly the kind of thing `--no-fallback` turns from a degradation
into a crash, so it is worth a guard rather than a one-off check.

Three layers cover it, and they cover different things:

| | runs on | database |
|---|---|---|
| `PostgresStorageIntegrationTest` | JVM (`@SpringBootTest`) | PostgreSQL under Testcontainers |
| `native-image` job smoke steps | the native binary | H2, then PostgreSQL as a CI service |
| `docker-compose.native.yml` | the native binary | PostgreSQL, by hand |

Startup measured in the same container shape: 61 ms native against 1.48 s for the jar.

The test is `@Testcontainers(disabledWithoutDocker = true)`: without a daemon it skips rather
than fails, so a developer with Docker stopped does not see a red suite for an environment
problem. **It therefore skips in CI too** — the `build-test` job runs in a plain Maven image with
no Docker socket — so PostgreSQL coverage is something a person runs, not something the pipeline
guarantees. Wiring a `docker:dind` service into that job would close it. **On a non-default Docker socket** (OrbStack, Colima, rootless) Testcontainers may
negotiate too old an API version and report "Could not find a valid Docker environment"; the fix
is `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` plus `-DargLine="-Dapi.version=1.44"`.
**That second flag now costs the coverage report**: JaCoCo's agent reaches surefire through the
`argLine` property, and a command-line `-DargLine` replaces it rather than adding to it, so the
run produces an empty report and no warning. Put `api.version=1.44` in
`~/.testcontainers.properties` instead, or add `-Djacoco.skip=true` and accept that one run
measures nothing.

### Native image

```bash
export JAVA_HOME=/path/to/graalvm        # GraalVM 25+ required
mvn -B install -DskipTests               # install all modules first
mvn -B -Pnative -pl jclaw-app package -DskipTests
./jclaw-app/target/jclaw                 # ~80 MB binary, ~78 ms startup vs ~1.2 s for the jar
```

The `native` profile lives in `jclaw-app/pom.xml`. The Boot parent contributes only
`pluginManagement` for it, so the module must declare `native-maven-plugin` itself — a bare
`mvn -Pnative native:compile` at the root does nothing useful.

### CLI surface

| Command | Purpose |
|---|---|
| `run [--stream] [--attach f]…` | one-shot turn; exits 0 ok, 1 failed, 2 parked on a gate |
| `submit` | queue a turn durably and return its run id; a `worker` or `serve` executes it. `--attach` like `run` |
| `serve [--host --port --concurrency --per-user]` | HTTP ingress + worker loop: browser UI at `/`, OpenAI-compatible `/v1/chat/completions`, enqueue, run projections, SSE event streams, approvals; operator token via `jclaw.serve-token`, per-user tenants via `jclaw.serve-users` |
| `repl` | interactive session with readline editing (and still pipes); resolves approval gates inline on a real terminal |
| `approvals list [--all]\|approve\|deny` | resolve gates (approval, auth, process); approving resumes by default; expired gates are hidden and refuse decisions |
| `resume <run-id>` | continue a parked run |
| `memory write\|search\|list\|forget\|reindex` | durable memories, BM25 + recency + vector (when an embedding provider is configured) |
| `routines add\|list\|remove\|pause\|resume\|run-due` | agent work fired by a trigger: `--cron`, `--every`, `--webhook`, or `--on <event> --when k=v` |
| `worker [--concurrency N]` | long-lived: fires routines, sweeps leases, executes queued runs under a cap |
| `skills list\|show` | installed skills |
| `extensions install\|list\|remove\|enable\|disable\|keygen\|sign\|search\|add\|outdated\|upgrade\|profile` | signed extension packages (skills, MCP servers, WASM tools); a trusted publisher's signature makes an install `VERIFIED`. `search`/`add`/`outdated`/`upgrade` work against `jclaw.extension-registries`; `profile` enables exactly one named set |
| `models [--probe]` | provider status; `--probe` proves one actually responds |
| `onboard` | writes `~/.jclaw/jclaw.yaml` |
| `mcp add\|list\|remove\|toggle\|test\|refresh` | external MCP tool servers over stdio or streamable HTTP; tools, resources, and prompts; started on first use from a cached surface |
| `recover` | reconcile runs whose worker died |
| `retain [--dry-run]` | drop old rows of finished runs from results, events, checkpoints |
| `inbound list\|approve\|discard` | foreign messages the inbound policy held for review; approving enqueues the message fenced, discarding starts nothing |
| `secrets set\|list\|remove` | encrypted vault of credentials tools use by `{{secret:NAME}}` reference, bound to one capability and either a host list or `--subprocess` (staged into a child's environment, never an argument); one vault per tenant |
| `tools` | capability surface with effect/trust/unattended |
| `status [--run id [--spans]]` | recent activity from the event log, or one run's projection, or its spans. Not `--trace`: that name is claimed process-wide for verbosity and never reaches picocli |
| `doctor` | config + security posture; non-zero on real problems |

`run --stream` prints model output as it arrives, over the Anthropic SDK's event stream or the
OpenAI-compatible SSE path (OpenAI, OpenRouter, Ollama, `local`). Streaming is presentation only —
the machine sees the same response either way, so a streamed run and a buffered one produce
identical decisions and transcripts. Tool-call arguments stream as JSON fragments, so the sink is
told a call started and gets the complete call in the final response. The `failover` chain
streams through the first provider that accepts the model and will not fail over once prose has
been shown: a second provider would start a second answer on top of the first.

### Tracing a turn

`--debug` (DEBUG) narrates every pipeline step on any command: admission, each state-machine
step (`phase + observation -> decision`), checkpoint writes, the kernel's authority path
(existence → policy → approval → dispatch → redact/ref), provider calls with timings and token
usage, exit-claim validation, and lease release. `--trace` (TRACE) adds payloads: the system
prompt, every request message, tool arguments, model replies, capability output, and the wire
body — all redacted and bounded before logging, on the same `Redaction` path everything else
uses. Default level is INFO, so plain runs print only the reply; both flags are expanded by
`JclawApplication.expandVerbosityFlags` into `--logging.*` properties, which is also why
`--logging.level.io.jclaw=TRACE` works directly. Loggers live in the runtime, loop, kernel,
providers, and JSONL stores; the domain stays pure — the interpreter logs the machine's
decisions, `TurnMachine` itself never logs. Lower modules carry `slf4j-api` only; logback comes
from `jclaw-app`.

### Providers

`jclaw.provider` selects one of `mock` (default, no key), `anthropic`, `openai`, `openrouter`,
`ollama`, `local`, or `failover`. Credentials come from the environment — `ANTHROPIC_API_KEY`,
`OPENAI_API_KEY`, `OPENROUTER_API_KEY` — never from config files. `jclaw models` lists what is
configured; `jclaw models --probe` proves the active one actually responds.

**`local` is any OpenAI-compatible inference server** — LM Studio, vLLM, llama.cpp's server,
LocalAI — over the same adapter that serves OpenAI and OpenRouter. It requires
`jclaw.local-base-url` (there is no port these servers agree on, so refusing to guess beats a
connection error that reads like a jclaw bug): `http://localhost:1234/v1` for LM Studio,
`http://localhost:8000/v1` for vLLM. `LOCAL_API_KEY` is honoured when set but never required — no
credential hint is registered, so a missing key sends unauthenticated requests (correct for most
local servers) instead of failing with AUTH; a server that does check a token (vLLM `--api-key`)
answers 401 with its own message. It differs from `ollama` only in identity and the optional key,
and the identity matters: the event log and `jclaw models` should say what the operator actually
configured. `supports()` accepts any model id except bare `claude-*` (those belong to the
Anthropic SDK adapter), and when `local-base-url` is set the provider also joins the `failover`
chain between OpenRouter and Ollama. `jclaw onboard` prompts for the base URL when `local` is
chosen and writes it to `jclaw.yaml` — a URL is topology, not a credential.

**OpenRouter** is a gateway fronting many providers behind one OpenAI-compatible endpoint. Two
things differ from plain OpenAI, and both are enforced in code:

- **Model ids are namespaced** `org/model` (`anthropic/claude-sonnet-4.6`, `openai/gpt-5.2`). The
  provider's `supports()` requires that shape, so a failover chain never routes a bare id to
  OpenRouter and gets an unexplained 404 from a gateway the user did not realise they were using.
- **Attribution headers** `HTTP-Referer` and `X-Title` are sent only when configured
  (`jclaw.openrouter-referer`, `jclaw.openrouter-title`) — a blank header would still be sent and
  still be attributed.

Worth knowing: reaching **Claude models through OpenRouter uses the OpenAI-compatible shim**, not
the Anthropic SDK, so adaptive thinking, the native tool-call shape, and Anthropic-specific
features are unavailable. For Claude specifically, `jclaw.provider=anthropic` is the better path;
OpenRouter earns its place for breadth and for models with no first-party adapter.

**Tool names are encoded for the wire.** Capability ids are dotted (`builtin.read_file`) because
the namespace is what stops a third-party tool impersonating a built-in — but both the
OpenAI-compatible and Anthropic function-name schemas require `^[a-zA-Z0-9_-]{1,64}$`. `ToolNames`
encodes the dot to a single underscore (`builtin_read_file`) on the way out and decodes by
**lookup against the tools actually sent** on the way back, never by parsing: `x.a_b` and `x.a.b`
encode identically, so `wireNamesFor` refuses to build an ambiguous mapping rather than let one
capability silently shadow another. Sending a dotted name gets the entire request rejected with a
400 — which is exactly the bug that made every tool-carrying request fail. The encoding was
originally a double underscore (`builtin__read_file`); that is legal on every API but measurably
breaks small local models — qwen2.5/qwen3 via Ollama called `builtin_glob` 3/3 and
`builtin__glob` (or dotted names) 0/3, emitting a malformed call the server drops silently, which
surfaces as an empty reply with a nonzero completion-token count. Wire names must look like names
the models were tool-tuned on.

Credentials are resolved **per request, not at construction**. A provider bean that threw while
being wired would take the whole context down — including `doctor`, the one command whose job is
to report that the credential is missing. A missing key now surfaces as
`AUTH (OPENROUTER_API_KEY is not set)` in the event log and a failed `doctor` check.

### REPL line editing

`repl` uses JLine in emacs mode: Up/Down history, Left/Right, Ctrl-A/Ctrl-E, Ctrl-K, plus the rest
of emacs mode (Ctrl-W, Ctrl-U, Ctrl-Y, Alt-B/F). History persists to `<state-dir>/repl-history`;
`--no-history` disables it, and a line typed with a leading space is not recorded (the usual shell
convention).

**Slash commands** (`/help`, `/tools`, `/thread [id]`, `/new`, `/stream [on|off]`, `/exit`,
`/quit`) are session controls, never sent to the model. One enum — `ReplSlashCommand` — feeds
dispatch, completion, and `/help`, so the three can't disagree. Typing `/` as the first character
lists the commands immediately: the slash key is bound to a widget that self-inserts and invokes
JLine's `list-choices` when the buffer is exactly `/` (a slash elsewhere in a line is prose and
just inserts). Tab completes with descriptions. A line starting with `/` that matches nothing gets
a hint, not a model turn. This has no IronClaw counterpart — IronClaw's REPL documents no slash
commands; its Slack/Telegram surfaces get platform-native ones.

Two non-obvious details, both load-bearing:

- **Arrow keys are bound in both cursor modes.** JLine binds them from terminfo `kcuu1`, which on
  xterm-family terminals is the *application* form `ESC O A` only — correct after JLine emits
  `smkx`, but nothing at all when a terminal or multiplexer ignores that. `ReplCommand` binds the
  normal-mode `ESC [ A` form too. `ReplBindingsTest` asserts both, and was verified to fail when
  the extra binding is removed.
- **Event expansion is disabled.** JLine defaults to bash-style `!` history expansion, which would
  silently mangle prose prompts containing an exclamation mark.

Piped input still works: the terminal is built with `dumb(true)`, so a script or test gets plain
line reading rather than a failure.

Spring config is passed as `--jclaw.*` arguments or `JCLAW_*` env vars. `JclawApplication` strips
`--jclaw.*` / `--spring.*` before handing argv to picocli, since both frameworks see the same
arguments. User config lives at `~/.jclaw/jclaw.yaml` (imported optionally).

The `mock` provider is the default and needs no key. `jclaw.mock-script` scripts it
(`text:<reply>` / `tool:<capability>:<k=v,k=v>`), which is how the CLI — including tool calls and
the whole approval flow — is exercised without a network.

## Architecture

Hexagonal, with a pure functional core, mapping onto IronClaw's seven-layer ladder:

```
contracts   →  (jackson-annotations)     ports, turn vocabulary, refs, LoopExit, Result,
                                         ThreadLock, EmbeddingProvider, content blocks (incl. Image)
domain      →  contracts                 PURE: TurnMachine, Budget, Redaction, RrfFusion,
                                         MemoryRanking, VectorRanking, ContextCompaction,
                                         ContextSummary, InjectionHeuristics, RunScheduling,
                                         RateLimit, Retention, RunProjection, SandboxSpec,
                                         CronSpec, RoutineSchedule, PromptAssembly, LeaseRecovery
kernel      →  contracts, domain         CapabilityHost, CapabilityPolicy, ToolScopedContext,
                                         Workspace/Egress guards
loop        →  contracts, domain, kernel EffectInterpreter — the ONLY place an effect happens
providers   →  contracts                 mock, Anthropic (official SDK), OpenAI-compatible chat
                                         and embeddings (OpenAI / OpenRouter / Ollama / local),
                                         failover
tools       →  contracts, domain, kernel file, shell (host or docker), http, memory, skill,
                                         trigger, subagent, MCP client + capabilities
storage     →  contracts, domain, kernel row stores (JSONL files or one SQL table via
                                         RowStore): events, transcript, approvals, checkpoints,
                                         runs, results, memory, routines, mcp, secrets; SqlSchema
                                         migrations; file thread locks;
                                         filesystem skill catalog
app         →  all of the above          Spring wiring, picocli CLI, JclawRuntime, TurnRunScheduler,
                                         RoutineRunner, RecoveryService, RetentionService,
                                         Attachments, JclawHttpServer
```

### The five ideas worth preserving

**1. The loop is a pure function.** `TurnMachine.step(state, observation, policy, now)` returns
`(nextState, decision)` and performs no I/O — no sockets, no clock reads, no ports. A `LoopFamily`
is another such function over the same state and decisions (`reflective` reviews a draft before
persisting it); `LoopHook`s narrow or veto effects at the interpreter, never widen them.
`EffectInterpreter` is the single component that executes decisions. The agent's entire control
flow is testable with plain values and no mocks, replay is exact, and the complete set of effects
an agent can cause is five constructors in `LoopDecision`.

**2. `LoopExit` is an untrusted claim.** A driver returns refs; `JclawRuntime.validate` re-resolves
every ref against the store that minted it before any durable transition. A fabricated ref yields
`DRIVER_PROTOCOL_VIOLATION`, not a completed turn.

**3. `CapabilityHost` is the single authority gate.** Order is a security property: existence →
policy denial → approval (exact-invocation fingerprint) → dispatch → redact, bound, store, mint
ref. Approvals are keyed by `scope + fingerprint`, so approving one `shell` command does not
approve the next.

**4. Resume re-authorizes; it never assumes.** A parked run rehydrates its checkpoint and
*re-dispatches* the gated capability, so the kernel asks the approval store again. Denying a gate
and resuming produces a denial the model is told about, not an effect. There is a test for exactly
this (`ApprovalResumeIntegrationTest#resumeWithoutDecisionParksAgain`).

**5. Recovery never guesses in the permissive direction.** When a worker dies holding a lease,
`LeaseRecovery` decides — purely — whether the run may be replayed. Only a checkpoint proven
side-effect-free (`BEFORE_MODEL`, `BEFORE_BLOCK`, `BEFORE_CAPABILITY`) is requeued, and only after
a full lease TTL of grace so a merely-slow worker is not duplicated. Everything else, including an
unknown checkpoint kind, becomes a terminal sanitized failure the user resubmits explicitly.
Automatic retry of side-effecting work is never correct: a duplicated write cannot be un-written.

### The architecture is enforced, not described

`DependencyLawTest` machine-checks the layer ladder and the invariants below with ArchUnit, the
way IronClaw checks its own matrix in `ironclaw_architecture_tests`. The rules were verified to
fire by planting deliberate violations, not just by passing. Among them: the domain may not read
a clock or use randomness, the loop may not name an adapter, only the anthropic package may import
the Anthropic SDK, and tool lanes may not read the process environment.

### Invariants

- `contracts` — no Spring, no Jackson databind, no `java.sql`, no HTTP.
- `domain` — total, deterministic functions; the clock is a parameter, never read.
- `loop` — ports only; never imports `providers`, `tools`, or `storage`.
- **No credential is ever written to a store.** The vault is the only place a value lives. An
  extension or MCP server records the *name* of a vault entry per environment variable, and
  `McpCredentials` leases the values in the app layer when the server starts; an HTTP server's
  bearer token is leased the same way. A secret used this way must be bound to `mcp.connect`,
  and to the declared hosts when the package declares any.
- `tools` — no adapter holds a `SecretVault` handle; `HandlerContext` has no method to obtain
  one. There are two handoffs, both decided by the kernel and neither reversible by a lane.
  **Substitution:** the host replaces `{{secret:NAME}}` in the arguments a lane receives at
  dispatch, only when the secret's binding names that capability and every URL host in the
  arguments. **Staging:** for a `secret_env` request the host puts values on
  `HandlerContext.stagedEnvironment()` — a map for this one call, chosen by the request and
  permitted by a binding whose sole host is the reserved `*`, so a child process gets a
  credential without it ever becoming a command line. `stagedEnvironment` is values, not a
  lookup: a lane cannot ask for what it was not given, ask twice, or enumerate. The two are
  disjoint because `Binding.permitsHost` never matches `*` — a staged secret cannot be
  substituted and a host-bound one cannot be staged. Either way the fingerprint, approval
  prompt, checkpoints, and events keep the name, and the leased value joins that call's
  redaction set.
- The vault is per tenant (`SecretVaults.forTenant`), resolved from the invocation's
  `TurnScope` at dispatch. The tenant is fixed at admission, so naming another tenant's vault is
  not a check that could fail — there is no argument that expresses it.
- Events carry ids, refs, enums, and counters. There is no record component for a prompt, a tool
  argument, or a host path — redaction is structural, not remembered.
- `HandlerError` separates `Denied` (a guard refused) from `Failed` (the lane broke). Collapsing
  them hides blocked attacks among ordinary I/O errors.
- `CheckpointKind.replaysNoSideEffect()` is the sole predicate deciding whether an expired lease
  may be requeued. Unknown kinds fail closed.
- The run profile (model, system prompt) is resolved at admission and stored on the run, so a
  resume replays it rather than picking up whatever config says later.
- Memories and routines are **project**-scoped, not thread-scoped. Retrieved memories re-enter
  prompts, so cross-project leakage would be a prompt-injection vector.
- **Trust classes split host-shipped from third-party.** For `SYSTEM`/`FIRST_PARTY`, the operator's
  `CapabilityPolicy` is authoritative — host code is as trustworthy as the host. For `VERIFIED`,
  `COMMUNITY`, and `UNTRUSTED`, the trust ceiling binds and policy may only tighten it. This is why
  **every MCP tool requires approval in every mode, including `trusted`**: MCP servers register as
  `COMMUNITY`, whose ceiling is `PURE`, and no setting can raise it.
- Subagents are **child runs on the same machinery** — same turn machine, same interpreter, same
  `CapabilityHost` — never a second private engine. Nesting depth is derived from the thread id
  (`parent~sub1`) rather than passed as a parameter, so a model cannot understate its own depth.
  **A child is admitted under its parent's tenant**, through the tenant-taking overloads of
  `submit`/`enqueue`. Both paths used the three-argument forms, which resolve to `LOCAL_TENANT`,
  so every child of every tenant ran as the operator: it was handed the *local* vault at dispatch
  (`DefaultCapabilityHost` resolves `vaults.forTenant(scope.tenant())`), raised its gates in the
  operator's scope, and charged its tokens to the operator's budget — a credential boundary
  crossed by delegating, not by any argument a model could write. `SubagentIntegrationTest`
  and `AsyncSubagentIntegrationTest` pin both paths, each verified to fail with the tenant
  dropped again.
- **One active run per thread.** `JclawRuntime.submit` and `resume` take a `ThreadLock` before
  the inbound message is written, so a refused submission leaves no trace and two runs can never
  interleave one transcript. Refusal is `THREAD_BUSY`. The implementation follows the storage
  backend, and the two differ in a way worth knowing: `FileThreadLock` (JSONL) is an OS file lock
  that dies with its process, so there is no TTL to reason about but the guarantee stops at one
  host; `SqlThreadLock` (`storage=sql`) is a row, so it spans hosts but is a *lease* the holder
  renews, and a host frozen past the lease can be displaced. Acquisition is a primary-key insert
  either way, so live contenders are always resolved atomically.
- **Configuration can only tighten policy.** `jclaw.denied-capabilities` adds hard denials on top
  of the approval-mode posture and removes the tool from the published surface; the egress lists
  narrow `EgressGuard` and never bypass its private-network or metadata checks. Both are
  validated at startup so a typo fails loudly instead of denying nothing.
- **Queued runs see the conversation as of their submission.** `JclawRuntime.enqueue` makes the
  inbound message and a `QUEUED` record durable and nothing else; `resume` (which now also starts
  queued runs) seeds from the transcript minus any user message accepted after the run was
  submitted, with the run's own message placed last. Several turns queued on one thread therefore
  answer in order, each seeing the replies before it. `TurnRunScheduler` only decides when, through
  the pure `RunScheduling`; execution still goes through the thread lock and the lease.
- **Auth failures park, they do not fail.** A provider `AUTH` failure makes the interpreter raise
  a durable auth gate (an `ApprovalStore.Gate` of kind `AUTH` whose fingerprint is the credential
  hint) and hand the machine `Observation.AuthRequired`; the machine checkpoints `BEFORE_BLOCK`
  and parks `BLOCKED_AUTH`. Nothing was appended, so resume goes straight back to the model call.
- **Injection heuristics frame, they do not authorise.** `InjectionHeuristics` is pure and runs in
  `DefaultCapabilityHost.succeed` after redaction, bounding, and storage: the audit payload is what
  the tool returned; only the model-facing summary is fenced, defused, or, under `block`, withheld
  as a `Denied` outcome. The authority gate above it is still what stops an injected instruction
  from becoming an effect.
- **Summarisation is an effect the machine decides on.** With `ContextPolicy.summarise()`, the
  `BEFORE_MODEL` checkpoint is followed by a `CallModel(request, userFacing=false)` built by the
  pure `ContextSummary` from the span `ContextCompaction` would drop; the reply rewrites the
  state's messages (`AWAITING_SUMMARY`) and the real call follows. The interpreter never streams
  a non-user-facing call. A failed summary degrades to truncation; the effect vocabulary is
  still five constructors.
- **Per-tool limits narrow, never widen.** `CapabilityPolicy.toolEgress` is applied by
  `ToolScopedContext` after the host context's own check; `CapabilityPolicy.rateLimits` is a
  sliding window per process, checked before approval (so a loop cannot flood a human with gates)
  and counted at dispatch (so a parked call spends no permit).
- **A gate is one question.** `ApprovalStore.findGrant` returns the latest gate decided or not:
  a decision applies, an unexpired open gate re-parks the run on the same gate, an expired one is
  asked afresh. `Gate.isExpiredAt` is true only for undecided gates; grants never lapse.
- **Tests must pin what `~/.jclaw/jclaw.yaml` could change.** The app imports the user config
  file in tests too, so a test that depends on the approval mode or provider sets it explicitly.
- **Retention drops only what nothing depends on.** `Retention.expendable` is true only for a
  row older than the store's age whose run is terminal; an unknown run counts as unfinished.
  `RetentionService` rewrites results, events, and checkpoints atomically and never the
  transcript. The rewrite has a millisecond window against a concurrent append from another
  process; it is an audit row at most, never run state.
- **A process gate is a lane saying "not yet".** `HandlerError.Waiting` makes the kernel raise
  (or reuse) a `PROCESS` gate keyed by the invocation; the run parks `WAITING_PROCESS`; the
  scheduler requeues a parent when a child on a `~sub` thread finishes; the re-dispatched call
  reports the outcome. Only subagents use it today, and only with `jclaw.subagents-async`.
- **Attachments are part of the message.** `ContentBlock.Image` is written to the transcript with
  the text around it, weighs a fixed ~1600 tokens in the context estimate, and travels as a native
  block (Anthropic) or a data-URL part (OpenAI-compatible). `Attachments` refuses what it cannot
  extract rather than sending bytes the model cannot read.
- **The HTTP surface never executes in the request.** `JclawHttpServer` enqueues and reads;
  `TurnRunScheduler` executes. Projections are folded from the event log on demand
  (`RunProjection`), and the SSE stream is the same log followed by polling. One bearer token,
  loopback by default.
- **The sandbox is a contract, selected by configuration.** `SandboxSpec.argv` is the whole
  `docker run` vector; `ShellTool` runs it under the same timeout, environment scrub, and output
  bound as the host backend. Only the shell lane is sandboxed.
- **The context policy is a view, not a truth.** `ContextCompaction` derives what the model sees
  from the full history under `LoopPolicy.context()`; the loop state and transcript keep
  everything. It is applied at admission (bounding the checkpoint) and by `TurnMachine` before
  every model call (bounding a tool-heavy run), and it is idempotent: its own notice is free of
  charge and its count carries forward.
- **Vector similarity is additive.** `MemoryRanking` fuses BM25, recency, and, only when a query
  embedding is supplied, `VectorRanking`; embeddings from a different model never compare
  (`Embedding.sameSpaceAs`), and an embedding failure degrades to two rankings rather than failing
  the write or the search. The store never embeds; callers go through `MemoryTools` so the CLI and
  the tool share one path.

## Notes for future sessions

- **Jackson 3, not 2.** Boot 4 moved databind to `tools.jackson.core`. Do not add
  `com.fasterxml.jackson.core:jackson-databind` — it resolves to the 2.x line and will not match
  the mapper Boot auto-configures. `jackson-annotations` correctly stays on the old groupId.
  Jackson **2** is on the classpath transitively via the Anthropic SDK; the two coexist.
- **Codecs are hand-written on purpose** (`EventCodec`, `MessageCodec`, `JsonLoopStateCodec`): no
  reflection for native image, no accidental field disclosure, and a wire format decoupled from
  the records. Adding a field to an event requires editing its codec — that is the point.
- **Native metadata for the Anthropic SDK is captured, not authored.**
  `jclaw-app/src/main/resources/META-INF/native-image/io.jclaw/anthropic-sdk/` came from the
  GraalVM tracing agent. Without it, native builds fail at request serialization with a Jackson
  `InvalidDefinitionException`. **Regenerate after any SDK upgrade:**
  ```bash
  ANTHROPIC_API_KEY=dummy $JAVA_HOME/bin/java \
    -agentlib:native-image-agent=config-output-dir=jclaw-app/src/main/resources/META-INF/native-image/io.jclaw/anthropic-sdk \
    -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar run capture --jclaw.provider=anthropic
  ```
  A dummy key suffices — serialization happens before the auth failure. The capture predates
  image attachments: to send images through the Anthropic adapter from the native binary,
  recapture with `run --attach some.png` so the image block types are registered.
- **Test fakes must be at least as strict as the real API.** The original OpenRouter fixture
  accepted any function name and so passed while every real request was rejected. It now validates
  names the way OpenAI does, and that check was verified to fail when the encoding is removed.
- **The OpenAI-compatible adapter pins HTTP/1.1.** Java's `HttpClient` defaults to HTTP/2, which
  on plain `http://` URLs sends an h2c upgrade (`Connection: Upgrade` + `Upgrade: h2c`) with every
  request. llhttp-based servers — uvicorn's httptools mode, i.e. vLLM and anything on
  `uvicorn[standard]` — pause body parsing on any Upgrade header, so the app receives an empty
  body and FastAPI answers `{'type': 'missing', 'loc': ('body',), 'msg': 'Field required'}`.
  Plain-h11 uvicorn tolerates it, which is why a naive fixture (and the strict one here, which
  reads by Content-Length) passes either way; `doesNotAttemptH2cUpgrade` asserts the headers are
  absent and was verified to fail when the version pin is removed.
- **A container for the native image needs a shell.** `builtin.shell` runs `/bin/sh -c`, so a
  distroless runtime base would ship an agent with one of its own tools permanently broken.
  `Dockerfile.native` uses `debian:12-slim` for that reason, and glibc rather than Alpine because
  the image is not statically linked. It also has to build the binary *inside* Docker: the one
  the `native` profile produces on a developer's machine is for that machine.
- **JLine needs a native-access grant, declared twice.** It loads a native library to put a TTY
  into raw mode, which JDK 24+ restricts. The jar gets it from `Enable-Native-Access: ALL-UNNAMED`
  in its manifest (`maven-jar-plugin`); the native image has no manifest, so the same grant is a
  `--enable-native-access=ALL-UNNAMED` build arg in the `native` profile. Drop either and the REPL
  prints warnings now and fails on a future JDK.
- **A `@Bean` method must not return `Optional`.** On the JVM it works; in the native image it
  takes the whole context down at startup on *every* command. Under AOT the bean comes from a
  generated instance supplier, and `obtainFromSupplier` wraps the result in a `BeanWrapperImpl`
  whose constructor runs `ObjectUtils.unwrapOptional` before asserting the target is non-null —
  so `Optional.empty()` becomes null and fails the assert, and `Optional.of(x)` is silently
  unwrapped and registered under the wrong type. Carry the absence in a record instead
  (`LoginProvider`). `DependencyLawTest.AotInvariants` enforces this; it was added after the bug
  shipped green through 302 tests.
- **Do not catch `SpringApplication.AbandonedRunException` in `main`.** Spring throws it to abort
  the run during AOT processing. Treating it as a startup error makes `mvn -Pnative` fail with a
  misleading message; `JclawApplication` rethrows it explicitly.
- **Startup-failure logging is suppressed** for two named Spring loggers so a config mistake prints
  one line instead of a 40-frame trace. Restore while debugging with
  `--logging.level.org.springframework.boot.SpringApplication=ERROR`.
- **The browser UI sends on Enter, and that is a portability fix, not a preference.** The page
  shipped advertising `Ctrl+Enter` while binding `ctrlKey || metaKey`. On a Mac the send chord is
  `⌘↩`, so a user followed the placeholder, pressed a chord macOS routes into its own emacs-style
  text bindings, and got nothing — the handler was fine and the label was wrong, which is
  indistinguishable from a broken UI. Binding plain Enter (Shift+Enter for a newline, as every chat
  client does) deletes the platform question; `⌘↩` and `Ctrl+Enter` still work because neither sets
  `shiftKey`. Once plain Enter is bound, `isComposing` **and** `keyCode === 229` must both be
  checked first, or Enter committing an IME candidate also submits the half-typed message.
  `WebUiTest` pins the decisions in the source; `./scripts/web-ui-keys.sh` dispatches real
  `KeyboardEvent`s at the real served page in headless Chrome, and both were verified to fail with
  the original handler planted back.
- **The UI read every non-401 response as success.** `api()` threw on 401 and otherwise returned
  `r.json()`, so a turn refused on a busy thread (409 `THREAD_BUSY`) cleared the textarea, reported
  `run undefined queued`, and opened an event stream for a run that does not exist. Found by the
  browser harness above, not by reading. It now throws on any non-`ok` status, and `send()` clears
  the box only after the post succeeds — clearing first loses what the user typed on every refusal.
- **picocli collection-typed `@Parameters` fail under Spring singletons** with a bare
  `UnsupportedOperationException`. Use arrays (`String[]`), as `RunCommand` does.
- **`@DefaultValue("")` on a `List` property binds to `[""]`, not `[]`.** Filter blank entries.
- **Provider base URLs deliberately bypass the egress guard.** The guard stops *model-controlled*
  URLs reaching internal addresses; a base URL is operator configuration, and pointing it at
  `localhost:11434` for Ollama is the intended use. This is documented in
  `OpenAiCompatibleModelProvider`.
- **Every store is durable, as JSONL under `jclaw.state-dir` or as rows in SQL**
  (`jclaw.storage=sql`; embedded H2 by default, PostgreSQL by URL). The store classes keep their
  `Jsonl*` names because they still speak in one-JSON-document-per-row; they are written against
  `RowStore`, and `StorageBackend` picks the medium once. `results.jsonl` holds full capability
  payloads. Result refs are evidence: `JclawRuntime.validate` re-resolves each one in a
  `Completed` exit, and a run resumed in a second process completes with refs the first minted.
- **Every new `.java`, `.sh` and `.py` file needs the two-line SPDX header**, before the `package`
  declaration (or directly after the shebang), with a blank line after it:
  ```java
  // SPDX-FileCopyrightText: 2026 David Greco
  // SPDX-License-Identifier: Apache-2.0
  ```
  A file copied out of this tree then carries its licence with it, which a root `LICENSE` alone
  cannot do. `scripts/license-check.sh` enforces it in the `verify` CI stage — on a bare debian
  image, so a missing header fails in seconds rather than after a compile — and also asserts
  `LICENSE`, `NOTICE`, and the root POM's `<licenses>` block, which is the declaration that
  actually reaches a consumer of the jar. It searches only the first five lines: an SPDX string
  further down a file is a coincidence (a fixture, a javadoc quote), not a header. Verified to
  fire by stripping a real header, not just by passing.
- **A WASM module keeps the initial memory it declared.** `WasmLane` used to instantiate every
  module with `MemoryLimits(1, maxMemoryPages)`, which overrides the *initial* size, not just the
  cap. Every toolchain lays out a shadow stack before the module's own data — Rust's default is
  1 MiB — so a real module declares 17+ pages before holding a byte of its own, and starting it
  at one page fails on the first data segment above 64 KiB. That surfaces as
  `module_not_instantiable`, which reads exactly like an ungranted import, so the wrong thing
  gets debugged. It shipped green because the only test module was hand-assembled and fitted in
  one page: **no module any toolchain produced could run**.
  `WasmLaneTest.honoursDeclaredMemory` pins it against a module shaped the way a toolchain emits
  one, and was verified to fail with the initial size pinned back to 1.
- **The WASM runtime is Endive, not Chicory.** Endive is Chicory rehomed under the Bytecode
  Alliance — same codebase, same API, `com.dylibso.chicory` → `run.endive` for both the groupId
  and the packages, and two exception renames jclaw does not use. The move cost one file, because
  `WasmLane` is the only thing that ever imported the runtime; `DependencyLawTest.wasmRuntimeContained`
  now makes that true by construction rather than by habit. Both properties that mattered were
  verified rather than assumed: `withUnsafeExecutionListener` survives the fork, so instruction
  metering still works, and the native image builds and runs the lane end to end. Endive's
  `redline` backend compiles through Cranelift and ships prebuilt native binaries; it is
  experimental and opt-in, and turning it on would cost exactly the pure-Java property this
  dependency was chosen for.
- **A fan-out of subagents is sequential, and the interpreter is what makes it so.**
  `EffectInterpreter.invokeCapabilities` stops dispatching a batch at the first
  `NeedsApproval` — running further effects after deciding to park is the duplicated work
  checkpointing exists to prevent — and `DefaultCapabilityHost.waitOn` turns a lane's `Waiting`
  into exactly that outcome. So N `spawn_subagent` calls in one reply start one child, park,
  resume, and start the next, in async mode as well as sync. Concurrency comes from *independent
  runs*: `TurnRunScheduler` claims each queued run onto its own thread up to `--concurrency`,
  which is what a webhook `topic` fan-out produces from one POST. `examples/skill-fanout`
  demonstrates both halves and says which is which. Related: the mock provider's script is now a
  `ConcurrentLinkedDeque`, because `serve --concurrency N` polls one provider bean from N threads
  and a lost turn surfaces as "mock script exhausted" in a run that did nothing wrong
  (`MockModelProviderTest`, verified to fail with the plain `ArrayDeque`).
- **`WasmSpec` validates by construction, and `parsePermissions` only normalises.** A manifest's
  permissions are checked by building a spec from them and throwing the spec away — which reads
  exactly like a mistake, and SpotBugs calls it one (`RV_RETURN_VALUE_IGNORED_INFERRED`).
  "Simplifying" it to the parse call alone silently deleted the check, and only
  `WasmExtensionIntegrationTest.refusals` noticed. The javadoc that invited the mistake said
  parsing rejected unknown permissions; it now says what it does.
- **CPD finds three duplications and two of them stay.** `LoopFamilies.REFLECTIVE` was a
  copy of what `reviewing(id, instruction)` builds and is now a call to it — but note the ordering
  trap that made the copy tempting: the constant it passes has to be declared *above* the field,
  or the initializer reads it as null and class initialization throws. The other two — the OAuth
  token POST shared in shape by `OidcLogin` and `McpOAuth`, and the HTTP send in the two
  OpenAI-compatible providers — are left duplicated on purpose: about a dozen lines each, and
  folding them together would couple an identity flow to an MCP flow to save less than it costs.
- **Coverage is in the ordinary build, and the number has one source.** JaCoCo runs on every
  `mvn verify`; `report-aggregate` in jclaw-app produces the whole-tree figure, which works
  because that module depends on every other one, so no module exists solely to hold a report.
  Note what GitLab cannot do: without Pages it refuses to render an HTML artifact in the browser
  at all, because inline artifact serving is the Pages daemon's job. Markdown it does render, so on an instance without
  them `coverage-wiki` writes the report to the project wiki and the badge opens that; the
  per-line view is the merge request diff, painted from the Cobertura report. The wiki job is
  opt-in — CI_JOB_TOKEN cannot write a wiki, so it needs a project access token in `WIKI_TOKEN`
  and its rule simply does not match without one. Two things the rehearsal caught before it ever
  ran: a wiki that exists but has no page clones fine with an unborn HEAD, where
  `rev-parse --abbrev-ref HEAD` answers the literal "HEAD" and the push refspec becomes nonsense
  (`symbolic-ref --short` is right), and an unchanged report must not produce an empty commit.
  Read the aggregate and ignore the per-module figures: most of `contracts`, `kernel` and
  `tools` is exercised by integration tests that live in `jclaw-app`, so their own reports say
  5-16% while the aggregate — which credits a class wherever it was executed — says 73%.
  `scripts/coverage.sh` prints the single line both pipelines publish — GitLab scrapes it with
  the `coverage:` keyword for the MR widget and the badge, GitHub appends it to the run summary
  — so the two cannot report different numbers. The script pins `LC_ALL=C`, because awk formats
  73.3 as "73,3" under an Italian locale and the GitLab regex expects a dot.
- **The JaCoCo-to-Cobertura converter is in-tree, and it reads `<group>`.** GitLab paints
  coverage onto a merge request diff from a Cobertura report and reads nothing else; JaCoCo does
  not emit one. The published converter is an amd64-only image with no versioned tag, against an
  arm64 runner, so `scripts/jacoco-to-cobertura.py` does it in stdlib python3 instead. The trap it
  fell into first is worth remembering: an *aggregate* report nests its packages inside a
  `<group>` per module, so reading only top-level `<package>` elements produces a report with
  correct totals and no classes at all — valid XML that annotates nothing. It is verified by
  diffing against the reference converter's output rather than by looking right: same 219 files,
  same 12,187 line entries, same rates.
- **Two CI pipelines, kept in step by hand.** `.gitlab-ci.yml` (the live remote) and
  `.github/workflows/ci.yml` run the same six jobs; a change to one needs the same change to
  the other, and nothing checks that. Each also has a seventh job that publishes the JaCoCo
  report to its own Pages — `coverage-pages` on GitHub, `pages` on GitLab — so a reader is never
  sent across to the other remote for a number this one computed. The coverage badge opens the report belonging to
  whichever host is rendering it, which one file cannot do: **GitHub renders `.github/README.md`
  in preference to the root one, GitLab renders only the root one**, so there are two, generated
  from one body by `scripts/readme-sync.sh`. Edit the root file and run the script; both
  pipelines' verify stage runs `--check` and fails on drift. The generator also prefixes every
  relative link with `../` in the GitHub copy, because that file lives a directory down and
  `LICENSE` there would mean `.github/LICENSE`. Where they differ it is deliberate and commented at the
  step: GitHub's runners have a Docker socket, so the dind service and its three cleared TLS
  variables are absent; its `postgres` service gates on `pg_isready`, so the `/dev/tcp` wait
  loop is absent; `setup-graalvm` installs the toolchain, so there is no ENTRYPOINT to override.
  What GitHub genuinely lacks is `reports: junit:` — the totals line goes to the run summary and
  the XML is an artifact, because the alternative is a third-party action in a repository that
  verifies its own extension signatures.
- **The native image sizes its heap from `MemTotal`, and on the GitLab runner `MemTotal` is
  fiction.** The `native-image` job failed on most pipelines with "The Native Image build
  process ran out of memory", which reads like a flaky runner, reads like "use a bigger
  machine", and is neither. The VM reports 11.6 GiB, but something on it holds ~7.6 GB before
  the job starts, so the build actually has **~4.4 GiB** — printed by the `grep /proc/meminfo`
  step kept in that job, because no other line in the log reports it. native-image plans
  against the first number and is killed against the second. There is no cgroup limit in play;
  that was checked, `memory.max` reads `max`.
  **Three fixes failed before this one, and the failures are the useful part.**
  `--parallelism=4` alone changed nothing (the job OOMed at the same stage with `4 thread(s)
  ... set via '--parallelism=4'` in the log), so it is margin, not a fix. `-J-Xmx8g` then
  looked right for three green pipelines — and was only ever right for *that* host, because
  the runner was later resized from ~16.7 GB to 12.2 GB and the same unchanged setting went
  from 48% of the host to 64% and brought the OOM straight back. `-J-Xmx6g` then died at
  `[2/8]` with a 4.49 GB peak against its own 5.73 GB ceiling — a builder killed from outside,
  which is what finally ruled out the heap-exhaustion story. **Any ceiling above what the
  machine can supply is the same bug wearing a smaller number**, so stop tuning it and read
  `MemAvailable`.
  The value is measured at both ends, on the same architecture and GraalVM major: a 6 GB
  ceiling peaks at 3.75 GiB, 4 GB peaks at 3.42 GiB, 3 GB fails outright. A *tighter* ceiling
  lowers the peak, because the difference is garbage the GC had no reason to collect. Both
  pipelines set `NATIVE_IMAGE_OPTIONS: "--parallelism=4 -J-Xmx4g"`, and the GitLab job also
  caps `MAVEN_OPTS` with `-Xmx512m` — Maven shares those 4.2 GiB and was defaulting to a
  quarter of `MemTotal` for a job that resolves a POM and forks a subprocess.
  **Nothing reduces the ~4 GB requirement itself, so `--parallelism` is not a memory knob.**
  `-J-Xmx3g` fails at `--parallelism=1`, `2` and `4` alike, so the memory is the analysis and
  universe structures rather than per-thread compilation state; `-Ob` quick-build fails at 3g
  too. The flag is retained only to leave half the cores to whatever else shares the box, at
  roughly 25% build time. Earlier comments in both CI files claimed it held the working set
  down; that was never measured and is wrong.
  **This fit is tight by necessity and is a stopgap.** ~3.4 GiB of builder plus ~0.5 GiB of
  Maven against ~4.4 GiB available holds only while `MemAvailable` stays there; a dependency
  that grows the image will break it again. The durable fixes are a larger VM or moving
  whatever holds the other 7.6 GB off that host.
- Git: initialized on `main` (September 2026). `.gitignore` excludes `target/`, IDE files, `.claude/settings.local.json`, and `.byte-manifest`; the captured native-image metadata is versioned on purpose.

## Not built yet

Honest gaps against IronClaw's surface. jclaw is ~32k lines against IronClaw's ~1.4M; the
architecture and most runtime mechanisms are equivalent, the breadth is not. PARITY.md section
17 ranks these; section 16 records what the closed items actually delivered.

- **Fencing a frozen host** — with `storage=sql` two hosts now exclude each other: the thread
  lock is a row (`SqlThreadLock`), the scheduler counts the deployment's running runs rather than
  its own, and a run is claimed at selection so `RunStore.claim` arbitrates the race. What is
  missing is fencing. A row cannot vanish when a process dies, so it is a lease the holder
  renews, and a host frozen past it — a long GC pause, a suspended VM, a partition — can be
  displaced; if it then resumes and writes, two runs can interleave one transcript. A fence token
  threaded through every durable write would close it. Live hosts exclude each other correctly;
  a frozen one is the hole.
- **Channel adapters beyond two** — Slack and Telegram work over `POST /channels/{adapter}`,
  with reply-target bindings that survive gates and restarts. There is no Discord, Matrix, or
  email adapter, and no outbound-initiated message: jclaw answers, it does not start a
  conversation.
- **Identity beyond one provider** — sessions, three roles, an OIDC authorization code flow with
  PKCE, per-tenant policy and token budgets, and agents that give `TurnScope.agent()` meaning.
  There is no user directory and no group or team — roles are per-user strings in
  configuration, so a team of thirty is thirty lines — and the id token's signature is not
  verified because the code flow authenticates it by channel.
- **Secret custody breadth** — values are leased into arguments under a capability + host
  binding, staged into a child's environment under a `--subprocess` binding, scanned out of
  outbound model requests by the optional `secret-leak-scan` hook, and held in a vault per
  tenant. Every tenant's vault shares one encryption key, there is no rotation or expiry, and
  the leak scan is an exact-substring match that ignores values under eight characters.
- **SQL breadth** — `storage=sql` gives the busy stores a table each and shares `jclaw_rows`
  for the small ones, materialises a finished run's projection into `jclaw_run_projection`, and
  pools connections through HikariCP. Rows are still JSON documents rather than typed columns,
  the only materialised projection is the run view, and there is no read replica or partitioning.
- **A sandbox orchestrator** — shell commands and MCP servers run in containers, and WASM
  extensions run in-process under Endive with metered instructions, capped memory, and only the
  host imports their manifest asked for. There is no orchestrator with per-job tokens, no LLM
  proxying through the host, and a sandboxed MCP server's network is all-or-nothing rather than
  host-mediated per host.
- **Extension registry breadth** — packages install from a directory or from a static
  `index.json` registry (`search`, `add`, `outdated`, `upgrade`), with versioned upgrades,
  profiles, and three kinds (skill, MCP, WASM). There is no channel package kind, no
  publisher-side `publish` command, and no signed index — the index is trusted only for the
  digest, which the download must then match.
- **Pipeline-stage breadth** — hooks cover prompt assembly, pre/post model, pre/post capability,
  and gate raising, and a `reflective`-based family can be defined in `jclaw.loop-families`.
  There is no hook on checkpoint writes or exit validation, a configured family can only vary
  the review instruction (not the pass count, which would need a checkpoint codec change), and
  hooks are still Java beans rather than anything loadable at runtime.
- **Live instrumentation** — metrics (`/metrics`, Prometheus text, with real latency histograms)
  and traces (`/runs/{r}/trace`, OTLP/JSON, optional export to `otlp-endpoint`) are projections
  of the event log, and outbound model and MCP-over-HTTP calls carry a W3C `traceparent` naming
  the run's own trace and span. There is still **no OpenTelemetry SDK in the process**, which is
  a decision rather than an omission: spans are folded from the audit log, so they cannot
  disagree with it, and an SDK would add a second in-memory span model to keep in step plus a
  reflective dependency the native image would have to be taught. What that costs is the SDK's
  auto-instrumentation and its exporters' batching and retry; the OTLP/JSON export here is
  best-effort and unbatched.
- **Trigger breadth** — a routine fires on cron, an interval, a webhook (`POST /hooks/{name}`,
  bearer secret, SHA-256 stored, optionally fanning out to a `topic`), a filesystem watch
  (polled on the worker tick, fingerprint in `watches.jsonl`), or an audit event
  (`run.finished`, `gate.raised`, `turn.submitted` — the last being the inbound-message
  trigger). Event triggers still see only what the audit log records, a watch's latency is one
  tick rather than an OS notification, and there is no trigger on an external queue or a git ref.
- **MCP breadth** — servers reach over stdio or streamable HTTP, expose tools, resources, and
  prompts, and start on first use from a cached surface. An HTTP server authenticates with a
  vault-held bearer token or by **OAuth 2.1 client credentials** (RFC 8414 discovery, cached and
  refreshed), and **sampling** is answered behind a per-server cap with no tools, the host's
  model, and a bounded conversation. Missing: server-initiated *notifications*
  (`tools/list_changed`), which need a transport that reads outside a request, so a surface can
  go stale until `mcp refresh`; dynamic client registration; and host-mediated per-host egress
  for a stdio server, whose network is all-or-nothing unless containerised. The interactive
  authorization-code flow is deliberate — an agent has nobody at a keyboard to consent.
- **Inbound screening breadth** — platform messages and webhook bodies are screened by
  `InboundScreening` under `jclaw.inbound-policy`, fenced by default, and `review` holds a `HIGH`
  finding in a queue worked with `jclaw inbound`. What is not screened, by design, is the
  operator's own input and a signed-in user's own turn: they instruct what they own. What is
  missing is breadth of signal — the heuristics are the same regex set the capability path uses,
  there is no per-source policy (one setting covers every adapter and every webhook), and a held
  message has no expiry, so an unattended queue grows.
- **Smaller items** — OpenRouter routing preferences are not sent; embedding providers beyond
  the OpenAI-compatible shape (Voyage, Cohere) need their own adapter, so vector memory is
  unavailable to a deployment standardised on either; PDFs and other documents are refused as
  attachments; retention drops rows with no archival step.
