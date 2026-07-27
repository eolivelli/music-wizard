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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Decodes a recording to mono float samples at the rate analysis wants.
 *
 * <p>Goes through {@code javax.sound.sampled} rather than a codec library
 * directly, so that adding a format is a matter of putting a service provider on
 * the classpath. MP3 support comes from ffsampledsp, which registers itself that
 * way and carries its own FFmpeg natives.
 *
 * <p>Analysis runs at 22.05 kHz by default. Everything this pipeline looks at —
 * onsets, chroma up to about 5 kHz, vocal and bass f0 — lives well below the
 * Nyquist limit of that rate, and halving the sample count halves the cost of
 * every stage downstream.
 */
public final class AudioDecoder {

    /** Sample rate used for analysis. */
    public static final int ANALYSIS_SAMPLE_RATE = 22_050;

    /** Sample rate used where fidelity matters, such as feeding a separation model. */
    public static final int FULL_SAMPLE_RATE = 44_100;

    private AudioDecoder() {
    }

    /** Decodes a file to mono at the analysis sample rate. */
    public static AudioBuffer decode(Path file) {
        return decode(file, ANALYSIS_SAMPLE_RATE);
    }

    /**
     * Decodes a file to mono at a given sample rate.
     *
     * @throws UnsupportedAudioException when no provider can read the format
     */
    public static AudioBuffer decode(Path file, int targetSampleRate) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("not a readable file: " + file);
        }
        if (targetSampleRate <= 0) {
            throw new IllegalArgumentException(
                    "targetSampleRate must be positive, got: " + targetSampleRate);
        }

        // Buffered because some providers read the header in many small reads,
        // and because AudioSystem needs mark support to sniff the format.
        try (var raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             AudioInputStream in = AudioSystem.getAudioInputStream(raw)) {

            AudioFormat source = in.getFormat();
            AudioFormat pcm = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate() > 0 ? source.getSampleRate() : targetSampleRate,
                    16,
                    source.getChannels() > 0 ? source.getChannels() : 1,
                    2 * Math.max(1, source.getChannels()),
                    source.getSampleRate() > 0 ? source.getSampleRate() : targetSampleRate,
                    false);

            try (AudioInputStream decoded = AudioSystem.getAudioInputStream(pcm, in)) {
                float[] mono = readMono(decoded, pcm);
                int decodedRate = Math.round(pcm.getSampleRate());
                float[] resampled = decodedRate == targetSampleRate
                        ? mono
                        : Resampler.resample(mono, decodedRate, targetSampleRate);
                return new AudioBuffer(resampled, targetSampleRate);
            }
        } catch (UnsupportedAudioFileException e) {
            throw new UnsupportedAudioException(
                    "no audio provider can read " + file.getFileName()
                            + ". Supported out of the box: WAV, AIFF, and — via the bundled"
                            + " ffsampledsp natives — MP3, FLAC, M4A and Ogg.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /**
     * Reads the stream and mixes channels down to mono.
     *
     * <p>Channels are averaged rather than taking the left one, because a
     * stereo mix routinely places instruments hard to one side, and analysing a
     * single channel would silently drop them.
     */
    private static float[] readMono(AudioInputStream in, AudioFormat format) throws IOException {
        int channels = Math.max(1, format.getChannels());
        boolean bigEndian = format.isBigEndian();

        byte[] chunk = new byte[1 << 16];
        // Growable because the frame length is often unknown for compressed
        // input, where the provider reports NOT_SPECIFIED.
        float[] out = new float[1 << 16];
        int written = 0;
        int carry = 0;
        byte[] pending = new byte[4];

        int read;
        while ((read = in.read(chunk)) > 0) {
            int offset = 0;
            // A read can split a frame, so finish the partial one first.
            while (carry > 0 && offset < read) {
                pending[carry++] = chunk[offset++];
                if (carry == 2 * channels) {
                    if (written == out.length) {
                        out = grow(out);
                    }
                    out[written++] = frameToMono(pending, 0, channels, bigEndian);
                    carry = 0;
                }
            }
            int frameBytes = 2 * channels;
            int usable = ((read - offset) / frameBytes) * frameBytes;
            for (int i = offset; i < offset + usable; i += frameBytes) {
                if (written == out.length) {
                    out = grow(out);
                }
                out[written++] = frameToMono(chunk, i, channels, bigEndian);
            }
            int leftover = read - offset - usable;
            for (int i = 0; i < leftover; i++) {
                pending[carry++] = chunk[offset + usable + i];
            }
        }

        float[] exact = new float[written];
        System.arraycopy(out, 0, exact, 0, written);
        return exact;
    }

    private static float frameToMono(byte[] data, int offset, int channels, boolean bigEndian) {
        int sum = 0;
        for (int channel = 0; channel < channels; channel++) {
            int index = offset + channel * 2;
            int low = data[index] & 0xFF;
            int high = data[index + 1];
            sum += bigEndian ? ((data[index] << 8) | (data[index + 1] & 0xFF)) : ((high << 8) | low);
        }
        // Divide by the full-scale value of a signed 16-bit sample.
        return (float) (sum / (double) channels / 32768.0);
    }

    private static float[] grow(float[] array) {
        float[] bigger = new float[array.length * 2];
        System.arraycopy(array, 0, bigger, 0, array.length);
        return bigger;
    }

    /** Thrown when no provider on the classpath can read the file. */
    public static final class UnsupportedAudioException extends RuntimeException {
        UnsupportedAudioException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
