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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The arithmetic the decoder hands off, tested where a JVM can reach it.
 *
 * <p>{@code AudioImport} cannot be tested here — {@code MediaCodec} is a stub
 * under {@code returnDefaultValues} and there is no emulator in this build —
 * which is exactly why the downmix and the byte order live in this class and
 * not in that one.
 */
public class WavWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The rate is the decoder's, not a constant.
     *
     * <p>Opus decodes at 48000 by definition and most AAC at 44100, and both are
     * written as they arrive: {@code MwAnalysis} resamples from either, and
     * converting in between would be a lossy pass to reach a rate nothing uses.
     */
    @Test
    public void theHeaderStatesWhateverRateItWasGiven() throws Exception {
        for (int rate : new int[] {44_100, 48_000, 22_050}) {
            File wav = folder.newFile("rate-" + rate + ".wav");
            try (WavWriter writer = new WavWriter(wav, rate)) {
                writer.write(new short[] {1, 2, 3, 4}, 4, 1);
                writer.finish();
            }
            assertEquals(rate, WavFile.readFormat(wav).sampleRate());
            assertEquals(1, WavFile.readFormat(wav).channels());
        }
    }

    @Test
    public void theSizesArePatchedSoTheFileStatesItsOwnLength() throws Exception {
        File wav = folder.newFile("sized.wav");
        try (WavWriter writer = new WavWriter(wav, 44_100)) {
            writer.write(new short[100], 100, 1);
            writer.finish();
        }
        WavFile.Format format = WavFile.readFormat(wav);
        assertEquals(200, format.dataBytes());
        assertEquals(100, format.frames());
        assertEquals(WavFile.HEADER_BYTES + 200, wav.length());
    }

    @Test
    public void whatIsWrittenIsWhatIsReadBack() throws Exception {
        short[] samples = {0, 1, -1, 32_767, -32_768, 1234, -4321};
        File wav = folder.newFile("mono.wav");
        try (WavWriter writer = new WavWriter(wav, 44_100)) {
            writer.write(samples, samples.length, 1);
            writer.finish();
        }

        AudioBuffer read = WavFile.read(wav);
        assertEquals(samples.length, read.samples().length);
        for (int i = 0; i < samples.length; i++) {
            assertEquals(samples[i] / 32_768.0, read.samples()[i], 1e-6);
        }
    }

    /**
     * Stereo is averaged, and the result matches what {@code WavFile} would have
     * produced from the same audio written as stereo.
     *
     * <p>Not bit-for-bit: this averages in {@code int} and rounds once, where
     * {@code WavFile.read} averages in {@code double} after converting. The gap
     * is at most half a least-significant bit — two orders below the threshold
     * {@code AudioBuffer} calls silence — and the tolerance here says so rather
     * than hiding it.
     */
    @Test
    public void stereoIsAveragedTheWayTheRestOfTheAppAverages() throws Exception {
        short[] interleaved = {100, 200, -300, -400, 32_767, 32_767, 0, 1};
        int frames = interleaved.length / 2;

        File mono = folder.newFile("downmixed.wav");
        try (WavWriter writer = new WavWriter(mono, 44_100)) {
            writer.write(interleaved, frames, 2);
            writer.finish();
        }

        File stereo = folder.newFile("stereo.wav");
        writeStereo(stereo, interleaved);

        float[] ours = WavFile.read(mono).samples();
        float[] theirs = WavFile.read(stereo).samples();
        assertEquals(frames, ours.length);
        assertEquals(frames, theirs.length);
        for (int i = 0; i < frames; i++) {
            // Half a least-significant bit, stated as the number it is.
            assertEquals("frame " + i, theirs[i], ours[i], 0.5 / 32_768.0);
        }
    }

    /** Averaging must not drift a signal downward over a whole take. */
    @Test
    public void aHalfWayAverageRoundsAwayFromZeroInBothDirections() throws Exception {
        File wav = folder.newFile("rounding.wav");
        try (WavWriter writer = new WavWriter(wav, 44_100)) {
            // (1 + 2)/2 and (-1 + -2)/2 are both exactly half way.
            writer.write(new short[] {1, 2, -1, -2}, 2, 2);
            writer.finish();
        }
        float[] samples = WavFile.read(wav).samples();
        assertEquals(2 / 32_768.0, samples[0], 1e-9);
        assertEquals(-2 / 32_768.0, samples[1], 1e-9);
    }

    @Test
    public void moreThanTwoChannelsIsRefusedByName() throws Exception {
        File wav = folder.newFile("surround.wav");
        try (WavWriter writer = new WavWriter(wav, 48_000)) {
            IOException refused = assertThrows(IOException.class,
                    () -> writer.write(new short[12], 2, 6));
            assertTrue(refused.getMessage(), refused.getMessage().contains("6 channels"));
        }
    }

    /** A decode that failed halfway must not leave a take behind. */
    @Test
    public void anAbortedWriteLeavesNothing() throws Exception {
        File wav = new File(folder.getRoot(), "abandoned.wav");
        WavWriter writer = new WavWriter(wav, 44_100);
        writer.write(new short[100], 100, 1);
        assertTrue(wav.exists());

        writer.abort();
        assertFalse(wav.exists());
    }

    /**
     * A take with no audio is still a readable file.
     *
     * <p>It reads as zero frames rather than as a corrupt header, so the failure
     * shows up as an empty take rather than as a library that cannot be listed.
     */
    @Test
    public void aFileWithNoAudioIsStillValid() throws Exception {
        File wav = folder.newFile("empty.wav");
        try (WavWriter writer = new WavWriter(wav, 44_100)) {
            writer.finish();
        }
        WavFile.Format format = WavFile.readFormat(wav);
        assertEquals(0, format.dataBytes());
        assertEquals(0, format.frames());
        assertEquals(0, WavFile.read(wav).samples().length);
    }

    private static void writeStereo(File target, short[] interleaved) throws IOException {
        byte[] header = WavFile.header(44_100, 2, interleaved.length * 2L);
        byte[] body = new byte[interleaved.length * 2];
        for (int i = 0; i < interleaved.length; i++) {
            body[i * 2] = (byte) (interleaved[i] & 0xFF);
            body[i * 2 + 1] = (byte) ((interleaved[i] >> 8) & 0xFF);
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            out.write(header);
            out.write(body);
        }
    }
}
