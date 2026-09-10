# Contributing to jclaw

Thanks for looking. This page is the short version; [AGENTS.md](AGENTS.md) is the long one and
is worth reading before a first change, because several of this project's rules are enforced by
tests and will fail your build in ways that are hard to guess from the error alone.

Issues and pull requests go to **GitHub**. The project is also mirrored to a private GitLab,
which is where CI originally lived — you can ignore `.gitlab-ci.yml`, but if you change one
pipeline, change both.

## Getting a change in

```bash
mvn clean install            # build + the full suite
./scripts/byte-verify.sh scan
./scripts/license-check.sh
./scripts/readme-sync.sh     # after editing README.md — see below
```

All four run in CI and all four must pass.

`README.md` is the source; `.github/README.md` is generated from it, because GitHub renders that
one in preference and GitLab renders only the root — which is what lets each host's coverage badge
open the report that host published. Edit the root file, run `./scripts/readme-sync.sh`, and
commit both.

Before a change that touches much code, also run the static analysis:

```bash
mvn -Panalysis verify        # javac -Xlint, SpotBugs + FindSecBugs, PMD
```

The tree is clean under all three and they fail the build, so anything they report is yours. If
one is wrong about your code, add the exclusion to `config/spotbugs-exclude.xml` or
`config/pmd-ruleset.xml` **with the reason in prose** — every entry in those files says which
decision it defends, and one that does not is indistinguishable from a finding somebody got
tired of. Then open a PR against `main` describing what
changed and *why* — the existing commit log is the house style: prose that explains the
reasoning, not a summary of the diff.

To run one test: `mvn test -Dtest=ClassName#methodName -pl <module>` (add `-am` if deps are
stale).

## Five rules that will bite you otherwise

**Every new `.java`, `.sh` and `.py` file needs the SPDX header**, before the `package` declaration or
directly after the shebang, with a blank line after it:

```java
// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0
```

`scripts/license-check.sh` fails the build without it.

**The layer ladder is machine-checked.** `contracts ← domain ← kernel ← loop/tools/storage ← app`,
with providers depending on contracts only. `DependencyLawTest` enforces it with ArchUnit, along
with rules like *the domain may not read a clock or use randomness*, *the loop may not name an
adapter*, and *tool lanes may not read the process environment*. If one of those fires, the fix
is almost never to relax the rule.

**A new check must be verified to fire.** The convention here is that you prove a test fails by
planting the violation it is supposed to catch, then remove it — not that you write it and watch
it pass. Several rules in this repo shipped green while checking nothing, and that is the reason.
Say in the PR that you did it.

**Jackson 3, not Jackson 2.** Boot 4 moved databind to `tools.jackson.core`. Adding
`com.fasterxml.jackson.core:jackson-databind` pulls the 2.x line, which will not match the
mapper Boot auto-configures. Jackson 2 *is* on the classpath transitively via the Anthropic SDK;
the two coexist and that is not a bug to fix.

**Codecs are hand-written on purpose.** `EventCodec`, `MessageCodec`, `JsonLoopStateCodec`: no
reflection for the native image, no accidental field disclosure, and a wire format decoupled
from the records. Adding a field to an event means editing its codec. That is the design, not an
oversight.

## Things worth knowing before a bigger change

- **The loop is a pure function.** `TurnMachine.step` performs no I/O and the clock is a
  parameter. `EffectInterpreter` is the only place an effect happens, and the complete set of
  effects is five constructors in `LoopDecision`. A change that adds I/O to the domain is
  changing the architecture, so say so in the PR.
- **Security order is a property, not a style.** The `CapabilityHost` sequence is
  existence → policy → approval → dispatch → redact/bound/store/mint-ref. Reordering it is a
  security change even when the tests still pass. [SECURITY.md](SECURITY.md) lists what the
  project claims.
- **Tests must pin what `~/.jclaw/jclaw.yaml` could change.** The app imports the user config
  file in tests too, so a test depending on the approval mode or the provider sets it
  explicitly, or it passes on your machine and fails on someone else's.
- **Comments explain why, not what.** The pitfalls list in AGENTS.md exists because each entry
  cost someone a debugging session. If your change is subtle, leave the next person the reason.
- **`--debug` and `--trace` narrate the pipeline.** Usually faster than a debugger for
  understanding a turn: admission, each state-machine step, the kernel's authority path,
  provider calls, exit validation.

## Where to look

| | |
|---|---|
| [README.md](README.md) | what jclaw is, every setting, every command |
| [ARCH.md](ARCH.md) | C4 model and the full turn lifecycle |
| [AGENTS.md](AGENTS.md) | the invariants, the pitfalls list, notes for future sessions |
| [PARITY.md](PARITY.md) | what IronClaw has that jclaw does not — read before proposing a feature |
| [SECURITY.md](SECURITY.md) | what is in scope, and what is a known limitation |

## Reporting things

- **A security issue** — do not open a public issue. See [SECURITY.md](SECURITY.md).
- **A bug** — the issue template asks for provider, storage backend, and jar-vs-native. Those
  three account for most behavioural differences, so filling them in usually saves a round trip.
- **A feature** — check PARITY.md first. Much of what looks missing is missing on purpose, and
  the entry usually says why.

## Licensing

Contributions are accepted under the Apache License 2.0, the project's own licence. That is
Apache-2.0 section 5: unless you say otherwise in the PR, what you submit is contributed under
the same terms. There is no CLA to sign.

Keep the SPDX copyright line as it is when editing an existing file. If you would like to be
credited on a substantial new file, add a line rather than replacing one:

```java
// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: Apache-2.0
```

## Conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).
