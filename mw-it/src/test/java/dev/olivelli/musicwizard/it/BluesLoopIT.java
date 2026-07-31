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

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The project's first tier-2 gate: a real recording, measured against changes
 * that are known exactly rather than inferred.
 *
 * <p>{@code samples/gmajorblues.mp3} is 711 seconds of a twelve-bar blues in G
 * played round and round — {@code samples/list.txt} records the sequence, and
 * this file is committed and does not change, so the ground truth is a constant
 * rather than an estimate. That combination is rare enough to be worth a gate:
 * every other accuracy number in this repository comes from audio we
 * synthesised, and {@code CLAUDE.md} is blunt about what those are worth.
 *
 * <p>What it is guarding is a regression that already happened once. Before #3
 * this recording produced a single chord span, {@code N.C.}, covering all 711
 * seconds — the failure #185 describes — and nothing in the suite noticed,
 * because the synthetic fixtures were all green. Thresholds here are therefore
 * set well below what is measured, so that this fails when the pipeline stops
 * working rather than when it changes.
 *
 * <p>{@code *IT}, so it runs only under {@code -Pintegration}: it decodes and
 * analyses twelve minutes of audio. One analysis serves every assertion.
 */
class BluesLoopIT {

    /**
     * The changes, from {@code samples/list.txt}, as pitch classes with C = 0.
     *
     * <p>G7 G7 G7 G7 / C7 C7 G7 G7 / D7 C7 G7 D7 — a twelve-bar blues with the
     * standard turnaround.
     */
    private static final int[] CYCLE = {7, 7, 7, 7, 0, 0, 7, 7, 2, 0, 7, 2};

    /**
     * How long one twelve-bar cycle lasts, in seconds.
     *
     * <p>A property of this recording, measured from it rather than assumed:
     * the chroma's self-similarity over lag peaks at 27.15 s, which is 106.1
     * beats a minute in four, and 26.2 cycles fill the recording.
     *
     * <p>Bars are taken from this rather than from the tracked beat grid, and
     * the distinction matters. The tracker reports 108.1 BPM, about 2% fast, so
     * its bars slide against the music by a whole beat every cycle and by many
     * bars over twelve minutes — which is a real defect (#196) but a defect in
     * beat tracking, and scoring chords through it would measure the two
     * together and blame whichever was changed last. {@link
     * #theBarGridFromTheTrackedBeatsIsWorseThanTheChords} pins that gap
     * deliberately, so it is visible rather than hidden by this choice.
     *
     * <p>The score is sensitive to this figure, because an error compounds over
     * 26 cycles, and every neighbouring value scores lower — so a wrong constant
     * here can only understate the result. That is an argument for the wide
     * margin on the thresholds rather than against the measurement.
     */
    private static final double CYCLE_SECONDS = 27.15;

    private static Score score;
    private static Path sample;

    @BeforeAll
    static void transcribeOnce() {
        sample = locateSample();
        score = new AudioTranscriber().transcribe(sample, AudioTranscriber.Options.defaults());
    }

    /**
     * Finds {@code samples/gmajorblues.mp3} by walking up from the working
     * directory, which surefire sets to the module rather than the repository.
     */
    private static Path locateSample() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("samples").resolve("gmajorblues.mp3");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "samples/gmajorblues.mp3 was not found above " + System.getProperty("user.dir")
                        + "; it is committed, so this means the checkout is incomplete");
    }

    @Test
    @DisplayName("the recording is not one long no-chord span")
    void theRecordingIsNotOneLongNoChordSpan() {
        // The #185 failure in its plainest form, and the one this file exists
        // for. Before #3 both numbers below were on the far side of their
        // bounds: one span, 100% of the duration.
        ChordProgression chords = score.chords();
        double noChordSeconds = 0;
        for (Chord chord : chords.chords()) {
            if (chord.isNoChord()) {
                noChordSeconds += chord.durationSeconds();
            }
        }

        assertThat(100 * noChordSeconds / score.durationSeconds())
                .as("share of the recording labelled N.C.")
                .isLessThan(10.0);
        // Twenty-six cycles of three chords cannot be fewer than a few dozen
        // spans. Measured: 740. The upper bound is not idle either -- one span
        // per beat would be 1280, and a decoder that chatters that badly has
        // stopped smoothing.
        assertThat(chords.size()).isBetween(100, 1100);
    }

    @Test
    @DisplayName("the three chords of the blues are all found, and as sevenths")
    void theThreeChordsAreAllFound() {
        List<String> symbols = score.chords().chords().stream().map(Chord::symbol).toList();

        // Not merely present: present often. A single stray G7 among six hundred
        // spans would satisfy contains() and mean nothing.
        assertThat(symbols).filteredOn("G7"::equals).hasSizeGreaterThan(50);
        assertThat(symbols).filteredOn("C7"::equals).hasSizeGreaterThan(20);
        assertThat(symbols).filteredOn("D7"::equals).hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("most bars carry the chord the twelve-bar cycle says they should")
    void mostBarsCarryTheRightChord() {
        Labelling labelling = labelBars(CYCLE_SECONDS);

        // Measured 86.6% and 86.3%. The floors are set well under those: this is
        // a gate against the pipeline breaking, not a record of its best day.
        //
        // 58.3% is the number to beat rather than 0%, because seven of the
        // twelve bars are the tonic, so writing G7 in every bar scores that much
        // while being no transcription at all. Both floors clear it.
        assertThat(labelling.rootAccuracy())
                .as("bars whose root matches the cycle")
                .isGreaterThan(75.0);
        assertThat(labelling.rootAndQualityAccuracy())
                .as("bars whose root and quality both match")
                .isGreaterThan(72.0);
    }

    @Test
    @DisplayName("the IV and the V are found, not just the tonic")
    void theSubdominantAndDominantAreFound() {
        // The assertion that stops "G7 everywhere" from passing the one above.
        // Measured: G 88%, C 96%, D 68%. The D7 floor is the lowest because the
        // figure is -- the two D7 bars are one bar each in a turnaround, which is
        // the hardest position in the cycle to catch, and a third of them are
        // still missed.
        Labelling labelling = labelBars(CYCLE_SECONDS);

        assertThat(labelling.recall(7)).as("G7 bars found").isGreaterThan(78.0);
        assertThat(labelling.recall(0)).as("C7 bars found").isGreaterThan(85.0);
        assertThat(labelling.recall(2)).as("D7 bars found").isGreaterThan(55.0);
    }

    @Test
    @DisplayName("the tracked tempo is close to the loop's own")
    void theTrackedTempoIsCloseToTheLoops() {
        // 48 beats to the cycle at 27.15 s is 106.1 BPM. The tracker says 108.1,
        // which is 1.9% fast -- close enough to be the right tempo and wrong
        // enough that the bar lines walk off the music over twelve minutes.
        // Pinned as a band so that the drift cannot quietly grow (#196).
        List<Double> beats = score.beatGrid().map(BeatGrid::beatTimes).orElseThrow();
        double tracked = 60.0 * (beats.size() - 1)
                / (beats.get(beats.size() - 1) - beats.get(0));

        assertThat(tracked).isBetween(103.0, 111.0);
    }

    @Test
    @DisplayName("the bar grid from the tracked beats is worse than the chords are")
    void theBarGridFromTheTrackedBeatsIsWorseThanTheChords() {
        // Deliberately measuring what a reader of the engraved chart would get,
        // rather than what the chord stage alone is worth: bars of four tracked
        // beats, which is how the chart is actually laid out. Measured 47.5%
        // against the 86.6% the chords score when the bars are placed correctly.
        //
        // Recorded as a floor and as a gap, not as a target. The gap is the beat
        // tracker's 2% error accumulating, and the day it closes this assertion
        // should fail and be deleted -- which is the point of writing it down
        // rather than leaving the discrepancy in a commit message.
        List<Double> beats = score.beatGrid().map(BeatGrid::beatTimes).orElseThrow();
        int bars = (beats.size() - 1) / 4;
        int[] roots = new int[bars];
        for (int bar = 0; bar < bars; bar++) {
            roots[bar] = rootOverlapping(
                    beats.get(bar * 4), beats.get(Math.min(bar * 4 + 4, beats.size() - 1)));
        }

        assertThat(bestRotationAccuracy(roots, bars))
                .as("bars from the tracked beat grid whose root matches")
                .isGreaterThan(35.0);
        // The gap itself, so that closing it is what deletes this test.
        assertThat(labelBars(CYCLE_SECONDS).rootAccuracy() - bestRotationAccuracy(roots, bars))
                .as("how much the tracked bar grid costs")
                .isGreaterThan(20.0);
    }

    // ---- scoring ----

    /** Per-bar labels over the loop, and what they score against the cycle. */
    private record Labelling(int[] roots, boolean[] sevenths, int bars, int rotation) {

        double rootAccuracy() {
            int matches = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (roots[bar] == CYCLE[(bar + rotation) % 12]) {
                    matches++;
                }
            }
            return 100.0 * matches / bars;
        }

        double rootAndQualityAccuracy() {
            int matches = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (roots[bar] == CYCLE[(bar + rotation) % 12] && sevenths[bar]) {
                    matches++;
                }
            }
            return 100.0 * matches / bars;
        }

        /** Share of the bars that should hold {@code root} where it was found. */
        double recall(int root) {
            int wanted = 0;
            int found = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (CYCLE[(bar + rotation) % 12] != root) {
                    continue;
                }
                wanted++;
                if (roots[bar] == root) {
                    found++;
                }
            }
            return wanted == 0 ? 0 : 100.0 * found / wanted;
        }
    }

    /**
     * Labels each bar of the loop with the chord covering most of it.
     *
     * <p>The loop's phase is not known — the recording does not start on bar one
     * of a cycle and nothing says where it does — so every rotation is scored
     * and the best is taken. That is a free parameter with twelve values against
     * 314 bars, which is not enough freedom to manufacture a result: labelling
     * every bar G7 scores 58.3% at whichever rotation, and a random labelling
     * about 8%.
     */
    private Labelling labelBars(double cycleSeconds) {
        double barSeconds = cycleSeconds / 12;
        int bars = (int) Math.floor(score.durationSeconds() / barSeconds);
        int[] roots = new int[bars];
        boolean[] sevenths = new boolean[bars];
        for (int bar = 0; bar < bars; bar++) {
            double from = bar * barSeconds;
            Chord chord = chordOverlapping(from, from + barSeconds);
            roots[bar] = chord == null || chord.isNoChord() ? -1 : chord.pitchClasses()[0];
            sevenths[bar] = chord != null && !chord.isNoChord() && chord.quality().hasSeventh();
        }
        int rotation = bestRotation(roots, bars);
        return new Labelling(roots, sevenths, bars, rotation);
    }

    private Chord chordOverlapping(double from, double to) {
        Chord best = null;
        double bestOverlap = 0;
        for (Chord chord : score.chords().chords()) {
            double overlap = Math.min(to, chord.endSeconds()) - Math.max(from, chord.startSeconds());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = chord;
            }
        }
        return best;
    }

    private int rootOverlapping(double from, double to) {
        Chord chord = chordOverlapping(from, to);
        return chord == null || chord.isNoChord() ? -1 : chord.pitchClasses()[0];
    }

    private static int bestRotation(int[] roots, int bars) {
        int best = 0;
        int bestMatches = -1;
        for (int rotation = 0; rotation < 12; rotation++) {
            int matches = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (roots[bar] == CYCLE[(bar + rotation) % 12]) {
                    matches++;
                }
            }
            if (matches > bestMatches) {
                bestMatches = matches;
                best = rotation;
            }
        }
        return best;
    }

    private static double bestRotationAccuracy(int[] roots, int bars) {
        int rotation = bestRotation(roots, bars);
        int matches = 0;
        for (int bar = 0; bar < bars; bar++) {
            if (roots[bar] == CYCLE[(bar + rotation) % 12]) {
                matches++;
            }
        }
        return 100.0 * matches / bars;
    }
}
