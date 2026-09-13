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

package dev.olivelli.musicwizard.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the pages of a line alone print over and under the tune (#843). */
@DisplayName("a line alone on the page")
class LineAlonePageTest {

    private static final int SAMPLE_RATE = SignalFactory.DEFAULT_SAMPLE_RATE;

    @TempDir
    Path directory;

    private Path workspace;

    /** The flute take's tune in C, twice over, one sine per note at a note a beat. */
    @BeforeEach
    void analyseALineAlone() {
        int[] once = {76, 77, 79, 77, 76, 77, 79, 77, 76, 77, 76, 74, 72, 74, 72, 72};
        double beat = 0.5;
        float[] samples = new float[(int) (2 * once.length * beat * SAMPLE_RATE)];
        int at = 0;
        for (int repeat = 0; repeat < 2; repeat++) {
            for (int pitch : once) {
                float[] note = SignalFactory.sine(SignalFactory.midiToHz(pitch), beat, SAMPLE_RATE);
                System.arraycopy(note, 0, samples, at, Math.min(note.length, samples.length - at));
                at += note.length;
            }
        }
        Path source = directory.resolve("flute.wav");
        SignalFactory.writeWav(source, samples, SAMPLE_RATE);
        workspace = directory.resolve("flute.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w", workspace.toString())
                .exitCode()).isZero();
        CliRunner.Result analyze = CliRunner.run("analyze", workspace.toString(),
                "--melody", "--skip-separation", "--time-signature", "4/4");
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).as("the fixture is on the line-alone route")
                .contains("a line alone: the key is read from its notes")
                .contains("a line alone: nothing sounds under it to read chords from");
        CliRunner.Result render = CliRunner.run("render", workspace.toString(),
                "--parts", "chords,playable", "--no-pdf");
        assertThat(render.exitCode()).as(render.all()).isZero();
    }

    private String out(String name) throws IOException {
        return Files.readString(workspace.resolve("out").resolve(name));
    }

    @Test
    @DisplayName("the chart names no chord, and the run's record says why")
    void theChartNamesNoChord() throws IOException {
        String chart = out("chords.txt");
        List<String> symbols = new ArrayList<>();
        for (String line : chart.split("\n")) {
            if (!line.startsWith("|")) {
                continue;
            }
            for (String cell : line.split("\\|")) {
                for (String symbol : cell.trim().split("\\s+")) {
                    if (!symbol.isEmpty()) {
                        symbols.add(symbol);
                    }
                }
            }
        }
        assertThat(symbols).isNotEmpty().allMatch(
                symbol -> symbol.equals(ChordQuality.NONE.symbol()) || symbol.equals("%"));

        RunManifest.StageRun chords = Workspace.open(workspace).readRunManifest().orElseThrow()
                .stage(ChordTrace.STAGE).orElseThrow();
        assertThat(chords.reason()).contains("a line alone");
    }

    @Test
    @DisplayName("the playable part carries no accidental the key does not")
    void thePlayablePartIsSpelledByTheKey() throws IOException {
        String staff = out("lead-playable.ly");
        // Every note-head the staff writes, with its accidental suffix: a
        // line on the white keys prints none under the key it is read in.
        Matcher heads = Pattern.compile("(?<![a-z\\\\])([a-g])((?:is|es|s)*)(?=[',]*\\d)")
                .matcher(staff.substring(staff.indexOf("\\new Staff")));
        int counted = 0;
        while (heads.find()) {
            counted++;
            assertThat(heads.group(2)).as("the accidental on " + heads.group()).isEmpty();
        }
        assertThat(counted).isPositive();
        assertThat(out("lead-playable.musicxml")).doesNotContain("<alter>");
    }
}
