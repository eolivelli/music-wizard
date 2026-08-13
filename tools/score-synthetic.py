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

Usage:  python3 tools/score-synthetic.py [--jar mw-cli/target/mw.jar]
"""

import argparse
import sys
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
samples = import_module("score-samples")

REPO = Path(__file__).resolve().parent.parent
CORPUS = REPO / "synthetic_samples"


def parse_spec(path: Path) -> dict:
    """Headers and the bar grid of a .spec.txt, as this harness needs them."""
    headers: dict = {}
    bars: list = []
    in_grid = False
    for raw in path.read_text().splitlines():
        line = raw.split("#")[0].strip()
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
    doc = samples.analyze(jar, mp3)

    spans = doc.get("chords", {}).get("chords", [])
    beats = doc.get("beatGrid", {}).get("beats", [])
    downbeats = [b["seconds"] for b in beats if b.get("downbeat")]
    if len(downbeats) < 4:
        return f"  {name}.mp3: no usable beat grid"

    bars = list(zip(downbeats, downbeats[1:]))
    shares = [samples.bar_shares(spans, a, b) for a, b in bars]
    want = spec["bars"]
    root_ok, full_ok = sequence_accuracy(shares, want)

    n = len(want)
    split = sum(1 for share in shares[:n] if len(share) > 1)
    want_key = spec["headers"].get("key")
    got_key = named_key(doc)
    key_verdict = "OK" if got_key == want_key else f"{got_key or 'none'} WRONG"
    return (f"  {name}.mp3: bars={len(shares)}/{n}"
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
