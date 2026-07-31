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
import java.util.List;
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
        // #163. Asserted over the awkward divisions rather than one of them:
        // a 4/4 bar written in halves, a 3/4 bar written in thirds, and a 5/4
        // bar, whose only divisions are one slot and five.
        List<Score> scores = List.of(
                bar(TimeSignature.FOUR_FOUR, C_G, 2, 2),
                bar(TimeSignature.FOUR_FOUR, C_G_A, 1, 2, 1),
                bar(TimeSignature.THREE_FOUR, C_G, 2, 1),
                bar(TimeSignature.SIX_EIGHT, C_G, 1.5, 1.5),
                bar(new TimeSignature(5, 4), C_G, 3, 2),
                bar(new TimeSignature(7, 8), C_G, 2, 1.5));
        for (Score score : scores) {
            double meter = score.tempoMap().initialTimeSignature().quarterBeatsPerBar();
            for (ChartLayout.Bar written : ChartLayout.of(score)) {
                double sum = written.cells().stream()
                        .mapToDouble(ChartLayout.Cell::lengthQuarters)
                        .sum();
                assertThat(sum).as("%s in %s", written.cells(), meter).isEqualTo(meter);
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
}
