#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0

"""Converts JaCoCo's XML report to the Cobertura shape GitLab reads.

GitLab paints covered and uncovered lines onto a merge request's diff from a Cobertura report,
and understands no other format. JaCoCo does not emit one, so something has to translate.

That something is in-tree rather than a container image, for two reasons that both bit:
the published converter image is amd64-only and this project's runner is arm64, and it carries
no versioned tag, so depending on it means depending on whatever `latest` points at today.
A hundred lines that can be read, pinned by being committed, and tested against the real report
is the cheaper dependency.

    jacoco-to-cobertura.py <jacoco.xml> <source-root>...  > cobertura.xml

Filenames come out repository-relative — `jclaw-domain/src/main/java/io/jclaw/...` — because that
is what GitLab matches against the diff. They are resolved by looking for the file under each
source root in turn, not by string arithmetic on the package name, so a module layout that does
not match its package prefix still lands in the right place.
"""

import os
import sys
import time
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr


def rate(missed, covered):
    total = missed + covered
    return (covered / total) if total else 1.0


def counter(node, kind):
    """JaCoCo's (missed, covered) for one counter type, or zeros when it has none."""
    for c in node.findall("counter"):
        if c.get("type") == kind:
            return int(c.get("missed", 0)), int(c.get("covered", 0))
    return 0, 0


def resolve(package, filename, roots):
    """The repository-relative path of a source file, or the package-relative one if unfound."""
    relative = os.path.join(package, filename)
    for root in roots:
        if os.path.isfile(os.path.join(root, relative)):
            return os.path.join(root, relative)
    return relative


def main(argv):
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    report_path, roots = argv[1], [r.rstrip("/") for r in argv[2:]]
    report = ET.parse(report_path).getroot()

    im, ic = counter(report, "LINE")
    bm, bc = counter(report, "BRANCH")
    cm, cc = counter(report, "COMPLEXITY")

    out = [
        '<?xml version="1.0" ?>',
        '<!DOCTYPE coverage SYSTEM "http://cobertura.sourceforge.net/xml/coverage-04.dtd">',
        '<coverage line-rate="%s" branch-rate="%s" lines-covered="%d" lines-valid="%d" '
        'branches-covered="%d" branches-valid="%d" complexity="%d" version="jacoco" '
        'timestamp="%d">' % (rate(im, ic), rate(bm, bc), ic, im + ic, bc, bm + bc, cm + cc,
                             int(time.time())),
        "<sources><source>.</source></sources>",
        "<packages>",
    ]

    # `iter`, not `findall`: an aggregate report nests its packages one level down, inside a
    # <group> per module. Reading only the top level yields a report with correct totals and no
    # classes at all — valid XML that annotates nothing, which is the failure that looks like
    # success. Verified against the reference converter's output before this was believed.
    for package in report.iter("package"):
        name = package.get("name", "")
        pim, pic = counter(package, "LINE")
        pbm, pbc = counter(package, "BRANCH")
        out.append('<package name=%s line-rate="%s" branch-rate="%s" complexity="0"><classes>'
                   % (quoteattr(name.replace("/", ".")), rate(pim, pic), rate(pbm, pbc)))

        for sourcefile in package.findall("sourcefile"):
            filename = resolve(name, sourcefile.get("name", ""), roots)
            sim, sic = counter(sourcefile, "LINE")
            sbm, sbc = counter(sourcefile, "BRANCH")
            # One class element per source file, not per JaCoCo class: nested and anonymous
            # classes share a file, and GitLab keys the annotation on the filename.
            out.append('<class name=%s filename=%s line-rate="%s" branch-rate="%s" '
                       'complexity="0"><methods/><lines>'
                       % (quoteattr(os.path.splitext(os.path.basename(filename))[0]),
                          quoteattr(filename), rate(sim, sic), rate(sbm, sbc)))
            for line in sourcefile.findall("line"):
                number = line.get("nr")
                covered_instructions = int(line.get("ci", 0))
                missed_branches = int(line.get("mb", 0))
                covered_branches = int(line.get("cb", 0))
                total_branches = missed_branches + covered_branches
                if total_branches:
                    percent = int(100 * covered_branches / total_branches)
                    out.append('<line number="%s" hits="%d" branch="true" '
                               'condition-coverage=%s/>'
                               % (number, 1 if covered_instructions else 0,
                                  quoteattr("%d%% (%d/%d)"
                                            % (percent, covered_branches, total_branches))))
                else:
                    out.append('<line number="%s" hits="%d" branch="false"/>'
                               % (number, 1 if covered_instructions else 0))
            out.append("</lines></class>")
        out.append("</classes></package>")

    out.append("</packages></coverage>")
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
