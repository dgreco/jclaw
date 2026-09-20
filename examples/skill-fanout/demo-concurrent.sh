#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# Several agents at the same time. Three routines share a webhook topic, so one POST fires all
# three; `serve --concurrency 3` executes them in parallel, each on its own thread. The event log
# at the end is the evidence: three runs claimed, working, and finished interleaved.
#
# Usage: ./demo-concurrent.sh [port] [extra --jclaw.* arguments...]
#   JCLAW_JAR=...       a jar other than the built one
#   JCLAW_PROVIDER=...  run the three agents against a real model instead of a scripted one
#
# Scripted by default, so it is deterministic and needs no credential. With JCLAW_PROVIDER set it
# scaffolds the same project demo-model.sh reviews and puts three real agents on it at once:
#
#   JCLAW_PROVIDER=ollama JCLAW_MODEL=qwen3:1.7b ./demo-concurrent.sh
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/sample-project.sh"
jar="${JCLAW_JAR:-$here/../../jclaw-bootstrap/target/jclaw-bootstrap-0.1.0-SNAPSHOT.jar}"
port="${1:-8791}"
if [ $# -gt 0 ]; then shift; fi
extra=("$@")

if [ ! -f "$jar" ]; then
  echo "demo-concurrent.sh: no jar at $jar — run 'mvn clean install' first, or set JCLAW_JAR" >&2
  exit 1
fi

root="$(mktemp -d)"
state="$root/state"
workspace="$root/ws"
mkdir -p "$state/skills" "$workspace"
cp -r "$here/parallel-review" "$state/skills/"

if [ -n "${JCLAW_PROVIDER:-}" ]; then
  # Real model: give the three agents something to actually review.
  scaffold_sample "$workspace"
  common=(--jclaw.state-dir="$state" --jclaw.workspace="$workspace")
  script=()
  mode="model ${JCLAW_MODEL:-from your configuration}"
else
  # Scripted: each run does two seconds of shell work so the overlap is visible on a machine where
  # a turn would otherwise be over in twenty milliseconds. One script entry per run per turn — the
  # provider is a single bean and all three runs poll it.
  common=(--jclaw.state-dir="$state" --jclaw.workspace="$workspace" --jclaw.provider=mock)
  script=(
    '--jclaw.mock-script[0]=tool:builtin.shell:command=sleep 2 && echo auth facet read'
    '--jclaw.mock-script[1]=tool:builtin.shell:command=sleep 2 && echo errors facet read'
    '--jclaw.mock-script[2]=tool:builtin.shell:command=sleep 2 && echo performance facet read'
    '--jclaw.mock-script[3]=text:facet reviewed'
    '--jclaw.mock-script[4]=text:facet reviewed'
    '--jclaw.mock-script[5]=text:facet reviewed'
  )
  mode="scripted (set JCLAW_PROVIDER to use a real model)"
fi

echo "== three routines, one topic =="
echo "provider: $mode"
# Only the first routine's secret is used. The other two are fired by *sharing the topic*, which
# is the operator's declaration in the routine — a caller cannot name a topic or join one.
secret=""
for facet in auth errors config; do
  out="$(java -jar "$jar" routines add \
    --name "review-$facet" --webhook --topic review --thread "review-$facet" \
    "Read every file under checkout/$facet/ and report the problems you find. One line each." \
    "${common[@]}")"
  echo "$out" | sed -n '1p'
  if [ -z "$secret" ]; then
    secret="$(echo "$out" | grep -o 'Bearer [0-9a-f]*' | head -1 | cut -d' ' -f2)"
  fi
done

echo
echo "== serve, three at a time =="
echo "http://127.0.0.1:$port  (log: $root/serve.log)"
java -jar "$jar" serve --port "$port" --concurrency 3 \
  "${common[@]}" --jclaw.approval-mode=trusted \
  ${script[@]+"${script[@]}"} ${extra[@]+"${extra[@]}"} \
  > "$root/serve.log" 2>&1 &
serve=$!
trap 'kill "$serve" 2>/dev/null || true' EXIT

# Wait for the port quietly, so a connection refused while the JVM starts is not printed as if
# it were the demo failing.
curl -s -o /dev/null --retry 30 --retry-connrefused --retry-delay 1 "http://127.0.0.1:$port/" || true

echo
echo "== one POST =="
curl -sS \
  -X POST "http://127.0.0.1:$port/hooks/review-auth" \
  -H "Authorization: Bearer $secret" \
  -H "Content-Type: application/json" \
  -d '{"pr": 412, "title": "checkout: retry on 503"}'
echo

# Wait for all three, rather than for a fixed time: on a slow machine — or a slow model — a sleep
# would be a flake.
for _ in $(seq 1 600); do
  # grep -c prints 0 and exits 1 when nothing matches; keep the count, drop the status.
  finished="$(grep -c '"type":"run.finished"' "$state/events.jsonl" 2>/dev/null || true)"
  [ "${finished:-0}" -ge 3 ] && break
  sleep 0.5
done

echo
echo "== what actually happened, from the audit log =="
echo "(three runs, interleaved — that is the concurrency)"
grep -E '"type":"(run.claimed|capability.invoked|run.finished)"' "$state/events.jsonl" \
  | sed -E 's/.*"type":"([^"]*)","at":"[^T]*T([^Z]*)Z","run":"run_([a-f0-9]{6})[^"]*".*/\2  \1  run_\3/'

if [ -n "${JCLAW_PROVIDER:-}" ]; then
  echo
  echo "== what each agent said =="
  for facet in auth errors config; do
    echo "--- review-$facet"
    # The transcript is one JSON document per line; the last ASSISTANT row on a thread is its reply.
    grep "\"thread\":\"review-$facet\"" "$state/transcript.jsonl" 2>/dev/null \
      | grep '"role":"ASSISTANT"' | tail -1 \
      | sed -e 's/.*"text":"//' -e 's/"}].*//' | cut -c1-400 || true
  done
fi

echo
echo "state: $state"
