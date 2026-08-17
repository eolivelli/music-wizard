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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.jtransforms.fft.FloatFFT_1D;

/**
 * Splits a separated vocal stem into the part that came from the voice and the
 * part that came from the band, so each can be scored on its own.
 *
 * <p>What it is for: a stem scored against the singer's annotations says how
 * much melody survived, and nothing about what removed it. Two different
 * defects are mixed in there — voice the mask took out with the band, and band
 * the mask left behind for the tracker to follow — and they want opposite
 * fixes (#503).
 *
 * <p>How the split is exact rather than estimated. The measurement supplies
 * the mix, so the band inside it is {@code mix - voice} sample for sample, and
 * both sources are known where the separator only saw their sum. Each
 * time-frequency bin of the stem is divided between the two in proportion to
 * the energy the two sources have in that bin, which is the ideal ratio mask —
 * so the two parts add back to the stem, and every sample of it is attributed
 * rather than discarded.
 *
 * <p>What the split cannot do is separate two sources that genuinely overlap
 * in one bin. There it apportions by a rule, and the trailing argument is that
 * rule's exponent: squared shares are the default and give an overlapped bin
 * mostly to whichever source is louder there, and a smaller exponent divides
 * it more evenly. A conclusion that changes with it is a conclusion about the
 * rule.
 *
 * <p>The transform is the separator's own frame, hop and window, because that
 * is the grid the mask was applied on, and a coarser one would smear a mask
 * decision across bins that were decided separately. Nothing here checks that
 * the two agree; keep them in step with {@code SpleeterStft} by reading it.
 *
 * <p>The run prints its own analysis-synthesis round trip and refuses rather
 * than reporting a split over a transform that does not invert. The sum of the
 * parts is printed beside it and is the same measurement over again — the
 * inverse is linear and the shares add to one — so read it as arithmetic
 * confirmed, not as a second witness.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/SeparationSplit.java \
 *       voice.wav mix.wav stem.wav voice-part.wav band-part.wav [exponent]
 * </pre>
 *
 * <p>Driven by {@code tools/apportion-separation-loss.py}, which mixes the
 * clips, runs the separator and scores each part against the annotations.
 */
public final class SeparationSplit {

    static final int FRAME = 4096;
    static final int HOP = 1024;

    private static final double MAX_ERROR_DB = -60.0;

    /** How an overlapped bin is divided; see the class javadoc. */
    private static final double DEFAULT_EXPONENT = 2.0;

    public static void main(String[] args) throws IOException {
        if (args.length != 5 && args.length != 6) {
            System.err.println("usage: SeparationSplit voice.wav mix.wav stem.wav"
                    + " voice-part.wav band-part.wav [exponent]");
            System.exit(2);
        }
        double exponent = args.length == 6
                ? Double.parseDouble(args[5]) : DEFAULT_EXPONENT;
        float[] voice = mono(args[0]);
        float[] mix = mono(args[1]);
        float[] stem = mono(args[2]);
        if (voice.length != mix.length || voice.length != stem.length) {
            System.err.printf("lengths differ: voice %d, mix %d, stem %d%n",
                    voice.length, mix.length, stem.length);
            System.exit(2);
        }
        if (energy(stem) == 0) {
            // Distinguished from a split that does not add up, which is what
            // the error below would otherwise call it: zero over zero is a
            // perfect reconstruction of nothing.
            System.err.println("the stem is silent: nothing to apportion");
            System.exit(1);
        }

        float[] band = new float[mix.length];
        for (int i = 0; i < band.length; i++) {
            band[i] = mix[i] - voice[i];
        }

        Stft stft = new Stft();
        float[][] voiceSpec = stft.forward(voice);
        float[][] bandSpec = stft.forward(band);
        float[][] stemSpec = stft.forward(stem);

        // The two source spectrograms are overwritten with the parts they
        // decide, frame by frame once their shares are taken: a whole
        // recording is several hundred megabytes of spectrogram per minute and
        // this tool is run over a corpus.
        for (int t = 0; t < stemSpec.length; t++) {
            for (int f = 0; f < Stft.BINS; f++) {
                // Over the energies, so the default exponent is the identity
                // and asks nothing of Math.pow.
                double v = Math.pow(energy(voiceSpec[t], f), exponent / 2);
                double b = Math.pow(energy(bandSpec[t], f), exponent / 2);
                // A bin where neither source has anything is a bin where the
                // stem has nothing either, so which side the zero falls on
                // decides nothing; the floor only keeps the ratio finite.
                double share = v / (v + b + 1e-30);
                voiceSpec[t][2 * f] = (float) (stemSpec[t][2 * f] * share);
                voiceSpec[t][2 * f + 1] = (float) (stemSpec[t][2 * f + 1] * share);
                bandSpec[t][2 * f] = (float) (stemSpec[t][2 * f] * (1 - share));
                bandSpec[t][2 * f + 1] = (float) (stemSpec[t][2 * f + 1] * (1 - share));
            }
        }

        float[] voicePart = stft.inverse(voiceSpec, stem.length);
        float[] bandPart = stft.inverse(bandSpec, stem.length);

        double roundTrip = errorDb(stft.inverse(stemSpec, stem.length), stem, stem);
        float[] sum = new float[stem.length];
        for (int i = 0; i < sum.length; i++) {
            sum[i] = voicePart[i] + bandPart[i];
        }
        double split = errorDb(sum, stem, stem);
        if (roundTrip > MAX_ERROR_DB || split > MAX_ERROR_DB) {
            System.err.printf(Locale.ROOT,
                    "the transform does not invert here: round trip %.1f dB,"
                    + " parts %.1f dB%n", roundTrip, split);
            System.exit(1);
        }

        writeWav(Path.of(args[3]), voicePart);
        writeWav(Path.of(args[4]), bandPart);
        System.out.printf(Locale.ROOT,
                "voice-retention %.1f  band-rejection %.1f  round-trip %.1f  split %.1f%n",
                levelDb(voicePart, voice), levelDb(bandPart, band), roundTrip, split);
    }

    private static double energy(float[] spectrum, int bin) {
        float re = spectrum[2 * bin];
        float im = spectrum[2 * bin + 1];
        return (double) re * re + (double) im * im;
    }

    /** How far one signal sits below another in energy, in decibels. */
    private static double levelDb(float[] part, float[] source) {
        return 10 * Math.log10((energy(part) + 1e-30) / (energy(source) + 1e-30));
    }

    private static double errorDb(float[] got, float[] want, float[] reference) {
        float[] difference = new float[got.length];
        for (int i = 0; i < got.length; i++) {
            difference[i] = got[i] - want[i];
        }
        return levelDb(difference, reference);
    }

    private static double energy(float[] samples) {
        double total = 0;
        for (float sample : samples) {
            total += (double) sample * sample;
        }
        return total;
    }

    private static float[] mono(String path) {
        AudioBuffer buffer = AudioDecoder.decode(Path.of(path), AudioDecoder.FULL_SAMPLE_RATE);
        return buffer.samples();
    }

    /**
     * 32-bit float WAV, because a part may sit tens of decibels under the mix
     * and 16-bit would quantise exactly the quiet signal being measured.
     */
    private static void writeWav(Path path, float[] samples) throws IOException {
        byte[] pcm = new byte[samples.length * 4];
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) {
            buffer.putFloat(sample);
        }
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_FLOAT,
                AudioDecoder.FULL_SAMPLE_RATE, 32, 1, 4, AudioDecoder.FULL_SAMPLE_RATE,
                false);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, samples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    /**
     * The separator's own transform: frame 4096, hop 1024, periodic Hann,
     * centre-padded, inverted by weighted overlap-add.
     *
     * <p>A copy rather than a use of {@code SpleeterStft}, which is not visible
     * outside its package; the parameters are asserted by the round trip this
     * tool prints.
     */
    private static final class Stft {

        static final int BINS = FRAME / 2 + 1;

        private final float[] window = new float[FRAME];
        private final FloatFFT_1D fft = new FloatFFT_1D(FRAME);

        Stft() {
            for (int i = 0; i < FRAME; i++) {
                window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / FRAME));
            }
        }

        float[][] forward(float[] samples) {
            float[] padded = new float[samples.length + FRAME];
            System.arraycopy(samples, 0, padded, FRAME / 2, samples.length);
            int frames = 1 + (samples.length + HOP - 1) / HOP;
            float[][] out = new float[frames][];
            for (int t = 0; t < frames; t++) {
                float[] buffer = new float[FRAME];
                int start = t * HOP;
                for (int i = 0; i < FRAME; i++) {
                    int at = start + i;
                    buffer[i] = at < padded.length ? padded[at] * window[i] : 0f;
                }
                fft.realForward(buffer);
                float[] spectrum = new float[2 * BINS];
                spectrum[0] = buffer[0];
                spectrum[1] = 0f;
                spectrum[2 * (BINS - 1)] = buffer[1];
                spectrum[2 * (BINS - 1) + 1] = 0f;
                for (int f = 1; f < BINS - 1; f++) {
                    spectrum[2 * f] = buffer[2 * f];
                    spectrum[2 * f + 1] = buffer[2 * f + 1];
                }
                out[t] = spectrum;
            }
            return out;
        }

        float[] inverse(float[][] spectra, int length) {
            float[] accumulator = new float[length + 2 * FRAME];
            float[] weights = new float[length + 2 * FRAME];
            float[] buffer = new float[FRAME];
            for (int t = 0; t < spectra.length; t++) {
                float[] spectrum = spectra[t];
                buffer[0] = spectrum[0];
                buffer[1] = spectrum[2 * (BINS - 1)];
                for (int f = 1; f < BINS - 1; f++) {
                    buffer[2 * f] = spectrum[2 * f];
                    buffer[2 * f + 1] = spectrum[2 * f + 1];
                }
                fft.realInverse(buffer, true);
                int start = t * HOP;
                for (int i = 0; i < FRAME; i++) {
                    int at = start + i;
                    if (at < accumulator.length) {
                        accumulator[at] += buffer[i] * window[i];
                        weights[at] += window[i] * window[i];
                    }
                }
            }
            float[] out = new float[length];
            for (int i = 0; i < length; i++) {
                int at = i + FRAME / 2;
                out[i] = weights[at] > 1e-9f ? accumulator[at] / weights[at] : 0f;
            }
            return out;
        }
    }

    private SeparationSplit() {
    }
}
