#!/usr/bin/env python3
"""Tests for the rules the sample harnesses score by.

The harnesses themselves cannot run in CI -- the benchmarks they need are
local-only for licensing -- so what CI can check is the arithmetic they apply,
on fixtures written here: a bar for the two chord harnesses, a word and its
onset for the lyric one. Run it directly:

    python3 tools/test-harness-rules.py
"""

import array
import contextlib
import io
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave
from importlib import import_module
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
samples = import_module("score-samples")
synthetic = import_module("score-synthetic")
chart = import_module("score-chart")
lyrics = import_module("score-lyrics")
melody = import_module("score-melody")
separation = import_module("measure-separation-cost")
vtt = import_module("vtt-to-lrc")
drift = import_module("baseline-drift")

C = (0, "MAJOR")
G = (7, "MAJOR")
F = (5, "MAJOR")


def span(symbol: str, start: float, end: float) -> dict:
    """One estimated chord span, as score.json spells it."""
    letter, accidental = symbol[0], "NATURAL"
    quality = symbol[1:] or "MAJOR"
    return {"root": {"letter": letter, "accidental": accidental},
            "quality": quality, "startSeconds": start, "endSeconds": end}


def chart_score(lines: list[str], truth: str) -> tuple[float, float]:
    """Root and root+quality bars correct, over a chart written out by hand."""
    bars = chart.bars_of("\n".join(f"  {line} |" for line in lines))
    return samples.accuracy(chart.shares_of(bars), samples.parse_truth(truth))


class BarCredit(unittest.TestCase):

    def test_the_chord_filling_most_of_the_bar_takes_it(self):
        self.assertEqual({C: 1.0}, samples.bar_credit({C: 3.0, G: 1.0}))

    def test_an_exact_tie_divides_the_bar(self):
        self.assertEqual({C: 0.5, G: 0.5}, samples.bar_credit({C: 2.0, G: 2.0}))

    def test_a_tie_does_not_depend_on_which_chord_came_first(self):
        self.assertEqual(samples.bar_credit({C: 2.0, G: 2.0}),
                         samples.bar_credit({G: 2.0, C: 2.0}))

    def test_three_and_four_way_ties_divide_the_same_way(self):
        """A committed baseline line rests on a third of a bar."""
        self.assertEqual({C: 1 / 3, G: 1 / 3, F: 1 / 3},
                         samples.bar_credit({C: 1.0, G: 1.0, F: 1.0}))
        self.assertEqual({C: 0.25, G: 0.25, F: 0.25, None: 0.25},
                         samples.bar_credit({C: 1.0, G: 1.0, F: 1.0, None: 1.0}))

    def test_a_silence_can_tie_and_earns_its_share_of_nothing(self):
        credit = samples.bar_credit({C: 2.0, None: 2.0})
        self.assertEqual({C: 0.5, None: 0.5}, credit)
        self.assertEqual((0.5, 0.5), samples.accuracy([credit],
                                                      samples.parse_truth("C")))

    def test_a_near_miss_is_not_a_tie(self):
        """The rule is exact equality. A tolerance would be one more arbitrary
        constant deciding the same bars."""
        self.assertEqual({C: 1.0}, samples.bar_credit({C: 2.0000001, G: 2.0}))

    def test_a_bar_with_nothing_in_it_earns_nothing(self):
        self.assertEqual({}, samples.bar_credit({}))


class ChartBars(unittest.TestCase):

    def test_a_dominated_bar_still_reads_as_its_dominant_chord(self):
        self.assertEqual((1.0, 1.0), chart_score(["c2. g4"], "C"))

    def test_an_evenly_split_bar_counts_as_the_half_it_got_right(self):
        self.assertEqual((0.5, 0.5), chart_score(["c1*1/2 g1*1/2"], "C"))

    def test_an_evenly_split_bar_scores_the_same_in_either_order(self):
        self.assertEqual(chart_score(["c1*1/2 g1*1/2"], "C"),
                         chart_score(["g1*1/2 c1*1/2"], "C"))

    def test_bar_lines_half_a_bar_out_of_phase_halve_every_change(self):
        """The property #242 is about, on a chart of two alternating chords.

        In phase, each bar holds one chord and reads as it. Half a bar out,
        every bar holds half of each -- and the positional tie-break this
        replaced scored that chart full marks, because the earlier cell of each
        bar spelled the same alternation one half-bar late.
        """
        in_phase = ["c1", "g1", "c1", "g1"]
        out_of_phase = ["c1*1/2 g1*1/2", "g1*1/2 c1*1/2"] * 2
        self.assertEqual((4.0, 4.0), chart_score(in_phase, "C G"))
        self.assertEqual((2.0, 2.0), chart_score(out_of_phase, "C G"))


class ModelBars(unittest.TestCase):

    def test_the_span_covering_most_of_the_bar_takes_it(self):
        spans = [span("C", 0.0, 3.0), span("G", 3.0, 4.0)]
        self.assertEqual({C: 1.0}, samples.bar_shares(spans, 0.0, 4.0))

    def test_two_spans_covering_exactly_as_much_divide_the_bar(self):
        spans = [span("C", 0.0, 2.0), span("G", 2.0, 4.0)]
        self.assertEqual({C: 0.5, G: 0.5}, samples.bar_shares(spans, 0.0, 4.0))

    def test_a_chord_is_measured_by_its_longest_span_not_its_total(self):
        """This harness's rule, kept: two short spans do not outweigh one long
        one, though totalling them would make this bar a tie. The chart harness
        totals instead, over its own bars."""
        spans = [span("C", 0.0, 1.0), span("G", 1.0, 3.0), span("C", 3.0, 4.0)]
        self.assertEqual({G: 1.0}, samples.bar_shares(spans, 0.0, 4.0))

    def test_a_bar_no_span_reaches_earns_nothing(self):
        self.assertEqual({}, samples.bar_shares([span("C", 0.0, 1.0)], 2.0, 3.0))


def minor_line(spans: list[dict], duration: float = 10.0) -> str:
    """The one line score_no_minor prints for a recording."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        samples.score_no_minor(Path("x.mp3"),
                               {"chords": {"chords": spans}, "durationSeconds": duration})
    return out.getvalue().strip()


class MinorSeconds(unittest.TestCase):
    """#546: the recordings whose truth is that they hold no minor chord.

    The row is read against zero, so what it counts has to be every quality
    with a minor third and nothing else."""

    def test_a_minor_span_is_counted_and_a_major_one_is_not(self):
        line = minor_line([span("C", 0.0, 6.0), span("AMINOR", 6.0, 8.0)])
        self.assertIn("2.0s of 10s (20.0%)", line)
        self.assertIn("spans 1/2", line)
        self.assertIn("m 2.0s", line)

    def test_spans_of_one_quality_are_summed_across_roots(self):
        """Broken down by quality, not by root: what a moved row asks is which
        rule moved."""
        line = minor_line([span("AMINOR", 0.0, 2.0), span("EMINOR", 2.0, 3.0),
                           span("GMINOR_SEVENTH", 3.0, 4.5)])
        self.assertIn("4.5s", line)
        self.assertIn("m 3.0s, m7 1.5s", line)

    def test_a_recording_with_no_minor_label_reads_zero(self):
        line = minor_line([span("C", 0.0, 10.0)])
        self.assertIn("0.0s of 10s (0.0%)", line)
        self.assertIn("spans 0/1", line)
        self.assertIn("none", line)

    def test_the_qualities_counted_are_the_enum_s_own_minorish_ones(self):
        """MINOR_THIRD is a copy of what ChordQuality.isMinorish() answers, and
        of the symbols the row prints. A quality added to the enum with a minor
        third must fail here rather than go uncounted in a row read against
        zero."""
        source = (Path(__file__).resolve().parent.parent
                  / "mw-core/src/main/java/dev/olivelli/musicwizard/core/model"
                  / "ChordQuality.java").read_text(encoding="utf-8")
        constants = re.findall(r"^    ([A-Z_]+)\(\"([^\"]*)\",\s*(?:true|false)((?:,\s*\d+)*)\)",
                               source, re.MULTILINE)
        self.assertGreater(len(constants), 10, "the enum's constants did not parse")
        minorish = {name: symbol for name, symbol, intervals in constants
                    if 3 in [int(i) for i in re.findall(r"\d+", intervals)]}
        self.assertEqual(minorish, samples.MINOR_THIRD)


def doc(spans: list[dict], beats: int = 16, phase: int = 0, per_bar: int = 4,
        beat_confidence: float = 0.5, phase_confidence: float = 0.35) -> dict:
    """A score.json as far as the phase block reads it: one beat a second, bar
    positions as BeatTracker.toBeatGrid writes them, and the two confidences it
    records as a product."""
    return {
        "chords": {"chords": spans},
        "beatGrid": {
            "beats": [{"seconds": float(i),
                       "downbeat": (i - phase) % per_bar == 0,
                       "positionInBar": (i - phase) % per_bar}
                      for i in range(beats)],
            "beatConfidence": {"value": beat_confidence},
            "downbeatConfidence": {"value": beat_confidence * phase_confidence},
        },
    }


# Four beats of C then four of G, twice: one chord to the bar at phase 0.
ALTERNATING = [span("C", 0.0, 4.0), span("G", 4.0, 8.0),
               span("C", 8.0, 12.0), span("G", 12.0, 16.0)]


class BarPhase(unittest.TestCase):
    """#303: a cell scored on a bar axis the estimator could not vouch for.

    The columns here are what tells a reader of a moved baseline row whether
    the chords moved or only the phase did."""

    def test_the_phase_is_read_off_the_bar_positions(self):
        self.assertEqual((3, 4), samples.bar_phase(doc([], phase=3)))

    def test_an_irregular_grid_has_no_phase_to_read(self):
        """The per-phase scores shift bar lines by index, so on a grid whose
        positions are not the regular ones they would measure something else."""
        irregular = doc([])
        irregular["beatGrid"]["beats"][5]["positionInBar"] = 2
        self.assertIsNone(samples.bar_phase(irregular))

    def test_a_grid_of_under_two_bars_has_no_phase_to_read(self):
        self.assertIsNone(samples.bar_phase(doc([], beats=7)))

    def test_one_beat_to_the_bar_has_no_phase_to_read(self):
        self.assertIsNone(samples.bar_phase(doc([], per_bar=1)))

    def test_the_phase_confidence_divides_the_beats_back_out(self):
        """The grid records the product of the two, and it is the phase's own
        half that says whether the estimator vouched for the axis."""
        self.assertAlmostEqual(
            0.35, samples.phase_confidence(doc([], beat_confidence=0.4)))

    def test_the_chosen_phase_scores_what_the_row_above_says(self):
        """The chord table bars on the flagged downbeats and this bars by
        index; they are the same bars, and a phase column that disagreed with
        the row it qualifies would be worse than none."""
        d = doc(ALTERNATING, phase=2)
        downbeats = [b["seconds"] for b in d["beatGrid"]["beats"] if b["downbeat"]]
        shares = [samples.bar_shares(ALTERNATING, a, b)
                  for a, b in zip(downbeats, downbeats[1:])]
        root, _ = samples.accuracy(shares, samples.parse_truth("C G"))
        self.assertEqual(100 * root / len(shares),
                         samples.phase_roots(d, "C G", 4)[2])

    def test_a_phase_that_halves_every_bar_scores_below_the_right_one(self):
        """Two beats of each chord in every bar, so no chord dominates and each
        bar counts as the half it got right. This is the spread the chosen
        phase is picked out of, and on these fixtures it is 50 points."""
        scores = samples.phase_roots(doc(ALTERNATING, phase=2), "C G", 4)
        self.assertEqual(100.0, scores[0])
        self.assertEqual(50.0, scores[2])

    def test_the_best_phase_is_the_chosen_one_where_nothing_beats_it(self):
        """One chord throughout scores the same at every phase. Naming the
        chosen phase then says no other does better; naming the lowest index
        would read as a phase the estimator passed over."""
        line = phase_line(doc([span("C", 0.0, 16.0)], phase=2), "C")
        self.assertIn("beat 2 of 4", line)
        self.assertIn("best beat 2 at 100.0%", line)

    def test_a_row_names_the_phase_it_scored_and_the_best_available(self):
        line = phase_line(doc(ALTERNATING, phase=2), "C G")
        self.assertIn("beat 2 of 4 at 0.3500", line)
        self.assertIn("root 50.0%", line)
        self.assertIn("best beat 0 at 100.0%", line)

    def test_a_grid_with_no_phase_says_so_rather_than_scoring_one(self):
        self.assertIn("no bar phase to read", phase_line(doc([], beats=7), "C"))

    def test_an_unreadable_confidence_is_not_reported_as_an_unreadable_phase(self):
        """#477: the grid records the product, so beats with no confidence
        leave nothing to divide by. The phase itself is there and regular --
        `bar_phase` reads it -- and the row must not say otherwise."""
        d = doc([], beat_confidence=0.0)
        self.assertEqual((0, 4), samples.bar_phase(d))
        self.assertIsNone(samples.phase_confidence(d))
        line = phase_line(d, "C")
        self.assertIn("no phase confidence to read", line)
        self.assertNotIn("no bar phase", line)

    def test_the_floor_is_the_estimator_s_own(self):
        """PHASE_FLOOR is a copy of DownbeatEstimator.BASE_CONFIDENCE, printed
        in the block's header as the value a phase nothing supports reports. A
        silent copy is how the header comes to name a number the estimator no
        longer uses."""
        source = (Path(__file__).resolve().parent.parent
                  / "mw-dsp/src/main/java/dev/olivelli/musicwizard/dsp"
                  / "DownbeatEstimator.java").read_text(encoding="utf-8")
        match = re.search(r"BASE_CONFIDENCE\s*=\s*([0-9.]+)\s*;", source)
        self.assertIsNotNone(match, "BASE_CONFIDENCE not found in DownbeatEstimator")
        self.assertEqual(float(match.group(1)), samples.PHASE_FLOOR)


def phase_line(document: dict, truth: str) -> str:
    """The one line score_phase prints for a file."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        samples.score_phase(Path("x.mp3"), document, truth)
    return out.getvalue().strip()


def scored(truth: str, hypothesis: list[str] | None = None, starts: list[float] | None = None):
    """The lyric harness's word and onset columns over an LRC and the words and
    onsets MW came back with. Both defaulted to the truth's own is the
    loop-closure case. The line-end column has its own helper below."""
    parsed = lyrics.truth_tokens(truth)
    tokens = parsed.tokens
    words = tokens if hypothesis is None else [lyrics.normalize(w) for w in hypothesis]
    if starts is None:
        starts = [0.0] * len(words)
    pairs = lyrics.align(tokens, words)
    return (lyrics.word_error(pairs, tokens, words),
            lyrics.onset_error(pairs, parsed.anchors, starts),
            parsed.word_level)


def end_scored(truth: str, line_ends: list[float]):
    """The line-end column alone, over a hypothesis that matches the truth's
    words exactly, so only the ends can move it."""
    parsed = lyrics.truth_tokens(truth)
    pairs = lyrics.align(parsed.tokens, parsed.tokens)
    return lyrics.end_error(pairs, parsed.ends, line_ends)


class SyntheticAlignment(unittest.TestCase):
    """The synthetic harness scores in sequence: rotation earns nothing.

    Its credit rules are score-samples', imported, so BarCredit above already
    covers them; what is this harness's own is the refusal to rotate."""

    def test_a_rotated_reading_scores_zero_where_the_cycle_scorer_forgives(self):
        want = [[C], [G], [C], [G]]
        rotated = [{G: 1.0}, {C: 1.0}, {G: 1.0}, {C: 1.0}]
        self.assertEqual(synthetic.sequence_accuracy(rotated, want), (0.0, 0.0))
        self.assertEqual(samples.accuracy(rotated, want), (4.0, 4.0))

    def test_a_split_bar_accepts_either_chord(self):
        grid = [[C, G]]
        self.assertEqual(synthetic.sequence_accuracy([{C: 1.0}], grid), (1.0, 1.0))
        self.assertEqual(synthetic.sequence_accuracy([{G: 1.0}], grid), (1.0, 1.0))
        self.assertEqual(synthetic.sequence_accuracy([{F: 1.0}], grid), (0.0, 0.0))

    def test_extra_estimated_bars_earn_nothing(self):
        want = [[C]]
        self.assertEqual(synthetic.sequence_accuracy([{C: 1.0}, {C: 1.0}], want),
                         (1.0, 1.0))

    def test_a_sharp_is_a_chord_not_a_comment_start(self):
        # SpecParser.java applies the same rule; a fix to one parser that
        # misses the other corrupts the grid on exactly one side.
        spec = synthetic.parse_spec_text(
            "title: sharps\nbars:\nE C#m F#m B  # trailing comment\n")
        self.assertEqual([len(bar) for bar in spec["bars"]], [1, 1, 1, 1])
        self.assertEqual(spec["bars"][1], [(1, "MINOR")])
        self.assertEqual(spec["bars"][3], [(11, "MAJOR")])


class Normalisation(unittest.TestCase):

    def test_case_and_edge_punctuation_go(self):
        self.assertEqual("generale", lyrics.normalize("Generale,"))
        self.assertEqual("sì", lyrics.normalize("«Sì»"))
        self.assertEqual("rivedi", lyrics.normalize("rivedi?"))

    def test_a_typographic_apostrophe_is_the_plain_one(self):
        self.assertEqual(lyrics.normalize("c'è"), lyrics.normalize("c’è"))

    def test_an_elision_stays_one_word(self):
        """Italian elides constantly and LrcLyrics splits on whitespace, so
        splitting here would mint one insertion per c'e and hold the word column
        permanently above zero on every Italian entry."""
        tokens, _, _, _ = lyrics.truth_tokens("[00:01.00]c'è l'amore")
        self.assertEqual(["c'è", "l'amore"], tokens)

    def test_accents_are_not_folded(self):
        """e/e-grave and perche/perche-acute are different words; folding them
        would score a wrong word as a right one."""
        self.assertNotEqual(lyrics.normalize("perché"), lyrics.normalize("perche"))
        self.assertNotEqual(lyrics.normalize("e"), lyrics.normalize("è"))

    def test_a_non_breaking_space_does_not_split_a_word(self):
        """Java's \\s is ASCII-only, so LrcLyrics keeps this as one token."""
        tokens, _, _, _ = lyrics.truth_tokens("[00:01.00]a\u00a0b")
        self.assertEqual(1, len(tokens))

    def test_a_non_breaking_space_is_not_stripped_either(self):
        """String.strip() and isBlank() use Character.isWhitespace, which says
        false for the three non-breaking spaces Python's str.strip() removes.
        Each of these is a divergence from str.strip()."""
        for space in ("\u00a0", "\u2007", "\u202f"):
            tokens, _, _, _ = lyrics.truth_tokens(f"[00:01.00]{space}uno due")
            self.assertEqual([f"{space}uno", "due"], tokens, space)
            tokens, _, _, _ = lyrics.truth_tokens(f"[00:01.00]uno due{space}")
            self.assertEqual(["uno", f"due{space}"], tokens, space)
        # Before the tag it is not blank either, so Java drops the whole line.
        self.assertEqual(([], {}, {}, False), lyrics.truth_tokens("\u00a0[00:01.00]uno"))

    def test_a_breaking_unicode_space_is_stripped(self):
        """The other half of that rule: Character.isWhitespace is Unicode-aware,
        so U+1680 and U+2003 go where U+00A0 stays."""
        for space in ("\u1680", "\u2003"):
            tokens, _, _, _ = lyrics.truth_tokens(f"[00:01.00]{space}uno")
            self.assertEqual(["uno"], tokens, space)

    def test_a_punctuation_only_token_keeps_its_place_and_its_anchor(self):
        """A dialogue dash or a leading ellipsis is routine in a subtitle track.
        LrcLyrics gives it a share of the line, so dropping it here would leave
        the run's stated onset on the *next* word and report an onset error on a
        loop that closed correctly."""
        tokens, anchors, _, _ = lyrics.truth_tokens("[00:01.00]\u2014 ciao amore")
        self.assertEqual(["", "ciao", "amore"], tokens)
        self.assertEqual({0: 1.0}, anchors)


class TruthTokens(unittest.TestCase):

    def test_a_plain_line_anchors_its_first_word_only(self):
        tokens, anchors, _, word_level = lyrics.truth_tokens("[00:10.00]uno due tre")
        self.assertEqual(["uno", "due", "tre"], tokens)
        self.assertEqual({0: 10.0}, anchors)
        self.assertFalse(word_level)

    def test_a_partly_tagged_line_anchors_each_run(self):
        tokens, anchors, _, word_level = lyrics.truth_tokens("[00:10.00]a <00:11.00>b c")
        self.assertEqual(["a", "b", "c"], tokens)
        self.assertEqual({0: 10.0, 1: 11.0}, anchors)
        self.assertTrue(word_level)

    def test_two_leading_tags_write_the_line_out_twice(self):
        """How an LRC writes a repeated chorus without copying it."""
        tokens, anchors, _, _ = lyrics.truth_tokens("[00:10.00] [00:20.00]ciao a tutti")
        self.assertEqual(["ciao", "a", "tutti"] * 2, tokens)
        self.assertEqual({0: 10.0, 3: 20.0}, anchors)

    def test_tokens_come_back_in_time_order(self):
        tokens, anchors, _, _ = lyrics.truth_tokens("[00:20.00]dopo\n[00:10.00]prima")
        self.assertEqual(["prima", "dopo"], tokens)
        self.assertEqual({0: 10.0, 1: 20.0}, anchors)

    def test_a_positive_offset_moves_the_words_earlier(self):
        """The tag's sign is a genuinely ambiguous corner of the format, so it
        is tested rather than reasoned about: LrcLyrics subtracts it."""
        _, anchors, _, _ = lyrics.truth_tokens("[offset:500]\n[00:10.00]ciao")
        self.assertEqual({0: 9.5}, anchors)
        _, anchors, _, _ = lyrics.truth_tokens("[offset:-500]\n[00:10.00]ciao")
        self.assertEqual({0: 10.5}, anchors)

    def test_an_unusable_offset_is_ignored_rather_than_carried(self):
        for bad in ("[offset:NaN]", "[offset:-Infinity]", "[offset:x]",
                    "[offset:1_0]"):
            _, anchors, _, _ = lyrics.truth_tokens(f"{bad}\n[00:10.00]ciao")
            self.assertEqual({0: 10.0}, anchors, bad)

    def test_the_offset_grammar_is_double_parse_doubles(self):
        """Values where float() and Double.parseDouble disagree, plus the
        grammar's own edges and the two that parse in both and are refused for
        being non-finite. float() cannot be handed the string and asked
        afterwards: it folds Unicode spaces and Unicode digits to ASCII before
        parsing, so a guard in front of it is reading a different string from
        the one that gets parsed."""
        for value, want in (("100d", 0.1), ("0x1p10", 1.024), ("0x1.8p9", 0.768),
                            ("\x01500", 0.5), ("500\x1b", 0.5)):
            self.assertEqual(want, lyrics.java_double(value), value)
        for refused in ("\u00a0500", "500\u00a0", "\u2007500", "\u202f500",
                        "\u0665\u0660\u0660", "1_0", "0x1", "NaN", "Infinity",
                        "", "d", "1e", "0xfp1023"):
            self.assertIsNone(lyrics.java_double(refused), refused)

    def test_a_type_suffix_moves_every_anchor_in_the_file(self):
        _, anchors, _, _ = lyrics.truth_tokens("[offset:100d]\n[00:10.00]ciao")
        self.assertEqual({0: 9.9}, anchors)

    def test_an_id_tag_and_an_empty_body_carry_no_word(self):
        tokens, anchors, _, _ = lyrics.truth_tokens(
            "[ti:Sere]\n[ar:iiridio]\n[00:10.00]ciao\n[00:12.00]")
        self.assertEqual(["ciao"], tokens)
        self.assertEqual({0: 10.0}, anchors)

    def test_a_byte_order_mark_does_not_cost_the_first_line(self):
        tokens, _, _, _ = lyrics.truth_tokens("\ufeff[00:10.00]ciao")
        self.assertEqual(["ciao"], tokens)

    def test_a_fraction_is_scaled_by_its_own_width(self):
        for tag in ("[00:01.5]", "[00:01.50]", "[00:01.500]", "[00:01:50]"):
            _, anchors, _, _ = lyrics.truth_tokens(f"{tag}ciao")
            self.assertEqual({0: 1.5}, anchors, tag)


class WordAndOnsetColumns(unittest.TestCase):

    LINES = "[00:10.00]uno due\n[00:20.00]tre quattro\n[00:30.00]cinque sei"
    PLACED = [10.0, 10.0, 20.0, 20.0, 30.0, 30.0]

    def test_the_loop_closes_at_zero(self):
        (sub, dels, ins, wer), (median, worst, matched, total), _ = scored(
            self.LINES, starts=self.PLACED)
        self.assertEqual((0, 0, 0, 0.0), (sub, dels, ins, wer))
        self.assertEqual((0.0, 0.0, 3, 3), (median, worst, matched, total))

    def test_one_wrong_word_is_a_substitution_and_keeps_its_onset(self):
        words = ["uno", "due", "tre", "QUATTRO-X", "cinque", "sei"]
        (sub, dels, ins, _), (_, worst, matched, total), _ = scored(
            self.LINES, words, self.PLACED)
        self.assertEqual((1, 0, 0), (sub, dels, ins))
        self.assertEqual((0.0, 3, 3), (worst, matched, total),
                         "a misheard word is still a word with a time")

    def test_a_dropped_line_does_not_shift_the_onsets_after_it(self):
        """Anchors are paired through the alignment, never by index. Pairing by
        index would turn one lost line into a whole-song onset failure."""
        (sub, dels, ins, _), (median, worst, matched, total), _ = scored(
            self.LINES, ["uno", "due", "cinque", "sei"], [10.0, 10.0, 30.0, 30.0])
        self.assertEqual((0, 2, 0), (sub, dels, ins))
        self.assertEqual((0.0, 0.0), (median, worst),
                         "the surviving lines are still where the file put them")
        self.assertEqual((2, 3), (matched, total), "the lost line's anchor stays counted")

    def test_a_doubled_chorus_is_insertions_and_is_not_clamped_at_a_hundred(self):
        words = ["uno", "due", "tre", "quattro", "cinque", "sei"] * 3
        (sub, dels, ins, wer), _, _ = scored(self.LINES, words)
        self.assertEqual((0, 0, 12), (sub, dels, ins))
        self.assertGreater(wer, 100.0)

    def test_the_backtrace_is_deterministic_on_a_tie(self):
        """A committed baseline rests on this: a flapping alignment would move
        the printed line between runs of an unchanged tree. Two substitutions
        and a delete-plus-insert both cost two here, and the fixed preference
        picks the pair every time rather than either at random."""
        self.assertEqual([(0, 0), (1, 1)], lyrics.align(["a", "b"], ["b", "a"]))

    def test_max_catches_the_one_line_the_median_cannot(self):
        starts = [10.0, 10.0, 20.4, 20.4, 30.0, 30.0]
        tokens, anchors, _, _ = lyrics.truth_tokens(self.LINES)
        pairs = lyrics.align(tokens, tokens)
        median, worst, matched, _ = lyrics.onset_error(pairs, anchors, starts)
        self.assertEqual(0.0, median)
        self.assertAlmostEqual(0.4, worst)
        self.assertEqual(3, matched)

    def test_no_matched_anchor_prints_no_number(self):
        """A median over nothing must not print as a measured zero."""
        line = lyrics.score_line("x.mp3", lyrics.Truth(["a"], {}, {}, False),
                                 lyrics.Heard(["b"], [0.0], [0.0]))
        self.assertIn("onset no anchors matched", line)
        self.assertNotIn("0.000s", line)


class LineEndColumn(unittest.TestCase):
    """The third column (#361), and what it takes from the file as a stated end.

    Which entries end a line is LrcLyrics' rule, not this harness's, and these
    hold the two sides together: the harness reports MW as wrong wherever it
    disagrees."""

    ONE_LINE = "[00:10.00]uno due tre\n[00:14.00]\n"

    def test_a_display_clear_states_the_line_it_ends(self):
        parsed = lyrics.truth_tokens(self.ONE_LINE)
        # Anchored on the line's last token, so it is paired through the word
        # alignment exactly as an onset is.
        self.assertEqual({2: 14.0}, parsed.ends)
        self.assertEqual(["uno", "due", "tre"], parsed.tokens)

    def test_the_column_moves_when_the_line_end_moves(self):
        """The point of the column, and what #361 measured as broken: the
        recording bound and the break rules move a line's end and moved
        nothing. Same words, same onsets, ends four seconds apart."""
        exact = end_scored(self.ONE_LINE, [14.0, 14.0, 14.0])
        stretched = end_scored(self.ONE_LINE, [18.0, 18.0, 18.0])

        self.assertEqual((0.0, 0.0, 1, 1), exact)
        self.assertEqual((4.0, 4.0, 1, 1), stretched)

    def test_a_clear_on_the_line_own_moment_does_not_end_it(self):
        """LrcLyrics ends a line at the next entry whose start is strictly
        greater -- one on the line's own moment does not end it, for the same
        reason a second voice does not. Reading it as an end invents an error
        the size of the whole line, and consumes the clear that really states
        one."""
        parsed = lyrics.truth_tokens(
            "[00:10.00]uno due tre\n[00:10.00]\n[00:14.00]\n[00:20.00]quattro\n")
        self.assertEqual({2: 14.0}, parsed.ends)

    def test_an_offset_that_collapses_two_starts_collapses_them_here_too(self):
        """The same rule reached without writing a duplicate tag: an offset
        larger than a line's own time clamps several starts to zero, which Java
        calls out as making them one moment. Third-party files carry such
        offsets."""
        parsed = lyrics.truth_tokens(
            "[offset:20000]\n[00:10.00]uno\n[00:14.00]\n[01:00.00]due\n")
        self.assertEqual({}, parsed.ends)

    def test_an_entry_of_only_word_tags_closes_a_line_but_states_no_end(self):
        """It is not blank, so it is not a clear; it produces no run, so Java
        builds no line from it. But nextMeasuring reads only the times, so it
        ends the line before it all the same, and the clear that follows states
        the end of nothing. Crediting the line with that clear's time reports an
        error against a loop that closed exactly."""
        parsed = lyrics.truth_tokens(
            "[00:10.00]uno due\n[00:20.00]<00:20.50>\n[00:30.00]\n")
        self.assertEqual({}, parsed.ends)

    def test_a_line_that_follows_a_tag_only_entry_still_states_its_own_end(self):
        """A tag-only entry is never credited with an end of its own, so it
        cannot overwrite the one the clear before it already stated."""
        parsed = lyrics.truth_tokens(
            "[00:10.00]uno\n[00:12.00]\n[00:13.00]<00:13.50>\n[00:14.00]\n"
            "[00:40.00]due\n[00:44.00]\n")
        self.assertEqual({0: 12.0, 1: 44.0}, parsed.ends)

    def test_a_second_clear_ends_nothing(self):
        """Two clears in a row: the first states this line's end and the second
        states the end of no line, so it must not overwrite it."""
        parsed = lyrics.truth_tokens("[00:10.00]uno\n[00:14.00]\n[00:15.00]\n")
        self.assertEqual({0: 14.0}, parsed.ends)

    def test_a_clear_before_any_line_ends_nothing(self):
        parsed = lyrics.truth_tokens("[00:05.00]\n[00:10.00]uno\n")
        self.assertEqual({}, parsed.ends)

    def test_a_file_stating_no_ends_says_so_rather_than_printing_a_zero(self):
        """An unmeasured column and a perfect one must not read alike."""
        parsed = lyrics.truth_tokens("[00:10.00]uno due")
        self.assertEqual({}, parsed.ends)
        line = lyrics.score_line("x.mp3", parsed, lyrics.Heard(parsed.tokens, [10.0, 10.0],
                                                               [12.0, 12.0]))
        self.assertIn("line end none stated", line)
        self.assertIn("ends 0/0", line)

    def test_an_end_whose_word_was_lost_is_counted_unmatched(self):
        """As an anchor is: reported beside the median it did not enter, rather
        than quietly leaving the sample."""
        parsed = lyrics.truth_tokens(self.ONE_LINE)
        pairs = lyrics.align(parsed.tokens, ["uno", "due"])
        self.assertEqual((None, None, 0, 1), lyrics.end_error(pairs, parsed.ends, [9.0, 9.0]))


class VttConversion(unittest.TestCase):
    """The tool that writes committed lyric ground truth, so a silent bug here
    is not caught by anything downstream."""

    HEAD = "WEBVTT\n\n"

    def cue(self, start: str, end: str, text: str) -> str:
        return f"00:00:{start} --> 00:00:{end}\n{text}\n"

    def test_a_missing_blank_line_does_not_swallow_the_next_cue(self):
        """A body ends at the next timing line as well as at a blank one.
        Without that, one cue is lost, the timing line becomes lyric text, and
        the count printed at the end comes from the same parse, so it cannot
        contradict it."""
        text = self.HEAD + "".join(self.cue(*c) for c in
                                   (("01.000", "02.000", "uno"),
                                    ("03.000", "04.000", "due"),
                                    ("05.000", "06.000", "tre")))
        self.assertEqual(3, len(vtt.cues(text)))
        self.assertEqual(["uno", "due", "tre"], [body for _, _, body in vtt.cues(text)])

    def test_a_multi_line_cue_is_one_lyric_line(self):
        text = self.HEAD + self.cue("01.000", "02.000", "uno\ndue")
        self.assertEqual([(1.0, 2.0, "uno due")], vtt.cues(text))

    def test_an_overlapping_cue_writes_no_stated_end(self):
        """A bare tag past the next cue's start would sort into the middle of
        that line and truncate it."""
        text = self.HEAD + self.cue("01.000", "09.000", "uno") + self.cue(
            "05.000", "06.000", "due")
        self.assertEqual("[00:01.00]uno\n[00:05.00]due\n[00:06.00]\n",
                         vtt.convert(text, set()))

    def test_abutting_cues_write_no_stated_end_either(self):
        """The next tag already says it."""
        text = self.HEAD + self.cue("01.000", "02.000", "uno") + self.cue(
            "02.000", "03.000", "due")
        self.assertEqual("[00:01.00]uno\n[00:02.00]due\n[00:03.00]\n",
                         vtt.convert(text, set()))

    def test_a_gap_writes_the_stated_end(self):
        """The whole point: LrcLyrics reads the bare tag as the line's end, so
        the line stops where the subtitler said rather than where the parser's
        break heuristic would cut it."""
        text = self.HEAD + self.cue("01.000", "02.000", "uno") + self.cue(
            "30.000", "31.000", "due")
        self.assertEqual("[00:01.00]uno\n[00:02.00]\n[00:30.00]due\n[00:31.00]\n",
                         vtt.convert(text, set()))

    def test_a_stated_end_survives_the_hundredth_minute(self):
        """Formatted tags sort [100:00.00] before [99:00.00], so convert's
        overlap test cannot be a string comparison. Asserted through convert,
        because the comparison lives there: the arithmetic on its own is
        monotone under either."""
        text = (self.HEAD
                + "01:38:00.000 --> 01:39:00.000\nuno\n\n"
                + "01:41:00.000 --> 01:42:00.000\ndue\n")
        self.assertEqual(
            "[98:00.00]uno\n[99:00.00]\n[101:00.00]due\n[102:00.00]\n",
            vtt.convert(text, set()))


class Keying(unittest.TestCase):
    """premerge.sh keys each line on the text before its first colon, and reads
    a line as a row when it carries '.mp3:' or is an indented '  name: '. Both
    halves of that are executed here rather than asserted in a comment; the
    second shape is what the melody harness prints, and PremergeComparison
    below runs the shipped comparison over one."""

    ARGS = (lyrics.Truth(["uno"], {0: 1.0}, {0: 2.0}, False),
            lyrics.Heard(["uno"], [1.0], [2.0]))

    def test_a_scored_row_is_gated(self):
        line = lyrics.score_line("sere-doltremare.mp3", *self.ARGS)
        self.assertIn(".mp3:", line)
        self.assertEqual("sere-doltremare.mp3", line.split(":")[0].strip())

    def test_a_missing_row_is_gated_under_the_same_key(self):
        line = lyrics.missing_line("sere-doltremare.mp3", "uncommitted/list.txt")
        self.assertIn(".mp3:", line)
        self.assertEqual("sere-doltremare.mp3", line.split(":")[0].strip())

    def test_a_minor_seconds_row_is_gated(self):
        """The row of #546 is one premerge compares, under its own key -- a
        recording is in two tables and neither may overwrite the other."""
        line = minor_line([span("AMINOR", 0.0, 1.0)])
        self.assertIn(".mp3:", line)
        self.assertEqual("minor x.mp3", line.split(":")[0].strip())

    def test_an_ad_hoc_row_is_not_gated(self):
        """A file with no licence reaching its words must not become a baseline
        row, so its line is deliberately keyed out of the comparison."""
        self.assertNotIn(".mp3:", lyrics.adhoc_line("generale.mp3", *self.ARGS))

    def test_the_preambles_are_not_gated(self):
        """The lines main() prints, not the module docstring -- premerge.sh
        reads the former, one per source."""
        for preamble in lyrics.PREAMBLES.values():
            self.assertNotIn(".mp3:", preamble)

    def test_a_missing_native_row_is_a_skip_not_a_failure(self):
        """The asr loop needs the sherpa native; a machine without one must
        report each baselined row in the marker premerge turns into a SKIP,
        exactly as an absent benchmark file does -- and the row must still be
        keyed, or the corpus-disagreement guard would fire instead."""
        line = lyrics.native_missing_line("sere-doltremare.mp3")
        self.assertIn(self.MARKER, line)
        self.assertIn(".mp3:", line)
        self.assertEqual("sere-doltremare.mp3", line.split(":")[0].strip())

    def test_the_report_literals_exist_in_analyze_itself(self):
        """The asr loop's classification reads analyze's printed report, so
        its literals are held against AnalyzeCommand's source the same way
        the skip marker is held against premerge.sh: a rewording there must
        fail here before the gate starts skipping every row in silence."""
        source = (Path(__file__).resolve().parent.parent
                  / "mw-cli/src/main/java/dev/olivelli/musicwizard/cli"
                  / "AnalyzeCommand.java").read_text(encoding="utf-8")
        for literal in ('"  transcribed "', '"lyric line"',
                        "heard no words in", "no sung stretches found",
                        "lyrics not transcribed", "lyric transcription failed",
                        "no ASR provider"):
            self.assertIn(literal, source)

    def test_an_adhoc_environment_skip_is_not_gated(self):
        """Like every ad-hoc line: a one-off reading must not look gated."""
        line = lyrics.adhoc_unavailable_line("generale.mp3", "no native")
        self.assertNotIn(".mp3:", line)
        self.assertIn("no native", line)

    def test_an_environment_skip_carries_the_reason_in_the_skip_key(self):
        """An ASR the machine could not run is a skip naming analyze's own
        reason -- never a scored row, whose 289 deletions would read as a
        catastrophic regression on a machine problem."""
        line = lyrics.unavailable_line("sere-doltremare.mp3", "model x is not in cache")
        self.assertIn(self.MARKER, line)
        self.assertIn(".mp3:", line)
        self.assertIn("model x is not in cache", line)
        # Bounded, so a stack trace pasted as the reason cannot wrap the row.
        long = lyrics.unavailable_line("x.mp3", "y" * 500)
        self.assertLess(len(long), 220)

    MARKER = ": not present (local-only"

    def test_every_harness_marks_an_absent_file_the_same_way(self):
        """premerge.sh turns a row carrying this marker into a SKIP. All three
        harnesses must produce it through their missing_line, or a fresh
        worktree fails the gate for every branch again (#365). The reader is
        held to the same literal as the writers: if premerge.sh's copy of the
        marker drifts, this fails before the gate does."""
        self.assertIn(self.MARKER,
                      (Path(__file__).resolve().parent / "premerge.sh").read_text())
        for line in (samples.missing_line("x.mp3"),
                     samples.missing_line("key x.mp3"),
                     samples.missing_line("phase x.mp3"),
                     samples.missing_line("minor x.mp3", "uncommitted"),
                     chart.missing_line("x.mp3"),
                     lyrics.missing_line("x.mp3", "uncommitted/list.txt")):
            self.assertIn(self.MARKER, line)
            self.assertIn(".mp3:", line)

    def test_a_measured_row_never_carries_the_skip_marker(self):
        """The converse: a row premerge compares must not be skippable. The
        scored row's fields are numeric or fixed-vocabulary, so the marker
        cannot arise, and this holds it that way."""
        self.assertNotIn(self.MARKER,
                         lyrics.score_line("sere-doltremare.mp3", *self.ARGS))


@contextlib.contextmanager
def scratch_repo():
    """A throwaway git repo, and a runner for git inside it.

    The ambient git environment is dropped rather than added to: `GIT_DIR`
    would put these commits in whatever repository the caller was standing in,
    and `GIT_CONFIG_COUNT` carries config past the three variables that shut
    the config files out. Naming the ones that bite leaves the next one to
    find; anything a repo of our own needs, we set here.

    The process environment is what is scrubbed, not one command's copy of it:
    the code under test spawns git of its own, and a shield the subject does
    not stand behind leaves it reading the ambient repository while the
    fixture reads this one.

    What it buys: `commit.gpgsign` on a machine with no key makes every commit
    here fail, and `diff.renames = false` lets the rename test below pass
    against a premerge.sh with the flag it exists to pin deleted -- a test that
    green-lights removing its own subject. CI has no such config, so both are
    local-only, which is where this file is most often run.
    """
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    env |= {"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1"}
    with tempfile.TemporaryDirectory() as tmp, \
            mock.patch.dict(os.environ, env, clear=True):
        def git(*args):
            return subprocess.run(("git", "-C", tmp) + args, check=True,
                                  capture_output=True, text=True)
        git("init", "-q")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        yield git, Path(tmp)


class BaselineDrift(unittest.TestCase):
    """premerge.sh prompts when main regenerated a baseline during this
    branch's life. What it must get right is the kind of change: a column
    added to every row rewrites the file without moving a measurement, and a
    prompt that calls that a moved figure is one people learn to skip."""

    CHART = ("charts emitted for samples with known ground truth:\n"
             "  blues-a-90bpm.mp3: bars=113  chords/bar 1.32  root 93.0/113 (82.3%)\n"
             "  bossa-cm.mp3: bars=98  chords/bar 1.23  root 23.5/98 (24.0%)\n")

    def status(self, old: str | None, new: str | None) -> int:
        """main()'s exit status for one file, with git and stdout stubbed."""
        show, out = drift.show, io.StringIO()
        drift.show = lambda rev, _path: old if rev == "base" else new
        try:
            with contextlib.redirect_stdout(out):
                return drift.main(["x", "base", "tip", "tools/baselines/a.txt"])
        finally:
            drift.show = show

    def test_the_quiet_statuses_are_ones_python_cannot_produce_by_accident(self):
        """premerge.sh keys its quiet arms on these numbers, and python exits 2
        of its own accord when it cannot open the script it was given. A
        classifier that never ran must not read as one that found nothing."""
        row = "h:\n  a.mp3: bars 4  root 1.0/2 (50.0%)\n"
        self.assertEqual(1, self.status(row, row.replace("1.0/2 (50.0%)",
                                                         "2.0/2 (100.0%)")))
        self.assertEqual(3, self.status(row, row.replace("(50.0%)\n",
                                                         "(50.0%)  n 7\n")))
        self.assertEqual(4, self.status(row, row + "  b.mp3: bars 4  root 2.0/2 (100.0%)\n"))
        self.assertEqual(0, self.status(row, row.replace("h:", "header:")))

    ROW = "h:\n  a.mp3: bars 4  root 1.0/2 (50.0%)\n"

    def cases(self) -> dict:
        """One pair of files per kind describe() says it can return."""
        return {
            "moved": (self.ROW, self.ROW.replace("1.0/2 (50.0%)", "2.0/2 (100.0%)")),
            "reshaped": (self.ROW, self.ROW.replace("(50.0%)\n", "(50.0%)  n 7\n")),
            "added": (self.ROW, self.ROW + "  b.mp3: bars 4  root 2.0/2 (100.0%)\n"),
            "": (self.ROW, self.ROW.replace("h:", "header:")),
        }

    def test_premerge_answers_every_status_the_classifier_can_return(self):
        """A status with no arm of its own lands in the loud default: safe, but
        saying the wrong thing, which is this class's own defect one level up.
        Only the loud status is left to that default. describe's docstring is
        taken as its contract: the case table below must match it exactly, so a
        kind cannot be documented without being answered here."""
        documented = set(re.findall(r'"(\w*)"', drift.describe.__doc__))
        self.assertEqual(documented, set(self.cases()))
        quiet = {self.status(*self.cases()[k]) for k in documented} - {1}
        script = (Path(__file__).resolve().parent / "premerge.sh").read_text()
        block = script.split("python3 tools/baseline-drift.py")[1].split("esac")[0]
        arms = set(re.findall(r"^\s*(\d+)\)", block, re.M))
        self.assertIn("*)", block, "the loud arm is the default")
        self.assertEqual(set(), {str(s) for s in quiet} - arms)

    def test_a_baseline_that_appeared_on_main_is_the_same_case_at_file_scale(self):
        """Nothing quoted earlier was measured against a file that did not
        exist. One that vanished stays loud."""
        self.assertEqual(4, self.status(None, self.ROW))
        self.assertEqual(1, self.status(self.ROW, None))

    def test_a_rev_git_could_not_read_is_not_a_file_that_did_not_exist(self):
        """Absence is quiet now, so `show` returning nothing has to mean the
        commit does not carry the path and nothing else."""
        self.assertEqual(1, self.status(drift.FAILED, self.ROW))
        self.assertEqual(1, self.status(self.ROW, drift.FAILED))

    def test_the_paths_premerge_hands_over_keep_a_rename_in_two_halves(self):
        """git names only a rename's destination, and the classifier would read
        that as a path with no older self -- its quiet arm -- while the figures
        inside it moved. Run with the flags premerge.sh actually passes, over a
        real rename, so the option and the rule cannot drift apart."""
        script = (Path(__file__).resolve().parent / "premerge.sh").read_text()
        invocation = re.search(r"git diff (.*?) -- tools/baselines/", script)
        self.assertIsNotNone(invocation, "premerge.sh's git diff line moved")
        flags = [t for t in invocation.group(1).split() if t.startswith("--")]
        with scratch_repo() as (git, tree):
            baselines = tree / "tools/baselines"
            baselines.mkdir(parents=True)
            (baselines / "score-chart.txt").write_text(self.CHART)
            git("add", "-A")
            git("commit", "-qm", "base")
            (baselines / "score-chart.txt").unlink()
            (baselines / "score-charts.txt").write_text(
                self.CHART.replace("93.0/113 (82.3%)", "71.0/113 (62.8%)"))
            git("add", "-A")
            git("commit", "-qm", "rename and re-measure")
            named = git("diff", *flags, "HEAD~1", "HEAD",
                        "--", "tools/baselines/").stdout.split()
        self.assertEqual(["tools/baselines/score-chart.txt",
                          "tools/baselines/score-charts.txt"], sorted(named))

    def test_absence_is_read_off_the_tree_and_not_off_a_failed_read(self):
        """A clone that holds the history but not every blob lists the older
        file and cannot read it. Calling that absence puts a re-measured
        baseline in the quiet arm, so `show` asks the tree; here the object is
        removed to make the read fail while the tree still names it."""
        with scratch_repo() as (git, tree):
            (tree / "b.txt").write_text(self.CHART)
            git("add", "-A")
            git("commit", "-qm", "one")
            blob = git("rev-parse", "HEAD:b.txt").stdout.strip()
            cwd = os.getcwd()
            try:
                os.chdir(tree)
                self.assertEqual(self.CHART, drift.show("HEAD", "b.txt"))
                self.assertIsNone(drift.show("HEAD", "gone.txt"))
                self.assertIs(drift.FAILED, drift.show("nosuchrev", "b.txt"))
                # The paths are repository-relative both sides of the tree
                # question, so where the run happens cannot decide the answer.
                (tree / "sub").mkdir()
                os.chdir(tree / "sub")
                self.assertEqual(self.CHART, drift.show("HEAD", "b.txt"))
                os.chdir(tree)
                (tree / ".git/objects" / blob[:2] / blob[2:]).unlink()
                self.assertIn("b.txt", git("ls-tree", "HEAD", "--", "b.txt").stdout)
                self.assertIs(drift.FAILED, drift.show("HEAD", "b.txt"))
            finally:
                os.chdir(cwd)

    def test_the_scratch_repo_stands_in_no_other_repository(self):
        """GIT_DIR would put these commits in whatever repository the caller
        was standing in, and GIT_CONFIG_COUNT carries config in past the
        variables that shut the config files out. Both are dropped, and from
        the process environment, so the code under test stands behind the
        shield too rather than reading the ambient repository through git of
        its own."""
        with tempfile.TemporaryDirectory() as ambient:
            hostile = {"GIT_DIR": ambient, "GIT_CONFIG_COUNT": "1",
                       "GIT_CONFIG_KEY_0": "diff.renames",
                       "GIT_CONFIG_VALUE_0": "false"}
            cwd = os.getcwd()
            with mock.patch.dict(os.environ, hostile):
                try:
                    with scratch_repo() as (git, tree):
                        self.assertTrue((tree / ".git").exists())
                        (tree / "a.txt").write_text(self.CHART)
                        git("add", "-A")
                        git("commit", "-qm", "one")
                        os.chdir(tree)
                        self.assertEqual(self.CHART, drift.show("HEAD", "a.txt"))
                        os.chdir(cwd)
                        git("mv", "a.txt", "b.txt")
                        git("commit", "-qam", "rename")
                        # No flag, so git's own default decides: detection on,
                        # and only the destination named. Both paths here would
                        # mean the ambient diff.renames got in -- which is what
                        # would let the rename test above pass over a
                        # premerge.sh with --no-renames deleted.
                        named = git("diff", "--name-only",
                                    "HEAD~1", "HEAD").stdout.split()
                        self.assertEqual(["b.txt"], named)
                finally:
                    os.chdir(cwd)
            self.assertEqual([], sorted(os.listdir(ambient)))

    def test_a_figure_that_moved_is_named_by_its_row(self):
        moved = self.CHART.replace("23.5/98 (24.0%)", "25.5/98 (26.0%)")
        summary, detail, kind = drift.describe(self.CHART, moved)
        self.assertEqual("moved", kind)
        self.assertEqual("figures moved in 1 of 2 rows", summary)
        self.assertEqual(["      bossa-cm.mp3"], detail)

    def test_a_measurement_that_reads_as_a_word_is_a_figure_that_moved(self):
        """Half of score-samples' rows are key rows, whose verdict is a word.
        Masking numbers alone would file OK -> WRONG as a changed column and
        take the quiet branch for a re-measurement anyone might have quoted."""
        old = "samples:\n  key a.mp3: G major at 40%  want G major  OK\n"
        new = "samples:\n  key a.mp3: E minor at 40%  want G major  WRONG\n"
        summary, _, kind = drift.describe(old, new)
        self.assertEqual("moved", kind)
        self.assertEqual("figures moved in 1 of 1 rows", summary)

    def test_a_word_that_moved_counts_even_where_a_column_was_added(self):
        """A key-estimator change regenerates the key rows and may add a
        column in the same commit. Matching by shape alone files the flipped
        verdict as a column that vanished, and the pasted verdict then says no
        figure moved over an inverted key."""
        old = "samples:\n  key a.mp3: G major at 40%  want G major  OK\n"
        new = ("samples:\n"
               "  key a.mp3: E minor at 40%  want G major  WRONG  weighed 12\n")
        _, _, kind = drift.describe(old, new)
        self.assertEqual("moved", kind)

    def test_a_word_valued_column_merely_added_is_still_a_column(self):
        """The converse, and why the rule is about a field vanishing rather
        than about digits: a new label-valued column moves nothing."""
        old = "lyrics:\n  a.mp3: words 289  wer 0.0%\n"
        new = "lyrics:\n  a.mp3: words 289  wer 0.0%  source lrc\n"
        _, _, kind = drift.describe(old, new)
        self.assertEqual("reshaped", kind)

    def test_a_file_whose_rows_did_not_change_claims_nothing(self):
        """A header edit is neither a moved figure nor a reshaped row, and the
        verdict clause pasted into a PR must not claim either."""
        old = "charts emitted for samples:\n  a.mp3: bars=10  root 5.0/10 (50.0%)\n"
        summary, detail, kind = drift.describe(old, old.replace("emitted", "written"))
        self.assertEqual("", kind)
        self.assertEqual(("rows unchanged", []), (summary, detail))

    def test_a_column_added_to_every_row_moves_no_figure(self):
        """#361 added a column to the lyric baselines, rewriting every row
        without re-measuring anything."""
        old = ("lyric words MW carries:\n"
               "  sere.mp3: words 289  onset median 0.000s  anchors 59/59 line-level\n")
        new = ("lyric words MW carries:\n"
               "  sere.mp3: words 289  onset median 0.000s  line end max 4.780s"
               "  anchors 59/59 line-level, ends 57/57\n")
        summary, detail, kind = drift.describe(old, new)
        self.assertEqual("reshaped", kind, "no measurement moved, so no figure is stale")
        self.assertEqual("columns changed in 1 of 1 rows, no shared figure moved",
                         summary)
        self.assertIn("      + line end max #s", detail,
                      "the columns that changed are named, since a field whose "
                      "own shape changed cannot be compared with its old self")

    ADDED = "  new-one.mp3: bars=10  root 5.0/10 (50.0%)\n"

    def test_a_row_that_only_appeared_is_reported_rather_than_warned_about(self):
        """#478: a benchmark added to the corpus rewrites a baseline without
        re-measuring anything, and a figure quoted earlier cannot have come
        from a row that did not exist. It is still said out loud, quietly."""
        summary, _, kind = drift.describe(self.CHART, self.CHART + self.ADDED)
        self.assertEqual("added", kind)
        self.assertEqual("1 rows added, 0 removed", summary)

    def test_an_older_file_that_did_not_parse_is_loud_though_rows_only_appeared(self):
        """The gain arm is quiet because the rows it names did not exist. Where
        the older file simply did not parse, every row reads as gained and any
        figure in it may have moved -- so the quiet arm must not be reachable
        off a comparison with nothing on one side of it."""
        _, _, kind = drift.describe("h:\n  flat\n", self.CHART)
        self.assertEqual("moved", kind)

    def test_a_row_that_vanished_is_loud(self):
        """A figure quoted from a row that is gone is stale by definition, and
        a renamed benchmark presents as a removal beside a gain."""
        _, _, kind = drift.describe(self.CHART + self.ADDED, self.CHART)
        self.assertEqual("moved", kind)

    def test_rows_added_beside_a_figure_that_moved_stay_loud(self):
        """The gain is what makes the summary read innocently; the figure that
        moved in a row both files share is what makes it stale."""
        moved = self.CHART.replace("23.5/98 (24.0%)", "25.5/98 (26.0%)")
        summary, _, kind = drift.describe(self.CHART, moved + self.ADDED)
        self.assertEqual("moved", kind)
        self.assertIn("figures moved in 1 of 2 rows", summary)

    def test_rows_added_beside_a_column_that_changed_keep_the_column_warning(self):
        """Reshaped outranks added: 'check what you quoted from these columns'
        still applies to the rows that were already there."""
        reshaped = self.CHART.replace("(24.0%)\n", "(24.0%)  n 7\n")
        _, _, kind = drift.describe(self.CHART, reshaped + self.ADDED)
        self.assertEqual("reshaped", kind)

    def test_a_header_is_not_a_row(self):
        """Header lines are flush left and hold a colon of their own. Reading
        one as a row would key the whole corpus on a sentence."""
        self.assertEqual(["blues-a-90bpm.mp3", "bossa-cm.mp3"],
                         sorted(drift.rows(self.CHART)))

    def test_a_row_is_not_required_to_name_an_mp3(self):
        """The chart harness prints key rows too, and score-synthetic names
        packages. Keying on '.mp3:' would make those invisible."""
        rows = drift.rows("  key blues-a.wav: C major at 16%  want C major  OK\n")
        self.assertEqual(["key blues-a.wav"], list(rows))

    def test_two_columns_of_the_same_shape_stay_two_columns(self):
        """Two fields that mask to the same text are two fields. Folding them
        into one would make a column added beside them read as a moved
        figure -- the cry-wolf the keying exists to prevent."""
        old = "h:\n  a.mp3: bars 4  bars 9\n"
        new = "h:\n  a.mp3: bars 4  bars 9  bars 7\n"
        summary, _, kind = drift.describe(old, new)
        self.assertEqual("reshaped", kind)
        self.assertEqual("columns changed in 1 of 1 rows, no shared figure moved",
                         summary)

    def test_a_file_whose_rows_do_not_parse_is_loud(self):
        """An all-clear that rests on having understood nothing is the failure
        this prompt exists to prevent."""
        summary, _, kind = drift.describe("flat 1\n", "flat 2\n")
        self.assertEqual("moved", kind)
        self.assertIn("no row in it parsed", summary)

    def test_two_sections_may_print_the_same_row_name(self):
        """Overwriting would hide whichever section came first."""
        text = "h:\n  a.mp3: bars 4\nkeys:\n  a.mp3: C major  OK\n"
        self.assertEqual(2, len(drift.rows(text)))
        _, _, kind = drift.describe(text, text.replace("bars 4", "bars 9"))
        self.assertEqual("moved", kind, "the first section's row must not be hidden")


class PremergeComparison(unittest.TestCase):
    """The comparison premerge.sh embeds, executed rather than described.

    It is extracted from the script instead of copied here, because a copy is
    what let the defect this class exists for survive: the melody harness names
    its benchmarks after packages and clips, which carry no file extension, so
    every one of its rows was keyed by nothing, absent from both sides of the
    diff, and the step reported PASS however far the numbers had moved. It was
    blind from the first run of #494 and only CI's own plain diff was holding
    the baseline.
    """

    @staticmethod
    def compare(baseline_text: str, current_text: str) -> str:
        script = (Path(__file__).resolve().parent / "premerge.sh").read_text()
        marker = 'diffs=$(python3 - "$baseline" <<\'PY\' "$out"\n'
        self_test = script.split(marker, 1)
        if len(self_test) != 2:
            raise AssertionError("premerge.sh no longer embeds the comparison this way")
        body = self_test[1].split("\nPY\n", 1)[0]
        with tempfile.TemporaryDirectory() as tmp:
            baseline = Path(tmp) / "baseline.txt"
            baseline.write_text(baseline_text)
            program = Path(tmp) / "compare.py"
            program.write_text(body)
            done = subprocess.run([sys.executable, str(program), str(baseline), current_text],
                                  capture_output=True, text=True)
            if done.returncode != 0:
                raise AssertionError(done.stderr)
            return done.stdout

    MELODY_ROW = "  melody-level1-c-96: notes=96/96  F1@50ms 88.5%  pitch 96.0%\n"
    CHORD_ROW = "  gmajorblues.mp3: bars=136  root 96.3%\n"

    def test_a_melody_row_is_compared(self):
        moved = self.MELODY_ROW.replace("88.5%", "12.3%")
        self.assertIn("DIFF", self.compare(self.MELODY_ROW, moved))

    def test_an_unmoved_melody_row_is_quiet(self):
        self.assertNotIn("DIFF", self.compare(self.MELODY_ROW, self.MELODY_ROW))

    def test_a_chord_row_is_still_compared(self):
        moved = self.CHORD_ROW.replace("96.3%", "12.3%")
        self.assertIn("DIFF", self.compare(self.CHORD_ROW, moved))

    def test_a_row_naming_a_file_inside_a_column_is_still_compared(self):
        """score-samples prints 'phase <file>.mp3: ...' rows, which are keyed on
        the whole prefix. Widening the row test must not drop them."""
        row = "  phase blues-a-90bpm.mp3: beat 0 of 4 at 0.3633  root 85.8%\n"
        self.assertIn("DIFF", self.compare(row, row.replace("85.8%", "12.3%")))

    def test_a_preamble_is_not_a_row(self):
        """The melody harness's own headers, verbatim: a line of prose that
        became a row would be compared as if it were a benchmark."""
        for preamble in ("Melody, note by note against each package's own MIDI melody track\n",
                         "(seconds throughout: the beat grid is scored by the chart harnesses)\n",
                         " the tracker is monophonic and reads the loudest periodic line)\n"):
            self.assertEqual("", self.compare(preamble, preamble).strip(),
                             f"prose became a row: {preamble!r}")

    def test_a_local_only_melody_row_skips_rather_than_failing(self):
        """A machine without the 69 MB of vocadito audio must report every clip
        skipped, in the marker premerge turns into a SKIP, exactly as an absent
        recording does for the lyric harness."""
        row = melody.missing_clip_line(1)
        self.assertIn("vocadito_1", row.split(":")[0])
        skipped = self.compare(row + "\n", row + "\n")
        self.assertIn("SKIP", skipped)


class MelodyRules(unittest.TestCase):
    """What the melody harness counts as a hit, and how it reads vocadito."""

    def test_a_note_annotation_is_read_as_onset_offset_and_semitone(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "notes.csv"
            # 440 Hz is A4, MIDI 69; 261.626 is middle C, MIDI 60.
            path.write_text("0.5,440.0,0.25\n1.0,261.626,0.5\n")
            self.assertEqual([(0.5, 0.75, 69), (1.0, 1.5, 60)], melody.vocadito_notes(path))

    def test_a_pitch_between_semitones_is_rounded_once_at_the_edge(self):
        """Either side of the half-semitone boundary, because a sung pitch sits
        between semitones far more often than on one, and the rounding is what
        decides whether a hit is a hit."""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "notes.csv"
            path.write_text("0.0,452.0,0.1\n0.5,453.0,0.1\n")   # 47 and 50 cents sharp
            self.assertEqual([69, 70], [note[2] for note in melody.vocadito_notes(path)])

    def test_a_perfect_transcription_scores_one(self):
        notes = [(0.0, 0.5, 60), (0.5, 1.0, 62)]
        self.assertAlmostEqual(1.0, melody.note_f1(notes, notes, 0.05))

    def test_an_onset_outside_the_tolerance_is_not_a_hit(self):
        reference = [(0.0, 0.5, 60)]
        self.assertEqual(0.0, melody.note_f1([(0.2, 0.7, 60)], reference, 0.05))
        self.assertAlmostEqual(1.0, melody.note_f1([(0.04, 0.5, 60)], reference, 0.05))

    def test_the_right_time_at_the_wrong_pitch_is_not_a_hit(self):
        self.assertEqual(0.0, melody.note_f1([(0.0, 0.5, 61)], [(0.0, 0.5, 60)], 0.05))

    def test_one_estimate_cannot_answer_two_reference_notes(self):
        """Matched one to one, so a stage returning a single long note where
        two were sung scores recall of a half rather than of one."""
        reference = [(0.0, 0.5, 60), (0.02, 0.5, 60)]
        self.assertAlmostEqual(2 / 3, melody.note_f1([(0.0, 0.5, 60)], reference, 0.05))

    def test_the_signal_literals_exist_in_the_pipeline_itself(self):
        """The --separated loop classifies by what analyze prints, so those
        literals are held against the source that prints them: a rewording
        would otherwise make the loop skip every row, or -- worse -- score the
        mix melody against the separated baseline, in silence."""
        repo = Path(__file__).resolve().parent.parent
        printed = (
            (repo / "mw-transcribe/src/main/java/dev/olivelli/musicwizard/transcribe"
                    / "AudioTranscriber.java").read_text(encoding="utf-8")
            + (repo / "mw-cli/src/main/java/dev/olivelli/musicwizard/cli"
                    / "AnalyzeCommand.java").read_text(encoding="utf-8"))
        for literal in (melody.FROM_STEM, melody.FROM_MIX, melody.NOT_SEPARATED,
                        melody.NO_STEM, melody.NO_BEATS):
            self.assertIn(literal, printed)

    #: The lines analyze prints around the cause, verbatim, so the fixtures
    #: below are as long as the real thing: the row is bounded, and boilerplate
    #: between the marker and the provider's message eats the budget the cause
    #: needs. Their literals are pinned against the source by the test above.
    NO_PROVIDER_REPORT = ("  the melody is read from the full mix: no separation"
                          " provider; the tracker is monophonic, so it returns the"
                          " loudest periodic line rather than the voice\n"
                          "  tracking the melody in the full mix\n")
    SEPARATOR_FAILED = ("  separating the vocal with onnx-spleeter\n"
                        "  tracking the melody in the full mix\n"
                        "warning: the melody is read from the full mix, where the"
                        " tracker returns the loudest periodic line rather than the"
                        " voice; the vocal could not be separated: {}\n")

    #: Spleeter's own model URI, which every fetch failure below quotes.
    MODEL_URI = ("https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems"
                 "/resolve/main/vocals.onnx")

    #: What ModelCache raises, in its own words. The four are what a machine
    #: that cannot separate actually says, and the last two are the hard pair:
    #: identical for the length of a sentence and a URL, and distinct only at
    #: the very end.
    FETCH_FAILURES = (
        "model spleeter-2stems (vocals.onnx, 37 MB) is not in"
        " /home/x/.cache/music-wizard/models and ml.offline is set;"
        f" unset it to download from {MODEL_URI}",
        f"model spleeter-2stems downloaded from {MODEL_URI} does not match its"
        f" checksum (expected {'b' * 64}, got {'c' * 64}); refusing to keep it",
        f"could not download model spleeter-2stems from {MODEL_URI}: Connection reset",
        f"could not download model spleeter-2stems from {MODEL_URI}:"
        " No space left on device",
    )

    def test_a_skip_row_names_the_cause_rather_than_the_symptom(self):
        """Both reports carry "tracking the melody in the full mix"; what
        differs is why. A row that named the symptom would read identically
        for a machine with no model and for a separator that crashed."""
        self.assertIn("no separation provider",
                      melody.first_line(self.NO_PROVIDER_REPORT, melody.REASONS))
        self.assertIn("No space left", melody.first_line(
            self.SEPARATOR_FAILED.format(self.FETCH_FAILURES[3]), melody.REASONS))

    def test_no_two_causes_produce_the_same_skip_row(self):
        """The property the row exists for, held against the messages that make
        it hard rather than against fixtures short enough to fit the bound: a
        row keeping only the head of a fetch failure reads the same however the
        fetch failed, and the action it should have prompted -- unset offline,
        delete the file, free the disk -- is the part it dropped."""
        rows = {melody.unavailable_line("pop-axis-g-116", melody.first_line(
                    self.SEPARATOR_FAILED.format(message), melody.REASONS))
                for message in self.FETCH_FAILURES}
        self.assertEqual(len(self.FETCH_FAILURES), len(rows),
                         f"causes sharing a row: {rows}")
        for row in rows:
            # Bounded, so a message of any length cannot wrap the row.
            self.assertLess(len(row), 220)

    def test_the_fetch_failures_are_quoted_from_the_cache_that_raises_them(self):
        """The fixtures above are only worth what they resemble, so their
        invariant halves are held against the source that builds them."""
        repo = Path(__file__).resolve().parent.parent
        cache = (repo / "mw-ml/src/main/java/dev/olivelli/musicwizard/ml"
                 / "ModelCache.java").read_text(encoding="utf-8")
        for fragment in (" MB) is not in ", " and ml.offline is set; unset it to"
                         " download from ", "could not download model ",
                         " does not match its checksum (expected ",
                         "); refusing to keep it"):
            self.assertIn(fragment, cache)
        models = (repo / "mw-ml/src/main/java/dev/olivelli/musicwizard/ml"
                  / "SpleeterModels.java").read_text(encoding="utf-8")
        # Split across two lines in the source, so held in halves.
        self.assertIn("https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems",
                      models)
        self.assertIn("/resolve/main/vocals.onnx", models)

    def test_a_machine_that_cannot_separate_skips_rather_than_scoring_the_mix(self):
        """The separated loops need a model this machine may not have. Scoring
        the mix melody against their baseline would report a missing download
        as a regression, so the row is a skip -- keyed, and in the marker
        premerge turns into one."""
        row = melody.unavailable_line("melody-level1-c-96", "no separation provider")
        self.assertIn(": not present (local-only", row)
        self.assertEqual("melody-level1-c-96", row.split(":")[0].strip())
        self.assertIn("no separation provider", row)
        # Bounded, so a stack trace pasted as the reason cannot wrap the row.
        self.assertLess(len(melody.unavailable_line("x", "y" * 500)), 220)

    def test_the_pinned_loops_hand_analyze_the_flag_that_pins_them(self):
        """Without --skip-separation the default rows would measure whatever
        separation the machine could do, against a baseline CI regenerates
        without one."""
        class Stop(Exception):
            pass

        seen = []

        def record(command, **kwargs):
            seen.append(command)
            if "analyze" in command:
                raise Stop
            return subprocess.CompletedProcess(command, 0, "", "")

        with mock.patch.object(melody.subprocess, "run", record):
            for separated in (False, True):
                with self.assertRaises(Stop):
                    melody.analyze(Path("mw.jar"), Path("x.mp3"), separated=separated)
        pinned, through_the_stem = seen[1], seen[3]
        self.assertIn("--skip-separation", pinned)
        self.assertNotIn("--skip-separation", through_the_stem)


class SeparationRatio(unittest.TestCase):
    """The level arithmetic behind #505.

    The tool it belongs to cannot run in CI — it needs a jar, Spleeter and a
    corpus that is local-only — so what CI can hold is the arithmetic that
    decides what it mixes, on fixtures written here.
    """

    def require_ffmpeg(self):
        """Skip where ffmpeg is absent — unless someone has said it must not be.

        These tests exist because nothing reached the mixing layer and a defect
        lived there for a whole PR. A runner without ffmpeg skips them green,
        which is that same silence wearing a different hat, so the job that is
        supposed to exercise them sets MW_REQUIRE_FFMPEG and gets a failure
        instead of a skip.
        """
        if shutil.which("ffmpeg"):
            return
        # Not truthiness: MW_REQUIRE_FFMPEG=0 has to mean what it says.
        if os.environ.get("MW_REQUIRE_FFMPEG", "") not in ("", "0"):
            self.fail("MW_REQUIRE_FFMPEG is set and ffmpeg is not on PATH")
        self.skipTest("ffmpeg is not on PATH")

    @staticmethod
    def tone(path, seconds, amplitude, rate=44100, loud=None):
        """A wav of silence with a full-length or partial sine in it."""
        samples = array.array("h", [0]) * int(seconds * rate)
        first, last = loud if loud else (0, seconds)
        for index in range(int(first * rate), int(last * rate)):
            samples[index] = int(amplitude * 32767
                                 * math.sin(2 * math.pi * 440 * index / rate))
        with wave.open(str(path), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(rate)
            out.writeframes(samples.tobytes())

    def test_a_full_scale_sine_reads_three_decibels_under_full_scale(self):
        """Pins the convention: dBFS of an RMS, so a full-scale sine is -3, not 0."""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "tone.wav"
            self.tone(path, 1.0, 1.0)
            level = separation.sung_rms_dbfs(path, [(0.0, 1.0, 69)])
            self.assertAlmostEqual(-3.01, level, places=1)

    def test_the_level_is_measured_where_the_singing_is(self):
        """The point of measuring over the notes rather than over the clip: a
        clip that is mostly silence is not thereby a quiet singer."""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sparse.wav"
            self.tone(path, 4.0, 1.0, loud=(1.0, 2.0))
            over_the_note = separation.sung_rms_dbfs(path, [(1.0, 2.0, 69)])
            over_the_clip = separation.sung_rms_dbfs(path, [(0.0, 4.0, 69)])
            self.assertAlmostEqual(-3.01, over_the_note, places=1)
            # Three quarters silence is 6 dB of dilution, and it is exactly the
            # error the old whole-clip reading made.
            self.assertAlmostEqual(-9.03, over_the_clip, places=1)
            self.assertGreater(over_the_note - over_the_clip, 5)

    def test_a_clip_whose_annotation_covers_no_audio_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "tone.wav"
            self.tone(path, 1.0, 1.0)
            with self.assertRaises(SystemExit):
                separation.sung_rms_dbfs(path, [])

    def test_the_gain_puts_the_voice_the_asked_for_distance_above_the_bed(self):
        for voice, bedside, ratio in ((-20.0, -20.0, 0.0), (-6.0, -30.0, 6.0),
                                      (-34.3, -18.0, -12.0)):
            gain = separation.bed_gain(voice, bedside, ratio)
            # The bed after the gain, in dBFS, and the distance from the voice.
            achieved = voice - (bedside + 20 * math.log10(gain))
            self.assertAlmostEqual(ratio, achieved, places=6,
                                   msg=f"voice {voice} bed {bedside} ratio {ratio}")

    def test_equal_levels_at_a_zero_ratio_leave_the_bed_alone(self):
        self.assertAlmostEqual(1.0, separation.bed_gain(-20.0, -20.0, 0.0))

    @staticmethod
    def stereo_tone(path, seconds, amplitude, correlated, rate=44100):
        """A two-channel bed, its channels either identical or independent."""
        frames = array.array("h")
        for index in range(int(seconds * rate)):
            left = int(amplitude * 32767 * math.sin(2 * math.pi * 300 * index / rate))
            right = left if correlated else int(
                amplitude * 32767 * math.sin(2 * math.pi * 190 * index / rate))
            frames.append(left)
            frames.append(right)
        with wave.open(str(path), "wb") as out:
            out.setnchannels(2)
            out.setsampwidth(2)
            out.setframerate(rate)
            out.writeframes(frames.tobytes())

    @staticmethod
    def silence(path, seconds, rate=44100):
        with wave.open(str(path), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(rate)
            out.writeframes((array.array("h", [0]) * int(seconds * rate)).tobytes())

    @staticmethod
    def whole_file_rms_dbfs(path):
        with wave.open(str(path)) as source:
            samples = array.array("h", source.readframes(source.getnframes()))
        total = sum(value * value for value in samples)
        return 20 * math.log10(math.sqrt(total / len(samples)) / 32768.0)

    def assert_bed_lands_where_it_was_measured(self, correlated):
        """The measured stream and the mixed stream must be the same one.

        This is the layer the unit tests did not reach and where the defect
        lived: `bed_gain`'s arithmetic was right, and `amix` was quietly
        converting a stereo bed to mono on its way in — in the float domain,
        where the two channels are summed at 1/sqrt(2) each without being
        renormalised. A bed whose channels are correlated therefore arrived 3 dB
        above what it had been measured at, and every ratio the tool printed was
        wrong by that much. With independent channels the same conversion is
        very nearly right, which is why one bed can look fine and another not.

        Asserted with a SILENT voice, so the mix holds the bed alone and its
        level can be read directly rather than unmixed.
        """
        self.require_ffmpeg()
        with tempfile.TemporaryDirectory() as tmp:
            voice = Path(tmp) / "voice.wav"
            bed = Path(tmp) / "bed.wav"
            mixed = Path(tmp) / "mixed.wav"
            self.silence(voice, 2.0)
            self.stereo_tone(bed, 2.0, 0.5, correlated=correlated)
            with mock.patch.object(separation, "BED_OFFSET_SECONDS", 0):
                measured = separation.segment_rms_dbfs(bed, 0, 2.0)
                # A stated voice level, since the voice here is silence: the
                # bed must land six decibels under it.
                gain = separation.bed_gain(-20.0, measured, 6.0)
                separation.mix(voice, bed, gain, mixed)
            self.assertAlmostEqual(-26.0, self.whole_file_rms_dbfs(mixed), delta=0.3,
                                   msg=f"correlated={correlated}")

    def test_a_correlated_stereo_bed_lands_at_the_ratio_it_was_given(self):
        self.assert_bed_lands_where_it_was_measured(correlated=True)

    def test_an_uncorrelated_stereo_bed_lands_at_the_ratio_it_was_given(self):
        self.assert_bed_lands_where_it_was_measured(correlated=False)

    def test_a_quieter_singer_asks_for_a_quieter_bed(self):
        """The defect #505 names, as an assertion: the bed follows the voice's
        own level rather than a constant, so two clips recorded far apart are
        mixed at the same ratio rather than at the same bed gain."""
        loud = separation.bed_gain(-16.8, -20.0, 0.0)
        quiet = separation.bed_gain(-34.3, -20.0, 0.0)
        self.assertGreater(loud, quiet)

    def test_peak_reads_a_signal_that_has_headroom(self):
        """`peak` is what the refusal is decided on, so it is asserted against
        levels known by construction rather than trusted. Two of them, because
        one is also what a `peak` that ignored its argument would return."""
        self.require_ffmpeg()
        with tempfile.TemporaryDirectory() as tmp:
            for amplitude, expected in ((0.5, -6.0), (0.125, -18.1)):
                path = Path(tmp) / f"tone_{amplitude}.wav"
                self.tone(path, 1.0, amplitude)
                self.assertAlmostEqual(expected, separation.peak(path), delta=0.2)
                self.assertFalse(separation.railed(separation.peak(path)))

    def test_a_mix_that_clamps_is_railed(self):
        """Two full-scale signals summed cannot fit, and the guard has to see
        it in what ffmpeg reports rather than in the arithmetic that caused it:
        `mix` writes 16-bit PCM, so the clamp is already done by the time the
        peak is read."""
        self.require_ffmpeg()
        with tempfile.TemporaryDirectory() as tmp:
            voice, bed, mixed = (Path(tmp) / n for n in ("v.wav", "b.wav", "m.wav"))
            self.tone(voice, 2.0, 0.99)
            self.stereo_tone(bed, 2.0, 0.99, correlated=True)
            with mock.patch.object(separation, "BED_OFFSET_SECONDS", 0):
                separation.mix(voice, bed, 1.0, mixed)
            self.assertTrue(separation.railed(separation.peak(mixed)))

    def test_the_rail_is_a_margin_rather_than_an_equality(self):
        """Pins the constant, which nothing else in this file reaches.

        `volumedetect` reports to a tenth of a decibel, so a clamped mix reads
        zero at that resolution and so does one peaking a twentieth of a decibel
        short: the report cannot separate them, and the threshold is the margin
        that decision needs rather than a measurement of distortion. Asserted
        here so that dropping the margin is a test failure rather than a silent
        change of what the tool refuses.
        """
        self.assertTrue(separation.railed(-0.1))
        self.assertFalse(separation.railed(-0.5))


class SyntheticTempo(unittest.TestCase):
    """The tempo column (#453): the parse and the ratio, on fixtures."""

    def test_the_tempo_is_read_from_the_line_analyze_prints(self):
        printed = "Key     G major\nTempo   116.0 BPM\nMeter   4/4\n"
        self.assertEqual(116.0, synthetic.printed_tempo(printed))

    def test_a_meter_counted_in_something_else_gives_up_its_quarter_tempo(self):
        """`formatTempo` prints the counted tempo first in a compound meter and
        the quarter tempo in the parentheses. The spec's tempo is in quarters --
        everything downstream of the beat grid is -- so the parenthesised figure
        wins where there is one. No package is in 6/8 yet; this is what stops
        the first one being scored against the wrong number."""
        printed = "Tempo   80.0 BPM (240.0 quarter notes/min)\n"
        self.assertEqual(240.0, synthetic.printed_tempo(printed))

    def test_a_tempo_that_changes_is_not_reported_as_a_constant_one(self):
        """The MIDI path prints its tempo through `statedTempo`, which for a
        file that changes tempo reads "140.0 BPM at the start, changed 3 times
        later" -- the wording MidiInputTest asserts. An unanchored pattern
        takes the 140.0 off that and states it as though the piece held it. No
        package is analysed from MIDI today, so nothing but this assertion
        holds the anchor in place."""
        self.assertIsNone(synthetic.printed_tempo(
            "Tempo   140.0 BPM at the start, changed 3 times later"))

    def test_no_tempo_line_is_not_a_tempo_of_zero(self):
        self.assertIsNone(synthetic.printed_tempo("Key     C major\n"))
        self.assertEqual("tempo none/96", synthetic.tempo_verdict(None, "96"))

    def test_a_doubled_grid_reads_as_a_doubling(self):
        """The reading this column exists to name: a grid at twice the written
        tempo, which is what pop-deceptive-f-72 read until #509. The ratio says
        so without the reader dividing."""
        self.assertEqual("tempo 144.1/72 (x2.00)",
                         synthetic.tempo_verdict(144.1, "72"))

    def test_the_pair_is_what_shows_a_drift_the_ratio_rounds_away(self):
        """108.1 against 108 is a ratio of 1.0009, which prints as x1.00 — so a
        drift of up to half a percent does hide in the ratio, and the pair
        beside it is what shows it. Both are printed for that reason."""
        self.assertEqual("tempo 108.1/108 (x1.00)",
                         synthetic.tempo_verdict(108.1, "108"))

    def test_a_rate_that_is_no_musical_multiple_is_not_rounded_to_one(self):
        """2.16 is neither a double nor a drift, and the column must not round
        it toward either: melody-level2pad-g-84 reads this, which is a rate
        that is simply wrong rather than an octave error."""
        self.assertEqual("tempo 181.3/84 (x2.16)",
                         synthetic.tempo_verdict(181.3, "84"))

    def test_a_spec_with_no_tempo_says_so_rather_than_dividing_by_it(self):
        self.assertEqual("tempo unstated", synthetic.tempo_verdict(120.0, None))


if __name__ == "__main__":
    unittest.main()
