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
  F1@100ms    the same at 100 ms, which is what the analysis window can
              actually support. Both are printed because the gap between
              them is how much of a loss is placement rather than pitch.
  pitch       raw pitch accuracy: the share of the reference's sounding time
              where the estimate sounds the same semitone. Independent of
              segmentation, so a melody cut into the wrong notes but read at
              the right pitches still scores well here and badly above.
  voiced      the share of the reference's sounding time the estimate also
              calls sounding. Dropped notes show up here and nowhere else.

Usage:  python3 tools/score-melody.py [--jar mw-cli/target/mw.jar]
"""

import argparse
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CORPUS = REPO / "synthetic_samples"

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

def analyze(jar: Path, mp3: Path) -> dict:
    """Runs the pipeline with the melody stage on, and reads the score back."""
    with tempfile.TemporaryDirectory() as tmp:
        ws = Path(tmp) / "w.mwz"
        for args in (["init", str(mp3), "--workspace", str(ws)],
                     ["analyze", str(ws), "--melody"]):
            done = subprocess.run(["java", "-jar", str(jar), *args],
                                  capture_output=True, text=True)
            if done.returncode != 0:
                sys.exit(f"mw {args[0]} failed on {mp3.name}:\n"
                         f"{done.stdout}{done.stderr}")
        return json.loads((ws / "score" / "score.json").read_text())


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
    """The pitch sounding at a moment, or None. Notes do not overlap here."""
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


def score_package(jar: Path, spec_file: Path) -> str:
    name = spec_file.name.removesuffix(".spec.txt")
    mp3 = spec_file.with_name(name + ".mp3")
    midi = spec_file.with_name(name + ".mid")
    if not mp3.exists() or not midi.exists():
        return f"  {name}: missing — regenerate with tools/music-teacher/generate.sh"
    reference = melody_notes(midi)
    if not reference:
        return f"  {name}: no melody track; not scored"

    estimate = estimated_notes(analyze(jar, mp3))
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
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"jar not found: {jar} (build with: mvn -DskipTests package)")

    specs = sorted(CORPUS.glob("*.spec.txt"))
    if not specs:
        sys.exit(f"no specs in {CORPUS}")
    print("Melody, note by note against each package's own MIDI melody track")
    print("(seconds throughout: the beat grid is scored by the chart harnesses)")
    print("(a package with a band under the melody is a control, not a target:")
    print(" the tracker is monophonic and reads the loudest periodic line)")
    for spec_file in specs:
        print(score_package(jar, spec_file))


if __name__ == "__main__":
    main()
