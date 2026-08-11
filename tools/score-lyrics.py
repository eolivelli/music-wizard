#!/usr/bin/env python3
"""Scores the lyric words MW carries against the file they were read from.

Nothing transcribes lyrics from audio yet (#314), so the only lyric input is a
supplied LRC and this is a **loop-closure gate**: an LRC in, `analyze`,
`render`, MW's words back out of `score/score.json`, scored against that same
LRC. Both columns are expected to read zero. That is the point of it -- what it
catches is the day they stop: a dropped, duplicated or reordered line, a word
lost to a tokenizer disagreement, a stated onset moved by the offset sign or the
sort.

**It sees line starts and nothing else.** Both columns are functions of the
onsets the file states, and a leading run's start is its line's start, so every
rule that decides where a line *ends* -- the break heuristic, the plausible
length, the recording bound -- is invisible to it. A regression that stretched a
line over the instrumental after it (#323) would not move either column by a
millisecond. #361 is the end-and-coverage column that would see it.

**Word error and onset error are reported separately** (#307). They fail for
different reasons -- one says the words are wrong, the other says they are in
the wrong place -- and a single number hides which.

Only onsets the file *states* are scored. MW's within-line word onsets are
apportioned by syllable count (`LrcLyrics.spread`), so re-deriving them here
would either copy that arithmetic, which measures nothing, or diverge from it,
which measures Python against Java. So an anchor is the first word of each run
the file times: a line's own `[mm:ss.xx]`, or a word's `<mm:ss.xx>`. A useful
consequence is that these numbers do not depend on `--lyrics-language` or on
the hyphenation patterns at all -- changing `hyph-it.pat.txt` moves every
within-line onset and moves neither column.

When #314 lands, the `.lrc` stops being an input and becomes only the truth
file: `analyze` is no longer given `--lyrics`, `SOURCE` below flips, and the
printed `source lrc` becomes `source audio` so the committed baseline itself
records the day the numbers changed meaning. The table, the columns, the
normalisation and the alignment are unchanged by that.

Ad-hoc use, against any recording and LRC without touching the baseline:

    tools/score-lyrics.py --file uncommitted/generale.mp3 \\
        --lrc uncommitted/generale.lrc --language it

That line is keyed so `premerge.sh` cannot see it, because a file with no
licence reaching its words is not ground truth and must not become a baseline
row (#347).

Like its siblings, this exits early when the jar is missing rather than running
without one -- a harness that cannot measure anything must not report that
nothing moved.
"""

import argparse
import json
import re
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# What is scored, and where its truth comes from. One row per recording:
# (audio, lrc, language). Paths are repo-relative and carry their directory --
# unlike the chord harnesses, whose benchmarks are all under samples/, this one
# also scores a cleared candidate staged in uncommitted/ while it waits for the
# ear that promotes it (docs/phone-to-corpus.md).
#
# The lyric right and the recording right are separate and both must be clear
# before a row is added here; uncommitted/list.txt says which is which per file.
LYRICS = {
    "sere-doltremare.mp3": (
        "uncommitted/sere-doltremare.mp3",
        "uncommitted/sere-doltremare.lrc",
        "it",
    ),
}

# The line main() prints above the rows. It holds no ".mp3:", which is what
# keeps premerge.sh from reading it as a row; the Keying tests execute that.
PREAMBLE = ("lyric words MW carries, against the file they were read from"
            " (nothing transcribes them from audio yet, #314):")

# Flipped by #314, when MW starts producing lyrics from the audio instead of
# being handed them. Printed in every line, so the baseline says which loop it
# measured.
SOURCE = "lrc"

# LrcLyrics' own two, transcribed: three-digit minutes, either separator.
LINE_TAG = re.compile(r"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")
WORD_TAG = re.compile(r"<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>")
ID_TAG = re.compile(r"\[([a-zA-Z#][a-zA-Z0-9_#]*):(.*)]")

# Java's \s is ASCII-only where Python's is not, and LrcLyrics splits words on
# \\s+. A non-breaking space is one token to it and would be two to us -- which
# on a subtitle track, where NBSP is common, would invent an insertion per
# occurrence and hold the WER permanently above zero.
ASCII_SPACE = re.compile(r"[ \t\n\x0b\f\r]+")

# Character.isWhitespace, which is what String.strip() and isBlank() use. It is
# Unicode-aware -- so not the set above -- but it excludes the three
# *non-breaking* spaces that Python's str.strip() removes. Getting this wrong is
# not academic: a stray U+00A0 before a line tag makes Java drop the whole line
# in silence, and a truth side using str.strip() would keep it and report the
# line as deletions on a loop that closed correctly.
JAVA_SPACE = frozenset(
    "\t\n\x0b\f\r\x1c\x1d\x1e\x1f "
    "\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006"
    "\u2008\u2009\u200a\u2028\u2029\u205f\u3000")
# U+00A0, U+2007 and U+202F are deliberately absent: they are the
# non-breaking ones, and Character.isWhitespace says false for all three.


def jstrip(text: str) -> str:
    """Java's String.strip()."""
    return text.strip("".join(JAVA_SPACE))


def jblank(text: str) -> bool:
    """Java's String.isBlank()."""
    return all(character in JAVA_SPACE for character in text)


# Java's \R.
LINE_BREAK = re.compile("\\r\\n|[\\n\\x0b\\f\\r\\x85\\u2028\\u2029]")

# Stripped from a token's ends only. The apostrophe is deliberately absent and
# the token is never split on one: Italian elides constantly -- c'è, l'amore,
# cinquant'anni -- and LrcLyrics splits on whitespace, so one word to it must
# stay one word here or every elision mints a phantom insertion.
EDGE_PUNCTUATION = ",.!?;:\"“”«»()[]{}…—–-·"
# Accents are NOT folded. e/è and perché/perche are different Italian words, and
# folding them would score a wrong word as a right one.
APOSTROPHES = {"’": "'", "ʼ": "'", "´": "'", "`": "'"}


def lrc_seconds(minutes: str, seconds: str, fraction: str | None) -> float:
    """LrcLyrics.secondsOf: the fraction is scaled by its own width, so .5, .50
    and .500 are all half a second."""
    value = int(minutes) * 60 + int(seconds)
    if fraction:
        value += int(fraction) / 10 ** len(fraction)
    return value


def normalize(token: str) -> str:
    """The one normalisation, applied identically to both sides."""
    token = unicodedata.normalize("NFC", token)
    for variant, plain in APOSTROPHES.items():
        token = token.replace(variant, plain)
    return token.strip(EDGE_PUNCTUATION).casefold()


def truth_tokens(text: str) -> tuple[list[str], dict[int, float], bool]:
    """The words the LRC states, their stated onsets, and whether it times words.

    Returns (normalised tokens, {token index: onset seconds}, word_level). The
    decomposition mirrors LrcLyrics.parse and LrcLyrics.wordsOf: the same BOM
    strip, the same [offset:] sign, the same repeated-tag expansion, the same
    sort, and one anchor per timed run.
    """
    text = text.removeprefix("\ufeff")
    offset = 0.0
    timed: list[tuple[float, str]] = []

    for raw in LINE_BREAK.split(text):
        line = jstrip(raw)
        if not line:
            continue
        stated = offset_of(line)
        if stated is not None:
            offset = stated
            continue
        starts: list[float] = []
        consumed = 0
        while True:
            tag = LINE_TAG.search(line, consumed)
            if tag is None or not jblank(line[consumed:tag.start()]):
                break
            starts.append(lrc_seconds(*tag.group(1, 2, 3)))
            consumed = tag.end()
        if not starts:
            continue
        body = jstrip(line[consumed:])
        for start in starts:
            timed.append((start, body))

    timed.sort(key=lambda entry: entry[0])

    word_level = False
    tokens: list[str] = []
    anchors: dict[int, float] = {}
    for start, body in timed:
        if jblank(body):
            # A timestamp with no text clears the display. It is not a line; it
            # ends the one before it, which is why it carries no token.
            continue
        line_start = max(0.0, start - offset)
        # Runs, exactly as wordsOf cuts them: the leading run is anchored by the
        # line, each <tag> opens another. Anchors are the *stated* values, left
        # unclamped -- LrcLyrics clamps a tag into its line's span, and this is
        # what makes that clamp visible as onset error rather than invisible.
        at = line_start
        cursor = 0
        for tag in WORD_TAG.finditer(body):
            chunk = body[cursor:tag.start()]
            if not jblank(chunk):
                open_run(chunk, at, tokens, anchors)
            at = max(0.0, lrc_seconds(*tag.group(1, 2, 3)) - offset)
            word_level = True
            cursor = tag.end()
        tail = body[cursor:]
        if not jblank(tail):
            open_run(tail, at, tokens, anchors)

    return tokens, anchors, word_level


def open_run(chunk: str, at: float, tokens: list[str], anchors: dict[int, float]) -> None:
    """Appends a run's words, anchoring the first of them.

    A token that normalises to nothing -- a lone dash opening a line of dialogue,
    a leading ellipsis, both routine in a subtitle track -- is kept rather than
    dropped. LrcLyrics keeps it and gives it a share of the line, so dropping it
    here would leave the run's stated onset sitting on the *next* word and report
    an onset error on a loop that closed correctly. Kept, it normalises to the
    empty string on both sides and pairs with itself.
    """
    words = [w for w in ASCII_SPACE.split(jstrip(chunk)) if not jblank(w)]
    if words:
        anchors[len(tokens)] = at
        tokens.extend(normalize(w) for w in words)


def offset_of(line: str) -> float | None:
    """LrcLyrics.offsetOf. Milliseconds, and a positive value is subtracted --
    the tag's sign is a genuinely ambiguous corner of the format, so it is
    transcribed rather than reasoned about."""
    tag = ID_TAG.fullmatch(line)
    if tag is None or tag.group(1).lower() != "offset":
        return None
    return java_double(jstrip(tag.group(2)).replace("+", ""))


def java_double(text: str) -> float | None:
    """Double.parseDouble, in milliseconds, or None where Java would throw.

    Not float(): Java takes a trailing type suffix (`100d`) and rejects the digit
    separators Python accepts (`1_0` reads as ten here and throws there). Either
    would move every anchor in the file by a tenth of a second or more. NaN and
    the infinities parse in both and are refused afterwards, as LrcLyrics refuses
    them -- a non-finite shift reaches LyricWord's constructor otherwise.
    """
    if "_" in text:
        return None
    if len(text) > 1 and text[-1] in "dDfF":
        text = text[:-1]
    try:
        value = float(text) / 1000.0
    except ValueError:
        return None
    return value if -float("inf") < value < float("inf") else None


def align(truth: list[str], hypothesis: list[str]) -> list[tuple[int | None, int | None]]:
    """Levenshtein pairs, (truth index, hypothesis index), either side None.

    The backtrace preference is fixed -- match, then substitution, then deletion,
    then insertion -- so a tie cannot make the committed baseline flap between
    runs.
    """
    n, m = len(truth), len(hypothesis)
    cost = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        cost[i][0] = i
    for j in range(1, m + 1):
        cost[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            same = truth[i - 1] == hypothesis[j - 1]
            cost[i][j] = min(cost[i - 1][j - 1] + (0 if same else 1),
                             cost[i - 1][j] + 1,
                             cost[i][j - 1] + 1)

    pairs: list[tuple[int | None, int | None]] = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and truth[i - 1] == hypothesis[j - 1] \
                and cost[i][j] == cost[i - 1][j - 1]:
            i, j = i - 1, j - 1
            pairs.append((i, j))
        elif i > 0 and j > 0 and cost[i][j] == cost[i - 1][j - 1] + 1:
            i, j = i - 1, j - 1
            pairs.append((i, j))
        elif i > 0 and cost[i][j] == cost[i - 1][j] + 1:
            i -= 1
            pairs.append((i, None))
        else:
            j -= 1
            pairs.append((None, j))
    pairs.reverse()
    return pairs


def word_error(pairs, truth: list[str], hypothesis: list[str]) -> tuple[int, int, int, float]:
    """(substitutions, deletions, insertions, percent of the truth's words).

    Not clamped at 100%: a hypothesis holding the song twice is legitimately
    above it, and clamping would print that as though nothing had been emitted.
    """
    substitutions = deletions = insertions = 0
    for i, j in pairs:
        if i is None:
            insertions += 1
        elif j is None:
            deletions += 1
        elif truth[i] != hypothesis[j]:
            substitutions += 1
    errors = substitutions + deletions + insertions
    percent = 100.0 * errors / len(truth) if truth else 0.0
    return substitutions, deletions, insertions, percent


def onset_error(pairs, anchors: dict[int, float], starts: list[float]):
    """(median, max, matched, total) over the onsets the file states, or None
    for the first two when nothing matched.

    Anchors are paired through the word alignment rather than by index, so a
    lost line cannot shift every onset after it; an anchor whose word was
    deleted is counted unmatched and reported beside the median it did not
    enter, rather than quietly leaving the sample.
    """
    matched_to = {i: j for i, j in pairs if i is not None and j is not None}
    errors = sorted(abs(starts[matched_to[i]] - at)
                    for i, at in anchors.items() if i in matched_to)
    if not errors:
        return None, None, 0, len(anchors)
    middle = len(errors) // 2
    median = (errors[middle] if len(errors) % 2
              else (errors[middle - 1] + errors[middle]) / 2.0)
    return median, errors[-1], len(errors), len(anchors)


def columns(truth, hypothesis, anchors, starts, word_level: bool) -> str:
    """The two columns and what qualifies them, without the leading key."""
    pairs = align(truth, hypothesis)
    substitutions, deletions, insertions, percent = word_error(pairs, truth, hypothesis)
    median, worst, matched, total = onset_error(pairs, anchors, starts)
    onset = ("no anchors matched" if median is None
             else f"median {median:.3f}s, max {worst:.3f}s")
    return (f"words {len(truth)}"
            f"  wer {percent:.1f}% (sub {substitutions}, del {deletions}, ins {insertions})"
            f"  onset {onset}"
            f"  anchors {matched}/{total} {'word-level' if word_level else 'line-level'}"
            f"  source {SOURCE}")


def score_line(name: str, truth, hypothesis, anchors, starts, word_level: bool) -> str:
    """A baselined row. Keyed `<name>.mp3:`, which is what premerge.sh gates on."""
    return f"  {name}: {columns(truth, hypothesis, anchors, starts, word_level)}"


def missing_line(name: str, where: str) -> str:
    return f"  {name}: not present (local-only; see {where} to fetch)"


def adhoc_line(name: str, truth, hypothesis, anchors, starts, word_level: bool) -> str:
    """A row for a file that is not ground truth. Deliberately keyed so it holds
    no `.mp3:` -- premerge.sh filters on that substring, so this cannot drift
    into looking gated when nothing gates it."""
    return (f"  ad-hoc {name} (not ground truth, not baselined): "
            f"{columns(truth, hypothesis, anchors, starts, word_level)}")


def run(jar: Path, mp3: Path, lrc: Path, language: str, workspace: Path) -> dict:
    """init, analyze with the lyrics, render them, and return the score document.

    `render` is run for its exit code, not its output. An `analyze` that failed
    to read the LRC warns on stderr and still exits 0, and render is where that
    becomes an error -- so this catches an empty hypothesis rather than scoring
    it as a clean one.
    """
    for args in (["init", str(mp3), "--workspace", str(workspace)],
                 ["analyze", str(workspace), "--lyrics", str(lrc),
                  "--lyrics-language", language],
                 ["render", str(workspace), "--parts", "lyrics"]):
        result = subprocess.run(["java", "-jar", str(jar), *args],
                                capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"mw {args[0]} failed on {mp3.name}:\n{result.stdout}{result.stderr}")
    return json.loads((workspace / "score" / "score.json").read_text(encoding="utf-8"))


def words_of(document: dict, name: str) -> tuple[list[str], list[float]]:
    """MW's words and their onsets, normalised the same way the truth is."""
    lines = document.get("lyrics", {}).get("lines", [])
    words = [word for line in lines for word in line["words"]]
    if not words:
        # Only reachable while the lyrics are an input. After #314 an empty
        # transcription is a result -- a full deletion -- and is scored.
        sys.exit(f"{name}: analysis carried no lyric words; the LRC was not read")
    return ([normalize(word["text"]) for word in words],
            [word["startSeconds"] for word in words])


def truth_of(lrc: Path, name: str) -> tuple[list[str], dict[int, float], bool]:
    """The truth side, refusing a file that states no words rather than
    dividing by its zero and printing a clean-looking `wer 0.0%`."""
    tokens, anchors, word_level = truth_tokens(lrc.read_text(encoding="utf-8"))
    if not tokens:
        sys.exit(f"{name}: {lrc} states no timed words, so there is nothing to score")
    return tokens, anchors, word_level


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--file", help="score this recording instead of the table")
    parser.add_argument("--lrc", help="its lyrics; required with --file")
    parser.add_argument("--language", default="it", help="its language tag")
    args = parser.parse_args()
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"build first: mvn -B -DskipTests package   (missing {jar})")

    print(PREAMBLE)

    if args.file:
        if not args.lrc:
            sys.exit("--file needs --lrc: there is nothing to score it against")
        mp3, lrc = Path(args.file), Path(args.lrc)
        truth, anchors, word_level = truth_of(lrc, mp3.name)
        with tempfile.TemporaryDirectory() as tmp:
            document = run(jar, mp3, lrc, args.language, Path(tmp) / "w.mwz")
        hypothesis, starts = words_of(document, mp3.name)
        print(adhoc_line(mp3.name, truth, hypothesis, anchors, starts, word_level))
        return

    missing = []
    for name, (audio, lyrics, language) in LYRICS.items():
        mp3, lrc = REPO / audio, REPO / lyrics
        if not mp3.exists() or not lrc.exists():
            missing.append((name, Path(audio).parent / "list.txt"))
            continue
        truth, anchors, word_level = truth_of(lrc, name)
        with tempfile.TemporaryDirectory() as tmp:
            document = run(jar, mp3, lrc, language, Path(tmp) / "w.mwz")
        hypothesis, starts = words_of(document, name)
        print(score_line(name, truth, hypothesis, anchors, starts, word_level))
    for name, where in missing:
        print(missing_line(name, where))


if __name__ == "__main__":
    main()
