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
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.android.mw.WavFile;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * {@code AudioRecord} to a WAV file, with a level to show while it runs.
 *
 * <p>PCM 16-bit mono at 44100 Hz: the one rate every Android device is required
 * to support, and the one the owner chose. The header is written by hand
 * (see {@link WavFile}) because the platform's own WAV writers are wrapped
 * around encoders the analysis has no use for.
 *
 * <p>Threading: the capture loop owns the file and the {@code AudioRecord}, and
 * touches nothing else; every callback is posted to the main looper. So the
 * activity above never sees a field written by another thread.
 */
final class Recorder {

    /** Callbacks, all delivered on the main thread. */
    interface Listener {

        /** Peak absolute amplitude of the last block, 0..1. */
        void onLevel(float peak);

        /** The take finished and its header was patched. */
        void onFinished(File wav, double seconds);

        /** Recording could not start, or died part-way. */
        void onFailed(String message);
    }

    private static final int CHANNELS = 1;

    /**
     * How much audio one read covers, at least.
     *
     * <p>The device's minimum buffer is a floor, not a target: reading in
     * chunks a good deal larger than it costs nothing and leaves room for a
     * scheduling hiccup before the hardware buffer overruns and drops audio.
     */
    private static final int BUFFER_MULTIPLIER = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private volatile boolean stopping;
    private Thread thread;

    Recorder(Listener listener) {
        this.listener = listener;
    }

    boolean isRecording() {
        return thread != null;
    }

    /**
     * Starts capturing into {@code wav}.
     *
     * <p>The caller must already hold {@code RECORD_AUDIO}; without it
     * {@code AudioRecord} yields silence rather than an error on some devices,
     * which is the worst of both.
     */
    void start(File wav) {
        if (thread != null) {
            return;
        }
        int minimum = AudioRecord.getMinBufferSize(
                MwAnalysis.RECORD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            listener.onFailed("this device cannot record 44100 Hz mono PCM");
            return;
        }
        int bufferBytes = minimum * BUFFER_MULTIPLIER;

        AudioRecord created;
        try {
            created = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    MwAnalysis.RECORD_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
        } catch (IllegalArgumentException | SecurityException e) {
            listener.onFailed("could not open the microphone: " + e.getMessage());
            return;
        }
        if (created.getState() != AudioRecord.STATE_INITIALIZED) {
            created.release();
            listener.onFailed("could not open the microphone");
            return;
        }

        stopping = false;
        thread = new Thread(() -> capture(wav, created, bufferBytes), "mw-recorder");
        thread.start();
    }

    /**
     * Asks the capture loop to finish.
     *
     * <p>Returns at once; {@link Listener#onFinished} arrives when the header
     * has been patched and the file is complete.
     */
    void stop() {
        stopping = true;
    }

    private void capture(File wav, AudioRecord source, int bufferBytes) {
        long dataBytes = 0;
        String failure = null;

        try (RandomAccessFile out = new RandomAccessFile(wav, "rw")) {
            out.setLength(0);
            out.write(WavFile.header(MwAnalysis.RECORD_SAMPLE_RATE, CHANNELS, 0));

            source.startRecording();
            byte[] block = new byte[bufferBytes];
            while (!stopping) {
                int read = source.read(block, 0, block.length);
                if (read < 0) {
                    failure = "the microphone stopped (error " + read + ")";
                    break;
                }
                if (read == 0) {
                    continue;
                }
                out.write(block, 0, read);
                dataBytes += read;
                postLevel(peakOf(block, read));
            }
            WavFile.patchSizes(out, dataBytes);
        } catch (IOException e) {
            failure = "could not write the recording: " + e.getMessage();
        } catch (IllegalStateException e) {
            failure = "the microphone could not be started: " + e.getMessage();
        } finally {
            try {
                source.stop();
            } catch (IllegalStateException ignored) {
                // Never started; nothing to stop.
            }
            source.release();
        }

        double seconds = dataBytes
                / (double) (MwAnalysis.RECORD_SAMPLE_RATE * CHANNELS * WavFile.BITS_PER_SAMPLE / 8);
        String message = failure;
        main.post(() -> {
            thread = null;
            if (message != null) {
                listener.onFailed(message);
            } else {
                listener.onFinished(wav, seconds);
            }
        });
    }

    private void postLevel(float peak) {
        main.post(() -> listener.onLevel(peak));
    }

    /** Peak of one block, as a fraction of full scale. */
    private static float peakOf(byte[] block, int length) {
        int peak = 0;
        // Whole frames only: a block always ends on one for mono 16-bit, but
        // reading a stray odd byte as half a sample would spike the meter.
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (block[i + 1] << 8) | (block[i] & 0xFF);
            int magnitude = Math.abs(sample);
            if (magnitude > peak) {
                peak = magnitude;
            }
        }
        return (float) Math.min(1.0, peak / 32768.0);
    }
}
