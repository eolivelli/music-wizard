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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for defects found in the second and third review rounds.
 *
 * <p>A workspace is a directory that gets copied and shared, and stage names can
 * reach the cache from a command-line flag, so both are untrusted input.
 */
class HardeningTest {

    @TempDir
    Path tempDirectory;

    private Path sourceFile;

    @BeforeEach
    void createSourceFile() throws IOException {
        sourceFile = tempDirectory.resolve("song.mp3");
        Files.writeString(sourceFile, "audio");
    }

    private Workspace newWorkspace() {
        return Workspace.create(tempDirectory.resolve("song.mwz"), sourceFile);
    }

    @Nested
    @DisplayName("stage cache cannot escape the workspace")
    class CacheContainment {

        @ParameterizedTest(name = "stage name \"{0}\" stays inside the cache")
        @ValueSource(strings = {"../../../escaped", "..", ".", "/etc", "a/../../b", ""})
        void writesStayInsideTheCache(String stageName) {
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();

            Path written = cache.writeText(
                    StageCache.Key.forStage(stageName), ".txt", "payload");

            assertThat(written.normalize().toAbsolutePath())
                    .startsWith(workspace.cacheDirectory().normalize().toAbsolutePath());
        }

        @Test
        @DisplayName("invalidating a traversing stage name deletes nothing outside")
        void invalidateCannotEscape() throws IOException {
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();

            // A canary next to the workspace, which a raw recursive delete would
            // have destroyed.
            Path canary = tempDirectory.resolve("precious/DO_NOT_DELETE.txt");
            Files.createDirectories(canary.getParent());
            Files.writeString(canary, "keep me");

            cache.invalidateStage("../precious");
            cache.invalidateStage("../../precious");

            assertThat(canary).exists();
            assertThat(workspace.descriptorFile()).exists();
        }

        @Test
        @DisplayName("invalidating \"..\" does not destroy the workspace")
        void invalidateCannotDeleteTheWorkspace() {
            Workspace workspace = newWorkspace();

            // ".." sanitizes to a single safe component, so it can only ever
            // address a directory inside the cache -- which does not exist here.
            assertThatCode(() -> workspace.cache().invalidateStage("..")).doesNotThrowAnyException();

            assertThat(workspace.descriptorFile()).exists();
            assertThat(workspace.sourceFile()).exists();
            assertThat(Workspace.isWorkspace(workspace.root())).isTrue();
        }

        @Test
        @DisplayName("a delimiter in a value cannot forge another component")
        void keyMaterialIsUnambiguous() {
            // Length-prefixed encoding: no value can imitate a component boundary.
            StageCache.Key injected = StageCache.Key.forStage("asr")
                    .with("lang", "en\u0000prompt\u0001sing");
            StageCache.Key genuine = StageCache.Key.forStage("asr")
                    .with("lang", "en").with("prompt", "sing");

            assertThat(injected.digest()).isNotEqualTo(genuine.digest());
        }

        @Test
        @DisplayName("a null value is distinct from the string \"null\"")
        void nullIsNotTheWordNull() {
            assertThat(StageCache.Key.forStage("s").with("a", null).digest())
                    .isNotEqualTo(StageCache.Key.forStage("s").with("a", "null").digest());
        }

        @Test
        @DisplayName("an unencodable key component is refused rather than collapsed")
        void rejectsUnpairedSurrogates() {
            // UTF-8 replaces every unpaired surrogate with a single '?', so these
            // five distinct values previously produced one identical digest. No
            // length prefix can undo that, because the encoder erased the
            // difference first -- so the value has to be refused.
            for (String value : new String[] {"\uD800", "\uDC00", "\uD801", "\uDFFF"}) {
                assertThatThrownBy(() ->
                        StageCache.Key.forStage("sep").with("model", value).digest())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("unpaired surrogate");
            }

            // A well-formed value that merely contains '?' is still fine, and a
            // valid surrogate PAIR (an emoji) must not be refused.
            assertThat(StageCache.Key.forStage("sep").with("model", "?").digest())
                    .isNotEqualTo(StageCache.Key.forStage("sep").with("model", "\uD83C\uDFB5").digest());
        }

        @Test
        @DisplayName("ordinary distinct keys still differ, and equal keys still match")
        void digestRemainsStable() {
            StageCache.Key one = StageCache.Key.forStage("chords").with("algo", "nnls").with("n", 3);
            StageCache.Key same = StageCache.Key.forStage("chords").with("n", 3).with("algo", "nnls");
            StageCache.Key other = StageCache.Key.forStage("chords").with("algo", "nnls").with("n", 4);

            assertThat(one.digest()).isEqualTo(same.digest());
            assertThat(one.digest()).isNotEqualTo(other.digest());
        }

        @Test
        @DisplayName("a symlinked cache directory is not deleted through")
        void refusesSymlinkedCacheDirectory() throws IOException {
            // invalidateStage deletes recursively, so a cache/ that is itself a
            // link would let it destroy whatever the link points at.
            Workspace workspace = newWorkspace();
            Path victim = tempDirectory.resolve("victim");
            Files.createDirectories(victim.resolve("beats"));
            Files.writeString(victim.resolve("beats/keep.txt"), "keep me");

            if (!replaceCacheWithSymlink(workspace, victim)) {
                return;
            }

            assertThatThrownBy(() -> workspace.cache().invalidateStage("beats"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cache directory is a symbolic link");
            assertThat(victim.resolve("beats/keep.txt")).exists();
        }

        @Test
        @DisplayName("an unencodable component fails at the call site, not in toString")
        void rejectsUnencodableEagerly() {
            // A toString() that throws breaks logging and debuggers, which is
            // exactly where you least want a surprise.
            assertThatThrownBy(() -> StageCache.Key.forStage("bad\uD800"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StageCache.Key.forStage("ok").with("k", "bad\uD800"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> StageCache.Key.forStage("ok").with("k", "fine").toString())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a failed write leaves no partial file behind")
        void failedWriteLeavesNoPartial() throws IOException {
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path staged = cache.stagingPath(key, ".bin");
            Files.writeString(staged, "abandoned");
            cache.discard(staged);

            assertThat(staged).doesNotExist();
        }

        @Test
        @DisplayName("abandoned staging files can be swept")
        void sweepsAbandonedStagingFiles() throws IOException {
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            for (int i = 0; i < 3; i++) {
                Path orphan = cache.stagingPath(key, ".bin");
                Files.writeString(orphan, "orphan " + i);
                backdate(orphan, Duration.ofDays(2));
            }
            cache.writeText(key, ".json", "real entry");

            assertThat(cache.sweepAbandonedStagingFiles()).isEqualTo(3);
            assertThat(cache.readText(key, ".json")).contains("real entry");
        }

        @Test
        @DisplayName("a sweep spares a staging file another run is still writing")
        void sweepSparesInFlightStagingFiles() throws IOException {
            // The whole reason the sweep has an age threshold. ".partial-" is
            // also the name a LIVE separation writes its stem under, and a
            // workspace can be open in two processes at once, so an
            // unconditional sweep deletes hundreds of megabytes out from under a
            // run that then fails at commit.
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path inFlight = cache.stagingPath(key, ".bin");
            Files.writeString(inFlight, "half a stem");
            Path abandoned = cache.stagingPath(key, ".bin");
            Files.writeString(abandoned, "yesterday's stem");
            backdate(abandoned, Duration.ofDays(2));

            assertThat(cache.sweepAbandonedStagingFiles()).isEqualTo(1);
            assertThat(inFlight).exists();
            assertThat(abandoned).doesNotExist();
        }

        @Test
        @DisplayName("a staging file dated in the future is spared, not swept")
        void sweepSparesFutureDatedStagingFiles() throws IOException {
            // Clock skew between a workspace on shared storage and this machine
            // must not be read as "very old indeed".
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path skewed = cache.stagingPath(key, ".bin");
            Files.writeString(skewed, "written by a fast clock");
            backdate(skewed, Duration.ofDays(-2));

            assertThat(cache.sweepAbandonedStagingFiles()).isZero();
            assertThat(skewed).exists();
        }

        @Test
        @DisplayName("a sweep does not delete through a symlinked cache directory")
        void sweepRefusesSymlinkedCacheDirectory() throws IOException {
            // Same trap as invalidateStage: the sweep deletes, and a workspace is
            // a directory people copy and share.
            Workspace workspace = newWorkspace();
            Path victim = tempDirectory.resolve("sweep-victim");
            Files.createDirectories(victim.resolve("stems"));
            Path bait = victim.resolve("stems/.partial-precious.bin");
            Files.writeString(bait, "not ours to delete");
            backdate(bait, Duration.ofDays(2));

            if (!replaceCacheWithSymlink(workspace, victim)) {
                return;
            }

            assertThatThrownBy(() -> workspace.cache().sweepAbandonedStagingFiles())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cache directory is a symbolic link");
            assertThat(bait).exists();
        }

        @Test
        @DisplayName("a sweep does not follow a staging file that is a symlink")
        void sweepDoesNotFollowStagingSymlinks() throws IOException {
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();
            StageCache.Key key = StageCache.Key.forStage("stems");
            // Force the stage directory into existence.
            cache.writeText(key, ".json", "real entry");

            Path secret = tempDirectory.resolve("secret.txt");
            Files.writeString(secret, "read me not");
            backdate(secret, Duration.ofDays(2));
            Path link = cache.pathFor(key, ".bin").getParent().resolve(".partial-link.bin");
            try {
                Files.createSymbolicLink(link, secret);
            } catch (UnsupportedOperationException | IOException e) {
                return;
            }

            assertThat(cache.sweepAbandonedStagingFiles()).isZero();
            assertThat(secret).exists();
        }
    }

    @Nested
    @DisplayName("a reserved staging file is always accounted for")
    class StagingLifecycle {

        @Test
        @DisplayName("anything still uncommitted is discarded when the JVM exits")
        void discardsOutstandingStagedFilesAtShutdown() throws IOException {
            // Without this the sweep barely helps with the case #15 is about: a
            // run that dies and is retried minutes later leaves an orphan that is
            // not yet old enough to sweep, and the user never opens that
            // workspace again a day later to collect it.
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path abandoned = cache.stagingPath(key, ".wav");
            Files.writeString(abandoned, "a stem nobody will commit");
            Path committed = cache.stagingPath(key, ".wav");
            Files.writeString(committed, "a stem that made it");
            Path entry = cache.commit(committed, key, ".wav");

            StageCache.discardOutstandingStagedFiles();

            assertThat(abandoned).doesNotExist();
            assertThat(entry).exists();
        }

        @Test
        @DisplayName("a real JVM exit collects the staged file, not just a direct call")
        void aRealShutdownCollectsTheStagedFile() throws Exception {
            // The in-process tests can only call the cleanup method directly, so
            // the registration itself -- the one line that makes any of this work
            // for a real user -- can be deleted with the whole suite still green.
            // Only a process that genuinely exits reaches it.
            Path staged = runStagingProcess("crash", 1);

            assertThat(staged).doesNotExist();
        }

        @Test
        @DisplayName("staging from inside somebody else's shutdown hook still succeeds")
        void stagingDuringShutdownDoesNotFail() throws Exception {
            // addShutdownHook throws IllegalStateException once shutdown has
            // begun. Letting that escape would give stagingPath an exception type
            // its contract never mentions AND strand the file createTempFile has
            // already made -- a new leak from the code meant to remove them.
            Path staged = runStagingProcess("stage-during-shutdown", 0);

            // Deterministic only because this mode stages nothing before
            // shutdown, so no cleanup hook was ever armed and there is no second
            // hook to race. In general a reservation made during shutdown may or
            // may not be collected; the age-based sweep is what accounts for it.
            // What is being asserted here is only that the reservation succeeds.
            assertThat(staged).exists();
        }

        /**
         * Runs {@link StagingCleanupProcess} in a real JVM and returns the path it
         * staged, checking it exited the way the mode intends.
         */
        private Path runStagingProcess(String mode, int expectedExitCode) throws Exception {
            Path root = tempDirectory.resolve("forked-" + mode + ".mwz");
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    StagingCleanupProcess.class.getName(),
                    mode, root.toString(), sourceFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String output;
            boolean exited;
            try {
                // Drained to EOF before waiting, so the child cannot block on a
                // full pipe while we block on the child.
                output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                // The failure this test exists to catch is cleanup that never
                // finishes, so an unbounded wait would turn a red test into a
                // build that hangs with no diagnostic at all.
                process.destroyForcibly();
            }
            assertThat(exited).as("child exited within 30s, output was:%n%s", output).isTrue();

            // Reported rather than merely asserted, so a classpath or fork problem
            // reads as one instead of as a cleanup failure.
            assertThat(output).as("process output").contains("STAGED ").doesNotContain("FAILED");
            assertThat(process.exitValue()).as("exit code, output was:%n%s", output)
                    .isEqualTo(expectedExitCode);
            return Path.of(output.lines()
                    .filter(line -> line.startsWith("STAGED "))
                    .findFirst().orElseThrow()
                    .substring("STAGED ".length()).trim());
        }

        @Test
        @DisplayName("a committed file is not deleted afterwards by the shutdown pass")
        void committedFilesAreForgotten() throws IOException {
            // commit() moves the staging file away, so a shutdown pass that still
            // believed it outstanding would be deleting a path that by then
            // belongs to a completely different reservation.
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path staged = cache.stagingPath(key, ".wav");
            Files.writeString(staged, "committed");
            cache.commit(staged, key, ".wav");
            // Somebody else's later reservation lands on the freed name.
            Files.writeString(staged, "a different run's stem");

            StageCache.discardOutstandingStagedFiles();

            assertThat(staged).exists();
        }

        @Test
        @DisplayName("a discarded file is not deleted afterwards by the shutdown pass")
        void discardedFilesAreForgotten() throws IOException {
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path staged = cache.stagingPath(key, ".wav");
            Files.writeString(staged, "abandoned");
            cache.discard(staged);
            // A later reservation lands on the freed name.
            Files.writeString(staged, "a different run's stem");

            StageCache.discardOutstandingStagedFiles();

            assertThat(staged).exists();
        }

        @Test
        @DisplayName("a swept file is not deleted afterwards by the shutdown pass")
        void sweptFilesAreForgotten() throws IOException {
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path staged = cache.stagingPath(key, ".wav");
            Files.writeString(staged, "abandoned long ago");
            backdate(staged, Duration.ofDays(2));
            assertThat(cache.sweepAbandonedStagingFiles()).isEqualTo(1);
            Files.writeString(staged, "a different run's stem");

            StageCache.discardOutstandingStagedFiles();

            assertThat(staged).exists();
        }

        @Test
        @DisplayName("one undeletable staging file does not abort the whole sweep")
        void sweepSurvivesAnUndeletableFile() throws IOException {
            // A workspace is copied and shared, so a stage directory arriving
            // with somebody else's permissions is expected. Aborting the walk
            // would silently strand every later staging file forever.
            Workspace workspace = newWorkspace();
            StageCache cache = workspace.cache();

            Path locked = workspace.cacheDirectory().resolve("aaa-locked");
            Files.createDirectories(locked);
            Path stuck = locked.resolve(".partial-stuck.bin");
            Files.writeString(stuck, "cannot be removed");
            backdate(stuck, Duration.ofDays(2));

            Path unlocked = workspace.cacheDirectory().resolve("zzz-open");
            Files.createDirectories(unlocked);
            Path free = unlocked.resolve(".partial-free.bin");
            Files.writeString(free, "can be removed");
            backdate(free, Duration.ofDays(2));

            if (!makeUndeletable(locked)) {
                return;
            }
            try {
                assertThat(cache.sweepAbandonedStagingFiles()).isEqualTo(1);
                assertThat(free).doesNotExist();
                assertThat(stuck).exists();
            } finally {
                Files.setPosixFilePermissions(locked,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }

        @Test
        @DisplayName("an explicit threshold of zero sweeps even a file written just now")
        void explicitZeroThresholdSweepsEverything() throws IOException {
            // The hook a future "mw cache --sweep" would use, and the reason the
            // no-arg default has to be the conservative one.
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path fresh = cache.stagingPath(key, ".bin");
            Files.writeString(fresh, "written just now");
            // Stamped explicitly rather than trusting the filesystem clock, so
            // the assertion cannot turn on timestamp granularity.
            backdate(fresh, Duration.ZERO);

            assertThat(cache.sweepAbandonedStagingFiles(Duration.ZERO)).isEqualTo(1);
            assertThat(fresh).doesNotExist();
        }

        @Test
        @DisplayName("the threshold divides the two sides of the boundary")
        void sweepsOnlyWhatIsOlderThanTheThreshold() throws IOException {
            StageCache cache = newWorkspace().cache();
            StageCache.Key key = StageCache.Key.forStage("stems");

            Path older = cache.stagingPath(key, ".bin");
            Files.writeString(older, "sixty-one minutes old");
            backdate(older, Duration.ofMinutes(61));
            Path younger = cache.stagingPath(key, ".bin");
            Files.writeString(younger, "fifty-nine minutes old");
            backdate(younger, Duration.ofMinutes(59));

            assertThat(cache.sweepAbandonedStagingFiles(Duration.ofHours(1))).isEqualTo(1);
            assertThat(older).doesNotExist();
            assertThat(younger).exists();
        }

        @Test
        @DisplayName("an unusable threshold is refused rather than guessed at")
        void refusesAnUnusableThreshold() {
            StageCache cache = newWorkspace().cache();

            assertThatThrownBy(() -> cache.sweepAbandonedStagingFiles(null))
                    .isInstanceOf(NullPointerException.class);
            // Negative would mean "delete files modified in the future", which is
            // exactly the clock-skew case the sweep deliberately spares.
            assertThatThrownBy(() -> cache.sweepAbandonedStagingFiles(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("a workspace with no cache directory sweeps nothing and does not fail")
        void sweepsNothingWhenTheCacheIsAbsent() throws IOException {
            Workspace workspace = newWorkspace();
            Files.delete(workspace.cacheDirectory());

            assertThat(workspace.cache().sweepAbandonedStagingFiles()).isZero();
        }
    }

    /**
     * Makes a directory's contents undeletable, returning false when the platform
     * or the current user makes that impossible -- running as root, or a
     * filesystem without POSIX permissions.
     */
    private static boolean makeUndeletable(Path directory) {
        try {
            Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
        Path probe = directory.resolve(".writable-probe");
        try {
            Files.writeString(probe, "root ignores permissions");
            Files.deleteIfExists(probe);
        } catch (IOException expected) {
            return true;
        }
        // Writable after all -- undo, so the temp directory can still be cleaned up.
        try {
            Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException ignored) {
            // Nothing further to try.
        }
        return false;
    }

    /** Backdates a file so a sweep sees it as abandoned rather than in flight. */
    private static void backdate(Path file, Duration age) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
    }

    /**
     * Replaces a workspace's cache directory with a symlink to {@code target},
     * returning false when the platform will not create symlinks at all.
     */
    private static boolean replaceCacheWithSymlink(Workspace workspace, Path target)
            throws IOException {
        Path cacheDir = workspace.cacheDirectory();
        try (var entries = Files.walk(cacheDir)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        }
        try {
            Files.createSymbolicLink(cacheDir, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    @Nested
    @DisplayName("workspace descriptor is untrusted input")
    class DescriptorTrust {

        private void rewriteSourceName(Workspace workspace, String name) throws IOException {
            String yaml = Files.readString(workspace.descriptorFile());
            Files.writeString(workspace.descriptorFile(),
                    yaml.replaceFirst("sourceFileName: .*", "sourceFileName: \"" + name + "\""));
        }

        @ParameterizedTest(name = "sourceFileName \"{0}\" is refused")
        @ValueSource(strings = {"../../secret.txt", "/etc/hostname", "..", "sub/../../out"})
        void refusesEscapingSourceName(String name) throws IOException {
            Workspace workspace = newWorkspace();
            rewriteSourceName(workspace, name);

            assertThatThrownBy(() -> Workspace.open(workspace.root()).sourceFile())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside the workspace");
        }

        @Test
        @DisplayName("a symlinked source file that points outside is refused")
        void refusesSymlinkedSourceFile() throws IOException {
            // Lexical containment is not enough. A workspace is copied and shared,
            // and tar and zip both preserve symlinks, so source/song.mp3 can point
            // anywhere on the machine. Before this was checked, reading the source
            // returned the contents of the link target.
            Workspace workspace = newWorkspace();
            Path secret = tempDirectory.resolve("secret.pem");
            Files.writeString(secret, "-----BEGIN PRIVATE KEY-----");

            Path imported = workspace.sourceFile();
            Files.delete(imported);
            try {
                Files.createSymbolicLink(imported, secret);
            } catch (UnsupportedOperationException | IOException e) {
                return; // Filesystem cannot make symlinks; nothing to assert.
            }

            assertThatThrownBy(() -> Workspace.open(workspace.root()).sourceFile())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resolves outside the workspace");
        }

        @Test
        @DisplayName("a symlinked source directory that points outside is refused")
        void refusesSymlinkedSourceDirectory() throws IOException {
            Workspace workspace = newWorkspace();
            Path outside = tempDirectory.resolve("outside");
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("secret.pem"), "private");

            Path link = workspace.sourceDirectory().resolve("sub");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException e) {
                return;
            }
            String yaml = Files.readString(workspace.descriptorFile());
            Files.writeString(workspace.descriptorFile(),
                    yaml.replaceFirst("sourceFileName: .*", "sourceFileName: sub/secret.pem"));

            assertThatThrownBy(() -> Workspace.open(workspace.root()).sourceFile())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resolves outside the workspace");
        }

        @Test
        @DisplayName("a symlinked source directory itself is refused")
        void refusesSymlinkedSourceRoot() throws IOException {
            // The anchor must not be attacker-controlled. Resolving the source
            // directory with toRealPath() follows this link too, so containment
            // would compare the target against itself and always pass.
            Workspace workspace = newWorkspace();
            Path outside = tempDirectory.resolve("elsewhere");
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("shadow"), "leaked");

            Path sourceDir = workspace.sourceDirectory();
            Files.delete(workspace.sourceFile());
            Files.delete(sourceDir);
            try {
                Files.createSymbolicLink(sourceDir, outside);
            } catch (UnsupportedOperationException | IOException e) {
                return;
            }
            String yaml = Files.readString(workspace.descriptorFile());
            Files.writeString(workspace.descriptorFile(),
                    yaml.replaceFirst("sourceFileName: .*", "sourceFileName: shadow"));

            assertThatThrownBy(() -> Workspace.open(workspace.root()).sourceFile())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("source directory is a symbolic link");
        }

        @Test
        @DisplayName("a workspace under a symlinked ancestor still works")
        void acceptsWorkspaceUnderSymlinkedAncestor() throws IOException {
            // The false positive to avoid: /tmp is /private/tmp on macOS, so a
            // perfectly ordinary workspace lives under a symlinked ancestor.
            Path realParent = tempDirectory.resolve("real");
            Files.createDirectories(realParent);
            Path linkedParent = tempDirectory.resolve("linked");
            try {
                Files.createSymbolicLink(linkedParent, realParent);
            } catch (UnsupportedOperationException | IOException e) {
                return;
            }

            Workspace workspace = Workspace.create(linkedParent.resolve("s.mwz"), sourceFile);

            assertThat(workspace.sourceFile()).isRegularFile();
            assertThat(workspace.sourceMatchesDigest()).isTrue();
        }

        @Test
        @DisplayName("an ordinary source file is still accepted")
        void acceptsOrdinarySourceFile() {
            Workspace workspace = newWorkspace();

            assertThat(workspace.sourceFile()).isRegularFile();
            assertThat(workspace.sourceMatchesDigest()).isTrue();
        }

        @Test
        @DisplayName("a descriptor with no schema version is rejected")
        void rejectsMissingSchemaVersion() throws IOException {
            Workspace workspace = newWorkspace();
            Files.writeString(workspace.descriptorFile(), "sourceFileName: song.mp3\n");

            assertThatThrownBy(() -> Workspace.open(workspace.root()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid schema version");
        }

        @Test
        @DisplayName("a descriptor naming no source file fails with a clear message")
        void rejectsMissingSourceName() throws IOException {
            Workspace workspace = newWorkspace();
            Files.writeString(workspace.descriptorFile(), "schemaVersion: 1\n");

            assertThatThrownBy(() -> Workspace.open(workspace.root()).sourceFile())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("names no source file");
        }

        @Test
        @DisplayName("the descriptor survives being rewritten repeatedly")
        void descriptorWriteIsAtomic() {
            Workspace workspace = newWorkspace();

            for (int i = 0; i < 20; i++) {
                workspace.updateMetadata("Title " + i, "Artist " + i);
            }

            assertThat(workspace.title()).contains("Title 19");
            // The temporary files used for the atomic swap must not accumulate.
            assertThat(workspace.root().toFile().list())
                    .noneMatch(name -> name.startsWith(".workspace-"));
        }
    }

    @Nested
    @DisplayName("failed creation")
    class FailedCreation {

        @Test
        @DisplayName("leaves nothing behind, so the user can simply retry")
        void rollsBack() throws IOException {
            Path target = tempDirectory.resolve("blocked.mwz");
            // Make the copy fail: a directory already occupies the source path.
            Path unreadable = tempDirectory.resolve("unreadable.mp3");
            Files.createDirectory(unreadable);

            assertThatThrownBy(() -> Workspace.create(target, unreadable))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(target).doesNotExist();
        }
    }
}
