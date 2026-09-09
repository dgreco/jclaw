# jclaw

**A hexagonal agent OS harness in Java 21 / Spring Boot 4.1 — an architectural clone of [IronClaw](https://github.com/nearai/ironclaw).**

jclaw runs an LLM agent loop the way an operating system runs a process: every effect the model asks for passes through one authority gate, every run is durable and resumable across process boundaries, and the decision logic is a pure function you can test without a network. It ships as a single uber jar or a GraalVM native binary and talks to Anthropic, OpenAI, OpenRouter, Ollama, or any local OpenAI-compatible server.

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
  - [Tools the agent can use](#tools-the-agent-can-use)
  - [Durable memory: `memory`](#durable-memory-memory)
  - [Skills: `skills`](#skills-skills)
  - [Scheduled routines: `routines` and `worker`](#scheduled-routines-routines-and-worker)
  - [MCP servers: `mcp`](#mcp-servers-mcp)
  - [Subagents](#subagents)
  - [Streaming](#streaming)
  - [Crash recovery: `recover`](#crash-recovery-recover)
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

Everything is durable JSONL under `~/.jclaw`: a run can park in one process, be approved in a second, and resume in a third. There is no server and no database.

**Status.** Milestones M0–M7 plus subagents, MCP, streaming, and lease-based crash recovery are complete. 201 tests pass across the modules, including 13 machine-checked architecture rules (ArchUnit). Both the uber jar and the native image are verified end to end, including subprocess spawning for MCP servers and shell tools.

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

Test totals by module (verified on this checkout): contracts 8 · domain 95 · kernel 14 · providers 23 · storage 11 · app 50 = **201, 0 failures**. `DependencyLawTest` in `jclaw-app` machine-checks the layer ladder with ArchUnit; the rules were confirmed to fire by planting deliberate violations.

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
| `injection-policy` | `sanitize` | What the kernel does with tool output that looks like a prompt injection: `off`, `warn` (audit event only), `sanitize` (fence it, defuse chat-template tokens, tell the model it is data), `block` (withhold HIGH-severity findings from the model). The stored payload is never altered. |
| `embedding-model` | *(provider default)* | `text-embedding-3-small` (openai), `openai/text-embedding-3-small` (openrouter), `nomic-embed-text` (ollama); **required for `local`**. Vectors carry their model id, so switching models means `jclaw memory reindex`. |

Logging is controlled through standard Spring properties (`--logging.level.io.jclaw=TRACE`) or the `--debug` / `--trace` shortcuts described under [Tracing a turn](#tracing-a-turn).

### Providers and credentials

| `provider` | Credential (env var) | Notes |
|---|---|---|
| `mock` | none | Default. Replies with a fixed message, or follows `mock-script`. |
| `anthropic` | `ANTHROPIC_API_KEY` (or `ANTHROPIC_AUTH_TOKEN`) | Official Anthropic Java SDK; adaptive thinking enabled; native tool-call shape; the only provider with true streaming. |
| `openai` | `OPENAI_API_KEY` | Chat Completions API. |
| `openrouter` | `OPENROUTER_API_KEY` | OpenAI-compatible gateway; model ids must be `org/model` (e.g. `anthropic/claude-sonnet-4.6`). Claude via OpenRouter goes through the OpenAI shim — no adaptive thinking; prefer `anthropic` for Claude. |
| `ollama` | none | Local daemon on loopback. |
| `local` | `LOCAL_API_KEY` (optional) | Any OpenAI-compatible server at `local-base-url`. Unauthenticated by default; a server that checks a token (vLLM `--api-key`) answers 401 with its own message. |
| `failover` | whatever is present | Builds a chain from configured providers in this order: Anthropic → OpenAI → OpenRouter → `local` (if `local-base-url` set) → Ollama. Providers with no credentials are omitted, not tried. |

Credentials are resolved **per request**, so a missing key does not prevent the process from starting: it shows up as an `AUTH` failure in the event log, and `jclaw doctor` reports it. Check what is configured with `jclaw models`; prove the active provider actually answers with `jclaw models --probe`.

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
├── approvals.jsonl     gates raised and decided
├── memory.jsonl        durable memories, project-scoped
├── routines.jsonl      scheduled routines
├── mcp.jsonl           registered MCP servers
├── repl-history        REPL line history
└── skills/<id>/SKILL.md
```

Because state is a directory, you can run several isolated agents by giving each its own `--jclaw.state-dir`. Delete the directory to start clean.

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

The reply is printed on stdout. Exit codes: **0** completed, **1** failed or cancelled, **2** parked on an approval gate (the gate id is printed so you can approve it). Ctrl-C stops the run at the next safe point — between effects, never mid-tool.

The system prompt the model sees is: your configured `system-prompt`, then a workspace section naming the directory (never its absolute path), then one-line summaries of installed skills. It is frozen when the run is admitted, so a resume replays exactly what the run started with.

**One run per thread.** Two `jclaw run -t x` processes started together would otherwise interleave two conversations in one transcript, so a thread is locked for the duration of a run (an OS file lock under `<state-dir>/locks/`, which dies with the process, so a crash never leaves a thread stuck). The second submission fails immediately with `thread_busy` and nothing is recorded; wait or use another thread. Parked runs do not hold the lock.

#### Context window

A thread's whole history is kept in the transcript, but a model request carries only what `context-max-messages` and `context-max-tokens` admit. Compaction keeps the newest messages that fit, cuts only where a request may legally begin (a user message, or an assistant message behind a short synthetic user notice), never separates a tool call from its results, and always keeps the message you just typed. When anything is dropped the model is told how many messages were omitted since the start of the thread. The same policy is applied before **every** model call, so a long tool-heavy run is bounded too, not just a long thread. There is no summarisation: the dropped span is gone from the model's view, not condensed.

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

When a turn parks on a gate the REPL prints the exact `jclaw approvals approve <gate>` command; resolving it still requires another shell (see PARITY.md).

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

What happens on resume is the point of the design: the run rehydrates its checkpoint and **re-dispatches** the gated call through the kernel, which consults the approval store for that exact invocation. Approved → the tool runs. Denied → the model receives a denial result and continues. Still undecided → the run parks again. Nothing is ever assumed from the fact that you typed `resume`.

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

Scheduling is a pure function over the queue: oldest first, at most `--concurrency` in flight, and never two runs on one thread at a time (the second waits a pass). A queued run is seeded with the conversation as of its own submission, so several turns queued on one thread answer in order, each seeing the replies to the ones before it. Runs requeued by `recover` are picked up the same way, so with a worker running a crashed turn resumes without a human typing `resume`. This is the shape any non-CLI surface needs, which is why it exists before one does.

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
| `builtin.http_fetch` | NETWORK | HTTP GET, ≤ 128 KiB, 20 s, ≤ 5 redirects each re-checked by the egress guard. Private, loopback, link-local, and cloud-metadata addresses are refused (unless `allow-private-networks`). |
| `builtin.shell` | PROCESS | `/bin/sh -c` in the workspace root; scrubbed environment (no API keys reach the child); 30 s default timeout (max 300); 64 KiB output cap; process tree killed on timeout. |
| `builtin.spawn_subagent` | PROCESS | Delegate a task to a child run (see [Subagents](#subagents)). |
| `mcp.<server>.<tool>` | NETWORK, COMMUNITY trust | Tools advertised by registered MCP servers. **Always gated.** |

Every path a tool receives is resolved against the workspace with symlinks followed and containment checked on the real path; anything outside is `path_outside_workspace`. Tool output is redacted (known credential values, then key-shaped patterns) and bounded to 64 KiB *before* it is stored or shown to the model.

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

Frontmatter is flat `key: value` lines (`name`, `description`, `when-to-use`); the directory name is the skill id. Installing is `cp -r`, removing is `rm -r` — there is deliberately no install command. Only the one-line summaries go into the system prompt; the model calls `builtin.skill_read` to load full instructions when a task calls for them (progressive disclosure). Bodies are capped at 64 KiB.

```bash
jclaw skills list
jclaw skills show release-notes
```

### Scheduled routines: `routines` and `worker`

A routine is a prompt that runs on a cron schedule, on its own thread, under your configured provider and approval mode.

```bash
jclaw routines add --name "morning triage" --cron "0 9 * * MON-FRI" --zone Europe/Rome \
  "list open TODO comments added since yesterday and write a summary to TRIAGE.md"
jclaw routines list            # id, status (DUE / scheduled / paused), next fire
jclaw routines list --due
jclaw routines pause <id>
jclaw routines resume <id>
jclaw routines remove <id>
```

Cron is the classic five fields (`minute hour day-of-month month day-of-week`) with `*`, lists, ranges, `/step`, `SUN…SAT` names, and Vixie semantics (day-of-month and day-of-week are a union when both are restricted). A schedule that can never fire is rejected. `--thread` defaults to the name lower-cased with hyphens; `--zone` to the system zone.

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

### MCP servers: `mcp`

jclaw speaks [Model Context Protocol](https://modelcontextprotocol.io) to external tool servers over **stdio** (JSON-RPC 2.0, protocol `2024-11-05`).

```bash
jclaw mcp add --name fs npx -y @modelcontextprotocol/server-filesystem .
jclaw mcp test fs              # start, handshake, list tools, shut down
jclaw mcp list
jclaw mcp toggle fs --disable  # keep the config, stop loading it
jclaw mcp toggle fs
jclaw mcp remove fs
```

The command is stored as argv (never re-parsed through a shell). Enabled servers are started when jclaw boots, with the workspace as their working directory and a scrubbed environment; each advertised tool registers as `mcp.<server>.<tool>` with `COMMUNITY` trust, which means **every call needs approval in every mode, including `trusted`** — no setting can raise a third-party ceiling. A server that fails to start is reported on stderr and skipped, never fatal. Only text content is returned to the model; images and other content types are elided.

### Subagents

The model can call `builtin.spawn_subagent` with a `prompt` (and optional `description`) to delegate a task. The child is an ordinary run on the **same** machinery — same turn machine, same interpreter, same capability host and approval policy — on a fresh thread derived from the parent's (`<parent>~sub1-…`), so it inherits none of the parent's conversation and only its conclusion travels back. Nesting depth is derived from the thread id rather than passed by the model, and is capped at 3. Spawning is `PROCESS`-class, so it is gated in `interactive`.

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

### Inspecting the system: `status`, `tools`, `models`, `doctor`

```bash
jclaw status               # last 20 events from the audit log (-n to change)
jclaw tools [--verbose]    # capability surface: effect, trust, unattended?, schemas
jclaw models [--probe]     # providers and credentials; --probe sends a tiny real request
jclaw doctor               # configuration, security posture, and checks; exit 1 on a real problem
```

`status` reads the redacted event log rather than internal state, so what you see is exactly what was durably recorded: claims, model calls with token counts and latency, capability invocations with outcome, injection findings (severity, rule count, and whether the output was warned about, sanitised, or blocked), gates, checkpoints, and finishes. `doctor` is designed as a CI preflight: `[fail]` lines (unreadable workspace, unwritable state dir, missing credential) set the exit code; warnings (private networks allowed, `trusted` mode) do not.

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
| 2 | `run` / `resume` / `approvals`: the run parked on a gate. Also picocli usage errors and Spring startup failures (misconfiguration), which happen before any command runs. |

`onboard --print` shows the config that would be written without writing it.

---

## Security model in one page

- **Workspace confinement.** Every path goes through `WorkspaceGuard`: normalized, symlinks resolved (for new files, the nearest existing ancestor is resolved), and containment checked on the real path — never by string prefix.
- **Egress guard.** `http_fetch` refuses non-http(s) schemes, URLs with embedded credentials, cloud-metadata hostnames, and any hostname resolving to *any* private, loopback, link-local, CGNAT, or ULA address (all resolved addresses must be public, defeating DNS-based bypasses). Redirects are re-checked per hop.
- **One authority gate.** `DefaultCapabilityHost` orders checks so a denied call never reaches side-effecting code: existence → policy denial → approval → dispatch → redact/bound/store. Per-invocation fingerprints, sticky denials, third-party trust ceilings that policy cannot raise.
- **Least-privilege lanes.** Tool handlers receive a context with exactly four methods (resolve path, check egress, display path, output budget). There is no method to obtain a secret. Shell and MCP children get a scrubbed environment.
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
├── jclaw-app/              Spring wiring, picocli CLI, JclawRuntime, native profile
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
