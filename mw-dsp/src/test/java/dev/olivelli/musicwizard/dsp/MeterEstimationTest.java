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
 * Tier-0 meter: fixtures whose bar length and subdivision are exact by
 * construction, so a reading can be compared against truth rather than against
 * another estimate.
 *
 * <p>Split in two on purpose. The gates are exercised through
 * {@link MeterEstimator#decide} on written-out readings, because what they are
 * is a decision over four numbers and driving them through synthesised audio
 * would test the synthesiser. The statistics themselves are exercised through
 * {@link MeterEstimator#read} on chroma and envelopes built to carry a known
 * period, which is the half a written-out reading cannot check.
 */
class MeterEstimationTest {

    /** Enough beats to clear the estimator's own minimum several times over. */
    private static final int BEATS = 97;

    private static final double INTERVAL = 0.5;

    private static List<Double> beats(int count) {
        List<Double> times = new ArrayList<>(count);
        for (int beat = 0; beat < count; beat++) {
            times.add(beat * INTERVAL);
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
     * An envelope striking {@code perPulse} times inside each inter-beat
     * interval, evenly.
     *
     * <p>A strike at the pulse itself and nothing else — {@code perPulse} of one
     * — is the texture a recording with no articulated subdivision has, and the
     * one the estimator must not read either way.
     */
    private static OnsetEnvelope subdivided(List<Double> beatTimes, int perPulse) {
        double frameRate = 100;
        double end = beatTimes.get(beatTimes.size() - 1) + INTERVAL;
        double[] strength = new double[(int) Math.round(end * frameRate)];
        for (int beat = 0; beat + 1 < beatTimes.size(); beat++) {
            for (int part = 0; part < perPulse; part++) {
                double at = beatTimes.get(beat) + INTERVAL * part / perPulse;
                int frame = (int) Math.round(at * frameRate);
                if (frame < strength.length) {
                    strength[frame] = 1;
                }
            }
        }
        return new OnsetEnvelope(strength, frameRate);
    }

    /** A reading with everything but the named harmonic periodicities at zero. */
    private static MeterEstimator.Reading harmonic(double atTwo, double atThree,
                                                   double atFour, double atSix) {
        return new MeterEstimator.Reading(atTwo, atThree, atFour, atSix, 1, 0, 400);
    }

    /** The same, subdivided in three rather than in two. */
    private static MeterEstimator.Reading inThree(double atTwo, double atThree,
                                                  double atFour, double atSix) {
        return new MeterEstimator.Reading(atTwo, atThree, atFour, atSix, 0, 1, 400);
    }

    @Nested
    @DisplayName("what the gates let through")
    class Gates {

        @Test
        @DisplayName("a three-beat bar the harmony states becomes 3/4")
        void threeBeatBar() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(harmonic(1, 100, 2, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(3);
            assertThat(estimate.pulseIsCountedBeat()).isTrue();
        }

        @Test
        @DisplayName("a two-beat bar subdivided in three becomes 6/8 on the counted beat")
        void twoBeatCompoundBar() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(inThree(100, 1, 2, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(estimate.pulsesPerBar()).isEqualTo(2);
            assertThat(estimate.pulseQuarters()).isEqualTo(1.5);
        }

        @Test
        @DisplayName("a two-beat bar subdivided in two is 2/4, which is left to the prior")
        void twoBeatSimpleBarHoldsTheAssumption() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(harmonic(100, 1, 2, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(4);
        }

        @Test
        @DisplayName("a three-beat bar is 3/4 however its pulse divides")
        void theSubdivisionDoesNotMoveAThreeBeatBar() {
            // 3/4 and 9/8 bar the same three pulses, so the subdivision has
            // nothing to decide here and is not asked.
            assertThat(MeterEstimator.decide(inThree(1, 100, 2, 1)).meter())
                    .isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(MeterEstimator.decide(harmonic(1, 100, 2, 1)).meter())
                    .isEqualTo(TimeSignature.THREE_FOUR);
        }

        @Test
        @DisplayName("a four-beat bar stays 4/4 however strongly the pulse swings")
        void aSwungFourBeatBarIsNotCompound() {
            // Every shuffle in the corpus reads as a triple subdivision and is
            // barred in four by its ground-truth cycle, so the subdivision may
            // not move a bar that has not already left four.
            MeterEstimator.Estimate estimate = MeterEstimator.decide(inThree(20, 1, 100, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(4);
        }

        @Test
        @DisplayName("a two-beat reading is refused where the four-beat bar is itself supported")
        void aFourBeatBarStatesItsOwnHalves() {
            // The two-beat reading wins on the raw statistic and is still a
            // four-beat bar whose halves are alike, which most comping is.
            assertThat(MeterEstimator.decide(inThree(100, 1, 20, 1)).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("nothing relates three to four, so a strong four does not refuse it")
        void aFourBeatBarDoesNotRefuseThree() {
            assertThat(MeterEstimator.decide(harmonic(1, 100, 20, 1)).meter())
                    .isEqualTo(TimeSignature.THREE_FOUR);
        }

        @Test
        @DisplayName("a winner that does not beat the assumption by the margin is refused")
        void theMarginIsWhatLeavingTheAssumptionCosts() {
            assertThat(MeterEstimator.decide(harmonic(1, 30, 20, 1)).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("a winner too weak to be a period at all is refused")
        void anUnsupportedWinnerIsRefused() {
            assertThat(MeterEstimator.decide(harmonic(1, 4, 0.1, 1)).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("six pulses grouped in threes are a compound bar the tracker filled with eighths")
        void sixPulsesGroupedInThrees() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(harmonic(5, 40, 1, 100));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.SIX_EIGHT);
            assertThat(estimate.pulsesPerBar()).isEqualTo(6);
            assertThat(estimate.pulseIsCountedBeat()).isFalse();
            // Six eighths fill the bar the meter counts in two dotted quarters.
            assertThat(estimate.pulseQuarters()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("six pulses grouped in twos are a simple bar the tracker filled with eighths")
        void sixPulsesGroupedInTwos() {
            MeterEstimator.Estimate estimate = MeterEstimator.decide(harmonic(40, 5, 1, 100));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(6);
            assertThat(estimate.pulseQuarters()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("a candidate that only wins once the winner is refused does not win")
        void refusingTheWinnerDoesNotPromoteTheRunnerUp() {
            // Two beats wins outright and is refused as a half-bar; three would
            // have cleared every gate on its own and is not the evidence.
            MeterEstimator.Estimate estimate = MeterEstimator.decide(inThree(100, 40, 20, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("a reading of nothing at all is 4/4 at the floor")
        void nothingReadIsTheAssumption() {
            MeterEstimator.Estimate estimate =
                    MeterEstimator.decide(new MeterEstimator.Reading(0, 0, 0, 0, 0, 0, 0));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(4);
            assertThat(estimate.confidence().value()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("an assumption held against contrary evidence reports near the floor")
        void theAssumptionHeldIsNotTheAssumptionSupported() {
            double contradicted = MeterEstimator.decide(inThree(100, 1, 20, 1))
                    .confidence().value();
            double supported = MeterEstimator.decide(harmonic(5, 1, 100, 1))
                    .confidence().value();

            double floor = MeterEstimator.decide(new MeterEstimator.Reading(0, 0, 0, 0, 0, 0, 0))
                    .confidence().value();

            assertThat(contradicted).isLessThan(supported).isCloseTo(floor, within(0.05));
        }

        @Test
        @DisplayName("no reading reports certainty")
        void nothingIsCertain() {
            assertThat(MeterEstimator.decide(harmonic(1, 1000, 1, 1)).confidence().value())
                    .isLessThan(1.0);
        }

        @Test
        @DisplayName("a bar length outside the candidates is not a question this answers")
        void unknownBarLength() {
            assertThatThrownBy(() -> harmonic(1, 1, 1, 1).at(5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("what the statistics measure")
    class Statistics {

        @Test
        @DisplayName("harmony that changes every three beats is periodic at three and nowhere else")
        void harmonicPeriod() {
            List<Double> times = beats(BEATS);
            MeterEstimator.Reading reading = MeterEstimator.read(
                    times, stepwiseChroma(BEATS - 1, 3), subdivided(times, 1));

            assertThat(reading.atThree()).isGreaterThan(reading.atTwo())
                    .isGreaterThan(reading.atFour());
            // The null this is read against has expectation one at every period.
            assertThat(reading.atTwo()).isLessThan(5.0);
            assertThat(reading.atFour()).isLessThan(5.0);
        }

        @Test
        @DisplayName("onsets on the half pulse read duple and on the thirds read triple")
        void subdivision() {
            List<Double> times = beats(BEATS);
            Chroma chroma = stepwiseChroma(BEATS - 1, 4);

            assertThat(MeterEstimator.read(times, chroma, subdivided(times, 2))
                    .subdividesInThree()).isFalse();
            assertThat(MeterEstimator.read(times, chroma, subdivided(times, 3))
                    .subdividesInThree()).isTrue();
        }

        @Test
        @DisplayName("a pulse with nothing struck between pulses is read neither way")
        void noSubdivisionIsNoEvidence() {
            List<Double> times = beats(BEATS);
            MeterEstimator.Reading reading = MeterEstimator.read(
                    times, stepwiseChroma(BEATS - 1, 4), subdivided(times, 1));

            // A strike at the pulse alone lands on every harmonic of the pulse
            // equally, so it argues for neither division.
            assertThat(reading.duple()).isCloseTo(reading.triple(),
                    within(reading.duple() * 0.1));
        }

        @Test
        @DisplayName("a three-beat click track with a chord a bar reads 3/4 end to end")
        void endToEnd() {
            List<Double> times = beats(BEATS);
            MeterEstimator.Estimate estimate = MeterEstimator.estimate(
                    times, stepwiseChroma(BEATS - 1, 3), subdivided(times, 1));

            assertThat(estimate.meter()).isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(estimate.pulsesPerBar()).isEqualTo(3);
        }

        @Test
        @DisplayName("too few beats to carry a period is answered as the assumption, not measured")
        void tooFewBeats() {
            List<Double> times = beats(20);
            MeterEstimator.Reading reading = MeterEstimator.read(
                    times, stepwiseChroma(19, 3), subdivided(times, 1));

            assertThat(reading.atThree()).isZero();
            assertThat(MeterEstimator.decide(reading).meter())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
        }

        @Test
        @DisplayName("a chroma that does not line up with these beats is rejected")
        void misalignedChroma() {
            List<Double> times = beats(BEATS);

            assertThatThrownBy(() -> MeterEstimator.read(
                    times, stepwiseChroma(BEATS + 5, 3), subdivided(times, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("beat-synchronous");
        }

        @Test
        @DisplayName("a recording with no harmony at all is the assumption rather than an error")
        void noHarmony() {
            List<Double> times = beats(BEATS);

            assertThat(MeterEstimator.estimate(times, new Chroma(new double[0][], 0),
                    subdivided(times, 1)).meter()).isEqualTo(TimeSignature.FOUR_FOUR);
        }
    }
}
