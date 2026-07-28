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

package dev.olivelli.musicwizard.arrange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.MusicalTime;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class QuantizerTest {

    private static final double BPM = 120;

    @Nested
    @DisplayName("the model's two timelines stay separate")
    class ModelInvariants {

        @Test
        @DisplayName("the wall-clock timing is not touched")
        void secondsSurviveUnchanged() {
            Performance performance = new Performance(fourFour(), 1);
            performance.run(60, 0.5, Performance.evenly(4, 4.0, 8));
            Score before = performance.score();

            Score after = Quantizer.quantize(before).score();

            List<Note> was = before.tracks().get(0).notes();
            List<Note> now = after.tracks().get(0).notes();
            assertThat(now).hasSameSizeAs(was);
            for (int i = 0; i < was.size(); i++) {
                assertThat(now.get(i).onsetSeconds()).isEqualTo(was.get(i).onsetSeconds());
                assertThat(now.get(i).durationSeconds()).isEqualTo(was.get(i).durationSeconds());
                assertThat(now.get(i).midiPitch()).isEqualTo(was.get(i).midiPitch());
                assertThat(now.get(i).velocity()).isEqualTo(was.get(i).velocity());
                assertThat(now.get(i).confidence()).isEqualTo(was.get(i).confidence());
            }
        }

        @Test
        @DisplayName("an un-quantized note goes in and a quantized one comes out")
        void everyNoteGainsMusicalTiming() {
            Performance performance = new Performance(fourFour(), 2);
            performance.run(60, 0.5, Performance.evenly(4, 4.0, 8));
            assertThat(performance.score().tracks().get(0).isQuantized()).isFalse();

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.isFullyQuantized()).isTrue();
            assertThat(quantized.score().tracks().get(0).notes())
                    .allSatisfy(n -> {
                        assertThat(n.isQuantized()).isTrue();
                        assertThat(n.onsetBeat().orElseThrow()).isGreaterThanOrEqualTo(0);
                        assertThat(n.durationBeats().orElseThrow()).isGreaterThan(0);
                    });
        }

        @Test
        @DisplayName("chords, keys and sections keep their beats empty")
        void onlyNotesAreQuantized() {
            Performance performance = new Performance(fourFour(), 3);
            performance.section(0, 8).section(8, 16);
            performance.run(60, 0.5, Performance.evenly(4, 4.0, 8));

            Score quantized = Quantizer.quantize(performance.score()).score();

            assertThat(quantized.sections()).allSatisfy(s -> assertThat(s.isQuantized()).isFalse());
            assertThat(quantized.keys()).isEmpty();
        }

        @Test
        @DisplayName("everything else on the score comes through untouched")
        void theRestOfTheScoreIsPreserved() {
            Performance performance = new Performance(fourFour(), 4);
            performance.run(60, 1.0, 0, 1, 2, 3);
            Score before = performance.score().withMetadata("Title", "Artist");

            Score after = Quantizer.quantize(before).score();

            assertThat(after.title()).contains("Title");
            assertThat(after.artist()).contains("Artist");
            assertThat(after.tempoMap()).isEqualTo(before.tempoMap());
            assertThat(after.durationSeconds()).isEqualTo(before.durationSeconds());
            assertThat(after.tracks().get(0).role()).isEqualTo(PartRole.LEAD_VOCAL);
            assertThat(after.tracks().get(0).name()).isEqualTo("Voice");
        }
    }

    @Nested
    @DisplayName("positions land where a reader would expect them")
    class Snapping {

        @Test
        @DisplayName("a bar of eighths lands on the half beats")
        void eighthsLandOnHalfBeats() {
            Performance performance = new Performance(fourFour(), 5);
            performance.run(60, 0.5, Performance.evenly(4, 4.0, 8));

            List<Note> notes = Quantizer.quantize(performance.score()).score()
                    .tracks().get(0).notes();

            for (int i = 0; i < notes.size(); i++) {
                assertThat(notes.get(i).onsetBeat().orElseThrow())
                        .describedAs("note %d", i)
                        .isEqualTo(i * 0.5);
            }
        }

        @Test
        @DisplayName("a triplet passage lands on thirds of a beat, not on 1/8 plus 1/16 noise")
        void tripletsLandOnThirds() {
            Performance performance = new Performance(fourFour(), 6);
            performance.run(60, 1.0 / 3, Performance.evenly(4, 4.0, 12));

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.grids())
                    .allSatisfy(g -> assertThat(g.resolution()).isEqualTo(GridResolution.THIRD_BEAT));
            List<Note> notes = quantized.score().tracks().get(0).notes();
            for (int i = 0; i < notes.size(); i++) {
                assertThat(notes.get(i).onsetBeat().orElseThrow())
                        .describedAs("note %d", i)
                        .isCloseTo(i / 3.0, within(1e-9));
            }
        }

        @Test
        @DisplayName("a note too short to print gets one grid step rather than being dropped")
        void aGraceNoteIsLengthenedToOneStep() {
            TempoMap tempoMap = fourFour();
            Performance performance = new Performance(tempoMap, 7);
            performance.run(60, 0.5, Performance.evenly(2, 4.0, 8));
            // A 12 ms flick at the top of bar 3 -- far shorter than the eighth
            // grid the rest of the bar establishes.
            performance.exact(72, 8.0, 0.012 / tempoMap.segments().get(0).secondsPerBeat());
            performance.run(60, 0.5, Performance.evenly(1, 4.0, 8)[1] + 8.0);

            QuantizedScore quantized = Quantizer.quantize(performance.score());
            Note grace = quantized.score().tracks().get(0).notes().stream()
                    .filter(n -> n.midiPitch() == 72)
                    .findFirst().orElseThrow();

            assertThat(grace.durationBeats()).contains(0.5);
            assertThat(grace.durationSeconds()).isLessThan(0.02);
        }

        @Test
        @DisplayName("a detached quarter still prints as a quarter")
        void theArticulationAllowanceKeepsFullNoteValues() {
            TempoMap tempoMap = fourFour();
            // A bar whose sixteenth run forces a sixteenth grid, plus a quarter
            // note on beat 3 released after 85% of its length -- ordinary
            // playing, and the case that rounds down without the allowance.
            Performance performance = new Performance(tempoMap, 8);
            for (int bar = 0; bar < 4; bar++) {
                for (int i = 0; i < 8; i++) {
                    performance.exact(60, bar * 4.0 + i * 0.25, 0.25);
                }
                performance.exact(64, bar * 4.0 + 2.0, 0.85);
            }
            Score score = performance.score();

            Note withAllowance = quarterNote(Quantizer.quantize(score));
            Note without = quarterNote(Quantizer.quantize(score,
                    QuantizationSettings.DEFAULT.withArticulationRatio(1.0)));

            assertThat(withAllowance.durationBeats()).contains(1.0);
            assertThat(without.durationBeats()).contains(0.75);
        }

        private Note quarterNote(QuantizedScore quantized) {
            assertThat(quantized.gridAtBar(0).orElseThrow().resolution())
                    .isEqualTo(GridResolution.QUARTER_BEAT);
            return quantized.score().tracks().get(0).notes().stream()
                    .filter(n -> n.midiPitch() == 64)
                    .findFirst().orElseThrow();
        }

        @Test
        @DisplayName("a note is released on its own bar's grid, not on the one it started in")
        void theOffsetIsSnappedWhereItLands() {
            // Bar 0 is triplets, bar 1 is sixteenths, and a note tied across the
            // bar line has to end where bar 1's reader expects a note to end.
            // Snapping it on bar 0's grid instead prints a triplet-length tail
            // in a bar that holds no triplets.
            TempoMap tempoMap = fourFour();
            Performance performance = new Performance(tempoMap, 35);
            performance.section(0, 4).section(4, 8);
            for (int i = 0; i < 12; i++) {
                performance.note(60, i / 3.0, 1 / 3.0);
            }
            for (int i = 0; i < 16; i++) {
                performance.note(60, 4.0 + i * 0.25, 0.25);
            }
            // Held from the last triplet of bar 0 to three sixteenths into bar 1.
            performance.exact(72, 11 / 3.0, 4.75 - 11 / 3.0);

            QuantizedScore quantized = Quantizer.quantize(performance.score());
            assertThat(quantized.gridAtBar(0).orElseThrow().resolution())
                    .isEqualTo(GridResolution.THIRD_BEAT);
            assertThat(quantized.gridAtBar(1).orElseThrow().resolution())
                    .isEqualTo(GridResolution.QUARTER_BEAT);

            Note tied = quantized.score().tracks().get(0).notes().stream()
                    .filter(n -> n.midiPitch() == 72)
                    .findFirst().orElseThrow();
            double end = tied.onsetBeat().orElseThrow() + tied.durationBeats().orElseThrow();
            assertThat(end).isCloseTo(4.75, within(1e-9));
        }

        @Test
        @DisplayName("a duration on a tuplet grid is one number, not five that nearly agree")
        void durationsAreCanonical() {
            // Positions on a triplet grid are not representable, so subtracting
            // two of them lands a few ulps out and drifts with the bar. The
            // notation layer has to match a duration against a note value, and
            // "one third, nearly" is not a note value.
            Performance performance = new Performance(fourFour(), 36);
            performance.run(60, 1 / 3.0, Performance.evenly(4, 4.0, 12));

            QuantizedScore quantized = Quantizer.quantize(performance.score());
            double step = quantized.gridAtBar(0).orElseThrow().stepQuarters();

            List<Double> durations = quantized.score().tracks().get(0).notes().stream()
                    .map(n -> n.durationBeats().orElseThrow())
                    .distinct()
                    .toList();

            // Every duration is bit-identical to a whole number of steps, so a
            // one-third note is one double and not four that nearly agree.
            assertThat(durations).allSatisfy(d ->
                    assertThat(d).isEqualTo(Math.rint(d / step) * step));
            assertThat(durations).allSatisfy(d -> assertThat(d).isGreaterThanOrEqualTo(step));
            assertThat(durations).contains(step);
        }

        @Test
        @DisplayName("a note played a hair early against a bar line lands on the bar line")
        void anEarlyDownbeatIsNotPulledBackIntoThePreviousBar() {
            TempoMap tempoMap = fourFour();
            Performance performance = new Performance(tempoMap, 9);
            performance.run(60, 1.0, 0, 1, 2, 3);
            // 15 ms before the downbeat of bar 2.
            performance.exact(60, 0, 1.0);
            List<Note> notes = new java.util.ArrayList<>(performance.notes());
            notes.add(Note.ofSeconds(tempoMap.beatsToSeconds(4.0) - 0.015, 0.5, 67,
                    Confidence.CERTAIN));

            Score score = scoreOf(tempoMap, notes);
            Note early = Quantizer.quantize(score).score().tracks().get(0).notes().stream()
                    .filter(n -> n.midiPitch() == 67)
                    .findFirst().orElseThrow();

            assertThat(early.onsetBeat()).contains(4.0);
        }
    }

    @Nested
    @DisplayName("compound meters are barred and divided as compound meters")
    class CompoundMeter {

        @Test
        @DisplayName("six eighths in 6/8 are plain eighths, not a tuplet")
        void sixEightSubdividesInThirds() {
            TempoMap tempoMap = TempoMap.constantPulse(80, TimeSignature.SIX_EIGHT);
            Performance performance = new Performance(tempoMap, 20);
            performance.run(60, 0.5, Performance.evenly(4, 3.0, 6));

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.grids()).hasSize(4);
            assertThat(quantized.grids()).allSatisfy(g -> {
                assertThat(g.resolution()).isEqualTo(GridResolution.THIRD_BEAT);
                assertThat(g.tuplet()).isEmpty();
                assertThat(g.stepQuarters()).isEqualTo(0.5);
                assertThat(g.timeSignature()).isEqualTo(TimeSignature.SIX_EIGHT);
            });
        }

        @Test
        @DisplayName("the same note times in 3/4 are half-beats, and that is the whole difference")
        void theSamePositionsInThreeFourAreNotCompound() {
            TempoMap sixEight = TempoMap.constantPulse(80, TimeSignature.SIX_EIGHT);
            TempoMap threeFour = TempoMap.constant(120, TimeSignature.THREE_FOUR);
            // Identical bar length -- three quarter beats -- and identical
            // onsets. Only the meter differs.
            assertThat(sixEight.initialTimeSignature().quarterBeatsPerBar())
                    .isEqualTo(threeFour.initialTimeSignature().quarterBeatsPerBar());

            QuantizedScore compound = quantizeSixEighths(sixEight);
            QuantizedScore simple = quantizeSixEighths(threeFour);

            assertThat(compound.grids().get(0).resolution()).isEqualTo(GridResolution.THIRD_BEAT);
            assertThat(simple.grids().get(0).resolution()).isEqualTo(GridResolution.HALF_BEAT);
            // Both put the notes in the same places; what differs is what a
            // notation back end will be told those places mean.
            assertThat(onsetBeats(compound)).isEqualTo(onsetBeats(simple));
            assertThat(compound.grids().get(0).stepQuarters())
                    .isEqualTo(simple.grids().get(0).stepQuarters());
        }

        private QuantizedScore quantizeSixEighths(TempoMap tempoMap) {
            Performance performance = new Performance(tempoMap, 21);
            performance.run(60, 0.5, Performance.evenly(4, 3.0, 6));
            return Quantizer.quantize(performance.score());
        }

        @Test
        @DisplayName("two notes to a 6/8 beat are a duplet, printed two in the time of three")
        void sixEightDupletsAreTuplets() {
            TempoMap tempoMap = TempoMap.constantPulse(80, TimeSignature.SIX_EIGHT);
            Performance performance = new Performance(tempoMap, 22);
            performance.run(60, 0.75, Performance.evenly(4, 3.0, 4));

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.grids()).allSatisfy(g -> {
                assertThat(g.resolution()).isEqualTo(GridResolution.HALF_BEAT);
                assertThat(g.tuplet()).contains(new GridResolution.Tuplet(2, 3));
            });
            assertThat(onsetBeats(quantized)).startsWith(0.0, 0.75, 1.5, 2.25, 3.0);
        }

        @Test
        @DisplayName("a meter change mid-piece is honoured bar by bar")
        void meterChangesAreFollowed() {
            TempoMap tempoMap = TempoMap.constant(BPM, TimeSignature.FOUR_FOUR)
                    .withMeterChange(2, TimeSignature.THREE_FOUR);
            Performance performance = new Performance(tempoMap, 23);
            // Two 4/4 bars then two 3/4 bars: 4 + 4 + 3 + 3 quarter beats.
            performance.run(60, 1.0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.gridAtBar(1).orElseThrow().timeSignature())
                    .isEqualTo(TimeSignature.FOUR_FOUR);
            assertThat(quantized.gridAtBar(2).orElseThrow().timeSignature())
                    .isEqualTo(TimeSignature.THREE_FOUR);
            assertThat(quantized.gridAtBar(2).orElseThrow().startBeat()).isEqualTo(8.0);
            assertThat(quantized.gridAtBar(3).orElseThrow().startBeat()).isEqualTo(11.0);
        }
    }

    @Nested
    @DisplayName("the published grids are what a notation back end asks for")
    class PublishedGrids {

        @Test
        void areOrderedAndCoverOnlyBarsThatSound() {
            Performance performance = new Performance(fourFour(), 30);
            performance.run(60, 1.0, 0, 1, 2, 3);
            // Bar 1 is silent.
            performance.run(60, 1.0, 8, 9, 10, 11);

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.grids()).extracting(BarGrid::bar).containsExactly(0, 2);
            assertThat(quantized.gridAtBar(1)).isEmpty();
            assertThat(quantized.gridAtBar(99)).isEmpty();
        }

        @Test
        @DisplayName("a sparse bar keeps the section's grid rather than falling back to the beat")
        void aSparseBarInheritsRatherThanResets() {
            // Bar 2 holds one note. On its own that is a whole bar's rest and a
            // note, which every grid fits equally, so the cheapest reading is
            // the beat -- and printing one bar of a passage of eighths in a
            // different subdivision is exactly what the prior exists to stop.
            Performance performance = new Performance(fourFour(), 31);
            for (int bar = 0; bar < 5; bar++) {
                if (bar == 2) {
                    performance.note(60, bar * 4.0 + 1.5, 0.5);
                    continue;
                }
                for (int i = 0; i < 8; i++) {
                    performance.note(60, bar * 4.0 + i * 0.5, 0.5);
                }
            }

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.gridAtBar(2).orElseThrow().resolution())
                    .isEqualTo(GridResolution.HALF_BEAT);
            assertThat(quantized.score().tracks().get(0).notes())
                    .anySatisfy(n -> assertThat(n.onsetBeat()).contains(9.5));
        }

        @Test
        @DisplayName("a section boundary is where the grid may change, and the only place it may")
        void theGridChangesFreelyAtASectionBoundary() {
            // The change penalty is set far above anything the evidence can pay,
            // so the only route from one subdivision to another is the waiver at
            // a section start. Four bars of eighths then four of triplets, with
            // and without the boundary declared.
            QuantizationSettings immovable =
                    QuantizationSettings.DEFAULT.withGridChangePenalty(1000);

            QuantizedScore withBoundary = Quantizer.quantize(
                    twoHalves(true).score(), immovable);
            QuantizedScore withoutBoundary = Quantizer.quantize(
                    twoHalves(false).score(), immovable);

            assertThat(withBoundary.gridAtBar(0).orElseThrow().resolution())
                    .isEqualTo(GridResolution.HALF_BEAT);
            assertThat(withBoundary.gridAtBar(4).orElseThrow().resolution())
                    .isEqualTo(GridResolution.THIRD_BEAT);
            assertThat(withoutBoundary.grids().stream().map(BarGrid::resolution).distinct())
                    .hasSize(1);
        }

        private Performance twoHalves(boolean declareSections) {
            Performance performance = new Performance(fourFour(), 33);
            if (declareSections) {
                performance.section(0, 16).section(16, 32);
            }
            for (int bar = 0; bar < 4; bar++) {
                for (int i = 0; i < 8; i++) {
                    performance.note(60, bar * 4.0 + i * 0.5, 0.5);
                }
            }
            for (int bar = 4; bar < 8; bar++) {
                for (int i = 0; i < 12; i++) {
                    performance.note(60, bar * 4.0 + i / 3.0, 1 / 3.0);
                }
            }
            return performance;
        }

        @Test
        @DisplayName("a section declared past the end of the music is ignored, not an index error")
        void aSectionBeyondTheMusicIsHarmless() {
            Performance performance = new Performance(fourFour(), 34);
            performance.run(60, 1.0, 0, 1, 2, 3);
            performance.section(0, 4).section(400, 440);

            assertThat(Quantizer.quantize(performance.score()).grids()).hasSize(1);
        }

        @Test
        void gridAtBeatFindsTheBarThatCoversIt() {
            Performance performance = new Performance(fourFour(), 32);
            performance.run(60, 1.0, 0, 1, 2, 3, 4, 5, 6, 7);

            QuantizedScore quantized = Quantizer.quantize(performance.score());

            assertThat(quantized.gridAtBeat(0.0).orElseThrow().bar()).isZero();
            assertThat(quantized.gridAtBeat(3.99).orElseThrow().bar()).isZero();
            assertThat(quantized.gridAtBeat(4.0).orElseThrow().bar()).isEqualTo(1);
            assertThat(quantized.gridAtBeat(7.5).orElseThrow().bar()).isEqualTo(1);
        }

        @Test
        void barGridDescribesItsOwnSpan() {
            BarGrid grid = new BarGrid(3, 12.0, GridResolution.HALF_BEAT, TimeSignature.FOUR_FOUR);
            assertThat(grid.endBeat()).isEqualTo(16.0);
            assertThat(grid.divisions()).isEqualTo(8);
            assertThat(grid.stepQuarters()).isEqualTo(0.5);
            assertThat(grid.snapWithinBar(1.2)).isEqualTo(1.0);
            assertThat(grid.snapWithinBar(1.3)).isEqualTo(1.5);
            assertThat(grid.snapWithinBar(-1)).isZero();
            assertThat(grid.snapWithinBar(99)).isEqualTo(4.0);
            assertThat(grid.toString()).contains("bar 4");
        }

        @Test
        void rejectOutOfOrderGrids() {
            BarGrid first = new BarGrid(1, 4.0, GridResolution.BEAT, TimeSignature.FOUR_FOUR);
            BarGrid second = new BarGrid(0, 0.0, GridResolution.BEAT, TimeSignature.FOUR_FOUR);
            assertThatThrownBy(() -> new QuantizedScore(
                    Score.empty(fourFour(), 10), List.of(first, second), SwingFeel.STRAIGHT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ordered by bar");
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        void anEmptyScoreQuantizesToNothing() {
            QuantizedScore quantized = Quantizer.quantize(Score.empty(fourFour(), 10));
            assertThat(quantized.grids()).isEmpty();
            assertThat(quantized.swing()).isEqualTo(SwingFeel.STRAIGHT);
            assertThat(quantized.isFullyQuantized()).isTrue();
        }

        @Test
        void anEmptyTrackIsLeftAlone() {
            Score score = Score.empty(fourFour(), 10)
                    .withTrack(NoteTrack.empty(PartRole.BASS, "Bass"));
            QuantizedScore quantized = Quantizer.quantize(score);
            assertThat(quantized.grids()).isEmpty();
            assertThat(quantized.score().tracks()).hasSize(1);
        }

        @Test
        void aSingleNoteBecomesAWholeBar() {
            Score score = scoreOf(fourFour(),
                    List.of(Note.ofSeconds(0.02, 1.9, 60, Confidence.CERTAIN)));
            QuantizedScore quantized = Quantizer.quantize(score);
            assertThat(quantized.grids()).hasSize(1);
            assertThat(quantized.grids().get(0).resolution()).isEqualTo(GridResolution.BEAT);
            assertThat(quantized.score().tracks().get(0).notes().get(0).onsetBeat()).contains(0.0);
        }

        @Test
        @DisplayName("a tempo map that puts the music a million bars out is rejected, not allocated")
        void refusesAnAbsurdBarCount() {
            TempoMap absurd = TempoMap.constant(1e9, TimeSignature.FOUR_FOUR);
            Score score = scoreOf(absurd, List.of(Note.ofSeconds(0, 600, 60, Confidence.CERTAIN)));
            assertThatThrownBy(() -> Quantizer.quantize(score))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("past the bar limit");
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> Quantizer.quantize(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Quantizer.quantize(Score.empty(fourFour(), 1), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void settingsValidateTheirOwnRanges() {
            assertThatThrownBy(() -> QuantizationSettings.DEFAULT.withArticulationRatio(0.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuantizationSettings.DEFAULT.withArticulationRatio(1.5))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuantizationSettings.DEFAULT.withGridChangePenalty(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuantizationSettings.DEFAULT.withGridChangePenalty(Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a quantized position converts back to the bar and beat a musician would name")
    void positionsRoundTripThroughTheTempoMap() {
        TempoMap tempoMap = TempoMap.constantPulse(80, TimeSignature.SIX_EIGHT);
        Performance performance = new Performance(tempoMap, 40);
        performance.run(60, 0.5, Performance.evenly(3, 3.0, 6));

        QuantizedScore quantized = Quantizer.quantize(performance.score());
        List<Note> notes = quantized.score().tracks().get(0).notes();

        MusicalTime second = tempoMap.toMusicalTime(notes.get(1).onsetBeat().orElseThrow());
        assertThat(second.bar()).isZero();
        assertThat(second.beatNumber()).isCloseTo(1 + 1.0 / 3, within(1e-9));

        MusicalTime seventh = tempoMap.toMusicalTime(notes.get(6).onsetBeat().orElseThrow());
        assertThat(seventh.barNumber()).isEqualTo(2);
        assertThat(seventh.isDownbeat()).isTrue();
    }

    private static TempoMap fourFour() {
        return TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
    }

    private static Score scoreOf(TempoMap tempoMap, List<Note> notes) {
        double duration = notes.stream().mapToDouble(Note::offsetSeconds).max().orElse(1) + 1;
        return new Score(Optional.empty(), Optional.empty(), tempoMap, Optional.empty(),
                List.of(), List.of(),
                List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN)),
                ChordProgression.empty(), Lyrics.empty(), duration);
    }

    private static List<Double> onsetBeats(QuantizedScore quantized) {
        return quantized.score().tracks().get(0).notes().stream()
                .map(n -> n.onsetBeat().orElseThrow())
                .toList();
    }
}
