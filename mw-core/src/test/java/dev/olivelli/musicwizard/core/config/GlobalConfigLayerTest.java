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
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>The class holds a resource lock because one test writes at the location
 * the environment names, which is process-wide however isolated each test's
 * {@code @TempDir} is. That is the same shape of hazard as #36, and the lock is
 * this class paying its own way rather than a fix for it.
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
                    new NotationConfig(null, "a3", null, null, null), null, null, null);

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
                    new NotationConfig(null, "legal", null, null, null), null, null, null));

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
            withGlobalConfigInTheEnvironment("notation:\n  paperSize: letter\n", () -> {
                Path root = tempDirectory.resolve("song.mwz");
                Workspace created = Workspace.create(root, newSourceFile());
                assertThat(created.effectiveConfig().notation().paperSize())
                        .isEqualTo("letter");

                assertThat(Workspace.open(root).effectiveConfig().notation().paperSize())
                        .isEqualTo("letter");
            });
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
            withGlobalConfigInTheEnvironment("notation:\n  paperSize: letter\n", () -> {
                Path stated = writeGlobalConfig("notation:\n  paperSize: legal\n");
                Path root = tempDirectory.resolve("song.mwz");
                Workspace.create(root, newSourceFile(), ConfigLoader.withoutGlobalConfig());

                Workspace reopened = Workspace.open(root, ConfigLoader.withGlobalConfigFile(stated));

                assertThat(reopened.effectiveConfig().notation().paperSize()).isEqualTo("legal");
                assertThat(reopened.configLoader().globalConfigFileLocation()).contains(stated);
            });
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
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml);
            try {
                body.run();
            } finally {
                Files.deleteIfExists(file);
                // Directories too: leaving them behind would mean a later run
                // could not tell a stale config from a fresh one.
                deleteIfEmpty(file.getParent());
                deleteIfEmpty(file.getParent().getParent());
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
         * <p>Cannot fail in CI, which has no such file, and cannot fail for a
         * developer who is not also a user. It fails for exactly the person the
         * issue is about — someone with a valid config and a build that has
         * stopped neutralising it — which is the population that otherwise gets
         * no signal at all.
         */
        @Test
        @DisplayName("the test JVM must not be able to see a real global config")
        void theTestJvmSeesNoGlobalConfig() {
            Path file = ConfigLoader.globalConfigFile();

            assertThat(file)
                    .withFailMessage("""
                            This test JVM can read a global config at %s, so any \
                            test that builds an effective config is reading it \
                            too and the suite depends on this machine (#133). \
                            If that path is under target/, a run died between \
                            planting and removing one, and deleting it is enough. \
                            Otherwise the build's XDG_CONFIG_HOME is not reaching \
                            this JVM: the parent pom points it under target/ for \
                            surefire and failsafe, and an IDE needs the same \
                            setting in its run configuration.""", file)
                    .doesNotExist();
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
            Path buildDirectory = Path.of(
                            System.getProperty("basedir", System.getProperty("user.dir")))
                    .resolve("target").toAbsolutePath().normalize();
            if (!file.toAbsolutePath().normalize().startsWith(buildDirectory)) {
                abort("XDG_CONFIG_HOME does not point under " + buildDirectory
                        + " (it resolves to " + file + "), so this test will not write there."
                        + " The build sets it in the parent pom; see #133.");
            }
            return file;
        }
    }
}
