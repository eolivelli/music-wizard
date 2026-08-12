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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The recordings directory: what is in it, and what is done to it.
 *
 * <p>App-private storage, so no storage permission is involved and uninstalling
 * the app takes the takes with it. Each recording is a WAV and, beside it with
 * the same stem, up to two side files: a {@code .score.json} once analysed —
 * the cache that makes re-opening instant and that "re-analyze" overwrites —
 * and a {@code .notes.txt} once the player has said what was played.
 *
 * <p>Everything here is plain {@code java.io}, so it is tested on the JVM
 * against a temporary directory rather than on a device.
 */
public final class RecordingStore {

    /** Extension of a take. */
    public static final String WAV = ".wav";

    /**
     * Longest name a rename may produce.
     *
     * <p>Well under any filesystem's limit, and short enough that the stem plus
     * {@code .score.json} still fits. Names are typed by a person naming a take,
     * not generated.
     */
    private static final int MAX_NAME_LENGTH = 64;

    /**
     * Sortable, second-resolution, and legal on every filesystem.
     *
     * <p>Colons would be the ISO spelling and are not usable in a file name on
     * a filesystem the phone might export to.
     */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    private final File directory;

    public RecordingStore(File directory) {
        this.directory = directory;
    }

    public File directory() {
        return directory;
    }

    /** One take: the audio, and where its analysis is cached. */
    public static final class Recording {

        private final File wav;

        public Recording(File wav) {
            this.wav = wav;
        }

        public File wav() {
            return wav;
        }

        public File scoreFile() {
            return MwAnalysis.scoreFileFor(wav);
        }

        /**
         * Where the player's own account of the take lives: beside the WAV,
         * same stem. "What was played", typed on the result screen and carried
         * in the bundle — the one file beside a take that cannot be recomputed.
         */
        public File notesFile() {
            String name = wav.getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            return new File(wav.getParentFile(), stem + ".notes.txt");
        }

        /** The name shown in the library: the file name without {@code .wav}. */
        public String displayName() {
            String name = wav.getName();
            return name.endsWith(WAV) ? name.substring(0, name.length() - WAV.length()) : name;
        }

        public boolean isAnalyzed() {
            return scoreFile().isFile();
        }

        public long modifiedMillis() {
            return wav.lastModified();
        }

        /**
         * Length in seconds, or 0 when the header cannot be read.
         *
         * <p>From the header rather than from the audio, so that listing a
         * directory does not decode every take in it.
         */
        public double durationSeconds() {
            try {
                return WavFile.readFormat(wav).durationSeconds();
            } catch (IOException | RuntimeException e) {
                return 0;
            }
        }
    }

    /** Creates the directory if it is not there yet. */
    public File ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        return directory;
    }

    /** Newest first, because the take someone just made is the one they want. */
    public List<Recording> list() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(WAV));
        List<Recording> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            out.add(new Recording(file));
        }
        out.sort(Comparator.comparingLong(Recording::modifiedMillis).reversed()
                .thenComparing(Recording::displayName));
        return out;
    }

    /**
     * A file for a take started now, whose name no existing take has.
     *
     * <p>The suffix only appears for a second take started inside the same
     * second as another, which a person cannot do but a test can.
     */
    public File newRecordingFile(Instant when, ZoneId zone) throws IOException {
        ensureDirectory();
        String stamp = STAMP.format(when.atZone(zone));
        File candidate = new File(directory, stamp + WAV);
        for (int n = 2; candidate.exists(); n++) {
            candidate = new File(directory, stamp + "-" + n + WAV);
        }
        return candidate;
    }

    /**
     * Renames a take and its cached analysis together.
     *
     * <p>Together, because the cache is found by the audio's stem: leaving it
     * behind would both lose the analysis and leave an orphan that a later take
     * of the same name would inherit.
     *
     * @return the renamed take
     * @throws IOException when the name is unusable, is already taken, or the
     *                     rename fails
     */
    public Recording rename(Recording recording, String rawName) throws IOException {
        String stem = sanitize(rawName);
        File target = new File(directory, stem + WAV);
        if (target.equals(recording.wav())) {
            return recording;
        }
        if (target.exists()) {
            throw new IOException("there is already a recording called " + stem);
        }
        Recording renamed = new Recording(target);
        // The target stem can carry side files orphaned by an interrupted move;
        // cleared now, so the take moving in cannot inherit another take's
        // analysis or words as its own.
        //noinspection ResultOfMethodCallIgnored
        renamed.scoreFile().delete();
        //noinspection ResultOfMethodCallIgnored
        renamed.notesFile().delete();

        File oldScore = recording.scoreFile();
        File oldNotes = recording.notesFile();
        if (!recording.wav().renameTo(target)) {
            throw new IOException("could not rename " + recording.displayName());
        }
        if (oldScore.isFile() && !oldScore.renameTo(renamed.scoreFile())) {
            // The audio moved and its analysis did not. Drop the stale cache
            // rather than leave it under the old stem, where it would be
            // attached to whatever take is named that next.
            //noinspection ResultOfMethodCallIgnored
            oldScore.delete();
        }
        if (oldNotes.isFile() && !oldNotes.renameTo(renamed.notesFile())) {
            // Not the cache's rule — see notesFile(): a failed move copies
            // instead, and if even that fails the original stays under the old
            // stem. A stray, not an heirloom: the clearing above keeps a later
            // rename onto that stem from serving it as another take's account.
            try {
                Files.copy(oldNotes.toPath(), renamed.notesFile().toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                oldNotes.delete();
            } catch (IOException e) {
                // Left behind, knowingly.
            }
        }
        return renamed;
    }

    /** Deletes a take, its cached analysis, and its note. */
    public void delete(Recording recording) {
        //noinspection ResultOfMethodCallIgnored
        recording.scoreFile().delete();
        //noinspection ResultOfMethodCallIgnored
        recording.notesFile().delete();
        //noinspection ResultOfMethodCallIgnored
        recording.wav().delete();
    }

    /**
     * The player's note for a take, or an empty string when there is none.
     *
     * <p>Unreadable counts as none: the audio is still there, and an error
     * screen over a side file would cost more than the words it reports on.
     */
    public static String readNotes(Recording recording) {
        File file = recording.notesFile();
        if (!file.isFile()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Writes the note, replacing what was there; a blank note deletes the file,
     * so an emptied field does not leave a ghost entry in the bundle.
     */
    public static void writeNotes(Recording recording, String text) throws IOException {
        File file = recording.notesFile();
        if (text == null || text.trim().isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return;
        }
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The file-name stem a typed name becomes.
     *
     * <p>A name reaches the filesystem and then, through the share sheet,
     * another app's idea of a file name. Separators and dots are the part that
     * matters: {@code ../} would write outside the app's own directory, and a
     * leading dot hides the take from the very file managers used to get it off
     * the phone.
     */
    public static String sanitize(String rawName) throws IOException {
        String name = rawName == null ? "" : rawName.trim();
        // Case-insensitively, because a name typed as "Take.WAV" is the same
        // intention as "take.wav" and stripping only the lowercase one produces
        // a file called Take.WAV.wav.
        if (name.toLowerCase(Locale.ROOT).endsWith(WAV)) {
            name = name.substring(0, name.length() - WAV.length());
        }
        StringBuilder out = new StringBuilder(name.length());
        // By code point rather than by char: an emoji in a take name is two
        // chars, and cutting between them at the length limit leaves an
        // unpaired surrogate. The filesystem then holds a name that is not the
        // one this method returned -- exactly the mismatch the trailing-dot rule
        // below exists to prevent, and it travels out through the share sheet.
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (out.length() + width > MAX_NAME_LENGTH) {
                break;
            }
            boolean illegal = codePoint == '/' || codePoint == '\\' || codePoint < ' '
                    || codePoint == ':' || codePoint == '*' || codePoint == '?'
                    || codePoint == '"' || codePoint == '<' || codePoint == '>'
                    || codePoint == '|';
            out.append(illegal ? "_" : new String(Character.toChars(codePoint)));
            i += width;
        }
        // Trailing dots and spaces are dropped by some filesystems, which would
        // make the rename appear to have produced a different name than it did.
        while (out.length() > 0 && (out.charAt(out.length() - 1) == '.'
                || out.charAt(out.length() - 1) == ' ')) {
            out.setLength(out.length() - 1);
        }
        while (out.length() > 0 && out.charAt(0) == '.') {
            out.deleteCharAt(0);
        }
        String stem = out.toString().trim();
        if (stem.isEmpty()) {
            throw new IOException("a recording needs a name");
        }
        return stem;
    }

    /** {@code m:ss}, the way a recorder shows a length. */
    public static String formatDuration(double seconds) {
        long total = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }
}
