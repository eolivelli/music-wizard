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

package dev.olivelli.musicwizard.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

import dev.olivelli.musicwizard.core.config.MusicWizardConfig.NotationConfig;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The global config layer: that it works, and that a test only gets one when it
 * asks for one.
 *
 * <p>Both halves are new with #133 and neither was previously possible. The
 * layer's precedence — documented in the README as the middle of CLI flags &gt;
 * {@code workspace.yaml} &gt; {@code ~/.config/music-wizard/config.yaml} — had
 * no test at all, because {@code ConfigLoader} resolved the location itself and
 * so the only way to exercise it was to write into the home directory of
 * whoever ran the suite. Which is also how it broke: a contributor with the
 * README's own two-line example got {@code WorkspaceTest} failing on
 * {@code paperSize}, with nothing pointing at their own config and CI green.
 *
 * <p>One test writes at the location the environment names, which no
 * {@code @TempDir} isolates, and one reads it. That location has two scopes and
 * needs two locks: the class-level {@code @ResourceLock} for other threads in
 * this JVM, and a {@link java.nio.channels.FileLock} for other JVMs sharing
 * this module's {@code target/}, since {@code XDG_CONFIG_HOME} is per module
 * rather than per process. Same shape of hazard as #36; both locks are this
 * class paying its own way rather than a fix for it.
 *
 * <p>It is one test rather than three because review measured the cost:
 * planting there in three tests failed 11 of 12 concurrent JVMs. Everything
 * that can be asserted on {@code globalConfigFileLocation()} instead is, and
 * only the assertion that a real file is read end to end still needs a real
 * file.
 */
@ResourceLock(GlobalConfigLayerTest.GLOBAL_CONFIG_ENVIRONMENT)
class GlobalConfigLayerTest {

    /** Guards the one location that is not per-test: the environment's own. */
    static final String GLOBAL_CONFIG_ENVIRONMENT = "global-config-environment";

    /** A test body that may throw, so the plant-and-remove helper can wrap one. */
    @FunctionalInterface
    interface ThrowingBody {
        void run() throws IOException;
    }

    @TempDir
    Path tempDirectory;

    private Path writeGlobalConfig(String yaml) throws IOException {
        Path file = tempDirectory.resolve("global-config.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private Path newSourceFile() throws IOException {
        Path source = tempDirectory.resolve("song.mp3");
        Files.writeString(source, "pretend this is audio");
        return source;
    }

    @Nested
    @DisplayName("choosing a global layer")
    class Choosing {

        @Test
        @DisplayName("a stated file is read exactly as stated")
        void statedFileIsReadAsStated() throws IOException {
            Path file = writeGlobalConfig("notation:\n  paperSize: letter\n");

            ConfigLoader loader = ConfigLoader.withGlobalConfigFile(file);

            // Exactly as given: no XDG prefix, no music-wizard directory
            // appended. A caller that wanted those would have said so.
            assertThat(loader.globalConfigFileLocation()).contains(file);
            assertThat(loader.readGlobalLayer().notation().paperSize()).isEqualTo("letter");
        }

        @Test
        @DisplayName("a stated file that does not exist is an empty layer, not an error")
        void absentStatedFileIsEmpty() {
            ConfigLoader loader = ConfigLoader.withGlobalConfigFile(
                    tempDirectory.resolve("never-written.yaml"));

            assertThat(loader.readGlobalLayer()).isEqualTo(MusicWizardConfig.empty());
            assertThat(loader.effectiveConfig(null, null))
                    .isEqualTo(MusicWizardConfig.DEFAULTS);
        }

        @Test
        @DisplayName("no global layer reads nothing and says so")
        void withoutGlobalConfigReadsNothing() {
            ConfigLoader loader = ConfigLoader.withoutGlobalConfig();

            assertThat(loader.globalConfigFileLocation()).isEmpty();
            assertThat(loader.readGlobalLayer()).isEqualTo(MusicWizardConfig.empty());
            assertThat(loader.effectiveConfig(null, null))
                    .isEqualTo(MusicWizardConfig.DEFAULTS);
        }

        @Test
        @DisplayName("null is not a way to say there is no global layer")
        void nullLocationIsRejected() {
            // It would behave identically to withoutGlobalConfig, which is
            // exactly the problem: an unset field would then mean "no global
            // layer" without anybody having decided that.
            assertThatThrownBy(() -> ConfigLoader.withGlobalConfigFile(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the environment is what the no-argument loader means")
        void noArgumentLoaderIsTheEnvironment() {
            // The CLI constructs loaders this way and must keep reading the
            // user's own config; that is the product feature, not the bug.
            assertThat(new ConfigLoader().globalConfigFileLocation())
                    .contains(ConfigLoader.globalConfigFile());
            assertThat(ConfigLoader.fromEnvironment().globalConfigFileLocation())
                    .contains(ConfigLoader.globalConfigFile());
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        @DisplayName("the global layer wins over the built-in defaults")
        void globalBeatsDefaults() throws IOException {
            Path file = writeGlobalConfig("notation:\n  paperSize: letter\n");

            MusicWizardConfig effective = ConfigLoader.withGlobalConfigFile(file)
                    .effectiveConfig(null, null);

            assertThat(effective.notation().paperSize()).isEqualTo("letter");
            // Keys the global layer left alone still come from the defaults.
            assertThat(effective.notation().transposeSemitones()).isZero();
            assertThat(effective.arrangement().maxNotesPerHand()).isEqualTo(4);
        }

        @Test
        @DisplayName("the workspace file wins over the global layer")
        void workspaceBeatsGlobal() throws IOException {
            Path global = writeGlobalConfig(
                    "notation:\n  paperSize: letter\n  capo: 3\n");
            Path workspaceConfig = tempDirectory.resolve("workspace-layer.yaml");
            Files.writeString(workspaceConfig, "notation:\n  paperSize: legal\n");

            MusicWizardConfig effective = ConfigLoader.withGlobalConfigFile(global)
                    .effectiveConfig(workspaceConfig, null);

            assertThat(effective.notation().paperSize()).isEqualTo("legal");
            // A key only the global layer sets must survive the workspace layer,
            // otherwise "each layer states what it changes" is not true.
            assertThat(effective.notation().capo()).isEqualTo(3);
        }

        @Test
        @DisplayName("command-line overrides win over both")
        void overridesBeatEverything() throws IOException {
            Path global = writeGlobalConfig("notation:\n  paperSize: letter\n");
            Path workspaceConfig = tempDirectory.resolve("workspace-layer.yaml");
            Files.writeString(workspaceConfig, "notation:\n  paperSize: legal\n");

            MusicWizardConfig overrides = new MusicWizardConfig(null, null,
                    new NotationConfig(null, "a3", null, null, null, null), null, null, null);

            MusicWizardConfig effective = ConfigLoader.withGlobalConfigFile(global)
                    .effectiveConfig(workspaceConfig, overrides);

            assertThat(effective.notation().paperSize()).isEqualTo("a3");
        }

        @Test
        @DisplayName("a workspace layers on the global config its loader was given")
        void workspaceUsesItsLoadersGlobalLayer() throws IOException {
            Path global = writeGlobalConfig("notation:\n  paperSize: letter\n");

            Workspace workspace = Workspace.create(
                    tempDirectory.resolve("song.mwz"), newSourceFile(),
                    ConfigLoader.withGlobalConfigFile(global));

            assertThat(workspace.effectiveConfig().notation().paperSize())
                    .isEqualTo("letter");

            workspace.updateConfig(new MusicWizardConfig(null, null,
                    new NotationConfig(null, "legal", null, null, null, null), null, null, null));

            assertThat(workspace.effectiveConfig().notation().paperSize())
                    .isEqualTo("legal");
        }
    }

    @Nested
    @DisplayName("isolation from the environment")
    class Isolation {

        /**
         * The regression proper. Plants a config where the environment says the
         * user's lives, and shows that a loader told to have no global layer
         * does not see it while {@code fromEnvironment()} does — the two halves
         * of #133 in one test: the suite stops depending on the developer's
         * machine, and the feature that depends on it keeps working.
         */
        @Test
        @DisplayName("a stated layer is unaffected by a config at the environment's location")
        void statedLayerIgnoresTheEnvironment() throws IOException {
            withGlobalConfigInTheEnvironment("notation:\n  paperSize: letter\n", () -> {
                assertThat(ConfigLoader.withoutGlobalConfig()
                        .effectiveConfig(null, null).notation().paperSize())
                        .isEqualTo("a4");

                Workspace workspace = Workspace.create(
                        tempDirectory.resolve("song.mwz"), newSourceFile(),
                        ConfigLoader.withoutGlobalConfig());
                assertThat(workspace.effectiveConfig().notation().paperSize())
                        .isEqualTo("a4");

                // And the environment-reading loader still reads it, so this is
                // isolation rather than the feature having been deleted.
                assertThat(ConfigLoader.fromEnvironment()
                        .effectiveConfig(null, null).notation().paperSize())
                        .isEqualTo("letter");
            });
        }

        /**
         * The loader-less {@code Workspace} factories must keep reading the
         * user's own config, because they are the ones the CLI uses:
         * {@code InitCommand} creates that way and {@code analyze},
         * {@code render} and {@code info} all open that way. Isolating the
         * suite by quietly stopping them would delete the feature rather than
         * isolate the tests, and nothing else in the suite would notice —
         * round-1 review confirmed both of these survive as mutants without
         * them.
         */
        @Test
        @DisplayName("the loader-less create and open still layer the user's own config")
        void loaderLessFactoriesReadTheEnvironment() throws IOException {
            // Asserts the location rather than a planted file's content. Both
            // kill the mutants that switch these factories to
            // withoutGlobalConfig(), and this way costs no write at the shared
            // environment location -- which is the whole reason this class needs
            // locking. That statedLayerIgnoresTheEnvironment reads a real file
            // end to end is what makes the location assertion mean something.
            Path root = tempDirectory.resolve("song.mwz");
            Workspace created = Workspace.create(root, newSourceFile());
            assertThat(created.configLoader().globalConfigFileLocation())
                    .contains(ConfigLoader.globalConfigFile());

            assertThat(Workspace.open(root).configLoader().globalConfigFileLocation())
                    .contains(ConfigLoader.globalConfigFile());
        }

        /**
         * And the loader-taking {@code open} must actually use the loader it is
         * given. Three-way rather than two: the expected {@code legal} is what
         * only the stated file says, distinguishing "used the given loader"
         * from "used none" ({@code a4}) and from "went to the environment
         * anyway" ({@code letter}).
         */
        @Test
        @DisplayName("open uses the loader it is given, not the environment")
        void openUsesTheLoaderItIsGiven() throws IOException {
            Path stated = writeGlobalConfig("notation:\n  paperSize: legal\n");
            Path root = tempDirectory.resolve("song.mwz");
            Workspace.create(root, newSourceFile(), ConfigLoader.withoutGlobalConfig());

            Workspace reopened = Workspace.open(root, ConfigLoader.withGlobalConfigFile(stated));

            // Three-way without needing a file at the environment location:
            // "legal" is what only the stated file says, ruling out "used no
            // loader" (a4), and the location rules out "went to the environment
            // anyway" -- which no longer needs a config planted there to detect.
            assertThat(reopened.effectiveConfig().notation().paperSize()).isEqualTo("legal");
            assertThat(reopened.configLoader().globalConfigFileLocation()).contains(stated);
        }

        /**
         * Runs {@code body} with {@code yaml} planted exactly where the
         * environment says the user's global config lives, and takes it away
         * again — including the directories, so a run leaves the environment
         * location as it found it: absent.
         */
        private void withGlobalConfigInTheEnvironment(String yaml, ThrowingBody body)
                throws IOException {
            Path file = disposableEnvironmentConfigFile();
            holdingTheEnvironmentLock(() -> {
                Files.createDirectories(file.getParent());
                Files.writeString(file, yaml);
                try {
                    body.run();
                } finally {
                    Files.deleteIfExists(file);
                    // Directories too: leaving them behind would mean a later
                    // run could not tell a stale config from a fresh one.
                    deleteIfEmpty(file.getParent());
                    deleteIfEmpty(file.getParent().getParent());
                }
            });
        }

        /**
         * Runs {@code body} with exclusive use of the environment's config
         * location, against every other JVM sharing this module's
         * {@code target/} as well as against every other thread in this one.
         *
         * <p>Two locks are needed because the location has two scopes and the
         * class-level {@code @ResourceLock} only covers the narrower one.
         * {@code XDG_CONFIG_HOME} is {@code ${project.build.directory}}-derived
         * — one path per <i>module</i>, not per JVM — so two {@code mvn}
         * invocations in the same checkout plant and delete in each other's
         * windows. That is not theoretical: review round 2 measured it failing
         * 7 of 12 concurrent JVMs once round 1 took the number of plant/delete
         * windows from one to three, against 0 of 12 before. A {@link FileLock}
         * is held by the JVM rather than the thread, which is exactly the
         * complement of what {@code @ResourceLock} gives, so the pair covers
         * both and neither covers both alone.
         *
         * <p>The lock file sits beside {@code no-global-config} rather than
         * inside it, since that directory is what the callers delete.
         */
        private void holdingTheEnvironmentLock(ThrowingBody body) throws IOException {
            Path lockFile = buildDirectory().resolve("global-config-environment.lock");
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = openLockFile(lockFile);
                    FileLock ignored = acquire(channel, lockFile)) {
                body.run();
            }
        }

        /**
         * Opens the lock file, turning the one way that fails into a sentence
         * naming its own remedy.
         *
         * <p>A leftover file this user cannot open — a container build as root
         * over a bind-mounted checkout, then a build as the developer — would
         * otherwise surface as a bare {@code AccessDeniedException} on both
         * tests that take the lock, including the one whose whole job is to
         * name the file that is causing trouble.
         */
        private FileChannel openLockFile(Path lockFile) throws IOException {
            try {
                return FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new IOException("cannot open the environment lock at " + lockFile
                        + " -- delete it, or run mvn clean. It is only a lock; it holds"
                        + " nothing worth keeping.", e);
            }
        }

        /**
         * Takes the lock, waiting for a peer JVM but not forever.
         *
         * <p>Waiting rather than skipping, because a skipped test reports as a
         * pass and this class exists to catch something that otherwise passes
         * silently. Bounded rather than {@code channel.lock()}, because there is
         * no {@code forkedProcessTimeoutInSeconds} in any pom, so a peer wedged
         * inside the lock would hang the suite with no diagnostic at all. The
         * bound is generous: contention means another whole test JVM, and only
         * a stuck one takes a minute.
         */
        private FileLock acquire(FileChannel channel, Path lockFile) throws IOException {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (true) {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
                if (System.nanoTime() >= deadline) {
                    // Fail rather than skip: see above.
                    throw new IOException("another JVM has held the environment lock at "
                            + lockFile + " for 60s. A test JVM sharing this module's"
                            + " target/ is stuck, or a stale lock survived one.");
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted waiting for " + lockFile, e);
                }
            }
        }

        private void deleteIfEmpty(Path directory) throws IOException {
            try {
                Files.deleteIfExists(directory);
            } catch (java.nio.file.DirectoryNotEmptyException e) {
                // Something else put a file there; leave it alone.
            }
        }

        /**
         * States the invariant directly, so that if it is ever broken the
         * suite says which file is at fault instead of failing an assertion
         * about paper sizes several classes away.
         *
         * <p>Two assertions, because absence alone is not the invariant. On a
         * machine with no global config — CI, and any developer who is not also
         * a user — absence holds whether or not the build isolates anything, so
         * a suite that had stopped isolating would look identical. The first
         * assertion is the one that fires everywhere: the location must be
         * inside this module's {@code target/}, which is true only because the
         * parent pom puts it there. Lose that block and CI says so.
         *
         * <p>This is the same argument {@link #acquire} makes about waiting
         * rather than skipping, and it is why the sibling tests' {@code abort}
         * is not enough on its own. An abort protects them from writing into a
         * real {@code ~/.config}, which is right — but it means losing the pom
         * block turns the regression test into a skip, and a skipped test
         * reports as a pass. Review round 4 confirmed exactly that: both blocks
         * deleted gave {@code Tests run: 327, Failures: 0, Skipped: 1} and
         * BUILD SUCCESS.
         *
         * <p>An IDE run with no {@code XDG_CONFIG_HOME} therefore fails here,
         * which is the intended answer rather than a cost — for the reason
         * above, that the alternative is the regression test skipping silently.
         * Not because the rest of {@code mw-core} would then read the
         * developer's config: it would not, since every test in this module
         * states which layer it means. The modules that would are the ones
         * driving whole commands, and they are why the pom block exists.
         *
         * <p>Takes the environment lock even though it only reads: a sibling
         * plants at the very location it asserts is absent, and without the
         * lock a concurrent JVM's plant reads here as a real global config.
         * Round 2 saw exactly that, once in twelve.
         */
        @Test
        @DisplayName("the test JVM must not be able to see a real global config")
        void theTestJvmSeesNoGlobalConfig() throws IOException {
            Path file = ConfigLoader.globalConfigFile();
            Path buildDirectory = buildDirectory();

            holdingTheEnvironmentLock(() -> {
                // A plain path comparison rather than AssertJ's startsWith,
                // which canonicalises and so requires the file to exist -- and
                // the whole point here is that it must not.
                assertThat(isUnderBuildDirectory(file))
                        .withFailMessage("""
                                The global config location for this test JVM is %s, \
                                which is outside %s, so this JVM resolves the config \
                                of whoever is running rather than an isolated one \
                                (#133). That file %s. No test in this module will \
                                have read it either way, because they all state \
                                which config layer they mean; the ones that would \
                                are in the modules driving whole commands. The \
                                parent pom points XDG_CONFIG_HOME under target/ for \
                                surefire and failsafe; if that block is gone, \
                                restore it, and an IDE needs the same setting in \
                                its run configuration.""",
                                file, buildDirectory,
                                // Reported, not asserted, and with no consequence
                                // attached to either answer: this assertion does
                                // not look at existence and short-circuits before
                                // the one that does. Saying what follows from it
                                // would be the diagnostic guessing -- which is how
                                // the previous two versions of this message came
                                // to be measurably false.
                                Files.exists(file) ? "exists" : "does not exist")
                        .isTrue();

                assertThat(file)
                        .withFailMessage("""
                                A global config exists at %s. That location is \
                                this module's isolated one -- the first assertion \
                                just established it -- so the build is configured \
                                correctly and a run died between planting a config \
                                there and removing it. Deleting the file, or mvn \
                                clean, is enough. What it would go on to affect is \
                                left unsaid here on purpose: this test has not \
                                measured it.""", file)
                        .doesNotExist();
            });
        }

        /**
         * Where the environment points, having refused to hand back anything
         * outside this module's {@code target/}.
         *
         * <p>The build points {@code XDG_CONFIG_HOME} under {@code target/} so
         * every module's tests get an empty global layer. If that ever stops
         * being true this returns nothing rather than a path, because the
         * alternative is a test writing into the developer's real
         * {@code ~/.config} — the file whose existence this whole issue is
         * about not disturbing.
         */
        private Path disposableEnvironmentConfigFile() {
            Path file = ConfigLoader.globalConfigFile();
            Path buildDirectory = buildDirectory();
            if (!isUnderBuildDirectory(file)) {
                abort("XDG_CONFIG_HOME does not point under " + buildDirectory
                        + " (it resolves to " + file + "), so this test will not write there."
                        + " The build sets it in the parent pom; see #133.");
            }
            return file;
        }

        /**
         * This module's {@code target/}. Resolved without the abort guard, so
         * the environment lock can be taken even in the state the guard exists
         * to refuse — {@code theTestJvmSeesNoGlobalConfig} must fail there,
         * not abort.
         */
        private Path buildDirectory() {
            return Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
                    .resolve("target").toAbsolutePath().normalize();
        }

        /**
         * Whether the build has isolated the global config location, which is
         * the single question behind both the guard test's first assertion and
         * the sibling tests' refusal to write. Component-wise, so a sibling
         * directory named {@code target-something} cannot pass for
         * {@code target}, and purely on the paths, so it does not depend on
         * anything existing.
         */
        private boolean isUnderBuildDirectory(Path file) {
            return file.toAbsolutePath().normalize().startsWith(buildDirectory());
        }
    }
}
