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

package dev.olivelli.musicwizard.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole pipeline on a signal whose harmony is known exactly: audio in,
 * chord chart and engraved PDF out.
 *
 * <p>Slow and dependent on an external binary, so it lives behind
 * {@code -Pintegration}. This is the test that fails when the stages stop
 * agreeing with each other, which unit tests on either side of a seam cannot
 * catch.
 */
class EndToEndIT {

    @TempDir
    Path tempDirectory;

    /** I-V-vi-IV in C: the most common progression in popular music. */
    private Path writeFourChordSong() {
        float[] samples = SignalFactory.clickTrackWithChords(120, new double[][] {
                SignalFactory.majorTriad(60),   // C
                SignalFactory.majorTriad(67),   // G
                SignalFactory.minorTriad(69),   // Am
                SignalFactory.majorTriad(65),   // F
        }, 4, 32, SignalFactory.DEFAULT_SAMPLE_RATE);

        Path file = tempDirectory.resolve("fourchords.wav");
        SignalFactory.writeWav(file, samples, SignalFactory.DEFAULT_SAMPLE_RATE);
        return file;
    }

    @Test
    @DisplayName("a recording becomes a workspace, a score and a chord chart")
    void fullPipeline() throws Exception {
        Path source = writeFourChordSong();

        Workspace workspace = Workspace.create(tempDirectory.resolve("song.mwz"), source);
        Score score = new AudioTranscriber().transcribe(
                workspace.sourceFile(), AudioTranscriber.Options.defaults());
        workspace.writeScore(score);

        // Persisted and readable, which is what makes analysis and rendering
        // separate commands rather than one long one.
        Score reloaded = workspace.readScore().orElseThrow();
        assertThat(reloaded).isEqualTo(score);

        assertThat(reloaded.tempoMap().averageTempo(reloaded.durationSeconds()))
                .isBetween(115.0, 128.0);
        assertThat(reloaded.chords().chords()).extracting(Chord::symbol)
                .startsWith("C", "G", "Am", "F");

        String chart = ChordChart.toText(reloaded);
        assertThat(chart).contains("| C").contains("| G").contains("| Am").contains("| F");
    }

    @Test
    @DisplayName("the bar lines land where the chords change")
    void downbeatsAgreeWithChords() {
        // The stages agreeing with each other is the whole point of running them
        // in one pipeline, and the seam this crosses is the one issue #27 found
        // broken: beats and chords agreed, and the downbeats were half a bar out
        // because they were phased from onset energy rather than from harmony.
        // Only the transcriber exercises the ordering that makes the harmonic
        // phase available, so it cannot be checked in mw-dsp alone.
        Score score = new AudioTranscriber().transcribe(
                writeFourChordSong(), AudioTranscriber.Options.defaults());

        List<Double> downbeats = score.beatGrid().orElseThrow().downbeatTimes();
        assertThat(downbeats).isNotEmpty();
        for (Chord chord : score.chords().chords()) {
            assertThat(downbeats).anySatisfy(downbeat ->
                    assertThat(downbeat).isCloseTo(chord.startSeconds(), within(0.06)));
        }
    }

    @Test
    @DisplayName("a clip too short to hold a bar still transcribes")
    void veryShortClipStillTranscribes() {
        // A clip this short tracks exactly one beat, and one beat means no beat
        // has chroma on both sides of it -- so there is nothing for the downbeat
        // stage to score. It has to fall back rather than reject the input:
        // Chroma.beatSynchronous cannot produce a beat-synchronous chroma from a
        // single beat, so validating that before checking whether there is
        // anything to score turned a transcribable recording into an error.
        // Reachable only with a tempo override, since inferring a tempo from one
        // beat fails earlier and for its own reasons.
        float[] samples = SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.3, SignalFactory.DEFAULT_SAMPLE_RATE);
        Path source = tempDirectory.resolve("blip.wav");
        SignalFactory.writeWav(source, samples, SignalFactory.DEFAULT_SAMPLE_RATE);

        Score score = new AudioTranscriber().transcribe(
                source, new AudioTranscriber.Options(120.0, null, null));

        assertThat(score.beatGrid().orElseThrow().downbeatTimes()).hasSize(1);
    }

    @Test
    @DisplayName("LilyPond engraves the generated chart to a PDF")
    void engravesToPdf() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        Path source = writeFourChordSong();
        Score score = new AudioTranscriber().transcribe(
                source, AudioTranscriber.Options.defaults());

        Path ly = tempDirectory.resolve("out/chords.ly");
        LilyPondRenderer.Result result =
                new LilyPondRenderer(lilypond).renderSource(ly, ChordChart.toLilyPond(score));

        assertThat(result.succeeded())
                .as("LilyPond output: %s", result.output())
                .isTrue();
        Path pdf = result.pdf().orElseThrow();
        assertThat(Files.size(pdf)).isGreaterThan(1000);
        // A PDF, not an empty file with the right extension.
        byte[] header = new byte[5];
        try (var in = Files.newInputStream(pdf)) {
            assertThat(in.read(header)).isEqualTo(5);
        }
        assertThat(new String(header, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("re-analysing the same recording produces an identical score")
    void isDeterministic() {
        Path source = writeFourChordSong();

        Score first = new AudioTranscriber().transcribe(source, AudioTranscriber.Options.defaults());
        Score second = new AudioTranscriber().transcribe(source, AudioTranscriber.Options.defaults());

        assertThat(second).isEqualTo(first);
    }
}
