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

package dev.olivelli.musicwizard.ml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the model-side STFT")
class SpleeterStftTest {

    @Test
    @DisplayName("round-trips a signal through forward and inverse")
    void roundTrips() {
        // Noise, not a sine: a sinusoid can round-trip through a broken
        // transform whose error hides in the phase it happens not to move.
        Random random = new Random(42);
        float[] signal = new float[44100];
        for (int i = 0; i < signal.length; i++) {
            signal[i] = random.nextFloat() * 2 - 1;
        }
        SpleeterStft stft = new SpleeterStft();

        float[] back = stft.inverse(stft.forward(signal), signal.length);

        double error = 0;
        double energy = 0;
        for (int i = 0; i < signal.length; i++) {
            double d = back[i] - signal[i];
            error += d * d;
            energy += signal[i] * signal[i];
        }
        assertThat(error / energy)
                .as("relative reconstruction energy error")
                .isLessThan(1e-6);
    }

    @Test
    @DisplayName("round-trips the edges at full amplitude, not faded")
    void edgesSurvive() {
        // The first and last samples are covered by fewer overlapping frames.
        // Dividing by the summed squared window is what keeps them at their
        // amplitude; a constant-overlap assumption fades them instead.
        float[] signal = new float[SpleeterStft.FRAME * 3];
        java.util.Arrays.fill(signal, 0.5f);
        SpleeterStft stft = new SpleeterStft();

        float[] back = stft.inverse(stft.forward(signal), signal.length);

        assertThat(back[0]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(back[signal.length - 1])
                .isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    @DisplayName("a signal shorter than one frame still round-trips")
    void shortSignal() {
        float[] signal = new float[100];
        for (int i = 0; i < signal.length; i++) {
            signal[i] = (float) Math.sin(i * 0.1);
        }
        SpleeterStft stft = new SpleeterStft();

        float[] back = stft.inverse(stft.forward(signal), signal.length);

        assertThat(back).hasSize(100);
        for (int i = 0; i < signal.length; i++) {
            assertThat(back[i]).isCloseTo(signal[i],
                    org.assertj.core.data.Offset.offset(0.001f));
        }
    }
}
