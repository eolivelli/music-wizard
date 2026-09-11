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
import static org.assertj.core.api.Assertions.withinPercentage;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Both readings of a recording's onsets, by the route the pipeline takes. */
    private static OnsetEnvelope.Both bothOf(float[] samples) {
        return OnsetEnvelope.bothFromAudio(new AudioBuffer(samples, RATE));
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
        List<double[]> uniform = new ArrayList<>(times.size());
        for (double time : times) {
            uniform.add(new double[] {time, 0.8});
        }
        return clicksWithGains(uniform, seconds);
    }

    /**
     * The same clicks, each with its own gain, so a fixture can make an offbeat
     * louder than the beat it hangs off.
     *
     * @param timesAndGains one {@code {seconds, gain}} pair per click
     */
    private static float[] clicksWithGains(List<double[]> timesAndGains, double seconds) {
        float[] out = new float[(int) Math.round(seconds * RATE)];
        int clickLength = Math.max(1, RATE / 100);
        for (double[] click : timesAndGains) {
            int start = (int) Math.round(click[0] * RATE);
            for (int i = 0; i < clickLength && start + i < out.length; i++) {
                double decay = Math.exp(-8.0 * i / clickLength);
                out[start + i] +=
                        (float) (click[1] * decay * Math.sin(2 * Math.PI * 1000 * i / RATE));
            }
        }
        return out;
    }

    /**
     * A click track that states its metre, over a noise bed: every beat sounds,
     * but the offbeats sound more quietly.
     *
     * <p>The bed is what makes the accent reach the envelope. Onset strength is
     * a rise in decibels, so against digital silence a click at a fifth of the
     * gain rises very nearly as far as a loud one and the two are all but
     * indistinguishable — measured, an eight-to-one gain ratio moves the
     * envelope by under a quarter. Over a bed, a quiet click clears it by much
     * less than a loud one, which is the situation a real mix is always in.
     *
     * @param weakGain gain of the offbeat clicks; the downbeats are at 0.8
     * @param bedGain  amplitude of the uniform noise the clicks sit over
     */
    private static float[] accentedClicks(double beatsPerMinute, double seconds,
                                          double weakGain, double bedGain, long seed) {
        double period = 60.0 / beatsPerMinute;
        List<double[]> clicks = new ArrayList<>();
        int beat = 0;
        for (double t = 0; t < seconds; t += period, beat++) {
            clicks.add(new double[] {t, beat % 2 == 0 ? 0.8 : weakGain});
        }
        float[] out = clicksWithGains(clicks, seconds);
        Random random = new Random(seed);
        for (int i = 0; i < out.length; i++) {
            out[i] += (float) (bedGain * (2 * random.nextDouble() - 1));
        }
        return out;
    }

    /**
     * A shuffle whose loudest events are not on the beat: a quiet click on odd
     * beats, a loud one on the backbeat, and a swung eighth two thirds of the
     * way through every beat that is louder than the beat it follows.
     *
     * <p>This is the shape the real benchmarks have and the synthetic ones did
     * not. {@code SignalFactory.clickTrack} puts every event on a beat, so the
     * beat tracker's spacing penalty is never asked to overrule the onset
     * evidence and its weight cannot be measured from it — which is how the
     * weight came to be wrong by a factor of 48 with every tier-0 test green.
     */
    private static float[] swungClicks(double beatsPerMinute, double seconds) {
        double period = 60.0 / beatsPerMinute;
        List<double[]> clicks = new ArrayList<>();
        int beat = 0;
        for (double t = 0; t < seconds; t += period, beat++) {
            clicks.add(new double[] {t, beat % 2 == 1 ? 1.5 : 0.35});
            clicks.add(new double[] {t + 2 * period / 3, 0.9});
        }
        return clicksWithGains(clicks, seconds);
    }

    /**
     * A recording that sounds only every second beat until {@code introSeconds}
     * and every beat after it, at one unchanging tempo throughout.
     *
     * <p>The shape of a lead-in, and the fixture for #292: the music never
     * changes pace, but an analysis window falling inside the intro sees its
     * strongest periodicity at half the rate, and a window after it sees the
     * rate itself.
     */
    private static float[] sparseIntro(double beatsPerMinute, double introSeconds,
                                       double seconds) {
        double period = 60.0 / beatsPerMinute;
        List<double[]> clicks = new ArrayList<>();
        int beat = 0;
        for (double t = 0; t < seconds; t += period, beat++) {
            if (t < introSeconds && beat % 2 == 1) {
                continue;
            }
            clicks.add(new double[] {t, 0.8});
        }
        return clicksWithGains(clicks, seconds);
    }

    /**
     * The mirror of {@link #sparseIntro}: every beat until {@code denseSeconds},
     * every second beat after it. The tempo never changes here either — what
     * changes is which reading the analysis windows are a majority of.
     */
    private static float[] denseIntroThenSparse(double beatsPerMinute, double denseSeconds,
                                                double seconds) {
        double period = 60.0 / beatsPerMinute;
        List<double[]> clicks = new ArrayList<>();
        int beat = 0;
        for (double t = 0; t < seconds; t += period, beat++) {
            if (t >= denseSeconds && beat % 2 == 1) {
                continue;
            }
            clicks.add(new double[] {t, 0.8});
        }
        return clicksWithGains(clicks, seconds);
    }

    /**
     * The texture #509 is about: a kick on every quarter, a hi-hat on every
     * eighth, over a noise bed. Every eighth carries an onset, the quarters
     * carry more of one, and only the quarters are stated in the bass
     * register.
     *
     * <p>The kick is long and low enough to land in the register
     * {@link OnsetEnvelope#pulseRegister} reads and the hat far above it; the
     * bed is load-bearing twice over. It makes the accent reach the envelope
     * at all, for the reason {@link #accentedClicks} gives — and it gives the
     * bass register a floor, without which the hat's own attack, which is
     * broadband however high its tone, is the loudest thing down there
     * between the kicks. A real mix has that floor; digital silence does not,
     * and at a tenth of this bed the hat's leakage alone lifts the register on
     * the offbeats to a tenth of the kick's level.
     */
    private static float[] kickAndHat(double quartersPerMinute, double seconds, long seed) {
        double quarter = 60.0 / quartersPerMinute;
        float[] out = new float[(int) Math.round(seconds * RATE)];
        for (double t = 0; t < seconds; t += quarter / 2) {
            boolean onQuarter = Math.round(t / (quarter / 2)) % 2 == 0;
            if (onQuarter) {
                addBurst(out, t, 60, 0.10, 0.9);
            }
            addBurst(out, t, 6000, 0.01, 0.25);
        }
        Random random = new Random(seed);
        for (int i = 0; i < out.length; i++) {
            out[i] += (float) (0.02 * (2 * random.nextDouble() - 1));
        }
        return out;
    }

    /** A decaying sine burst, mixed in at a time. */
    private static void addBurst(float[] out, double atSeconds, double frequencyHz,
                                 double lengthSeconds, double gain) {
        int start = (int) Math.round(atSeconds * RATE);
        int length = (int) Math.round(lengthSeconds * RATE);
        for (int i = 0; i < length && start + i < out.length; i++) {
            double decay = Math.exp(-5.0 * i / length);
            out[start + i] +=
                    (float) (gain * decay * Math.sin(2 * Math.PI * frequencyHz * i / RATE));
        }
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
        @DisplayName("a whisper out of digital silence does not outscore the music above it")
        void silenceArtefactDoesNotOutscoreTheMusic() {
            // #306. A decibel scale is unbounded below, so before the band floor
            // was made relative the step from digital silence to an inaudible
            // sample was a bigger rise than any attack in the recording: on
            // eb7-vamp-130.mp3 the decay at the end produced the joint-largest
            // frame in the whole envelope, out of audio peaking near -90 dBFS.
            //
            // Clicks, a second of true silence, then a burst 100 dB down. The
            // burst is inaudible and the clicks are not, so the envelope must
            // rank them that way round.
            float[] clicks = SignalFactory.clickTrack(120, 20, RATE);
            float[] tail = SignalFactory.silence(1, RATE);
            float[] whisper = SignalFactory.sine(440, 0.05, RATE);
            float[] audio = new float[clicks.length + tail.length + whisper.length];
            System.arraycopy(clicks, 0, audio, 0, clicks.length);
            for (int i = 0; i < whisper.length; i++) {
                audio[clicks.length + tail.length + i] = (float) (whisper[i] * 1e-5);
            }

            OnsetEnvelope envelope = envelopeOf(audio);
            double loudestClick = 0;
            double loudestWhisper = 0;
            for (int frame = 0; frame < envelope.length(); frame++) {
                double at = envelope.timeOf(frame);
                if (at < 20) {
                    loudestClick = Math.max(loudestClick, envelope.strength()[frame]);
                } else if (at > 20.5) {
                    loudestWhisper = Math.max(loudestWhisper, envelope.strength()[frame]);
                }
            }

            // A quarter, because the fixed floor this replaced already put the
            // burst under a half: it reaches 0.43 of the loudest click there and
            // 0.17 here, so a looser bound would pass either way and pin
            // nothing.
            assertThat(loudestWhisper)
                    .as("the loudest frame the inaudible burst produces")
                    .isLessThan(0.25 * loudestClick);
        }

        @Test
        @DisplayName("the envelope is unchanged by the recording's gain")
        void theEnvelopeDoesNotDependOnGain() {
            // The floor is a share of the recording's loudest band rather than
            // an absolute magnitude, so turning the input down must not change
            // what the envelope says. A fixed floor fails this: quieter audio
            // sits closer to it and its onsets are compressed against it.
            double[] loud = envelopeOf(SignalFactory.clickTrack(120, 12, RATE)).strength();
            double[] quiet = envelopeOf(scaled(SignalFactory.clickTrack(120, 12, RATE), 1e-3))
                    .strength();

            assertThat(quiet).hasSameSizeAs(loud);
            double worst = 0;
            for (int i = 0; i < loud.length; i++) {
                worst = Math.max(worst, Math.abs(loud[i] - quiet[i]));
            }
            assertThat(worst).as("largest difference over the whole envelope").isLessThan(1e-6);
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

            // Two frames is the shortest run the filter does something to, and
            // the constant pair above cannot tell whether it did: asserted on a
            // pair that differs, which must be pulled together. This is the case
            // the run scanner's `length < 2` guard decides, and the differential
            // test below cannot check it -- its reference filters each run by
            // calling this same method, so an off-by-one in that guard moves
            // both sides together and survives.
            double[][] step = new double[2][40];
            step[1][0] = 10;
            OnsetEnvelope.antiAlias(step);
            assertThat(step[0][0]).isGreaterThan(0.5);
            assertThat(step[1][0]).isLessThan(9.5);
        }

        @Test
        @DisplayName("poisoned audio never reaches the filter, because the buffer refuses it")
        void poisonedAudioIsRefusedBeforeItCanReachTheFilter() {
            // This test replaces three that measured what the envelope does with
            // non-finite *audio* -- one bad sample, a hole, and a swept family of
            // long holes in smooth material. Since issue #61, AudioBuffer rejects
            // non-finite samples, so none of those inputs can be built and their
            // measurements are about a signal the pipeline cannot receive.
            //
            // What they were protecting is still protected, and by better-placed
            // tests: antiAlias's run scanning is pinned directly on hand-built
            // mel bands by the tests below, which is the layer the code actually
            // works at. What is gone is only the audio-level route to it. The
            // guard inside antiAlias is deliberately left in place -- see #76.
            //
            // The blast radius those tests recorded is worth keeping, because it
            // is why the guard sits where it does: one bad audio sample lands in
            // every FFT bin of the eight windows containing it, so it poisons all
            // forty bands at once. Skipping a poisoned *band* would therefore
            // have disabled the filter for the whole recording, restoring the bug
            // it fixes; a click track could not see that happen (0.865 filtered,
            // 0.818 unfiltered, both healthy) and only a held note could
            // (0.591 to 0.751, straight back over the click floor).
            for (float poison : new float[] {Float.NaN, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY}) {
                float[] clicks = SignalFactory.clickTrack(120, 20, RATE);
                clicks[100_000] = poison;
                assertThatThrownBy(() -> new AudioBuffer(clicks, RATE))
                        .as("single sample, %s", poison)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("samples[100000]");

                // And a hole rather than a single sample, which is the shape the
                // deleted tests swept over.
                float[] holed = SignalFactory.clickTrack(120, 20, RATE);
                for (int i = 10 * RATE; i < 10 * RATE + RATE / 2; i++) {
                    holed[i] = poison;
                }
                assertThatThrownBy(() -> new AudioBuffer(holed, RATE))
                        .as("half-second hole, %s", poison)
                        .isInstanceOf(IllegalArgumentException.class);
            }

            // A real dropout is digital silence, not NaN, and silence is
            // filtered like anything else: it still tracks 120 BPM.
            float[] silenced = SignalFactory.clickTrack(120, 20, RATE);
            for (int i = 10 * RATE; i < 10 * RATE + RATE / 2; i++) {
                silenced[i] = 0;
            }
            TempoEstimator.Estimate estimate = TempoEstimator.estimate(envelopeOf(silenced));
            assertThat(estimate.beatsPerMinute()).isCloseTo(120, within(1.0));
            assertThat(estimate.strength()).isGreaterThan(0.7);
        }

        @Test
        @DisplayName("a non-finite sample is left alone, not smeared and not skipped")
        void nonFiniteSampleIsLeftAlone() {
            double[][] bands = new double[100][40];
            for (int frame = 0; frame < bands.length; frame++) {
                bands[frame][0] = frame % 2 == 0 ? 1 : -1;
                bands[frame][1] = 5;
            }
            bands[50][0] = Double.NaN;
            // A run at the very start, which has no earlier frame at all.
            bands[0][1] = Double.NaN;
            bands[1][1] = Double.NEGATIVE_INFINITY;
            OnsetEnvelope.antiAlias(bands);

            // Band 0 is filtered on both sides of the poison, which is the
            // point: skipping the band would leave the ripple at its full
            // amplitude of 1, and that is how the filter was once disabled for
            // a whole recording by a single bad sample.
            for (int frame = 5; frame <= 45; frame++) {
                assertThat(Math.abs(bands[frame][0])).as("frame %d", frame).isLessThan(0.15);
            }
            for (int frame = 56; frame <= 95; frame++) {
                assertThat(Math.abs(bands[frame][0])).as("frame %d", frame).isLessThan(0.15);
            }
            // The poisoned frame comes back as it went in, so the flux loop
            // drops the differences across it rather than reading the run's
            // recovery as an onset.
            assertThat(bands[50][0]).isNaN();

            // Each side is filtered as a series in its own right, so neither
            // can be contaminated by the other. Checked by filtering the two
            // halves separately and requiring the same answer.
            double[][] before = new double[50][40];
            double[][] after = new double[49][40];
            for (int frame = 0; frame < 50; frame++) {
                before[frame][0] = frame % 2 == 0 ? 1 : -1;
            }
            for (int frame = 0; frame < 49; frame++) {
                after[frame][0] = (frame + 51) % 2 == 0 ? 1 : -1;
            }
            OnsetEnvelope.antiAlias(before);
            OnsetEnvelope.antiAlias(after);
            for (int frame = 0; frame < 50; frame++) {
                assertThat(bands[frame][0]).as("frame %d", frame)
                        .isCloseTo(before[frame][0], within(1e-12));
            }
            for (int frame = 0; frame < 49; frame++) {
                assertThat(bands[frame + 51][0]).as("frame %d", frame + 51)
                        .isCloseTo(after[frame][0], within(1e-12));
            }

            // A leading run has no earlier frame to filter with, so the run
            // simply starts after it. The frames themselves come back
            // non-finite, and what follows must be untouched by them: band 1 is
            // constant and has to come back constant rather than ringing from
            // an invented starting value.
            assertThat(bands[0][1]).isNaN();
            assertThat(bands[1][1]).isNegative().isInfinite();
            for (int frame = 2; frame < bands.length; frame++) {
                assertThat(bands[frame][1]).as("frame %d", frame).isCloseTo(5, within(1e-9));
            }
        }

        @Test
        @DisplayName("the run scanner decomposes any pattern of poison the same way")
        void runScanningMatchesAnExplicitDecomposition() {
            // The loop that finds maximal runs of finite frames is hand-rolled
            // and nested, and it is the fourth version of this code -- the three
            // before it were each worse than what they replaced. So rather than
            // pick cases, compare it against an explicit decomposition on random
            // patterns: filter each run standalone and require the same answer,
            // frame for frame.
            //
            // This checks the decomposition, not the filter arithmetic, since
            // both sides call the same filter. antiAliasIsZeroPhase and
            // antiAliasAttenuatesNearNyquistRipple cover the arithmetic.
            Random random = new Random(49);
            for (int trial = 0; trial < 500; trial++) {
                int frames = 2 + random.nextInt(30);
                double[] original = new double[frames];
                for (int frame = 0; frame < frames; frame++) {
                    original[frame] = random.nextDouble() < 0.25
                            ? switch (random.nextInt(3)) {
                                case 0 -> Double.NaN;
                                case 1 -> Double.POSITIVE_INFINITY;
                                default -> Double.NEGATIVE_INFINITY;
                            }
                            : random.nextGaussian() * 10;
                }

                double[][] actual = new double[frames][40];
                for (int frame = 0; frame < frames; frame++) {
                    actual[frame][0] = original[frame];
                }
                OnsetEnvelope.antiAlias(actual);

                double[] expected = expectedByRun(original);
                for (int frame = 0; frame < frames; frame++) {
                    // NaN has to be compared as NaN: assertThat(double) uses ==,
                    // under which NaN does not equal itself.
                    if (Double.isNaN(expected[frame])) {
                        assertThat(actual[frame][0])
                                .as("trial %d, frame %d of %d", trial, frame, frames)
                                .isNaN();
                    } else {
                        assertThat(actual[frame][0])
                                .as("trial %d, frame %d of %d", trial, frame, frames)
                                .isEqualTo(expected[frame]);
                    }
                }
                // A poisoned frame must come back bit for bit, and the bands
                // that were never written must stay untouched -- the scratch
                // array is reused across runs and across bands.
                for (int frame = 0; frame < frames; frame++) {
                    if (!Double.isFinite(original[frame])) {
                        assertThat(Double.doubleToRawLongBits(actual[frame][0]))
                                .isEqualTo(Double.doubleToRawLongBits(original[frame]));
                    }
                    for (int band = 1; band < 40; band++) {
                        assertThat(actual[frame][band]).isZero();
                    }
                }
            }
        }

        /** Each maximal run of finite frames, filtered on its own. */
        private double[] expectedByRun(double[] original) {
            double[] expected = original.clone();
            int frame = 0;
            while (frame < original.length) {
                while (frame < original.length && !Double.isFinite(original[frame])) {
                    frame++;
                }
                int start = frame;
                while (frame < original.length && Double.isFinite(original[frame])) {
                    frame++;
                }
                int length = frame - start;
                if (length >= 2) {
                    double[][] solo = new double[length][40];
                    for (int i = 0; i < length; i++) {
                        solo[i][0] = original[start + i];
                    }
                    OnsetEnvelope.antiAlias(solo);
                    for (int i = 0; i < length; i++) {
                        expected[start + i] = solo[i][0];
                    }
                }
            }
            return expected;
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
            // stand out. A 120 BPM click track peaks at about 7.7 standard
            // deviations and the weakest tempo in 60..200 at 5.9; a sustained
            // sine reaches 2.2. Those were 10.1 and 2.7 before the band
            // low-pass of issue #49, which lowers every peak by roughly a
            // quarter -- it widens each attack by about half a frame, so the
            // same energy is spread over more of them. The ratio, which is what
            // this test is about, is barely touched.
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

        @Test
        @DisplayName("the rivals the sweep weighed are recorded, the range's ends included")
        void theSweepRecordsWhatItWeighed() {
            // A click track at the fastest rate the sweep considers, where the
            // prior takes the half. The sweep stops at that rate, so a peak
            // rising into it has no right-hand neighbour -- and a candidate
            // list that dropped it for that would hide the double of the rate
            // the prior chose, which is the rival a reader most often
            // disagrees with. The slowest rate is at the other end of the same
            // sweep and is admitted on the same terms.
            OnsetEnvelope envelope =
                    envelopeOf(SignalFactory.clickTrack(240, 20, RATE));

            TempoEstimator.Estimate estimate = TempoEstimator.estimate(envelope);

            assertThat(estimate.candidates())
                    .filteredOn(TempoEstimator.Candidate::chosen)
                    .singleElement()
                    .extracting(TempoEstimator.Candidate::beatsPerMinute)
                    .isEqualTo(estimate.beatsPerMinute());
            assertThat(estimate.candidates())
                    .extracting(TempoEstimator.Candidate::beatsPerMinute)
                    .contains(240.0);
            assertThat(estimate.candidates())
                    .isSortedAccordingTo(
                            Comparator.comparingDouble(TempoEstimator.Candidate::score)
                                    .reversed())
                    .allSatisfy(candidate -> assertThat(candidate.score()).isPositive());
        }

        @Test
        @DisplayName("a reading with nothing to choose between records no rivals")
        void silenceRecordsNoCandidates() {
            assertThat(TempoEstimator.estimate(envelopeOf(SignalFactory.silence(5, RATE)))
                    .candidates()).isEmpty();
        }

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

        @ParameterizedTest(name = "an accented {0} BPM click track is not read at half of it")
        @ValueSource(doubles = {120, 140})
        void accentAloneDoesNotHalveTheTempo(double bpm) {
            // Metre is accent, and autocorrelation reads accent as evidence for
            // the half: the beat's own lag pairs every loud click with a quiet
            // one, the half's lag pairs like with like. Uncorrected this fixture
            // reads exactly 60 and 70 BPM (#349).
            OnsetEnvelope envelope =
                    envelopeOf(accentedClicks(bpm, 20, 0.2, 0.02, 20_260_727L));

            double estimate = TempoEstimator.estimate(envelope).beatsPerMinute();

            assertThat(estimate).isCloseTo(bpm, withinPercentage(3));
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
        @DisplayName("a louder offbeat does not buy itself a beat")
        void aLouderOffbeatDoesNotBuyItselfABeat() {
            // The mechanism behind #196, at a scale small enough to assert on.
            // The spacing penalty is what stops the dynamic program leaving the
            // grid for a loud event between two beats, and while it was written
            // in log base 2 at a weight of 1 it was one forty-eighth of the
            // published one -- an extra beat cost two units against an offbeat
            // worth several, so the tracker took the detour and came back.
            //
            // Measured on this fixture: at the old weight 67 of the 100
            // intervals are a beat long and 17 are the two-thirds detour; at
            // the published one, 96 of 99 and one. The bounds sit between those
            // two populations rather than beside either.
            double bpm = 100;
            double period = 60.0 / bpm;
            BeatTracker.Result result = BeatTracker.track(envelopeOf(swungClicks(bpm, 60)));

            List<Double> beats = result.beatTimes();
            int onGrid = 0;
            int detours = 0;
            for (int i = 1; i < beats.size(); i++) {
                double ratio = (beats.get(i) - beats.get(i - 1)) / period;
                if (Math.abs(ratio - 1) < 0.10) {
                    onGrid++;
                } else if (Math.abs(ratio - 2.0 / 3) < 0.10) {
                    detours++;
                }
            }

            assertThat(onGrid)
                    .as("intervals within a tenth of one beat, of %d", beats.size() - 1)
                    .isGreaterThan(90);
            assertThat(detours)
                    .as("intervals that are the swung eighth's two thirds of a beat")
                    .isLessThan(5);
        }

        @ParameterizedTest(name = "the dynamic program follows a seed an octave out at {0} BPM")
        @ValueSource(doubles = {90, 120, 160})
        void theDynamicProgramFollowsItsSeedRatherThanFixingIt(double bpm) {
            // A limitation, pinned deliberately, because it is the cost of the
            // spacing weight being what Ellis published rather than a
            // forty-eighth of it, and because the comment on tempoOf used to
            // claim the opposite.
            //
            // Under the old weight a seed at *half* the true rate was
            // overridden by the clicks -- 45 gave back 89.9 -- and a seed at
            // double it was not. The penalties are symmetric, so that asymmetry
            // is not in the search window: correcting upward adds beats that
            // each collect an onset, and correcting downward removes beats that
            // sat between onsets and cost almost nothing to keep. See
            // BeatTracker.tempoOf, which has the arithmetic. Now neither is
            // corrected. Resolving the octave is TempoEstimator's job, and this
            // asserts that BeatTracker will not paper over it.
            //
            // On the fixture rather than through track(), because track() would
            // supply a correct seed and the point is what happens when the seed
            // is wrong.
            OnsetEnvelope envelope = envelopeOf(SignalFactory.clickTrack(bpm, 20, RATE));

            for (double factor : new double[] {0.5, 2.0}) {
                List<Double> beats =
                        BeatTracker.trackFixedTempo(envelope, bpm * factor, 0, envelope.length());
                assertThat(beats).hasSizeGreaterThan(4);

                double[] intervals = new double[beats.size() - 1];
                for (int i = 0; i < intervals.length; i++) {
                    intervals[i] = beats.get(i + 1) - beats.get(i);
                }
                java.util.Arrays.sort(intervals);
                double tracked = 60.0 / intervals[intervals.length / 2];

                assertThat(tracked)
                        .as("tracked rate from a seed at %s of the true one", factor)
                        .isCloseTo(bpm * factor, within(bpm * factor * 0.03));
            }
        }

        @Test
        @DisplayName("a sparse intro is tracked at the music's rate, not at half of it")
        void aSparseIntroIsTrackedAtTheMusicsRate() {
            // #292. The estimator seeds each window on its own, and a window of
            // intro that sounds only every second beat has its strongest
            // periodicity at half the music's rate. The dynamic program will
            // not correct that seed -- see
            // theDynamicProgramFollowsItsSeedRatherThanFixingIt, which pins the
            // limitation this compensates for -- so the intro used to be
            // tracked at half rate for its whole length.
            //
            // On g-blues-shuffle-cc.mp3 that cost eleven pulses and dragged the
            // end-to-end rate about two percent under the loop's own, because
            // the mean interval is the end-to-end rate and the intro's doubled
            // gaps are in it.
            double bpm = 105;
            double period = 60.0 / bpm;
            double introSeconds = 14;
            BeatTracker.Result result =
                    BeatTracker.track(envelopeOf(sparseIntro(bpm, introSeconds, 70)));

            List<Double> beats = result.beatTimes();
            int doubled = 0;
            int onGrid = 0;
            for (int i = 1; i < beats.size(); i++) {
                if (beats.get(i) > introSeconds) {
                    break;
                }
                double ratio = (beats.get(i) - beats.get(i - 1)) / period;
                if (Math.abs(ratio - 1) < 0.15) {
                    onGrid++;
                } else if (Math.abs(ratio - 2) < 0.30) {
                    doubled++;
                }
            }

            assertThat(onGrid)
                    .as("intro intervals of about one beat")
                    .isGreaterThan(15);
            assertThat(doubled)
                    .as("intro intervals of about two beats, which is the defect")
                    .isLessThan(3);
        }

        @Test
        @DisplayName("the reference follows the majority even where the majority is wrong")
        void theReferenceFollowsTheMajorityEvenWhereTheMajorityIsWrong() {
            // A limitation, pinned deliberately, the way
            // theDynamicProgramFollowsItsSeedRatherThanFixingIt pins the one it
            // compensates for. Invert the fixture above -- dense first, sparse
            // for the rest -- so the windows reading half the rate are the
            // majority, and the correction runs backwards: the opening, which
            // has an onset on every beat, is pulled onto the subdivision. #305.
            //
            // Measured over the dense opening rather than the whole recording,
            // and that is the point of the test rather than a detail of it. Two
            // thirds of this fixture is sparse, so the global median interval is
            // two beats whichever algorithm ran -- an assertion on it passes
            // against the code this branch replaced and pins nothing. Only the
            // dense region tells the two apart: 22 one-beat intervals and 6
            // two-beat before, none and 17 after.
            double bpm = 105;
            double period = 60.0 / bpm;
            double denseSeconds = 20;
            BeatTracker.Result result =
                    BeatTracker.track(envelopeOf(denseIntroThenSparse(bpm, denseSeconds, 60)));

            List<Double> beats = result.beatTimes();
            assertThat(beats).hasSizeGreaterThan(8);
            int onGrid = 0;
            int doubled = 0;
            for (int i = 1; i < beats.size(); i++) {
                if (beats.get(i) > denseSeconds) {
                    break;
                }
                double ratio = (beats.get(i) - beats.get(i - 1)) / period;
                if (Math.abs(ratio - 1) < 0.15) {
                    onGrid++;
                } else if (Math.abs(ratio - 2) < 0.30) {
                    doubled++;
                }
            }

            assertThat(doubled)
                    .as("intervals of two beats across the densely played opening")
                    .isGreaterThan(10);
            assertThat(onGrid)
                    .as("intervals of one beat there, which is what is lost")
                    .isLessThan(3);
        }

        @Test
        @DisplayName("a window's seed is corrected by a subdivision, never by an octave alone")
        void aSeedIsCorrectedBySubdivisionRatherThanByOctave() {
            // The three corrections the corpus actually needs, and the reason
            // the ratios are a table rather than the powers of two. Folding by
            // octaves fixes the first and leaves the other two on a rate that is
            // no whole subdivision of the pulse -- three quarters of it on the
            // 6/8 recording, four thirds of it on the vamp. Both are nearer the
            // pulse than the seed they replace, which is why "nearer" is not the
            // test: neither can bar the recording.

            // The inputs below are the corpus measurements that motivated the
            // table, taken before #231 gave the estimator the harmonic rhythm;
            // divideOutSubdivision is a pure function, so they pin its
            // arithmetic whatever the estimator now feeds it. Since #231 the
            // fm7 windows read 110 outright and cm-blues splits about evenly
            // between 189.00 and 63.25 -- the mechanism these pin is
            // unchanged, the corpus sightings have moved.
            //
            // g-blues-shuffle-cc.mp3 window 0 as it then read: a half.
            assertThat(BeatTracker.divideOutSubdivision(52.5, 105.5))
                    .isCloseTo(105.0, within(0.01));
            // cm-blues-68-95.mp3, in 6/8: windows at three times the pulse.
            assertThat(BeatTracker.divideOutSubdivision(191.25, 63.5))
                    .isCloseTo(63.75, within(0.01));
            // fm7-vamp-110.mp3: windows at two thirds of it.
            assertThat(BeatTracker.divideOutSubdivision(73.25, 110.0))
                    .isCloseTo(109.875, within(0.01));

            // A tempo that is genuinely different is not a subdivision of
            // anything and is left alone, which is what the per-window seed is
            // for: following a recording that changes pace.
            assertThat(BeatTracker.divideOutSubdivision(121.0, 105.5))
                    .isCloseTo(121.0, within(1e-9));
            assertThat(BeatTracker.divideOutSubdivision(88.0, 105.5))
                    .isCloseTo(88.0, within(1e-9));

            // A correction that would leave the estimator's own range is not
            // made, since no window could have been seeded outside it.
            assertThat(BeatTracker.divideOutSubdivision(50.0, 25.0))
                    .isCloseTo(50.0, within(1e-9));
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

    @Nested
    @DisplayName("the marked pulse")
    class Marked {

        @ParameterizedTest(name = "a hat on every eighth does not take the beat off the"
                + " marked quarter, bed seed {0}")
        @ValueSource(longs = {3, 11})
        void theMarkedQuarterOutranksTheHatsEighth(long seed) {
            // #509. Every eighth carries an onset, so the summed envelope is
            // periodic at both levels; the quarters are louder, and levelling
            // the accents -- which is what stops a recording arguing for its
            // own half -- is what takes that difference out again, whereupon
            // the prior takes the faster of the two. Only the register
            // distinguishes them, because only the quarters are stated in it.
            //
            // Two seeds because the bed is noise and the reading is a ratio of
            // levels within it. Swept over eight seeds at 58 and 60 quarters a
            // minute -- the tempi at which this fixture presents the ambiguity
            // at all, since at 63 the tracker reads the quarters unaided on
            // most seeds -- the parity at the doubled rate stays between 0.011
            // and 0.030 against a gate of 0.10. So the margin is the
            // fixture's rather than one draw's, and the assertions below are
            // on the reading rather than only on the rate, so a front-end
            // change that erodes it says so instead of flipping a BPM.
            double quarters = 60;
            OnsetEnvelope.Both onsets = bothOf(kickAndHat(quarters, 60, seed));
            OnsetEnvelope envelope = onsets.envelope();
            OnsetEnvelope register = onsets.pulseRegister();

            BeatTracker.Result withoutRegister = BeatTracker.track(envelope);
            BeatTracker.Result withRegister =
                    BeatTracker.track(envelope, HarmonicRhythm.none(), register);

            assertThat(withoutRegister.beatsPerMinute())
                    .as("the eighths, which is what the envelope and the prior settle on")
                    .isCloseTo(2 * quarters, withinPercentage(5));
            assertThat(withRegister.beatsPerMinute())
                    .as("the quarters, which are the beats the register states")
                    .isCloseTo(quarters, withinPercentage(5));

            MarkedPulse.Reading reading =
                    MarkedPulse.read(envelope, register, withoutRegister.beatsPerMinute());
            assertThat(reading.parity())
                    .as("the offbeat eighths against the quarters, in the register")
                    .isLessThan(0.05);
            assertThat(reading.contrast())
                    .as("the register on the tracked beats against between them")
                    .isGreaterThan(20);
        }

        @Test
        @DisplayName("a register articulating some other grid is not read as evidence")
        void aRegisterThatDoesNotStateTheGridDecidesNothing() {
            // The gate that keeps this off the recordings whose bass plays
            // every second beat of a correct grid, or plays across it: the
            // silences only mean something where the register is otherwise
            // loudest on the beats. Here it strikes every second beat and
            // every midpoint, so it states a grid, but not this one.
            double frameRate = 100;
            int frames = 3000;
            double rate = 120;
            double period = frameRate * 60.0 / rate;
            OnsetEnvelope envelope = accentedImpulses(frameRate, frames, period);
            List<int[]> whole = List.of(new int[] {0, frames});

            OnsetEnvelope everySecondBeat = impulses(frameRate, frames, 2 * period, 0, 1);
            OnsetEnvelope alsoBetween = merged(everySecondBeat,
                    impulses(frameRate, frames, period, period / 2, 1));

            assertThat(MarkedPulse.resolveOctave(rate, envelope, everySecondBeat, whole))
                    .as("the register states these beats and every second one is silent")
                    .isEqualTo(rate / 2);
            assertThat(MarkedPulse.resolveOctave(rate, envelope, alsoBetween, whole))
                    .as("the same silences, but the register is no quieter between the beats")
                    .isEqualTo(rate);
        }

        @Test
        @DisplayName("an envelope that ranks its own rate above the half keeps it")
        void theRegisterDoesNotOverturnAnEnvelopeThatDidNotAskIt() {
            // The register restores a preference the envelope had; it does not
            // invent one. On a grid whose beats are all equally loud the
            // envelope ranks the grid above its half, so however sparse the
            // bass part is -- here it plays every second beat, the strongest
            // reading the two gates have -- the octave is left where the
            // envelope and the prior put it.
            double frameRate = 100;
            int frames = 3000;
            double rate = 120;
            double period = frameRate * 60.0 / rate;
            OnsetEnvelope unaccented = impulses(frameRate, frames, period, 0, 1);

            assertThat(MarkedPulse.resolveOctave(rate, unaccented,
                    impulses(frameRate, frames, 2 * period, 0, 1),
                    List.of(new int[] {0, frames})))
                    .isEqualTo(rate);
        }

        @Test
        @DisplayName("a register that is silence with thumps in it states nothing")
        void aFewFramesCannotCarryTheReading() {
            // A mean is a poor summary of a half that is mostly silence: a
            // handful of loud frames make the same mean as a bass playing on
            // every beat, and the silence between the beats then reads as the
            // strongest contrast there is. Eight thumps in three thousand
            // frames is a quarter of this grid's louder half, which is why the
            // share is measured against that half's own level rather than
            // against zero -- the register is zero-mean, so about a quarter of
            // arbitrary frames are positive.
            double frameRate = 100;
            int frames = 3000;
            double rate = 120;
            double period = frameRate * 60.0 / rate;
            OnsetEnvelope envelope = accentedImpulses(frameRate, frames, period);
            List<int[]> whole = List.of(new int[] {0, frames});

            // Eight thumps four beats apart cover a quarter of this grid's
            // louder half; a bass playing once a bar over the whole of it
            // covers all of them, which is the case the test below pins.
            for (int thumps : new int[] {1, 8}) {
                double[] register = new double[frames];
                for (int i = 0; i < thumps; i++) {
                    register[(int) Math.round(i * 4 * period)] = 5;
                }
                // A floor, because a register of digital silence is the one
                // case real audio never presents and the guard must not need.
                for (int i = 0; i < frames; i++) {
                    register[i] += 0.001;
                }
                assertThat(MarkedPulse.resolveOctave(rate, envelope,
                        new OnsetEnvelope(register, frameRate), whole))
                        .as("%d thumps in %d frames", thumps, frames)
                        .isEqualTo(rate);
            }
        }

        @Test
        @DisplayName("a bass playing once a bar under an accented grid is halved wrongly")
        void aSparseBassPartIsIndistinguishableFromASubdivision() {
            // The limitation, pinned rather than claimed away, as
            // theReferenceFollowsTheMajorityEvenWhereTheMajorityIsWrong pins
            // the one it sits beside. A two-way interleave reads a register
            // striking every fourth beat of a correct grid exactly as it reads
            // one striking every second beat of a doubled one, and the
            // envelope agrees with it here because the grid is accented. No
            // recording in the corpus is this bare in the bass register --
            // tools/TempoOctave.java prints what they read -- and if one
            // arrives, this is the test that says what it will do.
            double frameRate = 100;
            int frames = 3000;
            double rate = 120;
            double period = frameRate * 60.0 / rate;
            OnsetEnvelope envelope = accentedImpulses(frameRate, frames, period);

            assertThat(MarkedPulse.resolveOctave(rate, envelope,
                    impulses(frameRate, frames, 4 * period, 0, 1),
                    List.of(new int[] {0, frames})))
                    .isEqualTo(rate / 2);
        }

        @Test
        @DisplayName("a window the register goes quiet in still votes on what the envelope ranked")
        void aRefusedWindowKeepsItsVoteOnTheEnvelope() {
            // The order the two questions are asked in, which nothing else
            // here reaches: the register is read window by window, and a
            // window where the bass drops out -- an intro, a breakdown -- has
            // still ranked the two rates on the envelope. Asking the register
            // first and letting its refusal swallow that vote would be a claim
            // about the envelope the window never made.
            double frameRate = 100;
            int window = 3000;
            double rate = 120;
            double period = frameRate * 60.0 / rate;
            List<int[]> windows = List.of(new int[] {0, window},
                    new int[] {window, 2 * window}, new int[] {2 * window, 3 * window});

            // Accented in the first and last window and level in the middle
            // one, so the envelope ranks the half above the rate in two of the
            // three -- a majority only if the last one is counted.
            double[] strength = new double[3 * window];
            int beat = 0;
            for (double at = 0; at < strength.length; at += period, beat++) {
                boolean level = at >= window && at < 2 * window;
                strength[(int) Math.round(at)] = level || beat % 2 == 0 ? 1 : 0.35;
            }
            OnsetEnvelope envelope = new OnsetEnvelope(strength, frameRate);

            // The register states every second beat, and goes all but silent
            // for the last window.
            double[] bass = new double[3 * window];
            for (double at = 0; at < 2 * window; at += 2 * period) {
                bass[(int) Math.round(at)] = 1;
            }
            bass[2 * window] = 1;
            OnsetEnvelope register = new OnsetEnvelope(bass, frameRate);

            MarkedPulse.Reading reading = MarkedPulse.read(envelope, register, rate, windows);
            assertThat(reading.windowsRefused())
                    .as("the window the register went quiet in")
                    .isEqualTo(1);
            assertThat(reading.envelopePrefersHalf())
                    .as("two of the three windows rank the half above the rate")
                    .isTrue();
            assertThat(MarkedPulse.resolveOctave(rate, envelope, register, windows))
                    .isEqualTo(rate / 2);
        }

        @Test
        @DisplayName("a clip shorter than a window is read over the whole of it")
        void aShortClipIsReadOverItsWholeSpan() {
            // One tempo is assumed over a clip this short, so the span it was
            // decided over is the clip itself. The instrument has to answer
            // about the same span as the decision, or it reports an abstention
            // on a recording the shipped code halves.
            double frameRate = 100;
            int frames = 800;
            double rate = 120;
            double period = frameRate * 60.0 / rate;
            OnsetEnvelope envelope = accentedImpulses(frameRate, frames, period);
            OnsetEnvelope everySecondBeat = impulses(frameRate, frames, 2 * period, 0, 1);

            assertThat(MarkedPulse.resolveOctave(rate, envelope, everySecondBeat,
                    BeatTracker.votingWindows(envelope)))
                    .isEqualTo(rate / 2);
            assertThat(MarkedPulse.read(envelope, everySecondBeat, rate)
                    .callsForHalving())
                    .as("the public reading, over the same windows")
                    .isTrue();
        }

        @Test
        @DisplayName("a halving that would leave the estimator's range is not made")
        void theCorrectionStaysInsideTheEstimatorsRange() {
            // A rate below MIN_TEMPO is one no window could have been seeded
            // with, which is the same bound divideOutSubdivision keeps.
            double frameRate = 100;
            int frames = 3000;
            double rate = 1.5 * TempoEstimator.MIN_TEMPO;
            double period = frameRate * 60.0 / rate;
            OnsetEnvelope envelope = accentedImpulses(frameRate, frames, period);
            OnsetEnvelope everySecondBeat = impulses(frameRate, frames, 2 * period, 0, 1);

            assertThat(MarkedPulse.resolveOctave(rate, envelope, everySecondBeat,
                    List.of(new int[] {0, frames})))
                    .isEqualTo(rate);
        }

        /**
         * An envelope whose beats alternate loud and quiet, which is what
         * makes its own autocorrelation rank the half above the beat -- the
         * situation the register exists to settle.
         */
        private OnsetEnvelope accentedImpulses(double frameRate, int frames, double period) {
            double[] strength = new double[frames];
            int beat = 0;
            for (double at = 0; at < frames; at += period, beat++) {
                strength[(int) Math.round(at)] = beat % 2 == 0 ? 1 : 0.35;
            }
            return new OnsetEnvelope(strength, frameRate);
        }

        /** An envelope that is {@code gain} every {@code period} frames and zero elsewhere. */
        private OnsetEnvelope impulses(double frameRate, int frames, double period,
                                       double offset, double gain) {
            double[] strength = new double[frames];
            for (double at = offset; at < frames; at += period) {
                strength[(int) Math.round(at)] = gain;
            }
            return new OnsetEnvelope(strength, frameRate);
        }

        private OnsetEnvelope merged(OnsetEnvelope first, OnsetEnvelope second) {
            double[] strength = first.strength().clone();
            for (int i = 0; i < strength.length; i++) {
                strength[i] = Math.max(strength[i], second.strength()[i]);
            }
            return new OnsetEnvelope(strength, first.frameRate());
        }
    }
}
