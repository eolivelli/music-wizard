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
import java.util.Objects;

/**
 * A signal's short-time level, in decibels, framed as the onset envelope is.
 *
 * <p>What it is for is the stroke a wind player's tongue puts inside a note
 * that carries on at the same pitch: the level falls and comes back, and the
 * pitch window is too wide to see the voice stop (#882).
 *
 * @param decibels       mean-square level of each frame, in decibels
 * @param frameRate      frames per second
 * @param offsetSeconds  where the first frame is centred
 */
public record LevelContour(double[] decibels, double frameRate, double offsetSeconds) {

    /** Keeps digital silence finite. */
    private static final double SILENCE = 1e-10;

    public LevelContour {
        Objects.requireNonNull(decibels, "decibels");
        if (!(frameRate > 0) || !Double.isFinite(frameRate)) {
            throw new IllegalArgumentException("frameRate must be positive, got: " + frameRate);
        }
    }

    public static LevelContour fromAudio(AudioBuffer audio) {
        Objects.requireNonNull(audio, "audio");
        float[] samples = audio.samples();
        int window = OnsetEnvelope.ONSET_WINDOW;
        int hop = OnsetEnvelope.ONSET_HOP;
        int frames = samples.length < window ? 0 : 1 + (samples.length - window) / hop;
        double[] decibels = new double[frames];
        for (int frame = 0; frame < frames; frame++) {
            double energy = 0;
            for (int i = frame * hop; i < frame * hop + window; i++) {
                energy += (double) samples[i] * samples[i];
            }
            decibels[frame] = 10 * Math.log10(energy / window + SILENCE);
        }
        return new LevelContour(decibels, (double) audio.sampleRate() / hop,
                window / 2.0 / audio.sampleRate());
    }

    public int length() {
        return decibels.length;
    }

    /** The time a frame is centred on, in seconds. */
    public double timeOf(int frame) {
        return offsetSeconds + frame / frameRate;
    }

    /** The frame centred nearest a time. */
    public int frameOf(double seconds) {
        return (int) Math.clamp(Math.round((seconds - offsetSeconds) * frameRate),
                0, Math.max(0, decibels.length - 1));
    }
}
