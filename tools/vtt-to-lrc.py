#!/usr/bin/env python3
"""Turn a WebVTT subtitle track into the LRC that `analyze --lyrics` reads.

An uploader's own manual subtitle track is the cheapest timed lyric ground
truth a permissively licensed recording can carry, and it is VTT. Two things
have to survive the conversion:

  * Cue end times. VTT states them and LRC has no field for one, so a cue that
    is not immediately followed by another is closed with a bare timestamp --
    the "clear the display" tag, which `LrcLyrics` reads as exactly that end
    (see its javadoc). Without it every line before an instrumental stretch
    would be cut by the parser's break heuristic instead of by the time the
    subtitler actually wrote down, which on a track chosen for its instrumental
    intro and outro is the one thing worth keeping.
  * Multi-line cues. A cue wrapped for display is one lyric line, so its rows
    are joined with a space rather than becoming two lines an ear never sang.

Cue text is copied verbatim otherwise: no case, accent or punctuation change.
The words are ground truth and normalising them here would silently rewrite it.

    tools/vtt-to-lrc.py in.vtt > out.lrc
    tools/vtt-to-lrc.py in.vtt --drop-cues 1 > out.lrc   # 1-based, e.g. a title card

`--drop-cues` exists so a caption that is not sung -- a title card, a credit --
can be removed by a recipe that is written down, rather than by a hand edit
nobody can reproduce.
"""

import argparse
import re
import sys

# HH:MM:SS.mmm or MM:SS.mmm, the two forms WebVTT allows.
TIMING = re.compile(
    r"^(?:(\d+):)?(\d{1,2}):(\d{2})\.(\d{3})\s*-->\s*"
    r"(?:(\d+):)?(\d{1,2}):(\d{2})\.(\d{3})"
)


def seconds(hours: str | None, minutes: str, secs: str, millis: str) -> float:
    return int(hours or 0) * 3600 + int(minutes) * 60 + int(secs) + int(millis) / 1000


def cues(text: str) -> list[tuple[float, float, str]]:
    """Every timed cue, in file order, as (start, end, one-line text)."""
    out = []
    lines = text.replace("﻿", "").splitlines()
    i = 0
    while i < len(lines):
        m = TIMING.match(lines[i].strip())
        if not m:
            i += 1
            continue
        start = seconds(*m.group(1, 2, 3, 4))
        end = seconds(*m.group(5, 6, 7, 8))
        i += 1
        body = []
        while i < len(lines) and lines[i].strip():
            body.append(lines[i].strip())
            i += 1
        if body:
            out.append((start, end, " ".join(body)))
    return out


def tag(t: float) -> str:
    """LRC's [mm:ss.xx]. Minutes are not wrapped at 60: a 78-minute file is one
    line saying 78, not one saying 18."""
    minutes, rest = divmod(round(t * 100), 6000)
    return f"[{minutes:02d}:{rest // 100:02d}.{rest % 100:02d}]"


def convert(text: str, drop: set[int]) -> str:
    kept = [c for n, c in enumerate(cues(text), start=1) if n not in drop]
    out = []
    for i, (start, end, body) in enumerate(kept):
        out.append(f"{tag(start)}{body}")
        # Only where the stated end is not already implied by the next cue.
        # Rounded to the printed resolution first, so two timestamps that print
        # the same do not produce a zero-length clear tag.
        nxt = kept[i + 1][0] if i + 1 < len(kept) else None
        if nxt is None or tag(end) != tag(nxt):
            out.append(tag(end))
    return "\n".join(out) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("vtt")
    parser.add_argument(
        "--drop-cues",
        default="",
        help="comma-separated 1-based cue numbers to leave out (title cards, credits)",
    )
    args = parser.parse_args()
    drop = {int(n) for n in args.drop_cues.split(",") if n.strip()}

    text = open(args.vtt, encoding="utf-8").read()
    total = len(cues(text))
    unknown = sorted(n for n in drop if n < 1 or n > total)
    if unknown:
        sys.exit(f"--drop-cues names {unknown}, but the file has {total} cues")
    sys.stdout.write(convert(text, drop))
    print(f"{total - len(drop)} cues written ({total} read)", file=sys.stderr)


if __name__ == "__main__":
    main()
