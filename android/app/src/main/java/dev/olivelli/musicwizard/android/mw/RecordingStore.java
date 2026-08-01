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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The recordings directory: what is in it, and the four things done to it.
 *
 * <p>App-private storage, so no storage permission is involved and uninstalling
 * the app takes the takes with it. Each recording is a WAV and, once analysed,
 * a {@code .score.json} beside it with the same stem — the cache that makes
 * re-opening instant and that "re-analyze" overwrites.
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

        File oldScore = recording.scoreFile();
        if (!recording.wav().renameTo(target)) {
            throw new IOException("could not rename " + recording.displayName());
        }
        Recording renamed = new Recording(target);
        if (oldScore.isFile() && !oldScore.renameTo(renamed.scoreFile())) {
            // The audio moved and its analysis did not. Drop the stale cache
            // rather than leave it under the old stem, where it would be
            // attached to whatever take is named that next.
            //noinspection ResultOfMethodCallIgnored
            oldScore.delete();
        }
        return renamed;
    }

    /** Deletes a take and its cached analysis. */
    public void delete(Recording recording) {
        //noinspection ResultOfMethodCallIgnored
        recording.scoreFile().delete();
        //noinspection ResultOfMethodCallIgnored
        recording.wav().delete();
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
        if (name.endsWith(WAV)) {
            name = name.substring(0, name.length() - WAV.length());
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && out.length() < MAX_NAME_LENGTH; i++) {
            char c = name.charAt(i);
            boolean illegal = c == '/' || c == '\\' || c == 0 || c < ' '
                    || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|';
            out.append(illegal ? '_' : c);
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
