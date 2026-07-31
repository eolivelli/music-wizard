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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two rates a beat grid can be asked for, and which of them places things.
 *
 * <p>#200. A tracked grid is not even: the beat tracker scores its pulses
 * against one periodic template and then keeps up with the recording by
 * shortening and lengthening around it. So the typical interval and the rate the
 * grid ran at are two different numbers, and everything downstream of
 * {@link Score#estimatedTempo()} wants the second one -- a bar line, a chart
 * cell and a metronome mark all put pulse {@code k} at {@code first + k * period},
 * which is arithmetic on an index rather than on an interval.
 *
 * <p>The grids here are built to have that shape deliberately. On an even grid
 * every statistic agrees, which is why every fixture that had one was blind to
 * this.
 */
class GridRateTest {

    private static BeatGrid gridOf(List<Double> times) {
        return BeatGrid.ofTimes(times, TimeSignature.FOUR_FOUR, Confidence.of(0.9));
    }

    /**
     * A grid with the shape a tracked one has: a periodic template that most
     * intervals sit on, and a minority that are shorter because the recording ran
     * ahead of the template.
     *
     * <p>28 intervals of 0.5s and 12 of 0.4s, interleaved so no window is
     * unrepresentative. The median is 0.5s -- the template -- while the 40
     * <em>intervals</em> that 41 pulses hold span 18.8s, a rate of 0.47s: 6%
     * apart, where the real recording's two figures are 1.4% apart.
     * Accumulating 0.4s repeatedly is not exact in binary, so the assertions
     * below carry a tolerance rather than claiming they do not need one.
     */
    private static List<Double> aTrackedShape() {
        List<Double> times = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < 40; i++) {
            times.add(at);
            at += i % 10 < 3 ? 0.4 : 0.5;
        }
        times.add(at);
        return times;
    }

    @Nested
    @DisplayName("the rate a grid ran at")
    class OverallRate {

        @Test
        @DisplayName("is the span divided by the intervals in it, not the typical interval")
        void isASpanNotAnInterval() {
            BeatGrid grid = gridOf(aTrackedShape());

            assertThat(grid.size()).isEqualTo(41);
            assertThat(grid.beatTimes().get(40) - grid.beatTimes().get(0))
                    .isCloseTo(18.8, within(1e-9));
            assertThat(grid.medianPulseRate())
                    .as("the template the tracker scored against")
                    .isCloseTo(120.0, within(1e-9));
            assertThat(grid.overallPulseRate())
                    .as("what the pulses actually did: 40 intervals in 18.8s")
                    .isCloseTo(60.0 * 40 / 18.8, within(1e-9))
                    .isCloseTo(127.66, within(0.01));
        }

        @Test
        @DisplayName("places pulse k where pulse k is, which the median does not")
        void placesThePulseItIsAskedFor() {
            // The property every reader of estimatedTempo depends on and none of
            // them states. A bar line, a chart cell and a metronome mark are all
            // "first + k * period", so the period has to be the one that makes
            // that land on pulse k.
            BeatGrid grid = gridOf(aTrackedShape());
            List<Double> times = grid.beatTimes();
            double fromRate = 60.0 / grid.overallPulseRate();
            double fromMedian = 60.0 / grid.medianPulseRate();

            double rateError = 0;
            double medianError = 0;
            for (int k = 0; k < times.size(); k++) {
                rateError = Math.max(rateError,
                        Math.abs(times.get(0) + k * fromRate - times.get(k)));
                medianError = Math.max(medianError,
                        Math.abs(times.get(0) + k * fromMedian - times.get(k)));
            }
            // The rate is exact at both ends and stays within half a pulse
            // between them -- 0.21s of a 0.5s pulse, which is the grid's own
            // wander and nothing a constant period can remove. The median never
            // recovers: 6% slow for all forty pulses is 1.2s, two and a half
            // pulses, and still growing when the grid ends.
            assertThat(rateError).isLessThan(0.5 * 0.5);
            assertThat(medianError).isCloseTo(1.2, within(1e-9));
            assertThat(times.get(0) + 40 * fromMedian - times.get(40))
                    .isCloseTo(1.2, within(1e-9));
        }

        @Test
        @DisplayName("counts pulses in 4/4 and quarter notes in 6/8, like the median pair")
        void reportsPulsesAndQuarterNotesSeparately() {
            // The same conflation medianPulseRate and medianTempo exist to keep
            // apart: a pulse is a quarter note only in simple time, and a 6/8
            // pulse is a dotted quarter.
            BeatGrid grid = gridOf(aTrackedShape());

            assertThat(grid.overallTempo(TimeSignature.FOUR_FOUR))
                    .isEqualTo(grid.overallPulseRate());
            assertThat(grid.overallTempo(TimeSignature.SIX_EIGHT))
                    .isCloseTo(grid.overallPulseRate() * 1.5, within(1e-9));
        }

        @Test
        @DisplayName("needs two pulses, because one carries no interval")
        void needsTwoPulses() {
            BeatGrid lonely = gridOf(List.of(0.05));

            assertThatThrownBy(lonely::overallPulseRate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("two beats");
            assertThatThrownBy(() -> lonely.overallTempo(TimeSignature.FOUR_FOUR))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("answers the same off bare pulse times as off a built grid")
        void theStaticOverloadIsTheSameStatistic() {
            // The overload exists for the transcriber, which reports the rate it
            // tracked before the downbeat phase is known and so has no grid to
            // ask. Two copies of a rate is exactly how the progress line and the
            // chart header came to disagree, so this pins them to one answer
            // rather than to two that happen to match today.
            List<Double> times = aTrackedShape();

            assertThat(BeatGrid.overallPulseRate(times))
                    .isEqualTo(gridOf(times).overallPulseRate());
        }

        @Test
        @DisplayName("the overload enforces the invariant the constructor would have")
        void theStaticOverloadStillDemandsAnOrderedGrid() {
            // A caller reaching past the grid must not reach past its validation:
            // unordered times give a negative or infinite rate, and this is the
            // one entry point where no constructor has already refused them.
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(0.5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two beats");
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(1.0, 0.5, 2.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly increase");
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(1.0, 1.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly increase");
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("agrees with the median on an even grid, which is why this was invisible")
        void agreesWithTheMedianOnAnEvenGrid() {
            List<Double> even = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                even.add(0.05 + i * 0.5);
            }
            BeatGrid grid = gridOf(even);

            assertThat(grid.overallPulseRate()).isEqualTo(grid.medianPulseRate());
            assertThat(grid.overallTempo(TimeSignature.SIX_EIGHT))
                    .isEqualTo(grid.medianTempo(TimeSignature.SIX_EIGHT));
        }

        @Test
        @DisplayName("pays for a dropped pulse in proportion to how short the grid is")
        void aDroppedPulseCostsOnePartInTheGridsLength() {
            // The first concession, executable rather than asserted in prose. The
            // median is unmoved by a dropped pulse and this is not, because it
            // is exactly the mean interval -- and the mean of n-1 intervals loses
            // one of them. What that costs is one part in n-1, so the objection
            // is real on a clip and negligible on a recording. Six pulses is
            // about three seconds, and reachable: estimatedTempo reads the grid
            // from two pulses up.
            for (int pulses : new int[] {6, 12, 20, 200, 2000}) {
                List<Double> even = new ArrayList<>();
                for (int i = 0; i < pulses; i++) {
                    even.add(i * 0.5);
                }
                List<Double> dropped = new ArrayList<>(even);
                dropped.remove(pulses / 2);

                assertThat(gridOf(dropped).medianPulseRate())
                        .as("%d pulses, median", pulses)
                        .isCloseTo(120.0, within(1e-9));
                // 60/(0.5 * (n-1)/(n-2)) -- 5.3% low at 20 pulses, 0.05% at 2000.
                assertThat(gridOf(dropped).overallPulseRate())
                        .as("%d pulses, rate", pulses)
                        .isCloseTo(120.0 * (pulses - 2) / (pulses - 1.0), within(1e-9));
            }
        }

        @Test
        @DisplayName("pays for a partly mistracked recording however long the grid is")
        void anOctaveErrorOverPartOfAGridIsNotBoundedByItsLength() {
            // The second concession, and the one that does not shrink -- so it is
            // asserted rather than left to the sentence above, which bounds only
            // the first. A stretch tracked an octave out moves this by the
            // fraction of the duration it covers, and moves the median not at
            // all. Round 1 of review found the javadoc claiming the whole trade
            // fell away with length, on the strength of the dropped-pulse case
            // alone.
            //
            // Measured at two lengths a factor of ten apart, because round 2
            // found the display name asserting length-independence off a single
            // grid -- which is the same defect one level up. The answer is
            // identical at both, since the ratio depends on the fraction and not
            // on the count.
            //
            // Not hypothetical: BeatTracker re-estimates the tempo per window and
            // its own javadoc says the autocorrelation peak "is prone to landing
            // an octave out". Nothing measured bounds it in general.
            // gmajorblues.mp3 happens to contain no such stretch -- 8 of its 1280
            // intervals are under 0.6x the median and the longest consecutive run
            // is 2, spanning 0.563s of 710.7s -- and that is the short side,
            // which is the side a double-time stretch is on. #205.
            for (int scale : new int[] {1, 10}) {
                List<Double> partlyDoubled = new ArrayList<>();
                double at = 0;
                for (int i = 0; i < 80 * scale; i++) {
                    partlyDoubled.add(at);
                    at += 0.5;
                }
                for (int i = 0; i < 40 * scale; i++) {
                    partlyDoubled.add(at);
                    at += 0.25;
                }
                partlyDoubled.add(at);
                BeatGrid grid = gridOf(partlyDoubled);

                assertThat(grid.medianPulseRate())
                        .as("%d pulses: right for the four fifths of the duration that "
                                + "is tracked right", grid.size())
                        .isCloseTo(120.0, within(1e-9));
                assertThat(grid.overallPulseRate())
                        .as("%d pulses: right nowhere, since 120 for 40s and then 240 "
                                + "for 10s averages to 144", grid.size())
                        .isCloseTo(144.0, within(1e-9));
            }
        }
    }

    @Nested
    @DisplayName("the tempo a score reports")
    class ScoreTempo {

        @Test
        @DisplayName("is the grid's rate, not the grid's median interval")
        void readsTheRate() {
            // Both halves matter. The first says which accessor is read; the
            // second says the fixture can tell, which every earlier one could
            // not because its grid was even.
            BeatGrid grid = gridOf(aTrackedShape());
            Score score = Score.empty(
                            TempoMap.fromBeatTimes(grid.beatTimes(), TimeSignature.FOUR_FOUR),
                            20.0)
                    .withBeatGrid(grid);

            assertThat(score.estimatedTempo())
                    .isEqualTo(grid.overallTempo(TimeSignature.FOUR_FOUR));
            assertThat(score.estimatedTempo())
                    .as("the median is a different number here, so this discriminates")
                    .isNotCloseTo(grid.medianTempo(TimeSignature.FOUR_FOUR), within(1.0));
        }

        @Test
        @DisplayName("still loses to a tempo somebody typed")
        void aCorrectionStillWins() {
            // The rule this change must not disturb: --tempo moves the map and
            // not the grid, and a correction that loses to the value it corrects
            // is not a correction. Read alongside
            // ChordChartTest.headerAndBarsCannotDisagree, which is the same rule
            // seen from the chart.
            BeatGrid grid = gridOf(aTrackedShape());
            TempoMap supplied = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 60.0, Provenance.SUPPLIED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));

            assertThat(Score.empty(supplied, 20.0).withBeatGrid(grid).estimatedTempo())
                    .isEqualTo(60.0);
        }
    }
}
