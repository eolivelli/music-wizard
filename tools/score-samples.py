#!/usr/bin/env python3
"""Scores mw against the sample benchmarks whose ground truth is known.

For each benchmark present in samples/ (some are local-only and fetched per
the instructions in samples/list.txt), this runs the shaded CLI, segments the
estimated chords into bars on the recording's own tracked beats, aligns the
known cycle at the best rotation, and reports per-bar accuracy.

It then reports the bar phase each of those rows rests on. The bars above begin
where the estimator decided they begin, and on real material that decision is
routinely made at the floor of its own confidence (#303) -- so a row can move
because the chords moved or because the phase flipped, and the columns above
cannot tell the two apart. The best phase a row names is an oracle -- the known
cycle picks it -- so it explains a row that moved and is never a figure the tool
could have produced.

It then reports the key each file was named with, against the key expected for
it -- see KEYS below for where each of those comes from, which is not the same
for every row. That table covers every file the chord table does, and the ones
it does not: a key can be stated for a recording whose bar-by-bar changes are
not written down here.

Last it reports the minor-quality seconds of the recordings in NO_MINOR_CHORD,
whose truth is an invariant rather than a grid -- see that table.

The committed CI gate lives in mw-it and reads one of these recordings; this
script is the local, all-samples view of the same question: is the tool getting
closer to the charts a musician would write?

Usage:  python3 tools/score-samples.py [--jar mw-cli/target/mw.jar]
"""

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Ground truth from samples/list.txt, one token per bar, spelled exactly as
# ChordQuality.symbol() spells it so that this file, samples/list.txt and the
# tool's own output all say the same thing: '7' dominant, 'm7' minor seventh,
# 'maj7' major seventh, '6' major sixth, 'm6' minor sixth, 'm7b5'
# half-diminished, 'dim' diminished, plain letter a major triad. 'X-Y' is a
# bar holding both chords; either counts. Scoring reports root match and
# root+quality separately, so a plain triad on the right root earns the first
# and not the second.
#
# Every grid here is one repeating cycle a musician confirmed against the
# recording. Two committed files are deliberately absent: ballad-wine-roses-65
# is a 32-bar standard rather than a loop, and its changes carry a bass note
# and a suspension this scorer has nowhere to put; footprints-200 is in three,
# so the bars a 4/4 downbeat sequence cuts are not its bars. samples/list.txt
# carries both sets of changes.
BENCHMARKS = {
    "blues-a-90bpm.mp3":
        "A7 A7 A7 A7  D7 D7 A7 A7  E7 D7 A7 E7",
    "blues-shuffle-a-106bpm.mp3":
        "A7 A7 A7 A7  D7 D7 A7 A7  E7 D7 A7 E7",
    "blues-e-90bpm.mp3":
        "E7 E7 E7 E7  A7 A7 E7 E7  B7 A7 E7 B7",
    "slow-68-40.mp3":
        "A7 A7 A7 A7  D7 D7 A7 A7  E7 D7 A7 E7",
    # The committed tier-2 gate: BluesLoopIT scores this same recording against
    # this same cycle, on a bar axis measured from the recording rather than
    # taken from the tracker. Both readings are wanted -- where they disagree,
    # the disagreement is the beat grid.
    "g-blues-shuffle-cc.mp3":
        "G7 G7 G7 G7  C7 C7 G7 G7  D7 C7 G7 D7",
    # A minor blues that stays minor, which is the case a corpus of dominant
    # sevenths cannot make: here a major third on the tonic is an error rather
    # than the answer.
    "bm-blues-slow.mp3":
        "Bm Bm Bm Bm  Em Em Bm Bm  G7 F#7 Bm Bm",
    "cm-blues-68-95.mp3":
        "Cm7 Cm7 Cm7 C7  Fm7 Fm7 Cm7 Cm7  Ab7 G7 Cm7 G7",
    # Eight bars in three, so the bar axis is only as good as the meter.
    "waltz-am-e7-160.mp3":
        "Am Am Am E7  E7 E7 E7 Am",
    "f-blues-swing-170.mp3":
        "F7 Bb7 F7 F7  Bb7 Bdim F7 Am7b5-D7  Gm7 C7 F7-D7 Gm7-C7",
    # Half of it is a quality the estimator cannot name, so the root and
    # root+quality columns are expected to come apart here rather than move
    # together.
    "jazz-251-c-140.mp3":
        "Dm7 Dm7 G7 G7  Cmaj7 Cmaj7 Cmaj7 Cmaj7",
    "fm7-vamp-110.mp3":
        "Fm7",
    "eb7-vamp-130.mp3":
        "Eb7",
    "bossa-cm.mp3":
        "Cm7 Cm7 Fm6 Fm6  Dm7b5 G7 Cm6 Cm6  Ebm7 Ab7 Dbmaj7 Dbmaj7  "
        "Dm7b5 G7 Cm6 Dm7b5-G7",
    # Plain triads throughout, which is what a corpus of sevenths needs: it is
    # where "found the seventh" and "reported one because nothing said not to"
    # look different (#273). The grid is the uploader's stated one chord per
    # bar, confirmed without ears -- the stated 120 BPM in four is a two-second
    # bar, and 57 tracked bars fill the recording's 114 seconds. See
    # samples/list.txt for what that argument does not settle.
    "pop-c-g-am-f-120.mp3":
        "C G Am F",
}

# The key each file is in, from samples/list.txt -- read off what it states,
# which for some files is a key and for others the chords and the framing. Where
# it names no mode the reading is #275's.
#
# Two of these rows are weaker than the rest and it is worth knowing which. The
# one-chord vamps cannot fail for a recording the estimator hears as one chord,
# since the tonic-chord weight can then name no other key -- they are closer to
# a tautology than a measurement, though not quite one in practice, because the
# estimator does not hear either file as one chord; the chord table above has
# how far off it is. And the twelve-bar blues rows are one shape transposed, so
# they are several readings of one question rather than several questions.
#
# The last two are the pair that is the point of the exercise: the same four
# chords framed on C and framed on A minor. They share every note, so nothing
# in the harmony separates them and only where the loop begins and ends does --
# which is the least reliable thing on a recording. Expect the estimator to say
# so in its confidence rather than to get both.
#
# That is the shape of most rows the committed baseline reads WRONG: they name
# the relative of the key wanted and report a low confidence for it, so the
# signature is right and which of the pair is home is not, which is #277 rather
# than a defect in the table. It is not the shape of all of them. bossa-cm reads
# F minor against C minor -- four flats against three, so the signature itself
# is wrong, and its signature confidence says so where its tonic confidence does
# not. A row that reads WRONG has to be looked at before it is filed under #277.
KEYS = {
    "blues-a-90bpm.mp3": "A major",
    "blues-shuffle-a-106bpm.mp3": "A major",
    "blues-e-90bpm.mp3": "E major",
    "slow-68-40.mp3": "A major",
    "g-blues-shuffle-cc.mp3": "G major",
    "fm7-vamp-110.mp3": "F minor",
    "eb7-vamp-130.mp3": "Eb major",
    "bossa-cm.mp3": "C minor",
    "bm-blues-slow.mp3": "B minor",
    "cm-blues-68-95.mp3": "C minor",
    "waltz-am-e7-160.mp3": "A minor",
    "f-blues-swing-170.mp3": "F major",
    "jazz-251-c-140.mp3": "C major",
    "ballad-wine-roses-65.mp3": "F major",
    "footprints-200.mp3": "C minor",
    "pop-c-g-am-f-120.mp3": "C major",
    "pop-am-f-c-g-144.mp3": "A minor",
}

# Recordings a musician has confirmed hold no minor chord anywhere, with where
# each of them is; uncommitted/list.txt carries the confirmation per file. They
# are commercial audio, which is why the truth about them can live here while
# the recordings themselves cannot (CLAUDE.md).
#
# The invariant wants no grid: any minor-quality second is a false label
# whatever bar it lands in. That is what prices the veto of #543 and the
# qualities of #547, which chart diffs were pricing before (#546).
NO_MINOR_CHORD = {
    "la-canzone-del-sole.mp3": "uncommitted",
    "johnny-b-goode.mp3": "uncommitted",
}

# The qualities that hold a minor third, by ChordQuality's own name and symbol.
# ChordQuality.isMinorish() is the rule -- a third of three semitones -- and
# tools/test-harness-rules.py holds this table to the enum, so a quality added
# there cannot go uncounted here.
MINOR_THIRD = {
    "MINOR": "m",
    "DIMINISHED": "dim",
    "MINOR_SEVENTH": "m7",
    "MINOR_MAJOR_SEVENTH": "mMaj7",
    "HALF_DIMINISHED_SEVENTH": "m7b5",
    "DIMINISHED_SEVENTH": "dim7",
    "MINOR_SIXTH": "m6",
}

# DownbeatEstimator.BASE_CONFIDENCE: what it reports for a phase nothing at all
# supports. Printed once in the phase block's header rather than tested against
# per row -- which rows sit at it is the whole of what that column is for, and a
# tolerance for "near it" would be one more constant deciding that.
# tools/test-harness-rules.py holds this copy to the Java one.
PHASE_FLOOR = 0.35

LETTER_SEMITONE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
ACCIDENTAL = {"NONE": 0, "SHARP": 1, "FLAT": -1, "DOUBLE_SHARP": 2, "DOUBLE_FLAT": -2}
# ChordQuality's own symbols, so a truth string is read the way the tool writes
# a chord. Only the qualities the corpus actually holds are listed: an unknown
# suffix must fail loudly here rather than be scored as something else.
SUFFIX_QUALITY = {
    "": "MAJOR",
    "m": "MINOR",
    "dim": "DIMINISHED",
    "7": "DOMINANT_SEVENTH",
    "m7": "MINOR_SEVENTH",
    "maj7": "MAJOR_SEVENTH",
    "m7b5": "HALF_DIMINISHED_SEVENTH",
    "m6": "MINOR_SIXTH",
    "6": "SIXTH",
}


def missing_line(label: str, where: str = "samples") -> str:
    """A baselined name this machine cannot measure; premerge.sh skips rows
    carrying this line's marker, and test-harness-rules.py holds every writer
    of it, and premerge.sh itself, to one literal.

    `where` is the directory the file belongs in, because its own list.txt is
    what says how to fetch it."""
    return f"  {label}: not present (local-only; see {where}/list.txt to fetch)"


def parse_chord(symbol: str) -> tuple[int, str]:
    """Lead-sheet symbol -> (pitch class, ChordQuality name)."""
    pc = LETTER_SEMITONE[symbol[0]]
    rest = symbol[1:]
    while rest[:1] in ("b", "#"):
        pc += 1 if rest[0] == "#" else -1
        rest = rest[1:]
    return pc % 12, SUFFIX_QUALITY[rest]


def parse_truth(line: str) -> list[list[tuple[int, str]]]:
    """One cycle as a list of bars, each a list of acceptable chords."""
    return [[parse_chord(c) for c in bar.split("-")] for bar in line.split()]


def chord_of(span) -> tuple[int, str] | None:
    """(pitch class, quality) of an estimated span, or None for N.C."""
    quality = span.get("quality", "NONE")
    if quality == "NONE":
        return None
    root = span.get("root", {})
    pc = (LETTER_SEMITONE.get(root.get("letter", "C")[0], 0)
          + ACCIDENTAL.get(root.get("accidental", "NONE"), 0)) % 12
    return pc, quality


def analyze(jar: Path, mp3: Path) -> dict:
    return analyze_with_output(jar, mp3)[0]


def analyze_with_output(jar: Path, mp3: Path) -> tuple[dict, str]:
    """The score document, and what `analyze` printed while producing it.

    Both from one run, so a caller wanting a figure MW derives rather than
    stores can read MW's own answer instead of deriving it again here. The
    tempo is the one: `Score.estimatedTempo()` is a method, and a harness that
    reimplemented it would be a second definition of a number the engraved
    chart already prints.
    """
    with tempfile.TemporaryDirectory() as tmp:
        ws = Path(tmp) / "w.mwz"
        printed = ""
        for args in (["init", str(mp3), "--workspace", str(ws)], ["analyze", str(ws)]):
            r = subprocess.run(["java", "-jar", str(jar), *args],
                               capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(f"mw {args[0]} failed on {mp3.name}:\n{r.stdout}{r.stderr}")
            printed = r.stdout
        return json.loads((ws / "score" / "score.json").read_text()), printed


def bar_credit(covered: dict) -> dict:
    """How one bar's credit divides between the chords in it.

    The chord covering most of the bar takes all of it: that is the chord a
    reader takes from the bar, and it is the rule both chord harnesses have always
    applied. Where several cover exactly as much, the bar is split equally
    between them instead of the first one taking it, which is #242 -- which
    chord comes first is a fact about print order, not about the recording, and
    the chart harness meets an exact tie on nearly every bar it prints as two
    chords, because `ChartLayout.atHarmonicRhythm` writes an evenly split 4/4
    bar as exactly 2+2.

    A tie is exact equality rather than a near miss. On the chart's cells that
    is the whole of it, since the lengths are written by the layout; on the
    estimated spans it is rarer, and a tolerance would be one more arbitrary
    constant deciding the same bars.
    """
    if not covered:
        return {}
    top = max(covered.values())
    tied = [chord for chord, amount in covered.items() if amount == top]
    return {chord: 1.0 / len(tied) for chord in tied}


def bar_shares(spans, start: float, end: float) -> dict:
    """The credit for [start, end), by bar_credit over the spans in it.

    Measured on the single longest span per chord rather than on the chord's
    total across the bar, which is the rule this harness has always used and is
    not the chart harness's; see that script's header for why the difference is
    harmless.
    """
    longest: dict = {}
    for s in spans:
        overlap = min(end, s["endSeconds"]) - max(start, s["startSeconds"])
        if overlap > 0:
            chord = chord_of(s)
            longest[chord] = max(longest.get(chord, 0.0), overlap)
    return bar_credit(longest)


def accuracy(shares: list, want: list) -> tuple[float, float]:
    """Bars correct on root and on root+quality, at the cycle's best rotation.

    Shared with the chart harness, which asks the same question over different
    bars: one rule, so the two cannot come to disagree about what counts as a
    correct bar. A bar whose credit is split contributes each share separately,
    so a bar the estimate splits between a right chord and a wrong one counts
    as the half it got right.
    """
    cycle = len(want)

    def rotated(rotation: int) -> tuple[float, float]:
        root_ok = full_ok = 0.0
        for index, share in enumerate(shares):
            acceptable = want[(index + rotation) % cycle]
            for got, credit in share.items():
                if got is not None and any(got[0] == w[0] for w in acceptable):
                    root_ok += credit
                    if any(got == w for w in acceptable):
                        full_ok += credit
        return root_ok, full_ok

    return max((rotated(r) for r in range(cycle)), key=lambda t: t[0])


def score(mp3: Path, doc: dict, truth: list[str]) -> None:
    spans = doc.get("chords", {}).get("chords", [])
    beats = doc.get("beatGrid", {}).get("beats", [])
    downbeats = [b["seconds"] for b in beats if b.get("downbeat")]
    if len(downbeats) < 4:
        print(f"  {mp3.name}: no usable beat grid")
        return

    bars = list(zip(downbeats, downbeats[1:]))
    shares = [bar_shares(spans, a, b) for a, b in bars]

    root_ok, full_ok = accuracy(shares, parse_truth(truth))
    nc_time = sum(s["endSeconds"] - s["startSeconds"]
                  for s in spans if chord_of(s) is None)
    duration = doc.get("durationSeconds", 1.0)

    n = len(shares)
    # How many bars no chord dominates is reported beside the columns, so a
    # split bar's half is never read as a bar the estimate got right.
    split = sum(1 for share in shares if len(share) > 1)
    print(f"  {mp3.name}: bars={n}  root {root_ok:.1f}/{n} ({100 * root_ok / n:.1f}%)"
          f"  root+quality {full_ok:.1f}/{n} ({100 * full_ok / n:.1f}%)"
          f"  split {split}"
          f"  N.C. {100 * nc_time / duration:.1f}% of {duration:.0f}s")


def bar_phase(doc: dict) -> tuple[int, int] | None:
    """Which beat of the bar the grid begins bars on, and how many it counts.

    None where the phase is not a fact about this grid at all: no bar positions
    recorded, a meter of one beat, too few beats to lay two bars at every phase,
    or positions that are not the regular ones `BeatTracker.toBeatGrid` writes.
    The per-phase scores below shift the bar lines by index, so an irregular
    grid would have them measuring something other than the phase.
    """
    beats = doc.get("beatGrid", {}).get("beats", [])
    positions = [b.get("positionInBar") for b in beats]
    if not positions or any(p is None for p in positions):
        return None
    per_bar = max(positions) + 1
    if per_bar < 2 or len(beats) < 2 * per_bar:
        return None
    phase = (per_bar - positions[0]) % per_bar
    if any(p != (i - phase) % per_bar for i, p in enumerate(positions)):
        return None
    return phase, per_bar


def phase_confidence(doc: dict) -> float | None:
    """What the estimator reported for the phase alone.

    `BeatTracker.toBeatGrid` records the product of the phase's confidence and
    the beats' -- a phase is only as good as the beats it phases -- so the
    phase's own is the ratio. Reading the product instead would report the beat
    tracker's doubt as the downbeat estimator's, and the floor this column
    exists to show would then appear at a different number on every recording.
    """
    grid = doc.get("beatGrid", {})
    beat = grid.get("beatConfidence", {}).get("value")
    downbeat = grid.get("downbeatConfidence", {}).get("value")
    if not beat or downbeat is None:
        return None
    return downbeat / beat


def phase_roots(doc: dict, truth: str, per_bar: int) -> list[float]:
    """Bars correct on root, as a percentage, with bars begun on each beat.

    The same chords and the same beats every time; only which of them starts a
    bar moves.

    Root only, so a phase stays one number.

    Every phase has at least one bar, since `bar_phase` returns nothing for a
    grid shorter than two bars.
    """
    spans = doc.get("chords", {}).get("chords", [])
    beats = [b["seconds"] for b in doc.get("beatGrid", {}).get("beats", [])]
    want = parse_truth(truth)
    scores = []
    for phase in range(per_bar):
        lines = beats[phase::per_bar]
        shares = [bar_shares(spans, a, b) for a, b in zip(lines, lines[1:])]
        root_ok, _ = accuracy(shares, want)
        scores.append(100 * root_ok / len(shares))
    return scores


def score_phase(mp3: Path, doc: dict, truth: str) -> None:
    """The bar phase this file's row above rests on, and what it cost."""
    grid = bar_phase(doc)
    if grid is None:
        print(f"  phase {mp3.name}: no bar phase to read")
        return
    confidence = phase_confidence(doc)
    if confidence is None:
        # Saying "no bar phase" here would report a phase that is there and
        # regular as one that is not (#477).
        print(f"  phase {mp3.name}: no phase confidence to read")
        return
    phase, per_bar = grid
    scores = phase_roots(doc, truth, per_bar)
    # Ties go to the phase the estimator chose, so that a row where no other
    # phase does better says so by naming that phase rather than an equal one.
    best = max(range(per_bar), key=lambda p: (scores[p], p == phase))
    print(f"  phase {mp3.name}: beat {phase} of {per_bar} at {confidence:.4f}"
          f"  root {scores[phase]:.1f}%"
          f"  best beat {best} at {scores[best]:.1f}%")


def score_key(mp3: Path, doc: dict, want: str) -> None:
    """The key the run named, against the one expected for the file."""
    keys = doc.get("keys", [])
    if not keys:
        print(f"  key {mp3.name}: none named  want {want}  WRONG")
        return
    # The audio path emits one key over the whole recording; a later modulation
    # stage (#228) would make this the longest, as Score.primaryKey does.
    key = max(keys, key=lambda k: k["endSeconds"] - k["startSeconds"])
    tonic = key["tonic"]
    written = (tonic["letter"] + ACCIDENTAL_SIGN[tonic.get("accidental", "NATURAL")]
               + " " + key["mode"].lower())
    print(f"  key {mp3.name}: {written} at {100 * key['confidence']['value']:.0f}%"
          f"  want {want}  {'OK' if written == want else 'WRONG'}")


ACCIDENTAL_SIGN = {"NATURAL": "", "NONE": "", "SHARP": "#", "FLAT": "b",
                   "DOUBLE_SHARP": "##", "DOUBLE_FLAT": "bb"}


def score_no_minor(mp3: Path, doc: dict) -> None:
    """Minor-quality seconds on a recording of NO_MINOR_CHORD, read against zero.

    Broken down by quality rather than by root, because the rules that produce
    them are per quality: `ChordEstimator` decides the third one way, the
    seventh across a whole root, the sixth and the diminished fifth another.
    """
    spans = doc.get("chords", {}).get("chords", [])
    held: dict[str, float] = {}
    minor = 0
    for span in spans:
        symbol = MINOR_THIRD.get(span.get("quality", "NONE"))
        if symbol is None:
            continue
        held[symbol] = held.get(symbol, 0.0) + span["endSeconds"] - span["startSeconds"]
        minor += 1
    duration = doc.get("durationSeconds", 0.0)
    total = sum(held.values())
    # Longest first, and by symbol where two are equal, so the row is the same
    # text for the same recording.
    columns = ", ".join(f"{symbol} {seconds:.1f}s" for symbol, seconds
                        in sorted(held.items(), key=lambda kv: (-kv[1], kv[0])))
    print(f"  minor {mp3.name}: {total:.1f}s of {duration:.0f}s"
          f" ({100 * total / duration if duration else 0:.1f}%)"
          f"  spans {minor}/{len(spans)}"
          f"  {columns if columns else 'none'}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"build first: mvn -B -DskipTests package   (missing {jar})")

    # One analysis per file however many tables read it: the two below ask
    # different questions of the same run, and analysing twice would let them
    # disagree. Cached rather than done up front, so each line still appears as
    # it is measured -- this takes tens of minutes, and a run that prints
    # nothing until it is over looks hung both here and in the CI job log.
    analysed = {}

    def doc_for(name: str, where: str = "samples") -> dict | None:
        mp3 = REPO / where / name
        if mp3 not in analysed:
            analysed[mp3] = analyze(jar, mp3) if mp3.exists() else None
        return analysed[mp3]

    print("samples with known ground truth:")
    missing = []
    for name, truth in BENCHMARKS.items():
        doc = doc_for(name)
        if doc is None:
            missing.append(name)
        else:
            score(REPO / "samples" / name, doc, truth)
    for name in missing:
        print(missing_line(name))

    print("bar phase the rows above rest on, at the estimator's own confidence"
          f" in it (a phase nothing supports reports {PHASE_FLOOR:.4f}):")
    missing = []
    for name, truth in BENCHMARKS.items():
        doc = doc_for(name)
        if doc is None:
            missing.append(name)
        else:
            score_phase(REPO / "samples" / name, doc, truth)
    for name in missing:
        print(missing_line(f"phase {name}"))

    print("keys, against the expected key for each file:")
    missing = []
    for name, want in KEYS.items():
        doc = doc_for(name)
        if doc is None:
            missing.append(name)
        else:
            score_key(REPO / "samples" / name, doc, want)
    for name in missing:
        print(missing_line(f"key {name}"))

    print("minor-quality seconds where a musician confirms the recording holds"
          " no minor chord (want zero):")
    missing = []
    for name, where in NO_MINOR_CHORD.items():
        doc = doc_for(name, where)
        if doc is None:
            missing.append((name, where))
        else:
            score_no_minor(REPO / where / name, doc)
    for name, where in missing:
        print(missing_line(f"minor {name}", where))


if __name__ == "__main__":
    main()
