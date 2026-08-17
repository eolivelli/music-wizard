#!/usr/bin/env python3
"""Scores mw's melody stage against each synthetic package's own MIDI.

The ground truth is the "Melody" track of `<name>.mid`, which the spec
compiled and the .mp3 was rendered from — so unlike a transcription judged by
ear, every onset and every pitch here is exact by construction. Read
synthetic_samples/README.md for what that does and does not license: these are
tier 1-and-a-half figures and are never product accuracy.

Everything is measured in seconds and nothing in beats. That is deliberate:
the melody stage reads pitch out of the audio and the beat tracker reads the
grid, and scoring notes on the grid would fold two independent failures into
one column, where a melody read perfectly over a mistracked grid is
indistinguishable from the reverse. What the notation makes of these notes is
the chart harnesses' business.

Columns, per package:

  notes       how many notes came out, against how many are in the MIDI
  F1@50ms     note F1: a hit needs the right semitone and an onset within
              50 ms, matched one-to-one. The standard tolerance, and a hard
              one here — see PitchTracker, whose window is 93 ms wide.
              The column carries one name over two definitions of "onset":
              synthetic rows score against MIDI note-ons, vocadito's
              annotators mark the sung vowel. Do not compare it across the
              sources or tune placement against one alone — see #497.
  F1@100ms    the same at 100 ms, which is what the analysis window can
              actually support. Both are printed because the gap between
              them is how much of a loss is placement rather than pitch.
  pitch       raw pitch accuracy: the share of the reference's sounding time
              where the estimate sounds the same semitone. Independent of
              segmentation, so a melody cut into the wrong notes but read at
              the right pitches still scores well here and badly above.
  voiced      the share of the reference's sounding time the estimate also
              calls sounding. Dropped notes show up here and nowhere else.

With `--source vocadito` the same columns are scored against a corpus of real
singing instead: 40 clips of solo voice, annotated note by note by trained
musicians, CC BY 4.0 and fetched into `uncommitted/` (see uncommitted/list.txt).
Two things about that corpus decide how its rows are read.

**It carries its own ceiling.** Both annotators' notes are published, so the
row `annotators` is one musician scored against the other by this harness's own
rule. It sits far below 100%, because where a sung note begins is genuinely
ambiguous: the count beside it is the second annotator's own number of notes,
against the first annotator's in the `notes` denominator, and on this corpus no
clip's two readings agree. A melody stage approaching that row has reached the end of what this
metric can ask for, and a stage far above it is measuring an annotator's habits
rather than the singing.

**Nothing here is separated.** The clips are solo voice already, so these rows
measure segmentation and nothing else; a mix would be measuring Spleeter too.

`--separated` reads either corpus the way `analyze --melody` reads a recording
since #559: through the separated vocal. Its pair with the pinned rows is the
measurement — what separation buys where a band is playing under the melody,
and what it costs where the melody is alone, sung on one corpus and rendered
from a soundbank on the other. Local-only, because it needs a separation model
this machine has downloaded; without one every row skips.

Usage:  python3 tools/score-melody.py [--jar mw-cli/target/mw.jar]
                                      [--source synthetic|vocadito] [--separated]
"""

import argparse
import json
import math
import os
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CORPUS = REPO / "synthetic_samples"

#: Real singing, note-annotated. Absent from a fresh clone by design; the fetch
#: command is in uncommitted/list.txt, as it is for every other local-only file.
VOCADITO = REPO / "uncommitted" / "vocadito"

#: How many clips it holds. The loop runs over all of them whatever is on disk,
#: so a half-unpacked corpus reports the clips it is missing by name rather than
#: quietly scoring the ones it has.
VOCADITO_CLIPS = 40

MELODY_TRACK = "Melody"

#: Sampling step of the framewise columns, in seconds.
FRAME_SECONDS = 0.01

#: Onset tolerances the note columns are matched at, in seconds.
TOLERANCES = (0.05, 0.10)


# --------------------------------------------------------------- MIDI reading

def _chunks(data: bytes):
    at = 0
    while at + 8 <= len(data):
        tag = data[at:at + 4]
        length = struct.unpack(">I", data[at + 4:at + 8])[0]
        yield tag, data[at + 8:at + 8 + length]
        at += 8 + length


def _varlen(data: bytes, at: int) -> tuple[int, int]:
    value = 0
    while True:
        byte = data[at]
        at += 1
        value = (value << 7) | (byte & 0x7F)
        if not byte & 0x80:
            return value, at


def melody_notes(midi: Path) -> list[tuple[float, float, int]]:
    """The melody track's notes as (onset, offset, pitch), in seconds.

    A minimal SMF reader rather than a dependency: every harness in this
    directory runs on the standard library alone, so that a fresh clone can
    measure itself without a Python environment being set up first.

    One tempo is assumed, because mw-teacher writes one. A package with a tempo
    change would be read at its first tempo, and nothing here would say so —
    which is why this asserts on a second tempo event rather than averaging.
    """
    data = midi.read_bytes()
    chunks = list(_chunks(data))
    division = struct.unpack(">HHH", chunks[0][1][:6])[2]
    if division & 0x8000:
        sys.exit(f"{midi.name}: SMPTE time division is not supported")
    micros_per_quarter = 500_000
    tempos = 0
    found: list[tuple[float, float, int]] = []

    for tag, body in chunks[1:]:
        if tag != b"MTrk":
            continue
        at = tick = status = 0
        name = ""
        sounding: dict[int, list[int]] = {}
        notes: list[tuple[int, int, int]] = []
        while at < len(body):
            delta, at = _varlen(body, at)
            tick += delta
            if body[at] & 0x80:
                status = body[at]
                at += 1
            if status == 0xFF:
                meta = body[at]
                at += 1
                length, at = _varlen(body, at)
                payload = body[at:at + length]
                at += length
                if meta == 0x03:
                    name = payload.decode("latin-1")
                elif meta == 0x51:
                    micros_per_quarter = int.from_bytes(payload, "big")
                    tempos += 1
                continue
            if status in (0xF0, 0xF7):
                length, at = _varlen(body, at)
                at += length
                continue
            kind = status & 0xF0
            if kind in (0x80, 0x90, 0xA0, 0xB0, 0xE0):
                first, second = body[at], body[at + 1]
                at += 2
                if kind == 0x90 and second > 0:
                    sounding.setdefault(first, []).append(tick)
                elif kind == 0x80 or (kind == 0x90 and second == 0):
                    if sounding.get(first):
                        notes.append((sounding[first].pop(0), tick, first))
            else:
                at += 1  # program change, channel pressure
        if name == MELODY_TRACK:
            found = notes

    if tempos > 1:
        sys.exit(f"{midi.name}: more than one tempo; this harness assumes one")
    seconds = micros_per_quarter / 1e6 / division
    return sorted((start * seconds, end * seconds, pitch)
                  for start, end, pitch in found)


# ------------------------------------------------------------------- analysis

# What analyze prints for each way the melody stage can end, held against
# AnalyzeCommand's and AudioTranscriber's own source by a test in
# tools/test-harness-rules.py: a rewording there must fail before this starts
# scoring the wrong signal, or skipping every row, in silence.
#
# The outcome and the cause are different lines. The stage says which signal it
# read; why it was the mix is the CLI's, either as the caveat it prints when
# there is no stem to be had or as the warning it prints when a separator had
# one and could not produce it. The causes are searched first, because a skip
# row carrying the symptom would read the same for a machine with no model as
# for a separator that crashed.
FROM_STEM = "tracking the melody in the vocal stem"
FROM_MIX = "tracking the melody in the full mix"
NOT_SEPARATED = "the vocal could not be separated"
NO_STEM = "the melody is read from the full mix"
REASONS = (NOT_SEPARATED, NO_STEM, FROM_MIX)

# A recording nothing could be tracked in reaches no melody stage at all and
# prints neither outcome. Scored as the zero notes it produced, which is what
# the pinned loop scores it as: the two loops must not disagree about what an
# unanalysable package is.
NO_BEATS = "no beats found"


def analyze(jar: Path, mp3: Path, separated: bool = False,
            config_home: Path | None = None) -> tuple[dict | None, str | None]:
    """Runs the pipeline with the melody stage on, and reads the score back.

    Returns (score document, None), or (None, reason) when `separated` was
    asked for and the environment could not separate.

    Without `separated` the run passes `--skip-separation`, which pins what
    these rows measure: the tracker and the segmenter, on the audio as given.
    Since #559 `--melody` reads the separated vocal wherever a provider can be
    had, and whether one can is a fact about the machine -- so an unpinned run
    would measure Spleeter here and the mix in CI, against one committed
    baseline. What separation is worth is measured by `--source separated`,
    which is local-only for exactly that reason.
    """
    environment = dict(os.environ)
    if config_home is not None:
        # The harness's own empty config home, so a machine's
        # ml.separationProvider cannot move a committed baseline; the model
        # cache lives under XDG_CACHE_HOME and is untouched by this.
        environment["XDG_CONFIG_HOME"] = str(config_home)
    with tempfile.TemporaryDirectory() as tmp:
        ws = Path(tmp) / "w.mwz"
        melody = ["analyze", str(ws), "--melody"]
        report = ""
        for args in (["init", str(mp3), "--workspace", str(ws)],
                     melody if separated else melody + ["--skip-separation"]):
            done = subprocess.run(["java", "-jar", str(jar), *args],
                                  capture_output=True, text=True, env=environment)
            if done.returncode != 0:
                sys.exit(f"mw {args[0]} failed on {mp3.name}:\n"
                         f"{done.stdout}{done.stderr}")
            report = done.stdout + "\n" + done.stderr
        if separated and FROM_STEM not in report:
            if FROM_MIX in report:
                # No separator here, or none that ran. Scoring the mix melody
                # against this baseline would report a machine's missing model
                # as a regression in the stage.
                return None, first_line(report, REASONS)
            if NO_BEATS not in report:
                sys.exit(f"{mp3.name}: analyze reported no melody outcome at all:\n"
                         + report[-500:])
        return json.loads((ws / "score" / "score.json").read_text()), None


def first_line(report: str, markers: tuple[str, ...]) -> str:
    """What analyze said after the earliest marker that appears at all.

    Marker by marker rather than line by line, so the order of `markers` is a
    priority: the cause is reported wherever analyze printed it, even though
    the symptom is printed on stdout and often first.

    What follows the marker rather than the whole line, because the row is
    bounded and every character of our own wording spends the budget the cause
    needs -- and a model fetch's message is long, invariant at its head and
    distinct only somewhere inside. Analyze puts nothing after it for that
    reason. A marker with nothing after it is its own reason.
    """
    for marker in markers:
        for line in report.splitlines():
            if marker in line:
                return line.split(marker, 1)[1].strip(" :;,.") or marker
    return "no reason given"


def estimated_notes(doc: dict) -> list[tuple[float, float, int]]:
    for track in doc.get("tracks", []):
        if track.get("role") == "LEAD_VOCAL":
            return sorted((note["onsetSeconds"],
                           note["onsetSeconds"] + note["durationSeconds"],
                           note["midiPitch"])
                          for note in track.get("notes", []))
    return []


# -------------------------------------------------------------------- scoring

def note_f1(estimate: list, reference: list, tolerance: float) -> float:
    """One-to-one note F1 at an onset tolerance, on the semitone.

    Matched greedily in reference order, each estimate used once. Greedy is
    not maximal, but the alternative only differs where two estimates of the
    same pitch fall inside one tolerance window of each other — which is a
    tracker doing something this harness should not be smoothing over.
    """
    used = [False] * len(estimate)
    hits = 0
    for onset, _end, pitch in reference:
        best = -1
        for index, (candidate, _candidate_end, candidate_pitch) in enumerate(estimate):
            if used[index] or candidate_pitch != pitch:
                continue
            if abs(candidate - onset) <= tolerance:
                if best < 0 or abs(candidate - onset) < abs(estimate[best][0] - onset):
                    best = index
        if best >= 0:
            used[best] = True
            hits += 1
    if not hits:
        return 0.0
    precision = hits / len(estimate)
    recall = hits / len(reference)
    return 2 * precision * recall / (precision + recall)


def sounding_at(notes: list, when: float) -> int | None:
    """The pitch sounding at a moment, or None.

    Where two reference notes overlap, the one that started earlier answers.
    In this corpus that only ever arbitrates floating-point dust, tens of
    microseconds wide, from adding a duration to an onset.
    """
    for onset, end, pitch in notes:
        if onset <= when < end:
            return pitch
        if onset > when:
            break
    return None


def framewise(estimate: list, reference: list) -> tuple[float, float]:
    """Raw pitch accuracy and voicing recall over the reference's sounding time."""
    if not reference:
        return 0.0, 0.0
    end = reference[-1][1]
    frames = int(end / FRAME_SECONDS)
    voiced = right = sounding = 0
    for index in range(frames):
        when = index * FRAME_SECONDS
        want = sounding_at(reference, when)
        if want is None:
            continue
        voiced += 1
        got = sounding_at(estimate, when)
        if got is not None:
            sounding += 1
            if got == want:
                right += 1
    if not voiced:
        return 0.0, 0.0
    return right / voiced, sounding / voiced


# ------------------------------------------------------------------- vocadito

def vocadito_notes(csv_file: Path) -> list[tuple[float, float, int]]:
    """One annotator's notes as (onset, offset, pitch), pitch rounded to a semitone.

    The file is `onset seconds, pitch hertz, duration seconds`. Rounded here
    because that is the resolution this harness compares at, and rounding once
    at the edge keeps the comparison rule identical to the synthetic side's.
    """
    notes = []
    for line in csv_file.read_text().splitlines():
        if not line.strip():
            continue
        onset, hertz, duration = (float(field) for field in line.split(","))
        pitch = round(69 + 12 * math.log2(hertz / 440.0))
        notes.append((onset, onset + duration, pitch))
    return sorted(notes)


def missing_clip_line(clip: int) -> str:
    """The row for a clip this machine cannot measure.

    Its wording is what premerge.sh turns into a SKIP rather than a failure, so
    it is written once here and held to that script by a test — the lyric
    harness's own skip marker is pinned the same way, for the same reason.
    """
    return (f"  vocadito_{clip}: not present (local-only;"
            f" see uncommitted/list.txt to fetch)")


def score_clip(jar: Path, clip: int, separated: bool = False,
               config_home: Path | None = None) -> str:
    audio = VOCADITO / "Audio" / f"vocadito_{clip}.wav"
    first = VOCADITO / "Annotations" / "Notes" / f"vocadito_{clip}_notesA1.csv"
    second = VOCADITO / "Annotations" / "Notes" / f"vocadito_{clip}_notesA2.csv"
    if not audio.exists() or not first.exists() or not second.exists():
        return missing_clip_line(clip)
    document, reason = analyze(jar, audio, separated, config_home)
    if reason is not None:
        return unavailable_line(f"vocadito_{clip}", reason)
    reference = vocadito_notes(first)
    estimate = estimated_notes(document)
    other = vocadito_notes(second)
    if not estimate:
        return (f"  vocadito_{clip}: notes=0/{len(reference)}  F1@50ms 0.0%"
                f"  F1@100ms 0.0%  pitch 0.0%  voiced 0.0%"
                f"  annotators {100 * note_f1(other, reference, TOLERANCES[0]):.1f}%"
                f" ({len(other)} notes)")
    pitch, voiced = framewise(estimate, reference)
    return (f"  vocadito_{clip}: notes={len(estimate)}/{len(reference)}"
            f"  F1@50ms {100 * note_f1(estimate, reference, TOLERANCES[0]):.1f}%"
            f"  F1@100ms {100 * note_f1(estimate, reference, TOLERANCES[1]):.1f}%"
            f"  pitch {100 * pitch:.1f}%"
            f"  voiced {100 * voiced:.1f}%"
            f"  annotators {100 * note_f1(other, reference, TOLERANCES[0]):.1f}%"
            f" ({len(other)} notes)")


#: How much of the reason a skip row carries, so a pasted stack trace cannot
#: wrap it.
REASON_BUDGET = 160

#: How much of a URL inside one. A model URL is most of a fetch failure's
#: length and is the same in every one of them, so it is what a bounded row
#: gives up first: nothing is learned from the span the whole family shares.
URL_BUDGET = 26

ELLIPSIS = "..."

URL = re.compile(r"https?://\S+")


def elided(text: str, budget: int) -> str:
    """`text`, no longer than `budget`, with its middle taken out.

    The middle rather than the tail, because the tail is where a message says
    what actually happened: a fetch failure names the model and the URL first
    and the reason last, so keeping the head alone would read the same however
    the fetch failed.
    """
    if len(text) <= budget:
        return text
    if budget <= len(ELLIPSIS):
        return ELLIPSIS[:budget]
    head = (budget - len(ELLIPSIS)) // 2
    tail = budget - len(ELLIPSIS) - head
    return text[:head] + ELLIPSIS + text[-tail:]


def shortened(reason: str) -> str:
    """The reason, bounded, giving up the invariant spans before the rest.

    Two steps, because eliding by position alone answers this family badly:
    the offline message's actionable clause sits in the middle, between a cache
    path and the URL it would fetch from. Taking the URL out first leaves that
    message whole, and leaves the rest more of the budget than it needs.
    """
    return elided(URL.sub(lambda match: elided(match.group(), URL_BUDGET), reason),
                  REASON_BUDGET)


def unavailable_line(name: str, reason: str) -> str:
    """A row this machine could not measure, in the marker premerge.sh turns
    into a SKIP -- with analyze's own reason beside it. Never baselined: a
    committed baseline that certifies absence is a defect, and this text lives
    only on the current side of the diff."""
    return f"  {name}: not present (local-only; {shortened(reason)})"


def score_package(jar: Path, spec_file: Path, separated: bool = False,
                  config_home: Path | None = None) -> str:
    name = spec_file.name.removesuffix(".spec.txt")
    mp3 = spec_file.with_name(name + ".mp3")
    midi = spec_file.with_name(name + ".mid")
    if not mp3.exists() or not midi.exists():
        return f"  {name}: missing — regenerate with tools/music-teacher/generate.sh"
    reference = melody_notes(midi)
    if not reference:
        return f"  {name}: no melody track; not scored"

    document, reason = analyze(jar, mp3, separated, config_home)
    if reason is not None:
        return unavailable_line(name, reason)
    estimate = estimated_notes(document)
    if not estimate:
        return (f"  {name}: notes=0/{len(reference)}"
                f"  F1@50ms 0.0%  F1@100ms 0.0%  pitch 0.0%  voiced 0.0%")
    strict = note_f1(estimate, reference, TOLERANCES[0])
    loose = note_f1(estimate, reference, TOLERANCES[1])
    pitch, voiced = framewise(estimate, reference)
    return (f"  {name}: notes={len(estimate)}/{len(reference)}"
            f"  F1@50ms {100 * strict:.1f}%"
            f"  F1@100ms {100 * loose:.1f}%"
            f"  pitch {100 * pitch:.1f}%"
            f"  voiced {100 * voiced:.1f}%")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--source", choices=("synthetic", "vocadito"), default="synthetic")
    parser.add_argument("--separated", action="store_true",
                        help="read the melody through the separated vocal, as"
                             " analyze --melody does (#559)")
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"jar not found: {jar} (build with: mvn -DskipTests package)")

    # The config home is this harness's own and empty, so a machine's
    # ml.separationProvider cannot move a committed baseline. Created for both
    # loops, because the pinned one has the same reason to ignore the machine.
    with tempfile.TemporaryDirectory() as tmp:
        config_home = Path(tmp)
        if args.source == "vocadito":
            print("Melody, note by note against vocadito's annotations (real solo singing)")
            print("(the annotators column is one musician scored against the other by this")
            print(" same rule: it is the ceiling, not a target to pass)")
            if args.separated:
                print("(read through the separated vocal: what analyze --melody now does)")
            for clip in range(1, VOCADITO_CLIPS + 1):
                print(score_clip(jar, clip, args.separated, config_home))
            return

        specs = sorted(CORPUS.glob("*.spec.txt"))
        if not specs:
            sys.exit(f"no specs in {CORPUS}")
        print("Melody, note by note against each package's own MIDI melody track")
        print("(seconds throughout: the beat grid is scored by the chart harnesses)")
        print("(a package with a band under the melody is a control, not a target:")
        print(" the tracker is monophonic and reads the loudest periodic line)")
        if args.separated:
            print("(read through the separated vocal: what analyze --melody now does)")
        for spec_file in specs:
            print(score_package(jar, spec_file, args.separated, config_home))


if __name__ == "__main__":
    main()
