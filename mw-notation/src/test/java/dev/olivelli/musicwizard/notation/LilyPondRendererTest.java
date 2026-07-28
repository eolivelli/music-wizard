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
    @DisplayName("nothing but the message language is touched, because the rest decodes filenames")
    void theCharacterTypeIsLeftAlone() {
        // Round 5 of review found the first version of this setting LC_ALL and
        // LANG as well, which also set the character type -- and LilyPond then
        // could not decode a non-ASCII filename off its own command line.
        // canción.ly engraved before that change and failed after it. This is
        // that regression, in the one form a unit test can hold: the variables
        // that decide how bytes are read are left exactly as they were.
        ProcessBuilder builder = new ProcessBuilder("true");
        Map<String, String> environment = builder.environment();
        environment.put("LC_ALL", "es_ES.UTF-8");
        environment.put("LANG", "es_ES.UTF-8");
        environment.put("LC_CTYPE", "es_ES.UTF-8");
        environment.put("LANGUAGE", "es");

        LilyPondRenderer.speakEnglish(builder);

        assertThat(environment)
                .containsEntry("LC_ALL", "es_ES.UTF-8")
                .containsEntry("LANG", "es_ES.UTF-8")
                .containsEntry("LC_CTYPE", "es_ES.UTF-8")
                // Left as it was, and harmless: gettext ignores LANGUAGE
                // entirely once the messages locale is C.
                .containsEntry("LANGUAGE", "es");
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
