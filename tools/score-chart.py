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
*higher* on four of the five benchmarks there were then, because the recording's
own downbeat sequence wandered and one constant bar length tracked the music
better over
twelve minutes than the tracker's accumulated phase did.

#196 removed the wander, and the columns swapped: the chart column went *lower*
on four of those five, because the tracker's phase was the better of the two and
the constant bar length was what was left drifting -- it was spaced at the median
tracked interval where the grid ran at a rate half a percent from it. #200 has
since replaced that statistic with a rate, which closed most of the gap again
without closing it entirely, since what remains of it is the chart's phase
(#233) and the recording's own unevenness (#187). Both readings had the same
cause under them, seen from opposite sides, which is the reason to state the
mechanism here rather than a rule of thumb about which column wins. A maintainer
who runs both chord harnesses and finds them disagreeing is looking at the bar
axes, not at a bug in either.

Both columns are reported per benchmark:

  chords/bar   printed chords divided by printed bars. #212's metric.
  root, root+quality
               per bar, taking the bar's dominant printed chord -- the one
               filling most of it, which is what a reader takes from the bar --
               against the known cycle at its best rotation. The same rule as
               score-samples.py applies to the model, over different bars: not
               quite the same, in fact, since this totals equal symbols across
               a bar where that takes the single longest span.

               **That difference is harmless and stays harmless**: applied to
               every bar of every chart, totals and single-longest-span gave the
               same label on all 1134 bars of the seven benchmarks there were
               when it was measured. What
               is not harmless is a step *inside* the rule, and a previous
               version of this paragraph reassured the reader against it by
               accident -- "the bars are what make the columns differ".

               `ChartLayout.atHarmonicRhythm` writes an evenly split 4/4 bar as
               exactly 2+2 quarters, so on such a bar "the chord filling most of
               it" names no chord, and the bar is divided equally between the
               two (`bar_credit`). That case is not an edge: nearly every bar
               these charts print as two chords is an exact 2+2, so before #242
               the whole of the rule for them was that the earlier cell won.

               Which cell is earlier is not evidence, and it does not average
               out. At a bar rate the recording holds, the bar lines keep a
               fixed phase, so a recurring mid-bar change lands in the same
               place in every bar and the tie-break decides all of them the same
               way -- which is how a column meant to measure bar placement came
               to move with the bar rate in whichever direction that phase
               happened to point.
  split        bars no chord dominates, whose credit was divided. Reported so
               that a half is never read as a bar the chart got right.
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
                 chart     against the steady tracked rate, which is what
                           `Score.estimatedTempo()` spaces the chart's bars at.
                           Not zero, because one constant bar length drifts
                           against a recording that does not hold one (#187).
                           So this column is not a fact about how fast the
                           harmony moves; it is the share of gaps that drift has
                           pushed under one counted beat. Which is a tally, not
                           a scale: the gaps are whole multiples of the tracked
                           interval, so the threshold sits on a mode, and a cell
                           moves by a whole cohort or not at all rather than in
                           proportion to the drift. See
                           `ChartLayout.atHarmonicRhythm`.

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
# copied: two spellings of one truth is how two harnesses come to disagree
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

    The chart's counted beat is the *steady* rate of the tracked beats, and that
    is exact rather than approximate. `ChartLayout` spaces its bars at
    `60 / Score.estimatedTempo()` quarter notes; with no `--tempo` supplied that
    accessor returns `BeatGrid.steadyTempo`, which is
    `steadyPulseRate * beatUnitQuarters` -- so one counted beat comes to exactly
    the mean of the intervals within a fifth of the median, whatever the meter.

    **`STEADY_BAND` below is a copy of `BeatGrid`'s and nothing checks it.** It
    is reproduced because the only precise statement of the chart's axis lives in
    `BeatGrid`, and nothing the tool writes carries it: the chart header is
    `%.0f`, which round 3 of review on #212 already measured as too lossy to
    derive this from. A change to the band in `BeatGrid` has to be made here too,
    and #238 is open for removing the copy.

    An earlier version read the tempo back out of the printed chart header
    instead, on the reasoning that the header is the axis the bars were drawn
    on. Round 2 of review pointed out that this is inverted: the header is
    written with `%.0f`, so it is a *lossy second derivation* of the axis. It
    mattered. Chord gaps are whole numbers of tracked beat intervals, so a
    one-beat gap sits exactly on the boundary this counts against, and a
    fraction of a percent of tempo moves a whole cohort of them across at once
    -- on `gmajorblues.mp3` it moved the answer from 32.9% to 24.4%, both
    measured before #196 changed that recording's beat grid. The same
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
    median = (intervals[middle] if len(intervals) % 2
              else (intervals[middle - 1] + intervals[middle]) / 2.0)
    # BeatGrid.steadyPulseRate, in the same order it does it: the mean of the
    # intervals within STEADY_BAND of the median, falling back to the median when
    # the band is empty -- which an even count of wildly unequal intervals can do.
    STEADY_BAND = 0.2
    steady = [d for d in intervals
              if median * (1 - STEADY_BAND) <= d <= median * (1 + STEADY_BAND)]
    counted = sum(steady) / len(steady) if steady else median

    # The derivation above is only `estimatedTempo()`'s answer while it takes
    # its beat-grid branch, so the branch is checked rather than assumed --
    # against its own conditions, which `score.json` carries in full.
    #
    # An earlier version checked it by rounding the derived tempo and comparing
    # with the printed header. Round 3 of review measured that the band such a
    # check accepts is half a BPM wide, and that the reported figure ranges over
    # 24.4% to 36.1% inside it on `gmajorblues.mp3` -- both taken before #196
    # changed that recording's beat grid, like the pair in the docstring above,
    # and both still wider than the error the check was added to catch. A check whose resolution is the size of the bug
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
                 f"other than the tracked rate; this measure does not model that.")
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


def shares_of(bars: list) -> list[dict]:
    """Each bar's credit, divided between the chords printed in it.

    The chord filling most of the bar takes it, which is what a reader takes
    from the bar; a bar the layout splits evenly is halved between the two,
    which is `bar_credit`'s rule and #242.
    """
    shares = []
    for bar in bars:
        held: dict[tuple[int, str] | None, float] = {}
        for chord, length in bar:
            held[chord] = held.get(chord, 0.0) + length
        shares.append(_samples.bar_credit(held))
    return shares


def score(name: str, lilypond: str, truth: str,
          short: tuple[float, float] | None) -> None:
    bars = bars_of(lilypond)
    printed = sum(len(bar) for bar in bars)
    shares = shares_of(bars)

    root_ok, full_ok = _samples.accuracy(shares, parse_truth(truth))
    n = max(len(bars), 1)
    # How many bars no chord dominates is reported beside the columns, so a
    # split bar's half is never read as a bar the chart got right.
    split = sum(1 for share in shares if len(share) > 1)
    print(f"  {name}: bars={len(bars)}  chords/bar {printed / n:.2f}"
          f"  root {root_ok:.1f}/{n} ({100 * root_ok / n:.1f}%)"
          f"  root+quality {full_ok:.1f}/{n} ({100 * full_ok / n:.1f}%)"
          f"  split {split}"
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
