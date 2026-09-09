# jclaw vs IronClaw — Parity

An honest enumeration of what [IronClaw](https://github.com/nearai/ironclaw) (internally "Reborn") has that jclaw does not. jclaw is roughly 15k lines of Java against IronClaw's ~1.4M lines of Rust across ~63 crates. The **architecture** is equivalent — layer ladder, turn/run lifecycle, untrusted `LoopExit`, single `CapabilityHost` authority boundary, checkpoint-kind–driven recovery — but the **feature surface** is a small fraction. This file is the list, organised by IronClaw's own crate families so a gap can be traced to the crate that fills it upstream.

Sources: IronClaw's `README.md`, `crates/Architecture.md`, and the `crates/` listing as of September 2026; jclaw's code on this checkout. Where the upstream doc names a concept and jclaw has an equivalent under a different name, the mapping is given. Where the gap is uncertain it is marked *(unverified)*.

Legend: ✅ at parity · 🟡 partial · ❌ missing · ➕ jclaw-only

---

## 1. At a glance

| IronClaw area | Upstream crates | jclaw | Status |
|---|---|---|---|
| Layer ladder + architecture tests | `crates/AGENTS.md`, `ironclaw_architecture_tests` | 8 modules, `DependencyLawTest` (13 ArchUnit rules) | ✅ |
| Contracts / turn vocabulary | `contracts/{host_api, common, prompt_envelope, loop_contracts, extension_contracts, product_contracts}` | `jclaw-contracts` (loop + host + product vocabulary in one module; no prompt envelope, no extension contracts) | 🟡 |
| Pure agent loop, checkpoints, resumable state | `ironclaw_agent_loop`, `ironclaw_loop_host`, `ironclaw_turn_runner` | `TurnMachine` + `EffectInterpreter` + `JclawRuntime` | ✅ (one loop family) |
| Loop hooks | `ironclaw_hooks` | none | ❌ |
| Kernel: trust, authorization, approvals, capabilities, turns | `ironclaw_trust`, `_authorization`, `_approvals`, `_capabilities`, `_turns`, `_host_runtime` | `DefaultCapabilityHost`, `CapabilityPolicy`, `TrustClass`, `ApprovalStore`, `JclawRuntime.validate` | ✅ (single-tenant) |
| Kernel: resources, runtime policy, processes | `ironclaw_resources`, `ironclaw_runtime_policy`, `ironclaw_processes` | `Budget`; `CapabilityPolicy` postures + configurable hard denials; `RunStore` with leases and a per-thread `ThreadLock`; no process journal / process trees / deployment modes | 🟡 |
| Scheduler with bounded concurrency | `TurnRunScheduler`, `RebornTurnRunExecutor` | leases + heartbeats + reconciliation exist; nothing runs more than one worker | 🟡 |
| WASM lane | `ironclaw_wasm`, `ironclaw_wasm_limiter` | none | ❌ |
| Script / container sandbox lane | `ironclaw_sandbox` (Docker orchestrator/worker) | `builtin.shell` as an unsandboxed child process | ❌ |
| MCP lane | `ironclaw_mcp` | stdio JSON-RPC client, `tools/list` + `tools/call` | 🟡 |
| Extension system | `extension_registry`, `_host`, `_manager`, `_support`, `packages/*` | none (built-ins compiled in; MCP is the only external route) | ❌ |
| Products | `ironclaw_cli`, `_webui`, `_assistant`, `_operator`, `_openai_compat`, `_host_ingress`, Slack/Telegram channel packages | CLI + REPL only | ❌ |
| Substrates: filesystem, network | `ironclaw_filesystem`, `ironclaw_network` | `WorkspaceGuard`, `EgressGuard` | ✅ |
| Substrates: secrets | `ironclaw_secrets` (AES-256-GCM vault, leased handoff) | environment variables only; redaction of known values | ❌ |
| Substrates: safety | `ironclaw_safety` (injection detection, sanitization, leak detection, policy severities) | `Redaction`, `EgressGuard` with configurable allow/deny lists; no injection detection or sanitisation | 🟡 |
| Substrates: documents, libsql/Postgres, observability | `ironclaw_documents`, `ironclaw_libsql_runtime`, `ironclaw_observability` | JSONL files; SLF4J logs | ❌ |
| Events | `event_log`, `event_store`, `event_projections`, `event_streams` | `JsonlEventLog` (append + tail); no projections, no streams | 🟡 |
| Domains: threads, memory, skills, triggers, llm | `ironclaw_threads`, `_memory`, `_skills`, `_triggers`, `_llm` | `ThreadService`, `MemoryStore` + `EmbeddingProvider`, `SkillCatalog`, `RoutineStore`, `ModelProvider` | 🟡 |
| Domains: conversations, auth, identity, attachments, extractors, outbound | same-named crates | none | ❌ |
| Subagents | `ironclaw_loop_host` subagent port | `RuntimeSubagentHost` (child runs, depth ≤ 3) | ✅ |

---

## 2. Products and channels

IronClaw is a multi-surface runtime; jclaw has one product surface.

| Capability | IronClaw | jclaw |
|---|---|---|
| CLI (`run`, `repl`, `onboard`, `status`, `models`) | ✅ | ✅ |
| Web UI (`ironclaw_webui`, SSE + WebSocket browser gateway, login token) | ✅ | ❌ |
| OpenAI-compatible HTTP API (`ironclaw_openai_compat`) — use the agent from any OpenAI client | ✅ | ❌ |
| HTTP ingress / webhooks (`ironclaw_host_ingress`) | ✅ | ❌ |
| Slack and Telegram channel adapters (WASM channel packages implementing `ChannelAdapter`) | ✅ | ❌ |
| Operator / admin surface (`ironclaw_operator`) | ✅ | ❌ (partially covered by `status`, `doctor`, `recover`, `tools`) |
| Assistant product with conversation management (`ironclaw_assistant`, `ironclaw_conversations`) | ✅ | ❌ — jclaw has threads, not conversations with source/reply-target bindings |
| Source and reply-target bindings (`SourceBindingRef`, `ReplyTargetBindingRef`) | ✅ | ❌ — every reply goes to stdout |
| Long-running service (`ironclaw service restart`, `ironclaw serve`) | ✅ | ❌ — every command is a short-lived process; `worker` is the only long-lived mode and it only fires routines |
| `config list/set` commands | ✅ | ❌ — edit `~/.jclaw/jclaw.yaml` or pass `--jclaw.*` |
| Gate resolution inside the REPL | ✅ (WebUI approval flow) | ❌ — REPL prints the `approvals approve` command; another shell is needed |
| REPL slash commands (`/help /tools /thread /new /stream`) | not documented upstream | ➕ |

---

## 3. Runtime lanes and sandboxing

This is the largest gap. IronClaw's premise is that already-authorized work runs **in isolation**; jclaw authorizes identically but then runs everything in-process or as an ordinary child process.

| Capability | IronClaw | jclaw |
|---|---|---|
| WASM extension lane with capability-based host imports (filesystem, HTTP, credentials, output) | ✅ `ironclaw_wasm` | ❌ |
| WASM resource limiting (memory, CPU, time) | ✅ `ironclaw_wasm_limiter` | ❌ |
| Container sandbox: Docker orchestrator/worker pattern, per-job tokens, LLM proxying through the host | ✅ `ironclaw_sandbox`, `Dockerfile.sandbox-worker`, `Dockerfile.process-sandbox` | ❌ — `builtin.shell` is `/bin/sh -c` on the host with a scrubbed env, timeout, and output cap; no filesystem or network isolation beyond the workspace guard applied to *jclaw's own* path resolution (a shell command can `cat /etc/passwd`) |
| Process backend selected by policy (in-process worker vs container) | ✅ | ❌ |
| Dynamic tool building (agent authors and installs new WASM tools) | ✅ | ❌ |
| First-party executors routed through `RuntimeDispatcher` | ✅ | ✅ (`CapabilityHandler` lanes behind `DefaultCapabilityHost`) |
| MCP over stdio | ✅ | ✅ |
| MCP over HTTP / SSE / streamable HTTP | ✅ *(unverified which transports)* | ❌ |
| MCP OAuth for authenticated servers | ✅ *(unverified)* | ❌ |
| MCP resources, prompts, sampling, notifications | ✅ *(unverified)* | ❌ — only `initialize`, `tools/list`, `tools/call`; notifications are ignored |
| MCP server lifecycle | lazy / managed *(unverified)* | 🟡 all enabled servers start eagerly at boot, on every CLI invocation |
| Host-mediated egress for MCP servers | ✅ | ❌ — an MCP server process makes its own network calls; only jclaw's `http_fetch` is guarded |

---

## 4. Kernel boundary

At parity on the trust model; missing the multi-tenant, multi-process, and resource-accounting machinery.

| Capability | IronClaw | jclaw |
|---|---|---|
| Trust classification of sources and extensions (`ironclaw_trust`) | ✅ | ✅ `TrustClass` (SYSTEM, FIRST_PARTY, VERIFIED, COMMUNITY, UNTRUSTED) with per-class ceilings; policy may only tighten |
| Exact-invocation grants (`ironclaw_authorization`) | ✅ | ✅ SHA-256 fingerprint over id + sorted arguments; scope-keyed |
| Approval gates with **leases** (`ironclaw_approvals`) | ✅ gates expire / are re-leased | 🟡 gates are durable but never expire |
| Auth gates (`GateKind::Auth`, `BLOCKED_AUTH`) — run parks until the user authenticates | ✅ | 🟡 enum values exist; nothing raises an auth gate. A missing key is a failed turn, not a park |
| Process gates (`WAITING_PROCESS`) — run waits on an external process / child | ✅ | 🟡 enum value exists; subagents run synchronously inside the parent's turn instead |
| Budget reservation and process ownership (`ironclaw_resources`) | ✅ | 🟡 `Budget` (tokens, iterations, 10-min wall clock) per run; no reservation, no per-user or per-tenant accounting |
| Deployment modes / runtime policy / safety context (`ironclaw_runtime_policy`) | ✅ | 🟡 three fixed `approval-mode` postures plus `jclaw.denied-capabilities`, hard denials that hold in every mode and drop the tool from the published surface; no deployment modes or safety contexts |
| Neutral process journal, lifecycle transitions, suspension, **process trees** (`ironclaw_processes`, `JournaledProcessSnapshot`) | ✅ | 🟡 `RunStore` records status + lease; no journal cursor, no suspension, no parent/child tree (subagent lineage lives only in the thread id) |
| Turn admission and coordinator API (`ironclaw_turns`) | ✅ | ✅ `JclawRuntime.submit` |
| `TurnScope` as tenant / agent / project / thread isolation key | ✅ | 🟡 `TurnScope.local(project, thread)` — no tenant, no agent id; single user assumed |
| "One active run per canonical thread" enforced before side effects | ✅ | ✅ `ThreadLock` (OS file lock per canonical scope) taken in `JclawRuntime.submit`/`resume` before the inbound message is written; refusal is `THREAD_BUSY` with nothing recorded. Single-host, like the JSONL stores |
| Capability manifest publishing (`ironclaw_capabilities`) | ✅ | 🟡 descriptors are compiled in; `visibleSurface` publishes them; no manifests |
| Per-tool rate limiting | ✅ | ❌ |
| Endpoint allowlisting per tool / extension | ✅ | 🟡 `jclaw.egress-allowlist` / `egress-denylist` (exact hosts or `*.suffix`) bind into `EgressGuard` for every tool; not per tool or per extension |

---

## 5. Agent loop

| Capability | IronClaw | jclaw |
|---|---|---|
| Pure, resumable loop state; `LoopExit` as refs-only claim; `LoopExitApplier` validation | ✅ | ✅ |
| Checkpoint kinds driving recovery (`BeforeModel`, `BeforeBlock`, …) | ✅ | ✅ |
| Multiple **loop families** / sealed strategy composition | ✅ | ❌ — one machine; `LoopPolicy` is a value, not a strategy |
| `CanonicalAgentLoopExecutor` tick pipeline with input / prompt / model / capability / gate / stop stages | ✅ | 🟡 same stages, fixed; no pluggable stage |
| Execution-stage hooks (`ironclaw_hooks`) — pre/post model, pre/post tool | ✅ | ❌ |
| Prompt envelope contract (`prompt_envelope`) with context policy | ✅ | 🟡 `PromptAssembly` (base + workspace + skill summaries) plus `ContextPolicy` on `LoopPolicy`; no envelope contract type — the system prompt and the message window are assembled separately |
| Context management: compaction / summarisation / truncation policy | ✅ *(via context policy in `ResolvedRunProfile`)* | 🟡 `ContextPolicy` (message cap + estimated token budget) on `LoopPolicy`; pure `ContextCompaction` applied at admission and by `TurnMachine` before every model call — boundary-safe truncation with an omission notice, idempotent. No summarisation |
| `RunProfileResolver`: driver, checkpoint schema, model profile, capability surface, context policy, budget, scheduling class | ✅ | 🟡 `LoopPolicy` + `RunRecord` capture model, prompt, tools, budget, context policy; the context policy is resolved from current config on resume rather than stored on the run (it bounds the model's view, not the run's authority); no scheduling class, one schema version |
| Model gateway abstraction with per-profile routing | ✅ | 🟡 one `ModelProvider` per process, chosen at boot; `failover` is the only composite |
| Attachments (`ironclaw_attachments`) and extractors (`ironclaw_extractors`) — images, files, documents into the prompt | ✅ | ❌ — text-only `ContentBlock`s; `Thinking` is parsed but there is no image block |
| Cancellation | ✅ | ✅ (checked between effects) |
| Subagents via loop-host port | ✅ | ✅ (same machinery; depth from thread id; max 3; synchronous) |
| Parallel / asynchronous subagents | ✅ *(unverified)* | ❌ — a child run blocks the parent's tool call |

---

## 6. Scheduling, concurrency, recovery

| Capability | IronClaw | jclaw |
|---|---|---|
| Leases with heartbeat renewal; expiry → terminal or requeue by checkpoint kind | ✅ | ✅ (`LEASE_TTL` 2 min; grace of one further TTL) |
| `TurnRunScheduler` claiming durable queued work with bounded concurrency; per-user and per-inbound-type caps | ✅ | ❌ — the pieces (claim, heartbeat, contention, `LeaseRecovery`) exist and are tested, but no scheduler drives more than one worker |
| Queued runs (a `QUEUED` state that something later picks up) | ✅ | 🟡 `QUEUED` exists as a status; `recover` requeues, but only a human's `resume` executes it |
| Priorities, parallel jobs with isolated contexts | ✅ | ❌ |
| Self-repair of stuck operations | ✅ | 🟡 `recover` / worker sweep, manual or on each `worker` tick |
| Heartbeat system (proactive background agent runs) | ✅ | ❌ — routines are cron-only |

---

## 7. Routines and triggers

| Capability | IronClaw | jclaw |
|---|---|---|
| Cron routines | ✅ | ✅ (five-field Vixie cron, zones, pause/resume, `run-due`, `worker`) |
| Event triggers | ✅ | ❌ |
| Webhook triggers | ✅ | ❌ (no HTTP ingress at all) |
| Heartbeat / proactive triggers | ✅ | ❌ |
| Agent creates its own triggers | ✅ | ✅ (`builtin.trigger_*`) |
| Embedded scheduler | ✅ | ❌ by design — system cron or `jclaw worker` |

---

## 8. Memory, workspace, documents

| Capability | IronClaw | jclaw |
|---|---|---|
| Durable memory with hybrid search fused by RRF | ✅ full-text + **vector** | ✅ BM25 + recency + vector (cosine over stored embeddings, `VectorRanking`) via RRF; the vector list is present only when an embedding provider is configured and the query embeds |
| Embeddings / embedding provider | ✅ | ✅ `EmbeddingProvider` port; one OpenAI-compatible `/embeddings` adapter covers OpenAI, OpenRouter, Ollama, LM Studio, vLLM. Embeddings stored inline in `memory.jsonl` with their model id; `jclaw memory reindex` backfills |
| Workspace filesystem for notes, logs, context; identity files (persistent personality) | ✅ | ❌ — memories are records in `memory.jsonl`, not files; no identity file concept |
| Documents substrate (`ironclaw_documents`) — chunking, indexing | ✅ | ❌ |
| Memory tools exposed to the model | write / search / list / forget *(unverified)* | 🟡 `memory_write`, `memory_search` only; list and forget are CLI-only |
| Project scoping | ✅ (tenant/agent/project) | ✅ (project = workspace dir name) |

---

## 9. Safety layer

| Capability | IronClaw (`ironclaw_safety`) | jclaw |
|---|---|---|
| Secret redaction in tool output, logs, events | ✅ | ✅ `Redaction` (known values + key-shaped patterns; truncate-after-redact) |
| Pattern-based prompt-injection detection on tool results and inbound content | ✅ | ❌ |
| Content sanitisation / escaping of untrusted text before it re-enters the prompt | ✅ | ❌ |
| Policy rules with severities (Block / Warn / Review / Sanitize) | ✅ | ❌ |
| Leak detection on outbound requests and responses | ✅ | 🟡 redaction only; no scan of *outbound* model requests for secrets that arrived via a tool |
| Credential injection at the host boundary (tools never see the raw secret) | ✅ | 🟡 tools cannot obtain secrets (no method exists) but there is also no way for a tool to *use* one — e.g. an authenticated `http_fetch` is impossible |
| Endpoint allowlisting | ✅ | ✅ `jclaw.egress-allowlist` / `egress-denylist`; the lists can only narrow — private-network and metadata denials still apply to allowed hosts |
| Audit log of all tool executions | ✅ | ✅ `CapabilityInvoked` events |

---

## 10. Secrets, auth, identity

| Capability | IronClaw | jclaw |
|---|---|---|
| Encrypted secret vault (AES-256-GCM), leased/staged per runtime handoff | ✅ `ironclaw_secrets` | ❌ — environment variables only |
| Secret references usable by tools without exposure | ✅ | ❌ |
| Multi-user identity (`ironclaw_identity`), per-user scoping | ✅ | ❌ — single operator |
| Auth domain (`ironclaw_auth`): Google OAuth, NEAR AI login, WebUI login tokens | ✅ | ❌ (Anthropic `ANTHROPIC_AUTH_TOKEN` OAuth-style credential is honoured, nothing more) |
| Auth gates that park a run until credentials arrive | ✅ | ❌ (see §4) |
| Outbound domain (`ironclaw_outbound`) — typed outbound messages to channels | ✅ | ❌ |

---

## 11. Storage and events

| Capability | IronClaw | jclaw |
|---|---|---|
| SQL persistence: libsql/SQLite runtime, PostgreSQL for production, migrations | ✅ `ironclaw_libsql_runtime`, `migrations/` | ❌ — everything is append-only JSONL. `spring-jdbc`/H2 appear as dependencies but no SQL layer exists |
| Event log | ✅ `event_log` | ✅ `JsonlEventLog` |
| Event store with read models / projections (`event_projections`) | ✅ | ❌ — `status` tails the raw log |
| Event streams (live subscription; feeds SSE/WebSocket UIs) | ✅ `event_streams` | ❌ |
| Durable capability results | ✅ | ✅ `JsonlCapabilityResultStore` (`results.jsonl`); `JclawRuntime.validate` re-resolves every result ref in a `Completed` exit, so a run resumed in another process still proves its evidence |
| Transcript with drafts vs finals | ✅ | 🟡 `appendAssistant(…, draft)` flag exists; nothing writes drafts |
| Checkpoint schema versioning + migration | ✅ | 🟡 schema version recorded (v1); an unreadable version fails closed; no migration |
| Thread history compaction / archival | ✅ *(unverified)* | ❌ — `transcript.jsonl` grows forever. The *model's view* of a thread is bounded by `ContextCompaction` (§5); the file itself is never compacted or archived |

---

## 12. Extensions and packaging

| Capability | IronClaw | jclaw |
|---|---|---|
| Extension manifests declaring capabilities, permissions, endpoints | ✅ `extension_contracts`, `extension_manifests` | ❌ |
| Extension registry, host, manager (install / enable / update) | ✅ | ❌ — `mcp add` is the only "install" |
| Installable packages (channels, tools) under `extensions/packages/` | ✅ 14 packages | ❌ |
| Signature verification for `VERIFIED` extensions | ✅ | ❌ — `TrustClass.VERIFIED` exists; nothing can produce it |
| Skills as installable packages | ✅ | 🟡 skills are directories you `cp -r`; no `skills install`, no registry, no versioning |
| Profiles (`profiles/`) selecting bundled configurations | ✅ | ❌ |

---

## 13. Observability and operations

| Capability | IronClaw | jclaw |
|---|---|---|
| Structured tracing / metrics substrate (`ironclaw_observability`, `trace_commons`) | ✅ | ❌ — SLF4J/logback with `--debug`/`--trace`; no OpenTelemetry, no metrics |
| Latency harness (`harness/latency/`) | ✅ | ❌ |
| Deployment assets (`deploy/`, `docker/`, `infra/runner/`) | ✅ | ❌ — one jar or one binary, no Dockerfile |
| Test tooling (`test-tools/`, `tests/` integration suites) | ✅ | 🟡 182 unit + integration tests, including a child-JVM test for cross-process thread locking; no end-to-end suite against a live provider |
| `doctor`-style preflight | *(unverified)* | ➕ `jclaw doctor` |

---

## 14. Model providers

| Capability | IronClaw | jclaw |
|---|---|---|
| Multiple providers, switchable (`models set-provider`) | ✅ | ✅ (`jclaw.provider`, `onboard`) |
| Anthropic native (adaptive thinking, native tool shape, streaming) | ✅ *(unverified which SDK)* | ✅ official SDK |
| OpenAI, OpenRouter, Ollama, local OpenAI-compatible servers | ✅ *(set unverified)* | ✅ + ➕ `local` with base-URL requirement |
| Failover chain | *(unverified)* | ➕ `failover` |
| Streaming for OpenAI-compatible providers | ✅ | ✅ SSE with fragment-assembled tool calls and the usage trailer; `failover` streams too and will not fail over once prose has been shown |
| OpenRouter routing preferences (provider order, fallbacks, transforms) | *(unverified)* | ❌ |
| Per-run model profile selection | ✅ | 🟡 one model per process; recorded per run for resume fidelity |
| Multimodal input | ✅ | ❌ |
| Embedding provider for memory retrieval | ✅ | ✅ `jclaw.embedding-provider` (`openai`, `openrouter`, `ollama`, `local`) over one OpenAI-compatible `/embeddings` adapter; Anthropic has no embeddings endpoint, so a Claude operator pairs it with Ollama or OpenAI. Failures degrade to lexical + recency |

---

## 15. Things jclaw does that are *not* gaps

Listed so the comparison is not read as one-directional:

- The five load-bearing invariants are implemented and tested: pure loop, untrusted exits, single authority gate with exact-invocation approvals, re-authorising resume, fail-closed lease recovery.
- Wire-safe tool-name encoding with collision refusal (`ToolNames`), verified against a strict fake of the OpenAI schema.
- HTTP/1.1 pin for llhttp-based local servers (vLLM), with a test that fails if removed.
- Native image with captured SDK metadata, verified including subprocess spawning.
- REPL slash commands and dual-mode arrow-key bindings.
- `doctor` as a CI preflight; `recover --dry-run` explaining every decision.
- A failover chain that knows not to retry a stream once the user has seen output from it.
- Thread exclusivity as an OS file lock that dies with its process — no TTL, no reconciliation, proven with a second JVM in the test suite.
- Context compaction that is pure, idempotent, and structurally safe (never opens a request with a tool result or a bare assistant turn; never splits a tool call from its results; reports the running total omitted).
- Vector ranking that cannot mis-rank across embedding models: every stored vector carries its model id and only same-space vectors are compared.

---

## 16. Suggested order if closing gaps

Ranked by leverage relative to effort, given the existing seams. Closed in September 2026, in
order: the one-active-run-per-thread lock (§4), the context compaction policy (§5), vector ranking
as a third list into `RrfFusion` (§8), streaming for the OpenAI-compatible adapter (§14), the
configurable denied set and egress lists (§4, §9), and the durable `CapabilityResultStore` (§11).
What remains:

1. **A `TurnRunScheduler`** over the existing lease primitives (§6) — the enabling step for a WebUI or channel adapters.
2. **Auth gates** (§4, §10) — the enum and status already exist.
3. **Prompt-injection heuristics and sanitisation** on tool results (§9).
4. **Context summarisation as an effect** (§5) — `ContextCompaction` truncates; a summarising step would feed its output back as ordinary history.
5. **Per-tool egress and rate limits** (§4) — the global lists exist; per-capability scoping needs the descriptor to carry them.
6. **A sandboxed process lane** (§3) — the largest gap, and the one that changes the threat model most.
