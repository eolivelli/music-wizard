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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The expected splits here are what a musician would write, reasoned about
 * meter by meter rather than recorded from the implementation. The property test
 * at the end is the safety net: whatever the split, the pieces must add back up
 * to the span, or the bar will not fill and LilyPond will engrave it wrong
 * without complaining.
 */
class MetricSplitterTest {

    private static final TimeSignature FIVE_FOUR = new TimeSignature(5, 4);
    private static final TimeSignature SEVEN_EIGHT = new TimeSignature(7, 8);
    private static final TimeSignature TWELVE_EIGHT = new TimeSignature(12, 8);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // A value that starts on the downbeat is just that value.
            "0   | 4   | 1",
            "0   | 2   | 2",
            "0   | 1   | 4",
            "0   | 0.5 | 8",
            // Dots. Three of the half-bar's two beats, and three of the beat's
            // two halves: the classic dotted quarter and dotted eighth.
            "0   | 3   | 2.",
            "0   | 1.5 | 4.",
            "0   | 0.75| 8.",
            // A half note from beat two spans the middle of the bar, which is a
            // boundary a half note is allowed to span.
            "1   | 3   | 2",
            "2   | 4   | 2",
            // Syncopation: an eighth in, a dotted quarter reaching beat three.
            "0.5 | 2   | 4.",
            // An eighth note is an eighth note wherever it starts.
            "0.25| 0.75| 8",
            // Five eighths from the downbeat is no single value: it becomes a
            // half note tied to an eighth, which shows beat three.
            "0   | 2.5 | 2,8",
            // Seven eighths likewise, and the cut falls on beat three because
            // that is the strongest beat inside the bar: a half note then a
            // dotted quarter, not a dotted half then an eighth.
            "0   | 3.5 | 2,4.",
            // Crossing beat two off the grid of beats: the piece before the beat
            // is written out, the rest is one value.
            "0.5 | 3   | 4.,4",
    })
    @DisplayName("common time splits the way a reader expects")
    void commonTime(double from, double to, String expected) {
        assertThat(MetricSplitter.split(TimeSignature.FOUR_FOUR, from, to))
                .containsExactly(expected.split(","));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // The whole bar, and one of its two dotted-quarter beats.
            "0   | 3   | 2.",
            "0   | 1.5 | 4.",
            "1.5 | 3   | 4.",
            // Two of a beat's three eighths are a quarter, wherever in the beat
            // they start.
            "0   | 1   | 4",
            "0.5 | 1.5 | 4",
            "1.5 | 2.5 | 4",
            // Four eighths from the downbeat are NOT a half note: a half note
            // there hides the second beat. This is the whole difference between
            // 6/8 and 3/4.
            "0   | 2   | 4.,8",
            // And spanning the beat from inside the first one.
            "0.5 | 2.5 | 4,4",
    })
    @DisplayName("compound time keeps its two groups of three visible")
    void sixEight(double from, double to, String expected) {
        assertThat(MetricSplitter.split(TimeSignature.SIX_EIGHT, from, to))
                .containsExactly(expected.split(","));
    }

    @Test
    @DisplayName("the same four eighths are a half note in 3/4 and are not in 6/8")
    void threeFourAndSixEightDisagreeOnTheSameLength() {
        // Both bars hold three quarter beats. Only the grouping differs, and it
        // is the reason both meters exist.
        assertThat(MetricSplitter.split(TimeSignature.THREE_FOUR, 0, 2)).containsExactly("2");
        assertThat(MetricSplitter.split(TimeSignature.SIX_EIGHT, 0, 2)).containsExactly("4.", "8");
    }

    @Test
    @DisplayName("a bar longer than a whole note ties rather than reaching for a breve")
    void irregularBars() {
        // Five quarters: a whole note and a quarter, not five tied quarters.
        assertThat(MetricSplitter.split(FIVE_FOUR, 0, 5)).containsExactly("1", "4");
        // Seven eighths: a dotted half and an eighth, not seven tied eighths.
        assertThat(MetricSplitter.split(SEVEN_EIGHT, 0, 3.5)).containsExactly("2.", "8");
        // Twelve eighths are six quarters, which is a dotted whole note -- the
        // one bar length longer than a whole note that a single value reaches.
        assertThat(MetricSplitter.split(TWELVE_EIGHT, 0, 6)).containsExactly("1.");
        // One beat short of it is not, and cuts at the middle of the bar.
        assertThat(MetricSplitter.split(TWELVE_EIGHT, 0, 4.5)).containsExactly("2.", "4.");
    }

    @Test
    @DisplayName("a span that leaves the bar is a bug in the caller, not a long note")
    void rejectsSpansOutsideTheBar() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MetricSplitter.split(TimeSignature.FOUR_FOUR, 0, 5))
                .withMessageContaining("leaves a 4/4 bar");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MetricSplitter.split(TimeSignature.FOUR_FOUR, -1, 2));
    }

    @Test
    @DisplayName("a span off the grid is refused rather than rounded into the next note")
    void rejectsSpansOffTheGrid() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MetricSplitter.split(TimeSignature.FOUR_FOUR, 0, 1.0 / 3))
                .withMessageContaining("whole number of 1/64");
    }

    @Test
    @DisplayName("every span of every bar adds back up to itself")
    void everySplitSumsToTheSpan() {
        List<TimeSignature> meters = List.of(
                TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT,
                FIVE_FOUR, SEVEN_EIGHT, TWELVE_EIGHT,
                new TimeSignature(2, 2), new TimeSignature(9, 8), new TimeSignature(2, 4));
        // A sixteenth grid rather than the full 64th one: the same arithmetic,
        // in a test that still finishes in well under a second.
        double step = 0.25;
        for (TimeSignature meter : meters) {
            double bar = meter.quarterBeatsPerBar();
            for (double from = 0; from < bar; from += step) {
                for (double to = from + step; to <= bar; to += step) {
                    List<String> values = MetricSplitter.split(meter, from, to);
                    double sum = 0;
                    for (String value : values) {
                        sum += LilyPondNotes.quartersOf(value);
                    }
                    assertThat(sum)
                            .as("%s span %s..%s split as %s", meter, from, to, values)
                            .isEqualTo(to - from);
                }
            }
        }
    }
}
