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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * every run of steps inside a tuplet bracket, and every pickup fraction. The
 * constant was once defended by a tolerance justified with a
 * <em>false</em> claim about that arithmetic — {@code (1.0 / 6) * 768} is 128.0
 * exactly — and the tolerance was consequently unreachable and untestable. It is
 * gone; this is what replaced it.
 */
class ExportGridTest {

    /** Denominators {@link TimeSignature} accepts. */
    private static final int[] DENOMINATORS = {1, 2, 4, 8, 16, 32, 64};

    /** Largest numerator {@link TimeSignature} accepts. */
    private static final int MAX_NUMERATOR = 64;

    /**
     * Spans the sweep visits, pinned exactly.
     *
     * <p>An exact figure rather than a floor, because a sweep silently cut to a
     * hundredth of itself passes {@code isGreaterThan}. Two assertions here
     * once read {@code isGreaterThan(1_000)} against real
     * counts of 95,802 and 94,078.
     */
    private static final long EXPECTED_SPANS = 130_393;

    /** Tuplet steps the sweep visits, pinned exactly. */
    private static final long EXPECTED_TUPLET_STEPS = 95_802;

    /** Bar fractions the sweep visits, pinned exactly. */
    private static final long EXPECTED_BAR_FRACTIONS = 94_078;

    private static int stepsPerBar(TimeSignature meter) {
        return (int) Math.rint(meter.quarterBeatsPerBar() / LilyPondDuration.SHORTEST_QUARTERS);
    }

    /**
     * Whether a span is one the sweep visits.
     *
     * <p>Every span of every bar up to 64 sixty-fourths, which is every meter
     * of three quarter beats or fewer plus a good many longer ones. Above that
     * the count is quadratic in a bar that reaches 4096 steps, so the sweep
     * keeps the spans that touch a boundary — the bar line, the middle, the
     * step after each — and drops the interior ones, which differ from their
     * neighbours in nothing the splitter reads.
     */
    private static boolean worthChecking(int steps, int from, int to) {
        if (steps <= 64) {
            return true;
        }
        return isBoundary(steps, from) && isBoundary(steps, to);
    }

    private static boolean isBoundary(int steps, int step) {
        return step <= 2 || step >= steps - 2
                || Math.abs(step - steps / 2) <= 1
                || Math.abs(step - steps / 4) <= 1
                || Math.abs(step - 3 * steps / 4) <= 1;
    }

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
    @DisplayName("the splitter never writes a value outside the fourteen, in any meter")
    void theSplitterProducesNothingElse() {
        // What the first version of this test
        // really proved: MetricSplitter returns NoteValue, whose constructor
        // closes the domain to seven heads and a dot, so re-checking each
        // returned value against unitsOf re-checks the fourteen above. The
        // claim worth making is the other one -- that the splitter's *output*
        // domain is those fourteen, so covering them covers it -- and that it
        // never throws or returns an empty split for a span inside a bar.
        Set<NoteValue> produced = new HashSet<>();
        long spans = 0;
        for (TimeSignature meter : everyMeter()) {
            int steps = stepsPerBar(meter);
            for (int from = 0; from < steps; from++) {
                for (int to = from + 1; to <= steps; to++) {
                    if (!worthChecking(steps, from, to)) {
                        continue;
                    }
                    spans++;
                    List<NoteValue> values = MetricSplitter.split(meter,
                            from * LilyPondDuration.SHORTEST_QUARTERS,
                            to * LilyPondDuration.SHORTEST_QUARTERS);
                    assertThat(values).as("%s span %d..%d", meter, from, to).isNotEmpty();
                    produced.addAll(values);
                }
            }
        }
        // Every value it can write, in exactly the fourteen everyNoteValueIsExact
        // covers. Thirteen rather than fourteen because a dotted whole note is
        // six quarter beats, which only a 12/8 or larger bar holds -- and it is
        // in the set, so the sweep did reach one.
        Set<NoteValue> named = new HashSet<>();
        for (int denominator = 1; denominator <= LilyPondDuration.SHORTEST_DENOMINATOR;
                denominator *= 2) {
            named.add(new NoteValue(denominator, false));
            named.add(new NoteValue(denominator, true));
        }
        assertThat(produced).isSubsetOf(named);
        assertThat(produced).contains(new NoteValue(1, true), new NoteValue(64, false));
        assertThat(spans).isEqualTo(EXPECTED_SPANS);
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
        assertThat(checked).isEqualTo(EXPECTED_TUPLET_STEPS);
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
        assertThat(checked).isEqualTo(EXPECTED_BAR_FRACTIONS);
    }

    @Test
    @DisplayName("a length between two divisions is refused rather than rounded")
    void aLengthOffTheGridIsRefused() {
        // Rounding it would put the measure out by exactly as much as the
        // length was wrong, and a MusicXML measure that does not fill its meter
        // is imported by every scorewriter without a word.
        assertThatThrownBy(() -> ExportGrid.unitsOf(1.0 / 1000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a positive whole number");
        assertThatThrownBy(() -> ExportGrid.unitsOf(Double.NaN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExportGrid.unitsOf(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalStateException.class);
        // Zero and below are refused too, which LilyPondDuration.wholeNoteFraction
        // has always done: a length of nothing is not a length, and the one class
        // that exists so the two exports cannot disagree is the last place they
        // should disagree about what is legal.
        assertThatThrownBy(() -> ExportGrid.unitsOf(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> ExportGrid.unitsOf(-1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LilyPondDuration.wholeNoteFraction(0))
                .isInstanceOf(IllegalArgumentException.class);
        // And a length too long to count says so rather than throwing a bare
        // arithmetic error from the cast.
        assertThatThrownBy(() -> ExportGrid.unitsOf(1e12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive whole number");
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
