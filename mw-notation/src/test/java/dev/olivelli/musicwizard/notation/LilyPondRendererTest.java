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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the renderer does to the process it starts, checked without LilyPond.
 *
 * <p>Engraving itself belongs in {@code mw-it}, and does not run here: {@code
 * mvn verify} has to stay fast, offline and binary-free. The environment the
 * subprocess is handed is not engraving — it is arithmetic this class does
 * before starting anything — so it is checked here against a stand-in binary
 * that reports what it was given.
 */
class LilyPondRendererTest {

    @TempDir
    Path tempDirectory;

    /**
     * A stand-in for LilyPond that prints the locale it was started with.
     *
     * <p>A shell script, so this is POSIX-only and says so rather than pretending
     * otherwise; #33 records that nothing here has ever run on Windows.
     */
    private static Path localeReporter(Path directory) throws Exception {
        Path script = directory.resolve("fake-lilypond");
        Files.writeString(script, """
                #!/bin/sh
                echo "LANGUAGE=[${LANGUAGE-unset}]"
                echo "LC_ALL=[${LC_ALL-unset}]"
                echo "LANG=[${LANG-unset}]"
                echo "LC_MESSAGES=[${LC_MESSAGES-unset}]"
                exit 1
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    @Test
    @DisplayName("LilyPond is run in one language, whatever the machine is set to")
    void theSubprocessIsPinnedToTheCLocale() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // Round 4 of review found LilyPond's diagnostics translated on a machine
        // with LANGUAGE set: a failed bar check comes out as "attenzione: bar
        // check failed" under Italian, which contains neither "warning" nor
        // "error". Everything that reads that output to find out whether
        // something went wrong therefore stops finding out, and stops silently.
        //
        // Checked here rather than in mw-it because it is not about engraving
        // and because reproducing it there would need the forked JVM's own
        // environment changed, which is a build-file setting that would then be
        // the thing under test.
        Path source = tempDirectory.resolve("part.ly");
        Files.writeString(source, "% nothing to engrave\n");

        String output = new LilyPondRenderer(localeReporter(tempDirectory)).render(source).output();

        assertThat(output)
                .as("gettext reads LANGUAGE before LC_ALL, so setting the others is not enough")
                .contains("LANGUAGE=[unset]")
                .contains("LC_ALL=[C]")
                .contains("LANG=[C]")
                .contains("LC_MESSAGES=[C]");
    }

    @Test
    @DisplayName("a binary that cannot engrave is reported, not thrown")
    void aFailedRunIsAResultRatherThanAnException() throws Exception {
        assumeThat(File.separatorChar).as("POSIX only; see #33").isEqualTo('/');

        // The property the locale test leans on, pinned separately so that it is
        // not silently what makes that one pass: a non-zero exit produces a
        // Result carrying the output, because a failed engraving must not lose
        // the analysis that produced it.
        Path source = tempDirectory.resolve("part.ly");
        Files.writeString(source, "% nothing to engrave\n");

        LilyPondRenderer.Result result =
                new LilyPondRenderer(localeReporter(tempDirectory)).render(source);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.pdf()).isEmpty();
        assertThat(result.output()).isNotEmpty();
    }
}
