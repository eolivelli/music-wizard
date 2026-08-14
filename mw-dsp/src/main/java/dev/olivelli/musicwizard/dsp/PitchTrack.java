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

import java.util.Objects;

/**
 * A fundamental-frequency track: one pitch per frame, and whether that frame
 * was singing at all.
 *
 * <p>{@code frequenciesHz} is finite and positive in every frame, including
 * unvoiced ones. That is not an oversight: the decoder carries a pitch through
 * a silence so that the note after it is not forced to start from nowhere, and
 * throwing it away here would mean re-deriving it downstream. Read
 * {@code voiced} first and the frequency second — a frequency in an unvoiced
 * frame is the decoder's memory, not a measurement.
 *
 * @param frequenciesHz one fundamental per frame
 * @param voiced        whether each frame carries a sung or played pitch
 * @param voicedness    per-frame probability the frame is voiced, before decoding
 * @param sampleRate    sample rate of the audio analysed
 * @param windowSize    analysis window, in samples
 * @param hopSize       samples advanced between frames
 */
public record PitchTrack(
        double[] frequenciesHz,
        boolean[] voiced,
        double[] voicedness,
        int sampleRate,
        int windowSize,
        int hopSize) {

    public PitchTrack {
        Objects.requireNonNull(frequenciesHz, "frequenciesHz");
        Objects.requireNonNull(voiced, "voiced");
        Objects.requireNonNull(voicedness, "voicedness");
        if (frequenciesHz.length != voiced.length || frequenciesHz.length != voicedness.length) {
            throw new IllegalArgumentException("frame counts disagree: " + frequenciesHz.length
                    + " frequencies, " + voiced.length + " voicings, "
                    + voicedness.length + " voicednesses");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got: " + sampleRate);
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got: " + windowSize);
        }
        if (hopSize <= 0) {
            throw new IllegalArgumentException("hopSize must be positive, got: " + hopSize);
        }
        for (int i = 0; i < frequenciesHz.length; i++) {
            if (!Double.isFinite(frequenciesHz[i]) || frequenciesHz[i] <= 0) {
                throw new IllegalArgumentException("frequenciesHz[" + i + "] must be finite and"
                        + " positive, got: " + frequenciesHz[i]);
            }
        }
    }

    public int frameCount() {
        return frequenciesHz.length;
    }

    /** Frames per second. */
    public double frameRate() {
        return (double) sampleRate / hopSize;
    }

    /**
     * The time at the centre of a frame.
     *
     * <p>The centre rather than the start, as {@code Spectrogram.timeOf} has it,
     * and for a stronger reason here: a fundamental estimated by comparing a
     * window against a shifted copy of itself is a property of the whole window
     * and cannot be attributed to either end of it. It also bounds what a note
     * onset taken from this track can be worth — a pitch change inside a window
     * is only visible once it dominates the window, so an onset read from the
     * frame it first appears in is late, by up to half a window.
     */
    public double timeOf(int frame) {
        return (frame * (double) hopSize + windowSize / 2.0) / sampleRate;
    }

    /** The fractional MIDI pitch of a frame, whether or not it is voiced. */
    public double midiPitchAt(int frame) {
        return 69 + 12 * (Math.log(frequenciesHz[frame] / 440.0) / Math.log(2));
    }
}
