#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# One agent, several subagents: installs the skill, then runs a turn in which the agent reads it
# and delegates two facets. The mock provider scripts every reply, so this needs no API key and
# no network.
#
# Usage: ./demo.sh [path/to/jclaw-bootstrap.jar]
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
jar="${1:-$here/../../jclaw-bootstrap/target/jclaw-bootstrap-0.1.0-SNAPSHOT.jar}"

if [ ! -f "$jar" ]; then
  echo "demo.sh: no jar at $jar — run 'mvn clean install' first, or pass one" >&2
  exit 1
fi

root="$(mktemp -d)"
state="$root/state"
workspace="$root/ws"
mkdir -p "$state/skills" "$workspace"
cp -r "$here/parallel-review" "$state/skills/"

# Pin what ~/.jclaw/jclaw.yaml could otherwise change: this demo is about the fan-out, not about
# whichever provider the machine happens to be configured for.
common=(--jclaw.state-dir="$state" --jclaw.workspace="$workspace" --jclaw.provider=mock)

echo "== the skill is installed =="
java -jar "$jar" skills list "${common[@]}"

echo
echo "== the turn =="
# The script is the whole conversation, parent and children interleaved, because a subagent is an
# ordinary run and calls the same provider:
#   [0] parent  loads the skill
#   [1] parent  delegates the first facet   [2] that child answers
#   [3] parent  delegates the second facet  [4] that child answers
#   [5] parent  merges
#
# Commas are the argument separator in a mock-script tool call, so the prompts here avoid them.
java -jar "$jar" run "review the checkout module" \
  "${common[@]}" --jclaw.approval-mode=trusted \
  '--jclaw.mock-script[0]=tool:builtin.skill_read:id=parallel-review' \
  '--jclaw.mock-script[1]=tool:builtin.spawn_subagent:description=auth,prompt=Facet auth. Read checkout/auth and list findings - one line each.' \
  '--jclaw.mock-script[2]=text:auth: token refresh is not retried (auth/Session.java:88)' \
  '--jclaw.mock-script[3]=tool:builtin.spawn_subagent:description=errors,prompt=Facet errors. Read checkout/errors and list findings - one line each.' \
  '--jclaw.mock-script[4]=text:errors: 503 from the payment gateway is swallowed (errors/Gateway.java:41)' \
  '--jclaw.mock-script[5]=text:Two findings. auth - token refresh is not retried. errors - a 503 from the gateway is swallowed.'

echo
echo "== the two children, as runs of their own =="
java -jar "$jar" status "${common[@]}" | sed -n '1,20p'

echo
echo "state: $state"
