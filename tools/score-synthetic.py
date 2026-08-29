#!/usr/bin/env python3
"""Scores mw against the synthetic corpus, whose ground truth is exact.

Every package in synthetic_samples/ is scored against its own .spec.txt: the
spec compiled the MIDI the audio was rendered from, so unlike samples/ the
grid is truth by construction and every file is committed. That is what lets
this harness run in CI end to end — the workflow diffs its output against
tools/baselines/score-synthetic.txt, and tools/premerge.sh diffs it locally
like its siblings (#447). Any movement fails; an intended improvement
regenerates the baseline in the same PR.

The scoring rules are imported from score-samples.py — one rule, so the two
harnesses cannot come to disagree about what counts as a correct bar — with
one deliberate difference: bars are aligned as a sequence, first downbeat to
first bar, with no best-rotation search. The spec says where bar one is; a
reading that is right up to rotation is wrong here.

Every scored row states the meter it barred the package in against the spec's
own. On a package stating 4/4 the column is a control -- one that leaves 4/4 has
had its bar lines moved by something no spec asked for -- and on the packages
#702 added in 3/4, 6/8 and 12/8 it is the reading itself, against a meter the
spec compiled the MIDI from. One of those is expected to read wrong today and
its own issue says why.

Every scored row states the tempo as a ratio against the spec's own (#453). A
melody-only package states none: it returns before a grid is read at all. The
spec compiled the MIDI, so that tempo is exact, and a grid running at twice or
half it is the one failure this corpus can see that samples/ cannot — where the
truth is confirmed by ear, a doubled reading is an argument, and here it is
arithmetic. It rides the same baseline diff as the bar columns, so a beat
change that trades tempo for bars can no longer look like a clean win.

Usage:  python3 tools/score-synthetic.py [--jar mw-cli/target/mw.jar]
"""

import argparse
import re
import sys
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
samples = import_module("score-samples")

REPO = Path(__file__).resolve().parent.parent
CORPUS = REPO / "synthetic_samples"


def parse_spec(path: Path) -> dict:
    """Headers and the bar grid of a .spec.txt, as this harness needs them."""
    return parse_spec_text(path.read_text())


def parse_spec_text(text: str) -> dict:
    headers: dict = {}
    bars: list = []
    in_grid = False
    for raw in text.splitlines():
        # A '#' opens a comment only at line start or after whitespace — the
        # one inside C#m is an accidental. SpecParser.java applies the same
        # rule; the two parsers read the same format and must agree on it.
        line = re.sub(r"(?:^|\s)#.*$", "", raw).strip()
        if not line:
            continue
        if not in_grid:
            if line == "bars:":
                in_grid = True
            else:
                name, _, value = line.partition(":")
                headers[name.strip()] = value.strip()
        else:
            # 'X-Y' is a bar holding both chords; either counts, exactly as a
            # split bar is credited against samples/list.txt.
            bars.extend([samples.parse_chord(c) for c in token.split("-")]
                        for token in line.split())
    return {"headers": headers, "bars": bars}


def sequence_accuracy(shares: list, want: list) -> tuple[float, float]:
    """Bars correct on root and on root+quality, aligned as a sequence.

    The same credit rules as score-samples.accuracy — a split bar contributes
    each share separately — but bar i of the estimate is scored against bar i
    of the spec and nothing else.
    """
    root_ok = full_ok = 0.0
    for index in range(min(len(shares), len(want))):
        acceptable = want[index]
        for got, credit in shares[index].items():
            if got is not None and any(got[0] == w[0] for w in acceptable):
                root_ok += credit
                if any(got == w for w in acceptable):
                    full_ok += credit
    return root_ok, full_ok


#: `analyze`'s tempo line, in either form `AnalyzeCommand.formatTempo` writes.
#: In a meter counted in something other than a quarter it prints the counted
#: tempo first and the quarter tempo in the parentheses, so the second group is
#: preferred where it exists: the spec's `tempo:` is quarter beats per minute,
#: which is what every value downstream of the beat grid is counted in.
#:
#: Anchored at both ends. The MIDI path prints a tempo through `statedTempo`,
#: which for a file that changes tempo reads "140.0 BPM at the start, changed 3
#: times later" -- an unanchored pattern takes the 140.0 off that and states it
#: as though it were constant. No package is analysed from MIDI today, so the
#: anchor is what makes that arrive as `tempo none` rather than as a number.
TEMPO_LINE = re.compile(
    r"^Tempo\s+([\d.]+) BPM(?: \(([\d.]+) quarter notes/min\))?$")


def printed_tempo(printed: str) -> float | None:
    """The tempo MW reported, in quarter beats per minute.

    Read off `analyze`'s own output rather than derived again here.
    `Score.estimatedTempo()` prefers a supplied correction, exempts a lead-in
    and otherwise takes the grid's steady tempo; the beat grid is in
    score.json, so a harness could reproduce all of that, and the reason not to
    is that it would then hold a second definition of a number the engraved
    chart already prints.
    """
    for line in printed.splitlines():
        found = TEMPO_LINE.match(line.strip())
        if found:
            return float(found.group(2) or found.group(1))
    return None


def tempo_verdict(got: float | None, want: str | None) -> str:
    """The tempo column: what was read, against what the spec compiled.

    A tempo that moves alone, with no bar column beside it moving, can be a
    rounding boundary crossed rather than a grid that changed; #533 has the
    margins.
    """
    if want is None:
        return "tempo unstated"
    if got is None:
        return f"tempo none/{want}"
    return f"tempo {got:.1f}/{want} (x{got / float(want):.2f})"


def named_key(doc: dict) -> str | None:
    """The key the run named, spelled as a spec header spells it."""
    keys = doc.get("keys", [])
    if not keys:
        return None
    key = max(keys, key=lambda k: k["endSeconds"] - k["startSeconds"])
    tonic = key["tonic"]
    return (tonic["letter"]
            + samples.ACCIDENTAL_SIGN[tonic.get("accidental", "NATURAL")]
            + " " + key["mode"].lower())


def score_package(jar: Path, spec_file: Path) -> str:
    name = spec_file.name.removesuffix(".spec.txt")
    mp3 = spec_file.with_name(name + ".mp3")
    if not mp3.exists():
        return f"  {name}.mp3: missing — regenerate with tools/music-teacher/generate.sh"
    spec = parse_spec(spec_file)
    # A package with nothing playing the grid carries no evidence for it: the
    # melody was generated over those chords, but a melody states a chord the
    # way a single voice states a fugue. Scoring it would put four rows of
    # noise in the baseline that premerge and CI then defend. See
    # synthetic_samples/README.md and tools/score-melody.py, which is what
    # those packages are measured by.
    if spec["headers"].get("accompaniment") == "none":
        return f"  {name}.mp3: melody only; chords not scored"
    doc, printed = samples.analyze_with_output(jar, mp3)
    tempo = tempo_verdict(printed_tempo(printed), spec["headers"].get("tempo"))

    spans = doc.get("chords", {}).get("chords", [])
    beats = doc.get("beatGrid", {}).get("beats", [])
    downbeats = [b["seconds"] for b in beats if b.get("downbeat")]
    if len(downbeats) < 4:
        # With the tempo, because a grid too short to score is often a grid
        # that ran at the wrong multiple of the beat, and that says so.
        return f"  {name}.mp3: no usable beat grid  {tempo}"

    bars = list(zip(downbeats, downbeats[1:]))
    shares = [samples.bar_shares(spans, a, b) for a, b in bars]
    want = spec["bars"]
    root_ok, full_ok = sequence_accuracy(shares, want)

    n = len(want)
    split = sum(1 for share in shares[:n] if len(share) > 1)
    want_key = spec["headers"].get("key")
    got_key = named_key(doc)
    key_verdict = "OK" if got_key == want_key else f"{got_key or 'none'} WRONG"
    # The spec's own default, which is what SpecParser substitutes for a spec
    # that names no meter.
    want_meter = spec["headers"].get("meter", "4/4")
    got_meter = samples.barred_meter(doc)
    meter_verdict = "OK" if got_meter == want_meter else f"{got_meter or 'none'} WRONG"
    return (f"  {name}.mp3: bars={len(shares)}/{n}  {tempo}  meter {meter_verdict}"
            f"  root {root_ok:.1f}/{n} ({100 * root_ok / n:.1f}%)"
            f"  root+quality {full_ok:.1f}/{n} ({100 * full_ok / n:.1f}%)"
            f"  split {split}"
            f"  key {key_verdict}")


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
    print("Synthetic corpus, bar-by-bar against each package's own spec")
    print("(sequence-aligned: no rotation credit; see the module docstring)")
    for spec_file in specs:
        print(score_package(jar, spec_file))


if __name__ == "__main__":
    main()
