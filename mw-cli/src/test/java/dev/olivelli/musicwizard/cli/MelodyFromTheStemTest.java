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

import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where {@code analyze --melody} reads its melody from (#559).
 *
 * <p>The fixture is a mix whose loudest periodic line is a bass note, which is
 * what a monophonic tracker returns from a band recording — and the fake
 * separator's "vocal" is a tone two octaves above it. So the pitches in the
 * score say which buffer the tracker was handed, which is the whole question.
 */
@DisplayName("the melody stage's input signal")
class MelodyFromTheStemTest {

    /** A2, and nothing else in the mix is periodic enough to outrank it. */
    private static final int BASS_MIDI_PITCH = 45;

    @TempDir
    Path directory;

    private Path workspaceDirectory;

    @BeforeEach
    void importFixture() throws IOException {
        int rate = SignalFactory.DEFAULT_SAMPLE_RATE;
        // Clicks so the beat tracker finds a grid -- without one the pipeline
        // returns an empty score and never reaches the melody stage at all.
        float[] samples = SignalFactory.clickTrack(120, 8.0, rate);
        float[] bass = SignalFactory.sine(
                SignalFactory.midiToHz(BASS_MIDI_PITCH), 8.0, rate);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f * samples[i] + 0.5f * bass[i];
        }
        Path source = directory.resolve("band.wav");
        SignalFactory.writeWav(source, samples, rate);
        workspaceDirectory = directory.resolve("band.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w",
                workspaceDirectory.toString()).exitCode()).isZero();
    }

    /** Points the workspace at one separation provider by id. */
    private void configureSeparation(String id) throws IOException {
        Path descriptor = workspaceDirectory.resolve("workspace.yaml");
        Files.writeString(descriptor, Files.readString(descriptor)
                + "\nconfig:\n  ml:\n    separationProvider: " + id + "\n"
                + "    asrProvider: fake-cli-asr\n"
                + "    alignmentProvider: no-such-alignment\n");
    }

    private List<Note> melodyNotes() {
        Score score = Workspace.open(workspaceDirectory).readScore().orElseThrow();
        return score.track(PartRole.LEAD_VOCAL).map(NoteTrack::notes).orElseThrow();
    }

    @Test
    @DisplayName("the tracker reads the separated vocal, not the mix")
    void theMelodyComesFromTheStem() throws IOException {
        configureSeparation("fake-cli-voice");

        CliRunner.Result analyze = CliRunner.run(
                "analyze", workspaceDirectory.toString(), "--melody");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("separating the vocal with fake-cli-voice")
                .contains("tracking the melody in the vocal stem");
        assertThat(melodyNotes()).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch())
                        .isEqualTo(FakeVoiceSeparationProvider.VOICE_MIDI_PITCH));
    }

    @Test
    @DisplayName("without a provider it reads the mix, and says so rather than failing")
    void noProviderKeepsTheMixMelody() throws IOException {
        configureSeparation("no-such-separation");

        CliRunner.Result analyze = CliRunner.run(
                "analyze", workspaceDirectory.toString(), "--melody");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("no separation provider")
                .contains("tracking the melody in the full mix");
        assertThat(melodyNotes()).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch()).isEqualTo(BASS_MIDI_PITCH));
    }

    @Test
    @DisplayName("--skip-separation keeps the mix melody, and is no longer a no-op")
    void skipSeparationKeepsTheMixMelody() throws IOException {
        configureSeparation("fake-cli-voice");

        CliRunner.Result analyze = CliRunner.run("analyze",
                workspaceDirectory.toString(), "--melody", "--skip-separation");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("--skip-separation");
        assertThat(analyze.err())
                .as("the option now changes this run, so nothing may say it does not")
                .doesNotContain("skipping separation changes nothing");
        assertThat(melodyNotes()).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch()).isEqualTo(BASS_MIDI_PITCH));
    }

    @Test
    @DisplayName("a melody and lyrics in one run separate once")
    void oneSeparationPerRun() throws IOException {
        configureSeparation("fake-cli-voice");
        int before = FakeVoiceSeparationProvider.SEPARATIONS.get();

        CliRunner.Result analyze = CliRunner.run("analyze",
                workspaceDirectory.toString(), "--melody", "--lyrics-language", "en");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.out()).contains("tracking the melody in the vocal stem")
                .contains("transcribed");
        assertThat(FakeVoiceSeparationProvider.SEPARATIONS.get() - before)
                .as("both stages read one stem")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a separator that cannot run leaves the melody, and is not cached as a stem")
    void aFailedSeparationDegradesAndDoesNotPoisonTheCache() throws IOException {
        configureSeparation("fake-cli-unavailable-separation");

        CliRunner.Result analyze = CliRunner.run(
                "analyze", workspaceDirectory.toString(), "--melody");

        assertThat(analyze.exitCode()).as(analyze.all()).isZero();
        assertThat(analyze.err()).contains(FailingSeparationProvider.REASON)
                .contains("not cached");
        assertThat(melodyNotes()).isNotEmpty().allSatisfy(note ->
                assertThat(note.midiPitch()).isEqualTo(BASS_MIDI_PITCH));

        // The key names the stem this run could not produce, so the next run --
        // the one where the model is finally there -- must not be served it.
        CliRunner.Result again = CliRunner.run(
                "analyze", workspaceDirectory.toString(), "--melody");
        assertThat(again.out()).doesNotContain("reusing the cached analysis");
    }
}
