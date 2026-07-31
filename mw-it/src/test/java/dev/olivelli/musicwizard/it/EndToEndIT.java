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

import static dev.olivelli.musicwizard.it.LilyPondComplaints.assertEngravedCleanly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        //
        // A tempo override is no longer needed to reach this: inferring a tempo
        // from one beat used to throw before the downbeat stage was asked
        // anything (#75), and the transcriber now falls back rather than asking
        // for a tempo that is not there. Kept on the override path, because that
        // is the combination this test was written for and the one where a
        // regression would be quietest.
        float[] samples = SignalFactory.chord(
                SignalFactory.majorTriad(60), 0.3, SignalFactory.DEFAULT_SAMPLE_RATE);
        Path source = tempDirectory.resolve("blip.wav");
        SignalFactory.writeWav(source, samples, SignalFactory.DEFAULT_SAMPLE_RATE);

        Score score = new AudioTranscriber().transcribe(
                source, new AudioTranscriber.Options(120.0, null, null));

        assertThat(score.beatGrid().orElseThrow().downbeatTimes()).hasSize(1);
    }

    @Test
    @DisplayName("a clip between the two analysis window lengths still transcribes")
    void aClipBetweenTheTwoWindowLengthsStillTranscribes() {
        // The whole pipeline over the gap #3 opened between two window lengths.
        // NnlsChroma analyses at 8192 samples -- 0.371 s at the analysis rate,
        // twice the plain front end's window -- so a clip in this band yields no
        // chroma frames at all while BeatTracker still tracks two pulses in it.
        // Chroma.beatSynchronous then built a clamp whose bounds crossed and
        // threw IllegalArgumentException out of transcribe().
        //
        // Covered here as well as in mw-dsp because this is the level a user
        // reaches it from: the CLI on a very short file, which is exactly what
        // someone does first when trying the tool out.
        // Swept over click rates as well as lengths, and that is the point
        // rather than thoroughness for its own sake. The first version of this
        // test used one click rate, 40 ms, and passed while the pipeline still
        // threw for 129 other combinations in the same band.
        //
        // The reason is the opposite of unlucky, which is what makes it worth
        // writing down. Reaching the downbeat stage at all needs three tracked
        // beats -- two is caught by an earlier fallback -- and at 0.34 s only
        // five click periods out of eighty-six between 30 and 200 ms yield
        // three. 40 ms was not a bad draw; almost any single rate would have
        // been. A test written from one stack trace inherits that stack trace's
        // blind spot, and the sweep is what removes it.
        int rate = SignalFactory.DEFAULT_SAMPLE_RATE;
        for (double seconds : new double[] {0.30, 0.32, 0.34, 0.36, 0.37}) {
            for (double clickSeconds : new double[] {0.032, 0.040, 0.062, 0.100}) {
                float[] clicks = new float[(int) (seconds * rate)];
                for (int i = 0; i < clicks.length; i += (int) (clickSeconds * rate)) {
                    clicks[i] = 1;
                }
                Path source = tempDirectory.resolve(
                        String.format("gap-%.0f-%.0f.wav", seconds * 1000, clickSeconds * 1000));
                SignalFactory.writeWav(source, clicks, rate);

                Score score = new AudioTranscriber().transcribe(
                        source, AudioTranscriber.Options.defaults());

                // Nothing to say about the harmony of a third of a second of
                // clicks, and saying nothing is the correct outcome rather than
                // a degraded one.
                assertThat(score.chords().isEmpty())
                        .as("%.2f s of clicks every %.0f ms", seconds, clickSeconds * 1000)
                        .isTrue();
                assertThat(score.beatGrid()).isPresent();
            }
        }
    }

    @Test
    @DisplayName("LilyPond engraves the generated chart to a PDF, without complaining about it")
    void engravesToPdf() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        Path source = writeFourChordSong();
        Score score = new AudioTranscriber().transcribe(
                source, AudioTranscriber.Options.defaults());

        Path ly = tempDirectory.resolve("out/chords.ly");
        LilyPondRenderer.Result result =
                new LilyPondRenderer(lilypond).renderSource(ly, ChordChart.toLilyPond(score));

        // #153: the three assertions below this one were all this test had, and
        // none of them reads what the engraver said. LilyPond writes a perfectly
        // large PDF beginning "%PDF-" and exits zero for a chart it complained
        // about, so on the output CLAUDE.md calls this project's strongest, the
        // diagnostics were the one thing nothing looked at.
        //
        // What this catches is worth being exact about, because round 1 of
        // review on #164 found the first draft overclaiming it, and the claim
        // has since changed. It caught anything LilyPond said about the chart
        // and *not* a bar of the wrong length, because the emitter wrote no bar
        // checks and no \time -- halving every chord engraved in silence under
        // 2.24.3 and 2.26.0 alike. #160 closed that in the emitter, so the gate
        // now covers both: the chart states its meter and ends every bar with a
        // check, and engravingComplaintsAreNoticed damages a bar to prove it.
        //
        // No tolerance is passed. The one assertEngravedCleanly knows about is a
        // spacing complaint about a tuplet number against a beam, and a chord
        // chart has neither -- a carve-out with nothing behind it is the dead
        // carve-out #92's review rounds spent two of themselves avoiding.
        assertEngravedCleanly("the chord chart", result);
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
    @DisplayName("a complaint about this chart is noticed, so the clean engraving means something")
    void engravingComplaintsAreNoticed() throws Exception {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        Score score = new AudioTranscriber().transcribe(
                writeFourChordSong(), AudioTranscriber.Options.defaults());
        String clean = ChordChart.toLilyPond(score);

        // The emitter's own output with a bar broken, rather than hand-written
        // LilyPond: engraving a hand-copied file pins LilyPond's behaviour and
        // says nothing about ours. Round 4 of #92 made that distinction and it
        // applies here unchanged.
        //
        // One edit now, where this needed two. It used to have to insert the bar
        // check as well as shorten the bar, because the emitter wrote none -- so
        // what it demonstrated was that the gate notices a complaint about a
        // chart of this shape, not that a chart bar which does not sum produces
        // one. #164 measured the difference: every bar halved, no check added,
        // and the whole suite stayed green. #160 gave the emitter its own
        // checks, so halving the first bar is now enough, and this test says the
        // thing it always wanted to say.
        assertThat(clean).as("the emitter no longer opens the chart this way; "
                + "the damage below would be a no-op and this test would pass for nothing")
                .contains("c1 |");
        String damaged = clean.replaceFirst("c1 \\|", "c2 |");

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("damaged/chords.ly"), damaged);

        // Exit zero, a real page, the right magic bytes -- every assertion
        // engravesToPdf had before #153, passing on a chart that is wrong.
        assertThat(result.succeeded()).as("%s", result.output()).isTrue();
        assertThat(Files.size(result.pdf().orElseThrow())).isGreaterThan(1000);

        // And the moment rather than the wording, so this survives both of
        // LilyPond's spellings: the bar reached a half note where a whole was
        // due. Asserting merely that something was said would not distinguish a
        // counted bar from an unparsed one.
        // Read through the product's own accessor, which is what #156 added so
        // that a failed bar check reaches a user rather than only a test.
        assertThat(result.failedBarChecks())
                .as("%s", result.output())
                .contains("1/2");
        // The gate itself, pointed at the damaged run: what engravesToPdf
        // asserts of the clean one has to fail here or it is asserting nothing.
        assertThatThrownBy(() -> assertEngravedCleanly("the damaged chart", result))
                .as("%s", result.output())
                .isInstanceOf(AssertionError.class);
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
