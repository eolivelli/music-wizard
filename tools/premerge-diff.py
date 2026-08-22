#!/usr/bin/env python3
"""One harness's output against its committed baseline, for tools/premerge.sh.

Reads the harness output on stdin, prints a block for every row that moved
(DIFF) or that this machine could not measure (SKIP), and ends with one
accounting line saying how many baselined rows were actually compared. Every
skip is also appended to the record file named on the command line, where the
verdict reads it back and names it rather than counting it (#464).

Exits 0 when every row compared matched, 3 when a DIFF was printed, and 4 when
the baseline holds no row this can key -- a step comparing nothing is not a
clean one, and premerge fails on any status it does not know.
"""

import re
import sys
from pathlib import Path

MARKER = ": not present (local-only"


def rows(lines):
    """The lines naming one benchmark each, keyed by that name.

    Two shapes qualify: anything carrying "<file>.mp3:", which is what the
    chord and lyric harnesses print, and an indented "  <name>: ", which is
    what the melody harness prints -- its benchmarks are packages and clips
    rather than files, so they carry no extension and were keyed by nothing at
    all. That is not a cosmetic gap: with no key, a harness's every row was
    absent from both sides of this diff, the loop below had nothing to compare,
    and the step passed however far the numbers had moved. It was added in #494
    and blind from the first run; CI's own plain diff of the same baseline is
    what was actually defending it.
    """
    keyed = {}
    for line in lines:
        if ".mp3:" in line or re.match(r"^  \S+: ", line):
            # Two rows keyed alike would otherwise collapse into one, taking a
            # row off both sides of the diff and out of the count below with
            # it -- the count is a claim, so it must not be able to shrink
            # quietly. tools/baseline-drift.py disambiguates the same way.
            name = line.split(":")[0].strip()
            while name in keyed:
                name += "'"
            keyed[name] = line.rstrip()
    return keyed


def reason(row):
    """The cause the harness printed beside its skip, for the verdict to name.

    Whitespace is collapsed because the cause travels as one tab-separated
    field: a reason carrying a tab of its own would reach the verdict as a row
    with fields it cannot read.
    """
    tail = row.split(MARKER, 1)[1].lstrip("; ").rstrip()
    return " ".join((tail[:-1] if tail.endswith(")") else tail).split())


def main(argv):
    if len(argv) != 3:
        print(f"usage: {argv[0]} <baseline> <skip-record-file>", file=sys.stderr)
        return 2
    baseline_path, record_path = Path(argv[1]), Path(argv[2])
    baseline = rows(baseline_path.read_text(encoding="utf-8").splitlines())
    current = rows(sys.stdin.read().splitlines())
    name = baseline_path.name

    out, skips, compared = [], [], 0
    for row, base in sorted(baseline.items()):
        if row not in current:
            # The harness ran to completion and still printed nothing for this
            # baselined name: harness and baseline disagree about the corpus --
            # a benchmark retired without regenerating the baseline. Fetching a
            # file cannot fix that, so it fails rather than skips.
            out.append(f"DIFF {row}\n  baseline: {base}\n  current:  (no row printed)")
        elif MARKER in current[row]:
            # The diff can only compare what this machine can measure. Only the
            # CURRENT side may say so: a committed baseline that certifies
            # absence is a defect, and where this machine can measure the file
            # it falls through to DIFF below.
            skips.append((row, reason(current[row])))
            out.append(f"SKIP {row}: {skips[-1][1]}")
        else:
            compared += 1
            if current[row] != base:
                out.append(f"DIFF {row}\n  baseline: {base}\n  current:  {current[row]}")
    for row, line in sorted(current.items()):
        if row not in baseline:
            # The reverse disagreement: the harness printed a row the baseline
            # never recorded -- a benchmark added without regenerating the
            # baseline. The synthetic corpus grows a package per music-teacher
            # run, so this direction is the common one there.
            out.append(f"DIFF {row}\n  baseline: (no row recorded)\n  current:  {line}")

    for line in out:
        print(line)
    if not baseline:
        print(f"NOTHING COMPARED: {baseline_path} holds no row this can key.")
        return 4

    with record_path.open("a", encoding="utf-8") as records:
        records.write(f"total\t{name}\t{len(baseline)}\n")
        for row, why in skips:
            records.write(f"skip\t{name}\t{row}\t{why}\n")

    account = f"compared {compared} of {len(baseline)} baselined rows in {baseline_path}"
    if skips:
        account += f"; {len(skips)} skipped"
        if compared == 0:
            account += ", so this step certified nothing"
    print(account)
    return 3 if any(line.startswith("DIFF") for line in out) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
