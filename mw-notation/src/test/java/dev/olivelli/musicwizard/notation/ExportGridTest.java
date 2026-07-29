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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweep that lets {@link ExportGrid#unitsOf} compare exactly.
 *
 * <p>{@value ExportGrid#PER_QUARTER} divisions of a quarter note is an argument
 * rather than a measurement — that 24 and 32 are the tight constraints and 96
 * therefore divides everything — and an argument about floating point is not the
 * same as an argument about arithmetic. A third of a beat is not a representable
 * double, so "768 divides a triplet eighth" being true of the rationals says
 * nothing about whether {@code (1.0 / 6) * 768} is 128.0.
 *
 * <p>So this enumerates the space instead: every meter the model accepts, every
 * grid the quantizer can choose, every note value the splitter can produce,
 * every run of steps inside a tuplet bracket, and every pickup fraction. Round 1
 * of review found the constant defended by a tolerance justified with a
 * <em>false</em> claim about that arithmetic — {@code (1.0 / 6) * 768} is 128.0
 * exactly — and the tolerance was consequently unreachable and untestable. It is
 * gone; this is what replaced it.
 */
class ExportGridTest {

    /** Denominators {@link TimeSignature} accepts. */
    private static final int[] DENOMINATORS = {1, 2, 4, 8, 16, 32, 64};

    /** Largest numerator {@link TimeSignature} accepts. */
    private static final int MAX_NUMERATOR = 64;

    private static List<TimeSignature> everyMeter() {
        List<TimeSignature> meters = new ArrayList<>();
        for (int denominator : DENOMINATORS) {
            for (int numerator = 1; numerator <= MAX_NUMERATOR; numerator++) {
                meters.add(new TimeSignature(numerator, denominator));
            }
        }
        return meters;
    }

    @Test
    @DisplayName("every note value the splitter can write is a whole number of divisions")
    void everyNoteValueIsExact() {
        int checked = 0;
        for (int denominator = 1; denominator <= LilyPondDuration.SHORTEST_DENOMINATOR;
                denominator *= 2) {
            for (boolean dotted : new boolean[] {false, true}) {
                NoteValue value = new NoteValue(denominator, dotted);
                assertThat(ExportGrid.unitsOf(value.quarters()))
                        .as("%s", value.lilyPondToken())
                        .isEqualTo((int) Math.rint(value.quarters() * ExportGrid.PER_QUARTER));
                checked++;
            }
        }
        // Seven note heads, dotted and plain. A count rather than a comment, so
        // that a sweep silently reduced to nothing fails here.
        assertThat(checked).isEqualTo(14);
    }

    @Test
    @DisplayName("every span the splitter can cut, in every meter, is a whole number of divisions")
    void everySplitSpanIsExact() {
        long checked = 0;
        for (TimeSignature meter : everyMeter()) {
            double bar = meter.quarterBeatsPerBar();
            // Whole 64ths only: that is the grid the splitter refuses to take a
            // span off, so it is the whole of its input domain.
            int steps = (int) Math.rint(bar / LilyPondDuration.SHORTEST_QUARTERS);
            if (steps > 64) {
                // A 64/1 bar is 256 quarter beats and 4096 sixty-fourths, and
                // the span count is quadratic in that. Sampled at the ends and
                // the middle instead, which is where a span behaves differently;
                // the small meters below are swept whole.
                for (int from : new int[] {0, 1, steps / 2 - 1, steps / 2, steps - 1}) {
                    for (int to : new int[] {from + 1, from + 2, steps / 2, steps}) {
                        if (to > from && to <= steps) {
                            checked += check(meter, from, to);
                        }
                    }
                }
                continue;
            }
            for (int from = 0; from < steps; from++) {
                for (int to = from + 1; to <= steps; to++) {
                    checked += check(meter, from, to);
                }
            }
        }
        assertThat(checked).isGreaterThan(100_000);
    }

    private static long check(TimeSignature meter, int fromStep, int toStep) {
        double unit = LilyPondDuration.SHORTEST_QUARTERS;
        long checked = 0;
        for (NoteValue value : MetricSplitter.split(meter, fromStep * unit, toStep * unit)) {
            // The throw is the assertion: unitsOf refuses anything that is not
            // a whole number of divisions, exactly.
            ExportGrid.unitsOf(value.quarters());
            checked++;
        }
        return checked;
    }

    @Test
    @DisplayName("every tuplet step, in every meter and grid, is a whole number of divisions")
    void everyTupletStepIsExact() {
        long checked = 0;
        for (TupletBar bar : everyTupletBar()) {
            for (int steps = 1; steps <= bar.stepsPerBracket(); steps++) {
                // The sounding length, which is what a MusicXML duration holds
                // and what a MIDI tick counts -- not the written one, which is
                // longer for a triplet and shorter for a duplet.
                ExportGrid.unitsOf(steps * bar.stepQuarters());
                checked++;
            }
            // And the whole bar's worth, step by step, since a note may run
            // across brackets before the fragmenting cuts it.
            for (int step = 1; step <= bar.divisions(); step++) {
                ExportGrid.unitsOf(bar.beatOfStep(step) - bar.startBeat());
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(1_000);
    }

    @Test
    @DisplayName("every pickup and whole-bar length is a whole number of divisions")
    void everyBarFractionIsExact() {
        long checked = 0;
        for (TimeSignature meter : everyMeter()) {
            // The whole-bar rest, which is the bar's own length as a fraction.
            long[] whole = LilyPondDuration.wholeNoteFraction(meter.quarterBeatsPerBar());
            ExportGrid.unitsOf(4.0 * whole[0] / whole[1]);
            checked++;
        }
        for (TupletBar bar : everyTupletBar()) {
            // The pickup length inside a tuplet bar, which is the one length
            // that is not a whole number of 64ths and so cannot be a double
            // until it is divided here.
            for (int step = 0; step < bar.divisions(); step++) {
                long[] fraction = bar.lengthToBarLine(step);
                ExportGrid.unitsOf(4.0 * fraction[0] / fraction[1]);
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(1_000);
    }

    @Test
    @DisplayName("a length between two divisions is refused rather than rounded")
    void aLengthOffTheGridIsRefused() {
        // Rounding it would put the measure out by exactly as much as the
        // length was wrong, and a MusicXML measure that does not fill its meter
        // is imported by every scorewriter without a word.
        assertThatThrownBy(() -> ExportGrid.unitsOf(1.0 / 1000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a whole number");
        assertThatThrownBy(() -> ExportGrid.unitsOf(Double.NaN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExportGrid.unitsOf(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the shortest lengths the two constraints name come out as the two constraints say")
    void theConstraintsAreTheOnesClaimed() {
        // A 64th note: a sixteenth of a quarter.
        assertThat(ExportGrid.unitsOf(1.0 / 16)).isEqualTo(48);
        // A triplet 64th sounds for two thirds of one, which is the tight
        // constraint and needs 24 divisions to a quarter.
        assertThat(ExportGrid.unitsOf(1.0 / 24)).isEqualTo(32);
        // A duplet 64th in compound time sounds for half again, which needs 32.
        assertThat(ExportGrid.unitsOf(3.0 / 32)).isEqualTo(72);
        assertThat(ExportGrid.PER_QUARTER % 24).isZero();
        assertThat(ExportGrid.PER_QUARTER % 32).isZero();
        // A MIDI file header holds the resolution in fifteen bits.
        assertThat(ExportGrid.PER_QUARTER).isLessThanOrEqualTo(0x7FFF);
    }

    /** Every tuplet bar {@link TupletBar#of} will build, over every meter and grid. */
    private static List<TupletBar> everyTupletBar() {
        List<TupletBar> bars = new ArrayList<>();
        for (TimeSignature meter : everyMeter()) {
            for (GridResolution resolution : GridResolution.values()) {
                Optional<TupletBar> bar =
                        TupletBar.of(new BarGrid(0, 0, resolution, meter));
                bar.ifPresent(bars::add);
            }
        }
        assertThat(bars).as("no tuplet bar was built at all").isNotEmpty();
        return bars;
    }
}
