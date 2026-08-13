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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLayeringTest {

    @TempDir
    Path tempDirectory;

    // No global layer, so mw-core's rule has no exception: a test in this
    // module states which config layer it means. This one never reads the
    // global layer at all -- only readLayer(Path), write and yamlMapper -- but
    // "harmless because of what it happens not to call" is the reasoning #133
    // was filed about.
    private final ConfigLoader loader = ConfigLoader.withoutGlobalConfig();

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
                    new NotationConfig(null, "letter", null, null, null, null), null, null, null);

            MusicWizardConfig resolved = MusicWizardConfig.DEFAULTS.overriddenBy(over);

            assertThat(resolved.notation().paperSize()).isEqualTo("letter");
        }

        @Test
        @DisplayName("an unset sibling keeps the value from below")
        void unsetSiblingsSurvive() {
            MusicWizardConfig over = new MusicWizardConfig(null, null,
                    new NotationConfig(null, "letter", null, null, null, null), null, null, null);

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
                    new NotationConfig(null, "letter", null, null, null, null), null, null, null);
            MusicWizardConfig workspace = new MusicWizardConfig(null, null,
                    new NotationConfig(null, "a3", null, null, null, null), null, null, null);

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
                    new NotationConfig("/definitely/not/here/lilypond", null, null, null, null, null),
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
                    new NotationConfig(fake.toString(), null, null, null, null, null),
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

        @Test
        @DisplayName("two stray quotes do not merge the entries between them")
        void strayQuotesDoNotMergeEntries() throws Exception {
            // Tracking quotes as a running toggle makes an even number of
            // strays look balanced, so everything between them becomes one
            // unusable entry — the same swallowing failure, harder to see.
            Path executable = fakeBinary("lilypond.exe");
            String path = "/nowhere/a\"" + java.io.File.pathSeparator
                    + tempDirectory + java.io.File.pathSeparator + "/nowhere/b\"";

            assertThat(ConfigLoader.discover(path, true)).contains(executable);
        }

        @Test
        @DisplayName("on POSIX a leading quote does not join entries")
        void doesNotJoinEntriesOnPosix() throws Exception {
            // The other half of the POSIX rule: a quote must not let an entry
            // run past a separator either. Only reachable when the entry
            // *begins* with one, which is why the a"b directory misses it.
            Path executable = fakeBinary("lilypond");
            String path = "\"/nowhere/a" + java.io.File.pathSeparator + tempDirectory
                    + java.io.File.pathSeparator + "/nowhere/b\"";

            assertThat(ConfigLoader.discover(path, false, List.of())).contains(executable);
        }

        @Test
        @DisplayName("a stray opening quote does not cost the entries after it")
        void strayOpeningQuoteDoesNotCostLaterEntries() throws Exception {
            // The stray quote before /nowhere/lily can reach the closing quote
            // of the well-formed entry at the end, merging the directory
            // between them into an entry that names nothing.
            Path executable = fakeBinary("lilypond.exe");
            String separator = java.io.File.pathSeparator;
            String path = "/usr/bin" + separator + "\"/nowhere/lily" + separator
                    + tempDirectory + separator + "\"/nowhere/tools\"";

            assertThat(ConfigLoader.discover(path, true, List.of())).contains(executable);
        }

        @Test
        @DisplayName("a PATH wrapped in one pair of quotes finds any of its entries")
        void searchesInsideAWhollyQuotedPath() throws Exception {
            // set PATH="%PATH%;C:\lily\bin" quotes the whole variable, and puts
            // the interesting directory last. Reading the lot as one directory
            // is defensible; finding nothing when the directory is right there
            // is not. Every position has to work: the quotes come off before
            // the fallback splits, or the first and last entries keep one and
            // are rejected for carrying it.
            Path executable = fakeBinary("lilypond.exe");
            String separator = java.io.File.pathSeparator;
            String directory = tempDirectory.toString();
            for (String path : List.of(
                    "\"" + directory + separator + "/nowhere/a" + separator + "/nowhere/b\"",
                    "\"/nowhere/a" + separator + directory + separator + "/nowhere/b\"",
                    "\"/nowhere/a" + separator + "/nowhere/b" + separator + directory + "\"",
                    "\"" + directory + separator + "/nowhere/a\"",
                    "\"/nowhere/a" + separator + directory + "\"")) {
                assertThat(ConfigLoader.discover(path, true, List.of()))
                        .as("%s", path)
                        .contains(executable);
            }
        }

        @Test
        @DisplayName("a mis-quoted Windows entry is read again without its quotes")
        void readsAMisquotedEntryWithoutItsQuotes() throws Exception {
            // `set PATH="C:\lily\bin;%PATH%` — one quote, no closer. Read as
            // written the entry names nothing, and a quote cannot be part of a
            // Windows filename, so the only reading left is the one without it.
            Path executable = fakeBinary("lilypond.exe");

            assertThat(ConfigLoader.discover("\"" + tempDirectory, true, List.of()))
                    .contains(executable);
            assertThat(ConfigLoader.discover(tempDirectory + "\"", true, List.of()))
                    .contains(executable);
        }

        @Test
        @DisplayName("on POSIX quotes are never stripped to make an entry work")
        void doesNotStripQuotesOnPosix() throws Exception {
            // A quote is a legal POSIX filename character, so a"b names a"b and
            // not ab. Stripping would search a directory nobody listed.
            Path directory = Files.createDirectory(tempDirectory.resolve("ab"));
            fakeBinaryIn(directory, "lilypond");
            String entry = tempDirectory.resolve("a\"b").toString();

            assertThat(ConfigLoader.discover(entry, false, List.of())).isEmpty();

            // The other way in: a leading quote, as PATH="/opt/lily/bin:$PATH
            // in a mis-escaped script produces. Here the literal reading fails
            // — the entry is relative, since it starts with the quote — so
            // without the platform guard the stripped reading would be tried
            // and would find a directory the user did not list.
            fakeBinary("lilypond");
            assertThat(ConfigLoader.discover("\"" + tempDirectory, false, List.of()))
                    .isEmpty();
        }

        @Test
        @DisplayName("splitting PATH loses nothing, however it is quoted")
        void splittingIsLossless() {
            // The split is the fiddliest code here and every entry rule is
            // downstream of it, so pin the invariant directly: the entries
            // rejoined must be the input, byte for byte.
            String separator = java.io.File.pathSeparator;
            for (String path : List.of("", separator, separator + separator + separator,
                    "a" + separator, separator + "a", "\"", "\"\"", "\"\"\"", "\"\"\"\"",
                    "\"a\"" + separator + "b", "\"a" + separator + "b\"",
                    "\"a" + separator + "b", "a\"" + separator + "\"b",
                    "\"a\"b" + separator + "c", "a" + separator + "b\"")) {
                for (boolean windows : List.of(true, false)) {
                    assertThat(String.join(separator, ConfigLoader.splitPath(path, windows)))
                            .as("%s, windows=%s", path, windows)
                            .isEqualTo(path);
                }
            }
        }

        @Test
        @DisplayName("a PATH of stray quotes is parsed in linear time")
        void doesNotRescanForEveryStrayQuote() {
            // Each unterminated quote used to rescan to the end of the string,
            // which is quadratic: 300 kB of them took the best part of a minute
            // and RenderCommand calls discovery twice.
            String separator = java.io.File.pathSeparator;
            String path = ("\"/nowhere" + separator).repeat(60_000);

            long start = System.nanoTime();
            assertThat(ConfigLoader.splitPath(path, true)).hasSize(60_001);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // Generous enough not to be flaky; the quadratic version needs
            // minutes for this input.
            assertThat(elapsedMillis).isLessThan(5_000);
        }

        @Test
        @DisplayName("a stray quote costs its own Windows entry")
        void skipsWindowsEntryWithAStrayQuote() throws Exception {
            Path directory = quotedNameDirectory();
            Path executable = fakeBinaryIn(directory, "lilypond.exe");
            // The control: the same directory is searched when the quote is not
            // being read as quoting.
            assertThat(ConfigLoader.discover(directory.toString(), true, List.of()))
                    .isEmpty();
            assertThat(executable).exists();
        }

        @Test
        @DisplayName("on POSIX a quote is an ordinary filename character")
        void treatsQuoteAsOrdinaryOnPosix() throws Exception {
            Path directory = quotedNameDirectory();
            Path executable = fakeBinaryIn(directory, "lilypond");

            assertThat(ConfigLoader.discover(directory.toString(), false, List.of()))
                    .contains(executable);
        }

        @Test
        @DisplayName("a .bat or .cmd wrapper counts on Windows, with .exe first")
        void findsWindowsWrapperScripts() throws Exception {
            Path batOnly = Files.createDirectory(tempDirectory.resolve("bat"));
            Path bat = fakeBinaryIn(batOnly, "lilypond.bat");
            Path cmdOnly = Files.createDirectory(tempDirectory.resolve("cmd"));
            Path cmd = fakeBinaryIn(cmdOnly, "lilypond.cmd");
            Path both = Files.createDirectory(tempDirectory.resolve("both"));
            fakeBinaryIn(both, "lilypond.cmd");
            Path exe = fakeBinaryIn(both, "lilypond.exe");

            assertThat(ConfigLoader.discover(batOnly.toString(), true)).contains(bat);
            assertThat(ConfigLoader.discover(cmdOnly.toString(), true)).contains(cmd);
            assertThat(ConfigLoader.discover(both.toString(), true)).contains(exe);
        }

        @Test
        @DisplayName("the prefixes are searched when PATH yields nothing")
        void fallsBackToThePrefixes() throws Exception {
            // The mechanism behind blocker behaviour #2: Homebrew is not on a
            // non-login shell's PATH, so a miss on PATH must not end the search.
            Path executable = fakeBinary("lilypond");

            assertThat(ConfigLoader.discover("/nowhere", false, List.of(tempDirectory.toString())))
                    .contains(executable);
            assertThat(ConfigLoader.discover(null, false, List.of(tempDirectory.toString())))
                    .contains(executable);
        }

        @Test
        @DisplayName("PATH wins over the prefixes")
        void pathBeatsThePrefixes() throws Exception {
            Path onPath = Files.createDirectory(tempDirectory.resolve("path"));
            Path preferred = fakeBinaryIn(onPath, "lilypond");
            Path underPrefix = Files.createDirectory(tempDirectory.resolve("prefix"));
            fakeBinaryIn(underPrefix, "lilypond");

            assertThat(ConfigLoader.discover(onPath.toString(), false,
                    List.of(underPrefix.toString()))).contains(preferred);
        }

        @Test
        @DisplayName("a relative prefix is skipped, like a relative PATH entry")
        void skipsRelativePrefix() throws Exception {
            // The prefixes are constants today, but the rule that we never
            // resolve a relative directory against the working directory should
            // not depend on which list a directory arrived in.
            Path executable = fakeBinary("lilypond");
            Path base = Path.of("").toAbsolutePath();
            assumeTrue(base.getRoot().equals(tempDirectory.getRoot()),
                    "no relative path exists from the working directory to the temp directory");
            String relative = base.relativize(tempDirectory.toAbsolutePath()).toString();

            Optional<Path> found = ConfigLoader.discover(null, false, List.of(relative));

            assertThat(found).isNotEqualTo(Optional.of(Path.of(relative).resolve("lilypond")));
            assertThat(found).isNotEqualTo(Optional.of(executable));
        }

        @Test
        @DisplayName("an unparseable prefix does not end the search")
        void toleratesMalformedPrefix() throws Exception {
            Path executable = fakeBinary("lilypond");

            assertThat(ConfigLoader.discover(null, false,
                    List.of("bad\u0000prefix", tempDirectory.toString())))
                    .contains(executable);
        }

        @Test
        @DisplayName("the POSIX prefixes still name the Homebrew locations")
        void posixPrefixesNameHomebrew() {
            // Issue #17's whole reason for existing: Homebrew installs outside a
            // non-login shell's PATH, and `mw doctor` finds it only through this
            // list. Nothing else in the suite notices if it is emptied.
            assertThat(ConfigLoader.searchPrefixes(false)).containsExactly(
                    "/home/linuxbrew/.linuxbrew/bin",
                    System.getProperty("user.home") + "/.linuxbrew/bin",
                    "/opt/homebrew/bin",
                    "/usr/local/bin",
                    "/usr/bin");
            assertThat(ConfigLoader.searchPrefixes(true)).isEmpty();
        }

        /**
         * A directory whose name contains a quote — legal on POSIX, impossible
         * on Windows, which is the point of the two tests that use it.
         */
        private Path quotedNameDirectory() throws Exception {
            try {
                return Files.createDirectory(tempDirectory.resolve("a\"b"));
            } catch (java.io.IOException e) {
                assumeTrue(false, "this filesystem rejects a quote in a filename");
                throw e;
            }
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
