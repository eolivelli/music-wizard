#!/usr/bin/env python3
"""What a premerge run could not certify, and whether that was expected here.

Reads the records tools/premerge-diff.py wrote for the run and turns them into
the verdict's account of the rows this machine did not measure: each named with
the cause its harness printed, each step whose every row skipped called out as
having certified nothing, and each row checked against this machine's
expected-skip manifest (#464, #466).

The manifest is per machine rather than per checkout, because that is what the
question is about: a worktree that was provisioned without its local-only audio
skips rows the machine can measure, and only something outside that worktree
can tell the two apart. It sits beside MW's own config --
$XDG_CONFIG_HOME/music-wizard/premerge-skips.txt, or $MW_PREMERGE_SKIPS -- and
holds one glob per line, matched against "<baseline> <row>", with # comments.

It is absent by default, and then the skips are named but not gated: a fresh
clone short of the corpus must not fail the gate on every branch (#365), and a
machine that cannot reach an optional model must be able to say so without
editing anything under version control (#487).

Exits 0 when every skipped row was expected here, 3 when this machine declares
nothing, and 1 when a row skipped that the manifest does not cover.
"""

import fnmatch
import os
import sys
from pathlib import Path


def manifest_location():
    override = os.environ.get("MW_PREMERGE_SKIPS")
    if override:
        return Path(override)
    xdg = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg) if xdg and xdg.strip() else Path.home() / ".config"
    return base / "music-wizard" / "premerge-skips.txt"


def patterns(path):
    return [line.split("#", 1)[0].strip()
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.split("#", 1)[0].strip()]


def read(record_file):
    totals, skips = {}, []
    for line in record_file.read_text(encoding="utf-8").splitlines():
        kind, *fields = line.split("\t")
        if kind == "total":
            totals[fields[0]] = int(fields[1])
        elif kind == "skip":
            skips.append(tuple(fields))
    return totals, skips


def main(argv):
    if len(argv) != 4:
        print(f"usage: {argv[0]} <record-file> <skip-lines-printed> <steps-run>",
              file=sys.stderr)
        return 2
    record_file, printed, steps = Path(argv[1]), int(argv[2]), int(argv[3])
    totals, skips = read(record_file) if record_file.exists() else ({}, [])
    # A blind account prints exactly what a clean run prints, so it is held
    # against what premerge saw: every step records what it compared, and
    # premerge counted both the steps it ran and the SKIP lines it printed.
    if len(totals) != steps:
        print(f"FAIL: {len(totals)} of {steps} steps recorded what they compared "
              f"in {record_file}, so this cannot say what the run certified.")
        return 1
    if len(skips) != printed:
        print(f"FAIL: premerge printed {printed} skipped rows and {len(skips)} "
              f"reached {record_file}. The account below cannot be trusted.")
        return 1

    total = sum(totals.values())
    if not skips:
        print(f"every one of the {total} baselined rows was measured here.")
        return 0

    print(f"{len(skips)} of {total} baselined rows were not measured here:")
    for baseline, row, why in skips:
        print(f"  {baseline} {row} — {why}")
    for baseline in blind(totals, skips):
        print(f"{baseline}: every row skipped, so that step certified nothing.")

    location = manifest_location()
    if not location.exists():
        print(f"This machine declares no expected skips ({location} is absent), so "
              "the rows above are named but not gated. Write one glob per line, "
              "matched against '<baseline> <row>', to make an unexpected skip fail.")
        summarise(skips, totals, "not gated on this machine")
        return 3

    expected = patterns(location)
    unexpected = [f"{baseline} {row}" for baseline, row, _ in skips
                  if not any(fnmatch.fnmatchcase(f"{baseline} {row}", pattern)
                             for pattern in expected)]
    if not unexpected:
        print(f"all of them expected per {location}.")
        summarise(skips, totals, "all expected here")
        return 0
    print(f"{len(unexpected)} of them undeclared on this machine. Provision this "
          f"tree, or -- if this machine genuinely cannot measure them -- add to "
          f"{location}:")
    for key in unexpected:
        print(f"  {key}")
    summarise(skips, totals, f"{len(unexpected)} unexpected here")
    return 1


def blind(totals, skips):
    """The baselines whose every row skipped: those steps certified nothing,
    which is the claim a bare PASS was making for them (#466)."""
    return sorted(baseline for baseline in totals
                  if totals[baseline] == sum(1 for skip in skips
                                             if skip[0] == baseline))


def summarise(skips, totals, verdict):
    """The fragment premerge.sh splices into the verdict line, which is what
    gets pasted into a pull request: it must not read as a full run."""
    line = (f"SUMMARY: {len(skips)} of {sum(totals.values())} rows not measured, "
            f"{verdict}")
    nothing = blind(totals, skips)
    if nothing:
        line += f"; {', '.join(nothing)} certified nothing"
    print(line)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
