#!/usr/bin/env python3
"""Measures each benchmark's tempo from the audio, and scores the beat grid's
statistics against it.

**This is the axis #200 was decided on, and its whole point is that it does not
go through the beat tracker.** PR #207 was closed after ten rounds because its
supporting measurement scored agreement with `BeatTracker` and was read as
agreement with the music; anything choosing between statistics *of* the tracked
grid has to be scored against something the tracker did not produce.

So the reference here is a comb fit over an onset envelope taken straight from
the decoded audio: for each candidate period, sum the envelope at
`offset + k * period` across the whole recording at the best offset, and take
the period that scores highest. No `BeatTracker`, no chroma, no chord labels,
no `mw` code at all -- only `ffmpeg` to decode.

A second, unrelated axis agrees with it wherever it applies: the lag at which
the *estimated chord labels* repeat, which measures harmony rather than onsets.
That one cannot measure a vamp with no chord changes, which is why this exists.
The agreement is tabulated once, on #245; nothing committed re-runs that axis,
so the figures live there rather than here.

  usage:  python3 tools/measure-tempo.py [--sweep] [BENCHMARK ...]

The measurement itself needs only `ffmpeg`. The grid columns beside it, and
`--sweep` -- which re-derives `BeatGrid.STEADY_BAND`'s plateau -- analyse a
workspace per file and so need the shaded jar; without one the tool prints the
measured tempo alone and says what it skipped. **Four of the six measurable
benchmarks are local-only** (samples/list.txt has the fetch commands); the
sweep names every file it could not measure and says how many its plateau
actually covers, because a plateau over a silent subset is how a constant gets
certified by accident.

This is deliberately a second implementation of the steady-rate arithmetic --
a copy of `BeatGrid.steadyRateOf` and its band, after `score-chart.py`'s (#238)
-- because it exists to check the Java constant from outside. The cost of that
is that a change to `steadyRateOf` moves nothing here, and the javadoc keeps
looking verified; #238 carries that, for both copies.

Requires the `ffmpeg` binary on PATH. That is a dependency this tool has and
the product does not -- `mw` decodes through bundled natives -- and it is why
this lives in `tools/` rather than in a test.

**The peak is only meaningful because these are programmed backing tracks.** A
comb fit assumes one constant tempo for the whole recording; on a performance
that pushes and pulls it would report an average and its peak would be blunt.
`peak` is how far the winner stands above its strongest rival elsewhere in the
band, and it is a statement about the band's interior only.

**No column can vouch for the band itself, and none is printed.** This comb
scores a candidate by the mean envelope over its lattice at the best phase,
and the phases of a slower sub-lattice partition the true pulse's teeth, so
the best of them can never score below the pulse's own mean: halving the
tempo can only hold or raise the score, and an unconstrained comb is pulled
to the slowest strong submultiple above whatever floor it is given. A band an
octave out, in either direction, still lands in clean ratios with any check
derived from the same fit, so a wide-band column built on this comb cannot
fail, and this file deliberately does not print one. The octave comes from
outside the tool instead: each `SEARCH` band rests on documented ground truth
-- the tempo samples/list.txt states where it states one, otherwise a source
the band's own comment names -- and a new recording gets a band the same way
or gets no measurement.
"""

import argparse
import array
import json
import math
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
from importlib import import_module  # noqa: E402

BENCHMARKS = import_module("score-samples").BENCHMARKS

SAMPLE_RATE = 22050
WINDOW = 512
HOP = 128

# Where to look for each recording's pulse. The band is deliberately narrow --
# a comb fit will happily lock an octave out, and these bands are what stops it
# being asked to. Each one rests on documented ground truth -- the tempo
# samples/list.txt states, or a source named in a comment beside the band;
# that provenance, not any statistic here, is what says the band is honest
# (see the docstring). A benchmark without an entry is reported, never
# guessed.
SEARCH = {
    "blues-a-90bpm.mp3": (85.0, 95.0),
    "blues-e-90bpm.mp3": (85.0, 95.0),
    "blues-shuffle-a-106bpm.mp3": (100.0, 112.0),
    "fm7-vamp-110.mp3": (104.0, 116.0),
    "eb7-vamp-130.mp3": (124.0, 136.0),
    # The comb measures the half-note pulse, the recording's strongest even
    # periodicity. Since #333 that is also the pulse the tracker emits, so the
    # grid is scored against it directly; the quarter-note rate is twice this
    # (#322: ScoreBeats prints 149.889), and #231's history is on the issue.
    "bossa-cm.mp3": (70.0, 80.0),
}

# The factor between the measured pulse and the pulse the tracker emits. One
# everywhere today: bossa's four-thirds entry died with #333, and the empty
# dict stays so the mechanism -- and the reporting rule below that a file with
# a multiple is excluded from the plateau statistic -- outlives the corpus
# that currently needs neither.
TRACKED_MULTIPLE: dict[str, float] = {}

STEADY_BAND = 0.2  # a copy of BeatGrid's; see #238


def onset_envelope(mp3: Path) -> list[float]:
    """Half-wave-rectified first difference of short-time RMS."""
    try:
        decoded = subprocess.run(
            ["ffmpeg", "-v", "quiet", "-i", str(mp3), "-ac", "1",
             "-ar", str(SAMPLE_RATE), "-f", "s16le", "-"],
            capture_output=True)
    except FileNotFoundError:
        sys.exit("ffmpeg is not on PATH; this tool decodes with it")
    if decoded.returncode != 0:
        sys.exit(f"ffmpeg failed on {mp3.name}")
    samples = array.array("h")
    samples.frombytes(decoded.stdout[:len(decoded.stdout) // 2 * 2])
    frames = 1 + (len(samples) - WINDOW) // HOP
    if frames < 2:
        sys.exit(f"{mp3.name}: too short to measure")
    # Strided, because an envelope does not need every sample and the whole
    # point of this tool is that it stays readable.
    rms = [0.0] * frames
    for i in range(frames):
        start = i * HOP
        total = 0
        for j in range(start, start + WINDOW, 4):
            value = samples[j]
            total += value * value
        rms[i] = math.sqrt(total)
    envelope = [0.0] * frames
    for i in range(1, frames):
        rise = rms[i] - rms[i - 1]
        envelope[i] = rise if rise > 0 else 0.0
    peak = max(envelope) or 1.0
    return [e / peak for e in envelope]


def comb(envelope: list[float], low: float, high: float, step: float
         ) -> list[tuple[float, float]]:
    """(score, bpm) for each candidate tempo, best first."""
    frame_rate = SAMPLE_RATE / HOP
    scored = []
    bpm = low
    while bpm <= high:
        period = 60.0 / bpm * frame_rate
        beats = int((len(envelope) - 1) / period)
        best = 0.0
        for offset in range(0, int(period), 2):
            total = 0.0
            for k in range(beats):
                index = int(offset + k * period)
                if index < len(envelope):
                    total += envelope[index]
            best = max(best, total)
        scored.append((best / max(beats, 1), bpm))
        bpm += step
    scored.sort(reverse=True)
    return scored


def measured_tempo(mp3: Path) -> tuple[float, float]:
    """(bpm, peak over the strongest in-band rival)."""
    low, high = SEARCH[mp3.name]
    envelope = onset_envelope(mp3)
    coarse = comb(envelope, low, high, 0.05)
    best_score, best_bpm = coarse[0]
    rival = max((s for s, b in coarse if abs(b - best_bpm) > 0.15), default=0.0)
    return best_bpm, (best_score / rival if rival else float("inf"))


def grid_intervals(jar: Path, mp3: Path) -> list[float]:
    with tempfile.TemporaryDirectory() as tmp:
        workspace = Path(tmp) / "w.mwz"
        for args in (["init", str(mp3), "--workspace", str(workspace)],
                     ["analyze", str(workspace)]):
            result = subprocess.run(["java", "-jar", str(jar), *args],
                                    capture_output=True, text=True)
            if result.returncode != 0:
                sys.exit(f"mw {args[0]} failed on {mp3.name}:\n{result.stderr}")
        doc = json.loads((workspace / "score" / "score.json").read_text())
        beats = [b["seconds"] for b in doc["beatGrid"]["beats"]]
    return [beats[i] - beats[i - 1] for i in range(1, len(beats))]


def statistics_of(intervals: list[float], band: float = STEADY_BAND
                  ) -> tuple[float, float, float, int]:
    """(median, steady, plain mean) rates in pulses per minute, and kept count."""
    median = statistics.median(intervals)
    low, high = median * (1 - band), median * (1 + band)
    kept = [d for d in intervals if low <= d <= high]
    steady = sum(kept) / len(kept) if kept else median
    mean = sum(intervals) / len(intervals)
    return 60.0 / median, 60.0 / steady, 60.0 / mean, len(kept)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--sweep", action="store_true",
                        help="also re-derive BeatGrid.STEADY_BAND's plateau")
    parser.add_argument("benchmarks", nargs="*")
    args = parser.parse_args()

    wanted = args.benchmarks or list(BENCHMARKS)
    jar = Path(args.jar)
    have_jar = jar.exists()
    if not have_jar:
        print(f"{jar} is missing: printing the measurement alone, no grid"
              " columns and no sweep. Build with: mvn -B -DskipTests package")
    # SEARCH keys are checked against the scored corpus so a retired benchmark
    # cannot sit here looking fetchable; that has happened.
    for stale in sorted(set(SEARCH) - set(BENCHMARKS)):
        print(f"SEARCH names {stale}, which is not a scored benchmark;"
              " retire the entry or score the file")
    measurable = [n for n in BENCHMARKS if n in SEARCH]

    print("tempo measured from the audio"
          + (", and the grid statistics against it:" if have_jar else ":"))
    print(f"  {'benchmark':28s} {'measured':>9s} {'peak':>6s}"
          + (f" {'median':>9s} {'steady':>9s} {'mean':>9s}" if have_jar else ""))
    grids = {}
    truths = {}
    modelled = []
    absent = []
    for name in wanted:
        if name not in SEARCH:
            print(f"  {name}: no search band recorded; add one to SEARCH")
            continue
        row = BENCHMARKS.get(name)
        if row is None:
            # Only reachable in the state the stale check above reports: a band
            # whose benchmark was retired. Nothing then says which corpus to
            # look in, and guessing one would report a file as absent from a
            # directory it was never in.
            print(f"  {name}: no scored benchmark, so no corpus to look in")
            continue
        mp3 = REPO / row[0] / name
        if not mp3.exists():
            absent.append(name)
            print(f"  {name}: not present (local-only; see {row[0]}/list.txt)")
            continue
        tempo, sharpness = measured_tempo(mp3)
        if not have_jar:
            print(f"  {name:28s} {tempo:9.3f} {sharpness:5.1f}x")
            continue
        intervals = grid_intervals(jar, mp3)
        multiple = TRACKED_MULTIPLE.get(name)
        truth = tempo * (1.0 if multiple is None else multiple)
        median, steady, mean, kept = statistics_of(intervals)

        def err(value: float) -> str:
            return f"{100 * (value - truth) / truth:+8.3f}%"

        note = ""
        if multiple is not None:
            # A modelled reference, not a measured one: report it, keep it out
            # of the plateau statistic below rather than let its residual
            # dominate every band.
            modelled.append(name)
            note = f"  (grid scored against {multiple:.4g}x the measurement)"
        else:
            grids[name] = intervals
            truths[name] = truth
        print(f"  {name:28s} {tempo:9.3f} {sharpness:5.1f}x "
              f"{err(median)} {err(steady)} {err(mean)}{note}")

    if not args.sweep:
        return
    if not grids:
        print("\nsweep skipped: no benchmark was both measured and analysed"
              + ("" if have_jar else " (the jar is missing)"))
        return
    print(f"\nSTEADY_BAND plateau over {len(grids)} of {len(measurable)}"
          f" measurable benchmarks"
          + (f" (absent: {', '.join(absent)})" if absent else "")
          + (f" (modelled, excluded: {', '.join(modelled)})" if modelled else "")
          + ", swept at 0.0025:")
    band, worst = 0.04, []
    while band <= 0.35 + 1e-9:
        cells = []
        for name, intervals in grids.items():
            _, steady, _, kept = statistics_of(intervals, band)
            cells.append((abs(steady - truths[name]) / truths[name] * 100,
                          name, kept, len(intervals)))
        worst.append((band, max(cells)))
        band = round(band + 0.0025, 6)
    # The cells BeatGrid's javadoc argues from, then the quarter-step overview.
    for b, (error, name, kept, total) in worst:
        edge = b in (0.04, 0.0425, 0.05, 0.0525)
        step = abs(b / 0.025 - round(b / 0.025)) < 1e-9
        if edge or step:
            print(f"  band {b:6.4f}   worst {error:7.3f}%   {name}"
                  f"   kept {kept}/{total}")
    inside = [(e, b, n) for b, (e, n, _, _) in worst
              if 0.075 - 1e-9 <= b <= 0.30 + 1e-9]
    top = max(e for e, _, _ in inside)
    tied = [b for e, b, _ in inside if abs(e - top) < 1e-9]
    where = (f"{tied[0]}" if len(tied) == 1
             else f"[{tied[0]}, {tied[-1]}]")
    name = next(n for e, b, n in inside if abs(e - top) < 1e-9)
    print(f"  worst cell inside [0.075, 0.30]: {top:.3f}% at {where} ({name})")


if __name__ == "__main__":
    main()
