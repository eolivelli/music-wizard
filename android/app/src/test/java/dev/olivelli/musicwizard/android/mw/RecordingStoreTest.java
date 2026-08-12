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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.olivelli.musicwizard.android.mw.RecordingStore.Recording;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The recordings directory: naming, listing, renaming and deleting. */
public class RecordingStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private RecordingStore store;

    @Before
    public void setUp() throws IOException {
        store = new RecordingStore(new File(folder.getRoot(), "recordings"));
        store.ensureDirectory();
    }

    /** The name is sortable and legal, and a second take in the same second gets its own. */
    @Test
    public void aNewTakeIsNamedForWhenItStarted() throws IOException {
        Instant when = Instant.parse("2026-08-01T21:34:05Z");
        File first = store.newRecordingFile(when, ZoneId.of("UTC"));
        assertEquals("2026-08-01_21-34-05.wav", first.getName());

        touch(first);
        File second = store.newRecordingFile(when, ZoneId.of("UTC"));
        assertNotEquals(first.getName(), second.getName());
        assertEquals("2026-08-01_21-34-05-2.wav", second.getName());
    }

    /** Newest first: the take just made is the one being looked for. */
    @Test
    public void takesAreListedNewestFirst() throws IOException {
        File older = new File(store.directory(), "older.wav");
        File newer = new File(store.directory(), "newer.wav");
        touch(older);
        touch(newer);
        assertTrue(older.setLastModified(1_000_000L));
        assertTrue(newer.setLastModified(2_000_000L));

        List<Recording> listed = store.list();
        assertEquals(2, listed.size());
        assertEquals("newer", listed.get(0).displayName());
        assertEquals("older", listed.get(1).displayName());
    }

    /** Only WAVs are takes; the analyses and notes beside them are not listed. */
    @Test
    public void cachedAnalysesAreNotListedAsRecordings() throws IOException {
        touch(new File(store.directory(), "take.wav"));
        touch(new File(store.directory(), "take.score.json"));
        touch(new File(store.directory(), "take.notes.txt"));
        List<Recording> listed = store.list();
        assertEquals(1, listed.size());
        assertEquals("take", listed.get(0).displayName());
        assertTrue(listed.get(0).isAnalyzed());
    }

    /**
     * Renaming moves the cached analysis too.
     *
     * <p>The cache is found by the audio's stem. Leaving it behind would lose
     * the analysis and, worse, leave it to be inherited by whatever take is
     * named that next.
     */
    @Test
    public void renamingCarriesTheCachedAnalysisAlong() throws IOException {
        File wav = new File(store.directory(), "2026-08-01_21-34-05.wav");
        touch(wav);
        Recording before = new Recording(wav);
        write(before.scoreFile(), "{\"marker\":1}");

        Recording after = store.rename(before, "G C D strum");
        assertEquals("G C D strum", after.displayName());
        assertTrue(after.wav().isFile());
        assertFalse(wav.isFile());
        assertTrue(after.isAnalyzed());
        assertFalse(before.scoreFile().isFile());
        assertEquals("{\"marker\":1}", read(after.scoreFile()));
    }

    /** The note reads back exactly, and a blanked note removes its file. */
    @Test
    public void aNoteRoundTripsAndABlankOneDeletesTheFile() throws IOException {
        File wav = new File(store.directory(), "take.wav");
        touch(wav);
        Recording recording = new Recording(wav);
        assertEquals("", RecordingStore.readNotes(recording));

        RecordingStore.writeNotes(recording, "G C D, twice round");
        assertEquals("G C D, twice round", RecordingStore.readNotes(recording));

        RecordingStore.writeNotes(recording, "  \n");
        assertFalse("an emptied note must not leave a ghost file",
                recording.notesFile().isFile());
    }

    /**
     * Renaming carries the note too — it is the player's own words, and the
     * one file beside a take that cannot be recomputed if left behind.
     */
    @Test
    public void renamingCarriesTheNoteAlong() throws IOException {
        File wav = new File(store.directory(), "2026-08-01_21-34-05.wav");
        touch(wav);
        Recording before = new Recording(wav);
        RecordingStore.writeNotes(before, "slow blues in G");

        Recording after = store.rename(before, "kitchen blues");
        assertFalse(before.notesFile().isFile());
        assertEquals("slow blues in G", RecordingStore.readNotes(after));
    }

    /** A rename onto an existing take is refused rather than silently merging two. */
    @Test
    public void renamingOntoAnExistingTakeIsRefused() throws IOException {
        File first = new File(store.directory(), "one.wav");
        File second = new File(store.directory(), "two.wav");
        touch(first);
        touch(second);
        try {
            store.rename(new Recording(first), "two");
            fail("expected a rename onto an existing take to be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("two"));
        }
        assertTrue(first.isFile());
    }

    /** Deleting takes the cached analysis and the note with it. */
    @Test
    public void deletingRemovesTheAudioAndItsAnalysis() throws IOException {
        File wav = new File(store.directory(), "take.wav");
        touch(wav);
        Recording recording = new Recording(wav);
        write(recording.scoreFile(), "{}");
        RecordingStore.writeNotes(recording, "a note");

        store.delete(recording);
        assertFalse(wav.isFile());
        assertFalse(recording.scoreFile().isFile());
        assertFalse(recording.notesFile().isFile());
    }

    /**
     * A typed name never escapes the recordings directory.
     *
     * <p>The name reaches the filesystem and then, through the share sheet,
     * another app. Separators are the part that matters.
     */
    @Test
    public void aTypedNameIsReducedToASafeStem() throws IOException {
        // Separators become underscores and the leading dots go, so the result
        // names a file inside the recordings directory and nowhere else.
        assertEquals("_.._etc_passwd", RecordingStore.sanitize("../../etc/passwd"));
        assertEquals("take_1", RecordingStore.sanitize("take:1"));
        assertEquals("hidden", RecordingStore.sanitize(".hidden"));
        assertEquals("trailing", RecordingStore.sanitize("trailing. "));
        assertEquals("already named", RecordingStore.sanitize("  already named.wav  "));
        assertEquals(64, RecordingStore.sanitize("x".repeat(200)).length());
        // Case-insensitively, or "Take.WAV" becomes the file Take.WAV.wav.
        assertEquals("Take", RecordingStore.sanitize("Take.WAV"));
    }

    /**
     * Truncation never cuts a character in half.
     *
     * <p>An emoji is two chars. Cutting between them leaves an unpaired
     * surrogate, and what the filesystem then holds is not the name this method
     * returned — the same mismatch the trailing-dot rule exists to prevent, and
     * it travels out through the share sheet. The ASCII case above cannot see
     * this, because every char there is a whole character.
     */
    @Test
    public void aLongNameIsNotCutThroughTheMiddleOfACharacter() throws IOException {
        // 63 plain characters, then a guitar that would straddle the 64th.
        String typed = "x".repeat(63) + "\uD83C\uDFB8" + "more";
        String stem = RecordingStore.sanitize(typed);

        assertEquals("the pair does not fit, so neither half is taken", 63, stem.length());
        assertFalse("an unpaired surrogate reached the filesystem",
                Character.isHighSurrogate(stem.charAt(stem.length() - 1)));
        assertEquals(stem.length(), stem.codePointCount(0, stem.length()));

        // One character fewer in front and the whole guitar fits.
        String fits = RecordingStore.sanitize("x".repeat(62) + "\uD83C\uDFB8" + "more");
        assertEquals(64, fits.length());
        assertEquals("\uD83C\uDFB8", fits.substring(62));

        // And a real file can be created under it, which is the point.
        File named = new File(store.directory(), fits + RecordingStore.WAV);
        assertTrue(named.createNewFile());
        assertEquals(fits, store.list().get(0).displayName());
    }

    @Test
    public void anEmptyNameIsRefused() {
        for (String name : new String[] {null, "", "   ", "...", ".wav"}) {
            try {
                RecordingStore.sanitize(name);
                fail("expected " + name + " to be refused as a name");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("needs a name"));
            }
        }
    }

    /** The length shown in the library is read from the header, not from the audio. */
    @Test
    public void theListedLengthComesFromTheHeader() throws IOException {
        File wav = new File(store.directory(), "two-seconds.wav");
        int frames = 2 * MwAnalysis.RECORD_SAMPLE_RATE;
        try (OutputStream out = new FileOutputStream(wav)) {
            out.write(WavFile.header(MwAnalysis.RECORD_SAMPLE_RATE, 1, frames * 2L));
            out.write(new byte[frames * 2]);
        }
        assertEquals(2.0, new Recording(wav).durationSeconds(), 1e-9);
        assertEquals("2:05", RecordingStore.formatDuration(125));
        assertEquals("0:00", RecordingStore.formatDuration(0));
    }

    /** A file that is not a WAV at all is listed with a length of zero, not an error. */
    @Test
    public void anUnreadableTakeStillAppearsInTheLibrary() throws IOException {
        File wav = new File(store.directory(), "junk.wav");
        write(wav, "not audio");
        assertEquals(1, store.list().size());
        assertEquals(0.0, store.list().get(0).durationSeconds(), 0.0);
    }

    private static void touch(File file) throws IOException {
        write(file, "");
    }

    private static void write(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
    }
}
