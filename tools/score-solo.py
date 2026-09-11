#!/usr/bin/env python3
"""Scores the page mw writes for a solo instrument against the package's MIDI.

A package with `accompaniment: none` is one instrument playing a tune alone —
a flute, a single-voice piano — and what a player wants from it is the sheet:
the right notes in the right bars, at the right lengths, under the right key
signature. `tools/score-melody.py` scores the same packages in seconds, on
purpose, so that a melody read right over a grid tracked wrong still shows as
read right. This harness asks the other question: what reached the page. It
runs the route a solo recording takes — `analyze --melody --skip-separation`,
then `render --parts playable` — and reads `lead-playable.musicxml` back, so
every stage between the audio and the paper is inside the measurement: the
beat grid, its lead-in, the key, the tracker, the quantizer and the export.

Columns, per package:

  bars      measures on the page, against bars in the spec
  tempo     what analyze printed, against the spec's, as score-synthetic reads it
  shift     the offset, in quarter beats, at which the most notes land on
            their reference. Zero is a page whose bar one is the spec's bar
            one; a positive whole bar is a bar of rests the page opens with
            that nothing played (#824).
  notes     how many notes the page holds, against the MIDI melody track
  placed    note F1 at that shift: the right semitone at the right beat of
            the right bar, matched one-to-one. Exact, since a printed onset
            is either the beat it was played on or another one.
  held      of the placed notes, the share printed at the length played
  key       the key signature and mode on the page, against the spec's

The shift is reported and then credited rather than charged, because the two
defects are separate: a page a bar late with every note right is a numbering
error a player corrects by hand, and a page whose notes are wrong is not a
page. Each is read off its own column.

A package with anything playing under the melody is not scored here: its
tracker reads a mix (score-melody's caveat) and its grid reads the band
(score-synthetic's row), and a sheet column on it would fold both into one
number. These rows are tier one-and-a-half and never product accuracy — see
synthetic_samples/README.md.

Usage:  python3 tools/score-solo.py [--jar mw-cli/target/mw.jar] [--pinned]

`--pinned` forces the spec's tempo and meter on analyze, so the page is scored
with the grid taken away from the measurement. A sweep knob: the committed
baseline is the unpinned reading, which is what a recording gets.
"""

import argparse
import json
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
melody = import_module("score-melody")
synthetic = import_module("score-synthetic")

REPO = Path(__file__).resolve().parent.parent
CORPUS = REPO / "synthetic_samples"

#: Two printed positions or lengths closer than this are the same rational.
EXACT = 1e-3

#: The 4/4 the arranger insists on for a package with a melody (#715).
BAR_QUARTERS = 4.0

#: Fifths on the key signature of each major tonic, as a spec spells it.
MAJOR_FIFTHS = {"C": 0, "G": 1, "D": 2, "A": 3, "E": 4, "B": 5, "F#": 6, "C#": 7,
                "F": -1, "Bb": -2, "Eb": -3, "Ab": -4, "Db": -5, "Gb": -6, "Cb": -7}

MIDI_STEP = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


# ------------------------------------------------------------------ reference

def reference_notes(midi: Path, tempo: float) -> list[tuple[float, float, int]]:
    """The melody track as (onset, duration, pitch) in quarter beats from bar
    one, read off the seconds score-melody reads and the tempo the spec
    compiled at, which is the one tempo the MIDI holds."""
    per_second = tempo / 60.0
    return [(round(on * per_second, 6), round((off - on) * per_second, 6), pitch)
            for on, off, pitch in melody.melody_notes(midi)]


def spec_key(header: str) -> tuple[int, str] | None:
    tonic, _, mode = header.partition(" ")
    if mode not in ("major", "minor"):
        return None
    fifths = MAJOR_FIFTHS.get(tonic if mode == "major" else relative_major(tonic))
    return None if fifths is None else (fifths, mode)


def relative_major(minor_tonic: str) -> str:
    """The major key three semitones up, spelled with the flats or sharps the
    minor tonic's own name carries: Bb minor's relative is Db, F# minor's is A."""
    semitone = (MIDI_STEP[minor_tonic[0]] + {"#": 1, "b": -1}.get(minor_tonic[1:], 0) + 3) % 12
    names = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]
    if "#" in minor_tonic:
        names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    return names[semitone]


# ----------------------------------------------------------------------- page

def page_notes(musicxml: Path) -> dict:
    """What the playable part's first staff holds: its notes as (onset,
    duration, pitch) in quarter beats from the page's own origin, with ties
    joined into the note a player holds; its measure count; and its opening
    key as (fifths, mode)."""
    part = ET.parse(musicxml).getroot().find("part")
    if part is None:
        sys.exit(f"{musicxml}: no part")
    divisions = 1
    key = None
    notes: list[list] = []
    origin = 0.0
    measures = part.findall("measure")
    for measure in measures:
        at = origin
        longest = 0.0
        for element in measure:
            if element.tag == "attributes":
                found = element.find("divisions")
                if found is not None:
                    divisions = int(found.text)
                signature = element.find("key")
                if signature is not None and key is None:
                    key = (int(signature.findtext("fifths", "0")),
                           signature.findtext("mode", "major"))
            elif element.tag == "backup":
                at -= int(element.findtext("duration")) / divisions
            elif element.tag == "forward":
                at += int(element.findtext("duration")) / divisions
            elif element.tag == "note":
                if element.find("grace") is not None:
                    continue
                length = int(element.findtext("duration")) / divisions
                start = at if element.find("chord") is None else notes[-1][0]
                pitch = element.find("pitch")
                if pitch is not None:
                    midi = (12 * (int(pitch.findtext("octave")) + 1)
                            + MIDI_STEP[pitch.findtext("step")]
                            + int(float(pitch.findtext("alter", "0"))))
                    tied_back = any(t.get("type") == "stop" for t in element.findall("tie"))
                    previous = next((n for n in reversed(notes)
                                     if n[2] == midi and abs(n[0] + n[1] - start) < EXACT),
                                    None)
                    if tied_back and previous is not None:
                        previous[1] += length
                    else:
                        notes.append([start, length, midi])
                if element.find("chord") is None:
                    at += length
            longest = max(longest, at - origin)
        origin += longest
    return {"notes": [tuple(n) for n in notes], "measures": len(measures), "key": key}


# -------------------------------------------------------------------- scoring

def placed(estimate: list, reference: list, shift: float) -> list[tuple[int, int]]:
    """Index pairs (estimate, reference) matched one-to-one on semitone and
    exact onset once the page is moved back by `shift`."""
    free = list(range(len(estimate)))
    pairs = []
    for r, (on, _, pitch) in enumerate(reference):
        for e in free:
            e_on, _, e_pitch = estimate[e]
            if e_pitch == pitch and abs(e_on - shift - on) < EXACT:
                pairs.append((e, r))
                free.remove(e)
                break
    return pairs


def best_shift(estimate: list, reference: list) -> float:
    """The offset at which the most notes land on their reference: zero, or
    the distance between one of the page's opening notes and one of the
    reference's, so a page that dropped its first note is still read. Ties go
    to the smaller move, so a page that needs none is read as needing none."""
    if not estimate or not reference:
        return 0.0
    candidates = {0.0} | {round(e_on - r_on, 6)
                          for e_on, _, _ in estimate[:8] for r_on, _, _ in reference[:8]}
    return max(sorted(candidates, key=abs), key=lambda s: len(placed(estimate, reference, s)))


def f1(hits: int, estimated: int, expected: int) -> float:
    if hits == 0:
        return 0.0
    precision, recall = hits / estimated, hits / expected
    return 2 * precision * recall / (precision + recall)


# ------------------------------------------------------------------- pipeline

def render_solo(jar: Path, mp3: Path, pinned: dict | None) -> tuple[Path, str, tempfile.TemporaryDirectory]:
    """The playable MusicXML for a solo recording, and what analyze printed.

    The temporary directory is returned alive: the file is read by the caller.
    """
    tmp = tempfile.TemporaryDirectory()
    ws = Path(tmp.name) / "w.mwz"
    analyze = ["analyze", str(ws), "--melody", "--skip-separation"]
    if pinned:
        analyze += ["--tempo", pinned["tempo"], "--time-signature", pinned["meter"]]
    printed = ""
    for args in (["init", str(mp3), "--workspace", str(ws)],
                 analyze,
                 ["render", str(ws), "--parts", "playable", "--no-pdf"]):
        done = subprocess.run(["java", "-jar", str(jar), *args],
                              capture_output=True, text=True)
        if done.returncode != 0:
            sys.exit(f"mw {args[0]} failed on {mp3.name}:\n{done.stdout}{done.stderr}")
        if args[0] == "analyze":
            printed = done.stdout
    return ws / "out" / "lead-playable.musicxml", printed, tmp


def score_package(jar: Path, spec_file: Path, pinned: bool = False) -> str:
    name = spec_file.name.removesuffix(".spec.txt")
    mp3 = spec_file.with_name(name + ".mp3")
    midi = spec_file.with_name(name + ".mid")
    if not mp3.exists() or not midi.exists():
        return f"  {name}: missing — regenerate with tools/music-teacher/generate.sh"
    spec = synthetic.parse_spec(spec_file)
    headers = spec["headers"]
    if headers.get("accompaniment") != "none":
        return f"  {name}: not a solo package; not scored"
    tempo = float(headers["tempo"])
    reference = reference_notes(midi, tempo)
    if not reference:
        return f"  {name}: no melody track; not scored"

    musicxml, printed, tmp = render_solo(
        jar, mp3, {"tempo": headers["tempo"], "meter": synthetic.spec_meter(spec)} if pinned else None)
    with tmp:
        if not musicxml.exists():
            return f"  {name}: no playable part written"
        page = page_notes(musicxml)
    estimate = page["notes"]
    shift = best_shift(estimate, reference)
    pairs = placed(estimate, reference, shift)
    held = sum(1 for e, r in pairs if abs(estimate[e][1] - reference[r][1]) < EXACT)
    want_key = spec_key(headers.get("key", ""))
    got_key = page["key"]
    key = "OK" if got_key == want_key else f"{got_key} WRONG"
    return (f"  {name}: bars={page['measures']}/{len(spec['bars'])}"
            f"  {synthetic.tempo_verdict(synthetic.printed_tempo(printed), headers.get('tempo'))}"
            f"  shift {shift:+.2f}"
            f"  notes={len(estimate)}/{len(reference)}"
            f"  placed {100 * f1(len(pairs), len(estimate), len(reference)):.1f}%"
            f"  held {100 * held / len(pairs) if pairs else 0.0:.1f}%"
            f"  key {key}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--pinned", action="store_true",
                        help="force the spec's tempo and meter on analyze; a sweep,"
                             " never the baselined reading")
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"jar not found: {jar} (build with: mvn -DskipTests package)")
    specs = sorted(CORPUS.glob("*.spec.txt"))
    if not specs:
        sys.exit(f"no specs in {CORPUS}")
    print("Solo instrument, the playable part against each package's own MIDI, on the grid")
    print("(quarter beats throughout: the grid, its lead-in, the key and the notes are all on the page)")
    if args.pinned:
        print("(tempo and meter pinned to the spec: a sweep, not the baselined reading)")
    for spec_file in specs:
        print(score_package(jar, spec_file, args.pinned))


if __name__ == "__main__":
    main()
