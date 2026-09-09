# jclaw vs IronClaw — Parity

An honest enumeration of what [IronClaw](https://github.com/nearai/ironclaw) (internally "Reborn") has that jclaw does not, and of what jclaw now has. jclaw is roughly 20k lines of Java (plus 6.5k of tests) against IronClaw's ~1.4M lines of Rust across ~63 crates. The **architecture** is equivalent — layer ladder, turn/run lifecycle, untrusted `LoopExit`, single `CapabilityHost` authority boundary, checkpoint-kind–driven recovery — and, after the September 2026 parity work, most of the runtime *mechanisms* are present in some form. What remains missing is breadth, not mechanism: the long tail catalogued in section 16. This file is the list, organised by IronClaw's own crate families so a gap can be traced to the crate that fills it upstream.

Sources: IronClaw's `README.md`, `crates/Architecture.md`, and the `crates/` listing as of September 2026; jclaw's code on this checkout (332 tests, 0 failures). Where the upstream doc names a concept and jclaw has an equivalent under a different name, the mapping is given. Where the gap is uncertain it is marked *(unverified)*.

Legend: ✅ at parity · 🟡 partial · ❌ missing · ➕ jclaw-only

---

## 1. At a glance

| IronClaw area | Upstream crates | jclaw | Status |
|---|---|---|---|
| Layer ladder + architecture tests | `crates/AGENTS.md`, `ironclaw_architecture_tests` | 9 modules, `DependencyLawTest` (14 ArchUnit rules) | ✅ |
| Contracts / turn vocabulary | `contracts/{host_api, common, prompt_envelope, loop_contracts, extension_contracts, product_contracts}` | `jclaw-contracts` (loop + host + product vocabulary in one module; no prompt envelope type, no extension contracts) | 🟡 |
| Pure agent loop, checkpoints, resumable state | `ironclaw_agent_loop`, `ironclaw_loop_host`, `ironclaw_turn_runner` | `TurnMachine` + `EffectInterpreter` + `JclawRuntime`; `LoopFamily` (`canonical`, `reflective`) | ✅ |
| Loop hooks | `ironclaw_hooks` | `LoopHook` before/after model and capability; may narrow or veto, never widen | ✅ |
| Kernel: trust, authorization, approvals, capabilities, turns | `ironclaw_trust`, `_authorization`, `_approvals`, `_capabilities`, `_turns`, `_host_runtime` | `DefaultCapabilityHost`, `CapabilityPolicy`, `TrustClass`, `ApprovalStore` (approval, auth, and process gates with expiry), `JclawRuntime.validate` | ✅ |
| Kernel: resources, runtime policy, processes | `ironclaw_resources`, `ironclaw_runtime_policy`, `ironclaw_processes` | `Budget`; `CapabilityPolicy` postures + configurable hard denials, per-tool egress and rate limits, injection policy; `RunStore` with leases and a per-thread `ThreadLock`; no process journal / process trees / deployment modes | 🟡 |
| Scheduler with bounded concurrency | `TurnRunScheduler`, `RebornTurnRunExecutor` | `TurnRunScheduler` over the pure `RunScheduling`: `submit`/HTTP enqueue, `worker`/`serve` claim and execute, one run per thread, a global and a per-tenant cap | ✅ |
| WASM lane | `ironclaw_wasm`, `ironclaw_wasm_limiter` | none | ❌ |
| Script / container sandbox lane | `ironclaw_sandbox` (Docker orchestrator/worker) | `builtin.shell` and MCP servers in a `docker run` container per `SandboxSpec` (`shell-backend`, `mcp-backend`); host backend by default | 🟡 |
| MCP lane | `ironclaw_mcp` | stdio and streamable-HTTP transports; tools, resources, and prompts; lazy start from a cached surface | 🟡 |
| Extension system | `extension_registry`, `_host`, `_manager`, `_support`, `packages/*` | manifests, package digests, Ed25519 signatures, `VERIFIED`/`COMMUNITY` trust at install; local directories, no remote registry | 🟡 |
| Products | `ironclaw_cli`, `_webui`, `_assistant`, `_operator`, `_openai_compat`, `_host_ingress`, Slack/Telegram channel packages | CLI + REPL, and `jclaw serve`: a browser UI, an OpenAI-compatible endpoint, run projections, SSE, and per-user tenants | 🟡 |
| Substrates: filesystem, network | `ironclaw_filesystem`, `ironclaw_network` | `WorkspaceGuard`, `EgressGuard` (host-wide and per-tool lists) | ✅ |
| Substrates: secrets | `ironclaw_secrets` (AES-256-GCM vault, leased handoff) | `FileSecretVault` (AES-256-GCM, owner-only key file or `JCLAW_VAULT_KEY`); `{{secret:NAME}}` references substituted by the kernel host at dispatch under a capability + host binding | ✅ |
| Substrates: safety | `ironclaw_safety` (injection detection, sanitization, leak detection, policy severities) | `Redaction`, `InjectionHeuristics` with an `off/warn/sanitize/block` policy, egress lists; no leak detection on outbound requests | 🟡 |
| Substrates: documents, libsql/Postgres, observability | `ironclaw_documents`, `ironclaw_libsql_runtime`, `ironclaw_observability` | JSONL files or a SQL database (`jclaw.storage=sql`: H2 embedded, PostgreSQL hosted, versioned migrations) behind one `RowStore` port; retention; SLF4J logs, Prometheus metrics and OTLP traces projected from the event log; no documents | 🟡 |
| Events | `event_log`, `event_store`, `event_projections`, `event_streams` | `JsonlEventLog` or SQL rows; `RunProjection` read model; SSE stream per run; metrics and traces folded from the same log | 🟡 |
| Domains: threads, memory, skills, triggers, llm | `ironclaw_threads`, `_memory`, `_skills`, `_triggers`, `_llm` | `ThreadService`, `MemoryStore` + `EmbeddingProvider`, `SkillCatalog`, `RoutineStore`, `ModelProvider` | 🟡 |
| Domains: attachments | `ironclaw_attachments`, `ironclaw_extractors` | image and UTF-8 text attachments on `run`, `submit`, and HTTP; no PDF or document extraction | 🟡 |
| Domains: conversations, auth, identity, outbound | same-named crates | identity as `serve` users that are tenants; no conversations, no auth flow, no outbound | 🟡 |
| Subagents | `ironclaw_loop_host` subagent port | `RuntimeSubagentHost` (child runs, depth ≤ 3), synchronous by default or asynchronous via process gates under a worker | ✅ |

---

## 2. Products and channels

IronClaw is a multi-surface runtime. jclaw has the CLI, the REPL, and a minimal HTTP surface over the same runtime.

| Capability | IronClaw | jclaw |
|---|---|---|
| CLI (`run`, `repl`, `onboard`, `status`, `models`) | ✅ | ✅ plus `submit`, `serve`, `retain`, `approvals`, `resume`, `memory`, `routines`, `worker`, `skills`, `mcp`, `recover`, `tools`, `doctor` |
| HTTP ingress / webhooks (`ironclaw_host_ingress`) | ✅ | 🟡 `jclaw serve`: `POST /threads/{t}/turns` enqueues (202 + run id), `GET /runs/{r}` serves the projection and reply, `GET /runs/{r}/events` streams the run's events as SSE, `GET /runs/{r}/trace`, `GET /metrics`, `POST /hooks/{name}` fires a webhook routine, `GET /threads/{t}/messages`, `GET/POST /approvals`, `GET /health`; loopback by default, bearer tokens per user. No TLS |
| Web UI (`ironclaw_webui`, SSE + WebSocket browser gateway, login token) | ✅ | 🟡 `GET /` on `serve` is a single-page UI over the JSON and SSE routes: threads, transcript, posting turns, following a run's events, approving and denying gates; bearer token kept in session storage. No accounts, no WebSocket, no styling beyond legibility |
| OpenAI-compatible HTTP API (`ironclaw_openai_compat`) — use the agent from any OpenAI client | ✅ | ✅ `POST /v1/chat/completions` (buffered and `stream: true`) and `GET /v1/models` on `serve`; a stateless client's prior turns are replayed into a fresh thread, `X-Jclaw-Thread` names a persistent one; images arrive as data-URL parts; client `system` messages are ignored in favour of the operator's prompt; a parked run is reported in the completion text with `X-Jclaw-Gate` |
| Slack and Telegram channel adapters (WASM channel packages implementing `ChannelAdapter`) | ✅ | ✅ `ChannelAdapter` with Slack (Events API, HMAC-signed, replay window) and Telegram (secret-token header) over `POST /channels/{adapter}`; credentials from the vault under `channel.connect`. Compiled in rather than WASM packages |
| Operator / admin surface (`ironclaw_operator`) | ✅ | 🟡 `status`, `status --run`, `doctor`, `recover`, `retain`, `tools`, `approvals` |
| Assistant product with conversation management (`ironclaw_assistant`, `ironclaw_conversations`) | ✅ | ❌ — jclaw has threads, not conversations with source/reply-target bindings |
| Source and reply-target bindings (`SourceBindingRef`, `ReplyTargetBindingRef`) | ✅ | ✅ `ReplyTarget` bound durably to the thread by `ChannelBindingStore`, so an answer returns to the conversation it came from after a queue, a gate, and a restart |
| Long-running service (`ironclaw service restart`, `ironclaw serve`) | ✅ | 🟡 `serve` and `worker` are long-lived: ingress, scheduler, routines, lease sweep, hourly retention. No service manager |
| `config list/set` commands | ✅ | ❌ — edit `~/.jclaw/jclaw.yaml` or pass `--jclaw.*` |
| Gate resolution inside the REPL | ✅ (WebUI approval flow) | ✅ a parked turn is put to the user in the REPL: what is being approved, then `y`/`n`/`l`. Either answer resumes the run; anything else leaves the gate open. A piped session is never asked and still prints the command |
| REPL slash commands (`/help /tools /thread /new /stream`) | not documented upstream | ➕ |

---

## 3. Runtime lanes and sandboxing

IronClaw's premise is that already-authorized work runs **in isolation**. jclaw authorizes identically and can contain both kinds of untrusted code it runs, shell commands and MCP servers, in the same container contract; first-party lanes (file, http, memory, skills, triggers, subagents) are host code behind the guards and run in-process by design.

| Capability | IronClaw | jclaw |
|---|---|---|
| WASM extension lane with capability-based host imports (filesystem, HTTP, credentials, output) | ✅ `ironclaw_wasm` | ✅ `WasmLane` over Chicory, a pure-Java runtime so the native image is unaffected. Imports are granted per manifest and supplied by absence: a module importing one it was not granted fails to instantiate. `log`, `read_file` through the workspace guard, `http_get` through the egress guard; no credential import, since the vault stays above the lane |
| WASM resource limiting (memory, CPU, time) | ✅ `ironclaw_wasm_limiter` | ✅ `WasmSpec`: linear memory capped in pages, every instruction counted against a budget through an execution listener, a result-size cap, and a wall clock. A module is instantiated per call, so nothing carries between them |
| Container sandbox: Docker orchestrator/worker pattern, per-job tokens, LLM proxying through the host | ✅ `ironclaw_sandbox`, `Dockerfile.sandbox-worker`, `Dockerfile.process-sandbox` | 🟡 `jclaw.shell-backend=docker` runs each `builtin.shell` command in `docker run --rm` with no network, the workspace as the only mount at `/workspace`, memory/CPU/pid limits, a read-only root, and an explicit environment (`SandboxSpec`). One command per container, no orchestrator, no per-job tokens, no LLM proxying; the host backend is the default |
| Process backend selected by policy (in-process worker vs container) | ✅ | 🟡 selected by configuration per lane: `jclaw.shell-backend` and `jclaw.mcp-backend`, each `host` or `docker` (`SandboxSpec`, with an image and network override for MCP). Not per capability, not per trust class |
| Dynamic tool building (agent authors and installs new WASM tools) | ✅ | ❌ — an operator installs a WASM package; the agent cannot author or install one |
| First-party executors routed through `RuntimeDispatcher` | ✅ | ✅ (`CapabilityHandler` lanes behind `DefaultCapabilityHost`) |
| MCP over stdio | ✅ | ✅ `StdioTransport`, behind the same `McpTransport` port as HTTP |
| MCP over HTTP / SSE / streamable HTTP | ✅ *(unverified which transports)* | ✅ `HttpTransport`: streamable HTTP (protocol `2025-03-26`), reading either a JSON body or an SSE stream, echoing the server's session id, never following a redirect |
| MCP OAuth for authenticated servers | ✅ *(unverified)* | 🟡 an HTTP server authenticates with a bearer token held in the secret vault, bound to the capability `mcp.connect` and the endpoint's host; the app layer leases it and hands the transport a finished header. No OAuth 2.1 discovery, dynamic client registration, or authorization-code flow |
| MCP resources, prompts, sampling, notifications | ✅ *(unverified)* | 🟡 tools, resources (`list_resources`, `read_resource`), and prompts (`list_prompts`, `get_prompt`), registered only for what the server declared in its handshake. No sampling; server notifications are still ignored |
| MCP server lifecycle | lazy / managed *(unverified)* | ✅ lazy: `McpSurfaceCache` remembers what each server offered, keyed by a fingerprint of its command, URL, and environment names, so capabilities are published without a handshake and the server starts on first invocation. `mcp refresh` drops an entry; `jclaw.mcp-lazy=false` restores eager discovery |
| Host-mediated egress for MCP servers | ✅ | 🟡 a remote server's endpoint goes through `EgressGuard` before a connection is opened, so an MCP URL is checked exactly as a tool's is. Under `mcp-backend: docker` a stdio server's network is what `mcp-sandbox-network` says: `none` (default) or a Docker network; its configured environment reaches it by name, never on a command line. On the host a stdio server's own sockets remain unmediated |

---

## 4. Kernel boundary

At parity on the trust model and on the gate mechanics, and on tenant isolation; missing the multi-process and resource-accounting machinery.

| Capability | IronClaw | jclaw |
|---|---|---|
| Trust classification of sources and extensions (`ironclaw_trust`) | ✅ | ✅ `TrustClass` (SYSTEM, FIRST_PARTY, VERIFIED, COMMUNITY, UNTRUSTED) with per-class ceilings; policy may only tighten |
| Exact-invocation grants (`ironclaw_authorization`) | ✅ | ✅ SHA-256 fingerprint over id + sorted arguments; scope-keyed |
| Approval gates with **leases** (`ironclaw_approvals`) | ✅ gates expire / are re-leased | ✅ gates carry `expiresAt` (`jclaw.approval-ttl`, 24h): an unexpired open gate re-parks a resumed run on the same question, an expired one is asked afresh and can no longer be answered; decisions never expire |
| Auth gates (`GateKind::Auth`, `BLOCKED_AUTH`) — run parks until the user authenticates | ✅ | ✅ a provider `AUTH` failure raises a durable auth gate naming the missing credential; the run parks `BLOCKED_AUTH` at a replay-safe checkpoint and `resume` re-attempts the model call |
| Process gates (`WAITING_PROCESS`) — run waits on an external process / child | ✅ | ✅ a lane returning `HandlerError.Waiting` raises a process gate keyed by the invocation; used by asynchronous subagents. Only child runs use it today; there is no gate on arbitrary external processes |
| Budget reservation and process ownership (`ironclaw_resources`) | ✅ | 🟡 `Budget` (tokens, iterations, 10-min wall clock) per run; per-tool rate limits per process; a per-tenant concurrency cap in the scheduler; no reservation, no per-tenant token accounting |
| Deployment modes / runtime policy / safety context (`ironclaw_runtime_policy`) | ✅ | 🟡 three fixed `approval-mode` postures plus `jclaw.denied-capabilities`, `jclaw.injection-policy`, per-tool egress and rate limits; no deployment modes or safety contexts |
| Neutral process journal, lifecycle transitions, suspension, **process trees** (`ironclaw_processes`, `JournaledProcessSnapshot`) | ✅ | 🟡 `RunStore` records status + lease; the parent/child relation of subagents lives in the thread id and the process gate; no journal cursor, no suspension |
| Turn admission and coordinator API (`ironclaw_turns`) | ✅ | ✅ `JclawRuntime.submit` / `enqueue` / `resume` |
| `TurnScope` as tenant / agent / project / thread isolation key | ✅ | ✅ the tenant is the authenticated `serve` user (`jclaw.serve-users`), or `local` for the CLI and the operator; memories, routines, approvals, the thread lock, and the scheduler's per-tenant cap all key on it. The agent field is always `default` |
| "One active run per canonical thread" enforced before side effects | ✅ | ✅ `ThreadLock` (OS file lock per canonical scope) taken in `submit`/`resume` before the inbound message is written; refusal is `THREAD_BUSY` with nothing recorded. Single-host, like the JSONL stores |
| Capability manifest publishing (`ironclaw_capabilities`) | ✅ | 🟡 descriptors are compiled in; `visibleSurface` publishes them; no manifests |
| Per-tool rate limiting | ✅ | ✅ `jclaw.tool-rate-limits` (`N/window` per capability), a sliding window checked before approval and counted at dispatch; per process |
| Endpoint allowlisting per tool / extension | ✅ | ✅ host-wide `jclaw.egress-allowlist` / `egress-denylist`, plus `jclaw.tool-egress` per capability applied by a scoped handler context after the host checks; MCP servers make their own connections and are not covered |

---

## 5. Agent loop

| Capability | IronClaw | jclaw |
|---|---|---|
| Pure, resumable loop state; `LoopExit` as refs-only claim; `LoopExitApplier` validation | ✅ | ✅ — reply refs and every result ref are re-resolved before a `Completed` is trusted |
| Checkpoint kinds driving recovery (`BeforeModel`, `BeforeBlock`, …) | ✅ | ✅ |
| Multiple **loop families** / sealed strategy composition | ✅ | ✅ `LoopFamily` over the same state, decisions, and checkpoints: `canonical` and `reflective` (review the draft reply once, without tools, before persisting), chosen by `jclaw.loop-family`. `LoopDecision` stays sealed, so a family composes phases but cannot add an effect |
| `CanonicalAgentLoopExecutor` tick pipeline with input / prompt / model / capability / gate / stop stages | ✅ | 🟡 same stages; the model and capability stages take hooks, the prompt and gate stages do not |
| Execution-stage hooks (`ironclaw_hooks`) — pre/post model, pre/post tool | ✅ | ✅ `LoopHook` (before/after model, before/after capability) applied by `EffectInterpreter`; may narrow or veto, never widen (tools may only be removed, the model and messages are fixed, a capability's identity is fixed), and the kernel still checks what a hook returns. Built-in `budget-notice`; any `LoopHook` bean joins. `hook.fired` audit events |
| Prompt envelope contract (`prompt_envelope`) with context policy | ✅ | 🟡 `PromptAssembly` (base + workspace + skill summaries) plus `ContextPolicy` on `LoopPolicy`; no envelope contract type — the system prompt and the message window are assembled separately |
| Context management: compaction / summarisation / truncation policy | ✅ *(via context policy in `ResolvedRunProfile`)* | ✅ `ContextPolicy` (message cap + estimated token budget, summarise on/off); pure `ContextCompaction` truncates at safe boundaries with an omission notice; with summarisation on, `TurnMachine` first asks the model to summarise the dropped span (a non-streamed `CallModel`) and folds the answer into the notice, falling back to truncation if that call fails |
| `RunProfileResolver`: driver, checkpoint schema, model profile, capability surface, context policy, budget, scheduling class | ✅ | 🟡 `LoopPolicy` + `RunRecord` capture model, prompt, tools, budget, context policy; the context policy is resolved from current config on resume rather than stored on the run; no scheduling class, one schema version |
| Model gateway abstraction with per-profile routing | ✅ | 🟡 one `ModelProvider` per process, chosen at boot; `failover` is the only composite |
| Attachments (`ironclaw_attachments`) and extractors (`ironclaw_extractors`) — images, files, documents into the prompt | ✅ | 🟡 `ContentBlock.Image` (png/jpeg/gif/webp, base64) reaches Anthropic as an image block and OpenAI-compatible servers as a data-URL part; UTF-8 text files are quoted inline; PDFs and other documents are refused rather than mis-sent. `run --attach`, `submit --attach`, HTTP `attachments` |
| Cancellation | ✅ | ✅ (checked between effects) |
| Subagents via loop-host port | ✅ | ✅ (same machinery; depth from thread id; max 3) |
| Parallel / asynchronous subagents | ✅ *(unverified)* | ✅ with `jclaw.subagents-async`: the child is queued, the parent parks `WAITING_PROCESS` on a process gate, the scheduler requeues the parent when the child finishes, and the re-dispatched call returns the child's conclusion. Needs a `worker` or `serve`; synchronous remains the default |

---

## 6. Scheduling, concurrency, recovery

| Capability | IronClaw | jclaw |
|---|---|---|
| Leases with heartbeat renewal; expiry → terminal or requeue by checkpoint kind | ✅ | ✅ (`LEASE_TTL` 2 min; grace of one further TTL) |
| `TurnRunScheduler` claiming durable queued work with bounded concurrency; per-user and per-inbound-type caps | ✅ | 🟡 `TurnRunScheduler` claims `QUEUED` runs under a global cap and a per-tenant cap (`serve --per-user`) with one run per thread, decided by the pure `RunScheduling`; no per-inbound-type cap |
| Queued runs (a `QUEUED` state that something later picks up) | ✅ | ✅ `submit` and HTTP enqueue; `recover` requeues; a finished child requeues its waiting parent; `worker` and `serve` execute all of them. A queued run is seeded with the conversation as of its own submission |
| Priorities, parallel jobs with isolated contexts | ✅ | 🟡 parallel across threads under the cap; no priorities |
| Self-repair of stuck operations | ✅ | 🟡 `recover` / worker sweep on each tick; requeued runs are executed by the same worker |
| Heartbeat system (proactive background agent runs) | ✅ | ✅ `routines add --every <interval>`; see §7 |

---

## 7. Routines and triggers

| Capability | IronClaw | jclaw |
|---|---|---|
| Cron routines | ✅ | ✅ (five-field Vixie cron, zones, pause/resume, `run-due`, `worker`, `serve`) |
| Event triggers | ✅ | ✅ `--on run.finished\|gate.raised --when k=v`; `EventTriggerDispatcher` listens on the event log and enqueues the routine's turn with the event's attributes appended. Only those two types may drive a trigger, and an event from any routine's own thread fires nothing, so triggers cannot chase each other |
| Webhook triggers | ✅ | ✅ `--webhook` mints a bearer secret (only its SHA-256 is stored) and `POST /hooks/{name}` on `serve` fires it, authenticated by that secret rather than the operator's token, with the body bounded to 16 KiB and appended to the prompt |
| Heartbeat / proactive triggers | ✅ | ✅ `--every 30m` (or an ISO duration): due once the interval has passed since the last firing, so it drifts with execution instead of snapping to a wall-clock grid |
| Agent creates its own triggers | ✅ | ✅ (`builtin.trigger_*`), time-driven only: a model may set a cron or an interval, never a webhook or an event trigger |
| Embedded scheduler | ✅ | 🟡 `worker` and `serve` embed the poll loop; system cron remains an option |

---

## 8. Memory, workspace, documents

| Capability | IronClaw | jclaw |
|---|---|---|
| Durable memory with hybrid search fused by RRF | ✅ full-text + **vector** | ✅ BM25 + recency + vector (cosine over stored embeddings, `VectorRanking`) via RRF; the vector list is present only when an embedding provider is configured and the query embeds |
| Embeddings / embedding provider | ✅ | ✅ `EmbeddingProvider` port; one OpenAI-compatible `/embeddings` adapter covers OpenAI, OpenRouter, Ollama, LM Studio, vLLM. Embeddings stored inline in `memory.jsonl` with their model id; `jclaw memory reindex` backfills |
| Workspace filesystem for notes, logs, context; identity files (persistent personality) | ✅ | ❌ — memories are records in `memory.jsonl`, not files; no identity file concept |
| Documents substrate (`ironclaw_documents`) — chunking, indexing | ✅ | ❌ |
| Memory tools exposed to the model | write / search / list / forget *(unverified)* | 🟡 `memory_write`, `memory_search` only; list, forget, and reindex are CLI-only |
| Project scoping | ✅ (tenant/agent/project) | ✅ (project = workspace dir name) |

---

## 9. Safety layer

| Capability | IronClaw (`ironclaw_safety`) | jclaw |
|---|---|---|
| Secret redaction in tool output, logs, events | ✅ | ✅ `Redaction` (known values + key-shaped patterns; truncate-after-redact) |
| Pattern-based prompt-injection detection on tool results and inbound content | ✅ | 🟡 `InjectionHeuristics` (pure, ten specific rules, three severities) on every tool result in the kernel; inbound content is the operator's own and is not scanned |
| Content sanitisation / escaping of untrusted text before it re-enters the prompt | ✅ | ✅ flagged output is fenced with a notice and chat-template tokens are defused; the stored payload stays as the tool returned it |
| Policy rules with severities (Block / Warn / Review / Sanitize) | ✅ | 🟡 `jclaw.injection-policy`: `off`, `warn`, `sanitize` (default), `block` (withholds HIGH findings); no per-rule policy, no review queue |
| Leak detection on outbound requests and responses | ✅ | 🟡 redaction only, now including values leased for a call; no scan of *outbound* model requests for secrets that arrived via a tool |
| Credential injection at the host boundary (tools never see the raw secret) | ✅ | ✅ the model writes `{{secret:NAME}}`; `DefaultCapabilityHost` substitutes the value into the lane's arguments after every check, only if the binding names the capability and every URL host in the arguments; the reference form is fingerprinted, prompted, checkpointed, and logged (`SecretInjected`); `http_fetch` drops headers on cross-host redirects. Tools, providers, and the loop are barred from the vault port by the dependency law |
| Endpoint allowlisting | ✅ | ✅ host-wide and per-tool lists; each can only narrow — private-network and metadata denials still apply to allowed hosts |
| Audit log of all tool executions | ✅ | ✅ `CapabilityInvoked` events (ok, denied, failed, blocked, waiting) and `InjectionDetected` |

---

## 10. Secrets, auth, identity

| Capability | IronClaw | jclaw |
|---|---|---|
| Encrypted secret vault (AES-256-GCM), leased/staged per runtime handoff | ✅ `ironclaw_secrets` | ✅ `FileSecretVault`: AES-256-GCM per value with the name as associated data, append-only JSONL, key from an owner-only file or `JCLAW_VAULT_KEY`. Leased per capability call, and staged into an MCP server's process environment at start (`McpCredentials`) or into an HTTP server's bearer header, under a `mcp.connect` binding. No store anywhere holds a credential value |
| Secret references usable by tools without exposure | ✅ | ✅ `{{secret:NAME}}` in any tool argument, bound to one capability and a host list; `--subprocess` bindings stage a value into a child's environment instead, where an argument would become a command line; `secret-leak-scan` rewrites any value out of an outbound model request; one vault per tenant. `jclaw secrets set/list/remove`; the system prompt lists names and bindings, never values |
| Multi-user identity (`ironclaw_identity`), per-user scoping | ✅ | 🟡 `jclaw.serve-users` names users with static bearer tokens; each is the tenant of its runs, with namespaced threads (`alice:work`), its own memories and approvals, and read access only to its own runs and gates. The operator (`serve-token`) is the `local` tenant the CLI uses and reads everything. No user directory, roles, or per-user policy |
| Auth domain (`ironclaw_auth`): Google OAuth, NEAR AI login, WebUI login tokens | ✅ | ✅ an OpenID Connect authorization code flow with PKCE against any discovered provider, plus operator-minted sessions. Sessions name a person, carry a role, expire, and are stored as hashes. The id token is checked for issuer, audience, and expiry; its signature is not, since the code flow delivers it over an authenticated back channel |
| Auth gates that park a run until credentials arrive | ✅ | ✅ (see §4); cleared by setting the credential and resuming, shown by `approvals list` |
| Outbound domain (`ironclaw_outbound`) — typed outbound messages to channels | ✅ | ❌ |

---

## 11. Storage and events

| Capability | IronClaw | jclaw |
|---|---|---|
| SQL persistence: libsql/SQLite runtime, PostgreSQL for production, migrations | ✅ `ironclaw_libsql_runtime`, `migrations/` | ✅ `jclaw.storage=sql`: every store's rows in `jclaw_rows` (identity-ordered, indexed by store, run, thread) through `JdbcRowStore`; `SqlSchema` applies versioned migrations on start and refuses a newer database; embedded H2 file by default, PostgreSQL by JDBC URL with the password from the environment. Rows stay JSON documents rather than per-concept tables; connections are per operation |
| Event log | ✅ `event_log` | ✅ `JsonlEventLog` |
| Event store with read models / projections (`event_projections`) | ✅ | 🟡 `RunProjection`, a pure fold of one run's events (status, usage, capability calls, gates, injection findings); `status --run` and `GET /runs/{r}` serve it. Rebuilt on demand from the log; no materialised store, no cross-run projections |
| Event streams (live subscription; feeds SSE/WebSocket UIs) | ✅ `event_streams` | 🟡 `GET /runs/{r}/events` follows one run's events as server-sent events by polling the log; no fan-out subscription, no cross-run stream |
| Durable capability results | ✅ | ✅ `JsonlCapabilityResultStore` (`results.jsonl`); `JclawRuntime.validate` re-resolves every result ref in a `Completed` exit, so a run resumed in another process still proves its evidence |
| Transcript with drafts vs finals | ✅ | 🟡 `appendAssistant(…, draft)` flag exists; nothing writes drafts |
| Checkpoint schema versioning + migration | ✅ | 🟡 schema version recorded (v1); an unreadable version fails closed; no migration |
| Retention / archival | ✅ *(unverified)* | 🟡 `jclaw retain` and the worker's hourly sweep drop rows of finished runs older than `jclaw.retention-{results,events,checkpoints}` with an atomic rewrite; the transcript is never swept and there is no archival |

---

## 12. Extensions and packaging

| Capability | IronClaw | jclaw |
|---|---|---|
| Extension manifests declaring capabilities, permissions, endpoints | ✅ `extension_contracts`, `extension_manifests` | ✅ `jclaw-extension.json`: kind, command, required environment names, declared hosts, claimed effect class, publisher (`ExtensionRegistry.Manifest`, parsed and validated by the pure `ManifestParser`) |
| Extension registry, host, manager (install / enable / update) | ✅ | ✅ `FilesystemExtensionRegistry` with `jclaw extensions install/list/remove/enable/disable`, plus `search/add/outdated/upgrade` against the static `index.json` registries in `jclaw.extension-registries` and `profile` for named sets. A download is checked against the digest the index advertised, then installed down the local path, so a registry decides nothing about trust |
| Installable packages (channels, tools) under `extensions/packages/` | ✅ 14 packages | 🟡 three kinds: skill packages, MCP packages, and WASM packages whose tools register as `wasm.<name>.<tool>`; no channel packages |
| Signature verification for `VERIFIED` extensions | ✅ | ✅ Ed25519 over a `PackageDigest` of every file; `extensions keygen` / `extensions sign` on the publisher side, `jclaw.trusted-publishers` on the operator side. A trusted signature makes the install `VERIFIED` and its declared effect class is honoured for its tools; an untrusted or failing signature refuses the install; unsigned is `COMMUNITY` with `NETWORK` tools |
| Skills as installable packages | ✅ | ✅ a skill package installs, enables, disables, and removes through the registry; plain directories still work |
| Profiles (`profiles/`) selecting bundled configurations | ✅ | ❌ |

---

## 13. Observability and operations

| Capability | IronClaw | jclaw |
|---|---|---|
| Structured tracing / metrics substrate (`ironclaw_observability`, `trace_commons`) | ✅ | 🟡 both projected from the event log rather than instrumented: `Telemetry` counts every written event into Prometheus-format metrics at `/metrics`; the pure `RunTrace` turns a run's events into spans (root, model calls, capability calls, gates, with measured latencies) served as OTLP/JSON at `/runs/{r}/trace`, printed by `status --trace`, and exported to `jclaw.otlp-endpoint` when a run finishes. No OpenTelemetry SDK in-process, no propagation into provider/MCP calls, no histograms |
| Latency harness (`harness/latency/`) | ✅ | ❌ |
| Deployment assets (`deploy/`, `docker/`, `infra/runner/`) | ✅ | ❌ — one jar or one binary, no Dockerfile; a GitLab release pipeline publishes both |
| Test tooling (`test-tools/`, `tests/` integration suites) | ✅ | 🟡 302 unit + integration tests, including a child-JVM test for cross-process thread locking and a fake-docker test for the sandbox contract; no end-to-end suite against a live provider |
| `doctor`-style preflight | *(unverified)* | ➕ `jclaw doctor` |

---

## 14. Model providers

| Capability | IronClaw | jclaw |
|---|---|---|
| Multiple providers, switchable (`models set-provider`) | ✅ | ✅ (`jclaw.provider`, `onboard`) |
| Anthropic native (adaptive thinking, native tool shape, streaming, images) | ✅ *(unverified which SDK)* | ✅ official SDK |
| OpenAI, OpenRouter, Ollama, local OpenAI-compatible servers | ✅ *(set unverified)* | ✅ + ➕ `local` with base-URL requirement |
| Failover chain | *(unverified)* | ➕ `failover` |
| Streaming for OpenAI-compatible providers | ✅ | ✅ SSE with fragment-assembled tool calls and the usage trailer; `failover` streams too and will not fail over once prose has been shown |
| OpenRouter routing preferences (provider order, fallbacks, transforms) | *(unverified)* | ❌ |
| Per-run model profile selection | ✅ | 🟡 one model per process; recorded per run for resume fidelity |
| Multimodal input | ✅ | 🟡 images in; no audio, no documents |
| Embedding provider for memory retrieval | ✅ | ✅ `jclaw.embedding-provider` (`openai`, `openrouter`, `ollama`, `local`) over one OpenAI-compatible `/embeddings` adapter; Anthropic has no embeddings endpoint, so a Claude operator pairs it with Ollama or OpenAI. Failures degrade to lexical + recency |

---

## 15. Things jclaw does that are *not* gaps

Listed so the comparison is not read as one-directional:

- The five load-bearing invariants are implemented and tested: pure loop, untrusted exits, single authority gate with exact-invocation approvals, re-authorising resume, fail-closed lease recovery.
- Wire-safe tool-name encoding with collision refusal (`ToolNames`), verified against a strict fake of the OpenAI schema.
- HTTP/1.1 pin for llhttp-based local servers (vLLM), with a test that fails if removed.
- Native image with captured SDK metadata, verified including subprocess spawning.
- REPL slash commands and dual-mode arrow-key bindings.
- `doctor` as a CI preflight; `recover --dry-run` and `retain --dry-run` explaining every decision.
- A failover chain that knows not to retry a stream once the user has seen output from it.
- A queued run is seeded with the conversation as of its own submission: later turns queued on the same thread are excluded, and its own message is the current turn.
- Injection heuristics never alter the audit record: the stored payload is what the tool returned; only the model-facing copy is framed or withheld.
- A context summary is a model call the machine decides on like any other effect, never streamed as if the agent were speaking, and its failure degrades to truncation rather than failing the run.
- An open approval gate is one question: resuming an undecided run parks it on the same gate, so a human never finds duplicates; only an expired question is asked again.
- Thread exclusivity as an OS file lock that dies with its process — no TTL, no reconciliation, proven with a second JVM in the test suite.
- Retention can only drop rows of finished runs: a parked, queued, or unknown run keeps everything however old.
- The sandbox contract is a pure value with a test that pins every isolation flag, so a change to what a command may reach is a reviewable diff, not a runtime surprise.

---

## 16. What is still missing, ranked

Every item of the three earlier lists is closed — the twelve of the original, the six that
followed, and the ten ranked here in September 2026 (the sections above mark each ✅ or 🟡 with
its caveat). None of that makes jclaw IronClaw: it makes the *mechanisms* comparable. What is
left is the long tail each mechanism leaves behind, and it is worth being precise about, because
"closed" above never meant "as broad as upstream".

Ranked, again, by what a deployment beyond one operator's machine would hit first:

1. ~~**Channel adapters**~~ — closed: Slack and Telegram over `POST /channels/{adapter}`, with
   durable reply-target bindings. Still open under this heading: platform-native slash commands,
   adapters as installable packages rather than compiled in, and any outbound-initiated
   message.
2. ~~**A login flow**~~ — closed: OIDC with PKCE, sessions, three roles, per-tenant policy and
   token budgets, and named agents that make `TurnScope.agent()` mean something. Still open under
   this heading: a user directory, groups, per-tenant vaults, and id token signature
   verification for flows that would need it.
3. ~~**A WASM lane**~~ — closed: modules run under a metered pure-Java runtime with capped
   memory and only the host imports their manifest asked for. Still open under this heading: a
   sandbox orchestrator with per-job tokens, LLM proxying through the host, and letting the agent
   author its own modules.
4. ~~**A remote extension registry**~~ — closed: a registry is a static `index.json`, and
   `search` / `add` / `outdated` / `upgrade` work against it under a recomputed digest check;
   profiles enable exactly one named set. Still open under this heading: a channel package kind,
   a publisher-side `publish`, and a signed index (today the index is trusted only for the
   digest, which the download must match).
5. ~~**Secrets beyond one call**~~ — closed: a `--subprocess` binding stages a value into a
   child process's environment and never into an argument, the optional `secret-leak-scan` hook
   rewrites any vault value out of an outbound request, and each tenant gets its own vault.
   Still open under this heading: one encryption key across tenants, no rotation or expiry, and
   a scan that matches exact substrings of eight characters or more.
6. **SQL beyond one table** (§11) — every store's rows live in `jclaw_rows` with versioned
   migrations. Projections are folded on demand rather than materialised, there are no
   per-concept tables, and a connection is opened per operation.
7. **In-process telemetry** (§13) — metrics and traces are projections of the audit log,
   computed as events are written. There is no OpenTelemetry SDK in the process, no context
   propagation into provider or MCP calls, and no latency histograms (count, sum, max only).
8. **Pluggable stages** (§5) — hooks cover the model and capability stages, and there are two
   loop families. Prompt assembly and gate raising take no hook, and a new family is Java, not
   configuration.
9. **MCP auth and the reverse direction** (§3) — servers reach over stdio or HTTP and expose
   tools, resources, and prompts. Authentication is a vault-held bearer token, not the OAuth 2.1
   flow; sampling and server-initiated notifications are unimplemented; a stdio server's own
   sockets are unmediated unless it is containerised.
10. **Triggers with more sources** (§7) — cron, intervals, webhooks, and two audit-event types.
    No filesystem watches, no inbound-message triggers, no fan-out from one webhook.

Two things that are structurally different rather than merely narrower, and are unlikely to
change: jclaw is single-host (the thread lock is an OS file lock, the scheduler runs in one
process, and there is no cross-host coordination), and it is one agent (no agent-to-agent
protocol beyond subagents on the same machinery).
