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

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the renderer does to the process it starts, checked without LilyPond.
 *
 * <p>Engraving itself belongs in {@code mw-it} and does not run here: {@code mvn
 * verify} has to stay fast, offline and binary-free. What the subprocess is
 * handed is not engraving — it is arithmetic done before anything starts — so it
 * is checked here, against an environment poisoned on purpose rather than
 * against the machine's own.
 *
 * <p>That distinction is the finding of round 5 and is why this file was
 * rewritten: asserting on the ambient environment proved nothing, because the
 * build machine has no {@code LANGUAGE} set and every mutation of the code under
 * test passed.
 */
class LilyPondRendererTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("LilyPond is asked for English messages, whatever the machine says")
    void theSubprocessIsAskedForEnglishMessages() {
        // Poisoned first, so the assertion is about what the method does rather
        // than about what this machine happens not to have set.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_MESSAGES", "it_IT.UTF-8");
        environment.put("LANGUAGE", "it");
        environment.put("LC_ALL", "it_IT.UTF-8");
        environment.put("LANG", "it_IT.UTF-8");

        LilyPondRenderer.speakEnglish(builder);

        assertThat(environment).containsEntry("LC_MESSAGES", "C");
    }

    @Test
    @DisplayName("an LC_ALL that would override the message language is moved out of the way")
    void anAmbientLcAllIsNeutralisedRatherThanLeftToWin() {
        // Round 6 of review found the previous version of this a no-op for the
        // commonest way of all to set a locale: POSIX has LC_ALL override every
        // individual category, so writing LC_MESSAGES=C beside an inherited
        // LC_ALL=it_IT.UTF-8 changes nothing and LilyPond says "attenzione: bar
        // check failed" -- which is round 4's bug, reopened by round 5's fix.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "it_IT.UTF-8");
        environment.put("LANG", "it_IT.UTF-8");
        environment.put("LANGUAGE", "it");

        LilyPondRenderer.speakEnglish(builder);

        assertThat(environment).doesNotContainKey("LC_ALL").containsEntry("LC_MESSAGES", "C");
        // Left alone: gettext ignores LANGUAGE once the messages locale is C,
        // and LANG is outranked by LC_MESSAGES for messages.
        assertThat(environment)
                .containsEntry("LANGUAGE", "it")
                .containsEntry("LANG", "it_IT.UTF-8");
    }

    @Test
    @DisplayName("everything LC_ALL was covering is written out in its place, not just the ctype")
    void everyMaskedCategoryIsCarriedRatherThanUnMasked() {
        // Round 5 of review found round 4 setting a C ctype and breaking
        // canción.ly outright, so round 6 carried LC_ALL forward into LC_CTYPE.
        // Round 7 found that this is still the bug one category wide: LC_ALL
        // masks every category, and glibc's locale selection is all-or-nothing,
        // so one un-masked variable naming a locale that is not installed takes
        // the whole child down to C -- ctype included, and canción.ly with it.
        // An ambient "LC_ALL=it_IT.UTF-8 LC_TIME=de_DE.UTF-8" does exactly that,
        // and the second half of it is inert until the first is removed.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "es_ES.UTF-8");
        environment.put("LC_TIME", "de_DE.UTF-8");
        environment.put("LC_NUMERIC", "fr_FR.UTF-8");
        environment.remove("LC_CTYPE");

        LilyPondRenderer.speakEnglish(builder);

        // Every category POSIX and glibc define, bar the one being changed.
        assertThat(environment)
                .containsEntry("LC_CTYPE", "es_ES.UTF-8")
                .containsEntry("LC_NUMERIC", "es_ES.UTF-8")
                .containsEntry("LC_TIME", "es_ES.UTF-8")
                .containsEntry("LC_COLLATE", "es_ES.UTF-8")
                .containsEntry("LC_MONETARY", "es_ES.UTF-8")
                .containsEntry("LC_PAPER", "es_ES.UTF-8")
                .containsEntry("LC_NAME", "es_ES.UTF-8")
                .containsEntry("LC_ADDRESS", "es_ES.UTF-8")
                .containsEntry("LC_TELEPHONE", "es_ES.UTF-8")
                .containsEntry("LC_MEASUREMENT", "es_ES.UTF-8")
                .containsEntry("LC_IDENTIFICATION", "es_ES.UTF-8")
                .containsEntry("LC_MESSAGES", "C");
    }

    @Test
    @DisplayName("the character type that was in force is the one kept, not the one being ignored")
    void anOverriddenCharacterTypeIsReplacedByTheOneThatWasWinning() {
        // LC_CTYPE set and LC_ALL set: the effective ctype is LC_ALL's, because
        // it outranks. Keeping the LC_CTYPE that was being ignored would change
        // how bytes are decoded, which is the thing this method must not do --
        // so the move is unconditional rather than putIfAbsent.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_CTYPE", "POSIX");
        environment.put("LC_ALL", "ja_JP.UTF-8");

        LilyPondRenderer.speakEnglish(builder);

        assertThat(environment).containsEntry("LC_CTYPE", "ja_JP.UTF-8");
    }

    @Test
    @DisplayName("a machine with no LC_ALL keeps the character type it already had")
    void nothingIsInventedWhenThereIsNoLcAllToMove() {
        // The ordinary case, and the one that must not acquire a stray LC_CTYPE:
        // an empty LC_ALL is not a setting either, since POSIX has it fall
        // through to the individual categories.
        for (String lcAll : new String[] {null, ""}) {
            ProcessBuilder builder = new ProcessBuilder("true");
            Map<String, String> environment = builder.environment();
            environment.remove("LC_CTYPE");
            if (lcAll == null) {
                environment.remove("LC_ALL");
            } else {
                environment.put("LC_ALL", lcAll);
            }
            environment.put("LANG", "de_DE.UTF-8");

            LilyPondRenderer.speakEnglish(builder);

            assertThat(environment)
                    .as("LC_ALL was %s", lcAll == null ? "unset" : "empty")
                    .doesNotContainKey("LC_CTYPE")
                    .doesNotContainKey("LC_TIME")
                    .containsEntry("LANG", "de_DE.UTF-8")
                    .containsEntry("LC_MESSAGES", "C");
        }
    }

    @Test
    @DisplayName("engraving actually goes through it, rather than the method merely existing")
    void theRendererAppliesItToTheProcessItStarts() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // Round 6 of review found that deleting both call sites left every test
        // green: the seam was tested and the wiring was not. A stand-in binary
        // that reports the locale it was started with is the only thing that can
        // see the difference without LilyPond.
        Path script = tempDirectory.resolve("reports-locale");
        Files.writeString(script, """
                #!/bin/sh
                echo "LC_MESSAGES=[${LC_MESSAGES-unset}]"
                exit 1
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path source = tempDirectory.resolve("part.ly");
        Files.writeString(source, "% nothing to engrave\n");

        LilyPondRenderer renderer = new LilyPondRenderer(script);

        assertThat(renderer.render(source).output()).contains("LC_MESSAGES=[C]");
        // And --version, which parses output too. Round 7 found the previous
        // assertion here -- that a version was present at all -- satisfied by
        // any output whatsoever, so deleting the call in version() was killed by
        // nothing.
        assertThat(renderer.version()).contains("LC_MESSAGES=[C]");
    }

    @Test
    @DisplayName("a binary that cannot engrave is reported, not thrown")
    void aFailedRunIsAResultRatherThanAnException() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // The renderer's own contract, and the reason a failed engraving does
        // not lose the analysis that produced it. Uses a stand-in binary rather
        // than LilyPond, so it stays in the offline suite.
        Path script = tempDirectory.resolve("not-lilypond");
        Files.writeString(script, "#!/bin/sh\necho 'it went wrong'\nexit 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path source = tempDirectory.resolve("part.ly");
        Files.writeString(source, "% nothing to engrave\n");

        LilyPondRenderer.Result result = new LilyPondRenderer(script).render(source);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.pdf()).isEmpty();
        assertThat(result.output()).contains("it went wrong");
    }
}
