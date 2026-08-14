#!/usr/bin/env python3
"""Tests for the rules the sample harnesses score by.

The harnesses themselves cannot run in CI -- the benchmarks they need are
local-only for licensing -- so what CI can check is the arithmetic they apply,
on fixtures written here: a bar for the two chord harnesses, a word and its
onset for the lyric one. Run it directly:

    python3 tools/test-harness-rules.py
"""

import contextlib
import io
import re
import sys
import unittest
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
samples = import_module("score-samples")
synthetic = import_module("score-synthetic")
chart = import_module("score-chart")
lyrics = import_module("score-lyrics")
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
    """premerge.sh keys each line on the text before its first colon and reads
    only lines holding '.mp3:'. Both halves of that are executed here rather
    than asserted in a comment."""

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


class BaselineDrift(unittest.TestCase):
    """premerge.sh prompts when main regenerated a baseline during this
    branch's life. What it must get right is the kind of change: a column
    added to every row rewrites the file without moving a measurement, and a
    prompt that calls that a moved figure is one people learn to skip."""

    CHART = ("charts emitted for samples with known ground truth:\n"
             "  blues-a-90bpm.mp3: bars=113  chords/bar 1.32  root 93.0/113 (82.3%)\n"
             "  bossa-cm.mp3: bars=98  chords/bar 1.23  root 23.5/98 (24.0%)\n")

    def status(self, old: str, new: str) -> int:
        """main()'s exit status for one file, with git and stdout stubbed."""
        show, out = drift.show, io.StringIO()
        drift.show = lambda rev, _path: old if rev == "base" else new
        try:
            with contextlib.redirect_stdout(out):
                return drift.main(["x", "base", "tip", "tools/baselines/a.txt"])
        finally:
            drift.show = show

    def test_the_quiet_statuses_are_ones_python_cannot_produce_by_accident(self):
        """premerge.sh keys its two quiet arms on these numbers, and python
        exits 2 of its own accord when it cannot open the script it was given.
        A classifier that never ran must not read as one that found nothing."""
        row = "h:\n  a.mp3: bars 4  root 1.0/2 (50.0%)\n"
        self.assertEqual(1, self.status(row, row.replace("1.0/2 (50.0%)",
                                                         "2.0/2 (100.0%)")))
        self.assertEqual(3, self.status(row, row.replace("(50.0%)\n",
                                                         "(50.0%)  n 7\n")))
        self.assertEqual(0, self.status(row, row.replace("h:", "header:")))

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

    def test_a_row_that_appeared_counts_as_re_measured(self):
        summary, _, kind = drift.describe(
            self.CHART, self.CHART + "  new-one.mp3: bars=10  root 5.0/10 (50.0%)\n")
        self.assertEqual("moved", kind)
        self.assertEqual("1 rows added, 0 removed", summary)

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


if __name__ == "__main__":
    unittest.main()
