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

import android.media.MediaCodec;
import android.media.MediaFormat;
import dev.olivelli.musicwizard.android.mw.WavFile;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A take, compressed to FLAC by the platform encoder before it is uploaded.
 *
 * <p>Lossless is not a preference here: a take is corpus material, and the
 * desktop pipeline will be run against these files after every change to it.
 * FLAC roughly halves a recording and costs nothing in fidelity, and
 * {@code MediaCodec} means no new library on either side — the desktop already
 * decodes FLAC.
 *
 * <p>There is no FLAC muxer in the platform, so the container is assembled here,
 * which is less than it sounds: a FLAC file is its header followed by its
 * frames, and the encoder emits both. The header arrives once, flagged
 * {@code BUFFER_FLAG_CODEC_CONFIG} or carried as {@code csd-0} on the output
 * format depending on the codec, and both are handled because which one a device
 * does is not something this app can pin down.
 *
 * <p>The one thing it deliberately does not write is the total sample count and
 * MD5 in {@code STREAMINFO}: the encoder emits the header before it has seen the
 * audio, and the file is streamed straight to disk, so those stay zero. That is
 * legal FLAC — a decoder reads the frames — and it is what any streaming encoder
 * produces.
 */
final class FlacEncoder {

    /** How long to wait on the codec before going round the loop again. */
    private static final long DEQUEUE_TIMEOUT_MICROS = 10_000;

    /**
     * The encoder's own default is 5; this asks for it explicitly because the
     * key is required and an unset one is a configure failure on some devices.
     */
    private static final int COMPRESSION_LEVEL = 5;

    private FlacEncoder() {
    }

    /**
     * Encodes {@code wav} into {@code flac}, replacing whatever was there.
     *
     * @throws IOException when the WAV cannot be read, or when this device has
     *                     no FLAC encoder — required of Android 10 and later,
     *                     and this app runs from Android 8
     */
    static void encode(File wav, File flac) throws IOException {
        try (WavFile.Pcm pcm = WavFile.openPcm(wav);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(flac), 1 << 16)) {
            WavFile.Format format = pcm.format();
            MediaFormat requested = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_FLAC, format.sampleRate(), format.channels());
            requested.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, COMPRESSION_LEVEL);

            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC);
            try {
                codec.configure(requested, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
                pump(codec, pcm, out);
            } catch (IllegalStateException | IllegalArgumentException e) {
                // MediaCodec reports a codec that refused the format, or one
                // that died mid-stream, as an unchecked exception; to a caller
                // deciding whether to fall back to the WAV it is an I/O failure
                // like any other.
                throw new IOException("the FLAC encoder failed: " + e, e);
            } finally {
                codec.release();
            }
        }
    }

    /** Feeds PCM in and writes what comes out, until the codec says it is done. */
    private static void pump(MediaCodec codec, WavFile.Pcm pcm, OutputStream out)
            throws IOException {
        WavFile.Format format = pcm.format();
        InputStream audio = pcm.stream();
        int frameBytes = format.channels() * WavFile.BITS_PER_SAMPLE / 8;
        long remaining = format.dataBytes();
        long framesFed = 0;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] staging = new byte[1 << 16];
        byte[] header = null;
        boolean wroteAnything = false;
        boolean inputDone = false;

        while (true) {
            if (!inputDone) {
                int index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_MICROS);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    buffer.clear();
                    // Whole frames only: half a stereo frame would shift every
                    // sample after it into the wrong channel.
                    int want = (int) Math.min(
                            Math.min(buffer.remaining(), staging.length) / frameBytes * frameBytes,
                            remaining);
                    int read = want > 0 ? readFully(audio, staging, want) : 0;
                    if (read <= 0) {
                        codec.queueInputBuffer(index, 0, 0,
                                presentationMicros(framesFed, format.sampleRate()),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        buffer.put(staging, 0, read);
                        codec.queueInputBuffer(index, 0, read,
                                presentationMicros(framesFed, format.sampleRate()), 0);
                        remaining -= read;
                        framesFed += read / frameBytes;
                    }
                }
            }

            int index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_MICROS);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ByteBuffer csd = codec.getOutputFormat().getByteBuffer("csd-0");
                if (csd != null) {
                    header = bytesOf(csd);
                }
            } else if (index >= 0) {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    header = bytesOf(buffer);
                } else if (info.size > 0) {
                    if (!wroteAnything) {
                        if (header == null) {
                            throw new IOException("the FLAC encoder produced frames but no"
                                    + " stream header, so the file would not be readable");
                        }
                        out.write(header);
                        wroteAnything = true;
                    }
                    out.write(bytesOf(buffer));
                }
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
        if (!wroteAnything) {
            throw new IOException("the FLAC encoder produced no audio");
        }
    }

    /** Where in the take this buffer starts. FLAC is not timed, but MediaCodec wants it. */
    private static long presentationMicros(long frames, int sampleRate) {
        return sampleRate > 0 ? frames * 1_000_000L / sampleRate : 0;
    }

    private static byte[] bytesOf(ByteBuffer buffer) {
        byte[] copy = new byte[buffer.remaining()];
        buffer.get(copy);
        return copy;
    }

    /** Reads exactly {@code count} bytes, or fewer only at the end of the audio. */
    private static int readFully(InputStream in, byte[] into, int count) throws IOException {
        int read = 0;
        while (read < count) {
            int step = in.read(into, read, count - read);
            if (step < 0) {
                break;
            }
            read += step;
        }
        return read;
    }
}
