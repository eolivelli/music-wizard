#!/usr/bin/env python3
"""What changed in a baseline file between two commits, told apart by kind.

`premerge.sh` calls this to say whether a figure quoted earlier in a branch's
life was measured against a baseline `main` has regenerated since (#428). A
column added to every row rewrites the whole file without moving a single
measurement, so reporting that the way a changed number is reported would teach
people to skip the prompt -- hence the two kinds.

    python3 tools/baseline-drift.py <base> <tip> <path>...

Exits 1 if anything could have been re-measured, 2 if the files were only
reshaped, 0 if no row in them changed.
"""

import re
import subprocess
import sys


def rows(text: str) -> dict:
    """Baseline rows as name -> the fields printed on it, in order."""
    out: dict = {}
    for line in text.splitlines():
        # Rows are indented and named; anything flush left is a header.
        if not line[:1].isspace() or ":" not in line:
            continue
        name, _, body = line.partition(":")
        name = name.strip()
        # Two sections may print the same name -- score-samples' key section
        # prefixes it today, but overwriting would hide one section's movement.
        while name in out:
            name += "'"
        out[name] = re.split(r"\s{2,}", body.strip())
    return out


def shapes(fields: list) -> dict:
    """Fields keyed by shape -- the field with every number masked -- and by
    which occurrence of that shape they are, so two fields that mask to the
    same text stay two fields rather than one concatenation."""
    out, seen = {}, {}
    for f in fields:
        k = re.sub(r"\d+(?:\.\d+)?", "#", f)
        seen[k] = seen.get(k, 0) + 1
        out[(k, seen[k])] = f
    return out


def describe(old_text: str, new_text: str) -> tuple:
    """(summary, detail lines, "moved" | "reshaped" | "")."""
    old, new = rows(old_text), rows(new_text)
    if not old and not new:
        # The file changed and nothing in it parsed as a row. Reporting that as
        # "rows unchanged" would be the all-clear off an empty comparison.
        return "changed, and no row in it parsed -- read the diff", [], "moved"
    common = sorted(set(old) & set(new))
    figures, reshaped, gone, came = [], [], set(), set()
    for r in common:
        o, n = old[r], new[r]
        if len(o) == len(n):
            # Same columns, so compare them where they stand: a measurement
            # moves whether it reads as a number or as a word.
            if o != n:
                figures.append(r)
            continue
        reshaped.append(r)
        so, sn = shapes(o), shapes(n)
        lost, found = so.keys() - sn.keys(), sn.keys() - so.keys()
        gone |= lost
        came |= found
        # Where the columns moved, a field can only be matched to its old self
        # by shape -- except a field with no number in it, which IS the
        # measurement: a key row's OK. One of those vanishing is a
        # re-measurement, where one merely appearing is a new column.
        if any(so[k] != sn[k] for k in so.keys() & sn.keys()) \
                or any("#" not in k for k, _ in lost):
            figures.append(r)
    bits, detail = [], []
    kind = "moved" if figures else "reshaped" if reshaped else ""
    if set(old) != set(new):
        kind = "moved"
        bits.append(f"{len(set(new) - set(old))} rows added, "
                    f"{len(set(old) - set(new))} removed")
    if figures:
        bits.append(f"figures moved in {len(figures)} of {len(common)} rows")
        # Naming them is the actionable half: a reader who quoted one of these
        # benchmarks knows to re-take that figure, and one who did not is done.
        detail.append("      " + ", ".join(figures[:5])
                      + (f" and {len(figures) - 5} more"
                         if len(figures) > 5 else ""))
    if reshaped:
        bits.append(f"columns changed in {len(reshaped)} of {len(common)} rows"
                    + ("" if figures else ", no shared figure moved"))
        # A field whose own shape changed cannot be compared with its old self,
        # so show what appeared and vanished and let the reader judge rather
        # than claim nothing there was re-measured.
        detail += [f"      - {k}" for k in sorted({k for k, _ in gone})]
        detail += [f"      + {k}" for k in sorted({k for k, _ in came})]
    return "; ".join(bits) if bits else "rows unchanged", detail, kind


def show(rev: str, path: str):
    """The file at that commit, or None where it did not exist."""
    p = subprocess.run(["git", "show", f"{rev}:{path}"],
                       capture_output=True, text=True)
    return None if p.returncode else p.stdout


def main(argv: list) -> int:
    base, tip, paths = argv[1], argv[2], argv[3:]
    kinds = set()
    for path in paths:
        old, new = show(base, path), show(tip, path)
        name = path.rsplit("/", 1)[-1]
        if old is None or new is None:
            kinds.add("moved")
            print(f"  {name}: {'added' if old is None else 'removed'} on main")
            continue
        summary, detail, kind = describe(old, new)
        kinds.add(kind)
        print(f"  {name}: {summary}")
        if detail:
            print("\n".join(detail))
    # Three states, because the caller says three different things: a figure
    # that may be stale, a column that cannot make one stale, and a file whose
    # rows did not change at all.
    return 1 if "moved" in kinds else 2 if "reshaped" in kinds else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
