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
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tier-0 beat tracking: the tempo is exact by construction, so these compare
 * against truth rather than against another estimate. A failure here is a real
 * defect; there is no "hard input" excuse available.
 */
class BeatTrackingTest {

    private static final int RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    private static OnsetEnvelope envelopeOf(float[] samples) {
        return OnsetEnvelope.fromAudio(new AudioBuffer(samples, RATE));
    }

    @Nested
    @DisplayName("onset envelope")
    class Onsets {

        @Test
        @DisplayName("peaks at the clicks and is quiet between them")
        void peaksAtClicks() {
            OnsetEnvelope envelope = envelopeOf(SignalFactory.clickTrack(120, 8, RATE));

            // A click every 0.5s. Energy near a click must beat energy at the
            // midpoint between clicks.
            double atClicks = 0;
            double betweenClicks = 0;
            for (double t = 1.0; t < 7.0; t += 0.5) {
                atClicks += peakNear(envelope, t, 0.06);
                betweenClicks += peakNear(envelope, t + 0.25, 0.06);
            }
            assertThat(atClicks).isGreaterThan(betweenClicks * 2);
        }

        private double peakNear(OnsetEnvelope envelope, double seconds, double tolerance) {
            int from = envelope.frameOf(seconds - tolerance);
            int to = envelope.frameOf(seconds + tolerance);
            double peak = 0;
            for (int i = from; i <= to && i < envelope.length(); i++) {
                peak = Math.max(peak, envelope.strength()[i]);
            }
            return peak;
        }

        @Test
        @DisplayName("is flat for silence rather than inventing onsets")
        void silenceIsFlat() {
            assertThat(envelopeOf(SignalFactory.silence(4, RATE)).isFlat()).isTrue();
        }

        @Test
        @DisplayName("a sustained tone gives a far less peaky envelope than a click track")
        void steadyToneIsNotPeaky() {
            // The envelope is normalised to unit variance, so what separates
            // rhythmic material is not how often it exceeds a threshold -- the
            // tone actually exceeds 2.0 more often -- but how far its attacks
            // stand out. Clicks reach about 10 standard deviations; a sustained
            // sine reaches under 3.
            //
            // Deliberately not asserting on Estimate.strength: that measure does
            // not separate these two cases, and tuning it until this test passed
            // would be fitting the metric to the test rather than to reality.
            double tonePeak = peak(envelopeOf(SignalFactory.sine(440, 20, RATE)));
            double clickPeak = peak(envelopeOf(SignalFactory.clickTrack(120, 20, RATE)));

            assertThat(clickPeak).isGreaterThan(2 * tonePeak);
            assertThat(tonePeak).isLessThan(4.0);
        }

        private double peak(OnsetEnvelope envelope) {
            double peak = 0;
            for (double value : envelope.strength()) {
                peak = Math.max(peak, value);
            }
            return peak;
        }
    }

    @Nested
    @DisplayName("tempo estimation")
    class Tempo {

        @ParameterizedTest(name = "a {0} BPM click track lands on the right period family")
        @ValueSource(doubles = {90, 100, 120, 140, 160})
        void findsTheTempoFamily(double bpm) {
            OnsetEnvelope envelope = envelopeOf(SignalFactory.clickTrack(bpm, 20, RATE));

            double estimate = TempoEstimator.estimate(envelope).beatsPerMinute();

            // The estimator is a seed for the dynamic program, not the answer.
            // A perfectly periodic signal genuinely correlates at half and double
            // the beat rate, so landing an octave out is expected here and is
            // resolved by the tracker; what must never happen is landing on an
            // unrelated tempo.
            double ratio = estimate / bpm;
            assertThat(Math.min(Math.min(Math.abs(ratio - 1), Math.abs(ratio - 0.5)),
                    Math.abs(ratio - 2))).isLessThan(0.05);
            assertThat(TempoEstimator.estimate(envelope).strength()).isGreaterThan(0.1);
        }

        @Test
        @DisplayName("resolves the octave for a 120 BPM click track")
        void resistsOctaveErrorsAtTheCentre() {
            // At the centre of the perceptual prior the estimator should get the
            // octave right outright, without relying on the tracker to recover.
            OnsetEnvelope envelope = envelopeOf(SignalFactory.clickTrack(120, 20, RATE));

            double estimate = TempoEstimator.estimate(envelope).beatsPerMinute();

            assertThat(estimate).isCloseTo(120, within(6.0));
        }

        @Test
        @DisplayName("the prior is symmetric between halving and doubling")
        void priorIsSymmetricInLogSpace() {
            // Log-space symmetry is what stops the estimator systematically
            // favouring the faster or the slower alias.
            assertThat(TempoEstimator.perceptualWeight(60))
                    .isCloseTo(TempoEstimator.perceptualWeight(240), within(1e-9));
            assertThat(TempoEstimator.perceptualWeight(120)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("reports no confidence for silence instead of guessing")
        void silenceHasNoConfidence() {
            assertThat(TempoEstimator.estimate(envelopeOf(SignalFactory.silence(5, RATE))).strength())
                    .isZero();
        }
    }

    @Nested
    @DisplayName("beat tracking")
    class Beats {

        @ParameterizedTest(name = "tracks {0} BPM at the right spacing")
        @ValueSource(doubles = {90, 100, 120, 140, 160})
        void tracksEvenlySpacedBeats(double bpm) {
            BeatTracker.Result result = BeatTracker.track(
                    envelopeOf(SignalFactory.clickTrack(bpm, 20, RATE)));

            assertThat(result.beatTimes()).isNotEmpty();
            // Derived from the tracked beats, so it must agree with them closely.
            assertThat(result.beatsPerMinute()).isCloseTo(bpm, within(bpm * 0.02));

            // Spacing must match the period, not merely be self-consistent.
            List<Double> beats = result.beatTimes();
            double expectedGap = 60.0 / bpm;
            double totalError = 0;
            for (int i = 1; i < beats.size(); i++) {
                totalError += Math.abs((beats.get(i) - beats.get(i - 1)) - expectedGap);
            }
            assertThat(totalError / Math.max(1, beats.size() - 1)).isLessThan(expectedGap * 0.2);
        }

        @Test
        @DisplayName("beats land on the clicks, not between them")
        void beatsAlignWithClicks() {
            double bpm = 120;
            BeatTracker.Result result = BeatTracker.track(
                    envelopeOf(SignalFactory.clickTrack(bpm, 20, RATE)));

            // Clicks are at multiples of 0.5s. Measure each beat's distance to
            // the nearest one; a tracker locked to the wrong phase would sit
            // near 0.25s away rather than near zero.
            double worst = 0;
            for (double beat : result.beatTimes()) {
                double nearest = Math.round(beat / 0.5) * 0.5;
                worst = Math.max(worst, Math.abs(beat - nearest));
            }
            assertThat(worst).isLessThan(0.09);
        }

        @Test
        @DisplayName("produces no beats for silence")
        void silenceProducesNoBeats() {
            assertThat(BeatTracker.track(envelopeOf(SignalFactory.silence(5, RATE))).beatTimes())
                    .isEmpty();
        }

        @Test
        @DisplayName("covers the whole recording rather than stopping early")
        void coversTheRecording() {
            BeatTracker.Result result = BeatTracker.track(
                    envelopeOf(SignalFactory.clickTrack(120, 60, RATE)));

            List<Double> beats = result.beatTimes();
            assertThat(beats.get(0)).isLessThan(2.0);
            assertThat(beats.get(beats.size() - 1)).isGreaterThan(55.0);
            // 60s at 120 BPM is 120 beats; allow for windowing at the edges.
            assertThat(beats.size()).isBetween(100, 140);
        }

        @Test
        @DisplayName("builds a beat grid with a consistent downbeat phase")
        void buildsBeatGrid() {
            OnsetEnvelope envelope = envelopeOf(SignalFactory.clickTrack(120, 20, RATE));
            BeatTracker.Result result = BeatTracker.track(envelope);

            BeatGrid grid = BeatTracker.toBeatGrid(result, envelope, 4);

            assertThat(grid.beats()).hasSameSizeAs(result.beatTimes());
            assertThat(grid.downbeatTimes()).isNotEmpty();
            // Every fourth beat is a downbeat, and the model enforces that a
            // downbeat is position 0.
            assertThat(grid.beats().stream().filter(BeatGrid.Beat::downbeat).count())
                    .isCloseTo(grid.size() / 4L, within(2L));
            // Downbeat phase is a weaker claim than the beats themselves.
            assertThat(grid.downbeatConfidence().value())
                    .isLessThan(grid.beatConfidence().value() + 1e-9);
        }
    }
}
