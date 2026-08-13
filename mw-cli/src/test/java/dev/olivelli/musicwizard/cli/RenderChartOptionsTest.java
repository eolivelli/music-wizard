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

import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code render --beat-marks} and {@code --repeat-tags} produce, which is
 * #416 and #417.
 *
 * <p>Here rather than beside the engraving tests because what these hold is the
 * route, not the lane: an option this command accepts, layers into the config,
 * echoes back and reads with nothing is the shape #129 had, and it writes every
 * file and exits 0 while doing it. {@code BeatMarksTest} holds what the marks
 * say; these hold that asking for them reaches them.
 *
 * <p>Everything runs with {@code --no-pdf}: the fast suite must not shell out to
 * LilyPond, and no assertion here is about engraving.
 */
class RenderChartOptionsTest {

    @TempDir
    Path directory;

    /** The marks ride in the chart's only {@code Lyrics} context. */
    private static boolean hasMarks(Path chart) throws IOException {
        return Files.readString(chart).contains("\\lyricmode {");
    }

    /** The tags ride in the chart's only {@code Dynamics} context. */
    private static boolean hasTags(Path chart) throws IOException {
        return Files.readString(chart).contains("\\new Dynamics");
    }

    @Test
    @DisplayName("the flag draws the marks and nothing else does")
    void theFlagDrawsTheMarks() throws IOException {
        Path plain = tracked("plain");
        Path marked = tracked("marked");

        assertThat(CliRunner.run("render", plain.toString(), "--no-pdf").exitCode()).isZero();
        assertThat(CliRunner.run("render", marked.toString(), "--beat-marks", "--no-pdf")
                .exitCode()).isZero();

        assertThat(hasMarks(plain.resolve("out/chords.ly"))).isFalse();
        assertThat(hasMarks(marked.resolve("out/chords.ly"))).isTrue();
    }

    @Test
    @DisplayName("the workspace config works exactly as the flag does")
    void theConfigLayerIsHonoured() throws IOException {
        // A preference belongs in workspace.yaml as much as on the command line,
        // which is the half of #129 that had to be filed twice.
        Path workspace = tracked("song");
        Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                new MusicWizardConfig.NotationConfig(null, null, null, null, null, true, null),
                null, null, null));

        CliRunner.Result render = CliRunner.run("render", workspace.toString(), "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(hasMarks(workspace.resolve("out/chords.ly"))).isTrue();
    }

    @Test
    @DisplayName("the flag beats the config, in both directions")
    void theFlagWins() throws IOException {
        Path off = tracked("off");
        Workspace.open(off).updateConfig(new MusicWizardConfig(null, null,
                new MusicWizardConfig.NotationConfig(null, null, null, null, null, true, null),
                null, null, null));
        Path on = tracked("on");
        Workspace.open(on).updateConfig(new MusicWizardConfig(null, null,
                new MusicWizardConfig.NotationConfig(null, null, null, null, null, false, null),
                null, null, null));

        CliRunner.run("render", off.toString(), "--no-beat-marks", "--no-pdf");
        CliRunner.run("render", on.toString(), "--beat-marks", "--no-pdf");

        assertThat(hasMarks(off.resolve("out/chords.ly"))).isFalse();
        assertThat(hasMarks(on.resolve("out/chords.ly"))).isTrue();
    }

    @Test
    @DisplayName("a score with no tracked grid says so rather than dropping the request")
    void aScoreWithNoTrackedGridSaysSo() throws IOException {
        // Every score read from a MIDI file, where the beats are declared rather
        // than heard. The files are all written and the command exits 0, so
        // silence here would be a confident wrong answer about a page that does
        // not carry what was asked for.
        Path workspace = untracked("midi-like");

        CliRunner.Result render = CliRunner.run(
                "render", workspace.toString(), "--beat-marks", "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(render.all()).contains("no beat marks were drawn");
        assertThat(hasMarks(workspace.resolve("out/chords.ly"))).isFalse();
    }

    @Test
    @DisplayName("nothing is said when the marks were never asked for")
    void silenceWhenNoneWereAskedFor() {
        Path workspace = untracked("quiet");

        CliRunner.Result render = CliRunner.run("render", workspace.toString(), "--no-pdf");

        assertThat(render.all()).doesNotContain("beat marks");
    }

    @Test
    @DisplayName("the repeat tags are off unless asked for, in both outputs")
    void repeatTagsAreOffUnlessAskedFor() throws IOException {
        Path plain = tracked("plain-tags");
        Path tagged = tracked("tagged");

        assertThat(CliRunner.run("render", plain.toString(), "--no-pdf").exitCode()).isZero();
        assertThat(CliRunner.run("render", tagged.toString(), "--repeat-tags", "--no-pdf")
                .exitCode()).isZero();

        assertThat(hasTags(plain.resolve("out/chords.ly"))).isFalse();
        assertThat(Files.readString(plain.resolve("out/chords.txt"))).doesNotContain("Tags");
        assertThat(hasTags(tagged.resolve("out/chords.ly"))).isTrue();
        assertThat(Files.readString(tagged.resolve("out/chords.txt"))).contains("Tags");
    }

    @Test
    @DisplayName("the tags reach the config layer and the printed copy alike")
    void repeatTagsReachEveryCopy() throws IOException {
        // The chart echoed to the terminal is a second call to the renderer, so
        // it can disagree with the file. Both are asserted.
        Path workspace = tracked("tags-config");
        Workspace.open(workspace).updateConfig(new MusicWizardConfig(null, null,
                new MusicWizardConfig.NotationConfig(null, null, null, null, null, null, true),
                null, null, null));

        CliRunner.Result render = CliRunner.run("render", workspace.toString(), "--no-pdf");

        assertThat(render.exitCode()).as(render.all()).isZero();
        assertThat(hasTags(workspace.resolve("out/chords.ly"))).isTrue();
        assertThat(render.out()).contains("Tags");

        CliRunner.Result off = CliRunner.run(
                "render", workspace.toString(), "--no-repeat-tags", "--no-pdf");

        assertThat(hasTags(workspace.resolve("out/chords.ly"))).isFalse();
        assertThat(off.out()).doesNotContain("Tags");
    }

    /** A workspace whose planted score carries a tracked beat grid. */
    private Path tracked(String name) {
        List<Double> beats = new ArrayList<>();
        for (double at = 0; at < 24.0; at += 0.5) {
            beats.add(at);
        }
        return planted(name, Optional.of(BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR,
                Confidence.of(0.9))));
    }

    /** A workspace whose planted score carries none, as a MIDI import does. */
    private Path untracked(String name) {
        return planted(name, Optional.empty());
    }

    /**
     * Imports a source and writes a score straight into the workspace.
     *
     * <p>Planted rather than analysed, as the other {@code render} tests plant
     * theirs and for the same reason: what is under test is what {@code render}
     * does with a score, and analysing one would make these depend on the
     * accuracy of a stage they are not about.
     */
    private Path planted(String name, Optional<BeatGrid> grid) {
        Path source = directory.resolve(name + ".wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.2, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        Path root = directory.resolve(name + ".mwz");
        CliRunner.Result init = CliRunner.run("init", source.toString(), "-w", root.toString());
        assertThat(init.exitCode()).as(init.all()).isZero();
        Workspace workspace = Workspace.open(root);
        Score score = Score.empty(TempoMap.constant(120), 24.0).withChords(twelveBars());
        if (grid.isPresent()) {
            score = score.withBeatGrid(grid.get());
        }
        workspace.writeScore(score);
        return root;
    }

    /** Three printings of one four-bar line, so the chart has a line to tag. */
    private static ChordProgression twelveBars() {
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            chords.add(Chord.ofSeconds(
                    new PitchSpelling(roots[i % 4], Accidental.NATURAL, 4),
                    i % 4 == 2 ? ChordQuality.MINOR : ChordQuality.MAJOR,
                    i * 2.0, i * 2.0 + 2.0, Confidence.of(0.9)));
        }
        return new ChordProgression(chords, Confidence.of(0.9));
    }
}
