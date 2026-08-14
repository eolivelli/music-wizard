#!/usr/bin/env python3
"""What changed in a baseline file between two commits, told apart by kind.

`premerge.sh` calls this to say whether a figure quoted earlier in a branch's
life was measured against a baseline `main` has regenerated since (#428). A
column added to every row rewrites the whole file without moving a single
measurement, so reporting that the way a changed number is reported would teach
people to skip the prompt -- hence the two kinds.

    python3 tools/baseline-drift.py <base> <tip> <path>...

Exits 1 if anything could have been re-measured, 0 if not.
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
    """(summary, detail lines, could anything have been re-measured)."""
    old, new = rows(old_text), rows(new_text)
    if not old and not new:
        # The file changed and nothing in it parsed as a row. Reporting that as
        # "rows unchanged" would be the all-clear off an empty comparison.
        return "changed, and no row in it parsed -- read the diff", [], True
    common = sorted(set(old) & set(new))
    figures, reshaped, gone, came = [], [], set(), set()
    for r in common:
        o, n = old[r], new[r]
        if len(o) == len(n):
            # Same columns, so compare them where they stand. A measurement
            # that reads as a word moves this way and no other: a key row goes
            # from OK to WRONG without a digit changing anywhere.
            if o != n:
                figures.append(r)
            continue
        reshaped.append(r)
        so, sn = shapes(o), shapes(n)
        gone |= so.keys() - sn.keys()
        came |= sn.keys() - so.keys()
        if any(so[k] != sn[k] for k in so.keys() & sn.keys()):
            figures.append(r)
    bits, detail = [], []
    remeasured = bool(figures)
    if set(old) != set(new):
        remeasured = True
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
        detail += [f"      - {k}" for k, _ in sorted(set(gone))]
        detail += [f"      + {k}" for k, _ in sorted(set(came))]
    return "; ".join(bits) if bits else "rows unchanged", detail, remeasured


def show(rev: str, path: str):
    """The file at that commit, or None where it did not exist."""
    p = subprocess.run(["git", "show", f"{rev}:{path}"],
                       capture_output=True, text=True)
    return None if p.returncode else p.stdout


def main(argv: list) -> int:
    base, tip, paths = argv[1], argv[2], argv[3:]
    remeasured = False
    for path in paths:
        old, new = show(base, path), show(tip, path)
        name = path.rsplit("/", 1)[-1]
        if old is None or new is None:
            remeasured = True
            print(f"  {name}: {'added' if old is None else 'removed'} on main")
            continue
        summary, detail, moved = describe(old, new)
        remeasured = remeasured or moved
        print(f"  {name}: {summary}")
        if detail:
            print("\n".join(detail))
    return 1 if remeasured else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
