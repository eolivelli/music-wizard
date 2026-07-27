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
import java.nio.file.Files;
import java.nio.file.Path;
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
}
