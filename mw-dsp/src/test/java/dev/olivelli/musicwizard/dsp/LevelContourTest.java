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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LevelContourTest {

    private static final int RATE = 22_050;

    @Test
    @DisplayName("reads a sine's mean-square level, and the silence after it as far lower")
    void readsTheLevel() {
        float[] samples = new float[RATE];
        for (int i = 0; i < RATE / 2; i++) {
            samples[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / RATE));
        }
        LevelContour level = LevelContour.fromAudio(new AudioBuffer(samples, RATE));

        assertThat(level.decibels()[level.frameOf(0.2)])
                .isCloseTo(10 * Math.log10(0.125), within(0.05));
        assertThat(level.decibels()[level.frameOf(0.8)]).isLessThan(-90);
    }

    @Test
    @DisplayName("stamps a frame at its window's centre, where the level changes")
    void framesAreCentred() {
        float[] samples = new float[RATE];
        for (int i = RATE / 2; i < RATE; i++) {
            samples[i] = 0.5f;
        }
        LevelContour level = LevelContour.fromAudio(new AudioBuffer(samples, RATE));

        // The frame centred on the step straddles it evenly.
        assertThat(level.decibels()[level.frameOf(0.5)])
                .isCloseTo(10 * Math.log10(0.25 / 2), within(0.2));
        assertThat(level.timeOf(level.frameOf(0.5))).isCloseTo(0.5, within(0.5 / level.frameRate()));
    }

    @Test
    @DisplayName("a signal shorter than one window has no frames")
    void aShortSignalHasNoFrames() {
        LevelContour level = LevelContour.fromAudio(
                new AudioBuffer(new float[OnsetEnvelope.ONSET_WINDOW - 1], RATE));

        assertThat(level.length()).isZero();
    }
}
