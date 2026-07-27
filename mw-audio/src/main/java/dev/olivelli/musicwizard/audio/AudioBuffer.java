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

import java.util.Objects;

/**
 * Decoded audio: mono samples in [-1, 1] at a known sample rate.
 *
 * <p>Mono because every analysis stage in this project wants one signal, and
 * float because the alternative is 16-bit integers that every stage would have
 * to convert anyway.
 *
 * <p>The sample array is <em>not</em> copied on construction or on access. These
 * buffers routinely hold tens of millions of samples, and copying them at each
 * stage boundary would dominate the runtime of the whole pipeline. Treat the
 * array as read-only by convention; the stages in this project do.
 */
public final class AudioBuffer {

    private final float[] samples;
    private final int sampleRate;

    public AudioBuffer(float[] samples, int sampleRate) {
        this.samples = Objects.requireNonNull(samples, "samples");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got: " + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    /** The samples, shared rather than copied. Do not modify. */
    public float[] samples() {
        return samples;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int length() {
        return samples.length;
    }

    public double durationSeconds() {
        return (double) samples.length / sampleRate;
    }

    /** Converts a sample index to seconds. */
    public double timeOf(int sampleIndex) {
        return (double) sampleIndex / sampleRate;
    }

    /** Converts a time to the nearest sample index, clamped into range. */
    public int indexOf(double seconds) {
        long index = Math.round(seconds * sampleRate);
        return (int) Math.clamp(index, 0, Math.max(0, samples.length - 1));
    }

    /**
     * Peak absolute amplitude, which tells you whether the file is silent or
     * clipped before a stage wastes time analysing it.
     */
    public float peak() {
        float peak = 0;
        for (float sample : samples) {
            float magnitude = Math.abs(sample);
            if (magnitude > peak) {
                peak = magnitude;
            }
        }
        return peak;
    }

    /** Root-mean-square level over the whole buffer. */
    public double rms() {
        if (samples.length == 0) {
            return 0;
        }
        double sum = 0;
        for (float sample : samples) {
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }

    /** True when the signal is silent enough that analysis would be meaningless. */
    public boolean isEffectivelySilent() {
        return peak() < 1e-4f;
    }

    /**
     * A view of a time range as a new buffer. Copies, because callers slice small
     * windows out of large buffers and expect to keep them.
     */
    public AudioBuffer slice(double startSeconds, double endSeconds) {
        if (endSeconds <= startSeconds) {
            throw new IllegalArgumentException(
                    "end must be after start; got " + startSeconds + " to " + endSeconds);
        }
        int from = indexOf(startSeconds);
        int to = (int) Math.clamp(Math.round(endSeconds * sampleRate), from + 1L, samples.length);
        float[] copy = new float[to - from];
        System.arraycopy(samples, from, copy, 0, copy.length);
        return new AudioBuffer(copy, sampleRate);
    }

    @Override
    public String toString() {
        return String.format("AudioBuffer[%.2fs @ %d Hz, %d samples]",
                durationSeconds(), sampleRate, samples.length);
    }
}
