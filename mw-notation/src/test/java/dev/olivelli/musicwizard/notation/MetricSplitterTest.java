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
import java.util.ArrayList;
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Round 1 of review found all of these written as one symbol lying
            // across a beat that then vanished from the page. Length alone said
            // yes -- a dotted quarter IS one 6/8 beat -- and nothing asked where
            // the symbol started.
            "6/8  | 0.5  | 2    | 4,8",
            "6/8  | 1    | 2.5  | 8,4",
            "9/8  | 0.5  | 2    | 4,8",
            "12/8 | 0.5  | 3.5  | 4,4.,8",
            // The same defect above the beat in simple time: a half note starting
            // a sixteenth after beat one swallowed the middle of the bar.
            "4/4  | 0.25 | 2.25 | 8.,4,16",
            "4/4  | 1.5  | 3.5  | 8,4.",
            // Round 2 found the first fix testing the *unit's* size rather than
            // the *symbol's*, so it never fired in a meter whose beats do not
            // come in a power-of-two count: there the bar divides straight into
            // beats, no unit is ever longer than one, and a whole note could
            // still swallow four beats of a 5/4 bar from a sixteenth offset.
            "3/4  | 0.25 | 2.25 | 8.,4,16",
            "5/4  | 0.5  | 3.5  | 8,2,8",
            "6/4  | 0.5  | 4.5  | 8,2.,8",
            "7/8  | 0.25 | 1.75 | 16,4,16",
            // Round 3 found the second fix exempting a symbol of *exactly* a
            // dotted beat. Where the bar divides straight into beats, such a
            // symbol covers a complete one: 3/4 wrote this as a single 4. while
            // 4/4 tied the identical span.
            "3/4  | 0.75 | 2.25 | 16,4,16",
            "5/4  | 0.75 | 2.25 | 16,4,16",
            "7/8  | 0.375| 1.125| 32,8,32",
            // The same span in 4/4, which never had the defect, to pin that
            // triple time now reads identically rather than more loosely.
            "4/4  | 0.75 | 2.25 | 16,4,16",
    })
    @DisplayName("a symbol may not begin off the beat and then lie across one")
    void aSymbolMayNotHideABeatItStartsInsideOf(String meter, double from, double to,
                                                String expected) {
        String[] parts = meter.split("/");
        TimeSignature signature =
                new TimeSignature(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        assertThat(MetricSplitter.split(signature, from, to))
                .containsExactly(expected.split(","));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Beginning on a counted beat, a symbol may cross the next one: this
            // is a half note on beat two and a dotted half on beat one.
            "4/4  | 1    | 3    | 2",
            "4/4  | 0    | 3    | 2.",
            "12/8 | 1.5  | 4.5  | 2.",
            // Below the beat, subdivisions group freely -- an eighth note is an
            // eighth note on a sixteenth offset.
            "4/4  | 0.25 | 0.75 | 8",
            "6/8  | 0.5  | 1.5  | 4",
            // And across a *simple* beat, syncopation stays syncopation rather
            // than turning into ties nobody writes.
            "4/4  | 0.5  | 1.5  | 4",
            "4/4  | 0.5  | 2    | 4.",
            "2/2  | 0.5  | 3.5  | 2.",
            // The same in the meters round 2 found unguarded: beginning on a
            // beat is still enough, and a symbol shorter than a dotted beat
            // still floats.
            "3/4  | 1    | 3    | 2",
            "5/4  | 1    | 4    | 2.",
            "7/8  | 0.5  | 1.5  | 4",
    })
    @DisplayName("the beat rule does not turn ordinary syncopation into ties")
    void syncopationSurvivesTheBeatRule(String meter, double from, double to, String expected) {
        String[] parts = meter.split("/");
        TimeSignature signature =
                new TimeSignature(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        assertThat(MetricSplitter.split(signature, from, to))
                .containsExactly(expected.split(","));
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
    @DisplayName("no symbol ever swallows a counted beat it did not start on")
    void noSymbolSwallowsACountedBeat() {
        // Stated over the symbols that come out, in the terms a reader would use,
        // rather than by re-asking the predicate that produced them. That
        // distinction is not pedantry: three rounds of review found this rule
        // wrong, and the first two sweeps that were supposed to catch it were
        // written as the implementation's own length threshold negated, so they
        // agreed with the code about which spans were even worth looking at.
        //
        // The arithmetic here is exact integers counted in the shortest value the
        // emitter can write, so it shares no expression with the implementation
        // either -- round 4 pointed out that copying its floating-point kernel
        // leaves a change to that kernel marked only by its own duplicate. And it
        // sweeps at that same resolution, because sweeping a sixteenth grid while
        // MetricSplitter accepts a 64th one leaves three quarters of the legal
        // starting positions untested: a rule relaxed to a tolerance of a tenth
        // of a beat passed the whole suite at a sixteenth-note step.
        long offBeatSymbols = 0;
        for (TimeSignature meter : meters()) {
            long bar = units(meter.quarterBeatsPerBar());
            long beat = units(meter.beatUnitQuarters());
            for (long from = 0; from < bar; from++) {
                for (long to = from + 1; to <= bar; to++) {
                    long position = from;
                    List<String> values = MetricSplitter.split(
                            meter, from * GRID, to * GRID);
                    // Checked here as well as in everySplitSumsToTheSpan, because
                    // that one samples a sixteenth grid and this is the only
                    // sweep that reaches a 64th-note offset. A bar that does not
                    // fill its meter is the failure this whole class exists to
                    // prevent, and LilyPond engraves one without complaint.
                    long total = 0;
                    for (String value : values) {
                        total += units(LilyPondNotes.quartersOf(value));
                    }
                    assertThat(total)
                            .as("%s span %s..%s split as %s does not add back up",
                                    meter, from * GRID, to * GRID, values)
                            .isEqualTo(to - from);
                    for (String value : values) {
                        long length = units(LilyPondNotes.quartersOf(value));
                        if (position % beat == 0) {
                            position += length;
                            continue;
                        }
                        offBeatSymbols++;
                        // A counted beat that both starts and ends inside the
                        // symbol has neither an onset nor an ending anywhere on
                        // the page, and the reader has nothing to count against.
                        long firstBeatAfter = (position / beat + 1) * beat;
                        assertThat(firstBeatAfter + beat < position + length)
                                .as("%s span %s..%s split as %s: %s at beat %s buries the beat"
                                                + " at %s",
                                        meter, from * GRID, to * GRID, values, value,
                                        position * GRID, firstBeatAfter * GRID)
                                .isFalse();
                        if (meter.isCompound()) {
                            assertThat(length)
                                    .as("%s span %s..%s split as %s: %s at beat %s is a whole"
                                                    + " compound beat lying across the join",
                                            meter, from * GRID, to * GRID, values, value,
                                            position * GRID)
                                    .isLessThan(beat);
                        }
                        position += length;
                    }
                }
            }
        }
        // Counted after the on-beat continue, so it measures the symbols the
        // assertions actually saw rather than the ones they skipped.
        assertThat(offBeatSymbols)
                .as("the sweep never reached an off-beat symbol, so it asserted nothing")
                .isGreaterThan(100_000);
    }

    /** The shortest value the emitter can write, which is the grid everything lands on. */
    private static final double GRID = LilyPondDuration.SHORTEST_QUARTERS;

    /**
     * A length in quarter beats counted in shortest values.
     *
     * <p>Exact for every length either the model or the emitter produces: a beat
     * unit is {@code (1 or 3) * 4 / denominator} with the denominator a power of
     * two no greater than 64, and every note value is a whole number of 64ths.
     */
    private static long units(double quarters) {
        double exact = quarters / GRID;
        long rounded = Math.round(exact);
        assertThat((double) rounded).as("%s is not a whole number of 1/64 notes", quarters)
                .isEqualTo(exact);
        return rounded;
    }

    /** The longest bar the exhaustive sweep covers, in quarter beats. */
    private static final double LONGEST_SWEPT_BAR = 8;

    /**
     * Every meter the model admits whose bar is short enough to sweep
     * exhaustively at the emitter's own resolution — 190 of the 448.
     *
     * <p>Built from {@link TimeSignature}'s own limits rather than from a list
     * somebody typed. Round 5 of review found the list version claiming to be
     * this while covering 44: it stopped at denominator 16 and numerator 16, so
     * every meter in 32nds and 64ths, and both meters in whole notes, went
     * unswept while the javadoc said otherwise. On a rule four rounds found
     * wrong, the stated coverage is most of the test's value, and the excluded
     * meters turned out to cost about a second.
     *
     * <p>Longer bars are covered by {@link #everySplitSumsToTheSpan} at a coarser
     * step, which is the trade: this sweep is exhaustive and bounded, that one is
     * sampled and unbounded.
     */
    private static List<TimeSignature> meters() {
        List<TimeSignature> all = new ArrayList<>();
        for (int denominator = 1; denominator <= 64; denominator *= 2) {
            for (int numerator = 1; numerator <= 64; numerator++) {
                TimeSignature meter = new TimeSignature(numerator, denominator);
                if (meter.quarterBeatsPerBar() <= LONGEST_SWEPT_BAR) {
                    all.add(meter);
                }
            }
        }
        return all;
    }

    @Test
    @DisplayName("a bar too long to sweep exhaustively still adds back up")
    void everySplitSumsToTheSpan() {
        // The complement of noSymbolSwallowsACountedBeat, which is exhaustive but
        // stops at an eight-quarter bar. These are the meters past that bound,
        // sampled rather than swept, up to the longest bar the model admits.
        List<TimeSignature> meters = List.of(
                new TimeSignature(12, 4), new TimeSignature(16, 4), new TimeSignature(9, 2),
                new TimeSignature(4, 1), new TimeSignature(64, 8), new TimeSignature(24, 8),
                new TimeSignature(64, 4), new TimeSignature(17, 4), new TimeSignature(64, 1));
        for (TimeSignature meter : meters) {
            double bar = meter.quarterBeatsPerBar();
            // Sampled at a step that keeps every bar to about the same number of
            // positions, whether it holds twelve quarter beats or two hundred and
            // fifty-six. Sweeping the longest at a sixteenth would be a quarter
            // of a million spans of a bar nobody writes, and "mvn verify must
            // stay fast" is a rule of this project rather than a preference.
            double step = Math.max(0.25, Math.pow(2, Math.ceil(
                    Math.log(bar / 48) / Math.log(2))));
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
