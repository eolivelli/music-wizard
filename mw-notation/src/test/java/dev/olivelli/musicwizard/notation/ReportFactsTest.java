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

package dev.olivelli.musicwizard.notation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.arrange.SwingFeel;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the report counts, checked without a page in the way. */
class ReportFactsTest {

    @Test
    @DisplayName("bar lines come from the tempo map, one per bar, and stop at the end")
    void barLinesFollowTheTempoMap() {
        // 120 beats a minute in 4/4: a bar is two seconds.
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        ReportFacts.Bars bars = ReportFacts.barLines(map, 8);
        assertThat(bars.truncated()).isFalse();
        assertThat(bars.lines()).extracting(ReportFacts.BarLine::seconds)
                .containsExactly(0.0, 2.0, 4.0, 6.0, 8.0);
        assertThat(bars.lines()).extracting(ReportFacts.BarLine::bar)
                .containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    @DisplayName("a meter change moves the bar lines it applies from")
    void barLinesHonourAMeterChange() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR)
                .withMeterChange(2, TimeSignature.THREE_FOUR);
        // Two four-four bars of two seconds, then three-four bars of one and a half.
        assertThat(ReportFacts.barLines(map, 8).lines())
                .extracting(ReportFacts.BarLine::seconds)
                .containsExactly(0.0, 2.0, 4.0, 5.5, 7.0);
    }

    @Test
    @DisplayName("a tempo map spanning more bars than the page draws says so")
    void aVeryLongAxisIsTruncatedAndReported() {
        // A bar every ten milliseconds: absurd, and reachable, since the
        // quantizer accepts far more bars than a page can hold.
        TempoMap map = TempoMap.constant(24_000, TimeSignature.FOUR_FOUR);
        ReportFacts.Bars bars = ReportFacts.barLines(map, 3600);
        assertThat(bars.lines()).hasSize(ReportFacts.MAX_BAR_LINES);
        assertThat(bars.truncated()).isTrue();
    }

    @Test
    @DisplayName("qualities are counted in the vocabulary's own order")
    void chordQualitiesAreTallied() {
        var counts = ReportFacts.chordQualities(ReportFixtures.chordsOnly().chords());
        assertThat(counts).containsExactly(
                java.util.Map.entry(ChordQuality.MAJOR, 1),
                java.util.Map.entry(ChordQuality.MINOR, 1),
                java.util.Map.entry(ChordQuality.DOMINANT_SEVENTH, 1),
                java.util.Map.entry(ChordQuality.MAJOR_SEVENTH, 1),
                java.util.Map.entry(ChordQuality.NONE, 1));
    }

    @Test
    @DisplayName("the pulse spread is empty where there is no interval to measure")
    void aLonePulseHasNoSpread() {
        var grid = dev.olivelli.musicwizard.core.model.BeatGrid.ofTimes(
                List.of(1.0), 4, Confidence.of(0.5));
        assertThat(ReportFacts.beatIntervals(grid)).isEmpty();
        assertThat(ReportFacts.pulseSpread(grid)).isNull();
    }

    @Test
    @DisplayName("the pulse spread reads the shortest and longest gap")
    void theSpreadIsTheShortestAndLongestGap() {
        var grid = dev.olivelli.musicwizard.core.model.BeatGrid.ofTimes(
                List.of(0.0, 0.5, 1.5, 2.0), 4, Confidence.of(0.5));
        ReportFacts.PulseSpread spread = ReportFacts.pulseSpread(grid);
        assertThat(spread.shortest()).isEqualTo(0.5);
        assertThat(spread.longest()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a histogram is drawn over the values' own range")
    void theHistogramSpansTheValues() {
        int[] counts = ReportFacts.histogram(new double[] {10, 11, 12, 20}, 10, 20, 2);
        assertThat(counts).containsExactly(3, 1);
    }

    @Test
    @DisplayName("a histogram puts values outside its range in the nearest bucket")
    void valuesOutsideTheRangeLandAtTheEnds() {
        // Below the range into the first bucket, above it into the last, and
        // the value that is neither counted nowhere.
        assertThat(ReportFacts.histogram(new double[] {-5, 0.5, 50, Double.NaN}, 0, 1, 2))
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("a histogram needs a range")
    void anEmptyRangeIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReportFacts.histogram(new double[] {1}, 1, 1, 4));
    }

    @Test
    @DisplayName("a note reaching the playable part unchanged is counted as carried")
    void theReductionCountsWhatSurvived() {
        NoteTrack estimate = track(
                Note.ofSeconds(0, 0.5, 60, Confidence.CERTAIN),
                Note.ofSeconds(1, 0.5, 62, Confidence.CERTAIN),
                Note.ofSeconds(2, 0.5, 64, Confidence.CERTAIN));
        NoteTrack playable = track(
                Note.ofSeconds(0, 0.5, 60, Confidence.CERTAIN),
                // Same pitch, different length: not the estimate's note.
                Note.ofSeconds(1, 1.0, 62, Confidence.CERTAIN));
        ReportFacts.Reduction reduction = ReportFacts.reduction(estimate, playable);
        assertThat(reduction.estimateNotes()).isEqualTo(3);
        assertThat(reduction.playableNotes()).isEqualTo(2);
        assertThat(reduction.carried()).isEqualTo(1);
    }

    @Test
    @DisplayName("each bar is tallied under its own subdivision, coarsest first")
    void gridsAreTalliedPerBar() {
        // Built by hand rather than quantized, so this measures the tally and
        // not the quantizer's calibration.
        var quantized = new QuantizedScore(ReportFixtures.chordsOnly(), List.of(
                bar(0, GridResolution.HALF_BEAT), bar(1, GridResolution.THIRD_BEAT),
                bar(2, GridResolution.HALF_BEAT), bar(3, GridResolution.EIGHTH_BEAT)),
                SwingFeel.STRAIGHT);
        assertThat(ReportFacts.gridResolutions(quantized)).containsExactly(
                java.util.Map.entry(GridResolution.HALF_BEAT, 2),
                java.util.Map.entry(GridResolution.THIRD_BEAT, 1),
                java.util.Map.entry(GridResolution.EIGHTH_BEAT, 1));
    }

    @Test
    @DisplayName("a score the quantizer chose no subdivision for tallies nothing")
    void noGridsTallyNothing() {
        assertThat(ReportFacts.gridResolutions(
                Quantizer.quantize(ReportFixtures.chordsOnly()))).isEmpty();
    }

    @Test
    @DisplayName("a recording ending on a bar line is not credited with the bar it begins")
    void barsAreCountedRatherThanBarLines() {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        // Two seconds to a bar: an exact end, a part-filled last bar, one bar,
        // and a longer exact end. The bar lines drawn are one more each time
        // the last of them begins no bar.
        assertThat(ReportFacts.barCount(map, 8.0)).isEqualTo(4);
        assertThat(ReportFacts.barCount(map, 7.5)).isEqualTo(4);
        assertThat(ReportFacts.barCount(map, 2.0)).isEqualTo(1);
        assertThat(ReportFacts.barCount(map, 10.0)).isEqualTo(5);
        assertThat(ReportFacts.barLines(map, 8.0).lines()).hasSize(5);
    }

    @Test
    @DisplayName("the bar count is the tempo map's, never the number of lines drawn")
    void theBarCountIgnoresTheDrawingCap() {
        // Far more bars than the axis draws: the count must not report the cap.
        TempoMap map = TempoMap.constant(24_000, TimeSignature.FOUR_FOUR);
        assertThat(ReportFacts.barLines(map, 3600).lines())
                .hasSize(ReportFacts.MAX_BAR_LINES);
        assertThat(ReportFacts.barCount(map, 3600))
                .isGreaterThan(ReportFacts.MAX_BAR_LINES);
    }

    private static BarGrid bar(int index, GridResolution resolution) {
        return new BarGrid(index, index * 4.0, resolution, TimeSignature.FOUR_FOUR);
    }

    @Test
    @DisplayName("a score whose tempo was assumed rather than measured says so")
    void provenanceReachesThePage() {
        Score score = Score.empty(TempoMap.constant(
                120, TimeSignature.FOUR_FOUR, Provenance.ASSUMED), 4);
        assertThat(AnalysisReport.toHtml(score, AnalysisReport.Recording.unknown()))
                .contains("assumed, because nothing stated or measured one");
    }

    private static NoteTrack track(Note... notes) {
        return new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(notes), Confidence.CERTAIN);
    }
}
