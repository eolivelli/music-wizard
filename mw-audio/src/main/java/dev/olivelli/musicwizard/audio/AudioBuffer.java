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
 *
 * <p>Samples must be finite, and that is checked rather than assumed. See the
 * constructor for why it is checked here and not somewhere cheaper.
 */
public final class AudioBuffer {

    private final float[] samples;
    private final int sampleRate;

    /**
     * Wraps a sample array, rejecting any sample that is not finite.
     *
     * <p>A single non-finite sample does not stay where it lands. It reaches
     * every FFT bin of every window containing it, and any stage that then
     * aggregates over frames inherits it as a whole: one NaN in a 32-second
     * I-V-vi-IV click track pins {@code Chroma.estimateTuning} at -0.4875
     * semitones -- because a NaN deviation histograms into slot 0 and
     * {@code >} against NaN is false, so the mode can never move off it -- and
     * turns a chart of {@code C G Am F} at 0.91 confidence into a single
     * {@code N.C.} at 0.44. Beat tracking is untouched, so the failure does not
     * look like the bad sample it is.
     *
     * <p>Rejected rather than replaced with zero, and rejected here rather than
     * at the decode boundary, for one reason: no decodable file can produce a
     * non-finite sample. {@link AudioDecoder} never reads a float out of the
     * stream at all -- {@code frameToMono} reassembles two bytes into an
     * {@code int} and divides -- so even a provider that ignored the 16-bit
     * format it is asked for would yield finite garbage rather than a NaN. A
     * float WAV carrying NaN, both infinities and 1e30 decodes to zeros, as
     * {@code AudioPipelineTest} asserts. So this cannot turn a readable
     * recording into a hard error; the only thing it can catch is a caller with
     * a bug, and quietly substituting zero for that would alter the audio and
     * hide the cause at once.
     *
     * <p>The scan is a full pass over every buffer, including ones the pipeline
     * derives from other buffers, and that is affordable: 3.2 ms over five
     * minutes of audio, against 531 ms for {@code Spectrogram.compute} and
     * 1124 ms for {@code OnsetEnvelope.fromAudio} on the same samples. There is
     * deliberately no cheaper unchecked constructor for internally derived
     * buffers.
     *
     * <p>What this does <em>not</em> give you is an invariant that holds for
     * the lifetime of the buffer. The array is shared rather than copied, so a
     * caller that writes through {@link #samples()} -- a gain stage working in
     * place, say, which is exactly what the no-copy design invites -- can make
     * a validated buffer non-finite afterwards and nothing will notice. The
     * check is on construction; after that the read-only convention is what
     * holds, as it does for every other property of the array. See issue #79.
     *
     * @throws IllegalArgumentException if {@code sampleRate} is not positive or
     *     any sample is NaN or infinite
     */
    public AudioBuffer(float[] samples, int sampleRate) {
        this.samples = Objects.requireNonNull(samples, "samples");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got: " + sampleRate);
        }
        for (int i = 0; i < samples.length; i++) {
            if (!Float.isFinite(samples[i])) {
                throw new IllegalArgumentException(
                        "samples must be finite, but samples[" + i + "] is " + samples[i]);
            }
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
