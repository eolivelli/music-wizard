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

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How {@code render} writes the chords it was given, which is #227.
 *
 * <p>The estimator spells every black key as a sharp from a fixed table, so a
 * chart whose harmony is plainly B flat major reached a real page as
 * {@code A# F Gm D#}. This is where that is decided for every part at once:
 * {@code render} re-spells the score it read before any emitter sees it, so the
 * text chart and the engraved one cannot disagree, and neither can the staff
 * parts once they exist.
 *
 * <p>Everything here runs with {@code --no-pdf}; the fast suite must not shell
 * out to LilyPond, and none of these assertions is about engraving.
 */
class RenderChordSpellingTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("a B flat major chart is written with flats, in both files and on screen")
    void theChartComesOutInFlats() throws IOException {
        Path root = workspaceSpelledInSharps("karma");

        CliRunner.Result render = CliRunner.run("render", root.toString(), "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        String text = Files.readString(root.resolve("out").resolve("chords.txt"));
        assertThat(text).contains("Bb").contains("Eb").doesNotContain("A#").doesNotContain("D#");
        // Printed as well as written: this is the copy a user actually reads,
        // and it comes from a second call to the chart renderer.
        assertThat(render.out()).contains("Bb").doesNotContain("A#");

        String lilyPond = Files.readString(root.resolve("out").resolve("chords.ly"));
        assertThat(lilyPond).contains("bes").contains("ees")
                .doesNotContain("ais").doesNotContain("dis");
    }

    @Test
    @DisplayName("the saved transcription keeps the estimate it was analysed with")
    void theWorkspaceIsNotRewritten() {
        // Re-spelling is a decision about the printed page, so it must not turn
        // into a silent edit of what the analysis found. This also shows the
        // fixture really does arrive in sharps, so the assertions above are not
        // passing on an input that was already flat.
        Path root = workspaceSpelledInSharps("karma");

        CliRunner.Result render = CliRunner.run("render", root.toString(), "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        Score saved = Workspace.open(root).readScore().orElseThrow();
        assertThat(saved.chords().chords().stream().map(Chord::symbol))
                .containsExactly("A#", "F", "Gm", "D#");
    }

    /**
     * A workspace whose score holds I-V-vi-IV in B flat, spelled the way
     * {@code ChordEstimator} spells it.
     *
     * <p>The score is planted rather than analysed: what is under test is what
     * {@code render} does with a score, and running the estimator to obtain one
     * would make this depend on the accuracy of a stage it is not about.
     */
    private Path workspaceSpelledInSharps(String name) {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();

        ChordProgression chords = new ChordProgression(List.of(
                chord("A#4", ChordQuality.MAJOR, 0, 2),
                chord("F4", ChordQuality.MAJOR, 2, 4),
                chord("G4", ChordQuality.MINOR, 4, 6),
                chord("D#4", ChordQuality.MAJOR, 6, 8)),
                Confidence.of(0.8));
        Workspace workspace = Workspace.open(root);
        workspace.writeScore(Score.empty(TempoMap.constant(120), 8.0).withChords(chords));
        return root;
    }

    private static Chord chord(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.of(0.8));
    }
}
