#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# Prints one line of coverage totals from JaCoCo's CSV, in a shape both pipelines can read:
#
#   TOTAL coverage: 61.2% instructions (61234/100000), 48.9% branches, 63.0% lines
#
# GitLab's `coverage:` keyword scrapes the first number out of the job log; the GitHub job
# appends the same line to the run summary. One script, so the two cannot disagree about what
# the project's coverage is — which is the whole reason the number is computed here rather than
# by a regex in each pipeline.
#
# Reads the aggregate report when it exists (jclaw-bootstrap aggregates every module) and falls back
# to summing the per-module reports, which is what a partial build leaves behind.
set -eu

# The C locale, or awk formats 73.3 as "73,3" wherever the developer's locale says so — and the
# pipelines scrape this line with a regex that expects a dot.
export LC_ALL=C

root="$(cd "$(dirname "$0")/.." && pwd)"
aggregate="$root/jclaw-bootstrap/target/site/jacoco-aggregate/jacoco.csv"

if [ -f "$aggregate" ]; then
  files=("$aggregate")
  source_note="aggregate"
else
  # shellcheck disable=SC2207
  files=($(find "$root" -path "*/target/site/jacoco/jacoco.csv" | sort))
  source_note="per-module"
fi

if [ ${#files[@]} -eq 0 ]; then
  echo "coverage: no JaCoCo report found — run 'mvn verify' first" >&2
  exit 1
fi

# JaCoCo's CSV columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
# BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
awk -F, -v note="$source_note" '
  FNR == 1 || $1 == "GROUP" { next }
  {
    im += $4; ic += $5
    bm += $6; bc += $7
    lm += $8; lc += $9
  }
  END {
    if (im + ic == 0) { print "coverage: the report is empty (no instructions recorded)" > "/dev/stderr"; exit 1 }
    printf "TOTAL coverage: %.1f%% instructions (%d/%d), %.1f%% branches, %.1f%% lines [%s]\n",
           100 * ic / (im + ic), ic, im + ic,
           (bm + bc > 0 ? 100 * bc / (bm + bc) : 0),
           (lm + lc > 0 ? 100 * lc / (lm + lc) : 0),
           note
  }
' "${files[@]}"
