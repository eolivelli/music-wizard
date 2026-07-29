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
            for (long from = 0; from < bar; from++) {
                for (long to = from + 1; to <= bar; to++) {
                    offBeatSymbols += checkSpan(meter, from, to);
                }
            }
        }
        // Counted after the on-beat skip, so it measures the symbols the
        // assertions actually saw rather than the ones they skipped.
        assertThat(offBeatSymbols)
                .as("the sweep never reached an off-beat symbol, so it asserted nothing")
                .isGreaterThan(100_000);
    }

    @Test
    @DisplayName("a bar too long to sweep exhaustively is split correctly too")
    void longBarsAreSplitCorrectlyToo() {
        // The other 258 meters: everything past the bound the exhaustive sweep
        // stops at, up to the 256-quarter bar of 64/1. Sampled rather than swept,
        // because sweeping the longest at a 64th is tens of millions of spans of
        // a meter nobody writes and "mvn verify must stay fast" is a rule of this
        // project rather than a preference.
        //
        // The positions are chosen rather than stepped. A step is what round 6
        // found wrong here: scaled to the bar, it came out a whole multiple of
        // the beat unit in the three longest meters, so every span began on a
        // counted beat -- and an off-beat start is the one position class that
        // produced every defect this file has found.
        long offBeatSymbols = 0;
        int meters = 0;
        for (TimeSignature meter : longBarMeters()) {
            meters++;
            long bar = units(meter.quarterBeatsPerBar());
            long beat = units(meter.beatUnitQuarters());
            for (long from : samplePositions(bar, beat)) {
                for (long span : samplePositions(bar, beat)) {
                    long to = from + span;
                    if (span > 0 && to <= bar) {
                        offBeatSymbols += checkSpan(meter, from, to);
                    }
                }
            }
        }
        assertThat(meters).as("no long-bar meter was sampled").isGreaterThan(200);
        assertThat(offBeatSymbols)
                .as("every sampled span began on a counted beat, so the case that has produced"
                        + " every defect in this file was never reached")
                .isGreaterThan(10_000);
    }

    /**
     * Positions worth trying in a bar, in shortest-value units.
     *
     * <p>Deliberately built around the beat rather than by stepping: the bar
     * line, one unit either side of each of the first two beats, the last beat,
     * the middle of the bar and one unit either side of it, and the last unit.
     * Half of them are off the beat by construction, whatever the meter, which is
     * what a scaled step cannot promise — round 6 found a step that came out a
     * whole multiple of the beat unit in the three longest meters, so those
     * sampled nothing but downbeats.
     */
    private static java.util.SortedSet<Long> samplePositions(long bar, long beat) {
        java.util.SortedSet<Long> positions = new java.util.TreeSet<>(List.of(
                0L, 1L, beat - 1, beat, beat + 1, 2 * beat - 1, 2 * beat, 2 * beat + 1,
                bar / 2 - 1, bar / 2, bar / 2 + 1, bar - beat, bar - 1, bar));
        positions.removeIf(position -> position < 0 || position > bar);
        return positions;
    }

    /**
     * Splits one span and checks everything that must be true of the result,
     * returning how many of its symbols began off a counted beat.
     *
     * <p>Shared by the exhaustive sweep and the sampled one so that the two
     * cannot drift into checking different things, which is how the sum property
     * and the beat property came to cover different meters in the first place.
     */
    private static long checkSpan(TimeSignature meter, long from, long to) {
        long beat = units(meter.beatUnitQuarters());
        List<String> values = MetricSplitter.split(meter, from * GRID, to * GRID);

        // A bar that does not fill its meter is the failure this whole class
        // exists to prevent, and LilyPond engraves one without complaint.
        long total = 0;
        for (String value : values) {
            total += units(LilyPondNotes.quartersOf(value));
        }
        assertThat(total)
                .as("%s span %s..%s split as %s does not add back up",
                        meter, from * GRID, to * GRID, values)
                .isEqualTo(to - from);

        long offBeatSymbols = 0;
        long position = from;
        for (String value : values) {
            long length = units(LilyPondNotes.quartersOf(value));
            if (position % beat != 0) {
                offBeatSymbols++;
                // A counted beat that both starts and ends inside the symbol has
                // neither an onset nor an ending anywhere on the page, and the
                // reader has nothing to count against.
                long firstBeatAfter = (position / beat + 1) * beat;
                assertThat(firstBeatAfter + beat < position + length)
                        .as("%s span %s..%s split as %s: %s at beat %s buries the beat at %s",
                                meter, from * GRID, to * GRID, values, value,
                                position * GRID, firstBeatAfter * GRID)
                        .isFalse();
                if (meter.isCompound()) {
                    assertThat(length)
                            .as("%s span %s..%s split as %s: %s at beat %s is a whole compound"
                                            + " beat lying across the join",
                                    meter, from * GRID, to * GRID, values, value, position * GRID)
                            .isLessThan(beat);
                }
            }
            position += length;
        }
        return offBeatSymbols;
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

    /** {@link TimeSignature}'s own caps, asserted by {@link #theSweepsCoverTheWholeModel}. */
    private static final int MAX_NUMERATOR = 64;
    private static final int MAX_DENOMINATOR = 64;

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
     * <p>Longer bars are covered by {@link #longBarsAreSplitCorrectlyToo}, which
     * derives its set the same way and samples rather than sweeps. Between them
     * they name every meter the model admits, and
     * {@link #theSweepsCoverTheWholeModel} checks that they still do.
     */
    private static List<TimeSignature> meters() {
        return admissibleMeters(true);
    }

    /** Every meter the model admits whose bar is longer than that, which is the rest. */
    private static List<TimeSignature> longBarMeters() {
        return admissibleMeters(false);
    }

    private static List<TimeSignature> admissibleMeters(boolean shortBars) {
        List<TimeSignature> all = new ArrayList<>();
        for (int denominator = 1; denominator <= MAX_DENOMINATOR; denominator *= 2) {
            for (int numerator = 1; numerator <= MAX_NUMERATOR; numerator++) {
                TimeSignature meter = new TimeSignature(numerator, denominator);
                if (meter.quarterBeatsPerBar() <= LONGEST_SWEPT_BAR == shortBars) {
                    all.add(meter);
                }
            }
        }
        return all;
    }

    @Test
    @DisplayName("the meter sweeps still cover everything the model admits")
    void theSweepsCoverTheWholeModel() {
        // The bounds above are copied from TimeSignature, which exposes no
        // accessor for them. Copying is fine; copying silently is not -- round 5
        // found a typed meter list that had gone stale against limits that never
        // moved, and a list that goes stale against limits that DO move is the
        // same failure with a slower fuse. So the copy is asserted: widen the
        // model and this fails rather than the sweep quietly shrinking.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimeSignature(1, MAX_DENOMINATOR * 2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimeSignature(MAX_NUMERATOR + 1, 4));
        assertThat(meters().size() + longBarMeters().size())
                .isEqualTo(MAX_NUMERATOR * (Integer.numberOfTrailingZeros(MAX_DENOMINATOR) + 1));
    }

}
