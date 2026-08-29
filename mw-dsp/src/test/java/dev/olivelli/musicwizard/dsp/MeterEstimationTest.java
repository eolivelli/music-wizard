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

package dev.olivelli.musicwizard.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tier-0 meter: fixtures whose bar length is exact by construction, so a
 * reading can be compared against truth rather than against another estimate.
 *
 * <p>Split in two on purpose. The gates are exercised through
 * {@link MeterEstimator#decide} on written-out readings, because what they are
 * is a decision over four numbers and driving them through synthesised audio
 * would test the synthesiser. The statistic itself is exercised through
 * {@link MeterEstimator#read} on chroma built to carry a known period, which is
 * the half a written-out reading cannot check — and in particular that a period
 * scores its own divisors just as strongly, which is the whole reason the
 * divisor rule exists.
 */
class MeterEstimationTest {

    /** Enough beats to clear the estimator's own minimum several times over. */
    private static final int BEATS = 97;

    private static List<Double> beats(int count) {
        List<Double> times = new ArrayList<>(count);
        for (int beat = 0; beat < count; beat++) {
            times.add(beat * 0.5);
        }
        return times;
    }

    /** Chroma holding one pitch class for {@code perBar} spans, then moving on. */
    private static Chroma stepwiseChroma(int spans, int perBar) {
        double[][] vectors = new double[spans][12];
        for (int span = 0; span < spans; span++) {
            vectors[span][Math.floorMod(Math.floorDiv(span, perBar) * 5, 12)] = 1;
        }
        return new Chroma(vectors, 0);
    }

    /** A reading over enough beats to be one, with the periodicities written out. */
    private static MeterEstimator.Reading reading(double atTwo, double atThree,
                                                  double atFour, double atSix) {
        return new MeterEstimator.Reading(atTwo, atThree, atFour, atSix, 400);
    }

    @Nested
    @DisplayName("what the gates let through")
    class Gates {

        @Test
        @DisplayName("a three-pulse bar the harmony states becomes 3/4")
        void threePulseBar() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(reading(1, 100, 2, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(3);
            assertThat(estimate.pulseIsCountedBeat()).isTrue();
        }

        @Test
        @DisplayName("nothing relates three to four, so a strong four does not refuse it")
        void aFourPulseBarDoesNotRefuseThree() {
            assertThat(MeterEstimator.decide(reading(1, 100, 20, 1)).meter())
                    .isEqualTo(TimeSignature.THREE_FOUR);
        }

        @Test
        @DisplayName("a winner that does not beat the assumption by the margin is refused")
        void theMarginIsWhatLeavingTheAssumptionCosts() {
            assertThat(MeterEstimator.decide(reading(1, 30, 20, 1)).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("a winner too weak to be a period at all is refused")
        void anUnsupportedWinnerIsRefused() {
            assertThat(MeterEstimator.decide(reading(1, 4, 0.1, 1)).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("six is believed over three wherever six carries a comparable share")
        void threeDividesSix() {
            // What a bar of six that marks nothing but its own line looks like:
            // the statistic scores three exactly as strongly as six, so without
            // the divisor rule the reading falls to floating-point residue.
            MeterEstimator.Estimate estimate = MeterEstimator.decide(reading(50, 50, 1, 50));

            assertThat(estimate.pulsesPerBar()).isEqualTo(6);
            assertThat(estimate.meter()).isEqualTo(TimeSignature.SIX_EIGHT);
            // Six eighths fill the bar the meter counts in two dotted quarters.
            assertThat(estimate.pulseQuarters()).isEqualTo(0.5);
            assertThat(estimate.pulseIsCountedBeat()).isFalse();
        }

        @Test
        @DisplayName("a three-pulse bar keeps its reading where six is far behind")
        void aRealThreeIsNotTakenBySix() {
            // Two bars of three is a phrase, not a bar, and scores as one.
            assertThat(MeterEstimator.decide(reading(30, 150, 7, 24)).pulsesPerBar())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("six pulses grouped in twos are a simple bar the tracker filled with eighths")
        void sixPulsesGroupedInTwos() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(reading(40, 5, 1, 100));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(6);
            // Whichever way that reads, the bar lines are the same six pulses.
            assertThat(estimate.pulseQuarters()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("six pulses grouped in threes are a compound bar")
        void sixPulsesGroupedInThrees() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(reading(5, 40, 1, 100));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(estimate.pulsesPerBar()).isEqualTo(6);
        }

        @Test
        @DisplayName("a two-pulse bar is not a reading this makes")
        void twoIsNotACandidate() {
            // Harmony that moves every two pulses is a four-beat bar with two
            // chords in it as readily as a bar of two, and nothing measured
            // separates them (#701). The assumption stands.
            MeterEstimator.Estimate estimate = MeterEstimator.decide(reading(400, 1, 0.5, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(4);
        }

        @Test
        @DisplayName("a reading of nothing at all is 4/4 at the floor")
        void nothingReadIsTheAssumption() {
            MeterEstimator.Estimate estimate =
                    MeterEstimator.decide(new MeterEstimator.Reading(0, 0, 0, 0, 0));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(4);
            assertThat(estimate.confidence().value()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("an assumption held against contrary evidence reports near the floor")
        void theAssumptionHeldIsNotTheAssumptionSupported() {
            // A three-pulse reading that all but clears the margin: refused, so
            // the answer is the assumption, and the assumption is nearly all it
            // has going for it.
            double contradicted = MeterEstimator.decide(reading(1, 79, 20, 1))
                    .confidence().value();
            double supported = MeterEstimator.decide(reading(1, 1, 100, 1)).confidence().value();
            double floor = MeterEstimator.decide(new MeterEstimator.Reading(0, 0, 0, 0, 0))
                    .confidence().value();

            assertThat(MeterEstimator.decide(reading(1, 79, 20, 1)).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(contradicted).isLessThan(supported).isCloseTo(floor, within(0.05));
        }

        @Test
        @DisplayName("no reading reports certainty")
        void nothingIsCertain() {
            assertThat(MeterEstimator.decide(reading(1, 1000, 1, 1)).confidence().value())
                    .isLessThan(1.0);
        }

        @Test
        @DisplayName("a period outside the four this reads is not a question it answers")
        void unknownPeriod() {
            assertThatThrownBy(() -> reading(1, 1, 1, 1).at(5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("what the statistic measures")
    class Statistics {

        @Test
        @DisplayName("harmony that changes every three beats is periodic at three, not at four")
        void harmonicPeriod() {
            List<Double> times = beats(BEATS);
            MeterEstimator.Reading reading =
                    MeterEstimator.read(times, stepwiseChroma(BEATS - 1, 3));

            assertThat(reading.atThree()).isGreaterThan(reading.atFour());
            // The null this is read against has expectation one at every period.
            assertThat(reading.atFour()).isLessThan(5.0);
            assertThat(reading.atSix()).isLessThan(5.0);
        }

        @Test
        @DisplayName("a period scores its own divisors exactly as strongly as itself")
        void aPeriodScoresItsDivisors() {
            // The defect the divisor rule exists for: this is a measurement of
            // energy at a frequency, and a series repeating every six beats
            // repeats every three and every two as well.
            List<Double> times = beats(BEATS);
            MeterEstimator.Reading reading =
                    MeterEstimator.read(times, stepwiseChroma(BEATS - 1, 6));

            assertThat(reading.atSix()).isCloseTo(reading.atThree(), within(1e-9))
                    .isCloseTo(reading.atTwo(), within(1e-9));
            // And the rule is what stops the three from taking it.
            assertThat(MeterEstimator.decide(reading).pulsesPerBar()).isEqualTo(6);
        }

        @Test
        @DisplayName("a three-beat cycle read end to end is 3/4")
        void endToEnd() {
            List<Double> times = beats(BEATS);
            MeterEstimator.Estimate estimate =
                    MeterEstimator.estimate(times, stepwiseChroma(BEATS - 1, 3));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(3);
        }

        @Test
        @DisplayName("too few beats to carry a period is answered as the assumption, not measured")
        void tooFewBeats() {
            List<Double> times = beats(20);
            MeterEstimator.Reading reading = MeterEstimator.read(times, stepwiseChroma(19, 3));

            assertThat(reading.atThree()).isZero();
            assertThat(reading.usableBeats()).isEqualTo(18);
            assertThat(MeterEstimator.decide(reading).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("a chroma that does not line up with these beats is rejected")
        void misalignedChroma() {
            List<Double> times = beats(BEATS);

            assertThatThrownBy(() -> MeterEstimator.read(times, stepwiseChroma(BEATS + 5, 3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("beat-synchronous");
        }

        @Test
        @DisplayName("a recording with no harmony at all is the assumption rather than an error")
        void noHarmony() {
            assertThat(MeterEstimator.estimate(beats(BEATS), new Chroma(new double[0][], 0))
                    .meter()).isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("beats that do not ascend are answered rather than indexed off the end")
        void unorderedBeats() {
            List<Double> times = new ArrayList<>(beats(BEATS));
            times.set(10, times.get(60));

            assertThat(MeterEstimator.estimate(times, stepwiseChroma(BEATS - 1, 3)).meter())
                    .isNotNull();
        }
    }
}
