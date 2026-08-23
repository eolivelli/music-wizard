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

package dev.olivelli.musicwizard.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tier-0 tests: synthetic signals whose correct answer is known exactly. A
 * failure here is always a real defect, never a hard input.
 */
class AudioPipelineTest {

    @TempDir
    Path tempDirectory;

    private Path writeWav(float[] samples, int sampleRate) {
        Path file = tempDirectory.resolve("signal-" + System.nanoTime() + ".wav");
        SignalFactory.writeWav(file, samples, sampleRate);
        return file;
    }

    /**
     * Writes a 32-bit IEEE-float WAV, which {@code SignalFactory} cannot: it
     * writes 16-bit PCM, and 16-bit PCM is exactly the encoding that makes a
     * non-finite sample unrepresentable.
     */
    private Path writeFloatWav(float[] samples, int sampleRate) throws Exception {
        int dataBytes = samples.length * 4;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataBytes);
        buffer.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 3);   // WAVE_FORMAT_IEEE_FLOAT
        buffer.putShort((short) 1);   // mono
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 4);
        buffer.putShort((short) 4);
        buffer.putShort((short) 32);
        buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(dataBytes);
        for (float sample : samples) {
            buffer.putFloat(sample);
        }
        Path file = tempDirectory.resolve("float-" + System.nanoTime() + ".wav");
        Files.write(file, buffer.array());
        return file;
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {

        @Test
        @DisplayName("round-trips a WAV at its own sample rate")
        void decodesWav() {
            float[] original = SignalFactory.sine(440, 1.0, 22_050);
            Path file = writeWav(original, 22_050);

            AudioBuffer decoded = AudioDecoder.decode(file, 22_050);

            assertThat(decoded.sampleRate()).isEqualTo(22_050);
            assertThat(decoded.durationSeconds()).isCloseTo(1.0, within(0.01));
            // 16-bit quantisation is the only loss, so the shape must survive.
            assertThat(decoded.peak()).isCloseTo(0.5f, within(0.01f));
        }

        @Test
        @DisplayName("resamples to the analysis rate")
        void resamplesOnDecode() {
            Path file = writeWav(SignalFactory.sine(440, 1.0, 44_100), 44_100);

            AudioBuffer decoded = AudioDecoder.decode(file, 22_050);

            assertThat(decoded.sampleRate()).isEqualTo(22_050);
            assertThat(decoded.durationSeconds()).isCloseTo(1.0, within(0.02));
        }

        @Test
        @DisplayName("reports silence rather than pretending to analyse it")
        void detectsSilence() {
            Path file = writeWav(SignalFactory.silence(0.5, 22_050), 22_050);

            assertThat(AudioDecoder.decode(file).isEffectivelySilent()).isTrue();
        }

        @Test
        @DisplayName("explains itself when the format is unreadable")
        void rejectsUnknownFormat() throws Exception {
            Path bogus = tempDirectory.resolve("not-audio.mp3");
            Files.writeString(bogus, "this is not audio at all");

            assertThatThrownBy(() -> AudioDecoder.decode(bogus))
                    .isInstanceOf(AudioDecoder.UnsupportedAudioException.class)
                    .hasMessageContaining("MP3");
        }

        @Test
        @DisplayName("refuses a file that is not there")
        void rejectsMissingFile() {
            assertThatThrownBy(() -> AudioDecoder.decode(tempDirectory.resolve("absent.wav")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("describes what the file says it is, without decoding it")
        void describesTheSourceFormat() {
            Path file = writeWav(SignalFactory.sine(440, 0.25, 44_100), 44_100);

            AudioDecoder.SourceFormat format = AudioDecoder.describe(file).orElseThrow();

            // Not the type: which provider answers for a WAV decides whether
            // that reads as the container or as the encoding, and both are
            // true of the same file.
            assertThat(format.type()).isNotBlank();
            assertThat(format.encoding()).isEqualTo("PCM_SIGNED");
            assertThat(format.sampleRate())
                    .as("the rate the file is stored at, not the analysis rate")
                    .isEqualTo(44_100);
            assertThat(format.channels()).isEqualTo(1);
        }

        @Test
        @DisplayName("describes nothing rather than failing, for a file it cannot read")
        void describesNothingForAnUnreadableFile() throws Exception {
            Path bogus = tempDirectory.resolve("not-audio.mp3");
            Files.writeString(bogus, "this is not audio at all");

            assertThat(AudioDecoder.describe(bogus)).isEmpty();
            assertThat(AudioDecoder.describe(tempDirectory.resolve("absent.wav"))).isEmpty();
            assertThat(AudioDecoder.describe(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("resampling")
    class Resampling {

        @Test
        @DisplayName("preserves duration")
        void preservesDuration() {
            float[] samples = SignalFactory.sine(440, 2.0, 44_100);

            float[] out = Resampler.resample(samples, 44_100, 22_050);

            assertThat(out.length).isCloseTo(samples.length / 2, within(2));
        }

        @Test
        @DisplayName("is a no-op when the rate is unchanged")
        void identityWhenUnchanged() {
            float[] samples = SignalFactory.sine(440, 0.1, 22_050);

            assertThat(Resampler.resample(samples, 22_050, 22_050)).isSameAs(samples);
        }

        @Test
        @DisplayName("suppresses content above the new Nyquist limit instead of aliasing it")
        void lowPassesBeforeDownsampling() {
            // 8 kHz cannot exist below an 11.025 kHz Nyquist limit. Without the
            // low-pass it would not vanish -- it would fold down to 3 kHz and be
            // indistinguishable from a real partial, which is fatal for chroma.
            float[] tone = SignalFactory.sine(8_000, 0.5, 44_100);

            float[] out = Resampler.resample(tone, 44_100, 22_050);

            AudioBuffer buffer = new AudioBuffer(out, 22_050);
            double energyAtAlias = bandEnergy(buffer, 2_800, 3_200);
            double totalEnergy = bandEnergy(buffer, 100, 11_000);
            assertThat(energyAtAlias / Math.max(totalEnergy, 1e-9)).isLessThan(0.2);
        }

        private double bandEnergy(AudioBuffer buffer, double lowHz, double highHz) {
            Spectrogram spectrogram = Spectrogram.compute(buffer);
            int low = spectrogram.binOf(lowHz);
            int high = spectrogram.binOf(highHz);
            double sum = 0;
            for (float[] frame : spectrogram.magnitudes()) {
                for (int bin = low; bin <= high; bin++) {
                    sum += frame[bin];
                }
            }
            return sum;
        }
    }

    @Nested
    @DisplayName("spectrogram")
    class Spectra {

        @Test
        @DisplayName("puts a 440 Hz sine in the 440 Hz bin")
        void findsTheTone() {
            AudioBuffer audio = new AudioBuffer(SignalFactory.sine(440, 1.0, 22_050), 22_050);

            Spectrogram spectrogram = Spectrogram.compute(audio);

            int expected = spectrogram.binOf(440);
            float[] frame = spectrogram.magnitudes()[spectrogram.frameCount() / 2];
            int loudest = 0;
            for (int bin = 1; bin < frame.length; bin++) {
                if (frame[bin] > frame[loudest]) {
                    loudest = bin;
                }
            }
            assertThat(loudest).isCloseTo(expected, within(1));
        }

        @Test
        @DisplayName("reports frame times that match the hop")
        void reportsFrameTimes() {
            AudioBuffer audio = new AudioBuffer(SignalFactory.sine(440, 2.0, 22_050), 22_050);

            Spectrogram spectrogram = Spectrogram.compute(audio, 2048, 512);

            assertThat(spectrogram.frameRate()).isCloseTo(22_050 / 512.0, within(1e-9));
            double delta = spectrogram.timeOf(11) - spectrogram.timeOf(10);
            assertThat(delta).isCloseTo(512.0 / 22_050, within(1e-9));
        }

        @Test
        @DisplayName("handles audio shorter than one window")
        void handlesShortAudio() {
            AudioBuffer audio = new AudioBuffer(new float[100], 22_050);

            assertThat(Spectrogram.compute(audio).frameCount()).isZero();
        }
    }

    /**
     * A non-finite sample is not a local defect. It reaches every bin of every
     * window containing it, and any stage that aggregates over frames then
     * inherits it whole, so the buffer is where it has to be stopped.
     */
    @Nested
    @DisplayName("non-finite values")
    class NonFinite {

        @Test
        @DisplayName("refuses a buffer containing NaN, naming where it is")
        void rejectsNaN() {
            float[] samples = SignalFactory.sine(440, 0.1, 22_050);
            samples[137] = Float.NaN;

            assertThatThrownBy(() -> new AudioBuffer(samples, 22_050))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("samples[137]")
                    .hasMessageContaining("NaN");
        }

        @Test
        @DisplayName("refuses a buffer containing either infinity")
        void rejectsInfinities() {
            float[] positive = new float[8];
            positive[3] = Float.POSITIVE_INFINITY;
            assertThatThrownBy(() -> new AudioBuffer(positive, 22_050))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("samples[3]")
                    .hasMessageContaining("Infinity");

            float[] negative = new float[8];
            negative[7] = Float.NEGATIVE_INFINITY;
            assertThatThrownBy(() -> new AudioBuffer(negative, 22_050))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("samples[7]");
        }

        @Test
        @DisplayName("still accepts every finite value, including the extremes")
        void acceptsFiniteExtremes() {
            // The check is about finiteness, not range. A buffer can legitimately
            // sit outside [-1, 1] between a gain stage and a normalisation, and
            // rejecting that would be a different -- and wrong -- change.
            float[] samples = {
                0f, -0f, 1f, -1f, 2.5f, -2.5f,
                Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE,
            };

            AudioBuffer buffer = new AudioBuffer(samples, 22_050);

            assertThat(buffer.length()).isEqualTo(samples.length);
            assertThat(buffer.peak()).isEqualTo(Float.MAX_VALUE);
            assertThat(new AudioBuffer(new float[0], 22_050).length()).isZero();
        }

        @Test
        @DisplayName("keeps slicing a valid buffer working")
        void sliceOfAValidBufferSurvivesTheCheck() {
            AudioBuffer buffer = new AudioBuffer(SignalFactory.sine(440, 1.0, 22_050), 22_050);

            assertThat(buffer.slice(0.25, 0.75).durationSeconds()).isCloseTo(0.5, within(0.01));
        }

        @Test
        @DisplayName("decodes a float WAV carrying NaN and infinities rather than failing on it")
        void decodingNeverProducesANonFiniteSample() throws Exception {
            // This is the test that makes rejecting -- rather than sanitising --
            // safe, and it is a tripwire as much as an assertion. AudioDecoder
            // converts every format to 16-bit signed PCM, and no 16-bit integer
            // decodes to NaN or an infinity, so arbitrary user audio cannot reach
            // the constructor with one. If a float passthrough is ever added, the
            // constructor will start throwing here and this fails loudly, which
            // is the point at which sanitising at the decode boundary becomes the
            // right conversation.
            float[] samples = SignalFactory.sine(440, 0.5, 22_050);
            samples[1000] = Float.NaN;
            samples[2000] = Float.POSITIVE_INFINITY;
            samples[3000] = Float.NEGATIVE_INFINITY;
            samples[4000] = 1e30f;
            Path file = writeFloatWav(samples, 22_050);

            AudioBuffer decoded = AudioDecoder.decode(file, 22_050);

            assertThat(decoded.samples()).isNotEmpty();
            for (float sample : decoded.samples()) {
                assertThat(Float.isFinite(sample)).isTrue();
            }
            // And through the resampler as well, which runs before the buffer
            // is constructed and so is covered by the same check.
            AudioBuffer resampled = AudioDecoder.decode(file, 16_000);
            for (float sample : resampled.samples()) {
                assertThat(Float.isFinite(sample)).isTrue();
            }
        }

        @Test
        @DisplayName("refuses a spectrogram with a non-finite bin")
        void rejectsNonFiniteMagnitudes() {
            // windowSize 8 gives 5 bins, so these frames are the right width and
            // the finiteness clause is what they exercise.
            float[][] magnitudes = new float[3][5];
            magnitudes[1][4] = Float.NaN;

            assertThatThrownBy(() -> new Spectrogram(magnitudes, 22_050, 8, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magnitudes[1][4]");

            float[][] missingFrame = new float[2][];
            missingFrame[0] = new float[5];
            assertThatThrownBy(() -> new Spectrogram(missingFrame, 22_050, 8, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magnitudes[1]")
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("refuses frames that are not the width the transform produces")
        void rejectsFramesOfTheWrongWidth() {
            // A short row reaches Chroma, the tuning estimate and the onset
            // envelope as an ArrayIndexOutOfBoundsException from inside a DSP
            // loop -- the same unhelpful failure the null check exists to
            // prevent. windowSize 8 gives 5 bins.
            float[][] ragged = {new float[5], new float[3]};
            assertThatThrownBy(() -> new Spectrogram(ragged, 22_050, 8, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magnitudes[1] has 3 bins")
                    .hasMessageContaining("windowSize 8 gives 5");

            // A long row is just as wrong and fails silently rather than
            // loudly: the extra bins are simply never read.
            float[][] overlong = {new float[5], new float[9]};
            assertThatThrownBy(() -> new Spectrogram(overlong, 22_050, 8, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magnitudes[1] has 9 bins");

            // Rectangular but wrong is the case checking rows against each other
            // would have missed, and it is the one that does damage quietly:
            // every frequency method assumes the transform's width, so a caller
            // asking for 440 Hz would have been handed a bin centred at 43 Hz.
            float[][] uniformlyWrong = new float[3][5];
            assertThatThrownBy(() -> new Spectrogram(uniformlyWrong, 22_050, 2048, 512))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magnitudes[0] has 5 bins")
                    .hasMessageContaining("windowSize 2048 gives 1025");

            // Zero-width frames fall out of the same clause. They used to fail
            // as "0 > -1" from inside Math.clamp, three call levels down.
            assertThatThrownBy(() -> new Spectrogram(new float[3][0], 22_050, 8, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magnitudes[0] has 0 bins");
        }

        @Test
        @DisplayName("names which size was not positive, rather than which three could have been")
        void rejectsNonPositiveSizes() {
            // Untested before this PR, and load-bearing for the two sizes
            // nothing else catches. Measured on a build with the clause deleted:
            // sampleRate = 0 constructs, and then timeOf(0) is Infinity and
            // binOf() collapses every frequency onto bin 0 -- note frameRate()
            // is 0.0 there, finite and wrong, which is why it is the wrong
            // method to cite. hopSize = 0 constructs and frameRate() is
            // Infinity. Silent wrong answers, which is what this replaces.
            assertThatThrownBy(() -> new Spectrogram(new float[0][], 0, 2048, 512))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("sampleRate must be positive, got: 0");

            assertThatThrownBy(() -> new Spectrogram(new float[0][], 22_050, 2048, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("hopSize must be positive, got: 0");

            // bitCount(Integer.MIN_VALUE) is 1, so the power-of-two check never
            // catches it whatever order the clauses run in -- only this one
            // does. That is about coverage, not ordering.
            assertThatThrownBy(
                    () -> new Spectrogram(new float[0][], 22_050, Integer.MIN_VALUE, 512))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("windowSize must be positive, got: -2147483648");

            // The ordering itself, which needs a value both clauses reject:
            // bitCount(-4) is 30, so whichever check runs first names itself.
            // #86 proposes hoisting these into compute() and reasons from
            // positivity running first, so it should be defended rather than
            // asserted in a comment.
            assertThatThrownBy(() -> new Spectrogram(new float[0][], 22_050, -4, 512))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("windowSize must be positive, got: -4");
        }

        @Test
        @DisplayName("accepts what compute actually produces, at every resolution the pipeline uses")
        void acceptsEveryResolutionTheTransformProduces() {
            // The other direction: the width check must not reject a real
            // spectrogram. These are the two resolutions the pipeline runs at,
            // plus the default that neither stage uses.
            AudioBuffer audio = new AudioBuffer(SignalFactory.sine(440, 0.5, 22_050), 22_050);

            for (int[] resolution : new int[][] {{4096, 1024}, {1024, 128}, {2048, 512}}) {
                Spectrogram spectrogram = Spectrogram.compute(audio, resolution[0], resolution[1]);
                assertThat(spectrogram.binCount()).isEqualTo(resolution[0] / 2 + 1);
                for (float[] frame : spectrogram.magnitudes()) {
                    assertThat(frame).hasSize(resolution[0] / 2 + 1);
                }
            }
        }

        @Test
        @DisplayName("refuses a spectrogram the transform overflowed, not just a poisoned input")
        void spectrogramOverflowIsCaughtEvenThoughTheBufferWasFinite() {
            // 1e38 is finite, so the buffer accepts it -- and the window multiply
            // and transform then overflow. Validating only the input would leave
            // this door open, and a poisoned bin is worse than a poisoned sample
            // because every spectral stage reads the spectrogram, not the audio.
            float[] enormous = new float[8192];
            Arrays.fill(enormous, 1e38f);
            AudioBuffer buffer = new AudioBuffer(enormous, 22_050);

            assertThatThrownBy(() -> Spectrogram.compute(buffer, 2048, 512))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magnitudes must be finite");
        }

        @Test
        @DisplayName("resamples large values without inventing an infinity")
        void resamplingDoesNotOverflow() {
            // Alternating extremes, because the interpolation subtracts adjacent
            // samples: in float that difference overflows, in double it does not.
            // Upsampling is the case that exposes it, since downsampling
            // low-passes first and shrinks the values before they are subtracted.
            float[] alternating = new float[1000];
            for (int i = 0; i < alternating.length; i++) {
                alternating[i] = i % 2 == 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
            }

            for (float sample : Resampler.resample(alternating, 22_050, 44_100)) {
                assertThat(Float.isFinite(sample)).isTrue();
            }
            for (float sample : Resampler.resample(alternating, 44_100, 22_050)) {
                assertThat(Float.isFinite(sample)).isTrue();
            }
        }
    }
}
