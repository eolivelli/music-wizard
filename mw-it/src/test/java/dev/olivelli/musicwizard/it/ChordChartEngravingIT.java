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
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.notation.ChordChart;
import dev.olivelli.musicwizard.notation.LilyPondRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LilyPond reading back the chord chart's bar arithmetic.
 *
 * <p>This is the oracle {@link ChordChart} did not have. Its bars were decided
 * by code that nothing else could contradict — the text chart counted them one
 * way, the engraving accumulated durations another, and a page whose bars did
 * not add up engraved without a murmur, because a bare {@code \chordmode}
 * sequence with no {@code \time} and no {@code |} gives LilyPond nothing to
 * count. #164 measured that: an emitter mutated so every bar came out half a bar
 * left the whole suite green.
 *
 * <p>Now the chart states its meter and closes each bar with a check, so
 * engraving it is a test of the arithmetic. The fixtures here are the ones the
 * arithmetic used to get wrong: a meter that is not 4/4 (#64), a phase taken
 * from the beat grid rather than from the first chord (#83), and progressions
 * whose chords do not line up with bars (#174).
 *
 * <p>{@link ChordChartTest} in {@code mw-notation} asserts the same bars sum,
 * by reading the emitted durations back; that runs in {@code mvn verify} and is
 * the guard a change trips first. This one is the version where the claim is not
 * ours.
 */
class ChordChartEngravingIT {

    @TempDir
    Path tempDirectory;

    private static PitchSpelling root(NoteLetter letter) {
        return new PitchSpelling(letter, Accidental.NATURAL, 4);
    }

    private static Chord chord(NoteLetter letter, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(root(letter), quality, from, to, Confidence.of(0.9));
    }

    private static final NoteLetter[] ROOTS =
            {NoteLetter.C, NoteLetter.G, NoteLetter.A, NoteLetter.F};

    /** Four bars of one chord each in a stated meter, at a stated bar length. */
    private static Score fourBarsIn(TimeSignature meter, double barSeconds, double quarterBpm) {
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chords.add(chord(ROOTS[i], ChordQuality.MAJOR, i * barSeconds, (i + 1) * barSeconds));
        }
        return Score.empty(TempoMap.constant(quarterBpm, meter), 4 * barSeconds)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** A 4/4 click track whose downbeats sit on a chosen one of the four phases. */
    private static Score clickTrackPhasedAt(int phase) {
        List<BeatGrid.Beat> beats = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int position = Math.floorMod(i - phase, 4);
            beats.add(new BeatGrid.Beat(i * 0.5, position == 0, position));
        }
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chords.add(chord(ROOTS[i], ChordQuality.MAJOR, i * 2.0, i * 2.0 + 2.0));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 12.0)
                .withBeatGrid(new BeatGrid(beats, Confidence.of(0.9), Confidence.of(0.9)))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** Eight chords a half-beat apart under a corrected {@code --tempo 60}. */
    private static Score eightChordsAtAForcedTempo() {
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            chords.add(chord(ROOTS[i % 4], ChordQuality.MAJOR, i * 0.5, i * 0.5 + 0.5));
        }
        return Score.empty(TempoMap.constantPulse(60, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** One sub-beat chord among four that are beat-aligned. */
    private static Score anOrnamentalChord() {
        double[][] spans = {{0, 1}, {1, 1.1}, {1.1, 2}, {2, 3}, {3, 4}};
        NoteLetter[] roots = {NoteLetter.C, NoteLetter.G, NoteLetter.A,
                NoteLetter.F, NoteLetter.C};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < spans.length; i++) {
            chords.add(chord(roots[i], ChordQuality.MAJOR, spans[i][0], spans[i][1]));
        }
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 4.0)
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    /** A quantized chart whose meter changes at bar 2, which only MIDI produces. */
    private static Score aMeterChange() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                .withMeterChange(2, TimeSignature.SIX_EIGHT);
        double[] starts = {0, 4, 8, 11};
        double[] ends = {4, 8, 11, 14};
        List<Chord> chords = new ArrayList<>();
        for (int i = 0; i < starts.length; i++) {
            chords.add(Chord.ofSeconds(root(ROOTS[i]), ChordQuality.MAJOR,
                            map.beatsToSeconds(starts[i]), map.beatsToSeconds(ends[i]),
                            Confidence.of(0.9))
                    .quantizedTo(starts[i], ends[i]));
        }
        return Score.empty(map, map.beatsToSeconds(14))
                .withChords(new ChordProgression(chords, Confidence.of(0.9)));
    }

    static Stream<Arguments> charts() {
        return Stream.of(
                        // #64: a bar that is not four quarters long.
                        Arguments.of("three-four", fourBarsIn(TimeSignature.THREE_FOUR, 1.5, 120)),
                        Arguments.of("six-eight", fourBarsIn(TimeSignature.SIX_EIGHT, 1.0, 180)),
                        Arguments.of("five-four", fourBarsIn(new TimeSignature(5, 4), 2.5, 120)),
                        Arguments.of("seven-eight",
                                fourBarsIn(new TimeSignature(7, 8), 1.75, 120)),
                        // #83: bar lines on the grid's phase, harmony off it.
                        Arguments.of("phase-2", clickTrackPhasedAt(2)),
                        Arguments.of("phase-3", clickTrackPhasedAt(3)),
                        // #174: chords that do not line up with bars.
                        Arguments.of("forced-tempo", eightChordsAtAForcedTempo()),
                        Arguments.of("ornamental", anOrnamentalChord()),
                        // A meter change mid-chart, which needs a second \time.
                        Arguments.of("meter-change", aMeterChange()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("charts")
    @DisplayName("LilyPond counts the chart's bars and finds nothing to say")
    void everyBarSumsToItsMeter(String name, Score score) {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve(name + "/chart.ly"),
                        ChordChart.toLilyPond(score));

        // The narrow question first, so a failure says which defect came back
        // rather than merely that LilyPond said something.
        assertThat(result.failedBarChecks()).as("%s", result.output()).isEmpty();
        // Then everything else it might have said. No tolerance: the one
        // assertEngravedCleanly knows about is a spacing complaint between a
        // tuplet number and a beam, and a chord chart has neither.
        assertEngravedCleanly(name, result);
        assertThat(result.pdf()).isPresent();
    }

    @Test
    @DisplayName("a chart bar that does not sum is caught, so the clean runs mean something")
    void aShortBarIsCaught() {
        Path lilypond = ConfigLoader.findLilyPond(null).orElse(null);
        assumeThat(lilypond).as("LilyPond is not installed").isNotNull();

        // The emitter's own 3/4 output with one bar shortened, rather than
        // hand-written LilyPond: a hand-copied file pins LilyPond's behaviour and
        // says nothing about ours (#92, round 4). No bar check is inserted here,
        // which is the whole difference from before #160 -- the chart brings its
        // own.
        String clean = ChordChart.toLilyPond(fourBarsIn(TimeSignature.THREE_FOUR, 1.5, 120));
        assertThat(clean).as("the emitter no longer writes a 3/4 bar this way, so the "
                        + "damage below would be a no-op and this test would pass for nothing")
                .contains("c2. |");
        String damaged = clean.replace("c2. |", "c2 |");

        LilyPondRenderer.Result result = new LilyPondRenderer(lilypond)
                .renderSource(tempDirectory.resolve("short/chart.ly"), damaged);

        // A bar a quarter short of its 3/4 meter: LilyPond reports the moment it
        // reached at the check, which is a half note where three quarters were
        // due. It still exits zero and still writes a page, which is why reading
        // the diagnostics is the only thing that catches this.
        assertThat(result.failedBarChecks()).as("%s", result.output()).contains("1/2");
        assertThat(result.succeeded()).as("%s", result.output()).isTrue();
    }
}
