# Changelog

Notable changes to jclaw. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the pre-1.0
caveat that the CLI surface, the config keys, and the on-disk formats may still change between
minor versions.

## [Unreleased]

Everything so far. The project has not cut a release yet: the POM version is `0.1.0-SNAPSHOT`,
and a `-SNAPSHOT` never ships — the tag-triggered release job has nothing to publish until that
becomes `0.1.0`.

### Added

- Milestones M0–M7: the turn/run lifecycle, the `CapabilityHost` authority gate, durable
  resumable runs, lease-based crash recovery, and a per-thread run lock.
- Subagents as child runs on the same machinery, synchronous or async behind a process gate.
- MCP over stdio and streamable HTTP: tools, resources, prompts, OAuth 2.1 client credentials,
  and sampling behind a per-server cap.
- Providers: mock, Anthropic (official SDK), OpenAI, OpenRouter, Ollama, any OpenAI-compatible
  local server, and a failover chain. Streaming on all of them.
- Storage as JSONL files or SQL (embedded H2 by default, PostgreSQL by URL), with a table per
  busy store, materialised run projections, and a cross-host thread lock.
- An HTTP surface (`serve`): browser UI, an OpenAI-compatible endpoint, run projections, SSE
  event streams, approvals, webhooks, and channel adapters for Slack and Telegram.
- Vector memory, skills, scheduled routines, filesystem and fan-out triggers, extension
  registries with signed packages and profiles, a WASM lane, an encrypted per-tenant secret
  vault, OIDC login, and a container sandbox for the shell lane.
- A GraalVM native image, verified against both H2 and PostgreSQL in CI.
- Apache-2.0 licensing throughout: `LICENSE`, `NOTICE`, SPDX headers on every source file,
  licence metadata in the POM, copies inside the jar and both container images, and
  `scripts/license-check.sh` to keep it that way.
- A GitHub Actions pipeline mirroring the GitLab one.

[Unreleased]: https://github.com/dgreco/jclaw/commits/main
