#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# Writes the two host-flavoured READMEs from one source of prose.
#
# GitHub renders `.github/README.md` in preference to the root one; GitLab renders only the root
# one. That is the only mechanism by which the same badge can lead somewhere different depending
# on where it is being read, and the coverage badge needs exactly that: each host's copy must open
# the report that host's own pipeline published, never the other's.
#
# Two flavours, one body. What differs is confined to the badge block between the markers, plus
# the `../` every relative link needs in the GitHub copy — it lives one directory down, so `LICENSE`
# there would mean `.github/LICENSE`.
#
#   ./scripts/readme-sync.sh           rewrite both files
#   ./scripts/readme-sync.sh --check   fail if either is stale (what CI runs)
set -eu
export LC_ALL=C

root="$(cd "$(dirname "$0")/.." && pwd)"
source_file="$root/README.md"
github_file="$root/.github/README.md"
check=""
[ "${1:-}" = "--check" ] && check=1

# The reports, one per host. Both are published by that host's own pipeline: `coverage-pages` in
# .github/workflows/ci.yml, `pages` in .gitlab-ci.yml.
GITHUB_REPORT="https://dgreco.github.io/jclaw/"
# The mirror's report comes out of build-test's artifacts rather than off GitLab Pages, because
# Pages is an instance-level feature (`pages_external_url`) and this one does not have it turned
# on — with it off, the project has no Pages menu and /-/pages is a 404. The artifact browser
# needs nothing enabled and serves the same directory, for anyone who can see the project.
# If Pages is ever enabled there, this is the one line to change: the `pages` job already
# publishes to it, and the URL is on the project's Deploy > Pages page.
GITLAB_REPORT="https://gitlab.davidgreco.it/dgreco/jclaw/-/jobs/artifacts/main/file/jclaw-app/target/site/jacoco-aggregate/index.html?job=build-test"

# Each host gets its own status badge and its own coverage badge, and every link stays on the
# host doing the rendering. GitLab's coverage badge is live — the number comes from the
# `coverage:` keyword — so that flavour has no need of the static one; GitHub has no coverage
# badge of its own, so it gets the static number. One coverage badge either way.
badges() {   # $1 = github | gitlab
  local host="$1" status coverage
  if [ "$host" = github ]; then
    status='[![CI](https://github.com/dgreco/jclaw/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/dgreco/jclaw/actions/workflows/ci.yml)'
    coverage="[![coverage](https://img.shields.io/badge/coverage-73.3%25-brightgreen)]($GITHUB_REPORT)"
  else
    status='[![pipeline](https://gitlab.davidgreco.it/dgreco/jclaw/badges/main/pipeline.svg)](https://gitlab.davidgreco.it/dgreco/jclaw/-/pipelines)'
    coverage="[![coverage](https://gitlab.davidgreco.it/dgreco/jclaw/badges/main/coverage.svg)]($GITLAB_REPORT)"
  fi
  cat <<EOF
$status
$coverage
[![tests](https://img.shields.io/badge/tests-432-brightgreen)](#coverage)
[![license](https://img.shields.io/github/license/dgreco/jclaw?color=blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](#building)
[![GraalVM](https://img.shields.io/badge/GraalVM-native%20image-blue)](#native-image)
EOF
}

# Replaces whatever sits between the markers with this host's badges.
flavour() {  # $1 = host, reads the source on stdin
  # Through the environment, not -v: awk's -v does not take embedded newlines, and the badge
  # block is several lines.
  BADGES="$(badges "$1")" awk '
    /<!-- BADGES:START -->/ { print; print ENVIRON["BADGES"]; skip = 1; next }
    /<!-- BADGES:END -->/   { skip = 0 }
    !skip { print }
  '
}

# `.github/README.md` sits one directory below the paths the body was written against.
descend() {
  # perl, not sed: the rewrite needs a negative lookahead to leave absolute links, anchors and
  # mailto alone, and neither BSD nor GNU sed has one. perl-base is essential in Debian, so the
  # bare image the verify job runs on already has it.
  perl -pe 's{\]\((?!https?://|#|\.\./|mailto:)}{](../}g'
}

if [ ! -f "$source_file" ]; then
  echo "readme-sync: no README.md at $source_file" >&2
  exit 1
fi
if ! grep -q '<!-- BADGES:START -->' "$source_file"; then
  echo "readme-sync: README.md has no <!-- BADGES:START --> marker" >&2
  exit 1
fi

gitlab_out="$(flavour gitlab < "$source_file")"
github_out="$(printf '%s\n' "<!-- Generated from ../README.md by scripts/readme-sync.sh. Edit that one. -->" \
              && flavour github < "$source_file" | descend)"

if [ -n "$check" ]; then
  status=0
  printf '%s\n' "$gitlab_out" | diff -q - "$source_file" >/dev/null \
    || { echo "readme-sync: README.md is stale — run ./scripts/readme-sync.sh" >&2; status=1; }
  printf '%s\n' "$github_out" | diff -q - "$github_file" >/dev/null 2>&1 \
    || { echo "readme-sync: .github/README.md is stale — run ./scripts/readme-sync.sh" >&2; status=1; }
  [ $status -eq 0 ] && echo "readme-sync: both READMEs are current"
  exit $status
fi

mkdir -p "$(dirname "$github_file")"
printf '%s\n' "$gitlab_out" > "$source_file"
printf '%s\n' "$github_out" > "$github_file"
echo "readme-sync: wrote README.md (GitLab) and .github/README.md (GitHub)"
