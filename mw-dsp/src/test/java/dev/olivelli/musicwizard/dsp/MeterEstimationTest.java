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
 * is a decision over a handful of numbers and driving them through synthesised
 * audio would test the synthesiser. The statistics themselves are exercised
 * through {@link MeterEstimator#read} on chroma built to carry a known period
 * and on an envelope clicking at a known division, which is the half a
 * written-out reading cannot check — and in particular that a period scores its
 * own divisors just as strongly, which is the whole reason the divisor rule
 * exists.
 */
class MeterEstimationTest {

    /** Enough beats to clear the estimator's own minimum several times over. */
    private static final int BEATS = 97;

    /** The spacing of those beats, which is the pulse the divisions divide. */
    private static final double PULSE_SECONDS = 0.5;

    private static List<Double> beats(int count) {
        List<Double> times = new ArrayList<>(count);
        for (int beat = 0; beat < count; beat++) {
            times.add(beat * PULSE_SECONDS);
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

    /**
     * A reading over enough beats to be one, with the harmonic periodicities
     * written out and nothing read about how the pulse divides.
     */
    private static MeterEstimator.Reading reading(double atTwo, double atThree,
                                                  double atFour, double atSix) {
        return reading(atTwo, atThree, atFour, atSix, 0, 0);
    }

    /** The same with the pulse's divisions written out too. */
    private static MeterEstimator.Reading reading(double atTwo, double atThree,
                                                  double atFour, double atSix,
                                                  double inThree, double inTwo) {
        return new MeterEstimator.Reading(atTwo, atThree, atFour, atSix, inThree, inTwo, 400);
    }

    /** A reading of nothing at all, which is what too short a recording gives. */
    private static MeterEstimator.Reading nothing() {
        return new MeterEstimator.Reading(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * An onset envelope clicking at every pulse and at every division of one,
     * over the same beats {@link #beats} lays down.
     */
    private static OnsetEnvelope clicks(int divisions) {
        // A frame rate that makes every division of the pulse a whole number of
        // frames, so what is measured is the statistic and not the rounding.
        double frameRate = 120;
        double lag = frameRate * PULSE_SECONDS;
        double[] strength = new double[(int) Math.round(lag * BEATS)];
        for (double at = 0; at <= strength.length - 1; at += lag / divisions) {
            strength[(int) Math.round(at)] = 1;
        }
        return new OnsetEnvelope(strength, frameRate);
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
        @DisplayName("the harmony alone never reads a two-pulse bar")
        void theHarmonyAloneDoesNotReadTwo() {
            // Harmony that moves every two pulses is a four-beat bar with two
            // chords in it as readily as a bar of two, so with nothing read
            // about the pulse the assumption stands however strong it is (#704).
            MeterEstimator.Estimate estimate = MeterEstimator.decide(reading(400, 1, 0.5, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(4);
        }

        @Test
        @DisplayName("harmony in two under a pulse that divides in three is 6/8")
        void twoPulsesToACompoundBar() {
            MeterEstimator.Estimate estimate =
                    MeterEstimator.decide(reading(400, 1, 0.5, 1, 0.9, 0.1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(estimate.pulsesPerBar()).isEqualTo(2);
            assertThat(estimate.pulseQuarters()).isEqualTo(1.5);
        }

        @Test
        @DisplayName("the same harmony under a pulse that divides in two stays in four")
        void twoBeatCompingIsNotABarOfTwo() {
            // The endemic case the division exists to refuse: a vamp comping
            // every two beats of a four-beat bar.
            MeterEstimator.Estimate estimate =
                    MeterEstimator.decide(reading(400, 1, 0.5, 1, 0.1, 0.9));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(4);
        }

        @Test
        @DisplayName("a division in three does not shorten a bar the harmony supports")
        void aSupportedBarRefusesTheDivision() {
            // Every shuffle in the corpus divides its pulse in three and is
            // barred in four by its own cycle (#701), so a supported four-beat
            // bar has to survive a triple division whatever the harmony makes
            // of period two.
            assertThat(MeterEstimator.decide(reading(400, 1, 40, 1, 0.9, 0.1)).pulsesPerBar())
                    .isEqualTo(4);
            // And a supported three, which the same reasoning covers.
            assertThat(MeterEstimator.decide(reading(400, 40, 1, 1, 0.9, 0.1)).meter())
                    .isEqualTo(TimeSignature.THREE_FOUR);
        }

        @Test
        @DisplayName("a two-pulse rival the division refused still costs the assumption")
        void aRefusedTwoIsStillARival() {
            double contradicted =
                    MeterEstimator.decide(reading(400, 1, 20, 1, 0.1, 0.9)).confidence().value();
            double supported = MeterEstimator.decide(reading(1, 1, 100, 1)).confidence().value();
            double floor = MeterEstimator.decide(nothing()).confidence().value();

            assertThat(contradicted).isLessThan(supported).isCloseTo(floor, within(0.05));
        }

        @Test
        @DisplayName("a reading of nothing at all is 4/4 at the floor")
        void nothingReadIsTheAssumption() {
            MeterEstimator.Estimate estimate =
                    MeterEstimator.decide(nothing());

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
            double floor = MeterEstimator.decide(nothing())
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
                    MeterEstimator.read(times, stepwiseChroma(BEATS - 1, 3), clicks(1));

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
                    MeterEstimator.read(times, stepwiseChroma(BEATS - 1, 6), clicks(1));

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
                    MeterEstimator.estimate(times, stepwiseChroma(BEATS - 1, 3), clicks(1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(3);
        }

        @Test
        @DisplayName("too few beats to carry a period is answered as the assumption, not measured")
        void tooFewBeats() {
            List<Double> times = beats(20);
            MeterEstimator.Reading reading = MeterEstimator.read(times, stepwiseChroma(19, 3), clicks(1));

            assertThat(reading.atThree()).isZero();
            assertThat(reading.usableBeats()).isEqualTo(18);
            assertThat(MeterEstimator.decide(reading).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("a chroma that does not line up with these beats is rejected")
        void misalignedChroma() {
            List<Double> times = beats(BEATS);

            assertThatThrownBy(() -> MeterEstimator.read(times, stepwiseChroma(BEATS + 5, 3), clicks(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("beat-synchronous");
        }

        @Test
        @DisplayName("a pulse struck in thirds divides in three, and one struck in halves in two")
        void divisionsOfThePulse() {
            List<Double> times = beats(BEATS);
            Chroma chroma = stepwiseChroma(BEATS - 1, 2);

            MeterEstimator.Reading inThree = MeterEstimator.read(times, chroma, clicks(3));
            MeterEstimator.Reading inTwo = MeterEstimator.read(times, chroma, clicks(2));

            assertThat(inThree.inThree()).isGreaterThan(inThree.inTwo());
            assertThat(inTwo.inTwo()).isGreaterThan(inTwo.inThree());
            // A pulse struck and nothing else says nothing about either.
            MeterEstimator.Reading undivided = MeterEstimator.read(times, chroma, clicks(1));
            assertThat(undivided.inThree()).isLessThan(0.1);
            assertThat(undivided.inTwo()).isLessThan(0.1);
        }

        @Test
        @DisplayName("harmony every two pulses over a pulse struck in thirds is 6/8, end to end")
        void aCompoundBarCountedInTwo() {
            List<Double> times = beats(BEATS);
            Chroma chroma = stepwiseChroma(BEATS - 1, 2);

            assertThat(MeterEstimator.estimate(times, chroma, clicks(3)).meter())
                    .isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(MeterEstimator.estimate(times, chroma, clicks(3)).pulsesPerBar())
                    .isEqualTo(2);
            // The same harmony over a pulse struck in halves is the assumption.
            assertThat(MeterEstimator.estimate(times, chroma, clicks(2)).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("a recording with no harmony at all is the assumption rather than an error")
        void noHarmony() {
            assertThat(MeterEstimator.estimate(beats(BEATS), new Chroma(new double[0][], 0), clicks(1))
                    .meter()).isEqualTo(TimeSignature.FOUR_FOUR);
        }
    }
}
