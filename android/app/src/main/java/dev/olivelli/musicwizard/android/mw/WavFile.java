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

package dev.olivelli.musicwizard.android.mw;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * The 44-byte canonical WAV header, written and read by hand.
 *
 * <p>The desktop tool decodes through {@code javax.sound.sampled}, which does
 * not exist on Android — {@code AudioDecoder} is the only class in the reactor
 * that touches it, and this app must never load it. What replaces it is small
 * because the app only ever meets one format: signed 16-bit little-endian PCM,
 * which is exactly what {@code AudioRecord} produces.
 *
 * <p>Reading is more tolerant than writing, but only along two axes, and it is
 * worth being exact about which: chunks the app does not write ({@code LIST},
 * {@code fact}) are walked past rather than tripped over, and a recording whose
 * process was killed mid-take — leaving the declared sizes never patched — is
 * read from what is actually on disk, because a recording that cannot be opened
 * is a lost take. It is <em>not</em> a general WAV reader: anything that is not
 * 16-bit PCM in one or two channels is refused by name, including
 * {@code WAVE_FORMAT_EXTENSIBLE}, which is how some desktop tools write plain
 * PCM. Nothing imports a foreign file today, and widening this is #260.
 */
public final class WavFile {

    /** Size of the header this class writes. */
    public static final int HEADER_BYTES = 44;

    /** The only sample format the app records or reads. */
    public static final int BITS_PER_SAMPLE = 16;

    /** WAVE format tag for uncompressed PCM. */
    private static final int FORMAT_PCM = 1;

    /** Full scale of a signed 16-bit sample; the divisor {@code AudioDecoder} uses. */
    private static final double FULL_SCALE = 32768.0;

    private WavFile() {
    }

    /**
     * What a file's {@code fmt } and {@code data} chunks say about it.
     *
     * @param sampleRate    frames per second
     * @param channels      1 for the app's own recordings
     * @param bitsPerSample always 16 here; anything else is rejected on read
     * @param dataBytes     length of the audio payload, as the file declares it
     */
    public record Format(int sampleRate, int channels, int bitsPerSample, long dataBytes) {

        /** Frames of audio, whatever the channel count. */
        public long frames() {
            long bytesPerFrame = (long) channels * bitsPerSample / 8;
            return bytesPerFrame > 0 ? dataBytes / bytesPerFrame : 0;
        }

        public double durationSeconds() {
            return sampleRate > 0 ? frames() / (double) sampleRate : 0;
        }
    }

    /**
     * The header for a recording of {@code dataBytes} bytes.
     *
     * <p>A recorder does not know that length until it stops, so it writes this
     * with {@code dataBytes} zero and calls {@link #patchSizes} afterwards. The
     * two sizes in the header are the only fields that depend on it.
     */
    public static byte[] header(int sampleRate, int channels, long dataBytes) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive, got: " + channels);
        }
        if (dataBytes < 0) {
            throw new IllegalArgumentException("dataBytes must not be negative, got: " + dataBytes);
        }
        int blockAlign = channels * BITS_PER_SAMPLE / 8;
        long byteRate = (long) sampleRate * blockAlign;

        byte[] out = new byte[HEADER_BYTES];
        ascii(out, 0, "RIFF");
        // The RIFF size counts everything after this field: the eight-byte
        // "WAVE" + "fmt " ids, the 16-byte fmt body, the "data" id and its
        // size field, and the payload. That is 36 for this fixed layout.
        putInt(out, 4, HEADER_BYTES - 8 + dataBytes);
        ascii(out, 8, "WAVE");
        ascii(out, 12, "fmt ");
        putInt(out, 16, 16);
        putShort(out, 20, FORMAT_PCM);
        putShort(out, 22, channels);
        putInt(out, 24, sampleRate);
        putInt(out, 28, byteRate);
        putShort(out, 32, blockAlign);
        putShort(out, 34, BITS_PER_SAMPLE);
        ascii(out, 36, "data");
        putInt(out, 40, dataBytes);
        return out;
    }

    /**
     * Rewrites the two length fields of an already-written header in place.
     *
     * <p>Separate from {@link #header} because the recorder streams straight to
     * disk and learns the length last. Sizes that stayed zero are not fatal —
     * {@link #read} falls back to the bytes actually present — but a file whose
     * header is right opens in every other tool as well, and getting a take off
     * the phone and into the desktop corpus is the point of the app.
     */
    public static void patchSizes(RandomAccessFile file, long dataBytes) throws IOException {
        byte[] four = new byte[4];
        putInt(four, 0, HEADER_BYTES - 8 + dataBytes);
        file.seek(4);
        file.write(four);
        putInt(four, 0, dataBytes);
        file.seek(40);
        file.write(four);
    }

    /** Reads the format without touching the audio. */
    public static Format readFormat(File wav) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(wav), 1 << 13)) {
            return new Reader(in, wav.length()).readToData();
        }
    }

    /**
     * Reads a file into a mono buffer at the rate the file declares.
     *
     * <p>Channels are averaged rather than dropped, matching {@code AudioDecoder}
     * so that the same take analysed here and on the desktop sees the same
     * samples. Resampling to the analysis rate is {@link MwAnalysis}'s job, not
     * this class's: what comes out here is the file, unaltered.
     */
    public static AudioBuffer read(File wav) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(wav), 1 << 16)) {
            Reader reader = new Reader(in, wav.length());
            Format format = reader.readToData();
            float[] mono = reader.readMono(format);
            return new AudioBuffer(mono, format.sampleRate());
        }
    }

    /**
     * Walks the chunk list of one stream, then decodes the payload.
     *
     * <p>A single pass over a single stream, because the file can be tens of
     * megabytes and the phone should not hold two copies of it.
     */
    private static final class Reader {

        private final InputStream in;
        private final long fileLength;
        private long consumed;

        Reader(InputStream in, long fileLength) {
            this.in = in;
            this.fileLength = fileLength;
        }

        /** Reads chunks up to and including the {@code data} header. */
        Format readToData() throws IOException {
            byte[] riff = readFully(12);
            if (!isAscii(riff, 0, "RIFF") || !isAscii(riff, 8, "WAVE")) {
                throw new IOException("not a RIFF/WAVE file");
            }

            int sampleRate = 0;
            int channels = 0;
            int bits = 0;
            boolean haveFormat = false;

            while (true) {
                byte[] head = readFully(8);
                String id = ascii(head, 0);
                // Unsigned: a chunk longer than 2 GB is not a phone recording,
                // but a size read as a negative int would skip backwards.
                long size = getInt(head, 4) & 0xFFFFFFFFL;

                if ("fmt ".equals(id)) {
                    if (size < 16) {
                        throw new IOException("fmt chunk is too short: " + size + " bytes");
                    }
                    byte[] fmt = readFully(16);
                    int tag = getShort(fmt, 0);
                    channels = getShort(fmt, 2);
                    sampleRate = getInt(fmt, 4);
                    bits = getShort(fmt, 14);
                    if (tag != FORMAT_PCM) {
                        throw new IOException("only uncompressed PCM is supported,"
                                + " but the format tag is " + tag);
                    }
                    if (bits != BITS_PER_SAMPLE) {
                        throw new IOException("only " + BITS_PER_SAMPLE + "-bit samples are"
                                + " supported, but the file declares " + bits);
                    }
                    if (channels < 1 || channels > 2) {
                        throw new IOException("only mono and stereo are supported,"
                                + " but the file declares " + channels + " channels");
                    }
                    if (sampleRate <= 0) {
                        throw new IOException("the file declares a sample rate of " + sampleRate);
                    }
                    haveFormat = true;
                    // Any extension beyond the 16 bytes just read.
                    skip(size - 16 + (size % 2));
                } else if ("data".equals(id)) {
                    if (!haveFormat) {
                        throw new IOException("the data chunk comes before the fmt chunk");
                    }
                    return new Format(sampleRate, channels, bits, available(size));
                } else {
                    // Chunks are padded to an even length, and the pad byte is
                    // not counted in the size; a reader that forgets it lands
                    // one byte into the next chunk id.
                    skip(size + (size % 2));
                }
            }
        }

        /**
         * How many payload bytes there really are.
         *
         * <p>A take whose recording process was killed has a declared size of
         * zero and a file full of audio; a truncated copy has the opposite. The
         * file's own length settles both, and it is only a fallback: a header
         * that was patched normally declares exactly what is there.
         */
        private long available(long declared) {
            long rest = Math.max(0, fileLength - consumed);
            if (declared == 0 || declared > rest) {
                return rest;
            }
            return declared;
        }

        /** Decodes {@code dataBytes} of interleaved PCM into mono floats. */
        float[] readMono(Format format) throws IOException {
            int channels = format.channels();
            int frameBytes = channels * BITS_PER_SAMPLE / 8;
            int frames = (int) Math.min(Integer.MAX_VALUE, format.dataBytes() / frameBytes);
            float[] out = new float[frames];

            byte[] chunk = new byte[1 << 16];
            int written = 0;
            int carry = 0;
            byte[] pending = new byte[4];
            long remaining = (long) frames * frameBytes;

            while (remaining > 0 && written < frames) {
                int want = (int) Math.min(chunk.length, remaining);
                int read = in.read(chunk, 0, want);
                if (read <= 0) {
                    break;
                }
                remaining -= read;
                int offset = 0;
                // A read can split a frame, so finish the partial one first.
                while (carry > 0 && offset < read) {
                    pending[carry++] = chunk[offset++];
                    if (carry == frameBytes) {
                        out[written++] = frameToMono(pending, 0, channels);
                        carry = 0;
                    }
                }
                int usable = ((read - offset) / frameBytes) * frameBytes;
                for (int i = offset; i < offset + usable && written < frames; i += frameBytes) {
                    out[written++] = frameToMono(chunk, i, channels);
                }
                int leftover = read - offset - usable;
                for (int i = 0; i < leftover; i++) {
                    pending[carry++] = chunk[offset + usable + i];
                }
            }

            if (written == frames) {
                return out;
            }
            // Short read: hand back what is there rather than a tail of silence,
            // which would look to the beat tracker like a real pause.
            float[] exact = new float[written];
            System.arraycopy(out, 0, exact, 0, written);
            return exact;
        }

        private static float frameToMono(byte[] data, int offset, int channels) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                int index = offset + channel * 2;
                sum += (data[index + 1] << 8) | (data[index] & 0xFF);
            }
            return (float) (sum / (double) channels / FULL_SCALE);
        }

        private byte[] readFully(int count) throws IOException {
            byte[] buffer = new byte[count];
            int read = 0;
            while (read < count) {
                int step = in.read(buffer, read, count - read);
                if (step < 0) {
                    throw new EOFException("the file ends inside its header");
                }
                read += step;
            }
            consumed += count;
            return buffer;
        }

        private void skip(long count) throws IOException {
            long left = count;
            while (left > 0) {
                long stepped = in.skip(left);
                if (stepped <= 0) {
                    // skip() may legitimately return 0; read one byte to tell a
                    // slow stream from a finished one.
                    if (in.read() < 0) {
                        throw new EOFException("the file ends inside a chunk");
                    }
                    stepped = 1;
                }
                left -= stepped;
            }
            consumed += count;
        }
    }

    private static void ascii(byte[] out, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            out[offset + i] = (byte) text.charAt(i);
        }
    }

    private static String ascii(byte[] data, int offset) {
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            chars[i] = (char) (data[offset + i] & 0xFF);
        }
        return new String(chars);
    }

    private static boolean isAscii(byte[] data, int offset, String expected) {
        return expected.equals(ascii(data, offset));
    }

    private static void putInt(byte[] out, int offset, long value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putShort(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static int getInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int getShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
