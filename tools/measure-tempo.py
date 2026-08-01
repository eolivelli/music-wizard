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
`offset + k * period` across the whole recording at the best offset, and take the
period that scores highest. No `BeatTracker`, no chroma, no chord labels, no
`mw` code at all -- only `ffmpeg` to decode.

A second, unrelated axis agrees with it wherever it applies: the lag at which
the *estimated chord labels* repeat, which measures harmony rather than onsets.
That one cannot measure a vamp with no chord changes, which is why this exists.
Where both apply they agree to three decimals -- 106.007/106.000,
105.002/105.000, 90.000/90.000, 74.941/74.950.

  usage:  python3 tools/measure-tempo.py [--sweep] [BENCHMARK ...]

`--sweep` additionally re-derives `BeatGrid.STEADY_BAND`'s plateau, which needs
an analysed workspace per file and so needs the shaded jar.

Requires the `ffmpeg` binary on PATH. That is a dependency this tool has and the
product does not -- `mw` decodes through bundled natives -- and it is why this
lives in `tools/` rather than in a test.

**The peak is only meaningful because these are programmed backing tracks.** A
comb fit assumes one constant tempo for the whole recording; on a performance
that pushes and pulls it would report an average and its peak would be blunt.
Every peak below is 3x to 9x its nearest rival, which is what says the assumption
holds *for these files* -- check that ratio before trusting the figure on a new
one.
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

# Where to look for each recording's pulse, and how many beats fill one cycle of
# the progression score-samples.py knows. The band is deliberately wide enough to
# contain the half and double of nothing -- a comb fit will happily lock an
# octave out, and these bands are what stops it being asked to.
SEARCH = {
    "gmajorblues.mp3": (100.0, 112.0),
    "blues-a-90bpm.mp3": (85.0, 95.0),
    "blues-e-90bpm.mp3": (85.0, 95.0),
    "blues-shuffle-a-106bpm.mp3": (100.0, 112.0),
    "fm7-vamp-110.mp3": (104.0, 116.0),
    "eb7-vamp-130.mp3": (124.0, 136.0),
    # Tracked at four thirds of this, which is #231 rather than anything here.
    "bossa-cm.mp3": (70.0, 80.0),
}

# The factor between the music's beat and the pulse the tracker actually emits.
# One everywhere except where #231 applies, and named rather than folded into
# SEARCH so that the two facts stay separable.
TRACKED_MULTIPLE = {"bossa-cm.mp3": 4.0 / 3.0}

STEADY_BAND = 0.2  # a copy of BeatGrid's; see #238


def onset_envelope(mp3: Path) -> list[float]:
    """Half-wave-rectified first difference of short-time RMS."""
    decoded = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-i", str(mp3), "-ac", "1",
         "-ar", str(SAMPLE_RATE), "-f", "s16le", "-"],
        capture_output=True)
    if decoded.returncode != 0:
        sys.exit(f"ffmpeg failed on {mp3.name}; is it installed?")
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
    """(bpm, how many times the peak beats its nearest rival)."""
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
                  ) -> tuple[float, float, float]:
    """(median, steady, plain mean) rates, in pulses per minute."""
    median = statistics.median(intervals)
    low, high = median * (1 - band), median * (1 + band)
    kept = [d for d in intervals if low <= d <= high]
    steady = sum(kept) / len(kept) if kept else median
    mean = sum(intervals) / len(intervals)
    return 60.0 / median, 60.0 / steady, 60.0 / mean


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--sweep", action="store_true",
                        help="also re-derive BeatGrid.STEADY_BAND's plateau")
    parser.add_argument("benchmarks", nargs="*")
    args = parser.parse_args()

    wanted = args.benchmarks or list(BENCHMARKS)
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"build first: mvn -B -DskipTests package   (missing {jar})")

    print("tempo measured from the audio, and the grid statistics against it:")
    print(f"  {'benchmark':28s} {'measured':>9s} {'peak':>6s} "
          f"{'median':>9s} {'steady':>9s} {'mean':>9s}")
    grids = {}
    truths = {}
    for name in wanted:
        mp3 = REPO / "samples" / name
        if not mp3.exists():
            print(f"  {name}: not present (local-only; see samples/list.txt)")
            continue
        if name not in SEARCH:
            print(f"  {name}: no search band recorded; add one to SEARCH")
            continue
        tempo, sharpness = measured_tempo(mp3)
        intervals = grid_intervals(jar, mp3)
        grids[name] = intervals
        # What the tracker is following, which is the music except under #231.
        truth = tempo * TRACKED_MULTIPLE.get(name, 1.0)
        truths[name] = truth
        median, steady, mean = statistics_of(intervals)

        def err(value: float) -> str:
            return f"{100 * (value - truth) / truth:+8.3f}%"

        note = "" if name not in TRACKED_MULTIPLE else \
            f"  (tracker follows {TRACKED_MULTIPLE[name]:.4g}x this: #231)"
        print(f"  {name:28s} {tempo:9.3f} {sharpness:5.1f}x "
              f"{err(median)} {err(steady)} {err(mean)}{note}")

    if not args.sweep or not grids:
        return
    print("\nSTEADY_BAND plateau, swept at 0.0025 (BeatGrid's own step):")
    band, worst = 0.05, []
    while band <= 0.35001:
        cells = []
        for name, intervals in grids.items():
            _, steady, _ = statistics_of(intervals, band)
            cells.append((abs(steady - truths[name]) / truths[name] * 100, name))
        worst.append((band, max(cells)))
        band = round(band + 0.0025, 6)
    for b, (error, name) in worst:
        if abs(b / 0.025 - round(b / 0.025)) < 1e-9:
            print(f"  band {b:6.4f}   worst {error:7.3f}%   {name}")
    inside = [(e, b, n) for b, (e, n) in worst if 0.075 - 1e-9 <= b <= 0.30 + 1e-9]
    error, b, name = max(inside)
    print(f"  worst cell inside [0.075, 0.30]: {error:.3f}% at {b} ({name})")


if __name__ == "__main__":
    main()
