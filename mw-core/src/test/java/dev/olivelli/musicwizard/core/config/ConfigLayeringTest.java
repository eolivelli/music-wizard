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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.olivelli.musicwizard.core.config.MusicWizardConfig.AccidentalPreference;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig.LlmConfig;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig.NotationConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLayeringTest {

    @TempDir
    Path tempDirectory;

    private final ConfigLoader loader = new ConfigLoader();

    @Nested
    @DisplayName("layering")
    class Layering {

        @Test
        @DisplayName("an unset layer changes nothing")
        void emptyLayerIsANoOp() {
            MusicWizardConfig resolved = MusicWizardConfig.DEFAULTS
                    .overriddenBy(MusicWizardConfig.empty());

            assertThat(resolved).isEqualTo(MusicWizardConfig.DEFAULTS);
        }

        @Test
        @DisplayName("a set value wins over the layer beneath it")
        void moreSpecificLayerWins() {
            MusicWizardConfig over = new MusicWizardConfig(null, null,
                    new NotationConfig(null, "letter", null, null, null), null, null, null);

            MusicWizardConfig resolved = MusicWizardConfig.DEFAULTS.overriddenBy(over);

            assertThat(resolved.notation().paperSize()).isEqualTo("letter");
        }

        @Test
        @DisplayName("an unset sibling keeps the value from below")
        void unsetSiblingsSurvive() {
            MusicWizardConfig over = new MusicWizardConfig(null, null,
                    new NotationConfig(null, "letter", null, null, null), null, null, null);

            MusicWizardConfig resolved = MusicWizardConfig.DEFAULTS.overriddenBy(over);

            // Only paperSize was specified, so the rest must survive intact.
            assertThat(resolved.notation().transposeSemitones()).isZero();
            assertThat(resolved.notation().accidentalPreference())
                    .isEqualTo(AccidentalPreference.FROM_KEY);
            assertThat(resolved.arrangement().maxNotesPerHand()).isEqualTo(4);
        }

        @Test
        @DisplayName("false is a real value, not an absence")
        void falseOverridesTrue() {
            // The trap with nullable Booleans: `false` must win over a default of
            // `true`, rather than being treated as "unset".
            MusicWizardConfig over = new MusicWizardConfig(null, null, null, null, null,
                    new LlmConfig(null, null, null, false, null, null, null, null));

            MusicWizardConfig resolved = MusicWizardConfig.DEFAULTS.overriddenBy(over);

            assertThat(MusicWizardConfig.DEFAULTS.llm().repairLyrics()).isTrue();
            assertThat(resolved.llm().repairLyrics()).isFalse();
        }

        @Test
        @DisplayName("the last layer applied wins")
        void lastLayerWins() {
            MusicWizardConfig global = new MusicWizardConfig(null, null,
                    new NotationConfig(null, "letter", null, null, null), null, null, null);
            MusicWizardConfig workspace = new MusicWizardConfig(null, null,
                    new NotationConfig(null, "a3", null, null, null), null, null, null);

            MusicWizardConfig resolved = MusicWizardConfig.DEFAULTS
                    .overriddenBy(global)
                    .overriddenBy(workspace);

            assertThat(resolved.notation().paperSize()).isEqualTo("a3");
        }

        @Test
        @DisplayName("merging with null is a no-op")
        void nullLayerIsSafe() {
            assertThat(MusicWizardConfig.DEFAULTS.overriddenBy(null))
                    .isEqualTo(MusicWizardConfig.DEFAULTS);
        }
    }

    @Nested
    @DisplayName("serialization")
    class Serialization {

        @Test
        @DisplayName("an empty config writes no keys at all")
        void emptyConfigWritesNothing() throws Exception {
            // Regression guard: derived accessors such as isLlmEnabled() look like
            // bean getters to Jackson and previously leaked phantom keys such as
            // `llmEnabled` and `offline` into every persisted workspace file.
            String yaml = loader.yamlMapper()
                    .writeValueAsString(MusicWizardConfig.empty())
                    .trim();

            assertThat(yaml).isEqualTo("{}");
        }

        @Test
        @DisplayName("no derived accessor appears as a top-level key")
        void noDerivedKeysLeak() throws Exception {
            String yaml = loader.yamlMapper().writeValueAsString(MusicWizardConfig.DEFAULTS);

            // Derived accessors would surface as top-level keys, so check those
            // specifically. Nested keys such as ml.offline are genuine fields and
            // must be left alone.
            var topLevelKeys = yaml.lines()
                    .filter(line -> !line.isBlank() && !Character.isWhitespace(line.charAt(0)))
                    .map(line -> line.substring(0, line.indexOf(':')))
                    .toList();

            assertThat(topLevelKeys).containsExactlyInAnyOrder(
                    "schemaVersion", "analysis", "notation", "arrangement", "ml", "llm");
            assertThat(topLevelKeys)
                    .doesNotContain("llmEnabled", "offline", "lilypondPath");
        }

        @Test
        @DisplayName("round-trips through YAML unchanged")
        void roundTrips() {
            Path file = tempDirectory.resolve("config.yaml");
            loader.write(file, MusicWizardConfig.DEFAULTS);

            assertThat(loader.readLayer(file)).isEqualTo(MusicWizardConfig.DEFAULTS);
        }

        @Test
        @DisplayName("a missing file reads as an unset layer")
        void missingFileIsEmpty() {
            assertThat(loader.readLayer(tempDirectory.resolve("absent.yaml")))
                    .isEqualTo(MusicWizardConfig.empty());
        }

        @Test
        @DisplayName("a blank file reads as an unset layer")
        void blankFileIsEmpty() throws Exception {
            Path file = tempDirectory.resolve("blank.yaml");
            Files.writeString(file, "   \n");

            assertThat(loader.readLayer(file)).isEqualTo(MusicWizardConfig.empty());
        }

        @Test
        @DisplayName("keys from a newer version are ignored rather than fatal")
        void toleratesUnknownKeys() throws Exception {
            Path file = tempDirectory.resolve("future.yaml");
            Files.writeString(file, """
                    notation:
                      paperSize: letter
                      someFutureSetting: 42
                    aWholeNewSection:
                      enabled: true
                    """);

            MusicWizardConfig parsed = loader.readLayer(file);

            assertThat(parsed.notation().paperSize()).isEqualTo("letter");
        }
    }

    @Nested
    @DisplayName("LilyPond discovery")
    class LilyPondDiscovery {

        @Test
        @DisplayName("an explicit path that does not exist fails loudly")
        void rejectsBadExplicitPath() {
            // Falling back to a discovered binary would silently ignore an
            // explicit instruction, which is worse than refusing to run.
            MusicWizardConfig config = new MusicWizardConfig(null, null,
                    new NotationConfig("/definitely/not/here/lilypond", null, null, null, null),
                    null, null, null);

            assertThatThrownBy(() -> ConfigLoader.findLilyPond(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not an executable file");
        }

        @Test
        @DisplayName("an explicit path is used verbatim when it is executable")
        void honoursExplicitPath() throws Exception {
            // Stand in for the binary with any executable file.
            Path fake = tempDirectory.resolve("lilypond");
            Files.writeString(fake, "#!/bin/sh\necho fake\n");
            assertThat(fake.toFile().setExecutable(true)).isTrue();

            MusicWizardConfig config = new MusicWizardConfig(null, null,
                    new NotationConfig(fake.toString(), null, null, null, null),
                    null, null, null);

            assertThat(ConfigLoader.findLilyPond(config)).contains(fake);
        }

        @Test
        @DisplayName("survives a null config without throwing")
        void toleratesNullConfig() {
            // Must not throw; whether a binary is present depends on the machine.
            assertThat(ConfigLoader.findLilyPond(null)).isNotNull();
        }

        @Test
        @DisplayName("on Windows the executable is looked for as lilypond.exe")
        void findsWindowsExecutable() throws Exception {
            // Windows has no extensionless executables, so looking only for
            // "lilypond" means discovery can never succeed there.
            Path executable = fakeBinary("lilypond.exe");

            assertThat(ConfigLoader.discover(tempDirectory.toString(), true))
                    .contains(executable);
        }

        @Test
        @DisplayName("on POSIX the .exe name is not used")
        void ignoresWindowsExecutableOnPosix() throws Exception {
            Path executable = fakeBinary("lilypond.exe");

            assertThat(ConfigLoader.discover(tempDirectory.toString(), false))
                    .isNotEqualTo(Optional.of(executable));
        }

        @Test
        @DisplayName("an absolute PATH entry is searched")
        void findsPosixExecutableOnPath() throws Exception {
            Path executable = fakeBinary("lilypond");

            assertThat(ConfigLoader.discover(tempDirectory.toString(), false))
                    .contains(executable);
        }

        @Test
        @DisplayName("a relative PATH entry is skipped rather than resolved")
        void skipsRelativePathEntry() throws Exception {
            // The control above proves this same file is discoverable through an
            // absolute entry, so a miss here is about the entry being relative
            // and nothing else.
            Path executable = fakeBinary("lilypond");
            Path base = Path.of("").toAbsolutePath();
            assumeTrue(base.getRoot().equals(tempDirectory.getRoot()),
                    "no relative path exists from the working directory to the temp directory");
            String relative = base.relativize(tempDirectory.toAbsolutePath()).toString();
            assertThat(Path.of(relative)).isRelative();

            Optional<Path> found = ConfigLoader.discover(relative, false);

            // What the old code returned: a path resolved against whatever
            // directory the user happened to run the tool from, then executed.
            assertThat(found).isNotEqualTo(Optional.of(Path.of(relative).resolve("lilypond")));
            assertThat(found).isNotEqualTo(Optional.of(executable));
        }

        @Test
        @DisplayName("one unparseable PATH entry does not lose the rest")
        void toleratesMalformedPathEntry() throws Exception {
            Path executable = fakeBinary("lilypond");
            // A NUL is rejected by Path.of on every platform; on Windows a
            // wildcard in PATH does the same. Either used to throw out of
            // discovery entirely.
            String path = "bad\u0000entry" + java.io.File.pathSeparator + tempDirectory;

            assertThat(ConfigLoader.discover(path, false)).contains(executable);
        }

        @Test
        @DisplayName("a quoted Windows PATH entry is unquoted before use")
        void unquotesWindowsPathEntry() throws Exception {
            // cmd.exe strips these, so a quoted entry is a working PATH entry
            // for every other Windows tool.
            Path executable = fakeBinary("lilypond.exe");

            assertThat(ConfigLoader.discover("\"" + tempDirectory + "\"", true))
                    .contains(executable);
        }

        @Test
        @DisplayName("a separator inside quotes does not split a Windows entry")
        void doesNotSplitInsideQuotes() throws Exception {
            // The only way to put a directory whose name contains the separator
            // on PATH is to quote it; splitting first would turn one usable
            // entry into two unusable fragments.
            Path directory = Files.createDirectory(
                    tempDirectory.resolve("bin" + java.io.File.pathSeparatorChar + "x"));
            Path executable = fakeBinaryIn(directory, "lilypond.exe");

            assertThat(ConfigLoader.discover("\"" + directory + "\"", true))
                    .contains(executable);
        }

        @Test
        @DisplayName("an unbalanced quote does not swallow the rest of PATH")
        void unbalancedQuoteDoesNotSwallowTheRest() throws Exception {
            // Honouring an unterminated quote would make one malformed entry
            // cost every entry after it. The malformed entry itself is skipped:
            // a quote cannot be part of a Windows filename.
            Path executable = fakeBinary("lilypond.exe");
            String path = "\"C" + java.io.File.pathSeparator + tempDirectory;

            assertThat(ConfigLoader.discover(path, true)).contains(executable);
        }

        /** Stands in for the binary: any executable file with the right name. */
        private Path fakeBinary(String name) throws Exception {
            return fakeBinaryIn(tempDirectory, name);
        }

        private Path fakeBinaryIn(Path directory, String name) throws Exception {
            Path binary = directory.resolve(name);
            Files.writeString(binary, "#!/bin/sh\necho fake\n");
            assertThat(binary.toFile().setExecutable(true)).isTrue();
            return binary;
        }
    }
}
