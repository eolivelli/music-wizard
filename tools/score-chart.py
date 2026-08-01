#!/usr/bin/env python3
"""Scores the chord chart the tool actually prints, not the model behind it.

`tools/score-samples.py` reads `score/score.json` and asks whether the
*estimate* names the right chord in each bar. This reads `out/chords.ly` and
asks the question a reader of the page asks: how many chords are printed in a
bar, and is the one a reader takes away from that bar the right one.

The two differ in a way worth keeping visible, and it is not the way it looks.
On the audio path the chart's bar lines are its own -- one downbeat for phase
and `Score.estimatedTempo()` for rate -- so wherever those two disagree with
the grid they walk away from the recording's downbeats (#187, #200, #233).
That holds of the seconds route only; a progression carrying beats is laid out
on the beat axis instead, and `short_changes` below refuses to measure one.

**Neither score bounds the other, because they are scored over different
bars, and which of them is higher has already changed sign once.** An early
version of this paragraph said a chart score can never beat a model score and
that the gap is the drift; that was wrong, and before #196 the chart column was
*higher* on four of the five benchmarks, because the recording's own downbeat
sequence wandered and one constant bar length tracked the music better over
twelve minutes than the tracker's accumulated phase did.

#196 removed the wander, and the columns swapped: the chart column is now
*lower* on four of the five, because the tracker's phase is the better of the
two and the constant bar length is what is left drifting -- it is spaced at the
median tracked interval where the grid ran at a rate half a percent from it
(#200). Both readings had the same cause under them, seen from opposite sides,
which is the reason to state the mechanism here rather than a rule of thumb
about which column wins. A maintainer who runs both harnesses and finds them
disagreeing is looking at the bar axes, not at a bug in either.

Both columns are reported per benchmark:

  chords/bar   printed chords divided by printed bars. #212's metric.
  root, root+quality
               per bar, taking the bar's dominant printed chord -- the one
               filling most of it, which is what a reader takes from the bar --
               against the known cycle at its best rotation. The same rule as
               score-samples.py applies to the model, over different bars: not
               quite the same, in fact, since this totals equal symbols across
               a bar where that takes the single longest span. Measured on all
               five, the two rules give the same label everywhere; the bars are
               what make the columns differ.
  short
               the share of consecutive chord changes that are closer together
               than one counted beat, on each of the two axes there are. This is
               reported because the chart's reduction rule rests on it: see
               `ChartLayout.atHarmonicRhythm`, which argues that "faster than the
               counted beat" cannot separate a wrong `--tempo` from ordinary
               chatter. The two axes disagree, and the disagreement is the point:

                 tracked   against the beat grid the estimator itself used.
                           Zero by construction -- `ChordEstimator` takes both
                           boundaries of every span from the tracked beat times.
                 chart     against the median tracked interval, which is what
                           `Score.estimatedTempo()` spaces the chart's bars at.
                           Not zero, because one constant bar length drifts
                           against a recording it is not quite the rate of
                           (#187, #200). So this column is not a fact about how
                           fast the harmony moves; it is the size of that drift.

Usage:  python3 tools/score-chart.py [--jar mw-cli/target/mw.jar]
"""

import argparse
import json
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


def short_changes(workspace: Path) -> tuple[float, float] | None:
    """Changes closer than a counted beat, as a share, on each of the two axes.

    The chart's counted beat is the *median* interval between tracked beats, and
    that is exact rather than approximate. `ChartLayout` spaces its bars at
    `60 / Score.estimatedTempo()` quarter notes; with no `--tempo` supplied that
    accessor returns `BeatGrid.medianTempo`, which is
    `60 / median-interval * beatUnitQuarters` -- so one counted beat comes to
    exactly the median interval, whatever the meter.

    An earlier version read the tempo back out of the printed chart header
    instead, on the reasoning that the header is the axis the bars were drawn
    on. Round 2 of review pointed out that this is inverted: the header is
    written with `%.0f`, so it is a *lossy second derivation* of the axis. It
    mattered. Chord gaps are whole numbers of tracked beat intervals, so a
    one-beat gap sits exactly on the boundary this counts against, and a
    fraction of a percent of tempo moves a whole cohort of them across at once
    -- on `gmajorblues.mp3` it moved the answer from 32.9% to 24.4%. The same
    rounding then survived one round as the *check* on the derivation, which
    round 3 found accepts anything within half a BPM: a band the figure varies
    over by more than the error it was guarding. Both are gone.

    Returns None where the model cannot be measured this way, rather than a
    figure that would be mistaken for a measured zero.
    """
    doc = json.loads((workspace / "score" / "score.json").read_text())
    starts = [c["startSeconds"] for c in doc.get("chords", {}).get("chords", [])]
    beats = [b["seconds"] for b in doc.get("beatGrid", {}).get("beats", [])]
    if len(starts) < 2 or len(beats) < 2:
        return None

    intervals = sorted(beats[i] - beats[i - 1] for i in range(1, len(beats)))
    middle = len(intervals) // 2
    counted = (intervals[middle] if len(intervals) % 2
               else (intervals[middle - 1] + intervals[middle]) / 2.0)

    # The derivation above is only `estimatedTempo()`'s answer while it takes
    # its beat-grid branch, so the branch is checked rather than assumed --
    # against its own conditions, which `score.json` carries in full.
    #
    # An earlier version checked it by rounding the derived tempo and comparing
    # with the printed header. Round 3 of review measured that the band such a
    # check accepts is half a BPM wide, and that the reported figure ranges over
    # 24.4% to 36.1% inside it on `gmajorblues.mp3` -- wider than the error the
    # check was added to catch. A check whose resolution is the size of the bug
    # is not a check. This is the same defect one layer down: the rounded header
    # is the layer the problem was noticed at, and `estimatedTempo()`'s
    # conditions are the layer it lives at.
    # And before any of that: whether `estimatedTempo()` is consulted at all.
    # `ChartLayout.unreduced` dispatches on `ChordProgression.isQuantized()`, and
    # its beat-axis branch takes bar lengths from the `TempoMap` without ever
    # calling `quarterNoteSeconds`. Round 4 of review found this missing, and it
    # is the branch most likely to move: wiring `Quantizer` into the audio path
    # is #212's other candidate and `ChartLayout`'s javadoc calls it a live
    # option, and it would flip this predicate without touching any provenance.
    chords = doc.get("chords", {}).get("chords", [])
    if chords and all(c.get("startBeat") is not None for c in chords):
        sys.exit(f"{workspace.name}: the chords are quantized, so the chart is laid out on "
                 f"the beat axis rather than at estimatedTempo(); this measure does not "
                 f"model that.")

    segments = doc.get("tempoMap", {}).get("segments", [])
    provenances = {s.get("provenance", "UNKNOWN") for s in segments}
    if "SUPPLIED" in provenances:
        sys.exit(f"{workspace.name}: a supplied --tempo makes the chart's beat something "
                 f"other than the tracked median; this measure does not model that.")
    if provenances <= {"UNKNOWN"}:
        sys.exit(f"{workspace.name}: the tempo map records no provenance, so "
                 f"estimatedTempo() may prefer a stated constant over the beat grid; "
                 f"this measure does not model that.")

    def spans_a_beat(a: float, b: float) -> bool:
        return any(a < t <= b for t in beats)

    gaps = [(starts[i] - starts[i - 1]) for i in range(1, len(starts))]
    on_chart = sum(1 for g in gaps if g < counted)
    on_tracked = sum(1 for i in range(len(gaps))
                     if not spans_a_beat(starts[i], starts[i + 1]))
    return 100.0 * on_tracked / len(gaps), 100.0 * on_chart / len(gaps)


def score(name: str, lilypond: str, truth: str,
          short: tuple[float, float] | None) -> None:
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
          f"  root+quality {full_ok}/{n} ({100 * full_ok / n:.1f}%)"
          f"  short: " + ("not measurable"
                            if short is None
                            else f"tracked {short[0]:.1f}%, chart {short[1]:.1f}%"))


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
            score(name, lilypond, truth, short_changes(workspace))
            if args.cycles:
                text = (workspace / "out" / "chords.txt").read_text().splitlines()
                for line in [ln for ln in text if ln.startswith("|")][:args.cycles]:
                    print("      " + line)
    for name in missing:
        print(f"  {name}: not present (local-only; see samples/list.txt to fetch)")


if __name__ == "__main__":
    main()
