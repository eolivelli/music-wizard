#!/usr/bin/env python3
"""Measures what the mix-and-separate path costs the melody stage.

**The point is that the ground truth does not move.** A song gives no way to
tell a melody stage's error from a separator's, because the only reference is
the mix itself. So this takes recordings whose melody is already annotated note
by note — vocadito's solo voices — scores them, then mixes those same voices
with a band, puts the mix back through `mw separate`, and scores the separated
stem against the same annotations.

**Two things change between the outer rows, not one.** The middle row,
`spleeter only`, puts the clean voice through the separator with no bed, and it
costs almost nothing — but that is a null control: with no band present there
is nothing for the mask to remove, so it says what the separator does to a
voice *by itself* and nothing about what it does to a voice with a band on top
of it.

So the gap between the outer rows is not attributable by this tool. It holds
band the mask failed to remove and voice the mask removed along with the band,
in unknown proportion, and those are different defects with different fixes.
Projecting the stems back onto the clean voice says the second is large at the
ratios these clips present, so do not read this gap as leftover band. #503
carries that measurement.

**The vocal-to-band ratio is stated, not inherited (#505).** The bed is scaled
per clip so that the voice sits a named number of decibels above it, and the
comparison is measured where the singing actually is: the voice's level is its
RMS over the annotated sung spans, not over the clip, so a clip that opens with
four seconds of room tone is not thereby called quiet. Before this the bed took
a fixed gain and the voice whatever level its clip was recorded at — and
vocadito spans some 20 dB of level, so "the bed under the voice" was in fact
well above it on several clips, which were exactly the ones that lost
everything. The published figure was the average of two regimes and moved with
nothing but which singers had been recorded quietly.

**So ask for the curve, not the number.** `--ratio` takes several values and
the useful output is what the melody stage does across them: where it falls off
is a slope, and a single figure is one point on it chosen by accident. The
clean and separator-only rows do not depend on the ratio and are measured once.

**Read the voicing column beside the pitch column or not at all.** Voicing
recall asks what share of the annotated singing the estimate also called
sounding, and it does not ask whether what was tracked was the singer. Where
the band is loud the tracker follows the band, which is voiced, so that column
holds up while pitch accuracy collapses — and it can even fall as the voice
gets *quieter still*, because at some point the tracker stops finding anything
periodic at all. It is a measure of whether something was heard, not of whether
the right thing was.

The bed defaults to a committed synthetic package, so the measurement is
reproducible from the repository by anyone who has fetched vocadito
(`uncommitted/list.txt`), and `--bed` takes any other recording. Two beds are
still only comparable at the same ratio, which is now something this tool can
give you.

Nothing here is baselined. It answers a question that is asked when a separator
or a melody stage changes (#503), not on every premerge — and its cost is one
Spleeter run per clip plus one per clip per ratio.

Usage:  python3 tools/measure-separation-cost.py [--clips 10]
                                                 [--ratio 0 --ratio -6 ...]
                                                 [--bed FILE]
                                                 [--jar mw-cli/target/mw.jar]
"""

import argparse
import array
import math
import statistics
import subprocess
import sys
import tempfile
import wave
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
melody = import_module("score-melody")

REPO = Path(__file__).resolve().parent.parent

#: The default interferer: a full-band package with drums, bass and comping.
#: Committed, so the run reproduces from the repository. Comparing it against
#: another bed is not something this tool can do yet; see #505.
DEFAULT_BED = REPO / "synthetic_samples" / "pop-axis-g-116.mp3"

#: How far the voice sits above the bed, in decibels, when nothing is asked for.
#: Zero rather than a positive number because it is the least loaded default: it
#: says the two are equally loud where the singing is, and leaves the direction
#: of any claim to the caller.
DEFAULT_RATIO_DB = 0.0

#: Where in the bed to start, so every clip hears the same stretch of it and no
#: clip hears the arrangement's opening bar.
BED_OFFSET_SECONDS = 30


def sung_rms_dbfs(voice: Path, notes: list) -> float:
    """The voice's RMS over its annotated notes, in dBFS.

    Over the notes rather than over the clip, because the level that decides
    whether a separator can find a singer is the level of the singing: a clip
    that opens with four seconds of room tone is not quiet, and averaging that
    silence in would make this ask for a bed quieter than the comparison
    intends. Read with the standard library so this stays as dependency-free as
    its neighbours.
    """
    with wave.open(str(voice)) as source:
        if source.getsampwidth() != 2:
            sys.exit(f"{voice.name}: expected 16-bit audio, got "
                     f"{8 * source.getsampwidth()}-bit")
        channels = source.getnchannels()
        rate = source.getframerate()
        samples = array.array("h", source.readframes(source.getnframes()))
    if sys.byteorder == "big":
        samples.byteswap()

    total = 0.0
    counted = 0
    for onset, offset, _pitch in notes:
        first = int(onset * rate) * channels
        last = min(int(offset * rate) * channels, len(samples))
        for index in range(first, last):
            value = samples[index]
            total += value * value
            counted += 1
    if not counted:
        sys.exit(f"{voice.name}: its annotation covers none of the audio")
    return 20 * math.log10(math.sqrt(total / counted) / 32768.0)


def segment_rms_dbfs(bed: Path, start: float, end: float) -> float:
    """The bed's RMS over the stretch that will be mixed in, in dBFS.

    ffmpeg rather than the standard library because a bed may be an mp3, and
    `volumedetect`'s mean_volume is exactly this quantity.
    """
    done = subprocess.run(
        ["ffmpeg", "-hide_banner", "-ss", f"{start}", "-to", f"{end}",
         "-i", str(bed), "-af", "volumedetect", "-f", "null", "-"],
        capture_output=True, text=True)
    for line in done.stderr.splitlines():
        if "mean_volume:" in line:
            return float(line.split("mean_volume:")[1].split("dB")[0])
    sys.exit(f"could not measure {bed.name}:\n{done.stderr}")


def bed_gain(voice_dbfs: float, bed_dbfs: float, ratio_db: float) -> float:
    """The linear gain that puts the voice `ratio_db` above the bed.

    Its own function so the arithmetic can be asserted rather than read: this
    is the whole of #505, and the version it replaces was a constant applied to
    one side of a comparison whose other side varied by 20 dB.
    """
    return 10 ** ((voice_dbfs - ratio_db - bed_dbfs) / 20)


def duration(path: Path) -> float:
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)], capture_output=True, text=True)
    if probe.returncode != 0:
        sys.exit(f"ffprobe failed on {path}:\n{probe.stderr}")
    return float(probe.stdout.strip())


def mix(voice: Path, bed: Path, gain: float, into: Path) -> None:
    """The voice at its own level with the bed scaled to the asked-for ratio."""
    end = BED_OFFSET_SECONDS + duration(voice)
    # Refused rather than mixed against the silence past the bed's end: amix
    # would pad it, the tail of the clip would be a solo voice again, and the
    # row would report a cost lower than the one it claims to measure. Every
    # vocadito clip fits inside this bed today.
    if end > duration(bed):
        sys.exit(f"{voice.name} is longer than the bed has left after "
                 f"{BED_OFFSET_SECONDS}s; choose a longer bed or a smaller offset")
    done = subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(voice), "-i", str(bed),
        "-filter_complex",
        f"[1:a]atrim={BED_OFFSET_SECONDS}:{end},asetpts=PTS-STARTPTS,"
        f"volume={gain}[bed];[0:a][bed]amix=inputs=2:duration=first:normalize=0[out]",
        "-map", "[out]", "-ar", "44100", str(into)], capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit(f"ffmpeg failed mixing {voice.name}:\n{done.stderr}")


def separate(jar: Path, mixed: Path, workspace: Path) -> Path:
    for args in (["init", str(mixed), "--workspace", str(workspace)],
                 ["separate", str(workspace)]):
        done = subprocess.run(["java", "-jar", str(jar), *args],
                              capture_output=True, text=True)
        if done.returncode != 0:
            sys.exit(f"mw {args[0]} failed on {mixed.name}:\n{done.stdout}{done.stderr}")
    stem = workspace / "out" / "vocals.wav"
    if not stem.exists():
        sys.exit(f"no vocal stem written for {mixed.name}")
    return stem


def score(jar: Path, audio: Path, reference: list) -> tuple[float, float, float]:
    notes = melody.estimated_notes(melody.analyze(jar, audio))
    if not notes:
        return 0.0, 0.0, 0.0
    pitch, voiced = melody.framewise(notes, reference)
    return melody.note_f1(notes, reference, melody.TOLERANCES[0]), pitch, voiced


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--clips", type=int, default=10,
                        help="how many vocadito clips to put through, from the first")
    parser.add_argument("--bed", default=str(DEFAULT_BED),
                        help="what to mix with the voice; quote it with any figure")
    parser.add_argument("--ratio", type=float, action="append", metavar="DB",
                        help="decibels the voice sits above the bed, measured over"
                             " the sung spans; repeat it for a sweep")
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"jar not found: {jar} (build with: mvn -DskipTests package)")
    bed = Path(args.bed)
    if not bed.exists():
        sys.exit(f"no bed to mix with: {bed}")
    ratios = args.ratio if args.ratio else [DEFAULT_RATIO_DB]

    print("What the mix-and-separate path costs the melody stage")
    print(f"(bed: {bed.name}; the voice sits the stated decibels above it,")
    print(" measured over each clip's own annotated sung spans)")

    # The clean and separator-only rows do not depend on the ratio, so they are
    # measured once and reused across the sweep. That is not only cheaper: it
    # means every point on the curve is compared against the same baseline
    # rather than against a re-run of it.
    clean_rows, alone_rows, levels, references = {}, {}, {}, {}
    with tempfile.TemporaryDirectory() as tmp:
        for clip in range(1, args.clips + 1):
            voice = melody.VOCADITO / "Audio" / f"vocadito_{clip}.wav"
            notes = melody.VOCADITO / "Annotations" / "Notes" / f"vocadito_{clip}_notesA1.csv"
            if not voice.exists() or not notes.exists():
                print(melody.missing_clip_line(clip))
                continue
            reference = melody.vocadito_notes(notes)
            references[clip] = reference
            levels[clip] = sung_rms_dbfs(voice, reference)
            clean_rows[clip] = score(jar, voice, reference)
            # A null control: the same voice through the same separator with
            # no band at all. It bounds what the separator does to a voice by
            # itself, which is nearly nothing -- and it cannot apportion the
            # column beside it, because with no band present there is nothing
            # for the mask to take the voice out along with.
            alone_rows[clip] = score(
                jar, separate(jar, voice, Path(tmp) / f"ws_alone_{clip}"), reference)

        if not clean_rows:
            return
        print(f"  voice level over the sung spans: "
              f"{min(levels.values()):.1f} to {max(levels.values()):.1f} dBFS "
              f"across {len(levels)} clips")
        print(f"  clean            note F1 {100 * statistics.mean(r[0] for r in clean_rows.values()):5.1f}%"
              f"   pitch {100 * statistics.mean(r[1] for r in clean_rows.values()):5.1f}%"
              f"   voicing {100 * statistics.mean(r[2] for r in clean_rows.values()):5.1f}%")
        print(f"  separator only   note F1 {100 * statistics.mean(r[0] for r in alone_rows.values()):5.1f}%"
              f"   pitch {100 * statistics.mean(r[1] for r in alone_rows.values()):5.1f}%"
              f"   voicing {100 * statistics.mean(r[2] for r in alone_rows.values()):5.1f}%")
        print("  through a band, by how far the voice sits above it:")

        for ratio in ratios:
            rows = []
            for clip in sorted(clean_rows):
                voice = melody.VOCADITO / "Audio" / f"vocadito_{clip}.wav"
                # The bed is scaled per clip, because the voice's level is a
                # property of that recording and the ratio is what is being held
                # fixed across them.
                window = (BED_OFFSET_SECONDS, BED_OFFSET_SECONDS + duration(voice))
                gain = bed_gain(levels[clip], segment_rms_dbfs(bed, *window), ratio)
                mixed = Path(tmp) / f"mix_{clip}_{ratio}.wav"
                mix(voice, bed, gain, mixed)
                stem = separate(jar, mixed, Path(tmp) / f"ws_{clip}_{ratio}")
                rows.append(score(jar, stem, references[clip]))
            print(f"   {ratio:+6.1f} dB      note F1 {100 * statistics.mean(r[0] for r in rows):5.1f}%"
                  f"   pitch {100 * statistics.mean(r[1] for r in rows):5.1f}%"
                  f"   voicing {100 * statistics.mean(r[2] for r in rows):5.1f}%")


if __name__ == "__main__":
    main()
