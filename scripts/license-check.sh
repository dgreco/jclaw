#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# Every source file carries an SPDX header, so a file copied out of this tree
# carries its licence with it. A convention nothing checks decays on the first
# hurried commit — this is the check.
#
# Deliberately shell rather than a Maven plugin: it runs in the `verify` stage on a
# bare debian image alongside byte-verify.sh, before any toolchain exists, so a
# missing header fails in seconds rather than after a full compile.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

id_line="SPDX-License-Identifier: Apache-2.0"
copyright_line="SPDX-FileCopyrightText:"

sources() {
  find . -type f \( -name '*.java' -o -name '*.sh' \) \
    -not -path '*/target/*' -not -path '*/.git/*' \
    | sort
}

status=0

# Header scan. Only the first five lines are searched: a header further down is not a
# header, it is a coincidence — an SPDX identifier quoted inside a test fixture or a
# javadoc block would otherwise pass a file that has none of its own.
missing_id=0
missing_copyright=0
while IFS= read -r f; do
  head=$(head -5 "$f")
  case "$head" in
    *"$id_line"*) ;;
    *) echo "license-check: no SPDX-License-Identifier in $f" >&2; missing_id=$((missing_id + 1)); status=1 ;;
  esac
  case "$head" in
    *"$copyright_line"*) ;;
    *) echo "license-check: no SPDX-FileCopyrightText in $f" >&2; missing_copyright=$((missing_copyright + 1)); status=1 ;;
  esac
done < <(sources)

# The three root-level facts the headers point at. A header naming Apache-2.0 in a tree
# with no LICENSE is a claim with nothing behind it.
for required in LICENSE NOTICE; do
  if [ ! -f "$required" ]; then
    echo "license-check: $required is missing from the project root" >&2
    status=1
  fi
done

# The jar is what a consumer actually receives, and a LICENSE file in the repository
# does not travel inside one. The POM declaration is what does.
if ! grep -q '<licenses>' pom.xml; then
  echo "license-check: the root pom.xml declares no <licenses> block" >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "license-check: ok ($(sources | wc -l | tr -d ' ') files, LICENSE, NOTICE, pom metadata)"
else
  echo "license-check: $missing_id file(s) without an identifier, $missing_copyright without a copyright line" >&2
fi
exit "$status"
