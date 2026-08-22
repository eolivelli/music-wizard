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
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LrcLyrics;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.ChartOptions;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LilyPond engraving the beat-mark lane (#433).
 *
 * <p>{@code BeatMarksTest} reads the lane's text; what only engraving can say
 * is that the lane's own overrides — the grey, the small font, the centred
 * anchor — are vocabulary LilyPond accepts, in a lane that is also passing
 * the same bar checks as the chords. The grids here are the ones checked by
 * hand when the lane landed and never gated since: one that drifts off any
 * constant bar, the same under words, and one whose downbeats were never
 * decided, which prints ticks rather than numbers.
 */
class BeatMarksIT {

    private static final ChartOptions MARKED = new ChartOptions(true, false);

    @TempDir
    Path tempDirectory;

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    /** A chart of {@code bars} bars at 120 BPM in 4/4, so a bar is two seconds. */
    private static Score chart(int bars) {
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            chords.add(Chord.ofSeconds(root(roots[i % 4]), ChordQuality.MAJOR,
                    i * 2.0, i * 2.0 + 2.0, Confidence.of(0.9)));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), bars * 2.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** The chart under a grid that slows as it goes, so no constant bar fits it. */
    private static Score drifting(int bars) {
        List<Double> beats = new ArrayList<>();
        for (int i = 0; i < bars * 4; i++) {
            beats.add(0.5 * i + 0.004 * i * i);
        }
        return chart(bars).withBeatGrid(
                BeatGrid.ofTimes(beats, TimeSignature.FOUR_FOUR, Confidence.of(0.9)));
    }

    /** The same, with words placed under the drifting marks. */
    private static Score driftingUnderWords(int bars) {
        Score score = drifting(bars);
        return score.withLyrics(LrcLyrics.parse("""
                [00:00.00]<00:00.00>uno <00:01.00>due <00:02.50>tre
                [00:08.00]<00:08.00>quattro <00:09.30>cinque
                """, score.durationSeconds()));
    }

    /** The chart under a grid whose downbeats were never decided. */
    private static Score unphased(int bars) {
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < bars * 4; i++) {
            beats.add(BeatGrid.Beat.unphased(0.5 * i));
        }
        return chart(bars).withBeatGrid(
                new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.1)));
    }

    static Stream<Arguments> charts() {
        return Stream.of(
                Arguments.of("drifting", drifting(8)),
                Arguments.of("drifting-under-words", driftingUnderWords(8)),
                Arguments.of("unphased-ticks", unphased(4)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("charts")
    @DisplayName("the beat-mark lane engraves without complaint, and its bars sum")
    void theLaneEngravesCleanly(String name, Score score) {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();
        String source = ChordChart.toLilyPond(score, MARKED);
        // The case must hold what it is named for before LilyPond is asked
        // about it, or a fixture rotting towards the phased shape would keep
        // this green while gating nothing.
        assertThat(source).contains(name.equals("unphased-ticks") ? "\".\"" : "\"1\"");

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve(name + "/chart.ly"), source);

        assertThat(result.failedBarChecks()).as("%s", result.output()).isEmpty();
        assertEngravedCleanly(name, result);
        assertThat(result.pdf()).isPresent();
    }
}
