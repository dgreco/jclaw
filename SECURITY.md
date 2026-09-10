# Security policy

jclaw runs an LLM agent that executes shell commands, reaches the network, and holds
credentials in an encrypted vault. Its central claim is that every one of those effects passes
through a single authority gate. A way around that gate is the kind of report this document
exists for.

## Reporting a vulnerability

**Use GitHub's private vulnerability reporting** — the *Security* tab, then *Report a
vulnerability*. It opens a private thread visible only to you and the maintainers.

Please do not open a public issue for a suspected vulnerability, and do not put a working
exploit in one.

Include, where you can: the jclaw version or commit, whether you ran the jar, the native binary,
or a container, the provider and storage backend, the approval mode, and the smallest sequence
that reproduces it. A transcript from `--debug` is usually enough; `--trace` adds payloads and
may contain your own secrets, so redact before sending.

Expect an acknowledgement within a week. This is a personal project with no paid on-call and no
bug bounty, so a fix arrives when it arrives — but a report will not be ignored, and you will be
credited in the release notes unless you ask otherwise.

## Supported versions

Pre-1.0: only the tip of `main` is supported. There are no backports and no security branches.
If you are running a tagged build, expect to move forward to get a fix.

## What is in scope

These are the properties jclaw claims, so a way to break one is a vulnerability:

- **The authority gate.** Any path that dispatches a capability without passing
  existence → policy → approval → dispatch, or that reaches a capability the policy denies.
- **Approval binding.** Two different invocations that share one approval — a fingerprint
  collision, or an approval for `shell: ls` authorising `shell: rm -rf`. Approvals are per
  exact invocation by design.
- **Exit forgery.** A `LoopExit.Completed` carrying refs that `JclawRuntime.validate` accepts
  without them having been minted by the store.
- **The workspace and egress guards.** Escaping the configured workspace root, or reaching a
  loopback, RFC1918, or cloud metadata address through a model-controlled URL while
  `allow-private-networks` is false.
- **Secret custody.** A vault value appearing anywhere other than the vault and the one call it
  was leased to: in a store, an event, a checkpoint, a log line, an approval prompt, a command
  line, or an outbound model request. Also: a lane obtaining a secret it was not given, or one
  tenant reading another's vault, memories, or transcripts.
- **Trust ceilings.** A `COMMUNITY` or `UNTRUSTED` capability — every MCP tool is `COMMUNITY` —
  running without approval under any configuration, including `approval-mode=trusted`.
- **Recovery.** A crashed run replayed from a checkpoint that is not side-effect-free, or two
  live workers interleaving one transcript.
- **Prompt injection that becomes an effect.** Injected instructions reaching the model is
  expected and is what the heuristics frame; injected instructions *causing a capability call
  that the gate should have stopped* is a vulnerability.

## What is already known, and is not a vulnerability

These are documented limitations, not findings. [PARITY.md](PARITY.md) and the *Not built yet*
section of [AGENTS.md](AGENTS.md) carry the full list; the ones most often mistaken for bugs:

- **A frozen host can be displaced.** With `storage=sql` the thread lock is a renewed lease, so
  a host paused past its lease — long GC, suspended VM, partition — can lose it and, on
  resuming, write. Fencing tokens would close it. Live hosts exclude each other correctly.
- **The secret leak scan is an exact substring match** and ignores values under eight
  characters. It is a backstop on the outbound request, not a detection system.
- **The OIDC id token's signature is not verified.** The authorization code flow authenticates
  it by channel; there is no user directory and roles are per-user strings in configuration.
- **One encryption key covers every tenant's vault.** There is no rotation and no expiry.
- **`allow-private-networks=true` re-opens the SSRF surface** and `approval-mode=trusted`
  removes the human from the loop. Both are documented, both are flagged by `jclaw doctor`, and
  both are the operator's decision. Reporting that they do what they say is not a finding.
- **The agent executes shell commands.** That is the product. The container sandbox is opt-in
  configuration; without it, `builtin.shell` runs on the host under the workspace guard, the
  environment scrub, and the approval gate — not in a jail.
- **A stdio MCP server's network access is all-or-nothing** unless it is containerised, and a
  held inbound message never expires.
- **Provider base URLs bypass the egress guard by design.** They are operator configuration,
  not model-controlled input; pointing one at `localhost:11434` for Ollama is the intended use.

If you think one of these is worse than documented — a sharper exploit, a case the write-up
misses — that *is* worth reporting. The list marks what is known, not what is acceptable.

## Running jclaw with less risk

- Keep `approval-mode` at `interactive` or `read-only` for anything unattended.
- Leave `allow-private-networks` false and put real hosts on the egress allow list.
- Give the agent a workspace it may destroy, not your home directory.
- Turn on the container sandbox for the shell lane if the model handles untrusted input.
- Run `jclaw doctor`; it exits non-zero on a real problem and names the posture flags that
  deserve a second look.
