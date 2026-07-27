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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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

    /**
     * Clicks at intervals drawn uniformly from 0.12 s to 1.12 s: onsets as sharp
     * as a metronome's with no tempo behind them.
     *
     * <p>Seeded rather than random. A battery that passes on one draw and fails
     * on the next is not a regression gate, and the point of these fixtures is
     * to be comparable between runs.
     */
    private static float[] arrhythmicClicks(double seconds, long seed) {
        Random random = new Random(seed);
        List<Double> times = new ArrayList<>();
        for (double t = 0; t < seconds; t += 0.12 + random.nextDouble()) {
            times.add(t);
        }
        return clicksAt(times, seconds);
    }

    /** Clicks with the same shape as {@link SignalFactory#clickTrack}, at given times. */
    private static float[] clicksAt(List<Double> times, double seconds) {
        float[] out = new float[(int) Math.round(seconds * RATE)];
        int clickLength = Math.max(1, RATE / 100);
        for (double time : times) {
            int start = (int) Math.round(time * RATE);
            for (int i = 0; i < clickLength && start + i < out.length; i++) {
                double decay = Math.exp(-8.0 * i / clickLength);
                out[start + i] += (float) (0.8 * decay * Math.sin(2 * Math.PI * 1000 * i / RATE));
            }
        }
        return out;
    }

    private static float[] whiteNoise(double seconds, long seed) {
        Random random = new Random(seed);
        float[] out = new float[(int) Math.round(seconds * RATE)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) (0.3 * random.nextGaussian());
        }
        return out;
    }

    /** A tone that swells from quiet to loud: smooth, but not stationary. */
    private static float[] crescendo(double frequencyHz, double seconds) {
        float[] out = new float[(int) Math.round(seconds * RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            out[i] = (float) (0.5 * (0.2 + 0.8 * t / seconds)
                    * Math.sin(2 * Math.PI * frequencyHz * t));
        }
        return out;
    }

    private static float[] scaled(float[] samples, double gain) {
        float[] out = samples.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] *= (float) gain;
        }
        return out;
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
            // This is the property Estimate.peakiness turns into a number; the
            // assertions on that live in TempoConfidence.
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

    /**
     * Confidence has to rank rhythmic material above everything else, which is
     * more than "the two cases in the bug report come out the right way round".
     * These run a battery — clicks across the tempo range, smooth tones, noise,
     * sharp but arrhythmic onsets, silence — because each of the two components
     * is separately fooled by one of them, and only the product survives all
     * five.
     */
    @Nested
    @DisplayName("tempo confidence")
    class TempoConfidence {

        private static final double SECONDS = 20;
        private static final long SEED = 20_260_727L;

        private static double strengthOf(float[] samples) {
            return TempoEstimator.estimate(envelopeOf(samples)).strength();
        }

        @ParameterizedTest(name = "a {0} BPM click track reads as confidently rhythmic")
        @ValueSource(doubles = {60, 80, 100, 120, 140, 160, 180, 200})
        void clickTracksScoreHigh(double bpm) {
            TempoEstimator.Estimate estimate =
                    TempoEstimator.estimate(envelopeOf(SignalFactory.clickTrack(bpm, SECONDS, RATE)));

            // Measured 0.63 (at 60 BPM) to 0.84 across this range. The bound is
            // set below the whole measured band rather than snug against its
            // floor, so that a real regression trips it and ordinary drift does
            // not.
            assertThat(estimate.strength()).isGreaterThan(0.5);
            assertThat(estimate.peakiness()).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("a sustained tone no longer out-scores a click track")
        void sustainedToneNoLongerBeatsClicks() {
            TempoEstimator.Estimate tone =
                    TempoEstimator.estimate(envelopeOf(SignalFactory.sine(440, SECONDS, RATE)));
            TempoEstimator.Estimate clicks =
                    TempoEstimator.estimate(envelopeOf(SignalFactory.clickTrack(120, SECONDS, RATE)));

            // The inversion the issue reported is still there in the periodicity
            // component and always will be: a smooth envelope really is
            // self-similar at every lag, so 0.96 for the tone against 0.85 for
            // the clicks is an honest reading of periodicity. What was wrong was
            // calling that confidence.
            assertThat(tone.periodicity()).isGreaterThan(clicks.periodicity());

            // Peakiness is what breaks the tie, and it is not close.
            assertThat(tone.peakiness()).isLessThan(0.05);
            assertThat(clicks.peakiness()).isGreaterThan(0.9);
            assertThat(tone.strength()).isLessThan(clicks.strength() / 20);
        }

        @Test
        @DisplayName("a swelling tone is smooth too, and scores no better")
        void crescendoScoresLow() {
            // A crescendo is the case a periodicity-only measure could plausibly
            // have got right by accident, since it is not stationary. It does
            // not: only the envelope's shape matters, and that is still a wash.
            assertThat(strengthOf(crescendo(440, SECONDS))).isLessThan(0.05);
        }

        @Test
        @DisplayName("sharp onsets with no tempo behind them score low")
        void arrhythmicClicksScoreLow() {
            TempoEstimator.Estimate estimate =
                    TempoEstimator.estimate(envelopeOf(arrhythmicClicks(SECONDS, SEED)));

            // The mirror image of the sustained tone, and the reason peakiness
            // cannot be the whole answer either: these onsets are every bit as
            // impulsive as a metronome's, and there is no tempo there at all.
            assertThat(estimate.peakiness()).isGreaterThan(0.9);
            assertThat(estimate.periodicity()).isLessThan(0.2);
            assertThat(estimate.strength()).isLessThan(0.2);
        }

        @Test
        @DisplayName("white noise scores low on both counts")
        void whiteNoiseScoresLow() {
            assertThat(strengthOf(whiteNoise(SECONDS, SEED))).isLessThan(0.15);
        }

        @Test
        @DisplayName("silence scores zero on every component")
        void silenceScoresZero() {
            TempoEstimator.Estimate estimate =
                    TempoEstimator.estimate(envelopeOf(SignalFactory.silence(SECONDS, RATE)));

            assertThat(estimate.periodicity()).isZero();
            assertThat(estimate.peakiness()).isZero();
            assertThat(estimate.strength()).isZero();
        }

        @Test
        @DisplayName("every rhythmic signal outranks every non-rhythmic one")
        void rhythmicMaterialOutranksTheRest() {
            // The claim worth locking down is the ordering, not any single
            // number: a threshold placed anywhere in the gap must classify all
            // of these correctly.
            double worstRhythmic = Double.MAX_VALUE;
            for (double bpm : new double[] {60, 90, 120, 150, 180}) {
                worstRhythmic = Math.min(worstRhythmic,
                        strengthOf(SignalFactory.clickTrack(bpm, SECONDS, RATE)));
            }
            worstRhythmic = Math.min(worstRhythmic, strengthOf(SignalFactory.clickTrackWithChords(
                    120, new double[][] {SignalFactory.majorTriad(60), SignalFactory.majorTriad(67)},
                    4, SECONDS, RATE)));

            double bestNonRhythmic = Math.max(Math.max(
                            strengthOf(SignalFactory.sine(440, SECONDS, RATE)),
                            strengthOf(crescendo(440, SECONDS))),
                    Math.max(strengthOf(whiteNoise(SECONDS, SEED)),
                            strengthOf(arrhythmicClicks(SECONDS, SEED))));

            // Measured: 0.63 against 0.09, a factor of seven. Asserting three
            // leaves room for the sampling spread in the seeded fixtures without
            // letting the two classes touch.
            assertThat(worstRhythmic).isGreaterThan(3 * bestNonRhythmic);
        }

        @Test
        @DisplayName("confidence does not depend on how loud the recording is")
        void confidenceIsLevelIndependent() {
            // Both components are ratios over an envelope that is already
            // normalised, so a quiet mix must not read as less rhythmic than a
            // loud one. Worth pinning: gating on confidence would otherwise
            // penalise quiet recordings for being quiet.
            float[] clicks = SignalFactory.clickTrack(120, SECONDS, RATE);

            assertThat(strengthOf(scaled(clicks, 0.01)))
                    .isCloseTo(strengthOf(clicks), within(0.02));
        }

        @Test
        @DisplayName("peakiness reads as the duty cycle it claims to measure")
        void peakinessMatchesDutyCycle() {
            // Pinned on constructed arrays rather than on audio, so a change in
            // the onset front end cannot quietly move the arithmetic. An impulse
            // train on for one frame in fifty has kurtosis 48.0 by the closed
            // form for a two-valued signal, hence 1 - 3/48.0.
            double[] impulses = new double[5_000];
            for (int i = 0; i < impulses.length; i += 50) {
                impulses[i] = 1;
            }
            assertThat(TempoEstimator.peakiness(impulses)).isCloseTo(1 - 3.0 / 48.0, within(0.01));

            // A constant signal has no events to be sharp, and a sinusoid is
            // flatter than noise rather than peakier -- kurtosis 1.5 -- so both
            // must floor at zero instead of going negative.
            double[] constant = new double[100];
            java.util.Arrays.fill(constant, 5.0);
            assertThat(TempoEstimator.peakiness(constant)).isZero();

            double[] sinusoid = new double[1_000];
            for (int i = 0; i < sinusoid.length; i++) {
                sinusoid[i] = Math.sin(2 * Math.PI * i / 37.0);
            }
            assertThat(TempoEstimator.peakiness(sinusoid)).isZero();

            assertThat(TempoEstimator.peakiness(new double[0])).isZero();
            assertThat(TempoEstimator.peakiness(new double[] {1})).isZero();
        }

        @Test
        @DisplayName("windowed estimates are measured against the window, not the recording")
        void windowedPeakinessUsesTheWindowsOwnMean() {
            // The envelope is normalised over the whole recording, so a slice of
            // it is not mean-zero. Computing the moments about the recording's
            // mean instead of the slice's would make a window's peakiness depend
            // on what surrounds it; a click-track window must read as rhythmic
            // whether it is the first window or the last.
            OnsetEnvelope envelope = envelopeOf(SignalFactory.clickTrack(120, 60, RATE));
            int windowFrames = envelope.frameOf(15);

            TempoEstimator.Estimate first =
                    TempoEstimator.estimateWindow(envelope, 0, windowFrames);
            TempoEstimator.Estimate last = TempoEstimator.estimateWindow(
                    envelope, envelope.length() - windowFrames, envelope.length());

            assertThat(first.strength()).isGreaterThan(0.5);
            assertThat(last.strength()).isGreaterThan(0.5);
            assertThat(first.peakiness()).isCloseTo(last.peakiness(), within(0.05));
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
