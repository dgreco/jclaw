<!--
Thanks for the change. CONTRIBUTING.md has the full detail; this is the short checklist.
Delete any section that does not apply — an honest short PR beats a padded one.
-->

## What this changes, and why

<!-- The reasoning, not a restatement of the diff. What was wrong, or what is now possible? -->

## How it was verified

<!--
Which tests, and whether you watched them fail first. The house rule is that a new check is
proven by planting the violation it should catch — say so if you did.
-->

- [ ] `mvn clean install` passes
- [ ] `./scripts/byte-verify.sh scan` and `./scripts/license-check.sh` pass
- [ ] New `.java` / `.sh` files carry the two-line SPDX header
- [ ] New checks were verified to fire, not just to pass

## Anything load-bearing

<!--
Tick only what applies, and say a sentence about it. These are the changes worth a slower read.
-->

- [ ] Touches the `CapabilityHost` order, a guard, the vault, or an approval fingerprint
      → see SECURITY.md for what the project claims
- [ ] Adds I/O to `jclaw-domain`, or a new `LoopDecision` constructor
- [ ] Changes a codec, a checkpoint shape, or anything a resumed run reads back
- [ ] Changes one CI pipeline (the other needs the same change — they are kept in step by hand)
- [ ] Changes documented behaviour → README / AGENTS.md / PARITY.md updated
