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

package dev.olivelli.musicwizard.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What {@link Score#estimatedTempo()} takes from a beat grid, and why it is
 * neither of the two obvious answers.
 *
 * <p>The readers of that accessor all place something at
 * {@code first + k * period} for a whole number {@code k}, so they need a rate
 * per pulse index. The median interval is not one and is quantised besides; the
 * mean interval is one and folds in every gap where the tracker missed a beat.
 * {@link BeatGrid#steadyPulseRate()} is the mean over the intervals that are one
 * pulse long, and each test below is a grid on which at least one of the other
 * two gives a materially different answer -- otherwise it would be asserting
 * something three implementations share.
 */
class SteadyRateTest {

    private static final TimeSignature FOUR_FOUR = TimeSignature.FOUR_FOUR;
    private static final Confidence SURE = Confidence.of(0.9);

    private static BeatGrid gridOf(List<Double> times) {
        return BeatGrid.ofTimes(times, FOUR_FOUR, SURE);
    }

    /** {@code count} pulses starting at zero, one every {@code period} seconds. */
    private static List<Double> evenPulses(int count, double period) {
        List<Double> times = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            times.add(i * period);
        }
        return times;
    }

    /** The plain mean interval, which is exactly the end-to-end rate. */
    private static double meanRateOf(List<Double> times) {
        return 60.0 * (times.size() - 1) / (times.get(times.size() - 1) - times.get(0));
    }

    @Nested
    @DisplayName("on a grid the tracker got right")
    class Even {

        @Test
        @DisplayName("agrees with the median and the mean, so nothing regresses on synthetic audio")
        void allThreeAgree() {
            // Tiers 0 and 1 are exact grids, so this is the case every existing
            // fixture is in and the reason the suite did not move when #200
            // changed the fall-through. Stated as a test rather than left to be
            // inferred: it is the guarantee that this change cannot show up as a
            // regression in the synthetic gates.
            BeatGrid grid = gridOf(evenPulses(20, 0.5));

            assertThat(grid.steadyPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(grid.medianPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(meanRateOf(grid.beatTimes())).isCloseTo(120.0, within(1e-9));
        }

        @Test
        @DisplayName("counts intervals rather than pulses, so it is not off by one")
        void countsIntervalsNotPulses() {
            // Nineteen intervals over twenty pulses. Dividing by the pulse count
            // would give 114 BPM here, and it is the same off-by-one the whole
            // class of defect turns on, so it gets an assertion of its own on a
            // grid short enough for the error to be obvious.
            BeatGrid grid = gridOf(evenPulses(20, 0.5));

            assertThat(grid.steadyPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(grid.steadyPulseRate()).isNotCloseTo(60.0 * 20 / 9.5, within(1.0));
        }
    }

    @Nested
    @DisplayName("on a grid the tracker got wrong in one place")
    class Faults {

        @Test
        @DisplayName("ignores the gap left by a dropped pulse, where the mean folds it in")
        void aDroppedPulseDoesNotMove() {
            // #205's first case. The tracker misses one beat, leaving one interval
            // of a whole two pulses; every other interval still measures the music
            // exactly. The band excludes the doubled one.
            List<Double> even = evenPulses(20, 0.5);
            List<Double> dropped = new ArrayList<>(even);
            dropped.remove(10);
            BeatGrid grid = gridOf(dropped);

            assertThat(grid.steadyPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(grid.medianPulseRate()).isCloseTo(120.0, within(1e-9));
            // The figure #205 records as the cost of the plain mean, and the
            // reason reading the end-to-end rate directly was not the fix.
            assertThat(meanRateOf(dropped)).isCloseTo(113.6842, within(1e-4));
        }

        @Test
        @DisplayName("ignores the halves left by a spurious pulse, where the mean folds them in")
        void aSpuriousPulseDoesNotMove() {
            // #205's second case, and the one its own proposal conceded: counting
            // each interval as a whole number of pulses has to clamp a half-length
            // interval up to one. A band does not, because it rejects rather than
            // rounds.
            List<Double> spurious = new ArrayList<>(evenPulses(20, 0.5));
            spurious.add(5.25);
            spurious.sort(Double::compare);
            BeatGrid grid = gridOf(spurious);

            assertThat(grid.steadyPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(meanRateOf(spurious)).isCloseTo(126.3158, within(1e-4));
        }

        @Test
        @DisplayName("survives a run of dropped pulses, since it counts what is left")
        void severalDroppedPulsesDoNotMove() {
            // The real recording's version of the case above: on
            // blues-shuffle-a-106bpm.mp3 eleven of 576 intervals run longer than
            // 1.5x the median and one of them spans nearly seven pulses. So the
            // fault comes in quantity rather than singly, and a statistic that
            // survives one of them has not been shown to survive that.
            List<Double> pulses = new ArrayList<>(evenPulses(60, 0.5));
            for (int index : new int[] {50, 40, 39, 30, 20, 19, 18, 10}) {
                pulses.remove(index);
            }
            BeatGrid grid = gridOf(pulses);

            assertThat(grid.steadyPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(meanRateOf(pulses)).isLessThan(105.0);
        }
    }

    @Nested
    @DisplayName("on a grid quantised to the analysis hop")
    class Quantised {

        /**
         * A grid at {@code trueRate} whose pulses are rounded onto a frame axis,
         * which is the shape every tracked grid actually has.
         */
        private static List<Double> onFrames(int count, double truePeriod, double frame) {
            List<Double> times = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                times.add(Math.round(i * truePeriod / frame) * frame);
            }
            return times;
        }

        @Test
        @DisplayName("beats the median, because a median is one of the observed intervals")
        void theMedianInheritsTheHopAndThisDoesNot() {
            // The mechanism behind #200 on real audio, in miniature. Beat times
            // come off a frame axis, so every interval is a whole number of frames
            // and so is the median -- it is one of them. The mean is not, because
            // the rounding averages out over the intervals.
            //
            // 512 samples at 22050 Hz is the project's own hop, and at 106 BPM one
            // pulse is about 24.4 frames, so the median can only be 24 frames or
            // 25 and is out by up to 2% whichever it is. That is the error the
            // chart multiplies by a bar index three hundred times.
            double frame = 512.0 / 22050.0;
            double truePeriod = 60.0 / 106.0;
            List<Double> times = onFrames(600, truePeriod, frame);
            BeatGrid grid = gridOf(times);

            double medianError = Math.abs(grid.medianPulseRate() - 106.0);
            double steadyError = Math.abs(grid.steadyPulseRate() - 106.0);

            // The median lands on exactly 24 frames and reads 107.67, which is
            // 1.67 BPM out; the steady rate reads 105.9996. Both bounds sit far
            // from the figure they are bounding rather than beside it, so neither
            // passes by a hair: the margins are 3x and 27x.
            assertThat(medianError).isGreaterThan(0.5);
            assertThat(steadyError).isLessThan(0.01);

            // And the median really is quantised, not merely inaccurate: it is a
            // whole number of frames, so it cannot be improved by a better
            // recording.
            double medianPeriod = 60.0 / grid.medianPulseRate();
            assertThat(medianPeriod / frame)
                    .isCloseTo(Math.round(medianPeriod / frame), within(1e-9));
        }

        @Test
        @DisplayName("moves estimatedTempo itself, by more than the chart can absorb")
        void theFallThroughIsWhatMoves() {
            // The regression test for #200 proper: the same grid read through the
            // accessor the chart, the header, the staff and the MusicXML export
            // all share. Before #200 this answered 107.67 for a 106 BPM grid.
            //
            // 1.67 BPM matters at the scale a chart is read on. ChartLayout places
            // bar line k at anchor + k * 4 * 60 / estimatedTempo(), so a rate 1.6%
            // long walks a whole beat off the music inside sixteen bars and a
            // whole bar inside sixty-four -- and these benchmarks run to three
            // hundred.
            double frame = 512.0 / 22050.0;
            List<Double> times = onFrames(600, 60.0 / 106.0, frame);
            Score score = Score.empty(TempoMap.fromBeatTimes(times, FOUR_FOUR), 350.0)
                    .withBeatGrid(gridOf(times));

            assertThat(score.estimatedTempo()).isCloseTo(106.0, within(0.01));
            assertThat(score.estimatedTempo())
                    .isNotCloseTo(score.beatGrid().orElseThrow().medianTempo(FOUR_FOUR),
                            within(1.0));
        }
    }

    @Nested
    @DisplayName("the units")
    class Units {

        @Test
        @DisplayName("counts pulses, and converts to quarter notes only when asked")
        void aPulseIsAQuarterOnlyInSimpleTime() {
            // The same distinction medianPulseRate and medianTempo carry: a grid
            // holds pulses, and in 6/8 a pulse is a dotted quarter. Reporting the
            // pulse rate as a tempo is what mis-barred compound meters.
            BeatGrid grid = BeatGrid.ofTimes(evenPulses(12, 0.5), TimeSignature.SIX_EIGHT, SURE);

            assertThat(grid.steadyPulseRate()).isCloseTo(120.0, within(1e-9));
            assertThat(grid.steadyTempo(TimeSignature.SIX_EIGHT)).isCloseTo(180.0, within(1e-9));
            assertThat(grid.steadyTempo(FOUR_FOUR)).isEqualTo(grid.steadyPulseRate());
        }

        @Test
        @DisplayName("is what estimatedTempo answers with, on a grid where the three differ")
        void theFallThroughReadsThisAndNotTheOthers() {
            // The point of the whole change, on the one grid shape that tells the
            // three statistics apart: pulses every half second with two of them
            // missing, so the median is right by luck, the mean is dragged low,
            // and the steady rate is right by construction. Asserting against
            // medianTempo here would still have passed, which is why the mean is
            // asserted to be different too.
            List<Double> pulses = new ArrayList<>(evenPulses(30, 0.5));
            pulses.remove(20);
            pulses.remove(10);
            BeatGrid grid = gridOf(pulses);
            Score score = Score.empty(TempoMap.fromBeatTimes(pulses, FOUR_FOUR), 20.0)
                    .withBeatGrid(grid);

            assertThat(score.estimatedTempo()).isEqualTo(grid.steadyTempo(FOUR_FOUR));
            assertThat(score.estimatedTempo()).isCloseTo(120.0, within(1e-9));
            assertThat(meanRateOf(pulses)).isLessThan(114.0);
        }
    }

    @Nested
    @DisplayName("the degenerate grids")
    class Degenerate {

        @Test
        @DisplayName("refuses a grid with no interval in it")
        void oneBeatCarriesNoRate() {
            BeatGrid lone = gridOf(List.of(0.25));

            assertThatThrownBy(lone::steadyPulseRate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fewer than two beats");
        }

        @Test
        @DisplayName("answers with the median when the band around it is empty")
        void anEmptyBandFallsBackToTheMedian() {
            // Reachable only with an even number of intervals, where the median is
            // taken *between* the two middle ones rather than being one of them.
            // Two intervals an order of magnitude apart then leave both outside a
            // band drawn around their own average, and there is nothing to take a
            // mean of. Guarded rather than left to divide by zero, which would
            // return NaN -- and every comparison against NaN is silently false.
            BeatGrid grid = gridOf(List.of(0.0, 0.1, 10.1));

            assertThat(grid.steadyPulseRate()).isEqualTo(grid.medianPulseRate());
            assertThat(grid.steadyPulseRate()).isCloseTo(60.0 / 5.05, within(1e-9));
        }

        @Test
        @DisplayName("takes the single interval of a two-pulse grid")
        void twoPulsesAreOneInterval() {
            // An odd count of one, so the median is that interval and the band
            // holds it. Worth pinning because it is the boundary the guard above
            // sits on.
            BeatGrid grid = gridOf(List.of(1.0, 1.5));

            assertThat(grid.steadyPulseRate()).isCloseTo(120.0, within(1e-9));
        }
    }

    @Nested
    @DisplayName("the overload for a caller with times but no grid")
    class FromTimes {

        @Test
        @DisplayName("gives the same answer as the grid built from the same times")
        void theTwoFormsCannotDiverge() {
            List<Double> pulses = new ArrayList<>(evenPulses(40, 0.5));
            pulses.remove(15);
            pulses.add(3.25);
            pulses.sort(Double::compare);

            assertThat(BeatGrid.steadyPulseRate(pulses))
                    .isEqualTo(gridOf(pulses).steadyPulseRate());
        }

        @Test
        @DisplayName("refuses everything a grid would refuse")
        void itHoldsTheGridsInvariant() {
            // A caller that goes round the grid must not go round its invariant.
            // Each of these is rejected by BeatGrid.ofTimes too, and the parallel
            // is the point: the overload exists to save building a grid, not to
            // accept times a grid would not hold.
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(List.of(1.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fewer than two beats");
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fewer than two beats");
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(Arrays.asList(0.0, null)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(List.of(0.0, -1.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite and non-negative");
            assertThatThrownBy(() ->
                    BeatGrid.steadyPulseRate(List.of(0.0, Double.POSITIVE_INFINITY)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite and non-negative");
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(List.of(0.0, Double.NaN)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite and non-negative");
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(List.of(1.0, 0.5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly increase");
            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(List.of(1.0, 1.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly increase");
        }

        @Test
        @DisplayName("reports a non-finite time ahead of a disorder, as building a grid would")
        void finitenessIsCheckedBeforeOrdering() {
            // BeatGrid.ofTimes builds every Beat -- each of which rejects a
            // non-finite time -- before the canonical constructor looks at
            // ordering. So a list holding both faults has to name the non-finite
            // one, or the overload and the grid disagree about what is wrong with
            // the same input.
            List<Double> both = List.of(1.0, 0.0, Double.NaN);

            assertThatThrownBy(() -> BeatGrid.steadyPulseRate(both))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite and non-negative");
            assertThatThrownBy(() -> BeatGrid.ofTimes(both, FOUR_FOUR, SURE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite and non-negative");
        }

        @Test
        @DisplayName("answers for the snapshot it read, not for a list that changes after")
        void theListIsReadOnce() {
            // Every element is fetched exactly once, into a copy, before anything
            // is checked -- so what is measured is what was validated. A list that
            // returns a different value on a second read cannot make this return a
            // rate no grid would hold.
            List<Double> shifting = new java.util.AbstractList<>() {
                private int reads;

                @Override
                public Double get(int index) {
                    // Even pulses on the first pass over the list, nonsense after.
                    return reads++ < 4 ? index * 0.5 : -1.0;
                }

                @Override
                public int size() {
                    return 4;
                }
            };

            assertThat(BeatGrid.steadyPulseRate(shifting)).isCloseTo(120.0, within(1e-9));
        }
    }
}
