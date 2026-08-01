#!/usr/bin/env python3
"""Scores mw against the sample benchmarks whose ground truth is known.

For each benchmark present in samples/ (some are local-only and fetched per
the instructions in samples/list.txt), this runs the shaded CLI, segments the
estimated chords into bars on the recording's own tracked beats, aligns the
known 12-bar cycle at the best rotation, and reports per-bar accuracy.

The committed CI gate for the committed sample lives in mw-it; this script is
the local, all-samples view of the same question: is the tool getting closer
to the charts a musician would write?

Usage:  python3 tools/score-samples.py [--jar mw-cli/target/mw.jar]
"""

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Ground truth from samples/list.txt, in lead-sheet shorthand: '7' dominant,
# 'm7' minor seventh, 'm6' minor sixth, '0' half-diminished, 'M7' major
# seventh, plain letter a major triad. 'X-Y' is a bar holding both chords;
# either counts. Scoring reports root match and root+quality separately, so a
# plain triad on the right root earns the first and not the second.
BENCHMARKS = {
    "gmajorblues.mp3":
        "G7 G7 G7 G7  C7 C7 G7 G7  D7 C7 G7 D7",
    "blues-a-90bpm.mp3":
        "A7 A7 A7 A7  D7 D7 A7 A7  E7 D7 A7 E7",
    "blues-shuffle-a-106bpm.mp3":
        "A7 A7 A7 A7  D7 D7 A7 A7  E7 D7 A7 E7",
    "blues-e-90bpm.mp3":
        "E7 E7 E7 E7  A7 A7 E7 E7  B7 A7 E7 B7",
    "fm7-vamp-110.mp3":
        "Fm7",
    "eb7-vamp-130.mp3":
        "Eb7",
    "bossa-cm.mp3":
        "Cm7 Cm7 Fm6 Fm6  D0 G7 Cm6 Cm6  Ebm7 Ab7 DbM7 DbM7  D0 G7 Cm6 D0-G7",
}

LETTER_SEMITONE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
ACCIDENTAL = {"NONE": 0, "SHARP": 1, "FLAT": -1, "DOUBLE_SHARP": 2, "DOUBLE_FLAT": -2}
SUFFIX_QUALITY = {
    "": "MAJOR",
    "m": "MINOR",
    "7": "DOMINANT_SEVENTH",
    "m7": "MINOR_SEVENTH",
    "m6": "MINOR_SIXTH",
    "6": "SIXTH",
    "0": "HALF_DIMINISHED_SEVENTH",
    "M7": "MAJOR_SEVENTH",
}


def parse_chord(symbol: str) -> tuple[int, str]:
    """Lead-sheet symbol -> (pitch class, ChordQuality name)."""
    pc = LETTER_SEMITONE[symbol[0]]
    rest = symbol[1:]
    while rest[:1] in ("b", "#"):
        pc += 1 if rest[0] == "#" else -1
        rest = rest[1:]
    return pc % 12, SUFFIX_QUALITY[rest]


def parse_truth(line: str) -> list[list[tuple[int, str]]]:
    """One cycle as a list of bars, each a list of acceptable chords."""
    return [[parse_chord(c) for c in bar.split("-")] for bar in line.split()]


def chord_of(span) -> tuple[int, str] | None:
    """(pitch class, quality) of an estimated span, or None for N.C."""
    quality = span.get("quality", "NONE")
    if quality == "NONE":
        return None
    root = span.get("root", {})
    pc = (LETTER_SEMITONE.get(root.get("letter", "C")[0], 0)
          + ACCIDENTAL.get(root.get("accidental", "NONE"), 0)) % 12
    return pc, quality


def analyze(jar: Path, mp3: Path) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        ws = Path(tmp) / "w.mwz"
        for args in (["init", str(mp3), "--workspace", str(ws)], ["analyze", str(ws)]):
            r = subprocess.run(["java", "-jar", str(jar), *args],
                               capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(f"mw {args[0]} failed on {mp3.name}:\n{r.stdout}{r.stderr}")
        return json.loads((ws / "score" / "score.json").read_text())


def bar_label(spans, start: float, end: float):
    """The chord covering most of [start, end), or None."""
    best, best_overlap = None, 0.0
    for s in spans:
        overlap = min(end, s["endSeconds"]) - max(start, s["startSeconds"])
        if overlap > best_overlap:
            best, best_overlap = s, overlap
    return chord_of(best) if best else None


def score(mp3: Path, jar: Path, truth: list[str]) -> None:
    doc = analyze(jar, mp3)
    spans = doc.get("chords", {}).get("chords", [])
    beats = doc.get("beatGrid", {}).get("beats", [])
    downbeats = [b["seconds"] for b in beats if b.get("downbeat")]
    if len(downbeats) < 4:
        print(f"  {mp3.name}: no usable beat grid")
        return

    bars = list(zip(downbeats, downbeats[1:]))
    labels = [bar_label(spans, a, b) for a, b in bars]

    want = parse_truth(truth)
    cycle = len(want)

    def rotated_score(rot: int) -> tuple[int, int]:
        root_ok = full_ok = 0
        for i, got in enumerate(labels):
            acceptable = want[(i + rot) % cycle]
            if got is not None and any(got[0] == w[0] for w in acceptable):
                root_ok += 1
                if any(got == w for w in acceptable):
                    full_ok += 1
        return root_ok, full_ok

    root_ok, full_ok = max((rotated_score(r) for r in range(cycle)),
                           key=lambda t: t[0])
    nc_time = sum(s["endSeconds"] - s["startSeconds"]
                  for s in spans if chord_of(s) is None)
    duration = doc.get("durationSeconds", 1.0)

    n = len(labels)
    print(f"  {mp3.name}: bars={n}  root {root_ok}/{n} ({100 * root_ok / n:.1f}%)"
          f"  root+quality {full_ok}/{n} ({100 * full_ok / n:.1f}%)"
          f"  N.C. {100 * nc_time / duration:.1f}% of {duration:.0f}s")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"build first: mvn -B -DskipTests package   (missing {jar})")

    print("samples with known ground truth:")
    missing = []
    for name, truth in BENCHMARKS.items():
        mp3 = REPO / "samples" / name
        if mp3.exists():
            score(mp3, jar, truth)
        else:
            missing.append(name)
    for name in missing:
        print(f"  {name}: not present (local-only; see samples/list.txt to fetch)")


if __name__ == "__main__":
    main()
