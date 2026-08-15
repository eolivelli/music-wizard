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

**The vocal-to-band ratio is not controlled, and it dominates the result.**
The bed is attenuated by a fixed gain and the voice is used at whatever level
its clip was recorded at; vocadito's clips span some 20 dB of level, so on some
of them the "bed under the voice" is in fact well above it, and those are the
clips that lose everything. Until that is fixed (#505) this tool compares
loudnesses as much as it compares beds, and no figure from it should be quoted
without saying which clips it covers.

The bed defaults to a committed synthetic package, so the measurement is
reproducible from the repository by anyone who has fetched vocadito
(`uncommitted/list.txt`), and `--bed` takes any other recording.

So the number this prints is not "what separation costs", singular. It is what
this path costs *this voice against this bed at this ratio*, and the spread is
wider than most changes anyone would make to the melody stage. Quote the bed
with the figure or do not quote the figure.

Nothing here is baselined. It answers a question that is asked when a separator
or a melody stage changes (#503), not on every premerge — and its cost is two
Spleeter runs per clip.

Usage:  python3 tools/measure-separation-cost.py [--clips 10]
                                                 [--jar mw-cli/target/mw.jar]
"""

import argparse
import statistics
import subprocess
import sys
import tempfile
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
melody = import_module("score-melody")

REPO = Path(__file__).resolve().parent.parent

#: The default interferer: a full-band package with drums, bass and comping.
#: Committed, so the run reproduces from the repository. Comparing it against
#: another bed is not something this tool can do yet; see #505.
DEFAULT_BED = REPO / "synthetic_samples" / "pop-axis-g-116.mp3"

#: What the bed is attenuated by. NOT a ratio against the voice: the voice is
#: used at whatever level its clip was recorded at, so the ratio this produces
#: varies by clip and is often the other way up (#505).
BED_GAIN = 0.6

#: Where in the bed to start, so every clip hears the same stretch of it and no
#: clip hears the arrangement's opening bar.
BED_OFFSET_SECONDS = 30


def duration(path: Path) -> float:
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)], capture_output=True, text=True)
    if probe.returncode != 0:
        sys.exit(f"ffprobe failed on {path}:\n{probe.stderr}")
    return float(probe.stdout.strip())


def mix(voice: Path, bed: Path, into: Path) -> None:
    """The voice at its own level with the attenuated bed added, trimmed to it."""
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
        f"volume={BED_GAIN}[bed];[0:a][bed]amix=inputs=2:duration=first:normalize=0[out]",
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
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"jar not found: {jar} (build with: mvn -DskipTests package)")
    bed = Path(args.bed)
    if not bed.exists():
        sys.exit(f"no bed to mix with: {bed}")

    clean_rows, alone_rows, separated_rows = [], [], []
    print("What the mix-and-separate path costs the melody stage")
    print(f"(bed: {bed.name}, attenuated to {BED_GAIN}; the ratio against each")
    print(" voice is whatever that clip was recorded at, and is not controlled: #505)")
    print("  clip            clean F1  spleeter-only F1  separated F1")
    with tempfile.TemporaryDirectory() as tmp:
        for clip in range(1, args.clips + 1):
            voice = melody.VOCADITO / "Audio" / f"vocadito_{clip}.wav"
            notes = melody.VOCADITO / "Annotations" / "Notes" / f"vocadito_{clip}_notesA1.csv"
            if not voice.exists() or not notes.exists():
                print(melody.missing_clip_line(clip))
                continue
            reference = melody.vocadito_notes(notes)
            mixed = Path(tmp) / f"mix_{clip}.wav"
            mix(voice, bed, mixed)
            stem = separate(jar, mixed, Path(tmp) / f"ws_{clip}")

            # A null control: the same voice through the same separator with
            # no band at all. It bounds what the separator does to a voice by
            # itself, which is nearly nothing -- and it cannot apportion the
            # column beside it, because with no band present there is nothing
            # for the mask to take the voice out along with.
            alone_stem = separate(jar, voice, Path(tmp) / f"ws_alone_{clip}")

            clean = score(jar, voice, reference)
            alone = score(jar, alone_stem, reference)
            separated = score(jar, stem, reference)
            clean_rows.append(clean)
            alone_rows.append(alone)
            separated_rows.append(separated)
            print(f"  vocadito_{clip:<10d} {100 * clean[0]:6.1f}%      {100 * alone[0]:6.1f}%"
                  f"            {100 * separated[0]:6.1f}%")

    if not clean_rows:
        return
    for index, name in ((0, "note F1 @50 ms"), (1, "raw pitch accuracy"), (2, "voicing recall")):
        clean = 100 * statistics.mean(row[index] for row in clean_rows)
        alone = 100 * statistics.mean(row[index] for row in alone_rows)
        separated = 100 * statistics.mean(row[index] for row in separated_rows)
        print(f"  mean {name:20s} clean {clean:5.1f}%   spleeter-only {alone:5.1f}%"
              f"   separated {separated:5.1f}%"
              f"   (separator alone {alone - clean:+.1f}, with a band {separated - alone:+.1f})")


if __name__ == "__main__":
    main()
