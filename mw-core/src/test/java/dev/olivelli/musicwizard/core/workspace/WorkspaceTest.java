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

package dev.olivelli.musicwizard.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir
    Path tempDirectory;

    private Path sourceFile;

    @BeforeEach
    void createSourceFile() throws IOException {
        sourceFile = tempDirectory.resolve("song.mp3");
        Files.writeString(sourceFile, "pretend this is audio");
    }

    /**
     * A workspace with no global config layer.
     *
     * <p>Explicitly none, rather than whatever the person running the suite has
     * in {@code ~/.config/music-wizard/config.yaml}. The two-argument
     * {@code Workspace.create} reads theirs, which is right for the CLI and is
     * how a valid config used to fail this class's own
     * {@code workspaceConfigOverridesDefaults} (#133).
     */
    private Workspace newWorkspace() {
        return Workspace.create(tempDirectory.resolve("song.mwz"), sourceFile,
                ConfigLoader.withoutGlobalConfig());
    }

    /** As {@link Workspace#open(Path)}, likewise with no global layer. */
    private static Workspace reopen(Path root) {
        return Workspace.open(root, ConfigLoader.withoutGlobalConfig());
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("lays out the expected directories")
        void createsLayout() {
            Workspace workspace = newWorkspace();

            assertThat(workspace.sourceDirectory()).isDirectory();
            assertThat(workspace.cacheDirectory()).isDirectory();
            assertThat(workspace.scoreDirectory()).isDirectory();
            assertThat(workspace.outputDirectory()).isDirectory();
            assertThat(workspace.logDirectory()).isDirectory();
            assertThat(workspace.descriptorFile()).isRegularFile();
        }

        @Test
        @DisplayName("copies the source in rather than referencing it")
        void importsSourceFile() throws IOException {
            Workspace workspace = newWorkspace();

            assertThat(workspace.sourceFile()).isRegularFile();
            assertThat(Files.readString(workspace.sourceFile()))
                    .isEqualTo("pretend this is audio");

            // Deleting the original must not disturb the workspace.
            Files.delete(sourceFile);
            assertThat(workspace.sourceFile()).isRegularFile();
        }

        @Test
        @DisplayName("records a digest that detects a swapped source file")
        void detectsSwappedSource() throws IOException {
            Workspace workspace = newWorkspace();
            assertThat(workspace.sourceMatchesDigest()).isTrue();

            Files.writeString(workspace.sourceFile(), "different audio entirely");
            assertThat(workspace.sourceMatchesDigest()).isFalse();
        }

        @Test
        @DisplayName("refuses to overwrite an existing directory")
        void refusesToOverwrite() {
            newWorkspace();

            assertThatThrownBy(() -> Workspace.create(tempDirectory.resolve("song.mwz"), sourceFile,
                            ConfigLoader.withoutGlobalConfig()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("rejects a missing source file")
        void rejectsMissingSource() {
            assertThatThrownBy(() ->
                    Workspace.create(tempDirectory.resolve("x.mwz"), tempDirectory.resolve("nope.mp3"),
                            ConfigLoader.withoutGlobalConfig()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
        }
    }

    @Nested
    @DisplayName("opening")
    class Opening {

        @Test
        @DisplayName("round-trips the descriptor")
        void roundTripsDescriptor() {
            Path root = newWorkspace().root();

            Workspace reopened = reopen(root);
            assertThat(reopened.readDescriptor().sourceFileName()).isEqualTo("song.mp3");
            assertThat(reopened.readDescriptor().schemaVersion())
                    .isEqualTo(Workspace.CURRENT_SCHEMA_VERSION);
        }

        @Test
        @DisplayName("rejects a directory that is not a workspace")
        void rejectsNonWorkspace() {
            assertThatThrownBy(() -> reopen(tempDirectory))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a workspace");
        }

        @Test
        @DisplayName("refuses a workspace written by a newer version")
        void refusesNewerSchema() throws IOException {
            Workspace workspace = newWorkspace();
            String yaml = Files.readString(workspace.descriptorFile());
            Files.writeString(workspace.descriptorFile(),
                    yaml.replace("schemaVersion: 1", "schemaVersion: 99"));

            assertThatThrownBy(() -> reopen(workspace.root()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("upgrade Music Wizard");
        }

        @Test
        @DisplayName("recognises a workspace directory")
        void recognisesWorkspace() {
            Path root = newWorkspace().root();

            assertThat(Workspace.isWorkspace(root)).isTrue();
            assertThat(Workspace.isWorkspace(tempDirectory)).isFalse();
            assertThat(Workspace.isWorkspace(null)).isFalse();
        }

        @Test
        @DisplayName("reclaims staging files a crashed earlier run abandoned")
        void sweepsAbandonedStagingFilesOnOpen() throws IOException {
            // Nothing else in the tool ever deletes these, and a stem abandoned
            // between stagingPath and commit is hundreds of megabytes.
            Workspace workspace = newWorkspace();
            StageCache.Key key = StageCache.Key.forStage("stems");
            Path abandoned = workspace.cache().stagingPath(key, ".wav");
            Files.writeString(abandoned, "half a stem from a crashed run");
            Files.setLastModifiedTime(abandoned,
                    FileTime.from(Instant.now().minus(Duration.ofDays(2))));

            reopen(workspace.root());

            assertThat(abandoned).doesNotExist();
        }

        @Test
        @DisplayName("leaves alone a staging file another process is still writing")
        void sparesInFlightStagingFilesOnOpen() throws IOException {
            // Opening a second command against a workspace while the first is
            // still separating is ordinary usage; it must not destroy the stem
            // the first one is mid-write on.
            Workspace workspace = newWorkspace();
            StageCache.Key key = StageCache.Key.forStage("stems");
            Path inFlight = workspace.cache().stagingPath(key, ".wav");
            Files.writeString(inFlight, "half a stem, still being written");
            // An aged sibling, so the test fails if the sweep stops running at
            // all rather than only if it stops being selective.
            Path abandoned = workspace.cache().stagingPath(key, ".wav");
            Files.writeString(abandoned, "a stem from a crash last week");
            Files.setLastModifiedTime(abandoned,
                    FileTime.from(Instant.now().minus(Duration.ofDays(2))));

            reopen(workspace.root());

            assertThat(inFlight).exists();
            assertThat(abandoned).doesNotExist();
        }

        @Test
        @DisplayName("still opens when the sweep cannot run")
        void opensEvenWhenTheSweepFails() throws IOException {
            // Housekeeping must never be the reason a workspace is unopenable, so
            // the sweep's refusal to delete through a symlinked cache is
            // swallowed rather than propagated.
            Workspace workspace = newWorkspace();
            Path elsewhere = tempDirectory.resolve("elsewhere");
            Files.createDirectories(elsewhere);
            Files.delete(workspace.cacheDirectory());
            try {
                Files.createSymbolicLink(workspace.cacheDirectory(), elsewhere);
            } catch (UnsupportedOperationException | IOException e) {
                return;
            }

            assertThat(reopen(workspace.root()).readDescriptor().sourceFileName())
                    .isEqualTo("song.mp3");
        }
    }

    @Nested
    @DisplayName("metadata and config")
    class Metadata {

        @Test
        @DisplayName("preserves other fields when updating metadata")
        void updatesMetadata() {
            Workspace workspace = newWorkspace();
            String originalDigest = workspace.readDescriptor().sourceSha256();

            workspace.updateMetadata("Yesterday", "The Beatles");

            assertThat(workspace.title()).contains("Yesterday");
            assertThat(workspace.artist()).contains("The Beatles");
            assertThat(workspace.readDescriptor().sourceSha256()).isEqualTo(originalDigest);
            assertThat(workspace.readDescriptor().sourceFileName()).isEqualTo("song.mp3");
        }

        @Test
        @DisplayName("workspace preferences override the defaults")
        void workspaceConfigOverridesDefaults() {
            Workspace workspace = newWorkspace();

            assertThat(workspace.effectiveConfig().notation().paperSize()).isEqualTo("a4");

            workspace.updateConfig(new MusicWizardConfig(null, null,
                    new MusicWizardConfig.NotationConfig(null, "letter", null, null, null, null),
                    null, null, null));

            MusicWizardConfig effective = workspace.effectiveConfig();
            assertThat(effective.notation().paperSize()).isEqualTo("letter");
            // Untouched keys must still fall back to their defaults.
            assertThat(effective.notation().transposeSemitones()).isZero();
            assertThat(effective.arrangement().maxNotesPerHand()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("stage cache")
    class Cache {

        @Test
        @DisplayName("stores and retrieves a result by key")
        void storesAndRetrieves() {
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("chords").with("algorithm", "nnls");

            assertThat(cache.contains(key, ".json")).isFalse();
            cache.writeText(key, ".json", "{\"chords\":[]}");

            assertThat(cache.contains(key, ".json")).isTrue();
            assertThat(cache.readText(key, ".json")).contains("{\"chords\":[]}");
        }

        @Test
        @DisplayName("a changed parameter misses the cache")
        void differentParametersMiss() {
            StageCache cache = newWorkspace().cache();
            StageCache.Key first = StageCache.Key.forStage("chords").with("algorithm", "nnls");
            StageCache.Key second = StageCache.Key.forStage("chords").with("algorithm", "templates");

            cache.writeText(first, ".json", "one");

            assertThat(cache.readText(second, ".json")).isEmpty();
            assertThat(first.digest()).isNotEqualTo(second.digest());
        }

        @Test
        @DisplayName("key construction is order-independent")
        void keyOrderDoesNotMatter() {
            StageCache.Key one = StageCache.Key.forStage("beats").with("a", 1).with("b", 2);
            StageCache.Key other = StageCache.Key.forStage("beats").with("b", 2).with("a", 1);

            assertThat(one.digest()).isEqualTo(other.digest());
        }

        @Test
        @DisplayName("distinct component splits do not collide")
        void separatorsPreventCollisions() {
            // Without unambiguous delimiters, {"ab":"c"} and {"a":"bc"} would
            // hash the same material.
            StageCache.Key one = StageCache.Key.forStage("s").with("ab", "c");
            StageCache.Key other = StageCache.Key.forStage("s").with("a", "bc");

            assertThat(one.digest()).isNotEqualTo(other.digest());
        }

        @Test
        @DisplayName("the file content is part of the key")
        void fileContentIsPartOfKey() throws IOException {
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();
            Path input = workspace.root().resolve("input.bin");

            Files.writeString(input, "version one");
            String firstDigest = StageCache.Key.forStage("stems").withFile("audio", input).digest();

            Files.writeString(input, "version two");
            String secondDigest = StageCache.Key.forStage("stems").withFile("audio", input).digest();

            assertThat(firstDigest).isNotEqualTo(secondDigest);
        }

        @Test
        @DisplayName("invalidating a stage removes only that stage")
        void invalidatesOneStage() {
            StageCache cache = newWorkspace().cache();
            StageCache.Key chords = StageCache.Key.forStage("chords");
            StageCache.Key beats = StageCache.Key.forStage("beats");
            cache.writeText(chords, ".json", "chords");
            cache.writeText(beats, ".json", "beats");

            cache.invalidateStage("chords");

            assertThat(cache.contains(chords, ".json")).isFalse();
            assertThat(cache.contains(beats, ".json")).isTrue();
        }

        @Test
        @DisplayName("keeps the stage name visible in the file name")
        void fileNameIsDiagnosable() {
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("separation");

            assertThat(cache.pathFor(key, ".wav").getFileName().toString())
                    .startsWith("separation-")
                    .endsWith(".wav");
        }

        @Test
        @DisplayName("staged artifacts only appear once committed")
        void stagingThenCommitting() throws IOException {
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("stems").with("model", "htdemucs");

            Path staged = cache.stagingPath(key, ".wav");
            Files.writeString(staged, "audio bytes");
            assertThat(cache.contains(key, ".wav")).isFalse();

            cache.commit(staged, key, ".wav");
            assertThat(cache.contains(key, ".wav")).isTrue();
        }
    }
}
