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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TakeBundleTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final byte[] AUDIO = {1, 2, 3, 4, 5, 6, 7, 8};

    private File wav() throws IOException {
        File wav = temp.newFile("take.wav");
        Files.write(wav.toPath(), AUDIO);
        return wav;
    }

    @Test
    public void aFullBundleHoldsAllFourEntriesUnderTheTakesName() throws IOException {
        File score = temp.newFile("take.score.json");
        Files.write(score.toPath(), "{\"score\":true}".getBytes(StandardCharsets.UTF_8));
        File zip = new File(temp.getRoot(), "take.zip");

        TakeBundle.write(zip, "take", wav(), score, "| C | G |", "take  ·  0:01\n");

        try (ZipFile in = new ZipFile(zip)) {
            assertEquals(4, in.size());
            assertArrayEquals(AUDIO, bytesOf(in, "take.wav"));
            assertEquals("| C | G |", textOf(in, "take.chords.txt"));
            assertEquals("{\"score\":true}", textOf(in, "take.score.json"));
            assertEquals("take  ·  0:01\n", textOf(in, "take.info.txt"));
        }
    }

    /**
     * A take never analysed still bundles: the recording is the ground truth,
     * and the analysis can be run on the desktop.
     */
    @Test
    public void anUnanalysedTakeBundlesAsAudioAndInfoAlone() throws IOException {
        File zip = new File(temp.getRoot(), "take.zip");

        TakeBundle.write(zip, "take", wav(),
                new File(temp.getRoot(), "absent.score.json"), null, "info\n");

        try (ZipFile in = new ZipFile(zip)) {
            assertEquals(2, in.size());
            assertArrayEquals(AUDIO, bytesOf(in, "take.wav"));
            assertNull(in.getEntry("take.chords.txt"));
            assertNull(in.getEntry("take.score.json"));
        }
    }

    /**
     * Nothing half-written survives a failure: the share sheet would offer a
     * zip that does not open on the other side.
     */
    @Test
    public void aFailedWriteLeavesNoFileBehind() {
        File zip = new File(temp.getRoot(), "take.zip");

        assertThrows(IOException.class, () -> TakeBundle.write(
                zip, "take", new File(temp.getRoot(), "no-such.wav"), null, "chart", "info"));

        assertFalse("a half-written bundle must be deleted, not offered", zip.exists());
        assertFalse("nor left behind under the temp name",
                new File(temp.getRoot(), "take.zip.tmp").exists());
    }

    /**
     * A failure must not take out the previous share's good bundle either —
     * the write goes beside it and only a success replaces it.
     */
    @Test
    public void aFailedWriteLeavesThePreviousBundleIntact() throws IOException {
        File zip = new File(temp.getRoot(), "take.zip");
        TakeBundle.write(zip, "take", wav(), null, "the good chart", null);

        assertThrows(IOException.class, () -> TakeBundle.write(
                zip, "take", new File(temp.getRoot(), "no-such.wav"), null, null, null));

        try (ZipFile in = new ZipFile(zip)) {
            assertEquals("the good chart", textOf(in, "take.chords.txt"));
        }
    }

    /**
     * The recording's entry carries the file's own time — until a rename, the
     * take's default name is its date, and nothing else in the zip has one.
     */
    @Test
    public void theAudioEntryCarriesTheRecordingsOwnTime() throws IOException {
        File wav = wav();
        long recorded = 1_700_000_000_000L;
        assertTrue(wav.setLastModified(recorded));
        File zip = new File(temp.getRoot(), "take.zip");

        TakeBundle.write(zip, "take", wav, null, null, null);

        try (ZipFile in = new ZipFile(zip)) {
            long entryTime = in.getEntry("take.wav").getTime();
            // Within DOS-time resolution, in case the extended timestamp is absent.
            assertTrue("entry time " + entryTime + " should be about " + recorded,
                    Math.abs(entryTime - recorded) <= 2_000);
        }
    }

    /** The take's name, not a fixed one, names the entries — a chart pulled out beside another take's stays identifiable. */
    @Test
    public void entriesCarryTheTakesName() throws IOException {
        File zip = new File(temp.getRoot(), "bundle.zip");

        TakeBundle.write(zip, "wednesday-blues", wav(), null, "chart", null);

        try (ZipFile in = new ZipFile(zip)) {
            assertEquals("chart", textOf(in, "wednesday-blues.chords.txt"));
            assertArrayEquals(AUDIO, bytesOf(in, "wednesday-blues.wav"));
        }
    }

    private static byte[] bytesOf(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new AssertionError("no entry called " + name + " in " + zip.getName());
        }
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String textOf(ZipFile zip, String name) throws IOException {
        return new String(bytesOf(zip, name), StandardCharsets.UTF_8);
    }
}
