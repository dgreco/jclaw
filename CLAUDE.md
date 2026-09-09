# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

A working agent harness covering milestones **M0–M7**, plus subagents (sync or async), MCP,
streaming on every provider, lease-based crash recovery, a per-thread run lock, context
compaction with model summaries, vector memory, configurable denials, egress lists and per-tool
rate limits, injection heuristics, auth and process gates with expiry, a scheduler behind
`submit`/`worker`, an HTTP surface (`serve`) with run projections and SSE, attachments, store
retention, and a container sandbox for the shell lane. `jclaw run "..."` completes a real turn end
to end; runs park on gates and resume across process boundaries; memory, skills, and scheduled
routines work. Ships as an uber jar and a GraalVM native image, both verified — including
subprocess spawning for MCP servers and subagent child runs.

jclaw is a Java/Spring Boot reimplementation of the **architecture** of
[IronClaw](https://github.com/nearai/ironclaw) (a ~1.4M-line Rust agent harness, internally
"Reborn"). It is an architectural clone, not a port: the layering, the turn/run lifecycle, the
untrusted-`LoopExit` trust model, and the `CapabilityHost` authority boundary are faithful; the
feature surface is a fraction of IronClaw's. See **Not built yet** for the honest list.

259 tests pass across 9 modules, including 14 machine-checked architecture rules.

## Commands

- Build everything: `mvn clean install`
- Test (full suite): `mvn test`
- Test (single): `mvn test -Dtest=ClassName#methodName -pl <module>` (add `-am` if deps are stale)
- Run (jar): `java -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar <command>`
- Source-integrity guard: `./scripts/byte-verify.sh scan`

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
| `repl` | interactive session with readline editing (and still pipes) |
| `approvals list [--all]\|approve\|deny` | resolve gates (approval, auth, process); approving resumes by default; expired gates are hidden and refuse decisions |
| `resume <run-id>` | continue a parked run |
| `memory write\|search\|list\|forget\|reindex` | durable memories, BM25 + recency + vector (when an embedding provider is configured) |
| `routines add\|list\|remove\|pause\|resume\|run-due` | scheduled agent work |
| `worker [--concurrency N]` | long-lived: fires routines, sweeps leases, executes queued runs under a cap |
| `skills list\|show` | installed skills |
| `models [--probe]` | provider status; `--probe` proves one actually responds |
| `onboard` | writes `~/.jclaw/jclaw.yaml` |
| `mcp add\|list\|remove\|toggle\|test` | external MCP tool servers (stdio transport) |
| `recover` | reconcile runs whose worker died |
| `retain [--dry-run]` | drop old rows of finished runs from results, events, checkpoints |
| `secrets set\|list\|remove` | encrypted vault of credentials tools use by `{{secret:NAME}}` reference, bound to one capability and a host list |
| `tools` | capability surface with effect/trust/unattended |
| `status [--run id]` | recent activity from the event log, or one run's projection |
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
`(nextState, decision)` and performs no I/O — no sockets, no clock reads, no ports.
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
- `tools` — no adapter holds a `SecretVault` handle; `HandlerContext` has no method to obtain one.
  The kernel host substitutes `{{secret:NAME}}` references into the arguments a lane receives at
  dispatch, only when the secret's binding names that capability and every URL host in the
  arguments; the fingerprint, approval prompt, checkpoints, and events keep the reference form,
  and the leased value joins that call's redaction set.
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
- **One active run per thread.** `JclawRuntime.submit` and `resume` take a `ThreadLock` (an OS
  file lock per canonical scope, `FileThreadLock`) before the inbound message is written, so a
  refused submission leaves no trace and two processes can never interleave one transcript. The
  lock dies with its process; there is no TTL to reason about. Refusal is `THREAD_BUSY`.
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
- **JLine needs a native-access grant, declared twice.** It loads a native library to put a TTY
  into raw mode, which JDK 24+ restricts. The jar gets it from `Enable-Native-Access: ALL-UNNAMED`
  in its manifest (`maven-jar-plugin`); the native image has no manifest, so the same grant is a
  `--enable-native-access=ALL-UNNAMED` build arg in the `native` profile. Drop either and the REPL
  prints warnings now and fails on a future JDK.
- **Do not catch `SpringApplication.AbandonedRunException` in `main`.** Spring throws it to abort
  the run during AOT processing. Treating it as a startup error makes `mvn -Pnative` fail with a
  misleading message; `JclawApplication` rethrows it explicitly.
- **Startup-failure logging is suppressed** for two named Spring loggers so a config mistake prints
  one line instead of a 40-frame trace. Restore while debugging with
  `--logging.level.org.springframework.boot.SpringApplication=ERROR`.
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
- Git: initialized on `main` (September 2026). `.gitignore` excludes `target/`, IDE files, `.claude/settings.local.json`, and `.byte-manifest`; the captured native-image metadata is versioned on purpose.

## Not built yet

Honest gaps against IronClaw's surface. jclaw is ~20k lines against IronClaw's ~1.4M; the
architecture and most runtime mechanisms are equivalent, the breadth is not. PARITY.md section
16 ranks these.

- **Channel adapters** — `serve` has a browser UI and an OpenAI-compatible endpoint; there is no
  Slack or Telegram adapter and no reply-target binding beyond stdout and HTTP read-back.
- **Identity beyond static tokens** — `serve` users are tenants (`TurnScope.tenant()`), and every
  scope-keyed store and the scheduler separate by tenant. There is no login flow, no roles, no
  per-tenant policy or token accounting, and `TurnScope.agent()` is always `default`.
- **Secrets beyond one tool call** — the vault leases values into `http_fetch` headers (and any
  capability a binding names) but there is no staged handoff to subprocesses, no leak scan of
  outbound model requests, and no per-tenant vaults.
- **SQL beyond one table** — `storage=sql` keeps every store's rows in `jclaw_rows` with
  versioned migrations; there are no per-concept tables, no materialised projections (folds run
  on demand over indexed reads), and no connection pool (a connection per operation).
- **Sandboxing beyond the shell lane** — `shell-backend=docker` contains `builtin.shell` only;
  file, http, memory, and MCP lanes run in-process, and an MCP server's sockets are unmediated.
  No WASM lane, no orchestrator, no per-job tokens, no LLM proxying.
- **Extension ecosystem** — no manifests, registry, signed `VERIFIED` extensions, or installable
  skill packages; built-ins are compiled in and MCP is the only external route.
- **Loop hooks and loop families** — one machine, no pre/post model or tool hooks.
- **Observability substrate** — SLF4J and the event log only; no OpenTelemetry, no metrics.
- **Triggers beyond cron** — no event, webhook, or heartbeat triggers; the ingress does not route
  webhooks to routines.
- **MCP breadth** — stdio only; no HTTP/SSE transports, no OAuth, no resources or prompts, eager
  server lifecycle.
- **Smaller items** — `repl` does not resolve gates inline; OpenRouter routing preferences are not
  sent; embedding providers beyond the OpenAI-compatible shape (Voyage, Cohere) need their own
  adapter; PDFs and other documents are refused as attachments; inbound content is not scanned
  for injection and there is no review queue; retention has no archival step.
