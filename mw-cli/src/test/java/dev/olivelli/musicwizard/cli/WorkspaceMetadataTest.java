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

import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code mw init --title} is for: reaching the printed page.
 *
 * <p>It reached {@code workspace.yaml} and stopped there. The workspace knew the
 * title and the artist, {@code score.json} held {@code null} for both, and the
 * engraver reads the score -- so every chart the audio path produced came out
 * headed "Untitled" with no artist however carefully the workspace had been
 * labelled. That is #216, and it was observed on a real recording rather than
 * reasoned about.
 *
 * <p>Through the CLI, because the defect was entirely in the wiring: the model
 * field existed, serialized, and was already read by both renderers, and the
 * MIDI path filled it in. A unit test of any one of those pieces passed
 * throughout.
 */
class WorkspaceMetadataTest {

    @TempDir
    Path directory;

    /** A short synthesised tone, decoded by the natives that ship in the jar. */
    private Path audioSource() {
        Path source = directory.resolve("tone.wav");
        SignalFactory.writeWav(source, SignalFactory.chord(
                        SignalFactory.majorTriad(60), 1.0, SignalFactory.DEFAULT_SAMPLE_RATE),
                SignalFactory.DEFAULT_SAMPLE_RATE);
        return source;
    }

    /** The four-chord fixture, whose conductor track names it "Four chords". */
    private Path midiSource() {
        return MidiFixtures.write(MidiFixtures.fourChordSong(), directory.resolve("four.mid"));
    }

    private Path init(Path source, String name, String... metadata) {
        Path root = directory.resolve(name + ".mwz");
        List<String> arguments = new ArrayList<>(
                List.of("init", source.toString(), "-w", root.toString()));
        arguments.addAll(List.of(metadata));

        CliRunner.Result result = CliRunner.run(arguments.toArray(String[]::new));

        assertThat(result.exitCode()).as(result.all()).isZero();
        return root;
    }

    private Score analyse(Path root) {
        CliRunner.Result analyze = CliRunner.run("analyze", root.toString());
        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        return Workspace.open(root).readScore().orElseThrow();
    }

    @Test
    @DisplayName("an analysed recording is saved under the name the workspace was given")
    void theAudioPathCarriesTheWorkspaceMetadata() {
        Path root = init(audioSource(), "tone",
                "--title", "Hanno ucciso l'uomo ragno", "--artist", "883 (karaoke)");

        Score score = analyse(root);

        assertThat(score.title()).contains("Hanno ucciso l'uomo ragno");
        assertThat(score.artist()).contains("883 (karaoke)");
    }

    @Test
    @DisplayName("and the chart engraved from it is headed with them")
    void theEngravedChartIsHeaded() throws Exception {
        // The end of the chain, because that is where the defect was visible.
        // The two steps in between -- the score file, and the renderer reading
        // it -- were each already right on their own.
        Path root = init(audioSource(), "tone",
                "--title", "Hanno ucciso l'uomo ragno", "--artist", "883 (karaoke)");
        analyse(root);

        CliRunner.Result render = CliRunner.run("render", root.toString());

        assertThat(render.exitCode()).as(render.all()).isZero();
        String source = Files.readString(
                Workspace.open(root).outputDirectory().resolve("chords.ly"));
        assertThat(source)
                .contains("title = \"Hanno ucciso l'uomo ragno\"")
                .contains("composer = \"883 (karaoke)\"")
                .doesNotContain("Untitled");
    }

    @Test
    @DisplayName("a second analysis serves the cache and still writes the metadata")
    void theCachedPathCarriesItToo() {
        // The entry is keyed on the recording and the options, so it holds a
        // score with no metadata on it. Applying the workspace's after the cache
        // is consulted rather than before is what makes the second run of a
        // command produce the same file as the first -- and the second run is
        // the normal one, since the first is the slow one nobody repeats.
        Path root = init(audioSource(), "tone", "--title", "Once Analysed");
        analyse(root);

        CliRunner.Result second = CliRunner.run("analyze", root.toString());

        assertThat(second.out()).contains("reusing the cached analysis");
        assertThat(Workspace.open(root).readScore().orElseThrow().title())
                .contains("Once Analysed");
    }

    @Test
    @DisplayName("a title typed by a person outranks the one found in the file")
    void theWorkspaceOutranksTheFile() {
        // The fixture's conductor track is named "Four chords", which
        // MidiTranscriber reads as the title. Somebody who typed a title meant
        // it: a track name is a title only by convention.
        Path root = init(midiSource(), "titled", "--title", "The Real Title");

        assertThat(analyse(root).title()).contains("The Real Title");
    }

    @Test
    @DisplayName("and where nobody typed one, the file's own title survives")
    void theFileIsNotOverwrittenWithNothing() {
        // The half of the rule that a fix reaching only the first case would
        // break: propagating an absent title as null would have cleared a title
        // the MIDI path had just read.
        Path root = init(midiSource(), "untitled");

        assertThat(analyse(root).title()).contains("Four chords");
    }

    @Test
    @DisplayName("the two fields are carried separately, so one does not clear the other")
    void theFieldsAreIndependent() {
        // A workspace that names the artist and not the piece. Reading the pair
        // as one value would have dropped the file's title on the floor.
        Path root = init(midiSource(), "artistonly", "--artist", "883");

        Score score = analyse(root);

        assertThat(score.title()).contains("Four chords");
        assertThat(score.artist()).contains("883");
    }
}
