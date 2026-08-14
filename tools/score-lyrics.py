#!/usr/bin/env python3
"""Scores the lyric words MW carries against a reference text, on two loops.

`--source lrc` (the default) is the **loop-closure gate**: an LRC in,
`analyze`, `render`, MW's words back out of `score/score.json`, scored against
that same LRC. The word and onset columns are expected to read zero: MW carries
those values through from the file. **The line-end column is not**, because MW
does not carry a line's end through -- it decides one, from the gap to the next
entry, the typical line length and the recording's own end. So that column is a
measurement of the deciding, on a loop where everything else is a copy.

What the zeros catch is the day they stop: a dropped, duplicated or reordered
line, a word lost to a tokenizer disagreement, a stated onset moved by the
offset sign or the sort.

`--source asr` is the **transcription measurement** (#391): the same
recordings, `analyze --lyrics-language` with no lyrics file, the words MW
heard scored against the same LRC as truth. Its columns are honest quality
numbers, not zeros. The subprocess runs with its own empty config home and
the repo's own native build, so a machine's config cannot sway a committed
baseline. How each of analyze's reported outcomes is treated -- scored,
skipped with the reason, or a red gate -- is the classification block's own
story, told beside the code that implements it.

**The three columns fail for different reasons, which is why they are three.**
Word error says the words are wrong; onset error says they start in the wrong
place (#307); line-end error says they stop in the wrong place (#361). A single
number hides which.

The first two see where runs *start*, which on a line-level file is where lines
start -- so the break heuristic, the plausible length and the recording bound
were invisible to both, none of them being able to move a line's own first word
tag, and a regression stretching a line over the instrumental after it (#323)
moved neither by a millisecond. The end column is what sees that, and it is
scored against the one place a lyric file states where a line stops: a
timestamp with no text after it, which clears the display. `tools/vtt-to-lrc.py`
writes one per cue.

**Still not seen: where a word falls inside its line.** On a line-level truth
there is nothing to score it against, so a change to how a line's words are
distributed across it moves no column here. The engraved page is the only
instrument for that today.

A word tag outside its line's span is a different matter: it is clamped into
that span, so on a word-tagged file whatever set the end reaches a run start and
shows up as onset error.

Only times the file *states* are scored, onsets and ends alike. MW's
within-line word onsets are
apportioned by syllable count (`LrcLyrics.spread`), so re-deriving them here
would either copy that arithmetic, which measures nothing, or diverge from it,
which measures Python against Java. So an anchor is the first word of each run
the file times: a line's own `[mm:ss.xx]`, or a word's `<mm:ss.xx>`. A useful
consequence is that these numbers do not depend on `--lyrics-language` or on
the hyphenation patterns at all -- changing `hyph-it.pat.txt` moves every
within-line onset and moves neither column.

Ad-hoc use, against any recording and LRC without touching the baseline:

    tools/score-lyrics.py --file uncommitted/generale.mp3 \\
        --lrc uncommitted/generale.lrc --language it

That line is keyed so `premerge.sh` cannot see it, because an ad-hoc run is a
one-off reading and a baseline row is a standing claim that everyone's premerge
has to reproduce. Add a row below when a recording is meant to be scored on
every run.

Like its siblings, this exits early when the jar is missing rather than running
without one -- a harness that cannot measure anything must not report that
nothing moved.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path
from typing import NamedTuple

REPO = Path(__file__).resolve().parent.parent

# What is scored, and where its truth comes from. One row per recording:
# (audio, lrc, language). Paths are repo-relative and carry their directory --
# unlike the chord harnesses, whose benchmarks are all under samples/, this one
# also scores a cleared candidate staged in uncommitted/ while it waits for the
# ear that promotes it (docs/phone-to-corpus.md).
#
# A row needs the recording and its lyrics present on the machine, and the
# lyrics need not carry a licence -- they are read to measure MW, never
# redistributed, and CLAUDE.md carries that rule. What a row does need is a
# language the hyphenator has patterns for; uncommitted/list.txt says which is
# which per file.
LYRICS = {
    "sere-doltremare.mp3": (
        "uncommitted/sere-doltremare.mp3",
        "uncommitted/sere-doltremare.lrc",
        "it",
    ),
}

# The line main() prints above the rows, per source. Neither holds ".mp3:",
# which is what keeps premerge.sh from reading it as a row; the Keying tests
# execute that.
PREAMBLES = {
    "lrc": ("lyric words MW carries, against the file they were read from"
            " (the supplied-lyrics loop):"),
    "asr": ("lyric words MW hears in the audio, against the same file as"
            " truth (the transcription loop, #391):"),
}

# Which loop the rows measure. Printed in every line, so the baseline says.
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

# The grammar Double.valueOf's javadoc gives, with [0-9] rather than \\d --
# Python's \\d matches Unicode digits and Java's parser does not. NaN and the
# infinities take no type suffix; the hexadecimal form's p-exponent is required.
JAVA_DOUBLE = re.compile(
    r"[+-]?(?:NaN|Infinity"
    r"|(?:(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
    r"|0[xX](?:[0-9a-fA-F]+\.?[0-9a-fA-F]*|\.[0-9a-fA-F]+)[pP][+-]?[0-9]+)"
    r"[fFdD]?)")

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


class Truth(NamedTuple):
    """What the lyric file states, in the units this harness scores."""

    tokens: list[str]
    anchors: dict[int, float]
    """{token index: stated onset}, one per timed run."""
    ends: dict[int, float]
    """{last token index of a line: the moment its display clears}."""
    word_level: bool


class Heard(NamedTuple):
    """What MW carried, cut the same way."""

    tokens: list[str]
    starts: list[float]
    line_ends: list[float]
    """Parallel to tokens: the end of the line each word belongs to."""


def truth_tokens(text: str) -> Truth:
    """The words the LRC states, their stated times, and whether it times words.

    The decomposition mirrors LrcLyrics.parse and LrcLyrics.wordsOf: the same
    BOM strip, the same [offset:] sign, the same repeated-tag expansion, the
    same sort, and one anchor per timed run.
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
    ends: dict[int, float] = {}
    open_line = False
    for start, body in timed:
        if jblank(body):
            # A timestamp with no text clears the display. It is not a line; it
            # ends the one before it, which is why it carries no token -- and
            # why it is the one place a lyric file states where a line stops.
            # Anchored on that line's last token, so it is paired through the
            # word alignment exactly as an onset is and a lost line cannot
            # shift every end after it. Only the first clear after a line
            # counts: a second states the end of nothing.
            if open_line and tokens:
                ends[len(tokens) - 1] = max(0.0, start - offset)
                open_line = False
            continue
        open_line = True
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

    return Truth(tokens, anchors, ends, word_level)


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

    float() cannot be handed the string and asked afterwards. It runs
    PyUnicode_TransformDecimalAndSpaceToASCII first, which folds every Unicode
    space *and every Unicode decimal digit* to ASCII -- so `[offset:\u00a0500]`
    and `[offset:\u0665\u0660\u0660]` parse here and throw in Java. Guarding
    the call was tried twice and let both back in, because the guard and the
    parser disagreed about what a space is. So the grammar is matched first and
    float() only ever sees a string it cannot be creative about.

    The two trims are also different, and both are Java's rather than either
    being ours: String.strip() takes Character.isWhitespace, and parseDouble
    takes everything at or below U+0020 -- which is why a leading U+0001 parses
    in Java and a leading U+00A0 does not.

    The hexadecimal form is real: parseDouble reads `0x1p10` as 1024. Rejecting
    it would hide a shift Java applied.
    """
    text = text.strip("".join(chr(c) for c in range(0x21)))
    if not JAVA_DOUBLE.fullmatch(text):
        return None
    if text[-1] in "dDfF":
        text = text[:-1]
    try:
        value = (float.fromhex(text) if "x" in text.lower() else float(text)) / 1000.0
    except OverflowError:
        # float.fromhex raises on a hex value past the double range -- a large
        # p-exponent or a long enough mantissa on its own -- where parseDouble
        # returns Infinity. An exception here would abort the run rather than
        # ignore the tag. float() does not need this: it overflows to inf.
        return None
    # As LrcLyrics refuses it: a non-finite shift otherwise reaches LyricWord's
    # constructor, out of a public parser and past the caller's read guard.
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


def end_error(pairs, ends: dict[int, float], line_ends: list[float]):
    """(median, max, matched, total) over the line ends the file states, or
    None for the first two when nothing matched.

    A third column rather than a widening of the onset one, because it fails
    for a different reason (#361). Every onset this harness scores is a value
    the file states and MW copies, so the rules deciding where a line *stops* --
    the break, the typical length, the recording bound -- moved nothing in
    either existing column, and the benchmark was chosen for its long intro and
    outro precisely to exercise them. A line stretched over the instrumental
    after it now shows here.

    MW's line end rather than the matched word's own, because that is what the
    stated clear is the counterpart of: a line's last word need not be the one
    that stops latest, and LyricLine.endSeconds is the maximum for that reason.
    """
    matched_to = {i: j for i, j in pairs if i is not None and j is not None}
    errors = sorted(abs(line_ends[matched_to[i]] - at)
                    for i, at in ends.items() if i in matched_to)
    if not errors:
        return None, None, 0, len(ends)
    middle = len(errors) // 2
    median = (errors[middle] if len(errors) % 2
              else (errors[middle - 1] + errors[middle]) / 2.0)
    return median, errors[-1], len(errors), len(ends)


def columns(truth: Truth, heard: Heard) -> str:
    """The three columns and what qualifies them, without the leading key."""
    pairs = align(truth.tokens, heard.tokens)
    substitutions, deletions, insertions, percent = word_error(
        pairs, truth.tokens, heard.tokens)
    median, worst, matched, total = onset_error(pairs, truth.anchors, heard.starts)
    onset = ("no anchors matched" if median is None
             else f"median {median:.3f}s, max {worst:.3f}s")
    end_median, end_worst, end_matched, end_total = end_error(
        pairs, truth.ends, heard.line_ends)
    end = ("none stated" if end_total == 0
           else "no ends matched" if end_median is None
           else f"median {end_median:.3f}s, max {end_worst:.3f}s")
    return (f"words {len(truth.tokens)}"
            f"  wer {percent:.1f}% (sub {substitutions}, del {deletions}, ins {insertions})"
            f"  onset {onset}"
            f"  line end {end}"
            f"  anchors {matched}/{total}"
            f" {'word-level' if truth.word_level else 'line-level'}"
            f", ends {end_matched}/{end_total}"
            f"  source {SOURCE}")


def score_line(name: str, truth: Truth, heard: Heard) -> str:
    """A baselined row. Keyed `<name>.mp3:`, which is what premerge.sh gates on."""
    return f"  {name}: {columns(truth, heard)}"


def missing_line(name: str, where: str) -> str:
    return f"  {name}: not present (local-only; see {where} to fetch)"


def native_missing_line(name: str) -> str:
    """The dependency worth checking before spending a run per row: without
    the native nothing can decode, and the check is one stat. The loop's
    other needs -- the model archives a first run fetches -- are judged from
    analyze's own report instead (see SKIPPED), so their absence skips with
    the real reason rather than being guessed at up front."""
    return (f"  {name}: not present (local-only;"
            " run tools/build-sherpa-native.sh for this loop)")


def adhoc_unavailable_line(name: str, reason: str) -> str:
    """The ad-hoc twin of unavailable_line, deliberately keyed out of the
    comparison the way every ad-hoc line is: text between the name and the
    colon, so nothing gates a one-off reading."""
    return f"  ad-hoc {name} (not measurable here): {reason[:160]}"


def unavailable_line(name: str, reason: str) -> str:
    """A row the environment could not measure, in the same skip key, with
    analyze's own first line of explanation beside it. Never baselined: a
    committed baseline that certifies absence is a defect (premerge.sh says
    so), and this text exists only on the current side of the diff."""
    return f"  {name}: not present (local-only; {reason[:160]})"


def adhoc_line(name: str, truth: Truth, heard: Heard) -> str:
    """A row for a file that is not ground truth. Deliberately keyed so it holds
    no `.mp3:` -- premerge.sh filters on that substring, so this cannot drift
    into looking gated when nothing gates it."""
    return (f"  ad-hoc {name} (not ground truth, not baselined): "
            f"{columns(truth, heard)}")


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


# Where the provider loads its native from, pinned by this harness so the row
# measures the repo's own build; built by tools/build-sherpa-native.sh.
NATIVE_LIB = REPO / "third_party/sherpa-onnx/build/lib"


# What analyze prints for the ways this loop ends, classified by what each
# one IS rather than by one shared prefix. A decode that happened is scored,
# and so is a pipeline that ran and found nothing -- no sung stretches, no
# words -- because those are results this gate exists to notice. Only an
# environment that could not run the ASR at all (model not fetchable, native
# not loadable, provider absent in this build) is a skip carrying analyze's
# own reason: scoring a could-not-run as a page of deletions would fail the
# merge gate on a machine problem dressed as a regression. And a defect --
# analyze's transcription block throwing -- fails the harness loudly rather
# than skipping: hiding our own exception behind a skip row is the exact
# inversion of the gate's job. Analyze prints all of these with exit 0, so
# the report is the only witness; a Keying test holds these literals against
# AnalyzeCommand's source so a rewording there fails before the gate lies.
SCORED = (re.compile(r"\btranscribed \d+ lyric line"),
          re.compile(r"heard no words in"),
          re.compile(r"no sung stretches found"))
SKIPPED = (re.compile(r"warning: lyrics not transcribed: "),
           re.compile(r"lyrics not transcribed: no ASR provider"))
DEFECT = re.compile(r"lyric transcription failed")


def run_asr(jar: Path, mp3: Path, language: str, workspace: Path,
            config_home: Path) -> tuple[dict | None, str | None]:
    """init and analyze with no lyrics file: the words come from the audio.

    Returns (score document, None) when the ASR ran -- an empty transcription
    is a result, a full deletion, and is scored -- or (None, reason) when the
    environment could not run it; a reported defect does not return, it ends
    the harness red. No `render` gate on this loop. The config
    home is an empty directory this harness owns, so a machine's
    ml.asrModelDirectory or provider choice cannot move a committed baseline;
    the native path is the repo's own build, passed as sherpa's property,
    which outranks any config.
    """
    environment = dict(os.environ, XDG_CONFIG_HOME=str(config_home))
    report = ""
    for args in (["init", str(mp3), "--workspace", str(workspace)],
                 ["analyze", str(workspace), "--lyrics-language", language]):
        result = subprocess.run(
            ["java", f"-Dsherpa_onnx.native.path={NATIVE_LIB}",
             "-jar", str(jar), *args],
            capture_output=True, text=True, env=environment)
        if result.returncode != 0:
            sys.exit(f"mw {args[0]} failed on {mp3.name}:\n{result.stdout}{result.stderr}")
        report = result.stdout + "\n" + result.stderr
    if DEFECT.search(report):
        # Our code threw and analyze survived it; the gate must go red with
        # the reason, not quietly skip our own exception.
        sys.exit(f"{mp3.name}: analyze reported a transcription defect:\n"
                 + first_line(report, DEFECT))
    if any(marker.search(report) for marker in SCORED):
        document = json.loads(
            (workspace / "score" / "score.json").read_text(encoding="utf-8"))
        return document, None
    if any(marker.search(report) for marker in SKIPPED):
        for marker in SKIPPED:
            if marker.search(report):
                return None, first_line(report, marker).removeprefix("warning: ").strip()
    sys.exit(f"{mp3.name}: analyze reported no transcription outcome at all:\n"
             + report[-500:])


def first_line(report: str, marker: re.Pattern) -> str:
    for line in report.splitlines():
        if marker.search(line):
            return line.strip()
    return ""


def rejoined(words: list[dict]) -> list[dict]:
    """Syllables put back into the words they were split from.

    MW carries a lyric word as the syllables it is sung on once alignment has
    measured them (#414), because that is what the engraved sheet places. This
    harness scores words against a lyric file's words, so the two have to be
    counted the same way: a run joined by `hyphenatedToNext` is one word,
    taking the first syllable's onset. Scoring the syllables instead would
    report a substitution and a fistful of insertions for every word MW got
    exactly right.

    A word that was never split has the flag false and passes through, so this
    is the identity on lyrics that were not aligned.
    """
    out: list[dict] = []
    for word in words:
        if out and out[-1].get("hyphenatedToNext"):
            out[-1] = dict(out[-1],
                           text=out[-1]["text"] + word["text"],
                           # The maximum, as LyricLine.endSeconds is: sung spans
                           # overlap, so the last piece need not end last.
                           endSeconds=max(out[-1]["endSeconds"], word["endSeconds"]),
                           hyphenatedToNext=word.get("hyphenatedToNext", False))
        else:
            out.append(word)
    return out


def words_of(document: dict, name: str, source: str) -> Heard:
    """MW's words, their onsets, and their lines' ends, normalised the same way
    the truth is."""
    lines = document.get("lyrics", {}).get("lines", [])
    words: list[dict] = []
    line_ends: list[float] = []
    for line in lines:
        joined = rejoined(line["words"])
        if not joined:
            continue
        # The maximum, as LyricLine.endSeconds is, and carried per word so the
        # end survives the word alignment that pairs the two sides.
        end = max(word["endSeconds"] for word in joined)
        words.extend(joined)
        line_ends.extend([end] * len(joined))
    if not words and source == "lrc":
        # On the lrc loop the lyrics were an input, so nothing carried means
        # nothing was read. On the asr loop an empty transcription is a
        # result -- a full deletion -- and is scored as one.
        sys.exit(f"{name}: analysis carried no lyric words; the LRC was not read")
    return Heard([normalize(word["text"]) for word in words],
                 [word["startSeconds"] for word in words], line_ends)


def truth_of(lrc: Path, name: str) -> Truth:
    """The truth side, refusing a file that states no words rather than
    dividing by its zero and printing a clean-looking `wer 0.0%`."""
    truth = truth_tokens(lrc.read_text(encoding="utf-8"))
    if not truth.tokens:
        sys.exit(f"{name}: {lrc} states no timed words, so there is nothing to score")
    return truth


def measure(jar: Path, mp3: Path, lrc: Path, language: str,
            source: str) -> tuple[dict | None, str | None]:
    """One row's (document, skip reason), through whichever loop `source`
    names. The lrc loop never skips: its inputs are files this process can
    see, and run() exits hard on anything else."""
    with tempfile.TemporaryDirectory() as tmp:
        if source == "asr":
            config_home = Path(tmp) / "xdg"
            config_home.mkdir()
            return run_asr(jar, mp3, language, Path(tmp) / "w.mwz", config_home)
        return run(jar, mp3, lrc, language, Path(tmp) / "w.mwz"), None


def main() -> None:
    global SOURCE
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=str(REPO / "mw-cli/target/mw.jar"))
    parser.add_argument("--source", choices=("lrc", "asr"), default="lrc",
                        help="lrc: the supplied-lyrics loop; asr: transcription")
    parser.add_argument("--file", help="score this recording instead of the table")
    parser.add_argument("--lrc", help="its lyrics; required with --file")
    parser.add_argument("--language", default="it", help="its language tag")
    args = parser.parse_args()
    SOURCE = args.source
    jar = Path(args.jar)
    if not jar.exists():
        sys.exit(f"build first: mvn -B -DskipTests package   (missing {jar})")

    print(PREAMBLES[SOURCE])

    if args.file:
        if not args.lrc:
            sys.exit("--file needs --lrc: there is nothing to score it against")
        mp3, lrc = Path(args.file), Path(args.lrc)
        truth = truth_of(lrc, mp3.name)
        document, reason = measure(jar, mp3, lrc, args.language, SOURCE)
        if document is None:
            print(adhoc_unavailable_line(mp3.name, reason))
            return
        print(adhoc_line(mp3.name, truth, words_of(document, mp3.name, SOURCE)))
        return

    if SOURCE == "asr" and not (NATIVE_LIB / "libsherpa-onnx-jni.so").exists():
        # After the --file branch: an ad-hoc question about one recording
        # must not be answered with rows about the corpus table. For the
        # table this is worth checking up front -- it saves a full analysis
        # per row on a machine that cannot decode anything.
        for name in LYRICS:
            print(native_missing_line(name))
        return

    missing = []
    for name, (audio, lyrics, language) in LYRICS.items():
        mp3, lrc = REPO / audio, REPO / lyrics
        if not mp3.exists() or not lrc.exists():
            missing.append((name, Path(audio).parent / "list.txt"))
            continue
        truth = truth_of(lrc, name)
        document, reason = measure(jar, mp3, lrc, language, SOURCE)
        if document is None:
            print(unavailable_line(name, reason))
            continue
        print(score_line(name, truth, words_of(document, name, SOURCE)))
    for name, where in missing:
        print(missing_line(name, where))


if __name__ == "__main__":
    main()
