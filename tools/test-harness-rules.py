#!/usr/bin/env python3
"""Tests for the rules the two sample harnesses score bars by.

The harnesses themselves cannot run in CI -- four of the benchmarks are
local-only for licensing -- so what CI can check is the arithmetic they apply
to a bar, on fixtures written here. Run it directly:

    python3 tools/test-harness-rules.py
"""

import sys
import unittest
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
samples = import_module("score-samples")
chart = import_module("score-chart")

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


if __name__ == "__main__":
    unittest.main()
