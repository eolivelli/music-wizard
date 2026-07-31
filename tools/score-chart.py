#!/usr/bin/env python3
"""Scores the chord chart the tool actually prints, not the model behind it.

`tools/score-samples.py` reads `score/score.json` and asks whether the
*estimate* names the right chord in each bar. This reads `out/chords.ly` and
asks the question a reader of the page asks: how many chords are printed in a
bar, and is the one a reader takes away from that bar the right one.

The two differ in a way worth keeping visible. The chart's bar lines are its
own -- one downbeat for phase and `Score.estimatedTempo()` for rate -- so on a
recording whose beat drifts they walk away from the recording's downbeats
(#187, #196, #200). A chart score is therefore never better than the model
score and the gap is the drift, not the harmony.

Both columns are reported per benchmark:

  chords/bar   printed chords divided by printed bars. #212's metric.
  root, root+quality
               per bar, taking the bar's dominant printed chord -- the one
               filling most of it, which is what a reader takes from the bar --
               against the known cycle at its best rotation, exactly as
               score-samples.py scores the model.

Usage:  python3 tools/score-chart.py [--jar mw-cli/target/mw.jar]
"""

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

# The ground truth and its parsing are score-samples.py's, imported rather than
# copied: two spellings of one truth is how the two harnesses come to disagree
# about what the right answer is.
from importlib import import_module  # noqa: E402

_samples = import_module("score-samples")
BENCHMARKS = _samples.BENCHMARKS
parse_truth = _samples.parse_truth

# LilyPond chordmode, as ChordChart.chordMode writes it: a root, a duration,
# an optional quality modifier and an optional bass. A rest is a no-chord span.
TOKEN = re.compile(
    r"^(?P<root>r|[a-g](?:is|es)*)"
    r"(?P<duration>1\*\d+(?:/\d+)?|\d+\.?)"
    r"(?P<quality>:[^/\s]+)?"
    r"(?P<bass>/[a-g](?:is|es)*)?$"
)

LETTER_SEMITONE = {"c": 0, "d": 2, "e": 4, "f": 5, "g": 7, "a": 9, "b": 11}

# The inverse of ChordChart.lilyPondQuality. A bare root is a major triad.
QUALITY = {
    "": "MAJOR",
    ":m": "MINOR",
    ":dim": "DIMINISHED",
    ":aug": "AUGMENTED",
    ":sus2": "SUSPENDED_SECOND",
    ":sus4": "SUSPENDED_FOURTH",
    ":7": "DOMINANT_SEVENTH",
    ":maj7": "MAJOR_SEVENTH",
    ":m7": "MINOR_SEVENTH",
    ":m7+": "MINOR_MAJOR_SEVENTH",
    ":m7.5-": "HALF_DIMINISHED_SEVENTH",
    ":dim7": "DIMINISHED_SEVENTH",
    ":6": "SIXTH",
    ":m6": "MINOR_SIXTH",
}


def quarters(duration: str) -> float:
    """A LilyPond duration token as a length in quarter-note beats."""
    if duration.startswith("1*"):
        fraction = duration[2:].split("/")
        numerator = int(fraction[0])
        denominator = int(fraction[1]) if len(fraction) > 1 else 1
        return 4.0 * numerator / denominator
    dotted = duration.endswith(".")
    value = 4.0 / int(duration.rstrip("."))
    return value * 1.5 if dotted else value


def cell_of(token: str) -> tuple[tuple[int, str] | None, float]:
    """One chordmode event as ((pitch class, quality) or None, length)."""
    match = TOKEN.match(token)
    if not match:
        sys.exit(f"unparsed chordmode token: {token!r}")
    length = quarters(match["duration"])
    root = match["root"]
    if root == "r":
        return None, length
    pitch = LETTER_SEMITONE[root[0]]
    for accidental in re.findall(r"is|es", root[1:]):
        pitch += 1 if accidental == "is" else -1
    quality = match["quality"] or ""
    if quality not in QUALITY:
        sys.exit(f"unmapped chordmode quality: {quality!r}")
    return (pitch % 12, QUALITY[quality]), length


def bars_of(lilypond: str) -> list[list[tuple[tuple[int, str] | None, float]]]:
    """The chart's bars, each a list of cells, read back out of its source."""
    bars = []
    for line in lilypond.splitlines():
        stripped = line.strip()
        # One bar to a line, closed by a bar check. \time and the wrapper lines
        # are not bars.
        if not stripped.endswith("|") or stripped.startswith("\\"):
            continue
        tokens = stripped[:-1].split()
        if tokens:
            bars.append([cell_of(t) for t in tokens])
    return bars


def render(jar: Path, mp3: Path, workspace: Path) -> str:
    for args in (["init", str(mp3), "--workspace", str(workspace)],
                 ["analyze", str(workspace)],
                 ["render", str(workspace), "--parts", "chords"]):
        result = subprocess.run(["java", "-jar", str(jar), *args],
                                capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"mw {args[0]} failed on {mp3.name}:\n{result.stdout}{result.stderr}")
    return (workspace / "out" / "chords.ly").read_text()


def score(name: str, lilypond: str, truth: str) -> None:
    bars = bars_of(lilypond)
    printed = sum(len(bar) for bar in bars)

    labels = []
    for bar in bars:
        # The chord filling most of the bar, which is what a reader takes from
        # it. Ties go to the earlier cell, so a bar split evenly reads as the
        # chord it opens on.
        held: dict[tuple[int, str] | None, float] = {}
        order: list[tuple[int, str] | None] = []
        for chord, length in bar:
            if chord not in held:
                held[chord] = 0.0
                order.append(chord)
            held[chord] += length
        labels.append(max(order, key=lambda c: held[c]) if order else None)

    want = parse_truth(truth)
    cycle = len(want)

    def rotated(rotation: int) -> tuple[int, int]:
        root_ok = full_ok = 0
        for index, got in enumerate(labels):
            acceptable = want[(index + rotation) % cycle]
            if got is not None and any(got[0] == w[0] for w in acceptable):
                root_ok += 1
                if any(got == w for w in acceptable):
                    full_ok += 1
        return root_ok, full_ok

    root_ok, full_ok = max((rotated(r) for r in range(cycle)), key=lambda t: t[0])
    n = max(len(bars), 1)
    print(f"  {name}: bars={len(bars)}  chords/bar {printed / n:.2f}"
          f"  root {root_ok}/{n} ({100 * root_ok / n:.1f}%)"
          f"  root+quality {full_ok}/{n} ({100 * full_ok / n:.1f}%)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--cycles", type=int, default=0,
                        help="also print this many bars of the chart verbatim")
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"build first: mvn -B -DskipTests package   (missing {jar})")

    print("charts emitted for samples with known ground truth:")
    missing = []
    for name, truth in BENCHMARKS.items():
        mp3 = REPO / "samples" / name
        if not mp3.exists():
            missing.append(name)
            continue
        with tempfile.TemporaryDirectory() as tmp:
            workspace = Path(tmp) / "w.mwz"
            lilypond = render(jar, mp3, workspace)
            score(name, lilypond, truth)
            if args.cycles:
                text = (workspace / "out" / "chords.txt").read_text().splitlines()
                for line in [ln for ln in text if ln.startswith("|")][:args.cycles]:
                    print("      " + line)
    for name in missing:
        print(f"  {name}: not present (local-only; see samples/list.txt to fetch)")


if __name__ == "__main__":
    main()
