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

    /** A held note with vibrato: {@code cents} of frequency sweep at {@code rateHz}. */
    private static float[] vibrato(double frequencyHz, double cents, double rateHz,
                                   double seconds) {
        float[] out = new float[(int) Math.round(seconds * RATE)];
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double swept = frequencyHz
                    * Math.pow(2, (cents / 1200.0) * Math.sin(2 * Math.PI * rateHz * t));
            phase += 2 * Math.PI * swept / RATE;
            out[i] = (float) (0.5 * Math.sin(phase));
        }
        return out;
    }

    /** A held note with harmonics: constant amplitude, no attack, no modulation. */
    private static float[] heldNote(double fundamentalHz, int harmonics, double seconds) {
        float[] out = new float[(int) Math.round(seconds * RATE)];
        for (int harmonic = 1; harmonic <= harmonics; harmonic++) {
            for (int i = 0; i < out.length; i++) {
                out[i] += (float) (0.4 / harmonic
                        * Math.sin(2 * Math.PI * fundamentalHz * harmonic * i / RATE));
            }
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
        @DisplayName("the band low-pass is zero phase, so it cannot move an onset")
        void antiAliasIsZeroPhase() {
            // This filter sits between the spectrogram and the first
            // difference, so any group delay it has is a delay on every onset
            // time the tracker reads. Asserted on an impulse, whose filtered
            // response must come out symmetric about the frame it arrived in.
            // A one-directional filter -- the obvious way to write this, and
            // the cheaper one -- puts the entire response after the impulse:
            // 0.000 one frame before against 0.285 one frame after, so this
            // fails on the first comparison.
            //
            // Narrower than it sounds, and worth saying so: symmetry is all it
            // checks, and a filter that does nothing at all is perfectly
            // symmetric. It is antiAliasAttenuatesNearNyquistRipple below that
            // says the filter filters; this one says it does not shift.
            double[][] bands = new double[201][40];
            bands[100][0] = 1;
            OnsetEnvelope.antiAlias(bands);

            assertThat(bands[100][0]).isEqualTo(maximumOf(bands, 0));
            for (int offset = 1; offset <= 20; offset++) {
                assertThat(bands[100 - offset][0])
                        .as("frame %d against %d", 100 - offset, 100 + offset)
                        .isCloseTo(bands[100 + offset][0], within(1e-12));
            }
        }

        @Test
        @DisplayName("the band low-pass attenuates the ripple it exists to remove")
        void antiAliasAttenuatesNearNyquistRipple() {
            // Frame-alternating ripple is the Nyquist of this signal, and it is
            // where the partial beating lands once it has folded. Two
            // forward-backward passes leave 0.118 of it, 18.6 dB down; one pass
            // leaves 0.343, 9.3 dB down, which separates the populations by
            // 0.015 instead of 0.089. The bound is set between the two so that
            // dropping a pass fails here rather than surviving in the swept
            // test above on a margin too thin to mean anything.
            double[][] ripple = new double[400][40];
            for (int frame = 0; frame < ripple.length; frame++) {
                ripple[frame][0] = frame % 2 == 0 ? 1 : -1;
            }
            OnsetEnvelope.antiAlias(ripple);

            // Away from the edges, where the filter has settled.
            double worst = 0;
            for (int frame = 50; frame < 350; frame++) {
                worst = Math.max(worst, Math.abs(ripple[frame][0]));
            }
            assertThat(worst).isLessThan(0.15);

            // And it must leave a constant level exactly alone: a filter that
            // shifted the level would shift every band's decibels and so every
            // difference taken from them.
            double[][] flat = new double[200][40];
            for (double[] frame : flat) {
                java.util.Arrays.fill(frame, -12.5);
            }
            OnsetEnvelope.antiAlias(flat);
            for (double[] frame : flat) {
                assertThat(frame[0]).isCloseTo(-12.5, within(1e-9));
            }
        }

        @Test
        @DisplayName("the band low-pass copes with envelopes too short to filter")
        void antiAliasHandlesDegenerateInput() {
            // compute() returns early below two frames, but the filter is
            // reachable on its own and an IIR pass over an empty or
            // single-element series is exactly where an off-by-one lives.
            OnsetEnvelope.antiAlias(new double[0][]);
            double[][] single = new double[1][40];
            single[0][0] = 7;
            OnsetEnvelope.antiAlias(single);
            assertThat(single[0][0]).isEqualTo(7);

            double[][] pair = new double[2][40];
            pair[0][0] = 1;
            pair[1][0] = 1;
            OnsetEnvelope.antiAlias(pair);
            assertThat(pair[0][0]).isCloseTo(1, within(1e-9));
            assertThat(pair[1][0]).isCloseTo(1, within(1e-9));
        }

        @Test
        @DisplayName("one non-finite sample costs its own frames and not the filter")
        void oneBadSampleStaysLocal() {
            // A recursive filter smears one poisoned value across the whole
            // series, and the flux loop drops a non-finite rise silently
            // because `rise > 0` is false for NaN. Together those turn eight
            // damaged frames into a flat envelope for the entire recording:
            // measured, a 120 BPM click track goes from 0.865 to 0.000.
            //
            // The held note is the assertion that matters and the click track
            // is not, which is the opposite of how this test was first
            // written. One bad audio sample poisons every FFT bin of the eight
            // windows containing it, so it reaches all forty bands at once --
            // meaning the tempting fix, skipping a band that cannot be
            // filtered, silently disables the filter for the whole recording
            // and restores the bug. A click track cannot see that happen: it
            // reads 0.865 filtered and 0.818 unfiltered, both of them healthy.
            // A held note goes from 0.591 to 0.751 and straight back over the
            // click floor, so it can.
            for (float poison : new float[] {Float.NaN, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY}) {
                float[] clicks = SignalFactory.clickTrack(120, 20, RATE);
                clicks[100_000] = poison;
                TempoEstimator.Estimate estimate = TempoEstimator.estimate(envelopeOf(clicks));

                assertThat(estimate.beatsPerMinute()).as("%s", poison).isCloseTo(120, within(1.0));
                assertThat(estimate.strength()).as("%s", poison).isGreaterThan(0.7);

                float[] held = heldNote(440, 8, 20);
                held[100_000] = poison;
                assertThat(TempoEstimator.estimate(envelopeOf(held)).strength())
                        .as("the filter still runs, %s", poison)
                        .isLessThan(0.65);
            }
        }

        @Test
        @DisplayName("a non-finite sample is held over, not smeared and not skipped")
        void nonFiniteSampleIsHeldOver() {
            double[][] bands = new double[100][40];
            for (int frame = 0; frame < bands.length; frame++) {
                bands[frame][0] = frame % 2 == 0 ? 1 : -1;
                bands[frame][1] = 5;
            }
            bands[50][0] = Double.NaN;
            // A run at the very start, which has no previous sample to hold.
            bands[0][1] = Double.NaN;
            bands[1][1] = Double.NEGATIVE_INFINITY;
            OnsetEnvelope.antiAlias(bands);

            // Band 0 still gets filtered despite carrying the poison -- which
            // is the whole point, since skipping it would leave the ripple at
            // its full amplitude of 1.
            for (int frame = 5; frame <= 40; frame++) {
                assertThat(Math.abs(bands[frame][0])).as("frame %d", frame).isLessThan(0.15);
            }
            for (int frame = 62; frame <= 95; frame++) {
                assertThat(Math.abs(bands[frame][0])).as("frame %d", frame).isLessThan(0.15);
            }
            // Holding a value across the poisoned frame breaks the alternation
            // there, so the filter leaves a local excursion -- 0.67 at its
            // worst, against band decibels whose attacks are tens of dB, so it
            // cannot manufacture an onset. It stays local and it stays
            // symmetric, which is what says the filter is still zero phase
            // across a defect rather than only across clean data.
            assertThat(bands[49][0]).isCloseTo(bands[51][0], within(1e-12));
            assertThat(bands[48][0]).isCloseTo(bands[52][0], within(1e-12));
            assertThat(Math.abs(bands[50][0])).isLessThan(1.0);
            // And a leading run takes the first finite value rather than zero,
            // which would have been a 5 dB step and so a manufactured onset.
            assertThat(bands[0][1]).isCloseTo(5, within(1e-9));
            assertThat(bands[1][1]).isCloseTo(5, within(1e-9));
        }

        @Test
        @DisplayName("a band that is never finite is left alone rather than throwing")
        void entirelyNonFiniteBandIsLeftAlone() {
            double[][] bands = new double[50][40];
            for (int frame = 0; frame < bands.length; frame++) {
                bands[frame][0] = Double.NaN;
                bands[frame][1] = 3;
            }
            OnsetEnvelope.antiAlias(bands);

            assertThat(bands[25][0]).isNaN();
            assertThat(bands[25][1]).isCloseTo(3, within(1e-9));
        }

        private double maximumOf(double[][] bands, int band) {
            double peak = Double.NEGATIVE_INFINITY;
            for (double[] frame : bands) {
                peak = Math.max(peak, frame[band]);
            }
            return peak;
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

        // 196 is the floor and 136 the ceiling, found by sweeping every integer
        // tempo from 60 to 200 offline. Before the band low-pass of issue #49
        // the spread was 0.526 to 0.931 and was not monotone -- 105 scored 0.59
        // against 110's 0.90 -- because the trough came from frame-grid jitter
        // rather than from anything about the tempo. It is now 0.751 to 0.909
        // and much flatter, which is the same effect seen from the other side:
        // the ripple the filter removes was what made the troughs. 78 and 105,
        // the old troughs, are kept in the list so a regression that brings
        // them back is caught where it was found.
        @ParameterizedTest(name = "a {0} BPM click track reads as confidently rhythmic")
        @ValueSource(doubles = {60, 78, 100, 105, 120, 136, 160, 180, 196, 200})
        void clickTracksScoreHigh(double bpm) {
            TempoEstimator.Estimate estimate =
                    TempoEstimator.estimate(envelopeOf(SignalFactory.clickTrack(bpm, SECONDS, RATE)));

            // The bound sits about 10% under the swept floor of 0.751. It was
            // 0.45 before, against a floor of 0.526.
            assertThat(estimate.strength()).isGreaterThan(0.68);
            // Peakiness fell because low-passing the band decibels widens each
            // attack by about half a frame. The cost is uniform rather than
            // confined to the fast end -- 0.983 to 0.960 at 60 BPM, 0.965 to
            // 0.918 at 120, 0.940 to 0.862 at 200 -- but it only threatens a
            // bound at the fast end, where the gap between attacks is 52 frames
            // rather than 172.
            //
            // This is the tightest bound in the file and the one to look at
            // first if it ever fails: the swept minimum over 60 to 200 is
            // 0.8621, at 199 BPM, so there is 1.4% of headroom against 9.4% for
            // the strength bound above. It is deterministic, so tight is not
            // flaky -- but anything that widens attacks further will land here
            // before it lands anywhere else, and that is the warning this bound
            // is for rather than a number to relax.
            assertThat(estimate.peakiness()).isGreaterThan(0.85);
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
        @DisplayName("no held harmonic note out-scores any click tempo")
        void heldHarmonicNotesDoNotOutScoreClicks() {
            // Issue #49, and the claim the band low-pass exists to support. It
            // has to be a sweep rather than a fixture, because the failure it
            // replaces scattered violently between neighbouring inputs: on main
            // a held 440 Hz note scored 0.57 with six partials and 0.75 with
            // eight. Any single fixture is a coincidence of its own
            // frequencies, and the issue's first headline number was 6.5 times
            // optimistic for exactly that reason.
            //
            // Sixty held notes -- ten fundamentals from C2 to C6, six harmonic
            // counts -- against ten click tempi including 196, the floor of the
            // full 60-to-200 sweep. Constant amplitude, no attack, no decay, no
            // vibrato, no tremolo: nothing about any of them is rhythmic.
            //
            // Swept offline over the full grid the issue specifies, 170 held
            // notes against all 141 integer tempi: the worst held note scored
            // 0.751 on main and out-scored 72 tempi; it scores 0.662 now and
            // out-scores none, and no held note anywhere on the grid out-scores
            // any tempo. This is the affordable sixtieth of that, and it lands
            // on the same worst point.
            double worstHeld = 0;
            for (int midi : new int[] {36, 39, 45, 48, 57, 60, 63, 69, 72, 84}) {
                for (int harmonics : new int[] {1, 2, 3, 6, 8, 10}) {
                    worstHeld = Math.max(worstHeld, strengthOf(
                            heldNote(SignalFactory.midiToHz(midi), harmonics, SECONDS)));
                }
            }
            double weakestClick = Double.MAX_VALUE;
            for (double bpm : new double[] {60, 66, 78, 90, 105, 120, 136, 160, 196, 200}) {
                weakestClick = Math.min(weakestClick,
                        strengthOf(SignalFactory.clickTrack(bpm, SECONDS, RATE)));
            }

            // 0.662 against 0.751. Asserted as an ordering rather than as two
            // thresholds, so that a change which lifts both stays honest -- but
            // with the margin pinned too, because the ordering alone survives a
            // regression that shrinks 0.089 to 0.001, and a separation that
            // narrow would not be one. 0.05 is a little over half of what the
            // filter currently delivers; one forward-backward pass instead of
            // two gives 0.015 and trips it.
            assertThat(worstHeld).isLessThan(weakestClick);
            assertThat(weakestClick - worstHeld).isGreaterThan(0.05);
            // And the old worst point specifically, which is what the issue and
            // the strength() javadoc both quote.
            assertThat(strengthOf(heldNote(440, 8, SECONDS))).isLessThan(0.65);
        }

        @Test
        @DisplayName("a pure sine's score depends on its pitch, so 440 Hz proves nothing alone")
        void pureSineScoreDependsOnPitch() {
            // crescendoScoresLow and sustainedToneNoLongerBeatsClicks both use
            // 440 Hz, where the score is 0.004. That is not representative, and
            // asserting only there would let the javadoc keep claiming that
            // featureless material collapses.
            double at440 = strengthOf(SignalFactory.sine(440, SECONDS, RATE));
            double at87 = strengthOf(SignalFactory.sine(87.31, SECONDS, RATE));

            assertThat(at440).isLessThan(0.05);
            // Two orders of magnitude apart, from nothing but pitch.
            assertThat(at87).isGreaterThan(0.3);
            assertThat(at87).isGreaterThan(50 * at440);

            // And a crescendo at the same pitch tracks it, so "not stationary"
            // is not what rescues the 440 Hz case either.
            assertThat(strengthOf(crescendo(87.31, SECONDS))).isCloseTo(at87, within(0.05));
        }

        @Test
        @DisplayName("material outside the search range scores zero, like silence")
        void tooSlowOrTooShortScoresZero() {
            // Documented because it is a trap for anyone gating on a low
            // threshold: these are not weak readings, they are no reading at
            // all, and they are indistinguishable from silence.
            TempoEstimator.Estimate slow =
                    TempoEstimator.estimate(envelopeOf(SignalFactory.clickTrack(30, SECONDS, RATE)));

            assertThat(slow.peakiness()).isGreaterThan(0.9);   // the onsets are there
            assertThat(slow.periodicity()).isZero();           // 30 BPM is below MIN_TEMPO
            assertThat(slow.strength()).isZero();              // and so this says "nothing"

            // Likewise a clip too short to hold several periods: 0.011, which
            // is a hundredth of what the same click track reads over twenty
            // seconds. This used to be asserted against a 440 Hz sine, which
            // now reads 0.000 exactly and so cannot bound anything from above.
            assertThat(strengthOf(SignalFactory.clickTrack(120, 1, RATE)))
                    .isLessThan(0.05)
                    .isLessThan(strengthOf(SignalFactory.clickTrack(120, SECONDS, RATE)) / 20);

            // Only the bottom of the range does this. Above MAX_TEMPO the
            // estimator finds a subharmonic instead of giving up, so a 300 BPM
            // metronome reads high rather than zero -- the asymmetry is worth
            // pinning because "outside the search range reads as nothing" is the
            // natural and wrong assumption.
            assertThat(strengthOf(SignalFactory.clickTrack(300, SECONDS, RATE)))
                    .isGreaterThan(0.5);
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
            // Includes 78 and 105, the two troughs of the tempo sweep; the
            // round tempi alone would have understated the worst case by 0.10.
            for (double bpm : new double[] {60, 78, 90, 105, 120, 150, 180}) {
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

            // Measured with the seed below: 0.53 against 0.06, a factor of
            // nine. Asserting three rather than nine because the seeded
            // fixtures do vary -- swept over 200 seeds the arrhythmic clicks
            // reach 0.12, which still leaves a factor of 4.5, so the bound
            // holds for draws this test will never see.
            //
            // The claim is about these four fixtures and no wider. A modulated
            // sustained tone would land inside the gap and is deliberately not
            // in the set; modulatedToneIsNotSeparated covers that, and says so.
            assertThat(worstRhythmic).isGreaterThan(3 * bestNonRhythmic);
        }

        @Test
        @DisplayName("a modulated sustained tone is still NOT separated, and this pins how close")
        void modulatedToneIsNotSeparated() {
            // A documented limitation rather than a passing grade, and it
            // survives the band low-pass of issue #49 even though the
            // unmodulated held notes do not. A held note that is genuinely
            // modulated at a few hertz has real energy in the tempo band; no
            // filter can remove that, because it is the signal.
            //
            // What the filter did change is which vibrato is worst. The fixture
            // this test used to pin -- 440 Hz, 50 cents, 2 Hz -- fell from 0.61
            // to 0.37, comfortably under every click tempo, and pinning that
            // one alone would now claim a separation that does not exist. Swept
            // over 252 points instead (pitch A3 to C6, 20 to 100 cents, 1 to
            // 7 Hz), the worst vibrato scores 0.745 against a click floor of
            // 0.751: an ordering, but by 0.006, which is not a separation any
            // threshold could use. Unmodulated held notes clear the same floor
            // by 0.089.
            //
            // Two candidate fixes were measured and both refuted, so this is not
            // a matter of trying harder: counting how many mel bands rise
            // together does not separate them (vibrato lifts 32 of 40 against a
            // click's 40, because frequency-modulating a tone drags its whole
            // leakage skirt), and a SuperFlux-style maximum-filtered reference
            // frame makes it worse. Issue #43 carries both measurements and
            // stays open.
            double worstVibrato = strengthOf(vibrato(SignalFactory.midiToHz(84), 20, 1.0, SECONDS));
            double weakestClick = strengthOf(SignalFactory.clickTrack(196, SECONDS, RATE));
            double worstHeldNote = strengthOf(heldNote(SignalFactory.midiToHz(39), 3, SECONDS));

            // The limitation itself: vibrato reaches the click floor, and the
            // day it stops doing so this test fails and #43 gets revisited
            // rather than quietly outliving its own fix.
            assertThat(worstVibrato).isCloseTo(weakestClick, within(0.05));
            // And it is materially worse than an unmodulated held note, which
            // is what says the remaining problem is modulation rather than the
            // sampling artefact #49 removed.
            assertThat(worstVibrato).isGreaterThan(worstHeldNote);

            // The old fixtures, kept because they show which way each moved.
            assertThat(strengthOf(vibrato(440, 50, 2.0, SECONDS))).isLessThan(weakestClick);
            TempoEstimator.Estimate fast =
                    TempoEstimator.estimate(envelopeOf(vibrato(440, 50, 7.0, SECONDS)));
            assertThat(fast.strength()).isLessThan(weakestClick);
            // 7 Hz is 420 modulations per minute; the reported tempo is the
            // vibrato rate divided down, not a beat anyone could tap.
            assertThat(fast.beatsPerMinute()).isCloseTo(140, within(5.0));
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
        @DisplayName("peakiness depends on the signal's shape, not its offset or its level")
        void peakinessIsInvariantUnderOffsetAndScale() {
            // Both properties matter because estimateWindow passes a *slice* of
            // an envelope normalised over the whole recording: a window is
            // neither mean-zero nor unit-variance, so measuring it about the
            // recording's mean, or guarding on an absolute variance, would make
            // a window's answer depend on what surrounds it.
            //
            // Pinned on arrays rather than on audio deliberately. The same claim
            // asserted over two windows of a click track cannot fail -- measured,
            // the difference between taking the moments about the window's own
            // mean and about the recording's is 0.00002, against any tolerance
            // loose enough to write -- so that test would have kept passing after
            // the property was lost.
            double[] impulses = new double[5_000];
            for (int i = 0; i < impulses.length; i += 50) {
                impulses[i] = 1;
            }
            double reference = TempoEstimator.peakiness(impulses);
            assertThat(reference).isGreaterThan(0.9);

            assertThat(TempoEstimator.peakiness(offsetBy(impulses, 7.5)))
                    .isCloseTo(reference, within(1e-9));
            assertThat(TempoEstimator.peakiness(offsetBy(impulses, -1e6)))
                    .isCloseTo(reference, within(1e-9));
            assertThat(TempoEstimator.peakiness(multipliedBy(impulses, 1e-9)))
                    .isCloseTo(reference, within(1e-9));
            assertThat(TempoEstimator.peakiness(multipliedBy(impulses, 1e9)))
                    .isCloseTo(reference, within(1e-9));
        }

        @Test
        @DisplayName("an envelope carrying a non-finite sample reports no evidence")
        void nonFiniteEnvelopeIsRejectedQuietly() {
            // OnsetEnvelope's constructor is public and validates only the frame
            // rate, so a hand-built envelope can carry a NaN or an infinity.
            // Neither can come from fromAudio, but the failure mode if one did
            // was an IllegalArgumentException from Estimate's own validation
            // blaming peakiness for a malformed input -- the least informative
            // place for it to surface.
            for (double poison : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY}) {
                double[] values = new double[64];
                for (int i = 0; i < values.length; i += 8) {
                    values[i] = 1;
                }
                values[13] = poison;
                OnsetEnvelope envelope = new OnsetEnvelope(values, 172.0);

                assertThat(TempoEstimator.peakiness(values)).isZero();
                assertThat(TempoEstimator.estimate(envelope).strength()).isZero();
                assertThat(BeatTracker.track(envelope).confidence().value()).isZero();
            }
        }

        @Test
        @DisplayName("an envelope of finite but enormous samples reports no evidence too")
        void overflowingEnvelopeIsRejectedQuietly() {
            // These are the cases the finiteness checks exist for, and every one
            // of them has only finite samples -- which is exactly why the test
            // above does not reach them. The overflow happens inside the
            // arithmetic rather than arriving in the input, and it happens at
            // two different layers that need separate guards.
            //
            // Both of the first two saturate the running sum, so the mean and
            // then `largest` go infinite: the second is the same failure with
            // the opposite sign, not a different one. Neither reaches the
            // deviation arithmetic.
            double[] hugeMeanPositive = new double[64];
            java.util.Arrays.fill(hugeMeanPositive, 1e308);

            double[] hugeMeanNegative = new double[64];
            java.util.Arrays.fill(hugeMeanNegative, -1.7e308);
            hugeMeanNegative[13] = 1.7e308;

            // This one is different in kind: its mean and moments are perfectly
            // well behaved, and it fails a layer later, in the autocorrelation,
            // which squares the envelope.
            double[] hugeImpulses = new double[64];
            for (int i = 0; i < hugeImpulses.length; i += 8) {
                hugeImpulses[i] = 1e200;
            }

            for (double[] values : List.of(hugeMeanPositive, hugeMeanNegative, hugeImpulses)) {
                OnsetEnvelope envelope = new OnsetEnvelope(values, 172.0);

                assertThat(TempoEstimator.estimate(envelope).strength()).isZero();
                assertThat(BeatTracker.track(envelope).confidence().value()).isZero();
            }

            assertThat(TempoEstimator.peakiness(hugeMeanPositive)).isZero();
            assertThat(TempoEstimator.peakiness(hugeMeanNegative)).isZero();
            assertThat(TempoEstimator.peakiness(hugeImpulses)).isGreaterThan(0.4);
            assertThat(TempoEstimator.estimate(new OnsetEnvelope(hugeImpulses, 172.0))
                    .periodicity()).isZero();
        }

        @Test
        @DisplayName("merely enormous input still gets a real answer, not a rejection")
        void overflowGuardsDoNotFireOnLargeButUsableInput() {
            // The other half of the previous test, and the one that stops these
            // guards being tightened into a bug. Rejecting absurd input is only
            // right if input that is merely large still reads correctly: both
            // components are ratios, so scaling up must not move the answer
            // until the arithmetic actually overflows. Only upward -- scaling
            // *down* past 1e-9 does change it, because OnsetEnvelope.isFlat
            // uses an absolute threshold and estimate() short-circuits on it.
            // That is pre-existing and unreachable through fromAudio, but it is
            // why this test claims nothing about the small end.
            //
            // 1e150 is the largest round decade that survives the
            // autocorrelation, which squares.
            double[] enormous = new double[64];
            double[] ordinary = new double[64];
            for (int i = 0; i < enormous.length; i += 8) {
                enormous[i] = 1e150;
                ordinary[i] = 1;
            }

            TempoEstimator.Estimate large =
                    TempoEstimator.estimate(new OnsetEnvelope(enormous, 172.0));
            TempoEstimator.Estimate small =
                    TempoEstimator.estimate(new OnsetEnvelope(ordinary, 172.0));

            assertThat(large.strength()).isGreaterThan(0.1);
            assertThat(large.strength()).isCloseTo(small.strength(), within(1e-12));
            assertThat(large.peakiness()).isCloseTo(small.peakiness(), within(1e-12));
            assertThat(large.periodicity()).isCloseTo(small.periodicity(), within(1e-12));
        }

        @Test
        @DisplayName("windowed estimates of a click track stay rhythmic wherever the window sits")
        void windowedEstimatesStayRhythmic() {
            // A plain end-to-end guard on estimateWindow, making no claim about
            // which mean the moments are taken about -- that is
            // peakinessIsInvariantUnderOffsetAndScale's job.
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

        private static double[] offsetBy(double[] values, double offset) {
            double[] out = values.clone();
            for (int i = 0; i < out.length; i++) {
                out[i] += offset;
            }
            return out;
        }

        private static double[] multipliedBy(double[] values, double factor) {
            double[] out = values.clone();
            for (int i = 0; i < out.length; i++) {
                out[i] *= factor;
            }
            return out;
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
