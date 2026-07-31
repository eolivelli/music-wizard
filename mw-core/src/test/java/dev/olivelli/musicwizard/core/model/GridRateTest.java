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

import java.util.AbstractList;
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
        @DisplayName("the overload refuses every beat time a grid would refuse")
        void theStaticOverloadEnforcesTheWholeInvariant() {
            // A caller reaching past the grid must not reach past its
            // validation, and this is the one entry point where no constructor
            // has already refused the input.
            //
            // Round 4 of review found this checking the ordering alone while its
            // name claimed the invariant -- the layer the round before had named
            // rather than the layer the invariant lives at, which is Beat's own
            // constructor. So the cases below are read off that constructor
            // rather than off what seemed likely to matter, and the two
            // non-finite ones are the reason: they returned an answer.
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(0.5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two beats");
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(1.0, 0.5, 2.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly increase");
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(1.0, 1.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly increase");
            // 0.0 BPM before round 4, which a caller dividing 60 by turns into
            // infinity -- and a grid cannot hold an infinite beat time at all.
            assertThatThrownBy(() ->
                    BeatGrid.overallPulseRate(List.of(0.0, Double.POSITIVE_INFINITY)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite and non-negative");
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(0.0, Double.NaN)))
                    .isInstanceOf(IllegalArgumentException.class);
            // 30.0 BPM before round 4: plausible, and off a grid that could not
            // exist, since a beat before the recording started is not a beat.
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(-3.0, -1.0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite and non-negative");

            assertThatThrownBy(() -> BeatGrid.overallPulseRate(null))
                    .isInstanceOf(NullPointerException.class);
            // A null element, which List.of cannot hold but an ArrayList can.
            // Named, so the message says which beat rather than coming out of an
            // unboxing that mentions no beats at all.
            List<Double> withNull = new ArrayList<>(List.of(0.0, 0.5));
            withNull.add(null);
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(withNull))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("pulseSeconds[2]");
        }

        @Test
        @DisplayName("answers whatever the grid's own accessor answers, refusals included")
        void theStaticOverloadMirrorsTheInstanceAccessor() {
            // The property the case list above can only sample, and the reason
            // it is stated against the instance accessor rather than against the
            // constructor: round 5 of review found the first version of this
            // claiming the overload "accepts exactly what a grid accepts", which
            // is false at one pulse. ofTimes builds a perfectly legal one-beat
            // grid; neither form of overallPulseRate will answer for it. The
            // candidate table was all of length two or three, so it never
            // reached the one arity where the two disagree -- and the test
            // nineteen lines above asserts that very rejection.
            //
            // Against gridOf(...).overallPulseRate() the claim is true, and it
            // is the claim worth having anyway: what a caller reaching past the
            // grid must not be able to do is get a different answer, not build a
            // different grid.
            //
            // "One arity" is a universal, and this table is still a sample, so
            // it was checked once by exhaustion rather than argued: every list
            // of length 0 to 4 over {null, 0.0, -0.0, 0.5, 1.0, -1.0, NaN, both
            // infinities, MIN_VALUE, 2.0} -- 16105 of them -- and no
            // disagreement outside length 1. That sweep is not kept as a test;
            // it took a minute to run and its result is a fact about a
            // validation order, which the rows below are chosen to hold.
            List<List<Double>> candidates = new ArrayList<>(List.of(
                    List.of(),
                    List.of(0.5),
                    List.of(0.0, 0.5, 1.0),
                    List.of(0.05, 0.4, 1.9),
                    List.of(1.0, 0.5, 2.0),
                    List.of(1.0, 1.0),
                    List.of(-3.0, -1.0),
                    List.of(0.0, Double.POSITIVE_INFINITY),
                    List.of(Double.NEGATIVE_INFINITY, 0.0),
                    List.of(0.0, Double.NaN),
                    List.of(0.0, -0.0),
                    List.of(-0.0, 0.5),
                    List.of(0.0, Double.MIN_VALUE),
                    List.of(0.0, Double.MAX_VALUE)));
            candidates.add(listOf(0.0, null));
            candidates.add(listOf(null, 0.5));
            // Out of order *and* holding a null, which is what round 6 found the
            // two forms answering differently: the grid builds every Beat before
            // it looks at ordering, so it reports the null, and a single fused
            // validation pass reported the ordering. Both orderings of the two
            // faults, since only one of them was reachable.
            candidates.add(listOf(0.0, 0.0, null));
            candidates.add(listOf(1.0, null, 0.5));
            candidates.add(listOf(5.0, 1.0, Double.NaN));

            for (List<Double> times : candidates) {
                String viaOverload = answerOf(() -> BeatGrid.overallPulseRate(times));
                String viaGrid = answerOf(() -> gridOf(times).overallPulseRate());
                if (times.size() == 1) {
                    // The one input on which the two differ, kept in the table
                    // and named rather than left out of it -- leaving it out is
                    // what made the first version of this test false. Both
                    // refuse; they disagree only about whose fault it is, which
                    // the test below states.
                    assertThat(viaOverload).isEqualTo("IllegalArgumentException");
                    assertThat(viaGrid).isEqualTo("IllegalStateException");
                    continue;
                }
                assertThat(viaOverload).as("%s", times).isEqualTo(viaGrid);
            }
        }

        @Test
        @DisplayName("but says a lone pulse is a bad argument where the grid says it is a bad state")
        void theTwoFormsDifferOnlyInWhatTheyCallAOnePulseGrid() {
            // The one difference the mirror above cannot see, since both refuse.
            // Named rather than left implicit, because it is a real distinction
            // and not an oversight: a one-pulse list is a caller's mistake,
            // while a one-pulse grid is a legitimate object being asked
            // something it cannot answer -- which is exactly the case
            // Score.estimatedTempo guards with size() >= 2 rather than catching.
            assertThatThrownBy(() -> BeatGrid.overallPulseRate(List.of(0.5)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> gridOf(List.of(0.5)).overallPulseRate())
                    .isInstanceOf(IllegalStateException.class);
            // And the grid itself is legal, which is the fact that made the
            // first version of the mirror test false.
            assertThat(gridOf(List.of(0.5)).size()).isEqualTo(1);
        }

        @Test
        @DisplayName("measures the times it validated, not whatever the list says afterwards")
        void aListThatChangesUnderneathCannotGetPastTheCheck() {
            // The one shape the table above cannot express, whatever rows are
            // added to it: every row is a stable list, and this is a list that
            // answers differently the second time it is asked. Round 7 of review
            // found the previous version validating the caller's list in place
            // and then measuring it again -- four separate reads of the same
            // index -- and got -20 BPM out of the method whose whole job is
            // refusing what a grid would refuse.
            //
            // This fixture demonstrates the other direction of that same defect,
            // and the comment used to imply otherwise: against the old code it
            // rejects a list it had already accepted ("beat 1 at 1.0s does not
            // follow beat 0 at 1.5s"), because the second read comes back
            // reversed. A fixture for the -20 BPM direction would need the
            // second read to be ordered too. One defect, two faces, and the
            // assertion below holds for both -- what is measured has to be what
            // was checked.
            //
            // ofTimes has never had the hole, because it reads each element once
            // into a Beat, so the fix is to do the same rather than to add a
            // check. Nothing reachable does this: the sole production caller
            // hands over a freshly derived list on one thread. It is here
            // because a public method in mw-core that says it enforces an
            // invariant should enforce it.
            List<Double> shifty = new AbstractList<>() {
                private int reads;

                @Override
                public Double get(int index) {
                    // Ordered and legal while being validated; reversed after,
                    // which is what turned a checked list into a negative span.
                    return reads++ < size() ? index * 0.5 : (size() - 1 - index) * 0.5;
                }

                @Override
                public int size() {
                    return 4;
                }
            };

            assertThat(BeatGrid.overallPulseRate(shifty))
                    .as("the rate of the times that were checked, which is 0 to 1.5s in four")
                    .isCloseTo(120.0, within(1e-9));
        }

        @Test
        @DisplayName("and a list that shrinks after the arity check is measured at the arity checked")
        void aListThatShrinksAfterTheArityCheckIsMeasuredAtTheOneChecked() {
            // The same defect at the other end of the method, found in round 8:
            // the size was read once to guard and again to copy, so a list that
            // dropped to one pulse in between produced a one-element array, two
            // degenerate loops and 60.0 * 0 / 0.0 -- NaN. Worse than the 0.0 BPM
            // the javadoc cites as the reason this validation exists, because
            // every comparison against NaN is silently false, so it propagates
            // through a bound check rather than tripping one.
            List<Double> shrinking = new AbstractList<>() {
                private int sizeReads;

                @Override
                public Double get(int index) {
                    return index * 0.5;
                }

                @Override
                public int size() {
                    return sizeReads++ == 0 ? 4 : 1;
                }
            };

            // The size is read once now, so the four pulses that were guarded
            // are the four that are copied, validated and measured. Answering
            // the rate of what was checked is the guarantee; what must not
            // happen is an answer that is neither a refusal nor a rate.
            double rate = BeatGrid.overallPulseRate(shrinking);

            assertThat(rate).isNotNaN().isFinite().isPositive();
            assertThat(rate)
                    .as("four pulses 0.5s apart, which is what the arity check approved")
                    .isCloseTo(120.0, within(1e-9));
        }

        @Test
        @DisplayName("a list that really shrinks is refused, not answered")
        void aListThatActuallyShrinksIsRefused() {
            // The one escape the snapshot leaves, found in round 9 and named in
            // the javadoc rather than left to be discovered: a list that reports
            // four and then has three to give cannot be read once, and the read
            // fails. A refusal rather than a wrong answer, which is the
            // distinction that makes it acceptable -- but an undocumented
            // exception type from a public method is not.
            List<Double> vanishing = new AbstractList<>() {
                private int sizeReads;

                @Override
                public Double get(int index) {
                    if (index >= 2) {
                        throw new IndexOutOfBoundsException(
                                "Index " + index + " out of bounds for length 2");
                    }
                    return index * 0.5;
                }

                @Override
                public int size() {
                    return sizeReads++ == 0 ? 4 : 2;
                }
            };

            assertThatThrownBy(() -> BeatGrid.overallPulseRate(vanishing))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        /** A list that may hold nulls, which {@code List.of} refuses to. */
        private static List<Double> listOf(Double... times) {
            List<Double> list = new ArrayList<>(times.length);
            java.util.Collections.addAll(list, times);
            return list;
        }

        /**
         * What a call answered: its value, or the exception it threw.
         *
         * <p>The exception's class as well as the fact of one, so the table
         * cannot pass while the two entry points refuse the same input for
         * different kinds of reason.
         */
        private static String answerOf(java.util.function.DoubleSupplier call) {
            try {
                return String.valueOf(call.getAsDouble());
            } catch (RuntimeException refused) {
                return refused.getClass().getSimpleName();
            }
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
