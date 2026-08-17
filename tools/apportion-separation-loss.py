#!/usr/bin/env python3
"""Splits what separation costs the melody into the two defects it is made of.

`tools/measure-separation-cost.py` shows the collapse — a voice scored clean,
then scored again after a band has been mixed under it and the mix separated —
and cannot say what the collapse is made of. Two defects live in that gap and
they want opposite fixes (#503): band the mask failed to remove, which gives
the tracker something else to follow, and voice the mask removed along with the
band, which leaves it nothing.

**This apportions it by taking the stem apart.** The measurement made the mix,
so it knows the band inside it exactly — `mix - voice`, sample for sample —
where the separator only ever saw their sum. `tools/SeparationSplit.java`
divides each time-frequency bin of the stem between the two sources in
proportion to the energy each has there, so the two parts add back to the stem
and nothing is discarded. Then each part is scored against the same
annotations:

  clean               the voice as recorded — the ceiling
  mix                 the mix as given, not separated at all — the floor the
                      separator has to beat to be worth running
  voice part          what the separator delivered of the voice, alone.
                      Everything between this and clean is the mask cutting
                      into the singing.
  clean + band part   the untouched voice with the leftover band added back.
                      Everything between this and clean is the tracker
                      following the band.
  stem                the two together — the row the other tool reports

`voice part` and `clean + band part` are what apportions the loss, and they are
read as two separate subtractions from `clean` rather than as a chain: the
defects are not additive in the score, because a tracker that has already lost
the voice cannot lose it twice.

**What the split assumes.** Where voice and band genuinely occupy one bin it
apportions by a rule, which is a choice and not a measurement — squared shares
by default, the same assumption an ideal ratio mask makes. `--share-exponent`
varies it, and a conclusion that moves with it is a conclusion about the rule
rather than about the separator. It is also why the parts are scored rather
than merely measured: what a stage does with a signal is a stronger statement
than how much energy is in it.

**Railed mixes are skipped, not averaged in (#518).** A clip recorded loud has
no headroom for the bed, and the ratios where the loss happens are exactly the
ones it cannot reach. The run names the clips it skipped and apportions the
rest, since an apportionment over the clips that fit is worth more than a mean
over all of them that includes distortion.

Nothing here is baselined: it answers a question asked when the separator
changes, and costs a separation and an analysis of every row, per clip and
ratio.

Usage:  python3 tools/apportion-separation-loss.py [--clips 10] [--ratio 0]
                                                   [--bed FILE] [--keep DIR]
                                                   [--share-exponent N]
                                                   [--jar mw-cli/target/mw.jar]
"""

import argparse
import shutil
import statistics
import subprocess
import sys
import tempfile
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
melody = import_module("score-melody")
cost = import_module("measure-separation-cost")

REPO = Path(__file__).resolve().parent.parent

SPLITTER = REPO / "tools" / "SeparationSplit.java"

#: The splitter's own default, restated so the sweep can say what it used.
SHARE_EXPONENT = 2.0

ROWS = ("clean", "mix", "voice part", "clean + band part", "stem")


def split(jar: Path, voice: Path, mixed: Path, stem: Path,
          into: Path, name: str, exponent: float) -> tuple[Path, Path, str]:
    """The stem's voice part and band part, and what the splitter reported."""
    voice_part = into / f"{name}-voice-part.wav"
    band_part = into / f"{name}-band-part.wav"
    done = subprocess.run(
        ["java", "-cp", str(jar), str(SPLITTER), str(voice), str(mixed),
         str(stem), str(voice_part), str(band_part), str(exponent)],
        capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit(f"splitting {name} failed:\n{done.stdout}{done.stderr}")
    return voice_part, band_part, done.stdout.strip().splitlines()[-1]


def channels(path: Path) -> int:
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
         "stream=channels", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True)
    if probe.returncode != 0:
        sys.exit(f"ffprobe failed on {path}:\n{probe.stderr}")
    return int(probe.stdout.strip())


def add(first: Path, second: Path, into: Path) -> Path:
    """Two mono signals summed, at float, so nothing clips on the way in.

    Mono is checked rather than assumed: amix negotiates a shared layout and
    inserts its own conversion, which would put one side in at a level this
    sum is not asking for -- the defect BED_TO_MONO exists for next door.
    """
    for path in (first, second):
        if channels(path) != 1:
            sys.exit(f"{path.name} is not mono; this sum would be scaled by the"
                     " layout conversion amix inserts")
    done = subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(first), "-i", str(second),
        "-filter_complex", "[0:a][1:a]amix=inputs=2:duration=first:normalize=0[out]",
        "-map", "[out]", "-c:a", "pcm_f32le", str(into)], capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit(f"ffmpeg failed adding {second.name} to {first.name}:\n{done.stderr}")
    return into


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--clips", type=int, default=10,
                        help="how many vocadito clips to put through, from the first")
    parser.add_argument("--bed", default=str(cost.DEFAULT_BED),
                        help="what to mix with the voice; quote it with any figure")
    parser.add_argument("--ratio", type=float, action="append", metavar="DB",
                        help="decibels the voice sits above the bed, measured over"
                             " the sung spans; repeat it for a sweep")
    parser.add_argument("--keep", metavar="DIR",
                        help="where to leave the split stems for listening")
    parser.add_argument("--share-exponent", type=float, default=SHARE_EXPONENT,
                        metavar="N",
                        help="how a bin both sources occupy is divided; a"
                             " conclusion that moves with it is about the rule")
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"jar not found: {jar} (build with: mvn -DskipTests package)")
    bed = Path(args.bed)
    if not bed.exists():
        sys.exit(f"no bed to mix with: {bed}")
    keep = Path(args.keep) if args.keep else None
    if keep:
        keep.mkdir(parents=True, exist_ok=True)
    ratios = args.ratio if args.ratio else [cost.DEFAULT_RATIO_DB]

    print("What separation costs the melody, apportioned between the two defects")
    print(f"(bed: {bed.name}; the voice sits the stated decibels above it,")
    print(" measured over each clip's own annotated sung spans;")
    print(f" a bin both sources occupy is divided by share exponent"
          f" {args.share_exponent:g})")

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        # The clean row does not depend on the ratio, so it is scored once and
        # every point on the sweep is compared against the same reading of it
        # rather than against a re-run.
        clean, levels, references = {}, {}, {}
        for clip in range(1, args.clips + 1):
            voice = melody.VOCADITO / "Audio" / f"vocadito_{clip}.wav"
            notes = (melody.VOCADITO / "Annotations" / "Notes"
                     / f"vocadito_{clip}_notesA1.csv")
            if not voice.exists() or not notes.exists():
                print(melody.missing_clip_line(clip))
                continue
            references[clip] = melody.vocadito_notes(notes)
            levels[clip] = cost.sung_rms_dbfs(voice, references[clip])
            clean[clip] = cost.score(jar, voice, references[clip])
        if not clean:
            return

        for ratio in ratios:
            scores = {row: [] for row in ROWS}
            skipped = []
            print(f"   {ratio:+.1f} dB, clip by clip:")
            for clip in sorted(clean):
                voice = melody.VOCADITO / "Audio" / f"vocadito_{clip}.wav"
                window = (cost.BED_OFFSET_SECONDS,
                          cost.BED_OFFSET_SECONDS + cost.duration(voice))
                gain = cost.bed_gain(
                    levels[clip], cost.segment_rms_dbfs(bed, *window), ratio)
                mixed = work / f"mix_{clip}.wav"
                cost.mix(voice, bed, gain, mixed)
                peak = cost.peak(mixed)
                if cost.railed(peak):
                    skipped.append((clip, peak))
                    print(f"     vocadito_{clip:<3d} voice {levels[clip]:6.1f} dBFS"
                          f"   mix peaked at {peak:+.1f} dBFS   {cost.REFUSED_LABEL}")
                    mixed.unlink(missing_ok=True)
                    continue

                stem = cost.separate(jar, mixed, work / f"ws_{clip}")
                name = f"vocadito_{clip}_{ratio:+.0f}dB"
                voice_part, band_part, reported = split(
                    jar, voice, mixed, stem, keep or work, name,
                    args.share_exponent)
                over_clean = add(voice, band_part,
                                 (keep or work) / f"{name}-clean-plus-band.wav")

                row = {"clean": clean[clip],
                       "mix": cost.score(jar, mixed, references[clip]),
                       "voice part": cost.score(jar, voice_part, references[clip]),
                       "clean + band part": cost.score(jar, over_clean, references[clip]),
                       "stem": cost.score(jar, stem, references[clip])}
                for key, value in row.items():
                    scores[key].append(value)
                print(f"     vocadito_{clip:<3d} voice {levels[clip]:6.1f} dBFS   {reported}")
                for key in ROWS:
                    print(f"       {key:<20s} note F1 {100 * row[key][0]:5.1f}%"
                          f"   pitch {100 * row[key][1]:5.1f}%"
                          f"   voicing {100 * row[key][2]:5.1f}%")
                shutil.rmtree(work / f"ws_{clip}", ignore_errors=True)
                mixed.unlink(missing_ok=True)
                if not keep:
                    # A sweep leaves several float wavs per clip per ratio
                    # behind otherwise, and the scores are the output.
                    for spent in (voice_part, band_part, over_clean):
                        spent.unlink(missing_ok=True)

            if not scores["clean"]:
                print(f"   {ratio:+6.1f} dB: every clip {cost.REFUSED_LABEL}; nothing to apportion")
                continue
            print(f"   {ratio:+6.1f} dB mean over {len(scores['clean'])} clips:")
            for key in ROWS:
                print(f"       {key:<20s} note F1 "
                      f"{100 * statistics.mean(r[0] for r in scores[key]):5.1f}%"
                      f"   pitch {100 * statistics.mean(r[1] for r in scores[key]):5.1f}%"
                      f"   voicing {100 * statistics.mean(r[2] for r in scores[key]):5.1f}%")
            if skipped:
                print(f"       {cost.REFUSED_LABEL}, and left out of the mean: "
                      + ", ".join(f"vocadito_{clip} ({peak:+.1f} dBFS)"
                                  for clip, peak in skipped))
    print("  voice-retention and band-rejection are the split's own energy figures:")
    print("  how far each part sits below the source it came from, in decibels.")


if __name__ == "__main__":
    main()
