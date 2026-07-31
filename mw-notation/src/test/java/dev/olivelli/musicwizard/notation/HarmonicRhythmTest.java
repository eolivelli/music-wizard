/*
 * Copyright 2026 Music Wizard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the chart writes in a bar, as opposed to what sounds in it.
 *
 * <p>#212. The estimator is beat-synchronous and disagrees with itself between
 * beats, so before this the chart printed every span it produced — two to three
 * chords a bar on all five sample recordings, against music that changes about
 * once a bar. Harmony that was mostly right read as noise.
 *
 * <p>The fixtures here are stated in seconds at a constant 120 BPM, so one bar
 * is two seconds and one counted beat is half of one. Each is a single 4/4 bar
 * unless it says otherwise, which keeps the assertion about the rule and not
 * about where a bar line fell — {@link ChordChartTest} owns that question.
 *
 * <p>The rule's cost is asserted here too, not only its benefit. A test suite
 * that only showed the chatter going would pass equally on a rule that wrote one
 * chord a bar unconditionally, and that rule is wrong: a bar the music really
 * does split has to come out split.
 */
class HarmonicRhythmTest {

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    /**
     * A score whose chords run back to back from zero, one per stated length in
     * counted beats, in the stated meter at 120 quarter notes a minute.
     *
     * <p>Back to back and starting at the first bar line on purpose: a gap
     * before the first chord makes its bar the lead-in, which
     * {@link ChartLayout} deliberately does not reduce, and every fixture here
     * is about a bar that is reduced.
     */
    private static Score bar(TimeSignature meter, NoteLetter[] roots, double... beats) {
        double quarter = 0.5;
        List<Chord> chords = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < beats.length; i++) {
            chords.add(Chord.ofSeconds(root(roots[i]), ChordQuality.MAJOR,
                    at * quarter, (at + beats[i]) * quarter, Confidence.of(0.9)));
            at += beats[i];
        }
        return Score.empty(TempoMap.constant(120, meter), at * quarter)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    private static final NoteLetter[] C_G = {NoteLetter.C, NoteLetter.G};
    private static final NoteLetter[] C_G_A = {NoteLetter.C, NoteLetter.G, NoteLetter.A};
    private static final NoteLetter[] C_G_A_F =
            {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
    private static final NoteLetter[] C_G_C = {NoteLetter.C, NoteLetter.G, NoteLetter.C};

    @Test
    @DisplayName("a bar the estimator chattered in is written as the chord it mostly said")
    void aChatteringBarIsWrittenAsItsMajority() {
        // Three of four beats G, one beat C. This is the shape #212 is about:
        // measured over the five benchmarks, the chart printed 2.01 to 3.03
        // chords a bar and the music changes about once a bar.
        assertThat(ChordChart.barLines(bar(TimeSignature.FOUR_FOUR, C_G, 1, 3)))
                .containsExactly("| G           |");
        // And the other way round, so the rule is not "keep the last".
        assertThat(ChordChart.barLines(bar(TimeSignature.FOUR_FOUR, C_G, 3, 1)))
                .containsExactly("| C           |");
        // Three distinct chords, none of which holds half the bar: the chart
        // says the one that holds most of it rather than all three. A coverage
        // threshold would have kept all three here, because a division into
        // counted beats covers this bar exactly.
        assertThat(ChordChart.barLines(bar(TimeSignature.FOUR_FOUR, C_G_A, 1, 2, 1)))
                .containsExactly("| G           |");
    }

    @Test
    @DisplayName("a bar the music really splits is still written split")
    void aHalfBarChangeSurvives() {
        // Two beats each. The cost of the second symbol is 0.3 of a bar and it
        // buys half of one, so it is written -- which is what stops the rule
        // being "one chord a bar" with extra steps.
        assertThat(ChordChart.barLines(bar(TimeSignature.FOUR_FOUR, C_G, 2, 2)))
                .containsExactly("| C G         |");
        assertThat(ChordChart.toLilyPond(bar(TimeSignature.FOUR_FOUR, C_G, 2, 2)))
                .contains("c2 g2 |");
    }

    @Test
    @DisplayName("a one-beat chord survives in 3/4 and 6/8, where it is a bigger share")
    void aCountedBeatSurvivesWhereItIsWorthMore() {
        // The same constant reads differently per meter because the meters
        // differ, not because it is set per meter: one beat of three is a third
        // of the bar and clears 0.3, one of four is a quarter and does not. That
        // is a consequence worth pinning rather than discovering.
        assertThat(ChordChart.barLines(bar(TimeSignature.THREE_FOUR, C_G, 2, 1)))
                .containsExactly("| C G         |");
        // 6/8 is counted in two dotted quarters, so its bar offers one division
        // into halves and a chord holding one of them is half the bar.
        assertThat(ChordChart.barLines(bar(TimeSignature.SIX_EIGHT, C_G, 1.5, 1.5)))
                .containsExactly("| C G         |");
    }

    @Test
    @DisplayName("a bar whose harmony really does change every beat loses it")
    void aChordOnEveryBeatIsNotWritten() {
        // The cost of the rule, and it is the one an exact MIDI import pays:
        // four genuine changes in a 4/4 bar are written as one chord, because
        // nothing the chart can see distinguishes them from four estimates that
        // disagreed. #213 carries the provenance that would.
        assertThat(ChordChart.barLines(bar(TimeSignature.FOUR_FOUR, C_G_A_F, 1, 1, 1, 1)))
                .containsExactly("| C           |");
    }

    @Test
    @DisplayName("the written bars still sum to their meter, so the bar check stays a check")
    void everyWrittenBarFillsItsMeter() {
        // The reduction rewrites cell lengths, and a bar that no longer sums is
        // a bar LilyPond rejects -- which is the whole apparatus of #160 and
        // #163.
        //
        // Every fixture here is one the reduction actually rewrites, and the
        // assertion below says so rather than trusting it. Round 1 of review
        // found that was not true of the first draft: it named 3/4, 5/4 and 7/8
        // as the awkward meters and then chose bars in them that came out of the
        // reduction identical to what went in, so only the 4/4 case tested
        // anything.
        //
        // Getting that wrong is easy because the layout has already snapped its
        // chord positions onto a grid before any of this runs, and in most meters
        // that grid is the same counted beat the slots are. A fixture whose
        // boundaries the snapping alone tidies is inert here. The fifth is the
        // one where the reduction genuinely invents a length: a half-beat chord
        // between two others drags the layout grid below the counted beat, and
        // the bar comes out as 3 + 2 where 2.5 + 0.5 + 2 went in.
        List<Score> scores = List.of(
                bar(TimeSignature.FOUR_FOUR, C_G_A, 1, 2, 1),
                bar(TimeSignature.THREE_FOUR, C_G_C, 1, 1, 1),
                bar(TimeSignature.SIX_EIGHT, C_G, 1, 2),
                bar(new TimeSignature(5, 4), C_G_C, 2, 1, 2),
                bar(new TimeSignature(5, 4), C_G_A, 2.5, 0.5, 2),
                bar(new TimeSignature(7, 8), C_G_C, 1.5, 0.5, 1.5));
        for (Score score : scores) {
            double meter = score.tempoMap().initialTimeSignature().quarterBeatsPerBar();
            List<ChartLayout.Bar> written = ChartLayout.of(score);
            // The fixture has to reach the code under test, so say so rather than
            // trusting it: a bar the reduction handed back untouched sums for a
            // reason that has nothing to do with this.
            assertThat(ChordChart.lilyPondOf(score, written))
                    .as("a bar of %s the reduction rewrites", meter)
                    .isNotEqualTo(ChordChart.lilyPondOf(score, ChartLayout.unreduced(score)));
            for (ChartLayout.Bar bar : written) {
                double sum = bar.cells().stream()
                        .mapToDouble(ChartLayout.Cell::lengthQuarters)
                        .sum();
                assertThat(sum).as("%s in %s", bar.cells(), meter).isEqualTo(meter);
            }
        }
    }

    @Test
    @DisplayName("a bar is never left holding two cells with the same symbol")
    void equalNeighbouringSlotsMerge() {
        // Four beats of one chord reaches the finest division as four equal
        // slots. Left unmerged the page would print "C C C C" where the text
        // chart says "| C |", which is the two outputs disagreeing -- #174's
        // signature -- and four quarter notes where a whole note was played.
        Score score = bar(TimeSignature.FOUR_FOUR, C_G, 4, 4);
        assertThat(ChordChart.barLines(score)).containsExactly("| C           | G           |");
        assertThat(ChordChart.toLilyPond(score)).contains("c1 |").contains("g1 |");
    }

    @Test
    @DisplayName("the text chart and the engraving name the same chords after reducing")
    void bothOutputsAgreeOnWhatSurvived() {
        // The naming pass runs after the reduction, and it has to: deciding which
        // cell carries the name first, then dropping that cell, left the text
        // chart writing "%" for a chord it had never printed while the page --
        // which applies its own copy of the rule through chordChanges -- printed
        // it. Two bars of C either side of a bar the reduction rewrites.
        Score score = bar(TimeSignature.FOUR_FOUR,
                new NoteLetter[] {NoteLetter.C, NoteLetter.G, NoteLetter.C, NoteLetter.C},
                4, 1, 3, 4);
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | %           | %           |");
        // Named once on the page too: chordChanges suppresses the repeats, so
        // the source carries three bars and one c.
        assertThat(ChordChart.toLilyPond(score)).contains("c1 |\n      c1 |\n      c1 |");
    }

    @Test
    @DisplayName("a bar whose named cell the reduction drops does not leave the name behind")
    void aDroppedNameIsRecomputedRatherThanKept() {
        // Round 1 of review, and it reached real output: five bars across the
        // five sample recordings printed a chord change in the text that the
        // engraved page did not.
        //
        // The bar in the middle is laid out as C for three beats then G for one,
        // so the *first* cell is the one that names -- and the reduction keeps
        // that cell's chord and drops the G. A naming pass that only ever sets
        // the flag then leaves the surviving C still marked named, and the text
        // chart writes "C" where a reader is owed "%". The engraving reaches the
        // other answer, because chordChanges recomputes the rule from the symbols
        // it is handed. Reversing the middle bar to (G 1, C 3) hides the defect
        // entirely, which is why the fixture is this way round.
        Score score = bar(TimeSignature.FOUR_FOUR,
                new NoteLetter[] {NoteLetter.C, NoteLetter.C, NoteLetter.G, NoteLetter.C},
                4, 3, 1, 4);
        assertThat(ChordChart.barLines(score))
                .containsExactly("| C           | %           | %           |");
        assertThat(ChordChart.toLilyPond(score)).contains("c1 |\n      c1 |\n      c1 |");
    }

    @Test
    @DisplayName("the text chart and the page name a chord in exactly the same places")
    void theTextAndThePageNeverDisagreeAboutANameChange() {
        // The property behind the two fixtures above, stated over shapes rather
        // than over an instance, because the defect it guards was found on real
        // recordings and not on either fixture.
        //
        // Round 2 of review found the first version of this test unable to fail
        // for the defect, and the reason is worth keeping because it is the trap
        // rather than a slip. It generated only bars of one chord per counted
        // beat, and the reduction rewrites every such bar -- while the defect
        // only ever showed on a bar the reduction hands *back untouched*, which
        // is the one case where the old naming pass and the new one behave
        // identically. Tens of thousands of generated scores therefore found
        // nothing against deliberately broken code. Round 2 also measured that
        // the untouched shape is a large minority of bars on every one of the
        // five sample recordings, and that every real disagreement the fix
        // removed was of it, so the omission was not an exotic corner.
        //
        // So every score here pairs a bar of one chord per counted beat with a
        // bar holding a single chord, in both orders. Only one of those orders
        // can reach the defect -- with the single-chord bar first there is no
        // predecessor, so its cell is named in both passes and the stale branch
        // is never taken -- and the reversed order is carried as a control on
        // the rest of the composition rather than as evidence. Round 3 measured
        // that too, and the comment here claimed both orders before it did.
        //
        // The counting below is round 3's finding and it is the same lesson a
        // third time. Rebuilding the population was not enough: whether it can
        // construct the precondition at all depends on EXTRA_CHORD_COST, which
        // this class's own javadoc invites a maintainer to move. Set that to
        // 0.05 and every one of these scores goes inert -- the reduction stops
        // dropping anything, no predecessor ever changes, and the test passes
        // against deliberately broken code without a word. So the population is
        // made to prove its own power rather than asserted to have it, which is
        // what everyWrittenBarFillsItsMeter already does one test along and what
        // this one was missing.
        //
        // Two of the five meters cannot construct it either, and are carried
        // as controls for the same reason. In 3/4 and 6/8 at this cost a chord
        // holding one counted beat always earns its place, so the reduction
        // never drops one and never changes a bar's last symbol -- which is the
        // fact aCountedBeatSurvivesWhereItIsWorthMore asserts, seen from behind.
        // So the count is required to be positive in 4/4, 5/4 and 7/8 and is
        // expected to be zero in the other two.
        NoteLetter[] alphabet = {NoteLetter.C, NoteLetter.G, NoteLetter.A};
        Map<TimeSignature, Integer> reachedTheDefect = new LinkedHashMap<>();
        for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR,
                TimeSignature.SIX_EIGHT, new TimeSignature(5, 4), new TimeSignature(7, 8))) {
            int beats = meter.beatsPerBar();
            reachedTheDefect.put(meter, 0);
            for (boolean wholeBarFirst : List.of(false, true)) {
                for (int shape = 0; shape < Math.pow(alphabet.length, beats + 1); shape++) {
                    NoteLetter[] roots = new NoteLetter[beats + 1];
                    double[] lengths = new double[beats + 1];
                    int rest = shape;
                    for (int i = 0; i < roots.length; i++) {
                        roots[i] = alphabet[rest % alphabet.length];
                        rest /= alphabet.length;
                    }
                    // One bar of a chord to a counted beat, and one bar holding a
                    // single chord, which enters the second naming pass exactly
                    // as the reduction found it.
                    for (int i = 0; i < beats; i++) {
                        lengths[wholeBarFirst ? i + 1 : i] = meter.beatUnitQuarters();
                    }
                    lengths[wholeBarFirst ? 0 : beats] = meter.quarterBeatsPerBar();
                    Score score = bar(meter, roots, lengths);
                    assertBothOutputsNameTheSameCells(score);
                    if (couldStrandAName(score)) {
                        reachedTheDefect.merge(meter, 1, Integer::sum);
                    }
                }
            }
        }

        for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR,
                new TimeSignature(5, 4), new TimeSignature(7, 8))) {
            assertThat(reachedTheDefect.get(meter))
                    .as("scores in %s that put a stale name within reach; if this is zero the "
                            + "sweep above proves nothing and EXTRA_CHORD_COST has moved", meter)
                    .isPositive();
        }
    }

    /**
     * Whether a score contains the shape the naming defect needed: a bar the
     * reduction hands back untouched, whose first symbol the reduction has just
     * changed the predecessor of.
     *
     * <p>Both halves are required and neither alone is the defect. The untouched
     * bar is what carries a flag from the first naming pass into the second; the
     * changed predecessor is what makes that flag wrong. Specifically the first
     * pass sees a different symbol before this cell and marks it named, and the
     * second should see an equal one and not — so the text chart prints a chord
     * change the page does not.
     */
    private static boolean couldStrandAName(Score score) {
        List<ChartLayout.Bar> laid = ChartLayout.unreduced(score);
        List<ChartLayout.Bar> written = ChartLayout.of(score);
        String laidPrevious = null;
        String writtenPrevious = null;
        for (int i = 0; i < written.size(); i++) {
            List<ChartLayout.Cell> before = laid.get(i).cells();
            List<ChartLayout.Cell> after = written.get(i).cells();
            if (i > 0 && handedBack(before, after)
                    && !after.get(0).symbol().equals(laidPrevious)
                    && after.get(0).symbol().equals(writtenPrevious)) {
                return true;
            }
            laidPrevious = before.get(before.size() - 1).symbol();
            writtenPrevious = after.get(after.size() - 1).symbol();
        }
        return false;
    }

    /**
     * Whether the reduction returned a bar as it found it, compared on what it
     * decides -- the chords and their lengths. Not on {@code named}, which both
     * passes rewrite and which is the very thing under test.
     */
    private static boolean handedBack(List<ChartLayout.Cell> before,
                                      List<ChartLayout.Cell> after) {
        if (before.size() != after.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).chord().equals(after.get(i).chord())
                    || before.get(i).lengthQuarters() != after.get(i).lengthQuarters()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Asserts that the text chart prints a chord name in exactly the cells at
     * which a reader of the engraved page sees one.
     *
     * <p>Both real emitters, read back out of what they wrote, rather than
     * {@link ChartLayout.Cell#named()} compared against the rule that sets it --
     * which round 2 of review pointed out is the implementation's own expression
     * copied, and can only ever check the composition rather than the answer.
     * The page's rule is {@code chordChanges}, which prints a name wherever the
     * chord differs from the previous event's, so it is recovered here by
     * stripping the duration off each {@code chordmode} token and comparing.
     *
     * <p>No fixture here has a lead-in gap, so no bar holds a rest. That matters
     * because a rest is the one legitimate difference between the two: the text
     * writes {@code N.C.} and the page writes {@code r}, which carries no name.
     */
    private static void assertBothOutputsNameTheSameCells(Score score) {
        List<Integer> onThePage = new ArrayList<>();
        String previous = null;
        for (String line : chordModeOf(ChordChart.toLilyPond(score))) {
            int named = 0;
            for (String token : line.substring(0, line.length() - 1).trim().split(" +")) {
                String chord = chordOf(token);
                if (!chord.equals(previous)) {
                    named++;
                }
                previous = chord;
            }
            onThePage.add(named);
        }

        List<Integer> inTheText = new ArrayList<>();
        for (String line : ChordChart.barLines(score)) {
            for (String cell : line.strip().split("\\|")) {
                if (!cell.isBlank()) {
                    // "%" is the text chart saying it named nothing in this bar.
                    inTheText.add(cell.strip().equals("%") ? 0 : cell.strip().split(" +").length);
                }
            }
        }

        assertThat(inTheText).as("%s", ChordChart.barLines(score)).isEqualTo(onThePage);
    }

    /** The bars of a chart's {@code \chordmode} block, {@code \time} lines dropped. */
    private static List<String> chordModeOf(String source) {
        List<String> bars = new ArrayList<>();
        for (String line : source.split("\n")) {
            String stripped = line.strip();
            if (stripped.endsWith("|") && !stripped.startsWith("\\")) {
                bars.add(stripped);
            }
        }
        return bars;
    }

    /** A {@code chordmode} token with its duration removed: what the page names. */
    private static String chordOf(String token) {
        Matcher matcher = CHORD_MODE.matcher(token);
        assertThat(matcher.matches()).as("chordmode token %s", token).isTrue();
        return matcher.group(1) + matcher.group(2);
    }

    /** A root or rest, a duration, then the quality and any bass. */
    private static final Pattern CHORD_MODE =
            Pattern.compile("^(r|[a-g](?:is|es)*)\\d+\\.?(?:\\*\\d+(?:/\\d+)?)?(.*)$");
}
