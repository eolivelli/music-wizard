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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The WAV header, written and read back.
 *
 * <p>This is the app's replacement for {@code AudioDecoder}, which cannot run
 * on Android, so nothing else checks it: an error here is silent, and shows up
 * as an analysis of the wrong audio rather than as a failure.
 */
public class WavFileTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Bytes the recorder writes, read back by the reader that opens takes. */
    @Test
    public void headerRoundTripsThroughTheReader() throws IOException {
        File wav = folder.newFile("take.wav");
        short[] samples = {0, 1000, -1000, 32767, -32768, 7};
        writeWav(wav, 44_100, 1, samples);

        WavFile.Format format = WavFile.readFormat(wav);
        assertEquals(44_100, format.sampleRate());
        assertEquals(1, format.channels());
        assertEquals(16, format.bitsPerSample());
        assertEquals(samples.length * 2L, format.dataBytes());
        assertEquals(samples.length, format.frames());
        assertEquals(samples.length / 44_100.0, format.durationSeconds(), 1e-12);

        AudioBuffer buffer = WavFile.read(wav);
        assertEquals(44_100, buffer.sampleRate());
        assertEquals(samples.length, buffer.length());
        float[] expected = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            expected[i] = (float) (samples[i] / 32768.0);
        }
        assertArrayEquals(expected, buffer.samples(), 0f);
    }

    /** The header the recorder writes is exactly the canonical 44 bytes. */
    @Test
    public void headerHasTheCanonicalLayout() {
        byte[] header = WavFile.header(22_050, 2, 400);
        assertEquals(WavFile.HEADER_BYTES, header.length);
        assertEquals("RIFF", new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(header, 12, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("data", new String(header, 36, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(16, intAt(header, 16));
        assertEquals(1, shortAt(header, 20));
        assertEquals(2, shortAt(header, 22));
        assertEquals(22_050, intAt(header, 24));
        // byteRate = rate * channels * bytesPerSample, blockAlign = 4 for
        // 16-bit stereo. Both are derived, and both are what other tools read
        // to decide how long the file is.
        assertEquals(22_050 * 4, intAt(header, 28));
        assertEquals(4, shortAt(header, 32));
        assertEquals(16, shortAt(header, 34));
        assertEquals(400, intAt(header, 40));
        // RIFF size counts everything after its own four bytes.
        assertEquals(WavFile.HEADER_BYTES - 8 + 400, intAt(header, 4));
    }

    /** {@code patchSizes} rewrites both lengths and touches nothing else. */
    @Test
    public void patchSizesFillsInTheLengthTheRecorderLearnsLast() throws IOException {
        File wav = folder.newFile("patched.wav");
        byte[] audio = new byte[64];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) i;
        }
        try (RandomAccessFile file = new RandomAccessFile(wav, "rw")) {
            file.write(WavFile.header(44_100, 1, 0));
            file.write(audio);
            WavFile.patchSizes(file, audio.length);
        }

        byte[] expected = WavFile.header(44_100, 1, audio.length);
        byte[] actual = new byte[WavFile.HEADER_BYTES];
        try (RandomAccessFile file = new RandomAccessFile(wav, "r")) {
            file.readFully(actual);
        }
        assertArrayEquals(expected, actual);
        assertEquals(audio.length, WavFile.readFormat(wav).dataBytes());
    }

    /**
     * A take whose process died keeps a zero length in its header.
     *
     * <p>The file is still full of audio, and losing it would mean losing a
     * performance that cannot be repeated, so the reader falls back to the
     * bytes that are actually there.
     */
    @Test
    public void anUnpatchedHeaderStillReadsTheAudioThatIsThere() throws IOException {
        File wav = folder.newFile("killed.wav");
        try (OutputStream out = new FileOutputStream(wav)) {
            out.write(WavFile.header(44_100, 1, 0));
            out.write(new byte[] {0x00, 0x40, 0x00, (byte) 0xC0});
        }
        AudioBuffer buffer = WavFile.read(wav);
        assertEquals(2, buffer.length());
        assertEquals(0.5f, buffer.samples()[0], 1e-6f);
        assertEquals(-0.5f, buffer.samples()[1], 1e-6f);
    }

    /** A declared length longer than the file does not invent silence. */
    @Test
    public void aTruncatedFileYieldsOnlyTheSamplesItHas() throws IOException {
        File wav = folder.newFile("truncated.wav");
        try (OutputStream out = new FileOutputStream(wav)) {
            out.write(WavFile.header(44_100, 1, 1000));
            out.write(new byte[] {0x00, 0x40});
        }
        assertEquals(1, WavFile.read(wav).length());
    }

    /** Chunks the app does not write, in files it might be handed. */
    @Test
    public void chunksBeforeTheAudioAreSkipped() throws IOException {
        File wav = folder.newFile("with-list.wav");
        byte[] header = WavFile.header(8000, 1, 4);
        try (OutputStream out = new FileOutputStream(wav)) {
            // RIFF/WAVE, then an odd-length LIST chunk (which carries a pad
            // byte that is not counted in its size), then fmt and data.
            out.write(header, 0, 12);
            out.write(new byte[] {'L', 'I', 'S', 'T', 3, 0, 0, 0, 'a', 'b', 'c', 0});
            out.write(header, 12, WavFile.HEADER_BYTES - 12);
            out.write(new byte[] {0x00, 0x40, 0x00, (byte) 0xC0});
        }
        AudioBuffer buffer = WavFile.read(wav);
        assertEquals(8000, buffer.sampleRate());
        assertEquals(2, buffer.length());
        assertEquals(0.5f, buffer.samples()[0], 1e-6f);
    }

    /** Stereo is averaged, not half-dropped — the way {@code AudioDecoder} does it. */
    @Test
    public void stereoIsMixedDownToMono() throws IOException {
        File wav = folder.newFile("stereo.wav");
        writeWav(wav, 44_100, 2, new short[] {16384, 0, -16384, -16384});
        AudioBuffer buffer = WavFile.read(wav);
        assertEquals(2, buffer.length());
        assertEquals(0.25f, buffer.samples()[0], 1e-6f);
        assertEquals(-0.5f, buffer.samples()[1], 1e-6f);
    }

    @Test
    public void aFileThatIsNotWaveIsRejected() throws IOException {
        File wav = folder.newFile("not-a-wav.wav");
        try (OutputStream out = new FileOutputStream(wav)) {
            // An MP3 with an ID3 tag: the wrong kind of file to hand this reader,
            // and one a file manager will happily let someone pick.
            out.write("ID3 a tagged mp3, not a wave"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        try {
            WavFile.read(wav);
            fail("expected a file that is not RIFF/WAVE to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("RIFF"));
        }
    }

    @Test
    public void compressedWavIsRejectedRatherThanReadAsNoise() throws IOException {
        File wav = folder.newFile("alaw.wav");
        byte[] header = WavFile.header(8000, 1, 2);
        // Format tag 6 is A-law. Reading its bytes as PCM would produce audio
        // that is wrong rather than absent, which is the expensive kind of wrong.
        header[20] = 6;
        try (OutputStream out = new FileOutputStream(wav)) {
            out.write(header);
            out.write(new byte[] {0x11, 0x22});
        }
        try {
            WavFile.read(wav);
            fail("expected a non-PCM file to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("PCM"));
        }
    }

    /** Writes a canonical file the way {@code Recorder} does: header, then samples. */
    private static void writeWav(File file, int rate, int channels, short[] samples)
            throws IOException {
        byte[] audio = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            audio[2 * i] = (byte) (samples[i] & 0xFF);
            audio[2 * i + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(WavFile.header(rate, channels, audio.length));
            out.write(audio);
        }
    }

    private static int intAt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int shortAt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
