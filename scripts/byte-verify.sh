#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
# Guards against the transport hazard: tool-param writes can silently lowercase
# camelCase identifiers on disk. Compilation catches it in .java, but this gates
# the build (and hand-checks) over every source type, including poms and SQL.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
manifest=".byte-manifest"

sources() {
  find . -type f \
    \( -name '*.java' -o -name 'pom.xml' -o -name '*.json' -o -name '*.sql' \
       -o -name '*.properties' -o -name '*.yml' -o -name '*.sh' \) \
    -not -path '*/target/*' -not -path '*/.git/*' -not -name "$manifest" \
    | sort
}

generate() {
  sources | while IFS= read -r f; do shasum -r "${f#./}"; done
}

# Stray C0 control bytes (excluding tab, LF, CR) are the other half of the transport
# hazard: an invisible 0x1F inside a char literal reads as '' in every viewer and can
# turn into a different byte — or vanish — on the next write. Source stays printable.
# Deliberately uses tr rather than `grep -P`: macOS ships BSD grep, which has no -P at
# all. It exits non-zero on the unknown option, which is indistinguishable from "no match"
# — so a PCRE-based check silently passes everything on this platform. tr is POSIX and
# behaves identically on BSD and GNU.
#
# Deletes tab/LF/CR, printable ASCII, and all high-bit bytes (UTF-8 prose in javadoc is
# legitimate). Whatever survives is a genuine C0 control byte or DEL.
scan_control_bytes() {
  found=0
  while IFS= read -r f; do
    if [ "$(LC_ALL=C tr -d '\011\012\015\040-\176\200-\377' < "$f" | wc -c | tr -d ' ')" != "0" ]; then
      echo "byte-verify: CONTROL BYTE in $f" >&2
      found=1
    fi
  done < <(sources)
  return "$found"
}

case "${1:-validate}" in
  install)
    scan_control_bytes || { echo "byte-verify: refusing to install a manifest over tainted bytes" >&2; exit 1; }
    generate > "$manifest"
    echo "byte-verify: installed $(wc -l < "$manifest") entries"
    ;;
  scan)
    # Control-byte check alone — useful in CI and after bulk file generation,
    # and it needs no installed manifest.
    scan_control_bytes || exit 1
    echo "byte-verify: scan ok ($(sources | wc -l | tr -d ' ') files, no control bytes)"
    ;;
  validate)
    scan_control_bytes || exit 1
    if [ ! -f "$manifest" ]; then
      echo "byte-verify: no manifest — run 'byte-verify.sh install' after adding files" >&2
      exit 1
    fi
    tmp=$(mktemp)
    generate > "$tmp"
    if ! diff -q "$tmp" "$manifest" >/dev/null; then
      echo "byte-verify: MANIFEST DRIFT — on-disk bytes differ from installed state:" >&2
      diff "$tmp" "$manifest" | sed 's/^/  /' >&2
      rm -f "$tmp"
      exit 1
    fi
    rm -f "$tmp"
    echo "byte-verify: ok ($(wc -l < "$manifest") files)"
    ;;
  *)
    echo "usage: byte-verify.sh {install|validate|scan}" >&2
    exit 64
    ;;
esac
