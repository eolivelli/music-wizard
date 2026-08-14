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
    """Baseline rows as name -> {field shape: field}.

    A field is keyed by its shape -- itself with every number masked -- so a
    column added or renamed is a key that appeared, and a measurement that
    moved is a key whose value changed.
    """
    out = {}
    for line in text.splitlines():
        # Rows are indented and named; anything flush left is a header.
        if not line[:1].isspace() or ":" not in line:
            continue
        name, _, body = line.partition(":")
        fields = {}
        for f in re.split(r"\s{2,}", body.strip()):
            k = re.sub(r"\d+(?:\.\d+)?", "#", f)
            fields[k] = fields.get(k, "") + f
        out[name.strip()] = fields
    return out


def describe(old: str, new: str) -> tuple:
    """(summary, detail lines, could anything have been re-measured)."""
    old, new = rows(old), rows(new)
    common = sorted(set(old) & set(new))
    figures = [r for r in common
               if any(k in new[r] and old[r][k] != new[r][k] for k in old[r])]
    shape = [r for r in common if set(old[r]) != set(new[r])]
    bits, detail, remeasured = [], [], False
    if set(old) != set(new):
        remeasured = True
        bits.append(f"{len(set(new) - set(old))} rows added, "
                    f"{len(set(old) - set(new))} removed")
    if figures:
        remeasured = True
        bits.append(f"figures moved in {len(figures)} of {len(common)} rows")
        # Naming them is the actionable half: a reader who quoted one of these
        # benchmarks knows to re-take that figure, and one who did not is done.
        detail.append("      " + ", ".join(figures[:5])
                      + (f" and {len(figures) - 5} more"
                         if len(figures) > 5 else ""))
    if shape:
        bits.append(f"columns changed in {len(shape)} of {len(common)} rows"
                    + ("" if figures else ", no shared figure moved"))
        # A field whose own shape changed cannot be compared with its old self,
        # so show what appeared and vanished and let the reader judge rather
        # than claim nothing there was re-measured.
        detail += [f"      - {k}" for k in
                   sorted({k for r in shape for k in set(old[r]) - set(new[r])})]
        detail += [f"      + {k}" for k in
                   sorted({k for r in shape for k in set(new[r]) - set(old[r])})]
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
