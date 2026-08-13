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

package dev.olivelli.musicwizard.android;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import dev.olivelli.musicwizard.android.mw.WavWriter;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.function.BooleanSupplier;

/**
 * Decodes a downloaded container into a take.
 *
 * <p>{@code Recorder}'s opposite number: that one turns the microphone into a
 * WAV, this one turns an MP4 or WebM into the same thing. It is the only part of
 * the import that must touch the framework, which is why it holds nothing but
 * framework calls — the arithmetic is in {@link WavWriter}, where the JVM can
 * test it. There is no emulator in this build, so what is left here is checked
 * by running the app; {@code android/README.md} carries the list.
 *
 * <p>{@code AudioDecoder} in {@code mw-audio} cannot be used and never could:
 * it decodes through {@code javax.sound}, which Android does not have, and its
 * MP3/AAC provider is excluded from the APK by the build file.
 */
final class AudioImport {

    /** How long a decode may run, as a multiple of the audio's own length. */
    private static final int STALL_FACTOR = 4;

    /** A floor for very short files, where the multiple above is meaningless. */
    private static final long MIN_DEADLINE_MILLIS = 30_000;

    private static final long DEQUEUE_TIMEOUT_MICROS = 10_000;

    /** Past anything Fetch will hand over, and small enough that scaling it cannot overflow. */
    private static final long MAX_DURATION_MILLIS = 6L * 60 * 60 * 1000;

    private AudioImport() {
    }

    /** Told how far along the decode is, 0..1. */
    interface Progress {
        void onProgress(double fraction);
    }

    /**
     * Decodes {@code source} into a mono WAV at {@code target}.
     *
     * <p>Whole or not at all: a partial decode is removed rather than left, since
     * a truncated take analyses without complaint and looks like a short song.
     *
     * @return the sample rate written, which is the decoder's own
     */
    static int decodeToWav(File source, File target, Progress progress,
            BooleanSupplier cancelled) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        WavWriter writer = null;
        MediaCodec codec = null;
        boolean done = false;
        try {
            extractor.setDataSource(source.getPath());
            int track = audioTrack(extractor);
            extractor.selectTrack(track);

            MediaFormat input = extractor.getTrackFormat(track);
            long durationMicros = input.containsKey(MediaFormat.KEY_DURATION)
                    ? input.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = input.getString(MediaFormat.KEY_MIME);

            // The track format is passed through untouched on purpose. It carries
            // the codec-specific data — csd-0 for AAC, and for Opus csd-1 and
            // csd-2 as well, which are its pre-skip and seek pre-roll. Rebuilding
            // the format by hand is the classic way to get an Opus decoder that
            // produces noise instead of music.
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(input, null, null, 0);
            codec.start();

            writer = decode(extractor, codec, target, durationMicros, progress, cancelled);
            writer.finish();
            done = true;
            return writer.sampleRate();
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // Already stopped, or never started; release still matters.
                }
                codec.release();
            }
            extractor.release();
            if (writer != null) {
                if (done) {
                    writer.close();
                } else {
                    writer.abort();
                }
            }
        }
    }

    private static WavWriter decode(MediaExtractor extractor, MediaCodec codec, File target,
            long durationMicros, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        WavWriter writer = null;
        boolean inputDone = false;
        boolean outputDone = false;
        int channels = 0;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;

        // Clamped before it is scaled: a container declaring a nonsense duration
        // would otherwise overflow into a deadline already in the past, and every
        // decode would fail at the first check with a message about progress.
        long declaredMillis = Math.max(0, Math.min(durationMicros / 1000, MAX_DURATION_MILLIS));
        long deadline = System.nanoTime() / 1_000_000L
                + Math.max(MIN_DEADLINE_MILLIS, declaredMillis * STALL_FACTOR);

        try {
            while (!outputDone) {
                if (cancelled.getAsBoolean()) {
                    throw new InterruptedIOException("the import was cancelled");
                }
                if (System.nanoTime() / 1_000_000L > deadline) {
                    // A decoder that never reports end-of-stream would otherwise
                    // hold the worker thread for the life of the process.
                    throw new IOException("the decoder stopped making progress");
                }

                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_MICROS);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        if (buffer == null) {
                            // Not end-of-stream. Treating it as one would queue
                            // EOS and finish early, producing a take that is
                            // short and looks complete -- which is the outcome
                            // this class exists to avoid.
                            throw new IOException("the decoder gave up a buffer");
                        }
                        int read = extractor.readSampleData(buffer, 0);
                        if (read < 0) {
                            // Queued exactly once; a second end-of-stream is an
                            // IllegalStateException on some devices.
                            codec.queueInputBuffer(index, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, read,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_MICROS);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Authoritative, and not the same as the track format: this is
                    // what the decoder will actually emit.
                    MediaFormat output = codec.getOutputFormat();
                    int rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = output.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            : AudioFormat.ENCODING_PCM_16BIT;
                    if (channels < 1 || channels > 2) {
                        throw new IOException("this audio has " + channels
                                + " channels, and the app reads mono and stereo");
                    }
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT
                            && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        throw new IOException("the decoder produced a sample format"
                                + " this app cannot read");
                    }
                    if (writer != null) {
                        // A second format change mid-stream. Opening another
                        // writer would leak this one and, because the constructor
                        // truncates, throw away everything decoded so far -- a
                        // take that is quietly the tail of the song.
                        throw new IOException("the audio changes format partway through");
                    }
                    writer = new WavWriter(target, rate);
                    continue;
                }
                if (index < 0) {
                    continue;
                }

                ByteBuffer buffer = codec.getOutputBuffer(index);
                boolean payload = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0 && buffer != null && writer != null;
                if (payload) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    writeInto(writer, buffer, channels, encoding);
                }
                codec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                } else if (durationMicros > 0 && progress != null) {
                    long at = extractor.getSampleTime();
                    progress.onProgress(at < 0 ? 1 : Math.min(1, at / (double) durationMicros));
                }
            }

            if (writer == null) {
                throw new IOException("the decoder produced no audio");
            }
            return writer;
        } catch (IOException | RuntimeException failure) {
            if (writer != null) {
                writer.abort();
            }
            throw failure;
        }
    }

    private static void writeInto(WavWriter writer, ByteBuffer buffer, int channels, int encoding)
            throws IOException {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            // Some devices hand back float for high-resolution content. Converting
            // is better than refusing: a refusal here would be a failure the user
            // sees on one phone and not another, with nothing to act on.
            FloatBuffer floats = buffer.asFloatBuffer();
            short[] samples = new short[floats.remaining()];
            for (int i = 0; i < samples.length; i++) {
                float value = floats.get(i);
                value = Math.max(-1f, Math.min(1f, value));
                samples[i] = (short) Math.round(value * Short.MAX_VALUE);
            }
            writer.write(samples, samples.length / channels, channels);
            return;
        }
        ShortBuffer shorts = buffer.asShortBuffer();
        short[] samples = new short[shorts.remaining()];
        shorts.get(samples);
        writer.write(samples, samples.length / channels, channels);
    }

    private static int audioTrack(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        throw new IOException("the download holds no audio track");
    }
}
