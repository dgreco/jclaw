# jclaw

**A hexagonal agent OS harness in Java 21 / Spring Boot 4.1 — an architectural clone of [IronClaw](https://github.com/nearai/ironclaw).**

jclaw runs an LLM agent loop the way an operating system runs a process: every effect the model asks for passes through one authority gate, every run is durable and resumable across process boundaries, and the decision logic is a pure function you can test without a network. It ships as a single uber jar or a GraalVM native binary, talks to Anthropic, OpenAI, OpenRouter, Ollama, or any local OpenAI-compatible server, and can be driven from a terminal, a queue, or a small HTTP surface.

```
$ jclaw run "list the Java files under jclaw-domain and tell me which one is the state machine"
There are 15 Java files under jclaw-domain/src/main. The state machine is
jclaw-domain/src/main/java/io/jclaw/domain/loop/TurnMachine.java ...
```

- [What it is](#what-it-is)
- [Quick start](#quick-start)
- [Building](#building)
  - [Uber jar](#uber-jar)
  - [Native image](#native-image)
  - [Tests and source-integrity checks](#tests-and-source-integrity-checks)
  - [Continuous integration](#continuous-integration)
- [Configuration](#configuration)
  - [Where settings come from](#where-settings-come-from)
  - [All settings](#all-settings)
  - [Providers and credentials](#providers-and-credentials)
  - [Approval modes](#approval-modes)
  - [The state directory](#the-state-directory)
- [Using jclaw](#using-jclaw)
  - [One-shot turns: `run`](#one-shot-turns-run)
  - [Interactive sessions: `repl`](#interactive-sessions-repl)
  - [Approval gates: `approvals` and `resume`](#approval-gates-approvals-and-resume)
  - [Queued turns: `submit` and `worker`](#queued-turns-submit-and-worker)
  - [The HTTP surface: `serve`](#the-http-surface-serve)
  - [Tools the agent can use](#tools-the-agent-can-use)
  - [Durable memory: `memory`](#durable-memory-memory)
  - [Skills: `skills`](#skills-skills)
  - [Scheduled routines: `routines` and `worker`](#scheduled-routines-routines-and-worker)
  - [MCP servers: `mcp`](#mcp-servers-mcp)
  - [Subagents](#subagents)
  - [Streaming](#streaming)
  - [Crash recovery: `recover`](#crash-recovery-recover)
  - [Retention: `retain`](#retention-retain)
  - [Inspecting the system: `status`, `tools`, `models`, `doctor`](#inspecting-the-system-status-tools-models-doctor)
  - [Tracing a turn](#tracing-a-turn)
  - [Exit codes](#exit-codes)
- [Security model in one page](#security-model-in-one-page)
- [Project layout](#project-layout)
- [Further reading](#further-reading)

---

## What it is

jclaw reimplements the **architecture** of IronClaw — the seven-layer ladder, the turn/run lifecycle, the untrusted-exit trust model, and the single capability-authority boundary — in about 15k lines of Java. It is not a port: the feature surface is a fraction of IronClaw's (see [PARITY.md](PARITY.md)), but the load-bearing ideas are intact:

| Idea | What it means for you |
|---|---|
| **The loop is a pure function.** | The agent's control flow (`TurnMachine`) does no I/O. One class, `EffectInterpreter`, executes decisions. Replay is exact; everything is testable with plain values. |
| **Exits are claims, not facts.** | The runtime re-resolves every reference a run returns before believing it completed. |
| **One authority gate.** | Every tool call passes existence → policy → approval → dispatch → redact/bound/store, in that order. Approvals are per exact invocation, not per tool. |
| **Resume re-authorizes.** | A parked run asks the approval store again on resume; denying a gate produces a denial the model sees, not an effect. |
| **Recovery fails closed.** | A crashed worker's run is replayed only from a checkpoint proven side-effect-free, and only after a grace period. |

Everything is durable JSONL under `~/.jclaw`: a run can park in one process, be approved in a second, and resume in a third. There is no database, and no server unless you start one (`jclaw serve`).

**Status.** Milestones M0–M7 plus subagents (synchronous or asynchronous), MCP, streaming on every provider, lease-based crash recovery, a per-thread run lock, context compaction with model summaries, vector memory, configurable denials and egress lists, per-tool rate limits, injection heuristics, auth and process gates with expiry, a scheduler with `submit` and `worker`, an HTTP surface with run projections, event streams, and per-user tenants, attachments, store retention, an encrypted secret vault with host-side credential injection, a container sandbox for the shell lane, a SQL storage backend (embedded H2 or PostgreSQL) with schema migrations, signed extension packages, execution-stage hooks, a second loop family, Prometheus metrics with OTLP trace export, webhook, heartbeat, and event triggers, and MCP over HTTP with resources, prompts, and lazily started servers. 356 tests pass across the modules, including 15 machine-checked architecture rules (ArchUnit). Both the uber jar and the native image are verified end to end, including subprocess spawning for MCP servers and shell tools. [PARITY.md](PARITY.md) lists what IronClaw still has that jclaw does not.

---

## Quick start

Requirements: **JDK 21+** and **Maven 3.9+** (the wrapper is not checked in). For the native image, GraalVM 25+.

```bash
# 1. Build
mvn -q clean install -DskipTests

# 2. Try it with no API key — the mock provider is the default
java -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar run "hello"

# 3. Point it at a real model
export ANTHROPIC_API_KEY=sk-ant-...
java -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar run --jclaw.provider=anthropic \
  "summarise the README in this directory"

# 4. Make the choice permanent and verify it
java -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar onboard      # writes ~/.jclaw/jclaw.yaml
java -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar doctor       # config + security posture
java -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar models --probe
```

The rest of this document writes `jclaw …`; define an alias or use the native binary:

```bash
alias jclaw='java -jar /path/to/jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar'
```

The agent operates inside a **workspace** — by default the current directory. Every file path a tool touches is confined to it, and the workspace directory *name* becomes the project scope for memories and routines. Run jclaw from the project you want it to work on, or pass `--jclaw.workspace=/path`.

---

## Building

The build is a standard multi-module Maven reactor rooted at `pom.xml`, parented by `spring-boot-starter-parent` 4.1.1. Bytecode targets Java 21 (`maven.compiler.release=21`).

### Uber jar

```bash
mvn clean install            # compiles, runs all tests, installs every module
# or, skipping tests:
mvn -q clean install -DskipTests
```

The Spring Boot Maven plugin repackages `jclaw-app` into an executable jar:

```
jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar
```

Run it with `java -jar`. Startup is about 1.2 s. The manifest carries `Enable-Native-Access: ALL-UNNAMED` so JLine can put the terminal into raw mode without warnings on JDK 24+ (the REPL needs it).

### Native image

Requires **GraalVM 25 or newer** as `JAVA_HOME` (the toolchain is 25+; bytecode still targets 21).

```bash
export JAVA_HOME=/path/to/graalvm-25
mvn -B install -DskipTests                       # install every module first
mvn -B -Pnative -pl jclaw-app package -DskipTests
./jclaw-app/target/jclaw run "hello"
```

The `native` profile is declared in `jclaw-app/pom.xml` (the Boot parent only provides `pluginManagement` for it, so `mvn -Pnative native:compile` at the root does nothing useful). It produces `jclaw-app/target/jclaw`: roughly 80 MB, ~78 ms startup versus ~1.2 s for the jar. Build args: `--no-fallback` (a missing reflection registration fails the build instead of shipping a binary that dies at runtime), `--report-unsupported-elements-at-runtime`, and `--enable-native-access=ALL-UNNAMED`.

Two pieces of reachability metadata make the binary work, and both matter when you upgrade dependencies:

- **picocli-codegen** runs as an annotation processor and emits reflection config for every `@Command`. Without it every subcommand is invisible in the binary.
- **The Anthropic SDK's Jackson metadata** lives in `jclaw-app/src/main/resources/META-INF/native-image/io.jclaw/anthropic-sdk/` and was *captured* with the GraalVM tracing agent, not hand-written. Regenerate it after any SDK upgrade (a dummy key suffices — serialization happens before the auth failure):

  ```bash
  ANTHROPIC_API_KEY=dummy $JAVA_HOME/bin/java \
    -agentlib:native-image-agent=config-output-dir=jclaw-app/src/main/resources/META-INF/native-image/io.jclaw/anthropic-sdk \
    -jar jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar run capture --jclaw.provider=anthropic
  ```

Spring's AOT processing (`process-aot`) runs as part of the profile; `JclawApplication` deliberately rethrows `SpringApplication.AbandonedRunException` so that step is not mistaken for a startup failure.

### Tests and source-integrity checks

```bash
mvn test                                                   # whole suite
mvn test -Dtest=TurnMachineTest -pl jclaw-domain           # one class
mvn test -Dtest='ApprovalResumeIntegrationTest#resumeWithoutDecisionParksAgain' -pl jclaw-app -am
./scripts/byte-verify.sh scan                              # no stray control bytes in sources
./scripts/byte-verify.sh install && ./scripts/byte-verify.sh validate   # manifest drift check
```

Test totals by module (verified on this checkout): contracts 8 · domain 116 · kernel 14 · providers 25 · storage 12 · app 65 = **240, 0 failures**. `DependencyLawTest` in `jclaw-app` machine-checks the layer ladder with ArchUnit; the rules were confirmed to fire by planting deliberate violations.

### Continuous integration

`.gitlab-ci.yml` defines four jobs for the GitLab remote:

| Job | Stage | What it does |
|---|---|---|
| `byte-verify` | verify | `scripts/byte-verify.sh scan` — refuses stray control bytes in sources. |
| `build-test` | build | `mvn verify` on Temurin 21; publishes JUnit reports to the merge-request widget and the uber jar as an artifact. Maven's local repository is cached per pom hash. |
| `native-image` | native | Builds the GraalVM binary and smoke-tests it (`--version`, a mock-provider `run`). Mandatory on every pipeline: a broken native build fails the pipeline like a broken test. Needs a runner with several GB of memory. |
| `release` | release | Tags only. Uploads the native binary (`jclaw-linux-<arch>`), the uber jar, and `SHA256SUMS` to the project's generic package registry and creates a GitLab Release for the tag linking them. |

To cut a release, push a tag: `git tag v0.1.0 && git push origin v0.1.0`. The tag pipeline runs every job and ends by publishing the release at `/-/releases/v0.1.0`.

One pipeline per change: pushes to a branch with an open merge request run only the merge-request pipeline. The jobs assume a Docker-executor runner and pull public images (`maven:3.9.11-eclipse-temurin-21`, `ghcr.io/graalvm/native-image-community:25`).

---

## Configuration

### Where settings come from

All settings are Spring Boot properties under the `jclaw.` prefix, bound to an immutable record (`JclawProperties`) once at startup. They can be supplied, in ascending precedence:

1. **Defaults** in `jclaw-app/src/main/resources/application.yaml`.
2. **`~/.jclaw/jclaw.yaml`** — user config, imported optionally. `jclaw onboard` writes it. The path is fixed (not derived from `state-dir`, which would be circular).
3. **Environment variables** in relaxed-binding form: `JCLAW_PROVIDER`, `JCLAW_APPROVAL_MODE`, `JCLAW_LOCAL_BASE_URL`, …
4. **Command-line arguments** `--jclaw.<name>=<value>` on any command. `JclawApplication` strips `--jclaw.*`, `--spring.*`, `--logging.*`, `--management.*`, and `--server.*` before argv reaches picocli, so both frameworks see the same arguments without conflict. These flags therefore do not appear in `--help`'s option list; the root command's footer documents them.

A minimal `~/.jclaw/jclaw.yaml`:

```yaml
jclaw:
  provider: anthropic
  model: claude-opus-5
  approval-mode: interactive
```

**Credentials never go in config files.** They are read from the environment only (see below); `onboard` never asks for one, and the redactor masks the live values of every known credential variable wherever a tool might echo them.

### All settings

| Property (`jclaw.…`) | Default | Meaning |
|---|---|---|
| `workspace` | `.` | Root the agent may read and write. Every tool path is confined to it (real-path containment, symlinks resolved). Its directory name is the project scope. |
| `state-dir` | `~/.jclaw` | Where all durable state lives (see [The state directory](#the-state-directory)). |
| `provider` | `mock` | `mock` · `anthropic` · `openai` · `openrouter` · `ollama` · `local` · `failover` |
| `model` | `claude-opus-5` | Model id passed to the provider. OpenRouter requires `org/model` form. |
| `approval-mode` | `interactive` | `read-only` · `interactive` · `trusted` — see [Approval modes](#approval-modes). |
| `max-iterations` | `25` | Model↔tool cycles allowed per run. |
| `max-tokens` | `500000` | Token budget per run (input + output); `0` = unlimited. A fixed 10-minute wall-clock cap also applies. |
| `context-max-messages` | `200` | Most recent transcript messages a model request may carry. See [Context window](#context-window). |
| `context-max-tokens` | `100000` | Estimated token budget (4 chars/token) for those messages; the tighter of the two limits binds. Lower it for local models with small context windows. |
| `context-summarise` | `true` | When the window drops history, ask the model to summarise the dropped span first (one extra, non-streamed call) and carry the summary instead of a bare count. `false` truncates only. |
| `context-summary-max-tokens` | `1024` | Output cap for the summary call. |
| `approval-ttl` | `24h` | How long an unanswered approval or auth gate stays answerable. After that a resume asks afresh and the stale gate refuses a decision. Decisions never expire. |
| `system-prompt` | *"You are jclaw, a helpful agent operating inside a bounded workspace…"* | Operator instructions; jclaw appends a workspace section and skill summaries. |
| `allow-private-networks` | `false` | Lets `http_fetch` reach loopback / RFC1918 / link-local addresses. **Development only** — re-opens the SSRF surface the egress guard closes. `doctor` warns when set. |
| `openai-base-url` | `https://api.openai.com/v1` | Endpoint for `openai`; override for a compatible gateway. |
| `openrouter-base-url` | `https://openrouter.ai/api/v1` | Endpoint for `openrouter`. |
| `openrouter-referer` | *(blank)* | Optional `HTTP-Referer` attribution header; omitted when blank. |
| `openrouter-title` | `jclaw` | Optional `X-Title` attribution header. |
| `ollama-base-url` | `http://localhost:11434/v1` | Ollama's OpenAI-compatible endpoint. |
| `local-base-url` | *(blank)* | **Required for `local`**: any OpenAI-compatible server — `http://localhost:1234/v1` (LM Studio), `http://localhost:8000/v1` (vLLM), llama.cpp server, LocalAI. When set it also joins the `failover` chain. |
| `mock-script` | *(empty)* | Scripted turns for `mock`, in order: `text:<reply>` or `tool:<capability>:<k=v,k=v>`. Lets the whole CLI, including tool calls and the approval flow, run with no key and no network. |
| `embedding-provider` | `none` | `none` · `openai` · `openrouter` · `ollama` · `local` — adds vector similarity to memory retrieval (see [Durable memory](#durable-memory-memory)). Credentials come from the same environment variables as the chat providers. |
| `denied-capabilities` | *(empty)* | Capability ids refused outright in every approval mode and hidden from the model, e.g. `builtin.shell,builtin.http_fetch`. A malformed id fails startup. |
| `egress-allowlist` | *(empty)* | When set, tools may only reach these hosts: exact names or `*.suffix` wildcards. Private-network and cloud-metadata denials still apply to allowed hosts; the list can only narrow. |
| `egress-denylist` | *(empty)* | Hosts tools may never reach, on top of the built-in metadata hosts. Wins over the allowlist. |
| `tool-egress` | *(empty)* | Per-capability egress allowlists on top of the host lists: `tool-egress: {"[builtin.http_fetch]": "api.github.com,*.example.com"}` in YAML, or `--jclaw.tool-egress.[builtin.http_fetch]=…` on the command line. Applied after the host checks, so a tool list can only narrow. |
| `tool-rate-limits` | *(empty)* | Per-capability caps as `N/window` (`5/1m`, `100/1h`, `2/30s`), keyed the same way. A sliding window per process; a call past the cap is denied `rate_limited` without bothering a human. |
| `injection-policy` | `sanitize` | What the kernel does with tool output that looks like a prompt injection: `off`, `warn` (audit event only), `sanitize` (fence it, defuse chat-template tokens, tell the model it is data), `block` (withhold HIGH-severity findings from the model). The stored payload is never altered. |
| `embedding-model` | *(provider default)* | `text-embedding-3-small` (openai), `openai/text-embedding-3-small` (openrouter), `nomic-embed-text` (ollama); **required for `local`**. Vectors carry their model id, so switching models means `jclaw memory reindex`. |
| `subagents-async` | `false` | `true` queues a subagent for a worker and parks the parent `WAITING_PROCESS` until the child finishes; needs `worker` or `serve` running. `false` runs the child inside the parent's tool call. |
| `retention-results` / `retention-events` / `retention-checkpoints` | `14d` / `30d` / `7d` | Maximum age of a **finished** run's rows in each store before `retain` (or the worker's hourly sweep) drops them. `0` keeps forever. The transcript is never swept. |
| `serve-token` | *(blank)* | The operator's bearer token for `serve` (`JCLAW_SERVE_TOKEN` works too). The operator is the same `local` tenant as the CLI and can read every user's runs. Blank with no `serve-users` means no authentication: keep it on loopback. |
| `serve-users` | *(none)* | Named users for `serve`, as a map of user name to bearer token (`jclaw.serve-users.alice=<token>`). Each user is a tenant: their thread names are namespaced, their memories, routines, and approvals are their own, and they see only their own runs and gates. |
| `shell-backend` | `host` | `host` runs `builtin.shell` as a child process; `docker` runs each command in a container (see [Tools](#tools-the-agent-can-use)). |
| `sandbox-docker` / `sandbox-image` / `sandbox-network` / `sandbox-memory` / `sandbox-cpus` / `sandbox-pids-limit` | `docker` / `alpine:3.20` / `none` / `512m` / `1` / `256` | The container contract for `shell-backend: docker` and `mcp-backend: docker`: binary, image, network (`none` unless you say otherwise), memory, CPU, and pid limits. |
| `mcp-lazy` | `true` | Publish an MCP server's capabilities from its cached surface and start the server only when one is invoked. `false` rediscovers, and so starts every server, on every invocation. |
| `mcp-backend` | `host` | `host` runs MCP server processes directly; `docker` runs each one inside the sandbox contract (see [MCP servers](#mcp-servers-mcp)). |
| `mcp-sandbox-image` / `mcp-sandbox-network` | *(blank)* | Overrides for MCP servers under `mcp-backend: docker`; blank inherits `sandbox-image` / `sandbox-network`. Servers usually need a runtime image (`node:22-alpine`) and, when their tool exists to reach an API, `bridge`. |
| `storage` | `jsonl` | Where durable rows live: `jsonl` files under the state directory, or `sql` (every store in one database; see [The state directory](#the-state-directory)). Skills and thread locks stay on the filesystem either way. |
| `otlp-endpoint` | *(blank)* | An OpenTelemetry collector's base URL (OTLP/HTTP, JSON). Every finished run's trace is POSTed to `<endpoint>/v1/traces`, best effort, off the request path. Blank disables export. |
| `hooks` | `budget-notice` | Built-in execution-stage hooks to enable, by id. `budget-notice` tells the model, once most of the run's token budget is spent, to finish rather than start new work. Blank disables all built-ins. |
| `loop-family` | `canonical` | The loop strategy. `reflective` has the model review its draft reply once, without tools and without streaming, before it is persisted; one extra model call per turn. |
| `serve-user-roles` | *(none)* | Role per user: `viewer`, `member`, or `operator`. Unlisted people are members. A viewer may read but not start a turn or answer a gate. |
| `session-ttl` | `12h` | How long a session minted by `POST /login` lasts. |
| `oidc-issuer` / `oidc-client-id` / `oidc-client-secret` / `oidc-redirect-uri` | *(blank)* | An OpenID Connect provider to sign in through. The client secret is a vault entry name, bound to the capability `identity.login` and the provider's host. All four are needed before the login routes appear. |
| `agents` | *(none)* | Named run profiles: `jclaw.agents.reviewer.model` and `.system-prompt`. The chosen one becomes `TurnScope.agent()`, so a run records which configuration produced it. |
| `tenant-policies` | *(none)* | Per tenant: `.approval-mode`, `.denied-capabilities`, `.agent`. Can only narrow what the host already permits. |
| `tenant-token-budget` | `0` | Tokens one tenant may spend across finished runs before their turns are refused at admission. `0` means no limit. |
| `wasm-max-memory-pages` / `wasm-max-instructions` / `wasm-max-output-bytes` / `wasm-timeout` | `256` / `100000000` / `262144` / `5s` | What any WebAssembly module may have: linear memory in 64 KiB pages, instructions before it is stopped, the largest result it may return, and wall clock. |
| `trusted-publishers` | *(none)* | Extension publishers whose signatures make an install `VERIFIED`, as a map of publisher name to base64 Ed25519 public key (`jclaw.trusted-publishers.acme=<key>`, printed by `extensions keygen`). |
| `datasource-url` / `datasource-username` | *(blank)* / `sa` | For `storage: sql`. Blank URL means an embedded H2 file under the state directory; a `jdbc:postgresql://…` URL is the hosted option. The password comes only from `JCLAW_DATASOURCE_PASSWORD`. |

Logging is controlled through standard Spring properties (`--logging.level.io.jclaw=TRACE`) or the `--debug` / `--trace` shortcuts described under [Tracing a turn](#tracing-a-turn).

### Providers and credentials

| `provider` | Credential (env var) | Notes |
|---|---|---|
| `mock` | none | Default. Replies with a fixed message, or follows `mock-script`. |
| `anthropic` | `ANTHROPIC_API_KEY` (or `ANTHROPIC_AUTH_TOKEN`) | Official Anthropic Java SDK; adaptive thinking enabled; native tool-call shape; SDK event streaming; image attachments as native image blocks. |
| `openai` | `OPENAI_API_KEY` | Chat Completions API. |
| `openrouter` | `OPENROUTER_API_KEY` | OpenAI-compatible gateway; model ids must be `org/model` (e.g. `anthropic/claude-sonnet-4.6`). Claude via OpenRouter goes through the OpenAI shim — no adaptive thinking; prefer `anthropic` for Claude. |
| `ollama` | none | Local daemon on loopback. |
| `local` | `LOCAL_API_KEY` (optional) | Any OpenAI-compatible server at `local-base-url`. Unauthenticated by default; a server that checks a token (vLLM `--api-key`) answers 401 with its own message. |
| `failover` | whatever is present | Builds a chain from configured providers in this order: Anthropic → OpenAI → OpenRouter → `local` (if `local-base-url` set) → Ollama. Providers with no credentials are omitted, not tried. |

Provider credentials are resolved **per request**, so a missing key does not prevent the process from starting: it shows up as an `AUTH` failure in the event log, and `jclaw doctor` reports it. Check what is configured with `jclaw models`; prove the active provider actually answers with `jclaw models --probe`.

#### Secrets the agent may use but never see

Credentials a *tool* needs, as opposed to the model provider, live in the vault:

```bash
echo -n "$GITHUB_TOKEN" | jclaw secrets set github --capability builtin.http_fetch --host api.github.com
jclaw secrets list                                  # names and bindings; values are never printed
jclaw secrets remove github
```

The model is told which names exist and where each may go, and writes `{{secret:github}}` where the value belongs, typically in a request header. The kernel substitutes the value into the arguments the tool receives at dispatch, after every authority check, and only if the secret's binding names that capability and every host the arguments point at; a call that names another host, or another tool, is denied and the model is told why. The reference form is what gets fingerprinted, shown in the approval prompt, checkpointed, and logged; the value exists in one handler call, is added to the redaction set for that call's output, and travels only to the bound host (a redirect elsewhere is fetched without headers). The audit log records `secret.injected` with the name. Values are AES-256-GCM encrypted in `secrets.jsonl`; the key is `JCLAW_VAULT_KEY` (32 bytes, base64 or hex) or an owner-only `vault.key` generated on first use.

**Staging into a subprocess.** Substitution is right for an HTTP header and wrong for a child process: an argument becomes a command line, and a command line is readable by every process on the machine. So there is a second handoff. The model names a variable and a secret — never a reference that expands in place — and the value goes into the child's environment and nowhere else:

```bash
echo -n "$GH_TOKEN" | jclaw secrets set gh --capability builtin.shell --subprocess
# the model writes: {"command": "gh pr list", "secret_env": {"GH_TOKEN": "gh"}}
```

The two handoffs cannot cross, and the binding is what separates them. A `--host` secret is scoped to where it may be *sent*; a `--subprocess` secret has no host, because a child process can reach anywhere — binding one is the operator saying that arbitrary code may hold the credential. `permitsHost` never matches the reserved token, including against a URL whose authority is literally `*`, so a staged secret can never be substituted into an argument and a host-bound one can never be staged. The arguments keep the secret's *name*, which is public, so the fingerprint, the approval prompt, the checkpoints, and the events are unchanged; only the value moves, onto the handler context for that one call. A lane cannot ask for a secret it was not given and cannot enumerate what exists — the context carries values, not a vault.

**Scanning what comes back.** The binding rules govern where a secret goes; they cannot govern what returns. A tool reads a config file with a token in it, a server echoes an `Authorization` header in an error, a subprocess prints its own environment — and that output becomes a message in the next request, long after the capability boundary. `jclaw.hooks: [secret-leak-scan]` adds the check at the last moment before a request leaves: every value the vault holds is scanned for in the assembled system prompt, messages, and tool arguments, and each hit is rewritten back to `{{secret:NAME}}` — true, and exactly what the model would have written to use it deliberately. Values shorter than eight characters are not scanned, which is a deliberate blind spot: below that a secret matches ordinary prose by coincidence, and a scanner that fires on coincidence is one an operator turns off. A secret found inside a provider's *signed reasoning* vetoes the call instead, because rewriting the text would invalidate the signature and there is no third option that does not send the value. The hook is off by default; scanning every request against every secret is not free, and an installation with no vault should not pay for it.

**A vault per tenant.** Under `serve`, a secret named `api-key` means the tenant's own key: each tenant gets `secrets.<tenant>.jsonl`, and the tenant comes from the run's scope, fixed at admission, so there is no argument that could name another's vault. The default tenant keeps `secrets.jsonl`, so a single-user installation is untouched and nothing has to be migrated. They share one encryption key — separating tenants is about which credential a run can reach, and the key answers a different question; per-tenant keys would need per-tenant key custody, which is a real feature and not this one.

Tool names are dotted internally (`builtin.read_file`) and encoded to single-underscore form on the wire (`builtin_read_file`) because both the OpenAI and Anthropic schemas forbid dots; decoding is by lookup against the tools actually sent, never by parsing. The OpenAI-compatible adapter pins HTTP/1.1 because vLLM and other llhttp-based servers choke on Java's default h2c upgrade header.

### Approval modes

`approval-mode` sets the strongest **effect class** the agent may perform without asking. Each capability declares an effect class on the ladder `PURE < READ_LOCAL < WRITE_LOCAL < NETWORK < PROCESS < DESTRUCTIVE`:

| Mode | Auto-approve ceiling | Gates answerable? | Behaviour above the ceiling |
|---|---|---|---|
| `read-only` | `READ_LOCAL` | no | **Denied** outright — a gate nobody can answer would hang the run. For scripts and cron. |
| `interactive` (default) | `READ_LOCAL` | yes | Run **parks** on an approval gate; you approve or deny in another shell. |
| `trusted` | `PROCESS` | yes | Shell, writes, network, subagents all run unattended. Local development only — a prompt injection becomes code execution. `doctor` warns. |

Two things no mode changes: **every MCP tool requires approval in every mode** (third-party tools register at `COMMUNITY` trust, whose own ceiling is `PURE`, and policy may only tighten), and approvals are granted **per exact invocation** (a fingerprint over capability id plus sorted arguments), so approving one `shell` command never approves the next.

### The state directory

Everything durable is append-only JSONL under `state-dir` (default `~/.jclaw`), created on first run:

```
~/.jclaw/
├── jclaw.yaml          user config (written by `onboard`)
├── events.jsonl        audit log: ids, enums, counters, timings — never prompts or paths
├── transcript.jsonl    every user and assistant message, per thread
├── runs.jsonl          run records, resolved profile, status, lease
├── checkpoints.jsonl   loop state snapshots (what makes resume and recovery possible)
├── approvals.jsonl     gates raised and decided (approval, auth, and process gates, with expiry)
├── results.jsonl       full capability payloads behind result refs (redacted, bounded)
├── memory.jsonl        durable memories, project-scoped, with embeddings when configured
├── routines.jsonl      scheduled routines
├── mcp.jsonl           registered MCP servers
├── mcp-surface.jsonl   what each MCP server offered last time, so it need not be started
├── secrets.jsonl       vault entries: names, bindings, AES-256-GCM ciphertext
├── extensions.jsonl    installed extensions: manifests, trust, digests
├── extensions/<name>/  installed extension packages
├── vault.key           the vault key (owner-only), unless JCLAW_VAULT_KEY is set
├── locks/<hash>.lock   per-thread run locks (OS file locks; empty files)
├── repl-history        REPL line history
└── skills/<id>/SKILL.md
```

`results.jsonl`, `events.jsonl`, and `checkpoints.jsonl` grow with every run; `jclaw retain` (and the worker, hourly) drops rows of finished runs older than the `retention-*` settings. The transcript is never swept.

Because state is a directory, you can run several isolated agents by giving each its own `--jclaw.state-dir`. Delete the directory to start clean.

#### SQL instead of files

```bash
jclaw run "hello" --jclaw.storage=sql                                 # embedded H2: <state-dir>/jclaw.mv.db
JCLAW_DATASOURCE_PASSWORD=… jclaw serve --jclaw.storage=sql \
  --jclaw.datasource-url=jdbc:postgresql://db.internal/jclaw --jclaw.datasource-username=jclaw
```

With `storage: sql` the stores replay rows exactly as they do from a file and cannot tell the difference. Where those rows live depends on the store: the busy ones — events, transcript, runs, checkpoints, results, approvals, memory, routines — have a table each (`jclaw_events`, `jclaw_transcript`, …), ordered by an identity column with run and thread lifted into indexed columns. The small configuration stores (MCP servers, extensions, secrets, sessions, per-tenant vaults) share `jclaw_rows`, partitioned by store name, because a table per concept earns its DDL only where the volume or the query pattern justifies it, and a dozen near-empty tables would make the schema harder to read for nothing.

Schema migrations are versioned in `jclaw_schema` and applied on start; a database newer than the build is refused. Version 2 moves the busy stores out of the shared table in one transaction, preserving append order — which is the whole contract of a row store, and what `SchemaUpgradeTest` exists to prove.

A **finished** run's projection is materialised into `jclaw_run_projection` rather than folded on every read. Only terminal runs are stored: a finished run's events are final, so its fold is final and cannot disagree with the log, which is why there is no invalidation to get wrong. Running and parked runs are folded exactly as before. The row is written when the run ends and also read-through on a miss, so a run finished by another process or before the cache existed still gets cached the first time someone looks at it. A row from an older codec version, or one that will not decode, is a miss and nothing more — a cache that can fail a request has made the system worse.

Connections come from a **HikariCP pool** (`jclaw.datasource-pool-size`, default 8) rather than one per operation. For the CLI the difference is invisible; `serve` runs several turns at once, and against PostgreSQL each unpooled call costs a round trip and a backend process. The connection timeout is deliberately short: a worker holding a connection across a model call is a bug, and should surface as an error rather than a hang.

The vault stays encrypted in SQL. Skills (`skills/`), thread locks (`locks/`), the vault key, and the REPL history remain files. Retention rewrites a store in one transaction. The H2 default is for one machine; PostgreSQL is what a deployment with more than one host should use.

---

## Using jclaw

`jclaw --help` lists commands; every command and subcommand accepts `-h`. Any command also accepts the `--jclaw.*` configuration flags and `--debug` / `--trace`.

### One-shot turns: `run`

```bash
jclaw run "what does scripts/byte-verify.sh check?"
jclaw run --stream "explain the TurnMachine class"          # print prose as it arrives
jclaw run -t reviews "continue from where we left off"       # a named thread
jclaw run --jclaw.approval-mode=read-only "count the TODOs"  # never blocks; writes are denied
```

| Option | Meaning |
|---|---|
| `<prompt…>` | One or more words, joined with spaces. |
| `-t, --thread <id>` | Conversation thread to continue (default `default`). The thread's history is sent, compacted to the [context window](#context-window). |
| `--stream` | Stream model prose to the terminal as it is generated. Tool calls are not streamed. |
| `--attach <file>` | Attach a file to the turn; repeatable. Images (png, jpg, gif, webp, ≤ 5 MB) become image blocks the model sees; UTF-8 text files (≤ 256 KiB) are quoted under their name. Anything else is refused with a reason rather than sent as bytes the model cannot read. Attachments are stored in the transcript with the message. |

The reply is printed on stdout. Exit codes: **0** completed, **1** failed or cancelled, **2** parked (an approval gate, an auth gate, or a child run the parent is waiting on; the gate id is printed). Ctrl-C stops the run at the next safe point — between effects, never mid-tool.

The system prompt the model sees is: your configured `system-prompt`, then a workspace section naming the directory (never its absolute path), then one-line summaries of installed skills. It is frozen when the run is admitted, so a resume replays exactly what the run started with.

**One run per thread.** Two `jclaw run -t x` processes started together would otherwise interleave two conversations in one transcript, so a thread is locked for the duration of a run (an OS file lock under `<state-dir>/locks/`, which dies with the process, so a crash never leaves a thread stuck). The second submission fails immediately with `thread_busy` and nothing is recorded; wait or use another thread. Parked runs do not hold the lock.

#### Context window

A thread's whole history is kept in the transcript, but a model request carries only what `context-max-messages` and `context-max-tokens` admit. Compaction keeps the newest messages that fit, cuts only where a request may legally begin (a user message, or an assistant message behind a short synthetic user notice), never separates a tool call from its results, and always keeps the message you just typed. When anything is dropped the model is told how many messages were omitted since the start of the thread, and, with `context-summarise` on (the default), what they contained: before the real call the machine asks the model to summarise the dropped span in one extra, non-streamed call, and the summary rides in the same notice. Summaries compose: a later pass that drops the notice hands it to the next summary. If the summary call fails the turn continues with the bare count. The same policy is applied before **every** model call, so a long tool-heavy run is bounded too, not just a long thread.

### Interactive sessions: `repl`

```bash
jclaw repl
jclaw repl --stream -t scratch
echo "list the modules" | jclaw repl --quiet      # piped input works too
```

| Option | Meaning |
|---|---|
| `-t, --thread <id>` | Starting thread (default `default`). |
| `--stream` | Stream replies. |
| `--quiet` | Suppress banner and prompt markers (for pipes). |
| `--no-history` | Do not read or write `~/.jclaw/repl-history`. |

The REPL uses JLine in **emacs mode**: Up/Down history, Ctrl-A/E, Ctrl-K/U/W/Y, Alt-B/F. History persists across sessions (1000 lines, duplicates and space-prefixed lines ignored; credential-looking lines are excluded on a best-effort basis). Ctrl-C abandons the current line, Ctrl-D or `exit` quits. Bash-style `!` history expansion is disabled so prompts with exclamation marks survive intact.

Lines starting with `/` are **session controls** and never reach the model. Typing `/` alone lists them; Tab completes with descriptions.

| Slash command | Effect |
|---|---|
| `/help` | List commands. |
| `/tools` | Show the capability surface visible to the model. |
| `/thread [id]` | Show or switch the current thread. |
| `/new` | Switch to a fresh thread (`t-<8 hex>`). |
| `/stream [on\|off]` | Toggle or set streaming. |
| `/exit`, `/quit` | Leave. |

When a turn parks on an approval gate, the REPL asks there and then: it shows what is being approved and reads `y`, `n`, or `l` for later. Either answer resumes the run and prints what came back, a denial included, since the model is told and the turn finishes without the effect. Anything else, an empty line included, leaves the gate open: silence is not consent. A piped or scripted session is never asked and still prints the `jclaw approvals approve <gate>` command, so nothing that automates jclaw has to change.

### Approval gates: `approvals` and `resume`

When the model asks for something above your approval ceiling, the run **parks**: its state is checkpointed, the gate is written to `approvals.jsonl`, and the command exits 2 with the gate id.

```bash
$ jclaw run "create a NOTES.md with a summary of the build"
jclaw: run parked awaiting approval
  gate  gate-7f3a…  builtin.write_file {path=NOTES.md, content=…}
  resolve with: jclaw approvals approve gate-7f3a…

$ jclaw approvals list                     # gates awaiting a decision
$ jclaw approvals approve gate-7f3a…       # record approval AND resume the run
$ jclaw approvals approve gate-… --no-resume   # record only; resume later
$ jclaw approvals deny gate-…              # record denial (run stays parked)
$ jclaw approvals deny gate-… --resume     # deny and resume: the model is told, and may replan
$ jclaw resume <run-id>                    # resume any parked run by id (shown by `status`)
```

What happens on resume is the point of the design: the run rehydrates its checkpoint and **re-dispatches** the gated call through the kernel, which consults the approval store for that exact invocation. Approved → the tool runs. Denied → the model receives a denial result and continues. Still undecided → the run parks again **on the same gate**, so you never find duplicates. Nothing is ever assumed from the fact that you typed `resume`.

**Gates expire.** An unanswered gate lapses after `approval-ttl` (24 hours by default). `approvals list` hides lapsed gates (`--all` shows them), `approve`/`deny` refuse them, and resuming the run raises a fresh gate with the current arguments in front of you. A decision, once made, never expires.

`resume` reports like `run`: 0 completed, 2 parked again, 1 failed. `approvals approve/deny` exit 1 for an unknown or already-decided gate.

**Auth gates.** A provider that refuses for want of credentials (no `OPENROUTER_API_KEY`, a rejected key) does not fail the turn: the run parks `BLOCKED_AUTH` at a replay-safe checkpoint and the gate, naming the missing credential, appears in `jclaw approvals list` with kind `auth`. There is nothing to approve; set the credential and `jclaw resume <run-id>`, and the model call is re-attempted from exactly where it stopped. Resuming without the credential parks again.

### Queued turns: `submit` and `worker`

`run` executes a turn inline. `submit` only makes it durable, prints the run id, and returns; a worker executes it later under a concurrency cap:

```bash
jclaw submit -t reviews "summarise yesterday's changes"     # prints run_…, exits at once
jclaw submit -t reviews "and list open questions"           # queued behind the first on that thread
jclaw worker --concurrency 4                                # executes queued runs, fires routines, sweeps leases
jclaw worker --once                                         # one pass, then exit
```

Scheduling is a pure function over the queue: oldest first, at most `--concurrency` in flight, and never two runs on one thread at a time (the second waits a pass). A queued run is seeded with the conversation as of its own submission, so several turns queued on one thread answer in order, each seeing the replies to the ones before it. Runs requeued by `recover` are picked up the same way, so with a worker running a crashed turn resumes without a human typing `resume`; so are parents waiting on an asynchronous subagent. `submit --attach` works like `run --attach`.

### The HTTP surface: `serve`

`serve` is `worker` with an ingress: the JDK's HTTP server in front of the same runtime, scheduler, routine firing, lease sweep, and hourly retention.

```bash
export JCLAW_SERVE_TOKEN=$(openssl rand -hex 16)      # optional but wise; blank = no auth
jclaw serve --port 8080 --concurrency 4                # binds 127.0.0.1 unless --host says otherwise
jclaw serve --concurrency 4 --per-user 1 \
  --jclaw.serve-users.alice=$(openssl rand -hex 16)    # named users are tenants of their own
```

| Route | Does |
|---|---|
| `GET /` | A small browser UI: pick a thread, read the transcript, send turns, watch the run's events, approve or deny gates. Pure HTML and script over the routes below. |
| `POST /v1/chat/completions`, `GET /v1/models` | The OpenAI chat-completions shape, buffered or `"stream": true`, so any OpenAI SDK or tool can drive the agent (`base_url=http://127.0.0.1:8080/v1`, `api_key=<serve-token>`). Executes in the request. A stateless client's earlier turns are replayed into a fresh thread; send `X-Jclaw-Thread: <id>` to keep a persistent one. Client `system` messages are ignored: the operator's prompt governs. A parked run comes back as a completion explaining the gate, with `X-Jclaw-Run`, `X-Jclaw-Status`, and `X-Jclaw-Gate` headers. |
| `POST /threads/{thread}/turns` `{"text": "…", "attachments": [{"mediaType": "image/png", "data": "<base64>"}]}` | Enqueues a turn and returns `202 {"run": "run_…"}` at once; nothing executes in the request thread. |
| `GET /runs/{run}` | The run's projection folded from its events (status, timings, model calls and tokens, capability calls, injection findings, the open gate, failure) plus `reply` once completed. |
| `GET /runs/{run}/events` | Server-sent events following that run's audit log, replaying what exists and pushing new entries until the run is terminal; each `data:` is the same redacted record the JSONL file holds. |
| `GET /threads/{thread}/messages` | The transcript. |
| `GET /approvals`, `POST /approvals/{gate}` `{"approved": true}` | Pending gates; decide one and requeue its run for the scheduler. |
| `POST /channels/{adapter}` | A messaging platform's webhook. The platform authenticates with its own signature or secret token, never the operator's. A message becomes a queued turn and the request returns at once; the reply is delivered when the run finishes. |
| `POST /hooks/{name}` | Fires the webhook routine of that name, authenticated by its own bearer secret rather than the operator's token. Returns `202 {"run": "run_…"}`; the body joins the prompt. |
| `GET /runs/{run}/trace` | The run as an OTLP/JSON trace: a root span with a child per model call, capability call, and gate, projected from the same events. |
| `GET /metrics` | Process metrics in Prometheus text format: runs, model calls and tokens, capability calls by outcome, gates, injections, hooks, latencies. |
| `GET /health` | Liveness. |

Every route requires `Authorization: Bearer <token>` when `serve-token` or `serve-users` is set, health included; the browser UI's event stream passes it as `?access_token=` because `EventSource` cannot set headers. There is no TLS: put a reverse proxy in front if it leaves the machine.

**Signing in.** Static tokens are for machines. People get sessions: `POST /login` as the operator mints one for a user, and with an OpenID Connect provider configured, `GET /login/oidc` starts an authorization code flow with PKCE and `GET /login/callback` finishes it and returns a session. A session names a person, carries a role, expires, and can be revoked with `POST /logout`; only its SHA-256 is stored, so the session file cannot be read back into access. The id token's signature is not verified, deliberately: in the code flow it arrives over TLS on a back-channel call this process made to the discovered token endpoint, authenticated by the client secret, which is the case the OIDC specification says may skip it. What is checked is what the channel cannot establish, the issuer, the audience, and the expiry.

**Roles** are `viewer`, `member`, and `operator`. They govern the product surface only: a viewer may read runs and traces but not start a turn or answer a gate. None of it changes what the kernel authorizes, which asks the same questions of everyone.

**Agents and tenant policies.** An agent is a named model and system prompt; the one a tenant uses becomes `TurnScope.agent()`, so a run records the configuration that produced it and a resume replays that one rather than whatever config says later. A tenant policy sets an approval mode and extra denials for one tenant, and can only narrow what the host permits. A tenant token budget is checked at admission, never mid-run, since stopping a turn halfway spends the tokens and produces nothing.

**Users are tenants.** A caller presenting a `serve-users` token runs as that user: thread `work` is really `alice:work`, so two users on the same thread name hold two conversations; their runs carry the user as the tenant of their `TurnScope`, and everything that keys on scope, memories, routines, approvals, the thread lock, separates by it without the stores knowing about HTTP. A user sees only their own runs and gates (another user's is a 404). The operator token is the `local` tenant, the one the CLI uses, so what you do in a terminal and in the browser is one conversation, and the operator reads every tenant's runs. `--per-user N` caps how many of one tenant's runs execute at once, so one busy user cannot take every slot. What this surface is *not*: a Slack or Telegram adapter, or a login system; tokens are static (see PARITY.md).

### Messaging channels

jclaw can be talked to from Slack or Telegram over the same runtime as everything else. A message becomes an ordinary queued turn, and the answer is delivered when the run finishes, which is why it survives gates, restarts, and a queue.

```bash
jclaw secrets set slack-verify --capability channel.connect --host slack.com   # the signing secret
jclaw secrets set slack-token  --capability channel.connect --host slack.com   # the bot token
jclaw serve --jclaw.channels.slack.verify-secret=slack-verify \
            --jclaw.channels.slack.token=slack-token
```

Point the platform's webhook at `POST /channels/slack` or `/channels/telegram`. Both credentials come from the vault, bound to the capability `channel.connect` and the platform's API host, so no token sits in configuration.

**Inbound is verified before it is read.** Slack signs each request, and the adapter recomputes the HMAC over the raw body and compares it in constant time, refusing anything whose timestamp is more than five minutes old, since a valid signature on an old body is a replay that would run a turn twice. Telegram does not sign, so it is verified by the secret token it echoes in a header, which is weaker because it does not bind the body, and it is what the platform offers. A bot's own messages are ignored, without which an answer in a channel would look like a new message and the loop would not stop.

**A conversation is a thread.** The reply target is derived from the channel and thread, so `slack:C123/1700000000.1` is one continuing jclaw thread rather than a series of unrelated questions, and it is stored durably so a reply finds its way back after an approval gate held the run for an hour. Replies go into the Slack thread the message was in.

### Tools the agent can use

`jclaw tools` prints the live surface with effect class, trust class, and whether each runs unattended under the current policy; `--verbose` adds argument schemas. The built-ins:

| Capability | Effect | Does |
|---|---|---|
| `builtin.echo`, `builtin.time` | PURE | Dispatch canary; current time in a zone. |
| `builtin.read_file` | READ_LOCAL | UTF-8 file contents (≤ 256 KiB). |
| `builtin.list_dir`, `builtin.glob`, `builtin.grep` | READ_LOCAL | Directory listing; `glob:` pattern match; regex search with `file:line:` hits (500 results max). |
| `builtin.write_file` | WRITE_LOCAL | Full-file write, creating parents. |
| `builtin.memory_search` / `builtin.memory_write` | READ_LOCAL / WRITE_LOCAL | Project-scoped durable memory (see below). |
| `builtin.skill_list` / `builtin.skill_read` | PURE | List skills; load one's full instructions. |
| `builtin.trigger_list` / `_create` / `_pause` / `_resume` / `_remove` | READ_LOCAL / PROCESS / WRITE_LOCAL / PROCESS / WRITE_LOCAL | Let the agent schedule its own routines. |
| `builtin.http_fetch` | NETWORK | HTTP GET with optional request headers, ≤ 128 KiB, 20 s, ≤ 5 redirects each re-checked by the egress guard; headers are dropped on a redirect to another host. Private, loopback, link-local, and cloud-metadata addresses are refused (unless `allow-private-networks`). A vault secret goes in a header as `{{secret:NAME}}`. |
| `builtin.shell` | PROCESS | `/bin/sh -c` in the workspace root; scrubbed environment (no API keys reach the child); 30 s default timeout (max 300); 64 KiB output cap; process tree killed on timeout. With `shell-backend: docker`, each command runs in a `docker run --rm` container instead: no network, the workspace as the only mount at `/workspace`, memory/CPU/pid limits, read-only root. |
| `builtin.spawn_subagent` | PROCESS | Delegate a task to a child run, inline or queued (see [Subagents](#subagents)). |
| `mcp.<server>.<tool>` | NETWORK, COMMUNITY trust | Tools advertised by registered MCP servers. **Always gated.** |

Every path a tool receives is resolved against the workspace with symlinks followed and containment checked on the real path; anything outside is `path_outside_workspace`. Tool output is redacted (known credential values, then key-shaped patterns) and bounded to 64 KiB *before* it is stored or shown to the model, then scanned for prompt-injection patterns (`injection-policy`). Per-capability egress allowlists (`tool-egress`) and rate limits (`tool-rate-limits`) apply on top; a call past its cap is denied `rate_limited` without raising a gate.

### Durable memory: `memory`

Memories are facts the agent (or you) chose to keep. They are scoped to the **project** — the workspace directory name — so an agent working in one repository never retrieves memories from another; retrieved memories re-enter prompts, and cross-project leakage would be a prompt-injection vector.

```bash
jclaw memory write "the release branch is cut on the first Monday of the month" --tags process,release
jclaw memory search release cadence          # BM25 + recency (+ vector), fused with RRF
jclaw memory search -n 10 deploy
jclaw memory list                            # newest first (default 20)
jclaw memory forget mem_…                    # by id
jclaw memory reindex                         # embed memories that lack a vector for the configured model
```

The model has `builtin.memory_write` (gated in `interactive`) and `builtin.memory_search` (unattended). Ranking fuses up to three independent rankings via reciprocal rank fusion (k = 60): Okapi BM25 over the text, recency, and, when an embedding provider is configured, cosine similarity to the query's embedding. A blank query returns the most recent. Listing and deletion are CLI-only on purpose.

**Vector ranking** is additive. With `embedding-provider: none` (the default) retrieval is BM25 + recency exactly as before. Configure `ollama` (with `nomic-embed-text` pulled) or `openai` and every new memory is embedded on write, every query on search, and the vector list joins the fusion, which is what surfaces "the automobile needs new tyres" for a query about the car. Embeddings are stored inline in `memory.jsonl` with the model that produced them; vectors from different models are never compared, so after switching models run `jclaw memory reindex`. Embedding failures degrade rather than break: a memory is still written without a vector, a search still runs on the other two rankings, and `jclaw models` / `jclaw doctor` report the embedding configuration.

### Skills: `skills`

A skill is a directory containing a `SKILL.md`:

```
~/.jclaw/skills/
└── release-notes/
    └── SKILL.md
```

```markdown
---
name: Release notes
description: Draft release notes from merged PRs since the last tag
when-to-use: the user asks for release notes or a changelog entry
---
1. Run `git log --oneline <last-tag>..HEAD` ...
```

Frontmatter is flat `key: value` lines (`name`, `description`, `when-to-use`); the directory name is the skill id. Installing is `cp -r`, removing is `rm -r`; a skill that comes as a signed package goes through `extensions install` instead (below). Only the one-line summaries go into the system prompt; the model calls `builtin.skill_read` to load full instructions when a task calls for them (progressive disclosure). Bodies are capped at 64 KiB.

```bash
jclaw skills list
jclaw skills show release-notes
```

### Scheduled routines: `routines` and `worker`

A routine is a prompt that runs on its own thread, under your configured provider and approval mode, when something makes it fire. Exactly one trigger per routine, in four forms:

```bash
jclaw routines add --name "morning triage" --cron "0 9 * * MON-FRI" --zone Europe/Rome \
  "list open TODO comments added since yesterday and write a summary to TRIAGE.md"
jclaw routines add --name pulse --every 30m "check the build queue and report anything stuck"
jclaw routines add --name deploy --webhook "summarise this deploy notification"
jclaw routines add --name onfail --on run.finished --when status=FAILED \
  "a run just failed; read its trace and suggest what to look at"

jclaw routines list            # id, trigger, status (DUE / scheduled / paused), next fire
jclaw routines list --due
jclaw routines pause <id>
jclaw routines resume <id>
jclaw routines remove <id>
```

| Trigger | Fires when |
|---|---|
| `--cron "0 9 * * MON-FRI"` | The classic five fields (`minute hour day-of-month month day-of-week`) with `*`, lists, ranges, `/step`, `SUN…SAT` names, and Vixie semantics (day-of-month and day-of-week are a union when both are restricted), evaluated in `--zone`. |
| `--every 30m` | An interval (`30m`, `2h`, `1d`, or an ISO duration like `PT30M`) has passed since the last firing, or since creation. A heartbeat drifts with execution rather than snapping to a wall-clock grid, which is what you want for "check every so often". |
| `--webhook` | Someone `POST`s to `/hooks/<name>` on `serve` with the bearer secret printed once at creation. Only its SHA-256 is stored. The body, bounded to 16 KiB, is appended to the prompt. |
| `--on run.finished --when status=FAILED` | A matching audit event is written. The event types a trigger may name are `run.finished` and `gate.raised`; `--when` adds attribute equalities (`status`, `failure`, `thread`, `kind`). The event's attributes are appended to the prompt. |

A trigger that can never fire is rejected at creation. `--thread` defaults to the name lower-cased with hyphens; `--zone` to the system zone. The agent's own `builtin.trigger_create` may set a cron or an interval, never a webhook or an event trigger: one grants an outside caller a way in, the other reacts to other runs, and neither is something a model should arrange for itself.

Two rules stop event triggers chasing their own tails: only those two event types may drive one, and an event from a run on any routine's thread never fires anything, so a routine's own run finishing cannot re-trigger it or a sibling.

**Nothing fires routines on its own.** Choose one of two drivers:

```bash
# Option A: system cron — survives reboots, logs, familiar to operators
* * * * * /usr/local/bin/jclaw routines run-due --jclaw.workspace=/path/to/project

# Option B: a supervised polling process
jclaw worker                   # poll every 30 s (minimum 5); also executes queued runs (see `submit`)
jclaw worker --interval 60 --concurrency 4
jclaw worker --once            # one poll, then exit — handy for testing
```

`run-due` fires everything currently due and exits non-zero if any fired routine failed, so cron surfaces it. A routine that missed several slots (machine asleep) fires **once**, not once per missed slot; the firing is recorded before the turn starts so a crash cannot re-fire in a loop. `--dry-run` shows what would fire. Each `worker` tick also sweeps expired leases before claiming work.

Routines run unattended, so pair them with `--jclaw.approval-mode=read-only` (writes are denied, never parked) or accept that a gated call will park the routine's run until someone approves it.

### Extensions: `extensions`

An extension is a directory with a manifest, `jclaw-extension.json`, and either a `SKILL.md` or an MCP server to launch:

```json
{"name": "github-tools", "version": "1.2.0", "kind": "mcp", "description": "GitHub over MCP",
 "command": ["npx", "-y", "@acme/github-mcp"], "env": ["GITHUB_TOKEN"],
 "hosts": ["api.github.com"], "effect": "read_local", "publisher": "acme"}
```

```bash
jclaw secrets set gh-mcp --capability mcp.connect --host api.github.com   # the value, once, into the vault
jclaw extensions install ./github-tools --secret GITHUB_TOKEN=gh-mcp     # the name of the vault entry, not the value
jclaw extensions list                                              # name, version, kind, trust, enabled
jclaw extensions disable github-tools ; jclaw extensions enable github-tools
jclaw extensions remove github-tools
```

The manifest declares the kind, the command, the environment *names* the server needs, the hosts it says it reaches, the effect class its tools claim, and optionally a publisher. A value is never given at install: `--secret VAR=name` records which vault entry supplies each variable, and the value is leased when the server starts. The secret must be bound to the capability `mcp.connect`, and for a package that declares hosts, to those hosts, which a `VERIFIED` package has signed. A skill package is copied into the skills directory while enabled; an MCP package's server is started beside the `mcp add` servers, under `mcp-backend` like any other.

**A third kind: WebAssembly.** A `wasm` package ships `module.wasm` and declares the tools it offers and the host functions it wants. Each tool registers as `wasm.<extension>.<tool>` and goes through the authority gate like anything else, because a sandbox decides what code can reach and the kernel decides whether it may.

```json
{"name": "counter", "version": "1.0", "kind": "wasm",
 "permissions": ["log", "read_file"], "tools": ["count"]}
```

The runtime is pure Java, so the native image keeps working. Three things bound a module. Its memory is capped in pages. Every instruction is counted and the module is stopped when its budget is spent, which turns "please do not loop forever" into arithmetic. And it reaches the outside world only through host functions the manifest asked for and the host implements: `log`, `read_file` through the workspace guard, `http_get` through the egress guard. A module that imports one it was not granted fails to instantiate and never runs, so an ungranted capability is not a check that could be forgotten but a name that does not resolve.

A module is instantiated per call, so no tool call leaves state for the next. The calling convention is three exports, `memory`, `jclaw_alloc(len) -> ptr`, and `jclaw_call(ptr, len) -> i64` where the result packs a pointer and a length, kept small because each addition is another thing a module author can get wrong.

**Trust is decided at install, once, by signature.** A publisher generates a key pair with `jclaw extensions keygen --out keys` and signs a package with `jclaw extensions sign ./pkg --key keys/publisher.key`, which writes `jclaw-extension.sig`: an Ed25519 signature over a digest of every file in the package. An operator who lists the publisher's public key under `trusted-publishers` gets a **`VERIFIED`** install, and a verified manifest's declared effect class is believed: a read-only MCP tool that declares `read_local` can run unattended in `trusted` mode. An unsigned package installs as **`COMMUNITY`**: its tools are `NETWORK` whatever the manifest claims, and every call gates. A package whose signature does not verify, or whose publisher is not trusted, is refused outright, since a package claiming a publisher it cannot prove is worse than one claiming none. Editing a signed package breaks its signature.

**Registries: `search`, `add`, `outdated`, `upgrade`.** A registry is a static document at `<base>/index.json` listing packages, versions, and a URL and digest for each. That is the least interesting design available on purpose: no protocol, no account, nothing to run but a web server, so anyone can publish one.

```yaml
jclaw:
  extension-registries: [https://extensions.example.com]
```

```bash
jclaw extensions search changelog          # what the registries offer, newest version first
jclaw extensions add changelog             # newest; or changelog@1.2.0 for an exact version
jclaw extensions outdated                  # installed packages a registry has something newer for
jclaw extensions upgrade [name] [--dry-run]
```

Coming from a registry earns a package nothing. The archive is unpacked into a scratch directory under the state directory, the package digest is recomputed and compared with what the index advertised, and only then does the ordinary install path run — the one that verifies the signature and decides trust. An index that lies about a digest is caught before anything is installed; an index that omits one gets no trust for saying nothing. Archive entries whose names climb out of the directory are refused, the archive is bounded in size and entry count, redirects are never followed, and the registry URL goes through the egress guard like any other. The scratch directory is removed whether or not the install succeeded: a package that failed to verify should not be left where someone could install it by hand. `upgrade` carries the secrets the previous install was given, because those are the operator's, not the registry's. Versions compare numerically run by run, so 1.10.0 is newer than 1.9.0.

**Profiles** are named sets of extensions to have enabled together:

```yaml
jclaw:
  extension-profiles:
    prod: reviewed-skill,github-tools
    dev: reviewed-skill,github-tools,scratch
  extension-profile: prod
```

`jclaw extensions profile prod` enables exactly what the profile lists and **disables what it omits** — a profile that could only ever add things could not take anything away, which is most of what switching to the reviewed set is for. Applying the same profile twice changes nothing; a name the profile lists but nothing has installed is reported, not an error.

### MCP servers: `mcp`

jclaw speaks [Model Context Protocol](https://modelcontextprotocol.io) to external tool servers (JSON-RPC 2.0, protocol `2025-03-26`, negotiated down by servers on the older revision) over two transports:

- **stdio** — the server is a child process, the common form.
- **streamable HTTP** — the server is remote. Each message is a POST; the server may answer with a JSON body or an SSE stream, and both are read, so a server that sends progress notifications before its result works unchanged. The session id it returns is echoed on every later request. Redirects are never followed: an endpoint that moves is configuration to fix, not a hop to take.

An MCP server is third-party code with its own network stack. On the host it can reach anything and read the workspace directly; with `mcp-backend: docker` each server runs inside the same container contract as the sandboxed shell, with the workspace as its only mount, the network as `mcp-sandbox-network` says (`none` by default), and resource limits. Its configured environment is passed to the container **by name**, never as a value on the command line. The protocol is unchanged: the container's stdio is the server's.

```bash
jclaw mcp test fs --jclaw.mcp-backend=docker --jclaw.mcp-sandbox-image=node:22-alpine
```

```bash
jclaw mcp add --name fs npx -y @modelcontextprotocol/server-filesystem .
jclaw mcp add --name gh --url https://mcp.example.com/mcp --auth-secret gh-mcp
jclaw mcp add --name fs2 --secret API_KEY=fs-token npx -y @acme/fs-mcp   # a stdio server's environment, from the vault
jclaw mcp test fs              # start, handshake, list tools, shut down
jclaw mcp list
jclaw mcp refresh [name]       # forget cached surfaces; the next run rediscovers
jclaw mcp toggle fs --disable  # keep the config, stop loading it
jclaw mcp toggle fs
jclaw mcp remove fs
```

The command is stored as argv (never re-parsed through a shell). A stdio server runs with the workspace as its working directory and a scrubbed environment, plus whatever `--secret VAR=name` maps in, leased from the vault at start. Configuration holds the names; the values live in the vault and nowhere else. For a subprocess the host half of a binding cannot be enforced, since its egress is not observable, so the capability alone binds unless the package declares hosts. A remote endpoint goes through the **egress guard** first, exactly as a tool's URL does: an MCP server on a private address is refused unless `allow-private-networks` says otherwise. Its bearer token, when it needs one, comes from the [secret vault](#secrets-the-agent-may-use-but-never-see) rather than config: create it bound to the capability `mcp.connect` and the endpoint's host, and name it with `--auth-secret`. Nothing below the application layer ever holds the vault; the transport receives a finished header value.

```bash
echo -n "$GH_MCP_TOKEN" | jclaw secrets set gh-mcp --capability mcp.connect --host mcp.example.com
```

**What a server offers.** Tools register as `mcp.<server>.<tool>`. A server that declares resources also gets `mcp.<server>.list_resources` and `read_resource`; one that declares prompts gets `list_prompts` and `get_prompt`. Declared, not assumed: a server is never asked for a capability it did not claim. All of them carry `COMMUNITY` trust, which means **every call needs approval in every mode, including `trusted`** — no setting can raise a third-party ceiling. Only text content is returned to the model; images, binary resources, and other content types are named and elided.

**Servers start on first use.** The capability surface has to be known before a turn begins, and discovering it means a handshake, which used to mean spawning every configured server on every `jclaw` invocation. The surface is now cached per server, keyed by a fingerprint of its command, URL, and environment *names*, so a later process publishes the capabilities from the cache and starts nothing until one is actually invoked. Change any of that and the entry is invalidated; `mcp refresh` drops it by hand; `jclaw.mcp-lazy=false` rediscovers on every start. A server that fails to start is reported on stderr and skipped, never fatal.

### Subagents

The model can call `builtin.spawn_subagent` with a `prompt` (and optional `description`) to delegate a task. The child is an ordinary run on the **same** machinery — same turn machine, same interpreter, same capability host and approval policy — on a fresh thread derived from the parent's (`<parent>~sub1-…`), so it inherits none of the parent's conversation and only its conclusion travels back. Nesting depth is derived from the thread id rather than passed by the model, and is capped at 3. Spawning is `PROCESS`-class, so it is gated in `interactive`.

By default the child runs inside the parent's tool call. With `subagents-async: true` the child is **queued** instead: the parent parks `WAITING_PROCESS` on a process gate that names the child run, a `worker` or `serve` executes the child under the concurrency cap, and when it finishes the scheduler requeues the parent, which re-dispatches the same call and receives the child's conclusion as the tool result. Several children of one parent therefore run in parallel. Without a worker the parent would wait indefinitely, which is why the synchronous mode is the default.

### Hooks and loop families

Two seams let host code change how a run behaves without touching the machine.

**Hooks** run before and after every model call and capability dispatch. A hook may narrow a model request (amend the system prompt, drop tools) or veto it, and may rewrite a capability's arguments or veto the call; it may not widen a request, change a call's identity, or bypass the kernel, which still checks whatever a hook hands back. A rewrite or veto is recorded as a `hook.fired` audit event. Built-in hooks are enabled by id in `hooks`; any Spring bean implementing `LoopHook` is picked up as well, which is the seam a plugin uses.

**Loop families** are strategies over the same state, decisions, and checkpoints. `canonical` is the machine described in [ARCH.md](ARCH.md). `reflective` intercepts the moment the canonical machine would persist a reply and first asks the model to review the draft against the conversation and return the reply it stands behind; the revision replaces the draft, the review call is charged to the budget, is skipped when the budget is exhausted, and falls back to the draft if it fails. A family cannot invent a new kind of effect: the decision type is sealed and the interpreter is the only executor.

### Streaming

`run --stream`, `repl --stream`, and `/stream on` print model prose as it arrives. Streaming is presentation only: the machine receives the same complete response either way, so a streamed run and a buffered one produce identical decisions and transcripts. True incremental output is implemented for the Anthropic provider (SDK event stream) and for every OpenAI-compatible provider (server-sent events: OpenAI, OpenRouter, Ollama, `local`). Tool-call arguments arrive as JSON fragments, so they are assembled and delivered whole in the final response rather than streamed. The `failover` chain streams through the first provider that accepts the model and will not fail over once prose has been shown, since a second provider would start a second answer on top of the first.

### Crash recovery: `recover`

Every running turn holds a **lease** (2 minutes, renewed between effects). If a worker dies, its run's lease expires; `recover` decides what to do with each such run, and explains every decision:

```bash
jclaw recover --dry-run
  REQUEUED  run-…  resumable from BEFORE_MODEL             [dry-run]
  FAILED    run-…  checkpoint AFTER_CAPABILITY may have side effects
  skipped   run-…  parked on a gate, not executing
  skipped   run-…  within grace period; worker may still be alive
jclaw recover
jclaw resume <run-id>          # for anything REQUEUED
```

A run is requeued only when its latest checkpoint is provably replay-safe (taken before a model call or before a gate — nothing had escaped the process) **and** a full extra lease TTL has passed since expiry (a merely slow worker would have renewed). Everything else becomes a terminal `lease_expired` failure for you to resubmit deliberately. Unknown checkpoint kinds fail closed.

### Retention: `retain`

```bash
jclaw retain --dry-run     # per store: kept, dropped, and whether anything would be rewritten
jclaw retain               # drop old rows of finished runs from results, events, and checkpoints
```

A row is dropped only when it is older than the store's `retention-*` age **and** its run is terminal; a parked, queued, running, or unknown run keeps every row however old. The rewrite is atomic (temp file plus rename). The transcript is never swept: it is the conversation. `worker` and `serve` sweep once an hour.

### Inspecting the system: `status`, `tools`, `models`, `doctor`

```bash
jclaw status               # last 20 events from the audit log (-n to change)
jclaw status --run run_…   # one run's projection: status, timings, calls, tokens, gates, findings
jclaw status --run run_… --trace   # plus the run's spans: model calls, capabilities, gates, with durations
jclaw tools [--verbose]    # capability surface: effect, trust, unattended?, schemas
jclaw models [--probe]     # providers and credentials; --probe sends a tiny real request
jclaw doctor               # configuration, security posture, and checks; exit 1 on a real problem
```

`status` reads the redacted event log rather than internal state, so what you see is exactly what was durably recorded: claims, model calls with token counts and latency, capability invocations with outcome, injection findings (severity, rule count, and whether the output was warned about, sanitised, or blocked), gates, checkpoints, and finishes. `doctor` is designed as a CI preflight: `[fail]` lines (unreadable workspace, unwritable state dir, missing credential) set the exit code; warnings (private networks allowed, `trusted` mode) do not.

### Observability

The event log is the substrate; metrics and traces are projections of it, so neither can disagree with the audit record or carry anything a redactor did not pass.

- **Metrics.** `serve` exposes `/metrics` in Prometheus text format: `jclaw_runs_submitted_total`, `jclaw_runs_finished_total{status}`, `jclaw_model_calls_total{provider,model}`, `jclaw_model_tokens_total{kind}`, `jclaw_capability_calls_total{capability,outcome}`, `jclaw_gates_raised_total{kind}`, `jclaw_injections_total`, `jclaw_secrets_injected_total`, `jclaw_hooks_fired_total`, `jclaw_checkpoints_total`. Counted at the moment each event is written; in-memory for the life of the process.

  Latencies are **real histograms**: `jclaw_model_latency_millis` and `jclaw_capability_latency_millis` emit cumulative `_bucket{le=…}` series (1 ms to 60 s, roughly 1-2-5 per decade) with `_count`, `_sum`, and a non-standard `_max` kept because it is the one number a human reads straight off a terminal. Count and sum alone answered "is it slow on average", and an average hides the tail — which is where a timeout lives. With buckets the quantile is the scraper's to compute, which is where that decision belongs.

- **Trace context on outbound calls.** When the interpreter is making a model call it opens a W3C trace scope, and the Anthropic, OpenAI-compatible, and MCP-over-HTTP adapters put a `traceparent` header on the request. The ids are the run's own: `RunTrace.traceId(run)` and the span id of the model-call span, so a collector holding the provider's span and jclaw's OTLP export of the same run puts them under one trace and one parent — not two that merely overlap in time. The header carries two opaque identifiers and nothing else; the trace id is a SHA-256 of the run id, so it is not the run id.

  `builtin.http_fetch` deliberately does **not** send one. That URL is model-controlled, and a trace id sent to an arbitrary host is a correlator handed to somebody who did not need it.
- **Traces.** A run's events become one trace: a root span for the run (status, tokens, iterations, thread), a child span per model call and capability call with their measured latency, a span per gate from raised to resolved, and span events for claims, checkpoints, injection findings, secret injections, and hook firings. Trace and span ids derive from the run id, so re-exporting deduplicates. Read one with `status --run … --trace` or `GET /runs/{run}/trace` (OTLP/JSON), or set `otlp-endpoint` to have every finished run POSTed to a collector.

### Tracing a turn

```bash
jclaw run --debug "…"    # every pipeline step: admission, phase+observation→decision,
                         # checkpoints, the kernel's authority path, provider timings, exit validation
jclaw run --trace "…"    # …plus payloads: system prompt, messages, tool args, outputs — redacted and bounded
jclaw run --logging.level.io.jclaw.kernel=DEBUG "…"   # target one logger
```

Default level is INFO and prints only the reply. The domain never logs; the interpreter narrates the machine's decisions.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success (or nothing to do). |
| 1 | Turn failed or cancelled; not-found / conflict for `memory forget`, `skills show`, `routines *`, `mcp *`; a fired routine failed (`run-due`); probe failed (`models`); a `[fail]` check (`doctor`); config file exists (`onboard` without `--force`); unhandled error. |
| 2 | `run` / `resume` / `approvals`: the run parked (approval gate, auth gate, or waiting on a child run). Also picocli usage errors and Spring startup failures (misconfiguration), which happen before any command runs. |

`onboard --print` shows the config that would be written without writing it.

---

## Security model in one page

- **Workspace confinement.** Every path goes through `WorkspaceGuard`: normalized, symlinks resolved (for new files, the nearest existing ancestor is resolved), and containment checked on the real path — never by string prefix.
- **Egress guard.** `http_fetch` refuses non-http(s) schemes, URLs with embedded credentials, cloud-metadata hostnames, and any hostname resolving to *any* private, loopback, link-local, CGNAT, or ULA address (all resolved addresses must be public, defeating DNS-based bypasses). Redirects are re-checked per hop.
- **One authority gate.** `DefaultCapabilityHost` orders checks so a denied call never reaches side-effecting code: existence → policy denial → rate limit → approval → dispatch → redact/bound/store → injection scan. Per-invocation fingerprints, sticky denials, third-party trust ceilings that policy cannot raise, per-tool egress lists that can only narrow.
- **Untrusted tool output is framed.** Instruction-shaped text in a tool result is audited and, by default, fenced and defused before the model sees it; `block` withholds it. The stored payload is never altered.
- **Untrusted processes can be contained.** `shell-backend: docker` runs every command, and `mcp-backend: docker` every MCP server, in a throwaway container with no network unless configured, the workspace as its only mount, and resource limits. `doctor` says which backends are active.
- **Least-privilege lanes.** Tool handlers receive a context with exactly four methods (resolve path, check egress, display path, output budget). There is no method to obtain a secret; the dependency law bars tools, providers, and the loop from the vault port. Shell and MCP children get a scrubbed environment.
- **Extensions earn trust by signature.** A package signed by a publisher in `trusted-publishers` installs as `VERIFIED` and its declared effect class is honoured; anything else is `COMMUNITY`, `NETWORK`, and gated. A signature that fails refuses the install.
- **Secrets are leased, not held.** A `{{secret:NAME}}` reference is substituted by the kernel into one call's arguments, only for the bound capability and hosts, and masked out of that call's output. Nothing durable ever contains the value.
- **Structural redaction.** Events have no field for a prompt, argument, or host path. Tool output, provider errors, approval prompts, and trace logs all pass through the same redactor; truncation happens after redaction.
- **Untrusted exits.** The runtime re-resolves every reference a run returns before recording completion.
- **Fail-closed recovery.** Only provably replay-safe checkpoints are requeued.

Posture flags that deserve a second look are printed by `jclaw doctor`: `allow-private-networks=true` and `approval-mode=trusted`.

---

## Project layout

```
jclaw/
├── pom.xml                 reactor parent (Boot 4.1.1, Java 21, dependency management)
├── jclaw-contracts/        ports, turn vocabulary, refs, Result — no Spring, no HTTP
├── jclaw-domain/           pure functions: TurnMachine, Budget, Redaction, ranking, cron, recovery
├── jclaw-kernel/           CapabilityHost, CapabilityPolicy, WorkspaceGuard, EgressGuard
├── jclaw-loop/             EffectInterpreter — the only place an effect happens
├── jclaw-providers/        mock, Anthropic SDK, OpenAI-compatible, failover
├── jclaw-tools/            built-in capability handlers and the MCP client
├── jclaw-storage/          JSONL stores, hand-written codecs, filesystem skill catalog
├── jclaw-app/              Spring wiring, picocli CLI, JclawRuntime, scheduler, HTTP surface, native profile
├── scripts/byte-verify.sh  source-integrity guard
├── ARCH.md                 C4 architecture and the full turn lifecycle
└── PARITY.md               what IronClaw has that jclaw does not
```

Dependencies flow strictly downward (contracts ← domain ← kernel ← loop/tools/storage ← app; providers depend on contracts only) and `DependencyLawTest` enforces it. Notable choices: Jackson 3 (`tools.jackson`) via Boot 4 — do not add `jackson-databind` 2.x; hand-written codecs for native-image safety; JLine for the REPL; picocli bridged to Spring through its `IFactory`.

## Further reading

- [ARCH.md](ARCH.md) — C4 model (context, containers, components, code), the step-by-step lifecycle from prompt to result, and a full sequence diagram.
- [PARITY.md](PARITY.md) — an honest enumeration of what IronClaw has that jclaw does not.
- [CLAUDE.md](CLAUDE.md) — working notes for contributors and coding agents, including the pitfalls list.
- [IronClaw](https://github.com/nearai/ironclaw) — the original.
