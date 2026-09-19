#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# The same fan-out, with a real model.
#
# demo.sh scripts every reply, so it proves the machinery and nothing about the skill. Here the
# model reads the skill and decides for itself whether to delegate — which is the only way to
# find out whether a skill is written well enough to be followed.
#
# It scaffolds a small project with three independent defects planted in three directories, so
# there is something real to review and a way to tell whether the facets stayed disjoint.
#
# Usage: ./demo-model.sh ["a different prompt"] [extra --jclaw.* arguments...]
#   JCLAW_JAR=...  to point at a jar other than the built one
#
# This makes real API calls against whatever provider you have configured.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/sample-project.sh"
jar="${JCLAW_JAR:-$here/../../jclaw-app/target/jclaw-app-0.1.0-SNAPSHOT.jar}"
prompt="${1:-Review the checkout module and report the problems you find. Its three areas - auth, errors and config - can be read independently.}"
if [ $# -gt 0 ]; then shift; fi
extra=("$@")                       # anything else is passed straight through to jclaw

if [ ! -f "$jar" ]; then
  echo "demo-model.sh: no jar at $jar — run 'mvn clean install' first, or set JCLAW_JAR" >&2
  exit 1
fi

root="$(mktemp -d)"
state="$root/state"
workspace="$root/ws"
mkdir -p "$state/skills" "$workspace"
cp -r "$here/parallel-review" "$state/skills/"

# Note what is *not* pinned here. demo.sh forces `--jclaw.provider=mock` so it runs the same way
# on every machine; this one deliberately inherits ~/.jclaw/jclaw.yaml and your environment,
# because the provider is the entire point.
common=(--jclaw.state-dir="$state" --jclaw.workspace="$workspace" --logging.level.io.jclaw=DEBUG)

echo "== the provider =="
# `models --probe` exits non-zero when the active provider does not answer, which is exactly the
# question worth asking before scaffolding anything: a demo that gets as far as the model call
# and then parks on an auth gate reads like a broken demo.
if probe="$(java -jar "$jar" models --probe "${common[@]}" ${extra[@]+"${extra[@]}"} 2>&1)"; then
  echo "$probe" | grep -E "^(Active provider|Model|Probing|  ok)" || true
else
  echo "$probe" | grep -E "^(Active provider|Model|Probing|  failed)" || true
  echo
  echo "demo-model.sh: the configured provider did not answer, so there is no model to watch." >&2
  echo "Set a credential and try again — for example:" >&2
  echo "  ANTHROPIC_API_KEY=... ./demo-model.sh          # or OPENAI_API_KEY, OPENROUTER_API_KEY" >&2
  echo "  JCLAW_PROVIDER=ollama JCLAW_MODEL=qwen3:1.7b ./demo-model.sh   # a local ollama, no key" >&2
  echo "(demo.sh needs no credential at all: it scripts the model.)" >&2
  exit 1
fi

# The project under review, from sample-project.sh so this demo and demo-concurrent.sh review
# exactly the same code.
scaffold_sample "$workspace"

echo
echo "== the project under review =="
find "$workspace/checkout" -type f | sed "s|$workspace/||" | sort

echo
echo "== the turn =="
echo "prompt: $prompt"
echo
# Trusted so the fan-out runs unattended: `spawn_subagent` is PROCESS, so the default policy
# would stop and ask before each child — and then again for whatever each child wants to do.
# Watching that is instructive, but it is not a demo you can run in one command.
java -jar "$jar" run "$prompt" "${common[@]}" --jclaw.approval-mode=trusted ${extra[@]+"${extra[@]}"}

echo
echo "== what the model chose to do =="
# Counted from the audit log rather than from the reply, because the reply is the model's account
# of what it did and the log is what it actually did.
if [ -f "$state/events.jsonl" ]; then
  loaded="$(grep -c 'builtin.skill_read' "$state/events.jsonl" || true)"
  spawned="$(grep -c 'builtin.spawn_subagent' "$state/events.jsonl" || true)"
  threads="$(grep -o '~sub1-[a-f0-9]*' "$state/events.jsonl" | sort -u | wc -l | tr -d ' ')"
  echo "  skill loaded:      ${loaded:-0}"
  echo "  subagents spawned: ${spawned:-0}"
  echo "  child threads:     ${threads:-0}"
  echo
  echo "  (0 spawned is a real answer too: the model read the skill and judged the task did not"
  echo "   split, or it is not strong enough to follow it. The log tells you which.)"
  echo
  java -jar "$jar" status "${common[@]}" \
    | grep -E "skill_read|spawn_subagent|turn.submitted|run.finished" || true
fi

echo
echo "state: $state"
echo "workspace: $workspace"
