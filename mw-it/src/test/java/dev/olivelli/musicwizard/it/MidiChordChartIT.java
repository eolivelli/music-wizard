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
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.transcribe.MidiTranscriber;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The symbolic half of what {@code EndToEndIT} proves about the audio half: that
 * a file goes in and a chord chart comes out on paper.
 *
 * <p>This is the loop #115 broke. A MIDI import produced tempo, meter, keys and
 * notes but no harmony, so the chart was the string {@code (no chords were
 * found)} and the LilyPond behind it was a zero-duration score that the engraver
 * declined to page -- no warning a caller could act on, and no PDF. Asserting on
 * the chords alone would not have caught that, because a progression can be
 * present and still engrave to nothing.
 *
 * <p>The fixture is deliberately the same music as the audio end-to-end test, so
 * the two charts can be compared directly.
 */
class MidiChordChartIT {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("a MIDI file of I-V-vi-IV engraves to a chord chart PDF")
    void midiBecomesAnEngravedChart() throws Exception {
        Score score = new MidiTranscriber().transcribe(MidiFixtures.fourChordSong());

        String chart = ChordChart.toText(score);
        assertThat(chart).doesNotContain("no chords were found");
        // The fixture opens with a silent bar, then states the progression twice.
        assertThat(chart).contains("| N.C.")
                .contains("| C").contains("| G").contains("| Am").contains("| F");
        assertThat(score.chords().chords()).extracting(c -> c.symbol())
                .containsExactly("N.C.", "C", "G", "Am", "F", "C", "G", "Am", "F");

        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("chart.ly"), ChordChart.toLilyPond(score));

        assertThat(result.succeeded()).as("%s", result.output()).isTrue();
        // "skipping zero-duration score" is a warning rather than an error, so
        // the engraver reports success and writes nothing. Naming it is the
        // whole point of this assertion.
        assertThat(result.output()).doesNotContainIgnoringCase("zero-duration");
        Path pdf = result.pdf().orElseThrow();
        // An engraved page of chord symbols is tens of kilobytes; a blank one is
        // a couple.
        assertThat(Files.size(pdf)).isGreaterThan(10_000);
    }
}
