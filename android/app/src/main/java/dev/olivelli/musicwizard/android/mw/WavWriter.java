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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Streams decoded PCM into a mono WAV.
 *
 * <p>The counterpart to {@code Recorder}, which does the same thing for the
 * microphone: header first with the length unknown, audio appended, sizes
 * patched at the end. It exists as its own class so the arithmetic — the
 * downmix and the byte order — is tested on the JVM, leaving the decoder that
 * feeds it holding nothing but framework calls.
 *
 * <p>The rate is whatever the decoder produced. Nothing here resamples: an
 * import arrives at 48000 when it is Opus and usually 44100 when it is AAC,
 * {@code MwAnalysis} resamples to the analysis rate from either, and converting
 * in between would be a second pass over the audio to reach a rate nothing
 * uses.
 */
public final class WavWriter implements Closeable {

    private final File target;
    private final int sampleRate;
    private RandomAccessFile out;
    private long dataBytes;
    private boolean finished;

    /** Opens {@code target} and writes the header, with the length still unknown. */
    public WavWriter(File target, int sampleRate) throws IOException {
        this.target = target;
        this.sampleRate = sampleRate;
        this.out = new RandomAccessFile(target, "rw");
        try {
            out.setLength(0);
            out.write(WavFile.header(sampleRate, 1, 0));
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public int sampleRate() {
        return sampleRate;
    }

    public long frames() {
        return dataBytes / 2;
    }

    /**
     * Writes {@code frames} frames of interleaved 16-bit PCM, mixed to mono.
     *
     * <p>Channels are averaged rather than dropped, matching {@code WavFile.read}
     * and the desktop decoder, so the same audio analysed on either side sees
     * the same samples. The average is taken in {@code int} and rounded once,
     * where {@code WavFile} averages in {@code double} after converting; that
     * can differ by half a least-significant bit, which is two orders below the
     * threshold {@code AudioBuffer} calls silence and well inside what the
     * resampling downstream moves anyway.
     */
    public void write(short[] interleaved, int frames, int channels) throws IOException {
        if (channels < 1 || channels > 2) {
            throw new IOException("only mono and stereo can be written, not "
                    + channels + " channels");
        }
        byte[] mono = new byte[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            int sample;
            if (channels == 1) {
                sample = interleaved[frame];
            } else {
                int left = interleaved[frame * 2];
                int right = interleaved[frame * 2 + 1];
                int sum = left + right;
                // Away from zero, so a steady half-LSB does not drift the signal
                // toward negative over a whole take.
                sample = sum >= 0 ? (sum + 1) / 2 : (sum - 1) / 2;
            }
            if (sample > Short.MAX_VALUE) {
                sample = Short.MAX_VALUE;
            } else if (sample < Short.MIN_VALUE) {
                sample = Short.MIN_VALUE;
            }
            mono[frame * 2] = (byte) (sample & 0xFF);
            mono[frame * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        out.write(mono);
        dataBytes += mono.length;
    }

    /**
     * Patches the header so the file states its own length.
     *
     * <p>Separate from {@link #close}, so that a take abandoned halfway is closed
     * without being finished and {@link #abort} can take the file away.
     */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        WavFile.patchSizes(out, dataBytes);
        finished = true;
    }

    @Override
    public void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // Nothing useful to do: either finish() already patched the
                // header, or abort() is about to remove the file.
            }
            out = null;
        }
    }

    /** Closes and removes the file, for a decode that did not get to the end. */
    public void abort() {
        close();
        if (target.exists() && !target.delete()) {
            target.deleteOnExit();
        }
    }
}
