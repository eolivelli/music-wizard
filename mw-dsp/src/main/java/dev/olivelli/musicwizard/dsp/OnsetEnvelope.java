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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.Spectrogram;
import java.util.Objects;

/**
 * The onset strength envelope: one number per frame saying how much the spectrum
 * just changed.
 *
 * <p>This is the input to tempo estimation and beat tracking, and its quality
 * bounds theirs. The construction follows Ellis (2007): mel-band magnitudes in
 * decibels, first difference, half-wave rectified, summed across bands, then
 * high-passed and normalised.
 *
 * <p>Each step earns its place. Working in decibels makes a quiet passage
 * contribute as much as a loud one, because perceived accent tracks relative
 * rather than absolute change. Half-wave rectification keeps only increases in
 * energy, since a note ending is not an onset. Mel bands rather than raw FFT
 * bins stop a single strong partial from dominating. Subtracting a moving
 * average removes the slow swell of an arrangement, leaving the sharp local
 * changes that mark note attacks.
 *
 * @param strength   one value per frame, mean-zero and unit-variance
 * @param frameRate  frames per second
 */
public record OnsetEnvelope(double[] strength, double frameRate) {

    /**
     * Window and hop used for onset analysis, deliberately different from the
     * spectrogram used for harmony.
     *
     * <p>The hop has to be small. A beat period is not a whole number of frames,
     * so at a coarse hop successive beats land at different sub-frame positions
     * and the resulting amplitude ripple is itself periodic -- at half the beat
     * rate. That is a real periodicity in the envelope, not noise, and it defeats
     * the perceptual prior outright: measured here, a 43 fps envelope of a clean
     * 120 BPM click track estimates 60 BPM, while 86 fps and above estimate 120.
     * Ellis works at roughly 250 fps for the same reason.
     */
    public static final int ONSET_WINDOW = 1024;

    /** About 172 frames per second at the analysis rate, or 5.8 ms per frame. */
    public static final int ONSET_HOP = 128;

    private static final int MEL_BANDS = 40;
    private static final double MIN_HZ = 30;
    private static final double MAX_HZ = 8_000;

    public OnsetEnvelope {
        Objects.requireNonNull(strength, "strength");
        if (!(frameRate > 0)) {
            throw new IllegalArgumentException("frameRate must be positive, got: " + frameRate);
        }
    }

    /**
     * Computes the envelope straight from audio, at the resolution onset
     * analysis needs. Prefer this over building the spectrogram yourself; the
     * harmony spectrogram's hop is far too coarse for rhythm.
     */
    public static OnsetEnvelope fromAudio(AudioBuffer audio) {
        Objects.requireNonNull(audio, "audio");
        return compute(Spectrogram.compute(audio, ONSET_WINDOW, ONSET_HOP));
    }

    /** Computes the envelope from a spectrogram. */
    public static OnsetEnvelope compute(Spectrogram spectrogram) {
        Objects.requireNonNull(spectrogram, "spectrogram");
        int frames = spectrogram.frameCount();
        if (frames < 2) {
            return new OnsetEnvelope(new double[0], spectrogram.frameRate());
        }

        double[][] melBands = toMelDecibels(spectrogram);

        // First difference, half-wave rectified, summed across bands. Only
        // increases count: a note ending is not an onset.
        double[] flux = new double[frames];
        for (int frame = 1; frame < frames; frame++) {
            double sum = 0;
            for (int band = 0; band < MEL_BANDS; band++) {
                double rise = melBands[frame][band] - melBands[frame - 1][band];
                if (rise > 0) {
                    sum += rise;
                }
            }
            flux[frame] = sum;
        }
        flux[0] = flux.length > 1 ? flux[1] : 0;

        subtractMovingAverage(flux, (int) Math.round(spectrogram.frameRate()));
        normalise(flux);
        return new OnsetEnvelope(flux, spectrogram.frameRate());
    }

    /**
     * Maps FFT bins onto mel bands and converts to decibels.
     *
     * <p>Mel spacing matches how pitch resolution actually works: closely spaced
     * at low frequencies, coarse at high ones. Summing raw FFT bins instead
     * would let one loud high partial swamp the low-frequency evidence that
     * carries most rhythmic information.
     */
    private static double[][] toMelDecibels(Spectrogram spectrogram) {
        int frames = spectrogram.frameCount();
        int bins = spectrogram.binCount();

        double minMel = hzToMel(MIN_HZ);
        double maxMel = hzToMel(Math.min(MAX_HZ, spectrogram.sampleRate() / 2.0));
        int[] edges = new int[MEL_BANDS + 2];
        for (int i = 0; i < edges.length; i++) {
            double mel = minMel + (maxMel - minMel) * i / (edges.length - 1);
            edges[i] = Math.min(bins - 1, spectrogram.binOf(melToHz(mel)));
        }

        double[][] out = new double[frames][MEL_BANDS];
        for (int frame = 0; frame < frames; frame++) {
            float[] magnitudes = spectrogram.magnitudes()[frame];
            for (int band = 0; band < MEL_BANDS; band++) {
                int from = edges[band];
                int to = Math.max(edges[band + 2], from + 1);
                double sum = 0;
                for (int bin = from; bin < to && bin < bins; bin++) {
                    sum += magnitudes[bin];
                }
                // Floored before the logarithm so silence maps to a finite value
                // rather than negative infinity.
                out[frame][band] = 20 * Math.log10(Math.max(sum, 1e-10));
            }
        }
        return out;
    }

    /**
     * Removes the slow component by subtracting a moving average, keeping only
     * the rectified remainder.
     *
     * <p>Without this a crescendo reads as one long onset, and a dense
     * arrangement produces a high plateau that buries the individual attacks.
     */
    private static void subtractMovingAverage(double[] signal, int windowFrames) {
        int half = Math.max(1, windowFrames / 2);

        // Prefix sums, so each window average is O(1) and the whole pass is
        // linear rather than quadratic in the window size.
        double[] prefix = new double[signal.length + 1];
        for (int i = 0; i < signal.length; i++) {
            prefix[i + 1] = prefix[i] + signal[i];
        }

        double[] smoothed = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            int from = Math.max(0, i - half);
            int to = Math.min(signal.length, i + half + 1);
            smoothed[i] = (prefix[to] - prefix[from]) / (to - from);
        }
        for (int i = 0; i < signal.length; i++) {
            signal[i] = Math.max(0, signal[i] - smoothed[i]);
        }
    }

    private static void normalise(double[] signal) {
        double mean = 0;
        for (double value : signal) {
            mean += value;
        }
        mean /= Math.max(1, signal.length);

        double variance = 0;
        for (double value : signal) {
            variance += (value - mean) * (value - mean);
        }
        double deviation = Math.sqrt(variance / Math.max(1, signal.length));
        if (deviation < 1e-12) {
            // Constant signal, such as silence. Leave it at zero rather than
            // amplifying numerical noise into apparent onsets.
            java.util.Arrays.fill(signal, 0);
            return;
        }
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (signal[i] - mean) / deviation;
        }
    }

    static double hzToMel(double hz) {
        return 2595 * Math.log10(1 + hz / 700);
    }

    static double melToHz(double mel) {
        return 700 * (Math.pow(10, mel / 2595) - 1);
    }

    public int length() {
        return strength.length;
    }

    /** The time of a frame, in seconds. */
    public double timeOf(int frame) {
        return frame / frameRate;
    }

    /** The frame nearest a time. */
    public int frameOf(double seconds) {
        return (int) Math.clamp(Math.round(seconds * frameRate), 0, Math.max(0, strength.length - 1));
    }

    /** True when nothing in the signal looks like an onset. */
    public boolean isFlat() {
        for (double value : strength) {
            if (value > 1e-9) {
                return false;
            }
        }
        return true;
    }
}
