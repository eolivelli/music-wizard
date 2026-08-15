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
a fixed gain and the voice whatever level its clip was recorded at, and the
clips differ by tens of decibels — so "the bed under the voice" was in fact
well above it on several of them, which were exactly the ones that lost
everything. The published figure was the average of two regimes and moved with
nothing but which singers had been recorded quietly. The run prints the range
it measured, so read that rather than a figure quoted here.

**`--ratio` takes several values, and the ones that ask for a loud bed cannot
be reported.** The bed is added to a voice at the level its clip was recorded
at, so the only headroom is whatever that clip has left, and the clips recorded
loud have almost none. The run marks those rows, names the clips with their
peaks and exits non-zero rather than averaging a spoiled row with clean ones;
where the boundary falls is a property of the clips and the bed, so read it off
the run rather than from a figure here. It is the wrong side that is lost — a
voice buried under a band is where the loss this tool measures actually happens
— so what #518 has to fix is the mixing, not the reporting. The clean and
separator-only rows do not depend on the ratio and are measured once.

**Read the voicing column beside the pitch column or not at all.** Voicing
recall asks what share of the annotated singing the estimate also called
sounding, and it does not ask whether what was tracked was the singer. Where
the band is loud the tracker follows the band, which is voiced, so that column
holds up while pitch accuracy collapses, because the band is voiced. It is a
measure of whether something was heard, not of whether the right thing was.

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
import shutil
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

#: The default interferer: a full-band package with drums, bass, comping — and
#: a flute melody of its own, in the vocal register, which is the thing about it
#: a reader most needs to know, because that is what the tracker locks onto when
#: the band is loud. Committed, so the run reproduces from the repository.
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


CLIPPED_DBFS = -0.1

#: What a refused row is called, everywhere it is named. One word rather than
#: three literals, because `railed` refuses more than it can prove clipped and
#: "CLIPPED" said otherwise in two of the three places it was written out.
REFUSED_LABEL = "REFUSED"


def railed(mix_peak_dbfs: float) -> bool:
    """Whether a mix came close enough to full scale not to be trusted.

    `volumedetect` reports to a tenth of a decibel, so a clamped mix reads zero
    at that resolution — and so does one peaking a twentieth of a decibel
    short. From the report alone, clamped and nearly-clamped are the same
    answer. The threshold refuses that bin and one more, so it is a margin and
    not a measurement of distortion: a row it marks may hold none. Refusing a
    clean row costs a point on the curve, which is the cheaper mistake.
    """
    return mix_peak_dbfs >= CLIPPED_DBFS


#: How the bed is reduced to one channel, everywhere it is measured or mixed.
#: Written out rather than left to the graph: amix negotiates a mono graph
#: against a mono voice and inserts its own conversion, which in the float
#: domain sums the two channels at 1/sqrt(2) each without renormalising — so a
#: bed whose channels are correlated arrives 3 dB above what it was measured
#: at, and the ratio this tool exists to state is wrong by that much. The error
#: is bed-dependent, between nothing and 3 dB, which is worse than a constant:
#: it lands exactly on the comparison between two beds. Measure the stream you
#: mix.
BED_TO_MONO = "pan=mono|c0=0.5*c0+0.5*c1"


def segment_rms_dbfs(bed: Path, start: float, end: float) -> float:
    """The bed's RMS over the stretch that will be mixed in, in dBFS.

    ffmpeg rather than the standard library because a bed may be an mp3, and
    `volumedetect`'s mean_volume is exactly this quantity. Downmixed first —
    see BED_TO_MONO, without which this measures a different signal from the
    one mix() adds.
    """
    done = subprocess.run(
        ["ffmpeg", "-hide_banner", "-ss", f"{start}", "-to", f"{end}",
         "-i", str(bed), "-af", f"{BED_TO_MONO},volumedetect", "-f", "null", "-"],
        capture_output=True, text=True)
    for line in done.stderr.splitlines():
        if "mean_volume:" in line:
            return float(line.split("mean_volume:")[1].split("dB")[0])
    sys.exit(f"could not measure {bed.name}:\n{done.stderr}")


def bed_gain(voice_dbfs: float, bed_dbfs: float, ratio_db: float) -> float:
    """The linear gain that puts the voice `ratio_db` above the bed.

    Its own function so the arithmetic can be asserted rather than read.
    """
    return 10 ** ((voice_dbfs - ratio_db - bed_dbfs) / 20)


def duration(path: Path) -> float:
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)], capture_output=True, text=True)
    if probe.returncode != 0:
        sys.exit(f"ffprobe failed on {path}:\n{probe.stderr}")
    return float(probe.stdout.strip())


def peak(path: Path) -> float:
    """The mix's peak, in dBFS, as ffmpeg's `volumedetect` reports it.

    Rounded to a tenth of a decibel by that report, which is what `railed`
    has to work around.
    """
    done = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(path), "-af", "volumedetect",
         "-f", "null", "-"], capture_output=True, text=True)
    for line in done.stderr.splitlines():
        if "max_volume:" in line:
            return float(line.split("max_volume:")[1].split("dB")[0])
    sys.exit(f"could not measure the peak of {path.name}:\n{done.stderr}")


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
        f"{BED_TO_MONO},volume={gain}[bed];"
        f"[0:a][bed]amix=inputs=2:duration=first:normalize=0[out]",
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
    clipped = []
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
            shutil.rmtree(Path(tmp) / f"ws_alone_{clip}", ignore_errors=True)

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
            railed_here = 0
            print(f"   {ratio:+.1f} dB, clip by clip:")
            for clip in sorted(clean_rows):
                voice = melody.VOCADITO / "Audio" / f"vocadito_{clip}.wav"
                # The bed is scaled per clip, because the voice's level is a
                # property of that recording and the ratio is what is being held
                # fixed across them.
                window = (BED_OFFSET_SECONDS, BED_OFFSET_SECONDS + duration(voice))
                gain = bed_gain(levels[clip], segment_rms_dbfs(bed, *window), ratio)
                mixed = Path(tmp) / f"mix_{clip}_{ratio}.wav"
                mix(voice, bed, gain, mixed)
                # Clipping is a second variable arriving with the ratio, and it
                # arrives on the loud-voiced clips only — so a row that averaged
                # these clips with clean ones could be reporting distortion as
                # if it were the band. Refused rather than fixed here: attenuating
                # both sides preserves the ratio but changes the absolute level,
                # and the separator is not level-invariant (#515).
                mix_peak = peak(mixed)
                if railed(mix_peak):
                    clipped.append((clip, ratio, mix_peak))
                    railed_here += 1
                stem = separate(jar, mixed, Path(tmp) / f"ws_{clip}_{ratio}")
                scored = score(jar, stem, references[clip])
                rows.append(scored)
                # Per clip, because splitting a mean by the clips' own levels is
                # how #505 was found in the first place, and the mean alone
                # cannot be split after the fact.
                print(f"     vocadito_{clip:<3d} voice {levels[clip]:6.1f} dBFS"
                      f"   note F1 {100 * scored[0]:5.1f}%"
                      f"   pitch {100 * scored[1]:5.1f}%"
                      f"   voicing {100 * scored[2]:5.1f}%"
                      f"{'   ' + REFUSED_LABEL if railed(mix_peak) else ''}")
                shutil.rmtree(Path(tmp) / f"ws_{clip}_{ratio}", ignore_errors=True)
                mixed.unlink(missing_ok=True)
            # A mean over rows some of which railed is not a reading of this
            # ratio, and printing it unmarked is how it gets quoted anyway.
            spoiled = (f"   ** {railed_here} of {len(rows)} clips {REFUSED_LABEL};"
                       f" this row is not a reading of {ratio:+.1f} dB **"
                       if railed_here else "")
            print(f"   {ratio:+6.1f} dB mean note F1 {100 * statistics.mean(r[0] for r in rows):5.1f}%"
                  f"   pitch {100 * statistics.mean(r[1] for r in rows):5.1f}%"
                  f"   voicing {100 * statistics.mean(r[2] for r in rows):5.1f}%{spoiled}")

    if clipped:
        print()
        print(f"  {REFUSED_LABEL}: these mixes read -0.1 dBFS or louder. At zero"
              " the reading cannot")
        print("  be told from clamped, and -0.1 is the margin around it — so a row")
        print("  holding them may be reporting distortion rather than band, and")
        print("  the peaks below say which. Ask for a ratio that fits, or give")
        print("  the bed less to do.")
        for clip, ratio, mix_peak in clipped:
            print(f"    vocadito_{clip} at {ratio:+.1f} dB peaked at {mix_peak:+.1f} dBFS")
        sys.exit(1)


if __name__ == "__main__":
    main()
