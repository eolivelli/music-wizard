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

package dev.olivelli.musicwizard.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Provenance is carried by whoever knew it and read afterwards (#120), rather
 * than guessed back from the shape of the artifact (#73).
 *
 * <p><b>No fixture here starts at t = 0.</b> Every derivation of tempo, phase
 * and beat position agrees exactly at the origin, so a grid whose first pulse is
 * at zero cannot distinguish a map that carries its provenance from one that
 * infers it. The one test that does start at the origin says why in its body.
 */
@DisplayName("a value says where it came from")
class ProvenanceTest {

    /** A grid of {@code count} pulses, {@code interval} apart, starting at {@code first}. */
    private static List<Double> pulses(double first, double interval, int count) {
        List<Double> times = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            times.add(first + i * interval);
        }
        return times;
    }

    private static BeatGrid gridOf(List<Double> times) {
        return BeatGrid.ofTimes(times, TimeSignature.FOUR_FOUR, Confidence.of(0.9));
    }

    private static List<Provenance> provenances(TempoMap map) {
        return map.segments().stream().map(TempoMap.TempoSegment::provenance).toList();
    }

    @Nested
    @DisplayName("the enum itself")
    class TheEnum {

        @Test
        @DisplayName("only a declaration and a correction count as stated")
        void statedIsNarrowerThanKnown() {
            assertThat(Provenance.DECLARED.isStated()).isTrue();
            assertThat(Provenance.SUPPLIED.isStated()).isTrue();
            // The three that are not somebody's statement. ASSUMED is the one
            // worth naming: a default is the pipeline filling a gap, and if it
            // counted as stated it would outrank measured evidence.
            assertThat(Provenance.ASSUMED.isStated()).isFalse();
            assertThat(Provenance.MEASURED.isStated()).isFalse();
            assertThat(Provenance.DERIVED.isStated()).isFalse();
            assertThat(Provenance.UNKNOWN.isStated()).isFalse();
        }
    }

    @Nested
    @DisplayName("a tempo map records where each segment's tempo came from")
    class MapsAreLabelled {

        @Test
        @DisplayName("a tracked interval is measured; the lead-in that anchors it is not")
        void trackedIntervalsAreMeasuredAndTheLeadInIsDerived() {
            // 0.2s of audio before the first tracked pulse, so there is a lead-in
            // to label. At the origin there would be none and this would prove
            // nothing.
            TempoMap map = TempoMap.fromBeatTimes(
                    pulses(0.2, 0.5, 4), TimeSignature.FOUR_FOUR);

            assertThat(map.segments()).hasSize(4);
            assertThat(provenances(map)).containsExactly(
                    Provenance.DERIVED,
                    Provenance.MEASURED, Provenance.MEASURED, Provenance.MEASURED);
            // The lead-in's rate is nothing the recording ran at, which is why it
            // must not be reported as measured.
            assertThat(map.segments().get(0).beatsPerMinute()).isCloseTo(300.0, within(1e-9));
            assertThat(map.segments().get(1).beatsPerMinute()).isCloseTo(120.0, within(1e-9));
        }

        @Test
        @DisplayName("a grid that starts at the origin has no lead-in to label")
        void anOriginAnchoredGridIsAllMeasured() {
            // The one fixture that starts at zero, deliberately: it is the case
            // where no lead-in segment exists at all, and the assertion is about
            // its absence.
            TempoMap map = TempoMap.fromBeatTimes(
                    pulses(0.0, 0.5, 4), TimeSignature.FOUR_FOUR);

            assertThat(provenances(map)).containsOnly(Provenance.MEASURED);
            assertThat(map.segments()).hasSize(3);
        }

        @Test
        @DisplayName("a constant map says nothing about its tempo unless asked to")
        void aConstantMapIsUnknownByDefault() {
            // The unlabelled forms are for a caller that genuinely does not
            // know -- a test, a hand-built fixture -- and no main-source
            // producer uses them. #143 records that nothing but review enforces
            // that, and why removing them was not worth the churn here.
            assertThat(provenances(TempoMap.constant(120, TimeSignature.FOUR_FOUR)))
                    .containsExactly(Provenance.UNKNOWN);
            assertThat(provenances(TempoMap.constantPulse(120, TimeSignature.SIX_EIGHT)))
                    .containsExactly(Provenance.UNKNOWN);
            assertThat(provenances(TempoMap.constantPulse(
                    120, TimeSignature.SIX_EIGHT, Provenance.SUPPLIED)))
                    .containsExactly(Provenance.SUPPLIED);
        }

        @Test
        @DisplayName("two maps that differ only in provenance are different maps")
        void provenanceParticipatesInEquality() {
            // Worth pinning: a record's equals covers every component, so a
            // golden-file or oracle comparison that ignores provenance has to
            // strip it rather than assume it does not matter.
            assertThat(TempoMap.constant(120, TimeSignature.FOUR_FOUR, Provenance.SUPPLIED))
                    .isNotEqualTo(
                            TempoMap.constant(120, TimeSignature.FOUR_FOUR, Provenance.ASSUMED));
        }
    }

    @Nested
    @DisplayName("estimatedTempo reads provenance instead of guessing it from the shape")
    class EstimatedTempoReadsIt {

        /**
         * The map {@code --tempo 60} produces on a recording whose first tracked
         * pulse falls 0.2s in: a lead-in stretched to reach that pulse, then the
         * figure the user typed.
         */
        private TempoMap suppliedSixty() {
            return new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 60.0 / 0.2, Provenance.DERIVED),
                            new TempoMap.TempoSegment(1.0, 0.2, 60.0, Provenance.SUPPLIED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        }

        @Test
        @DisplayName("hears a supplied tempo with no beat grid to identify the lead-in against")
        void aSuppliedTempoSurvivesWithoutABeatGrid() {
            // The shape proxy could only exempt a lead-in by finding the segment
            // that starts at the grid's first beat. With no grid it gave up and
            // averaged the map instead -- so the anchored 60 came back as 64,
            // and the lead-in's 300 was two thirds of the reason. A score with a
            // supplied tempo and no grid is exactly what a MIDI import plus
            // --tempo will produce once #98 lands.
            Score corrected = Score.empty(suppliedSixty(), 12.0);

            assertThat(corrected.beatGrid()).isEmpty();
            assertThat(corrected.tempoMap().averageTempo(12.0))
                    .as("the fallback the proxy used to take, which must not be the answer")
                    .isCloseTo(64.0, within(1e-9));
            assertThat(corrected.estimatedTempo()).isEqualTo(60.0);
        }

        @Test
        @DisplayName("still hears a supplied tempo through a lead-in when there is a grid")
        void aSuppliedTempoSurvivesItsLeadInWithAGrid() {
            // Passes against the unfixed code too, and is labelled as a guard
            // rather than a reproduction: this is the one case the shape proxy
            // got right, so the new rule has to keep getting it right. What it
            // catches is a fix that answers the cases below by breaking this one.
            BeatGrid grid = gridOf(pulses(0.2, 0.5, 24));
            Score corrected = Score.empty(suppliedSixty(), 12.0).withBeatGrid(grid);

            assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR))
                    .as("the grid disagrees, which is what makes the choice matter")
                    .isCloseTo(120.0, within(1e-6));
            assertThat(corrected.estimatedTempo()).isEqualTo(60.0);
            // And never the lead-in's own rate, which is an artefact of where the
            // first pulse fell.
            assertThat(corrected.estimatedTempo())
                    .isNotEqualTo(corrected.tempoMap().initialTempo());
        }

        @Test
        @DisplayName("does not mistake a measured map that states one tempo for a correction")
        void aMeasuredMapIsNotACorrection() {
            // The discriminator. Both maps below have the identical shape -- a
            // constant tempo from the grid's first beat onwards -- and differ
            // only in what they say about where that tempo came from. The proxy
            // could not tell them apart and answered 120 for both.
            List<TempoMap.TempoSegment> shape = List.of(
                    new TempoMap.TempoSegment(0, 0.0, 120.0, Provenance.MEASURED),
                    new TempoMap.TempoSegment(2.0, 1.0, 120.0, Provenance.MEASURED),
                    new TempoMap.TempoSegment(4.0, 2.0, 120.0, Provenance.MEASURED));
            TempoMap measured = new TempoMap(
                    shape, List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            TempoMap supplied = new TempoMap(
                    shape.stream()
                            .map(s -> new TempoMap.TempoSegment(s.startBeat(), s.startSeconds(),
                                    s.beatsPerMinute(), Provenance.SUPPLIED))
                            .toList(),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));

            // A grid whose own rate is a different figure, so the two answers are
            // distinguishable. 0.75s is exact in binary, so 80 is exact too. The
            // grid is even, so every statistic on it agrees -- which is what this
            // test wants, since what it is separating is the map from the grid
            // rather than one grid statistic from another.
            BeatGrid grid = gridOf(pulses(1.0, 0.75, 8));
            assertThat(grid.steadyTempo(TimeSignature.FOUR_FOUR)).isEqualTo(80.0);
            assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR)).isEqualTo(80.0);

            assertThat(Score.empty(measured, 10.0).withBeatGrid(grid).estimatedTempo())
                    .as("measured: the grid is the better evidence")
                    .isEqualTo(80.0);
            assertThat(Score.empty(supplied, 10.0).withBeatGrid(grid).estimatedTempo())
                    .as("supplied: the correction wins over what it corrects")
                    .isEqualTo(120.0);
        }

        @Test
        @DisplayName("is no longer decided by how many beats happened to be tracked")
        void theHalfTempoOverloadIsNoLongerDiscontinuous() {
            // #73's case, reproduced with the jitter a real grid has.
            // fromBeatTimes emits one segment per beat interval plus a lead-in,
            // so a two-beat grid yielded a map the shape proxy read as a
            // correction, while a three-beat grid -- whose two intervals differ
            // in the last bits, as tracked intervals always do -- it did not.
            // The answer therefore jumped by a factor of two when a third beat
            // arrived: 121.2 for two beats, 59.7 for three.
            List<Double> twoBeats = List.of(0.05, 1.04);
            List<Double> threeBeats = List.of(0.05, 1.04, 2.06);

            for (List<Double> times : List.of(twoBeats, threeBeats)) {
                BeatGrid grid = gridOf(times);
                Score score = Score.empty(
                                TempoMap.fromBeatTimes(times, TimeSignature.FOUR_FOUR, 2.0), 10.0)
                        .withBeatGrid(grid);

                // Both grids are answered from the same place. The two figures
                // are not identical -- they are rates over different sets of
                // intervals -- so the claim is the rule, not the number.
                assertThat(score.estimatedTempo())
                        .as("%d tracked beats", times.size())
                        .isEqualTo(grid.steadyTempo(TimeSignature.FOUR_FOUR));
            }

            // And the assertion discriminates: for two beats the map states a
            // tempo well away from the grid's, which is the answer that used to
            // come back.
            TempoMap twoBeatMap = TempoMap.fromBeatTimes(
                    twoBeats, TimeSignature.FOUR_FOUR, 2.0);
            assertThat(twoBeatMap.segments()).hasSize(2);
            assertThat(twoBeatMap.segments().get(1).beatsPerMinute())
                    .isCloseTo(121.2, within(0.1));
            // One interval, so the grid's rate is that interval whichever
            // statistic asks for it.
            assertThat(gridOf(twoBeats).steadyTempo(TimeSignature.FOUR_FOUR))
                    .isCloseTo(60.6, within(0.1));
        }

        @Test
        @DisplayName("supplied tempi that disagree state no single tempo")
        void disagreeingSuppliedTempiFallThrough() {
            // Nothing produces this today. It is here because the alternative --
            // returning the first supplied segment -- would name a figure the
            // user never stated, and a rule with no test is a rule that changes
            // by accident.
            //
            // The map carries a lead-in, so the two averages differ and the
            // assertion pins the method actually called. Without one, asserting
            // against averageTempo would pass whichever of the two the code
            // used, which is the shape of test that proves nothing.
            TempoMap twoCorrections = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 300.0, Provenance.DERIVED),
                            new TempoMap.TempoSegment(1.0, 0.2, 120.0, Provenance.SUPPLIED),
                            new TempoMap.TempoSegment(5.0, 2.2, 60.0, Provenance.SUPPLIED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            Score score = Score.empty(twoCorrections, 10.0)
                    .withBeatGrid(gridOf(pulses(1.0, 0.75, 8)));

            assertThat(twoCorrections.averageTempoIgnoringLeadIn(10.0))
                    .as("the lead-in must actually move the figure, or this pins nothing")
                    .isNotEqualTo(twoCorrections.averageTempo(10.0));
            assertThat(score.estimatedTempo())
                    .isEqualTo(twoCorrections.averageTempoIgnoringLeadIn(10.0));
            // Neither the grid's 80 nor either supplied figure on its own.
            assertThat(score.estimatedTempo()).isNotIn(80.0, 120.0, 60.0);
        }

        @Test
        @DisplayName("a mistyped label costs whatever the score's other evidence says")
        void aMistypedLabelCostsWhateverTheScoreElseSays() {
            // Backs the table on Provenance.fromJson. A forgiving reader turns a
            // misspelt label into UNKNOWN, and what that costs is neither "the
            // value is not recorded" nor monotonic in the number of typos: it
            // depends on what other evidence the score carries, and the polarity
            // inverts between a score with a beat grid and one without.
            //
            // Both columns matter. The second is not hypothetical -- it is the
            // shape a MIDI import plus --tempo produces once #98 lands, and the
            // one aSuppliedTempoSurvivesWithoutABeatGrid exists for.
            BeatGrid grid = gridOf(pulses(0.2, 0.5, 24));
            assertThat(grid.medianTempo(TimeSignature.FOUR_FOUR))
                    .as("the grid must disagree with the correction, or a column shows nothing")
                    .isCloseTo(120.0, within(1e-6));

            // The supplied label lost, the derived anchor surviving.
            TempoMap oneLost = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 300.0, Provenance.DERIVED),
                            new TempoMap.TempoSegment(1.0, 0.2, 60.0, Provenance.UNKNOWN)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            // Every label lost, which is what a pre-#120 file looks like too.
            TempoMap allLost = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 300.0),
                            new TempoMap.TempoSegment(1.0, 0.2, 60.0)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));

            // With a grid: one typo discards the correction, because the map
            // records some provenance so the proxy does not run and there is no
            // supplied tempo left to find. All of them typed wrong happens to
            // come out right, since the proxy runs and exempts the lead-in
            // positionally -- a coincidence that holds only while the grid's
            // beats still line up with the map's, asserted below.
            assertThat(Score.empty(suppliedSixty(), 12.0).withBeatGrid(grid).estimatedTempo())
                    .isEqualTo(60.0);
            assertThat(Score.empty(oneLost, 12.0).withBeatGrid(grid).estimatedTempo())
                    .isEqualTo(120.0);
            assertThat(Score.empty(allLost, 12.0).withBeatGrid(grid).estimatedTempo())
                    .isEqualTo(60.0);

            // Without a grid the polarity inverts. One typo is harmless -- the
            // surviving DERIVED label still says which segment is the anchor, so
            // the fallback skips it and answers 60. All of them lost is the
            // damaging case: nothing identifies the anchor, its 300 is averaged
            // in, and 64 comes back -- a figure nobody supplied and the music
            // never had.
            assertThat(Score.empty(suppliedSixty(), 12.0).estimatedTempo()).isEqualTo(60.0);
            assertThat(Score.empty(oneLost, 12.0).estimatedTempo()).isEqualTo(60.0);
            assertThat(Score.empty(allLost, 12.0).estimatedTempo())
                    .isCloseTo(64.0, within(1e-9));
            assertThat(Score.empty(allLost, 12.0).estimatedTempo())
                    .as("64 is not a tempo anything in this score states")
                    .isNotIn(60.0, 120.0, 300.0);
            // And unlike the 60 and the 120, it is not a property of the map:
            // it is a duration-weighted mean, so it moves with the recording's
            // length. Worth pinning, because a reader who took 64 for a
            // constant would not know which cells of the table generalise.
            assertThat(Score.empty(allLost, 0.3).estimatedTempo())
                    .isNotCloseTo(64.0, within(1.0));

            // The one cell that is a coincidence rather than a design: the
            // proxy exempts the lead-in positionally -- the segment *before*
            // the one starting where the grid's first beat does -- so it
            // survives only while the grid's beats line up with the map's. Move
            // the whole grid off its map and the all-lost cell reads the grid's
            // own rate instead. Not a corruption anything produces, since it
            // takes editing times as well as labels; the point is that the
            // table is for the grid the map was built from, and this is where
            // that boundary is.
            BeatGrid drifted = gridOf(pulses(0.25, 0.5, 24));
            assertThat(drifted.medianTempo(TimeSignature.FOUR_FOUR))
                    .as("the drifted grid still reads 120, so only its phase changed")
                    .isCloseTo(120.0, within(1e-6));
            assertThat(Score.empty(allLost, 12.0).withBeatGrid(drifted).estimatedTempo())
                    .isEqualTo(120.0);
        }

        @Test
        @DisplayName("a map that records nothing still gets the old shape proxy")
        void anUnlabelledMapKeepsTheOldRule() {
            // The fallback for values that predate the field. Same map as
            // aSuppliedTempoSurvivesItsLeadInWithAGrid, with every label
            // dropped: the proxy identifies the lead-in by position and still
            // answers 60. A guard, not a reproduction -- it passes against the
            // unfixed code by construction, since it is asserting that the
            // unfixed behaviour survives for exactly these values.
            TempoMap unlabelled = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 60.0 / 0.2),
                            new TempoMap.TempoSegment(1.0, 0.2, 60.0)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            Score corrected = Score.empty(unlabelled, 12.0)
                    .withBeatGrid(gridOf(pulses(0.2, 0.5, 24)));

            assertThat(provenances(unlabelled)).containsOnly(Provenance.UNKNOWN);
            assertThat(corrected.estimatedTempo()).isEqualTo(60.0);
        }

        @Test
        @DisplayName("one recorded label is enough to retire the proxy for the whole map")
        void anyRecordedProvenanceRetiresTheProxy() {
            // The gate is "records no provenance at all", not "every segment is
            // unknown at this index". A producer that labelled what it knew has
            // said enough for the guess to be the wrong tool -- and the guess
            // here would answer 120, the tempo of a map whose only labelled
            // segment says it was measured.
            TempoMap mixed = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 120.0, Provenance.MEASURED),
                            new TempoMap.TempoSegment(2.0, 1.0, 120.0)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            Score score = Score.empty(mixed, 10.0)
                    .withBeatGrid(gridOf(pulses(1.0, 0.75, 8)));

            assertThat(score.estimatedTempo()).isEqualTo(80.0);
        }
    }

    @Nested
    @DisplayName("an anchoring lead-in is not a tempo the music had")
    class LeadInIsNotATempo {

        /**
         * A lead-in of 0.2s at 300, then 60 for the rest.
         *
         * <p>Shaped like the map {@code --tempo 60} builds, but labelled
         * {@code MEASURED} rather than {@code SUPPLIED}: what is under test here
         * is the averaging, and a supplied label would short-circuit
         * {@code estimatedTempo} before it ever got there.
         */
        private TempoMap anchored() {
            return new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 300.0, Provenance.DERIVED),
                            new TempoMap.TempoSegment(1.0, 0.2, 60.0, Provenance.MEASURED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
        }

        @Test
        @DisplayName("the average excludes a labelled lead-in and the plain one does not")
        void theLeadInIsSkipped() {
            TempoMap map = anchored();

            // One whole beat crammed into 0.2s is worth 4 seconds of music at
            // the real tempo, so it moves a twelve-second average by 4 BPM.
            assertThat(map.averageTempo(12.0)).isCloseTo(64.0, within(1e-9));
            assertThat(map.averageTempoIgnoringLeadIn(12.0)).isEqualTo(60.0);
        }

        @Test
        @DisplayName("a map that labels nothing averages exactly as it always did")
        void anUnlabelledMapIsUnchanged() {
            // The compatibility claim, checked rather than argued: for every map
            // written before #120 the two methods are the same function.
            List<TempoMap> maps = List.of(
                    TempoMap.fromBeatTimes(pulses(0.2, 0.5, 8), TimeSignature.FOUR_FOUR),
                    TempoMap.constant(90, TimeSignature.THREE_FOUR),
                    new TempoMap(
                            List.of(new TempoMap.TempoSegment(0, 0.0, 120.0),
                                    new TempoMap.TempoSegment(4.0, 2.0, 60.0)),
                            List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR))));
            for (TempoMap map : maps) {
                TempoMap unlabelled = new TempoMap(
                        map.segments().stream()
                                .map(s -> new TempoMap.TempoSegment(
                                        s.startBeat(), s.startSeconds(), s.beatsPerMinute()))
                                .toList(),
                        map.meterChanges());
                assertThat(unlabelled.averageTempoIgnoringLeadIn(12.0))
                        .as("%s", unlabelled)
                        .isEqualTo(unlabelled.averageTempo(12.0));
            }
        }

        @Test
        @DisplayName("a map that is nothing but a lead-in still answers from itself")
        void aMapOfNothingButLeadInDoesNotAnswerFromNothing() {
            // Not producible today, and that is exactly why it is here: the skip
            // must not be able to leave the window empty and divide by zero.
            TempoMap allDerived = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 300.0, Provenance.DERIVED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));

            assertThat(allDerived.averageTempoIgnoringLeadIn(12.0)).isEqualTo(300.0);
        }

        @Test
        @DisplayName("a lead-in reaching past the end of the piece is not skipped either")
        void aLeadInLongerThanThePieceStillAnswers() {
            // Reachable in principle from a duration that disagrees with the
            // map. Skipping here would leave a zero-length window.
            assertThat(anchored().averageTempoIgnoringLeadIn(0.1)).isEqualTo(300.0);
            // And the boundary itself: a window ending exactly where the lead-in
            // does is empty after the skip, so the whole map answers.
            assertThat(anchored().averageTempoIgnoringLeadIn(0.2)).isEqualTo(300.0);
        }

        @Test
        @DisplayName("a window inside one segment answers with that segment's stored tempo")
        void aSingleSegmentWindowIsExact() {
            // "The tempo the file declared" is the one thing a DECLARED segment
            // is for, and a mean over one value is that value -- but computing
            // it as beats over seconds is not exact. A MIDI file meaning 140
            // stores 140.00014000014, because a tempo event carries whole
            // microseconds per quarter note, and averaging that over its own
            // span used to answer 140.00014000013996: a number the file does
            // not contain, reported as the number the file contains.
            double stored = 60_000_000.0 / (long) Math.round(60_000_000.0 / 140.0);
            TempoMap declared = TempoMap.constant(
                    stored, TimeSignature.FOUR_FOUR, Provenance.DECLARED);

            assertThat(stored).isNotEqualTo(140.0);
            assertThat(declared.averageTempo(1234.567)).isEqualTo(stored);
            assertThat(declared.averageTempoIgnoringLeadIn(1234.567)).isEqualTo(stored);
            assertThat(Score.empty(declared, 1234.567).estimatedTempo()).isEqualTo(stored);

            // And it holds for a window that lands inside one segment of a map
            // that has several, not only for a map with one.
            TempoMap changing = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, stored, Provenance.DECLARED),
                            new TempoMap.TempoSegment(8.0, 8.0 * 60.0 / stored, 60.0,
                                    Provenance.DECLARED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            assertThat(changing.averageTempo(1.0)).isEqualTo(stored);
        }

        @Test
        @DisplayName("only a leading lead-in is skipped")
        void aDerivedSegmentInTheMiddleStillCounts() {
            // The skip walks from the front and stops. A derived segment later
            // in the map is a different thing from an anchor, and dropping it
            // would silently shorten the span the average is taken over.
            TempoMap map = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 60.0, Provenance.MEASURED),
                            new TempoMap.TempoSegment(6.0, 6.0, 120.0, Provenance.DERIVED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));

            assertThat(map.averageTempoIgnoringLeadIn(12.0)).isEqualTo(map.averageTempo(12.0));
            assertThat(map.averageTempoIgnoringLeadIn(12.0)).isCloseTo(90.0, within(1e-9));
        }

        @Test
        @DisplayName("a clip that tracks one lone beat reports the tempo it assumed")
        void oneTrackedBeatDoesNotReportItsAnchor() {
            // The reachable case, and the one that caught this: a single tracked
            // pulse gives a grid too short for a median, so estimatedTempo falls
            // all the way to the map -- which is a lead-in plus the assumed 120.
            // Averaging the lead-in in reported 173 for a clip nothing was
            // measured on.
            //
            // This is a regression the labelling itself introduced, not one that
            // predates it: the shape proxy answered 120 here, and TempoOverride-
            // Test.oneTrackedBeatIsNotAnError went red the moment the proxy
            // stopped running. It is a guard against reintroducing it.
            TempoMap assumed = new TempoMap(
                    List.of(new TempoMap.TempoSegment(0, 0.0, 300.0, Provenance.DERIVED),
                            new TempoMap.TempoSegment(1.0, 0.2, 120.0, Provenance.ASSUMED)),
                    List.of(new TempoMap.MeterChange(0, TimeSignature.FOUR_FOUR)));
            Score oneBeat = Score.empty(assumed, 1.0)
                    .withBeatGrid(gridOf(List.of(0.2)));

            assertThat(oneBeat.beatGrid().orElseThrow().size()).isEqualTo(1);
            assertThat(assumed.averageTempo(1.0)).isGreaterThan(150.0);
            assertThat(oneBeat.estimatedTempo()).isEqualTo(120.0);
        }
    }

    @Nested
    @DisplayName("score files")
    class Serialization {

        /**
         * A {@code score.json} written before segments carried provenance, byte
         * for byte. Generated by building the score the current code builds for
         * {@code --tempo 60} on a recording whose first pulse falls at 0.2s,
         * writing it through {@code ScoreJson}, and deleting the
         * {@code provenance} properties -- which is exactly the file the
         * previous build wrote, since every other property is unchanged.
         *
         * <p>It carries a <em>supplied</em> tempo on purpose. A file whose tempo
         * was merely tracked would load and report the same figure however the
         * missing property were handled, so it could not tell a working
         * fallback from a broken one.
         */
        private String scoreBeforeProvenance() throws Exception {
            try (var in = getClass().getResourceAsStream("/score-before-provenance.json")) {
                assertThat(in).as("fixture on the test classpath").isNotNull();
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @Test
        @DisplayName("a score written before provenance existed still opens")
        void anOlderScoreFileLoads() throws Exception {
            // #22 is what happens when a core change quietly invalidates files
            // already on disk. A new required JSON property would repeat it, so
            // a missing one has to read as UNKNOWN rather than be rejected.
            String json = scoreBeforeProvenance();
            assertThat(json).doesNotContain("provenance");

            assertThatCode(() -> ScoreJson.fromJson(json)).doesNotThrowAnyException();
            Score score = ScoreJson.fromJson(json);

            assertThat(provenances(score.tempoMap())).containsOnly(Provenance.UNKNOWN);
            // And the answer it gave before the field existed is the answer it
            // gives now: the proxy is still there for exactly these values.
            assertThat(score.tempoMap().segments()).hasSize(2);
            assertThat(score.estimatedTempo()).isEqualTo(60.0);
            assertThat(score.beatGrid()).isPresent();
        }

        @Test
        @DisplayName("a written score carries its provenance back")
        void provenanceSurvivesARoundTrip() {
            Score score = Score.empty(
                            new TempoMap(
                                    List.of(new TempoMap.TempoSegment(
                                                    0, 0.0, 300.0, Provenance.DERIVED),
                                            new TempoMap.TempoSegment(
                                                    1.0, 0.2, 60.0, Provenance.SUPPLIED)),
                                    List.of(new TempoMap.MeterChange(
                                            0, TimeSignature.FOUR_FOUR))),
                            12.0)
                    .withBeatGrid(gridOf(pulses(0.2, 0.5, 8)));

            Score reloaded = ScoreJson.fromJson(ScoreJson.toJson(score));

            assertThat(provenances(reloaded.tempoMap()))
                    .containsExactly(Provenance.DERIVED, Provenance.SUPPLIED);
            assertThat(reloaded).isEqualTo(score);
            // The point of writing it: the correction survives a save and a
            // reload, which is the whole reason it is on the model rather than
            // in the transcriber's local variables.
            assertThat(reloaded.estimatedTempo()).isEqualTo(60.0);
        }

        @Test
        @DisplayName("a label a later build invented reads as unknown, not as a broken file")
        void anUnrecognisedLabelReadsAsUnknown() {
            // The other direction of #22. This enum's javadoc promises new
            // constants, so a score.json written once M5 adds one must still
            // open on a build that does not have it -- and Jackson's default is
            // to fail the whole document on an unknown enum name, not just the
            // property. Confirmed by trying it: without the reader on
            // Provenance this throws InvalidFormatException naming the six
            // constants it does know.
            String json = """
                    {"segments":[{"startBeat":0.0,"startSeconds":0.0,\
                    "beatsPerMinute":120.0,"provenance":"ADVISED"}],\
                    "meterChanges":[{"startBar":0,\
                    "timeSignature":{"numerator":4,"denominator":4}}]}""";

            assertThatCode(() -> readMap(json)).doesNotThrowAnyException();
            assertThat(provenances(readMap(json))).containsExactly(Provenance.UNKNOWN);
            assertThat(Provenance.fromJson("ADVISED")).isEqualTo(Provenance.UNKNOWN);
            assertThat(Provenance.fromJson("SUPPLIED")).isEqualTo(Provenance.SUPPLIED);
            // Reachable only by a direct call: Jackson resolves a null token
            // before a delegating creator sees it, so a null in a file is
            // TempoSegment's business and is covered by the test below.
            assertThat(Provenance.fromJson(null)).isEqualTo(Provenance.UNKNOWN);
            // Writing is untouched by the reader, which is what stops a
            // forgiving read turning into a lossy write.
            assertThat(ScoreJson.toJson(Score.empty(
                    TempoMap.constant(120, TimeSignature.FOUR_FOUR, Provenance.SUPPLIED), 4.0)))
                    .contains("\"provenance\" : \"SUPPLIED\"");
        }

        @Test
        @DisplayName("an explicit null provenance reads as unknown, not as a broken file")
        void anExplicitNullReadsAsUnknown() {
            // Jackson writes an absent Optional as an explicit null elsewhere in
            // this model, so a hand-edited or hand-written file may well carry
            // one here too. It must land in the same place a missing property
            // does rather than throwing on a null component.
            String json = """
                    {"segments":[{"startBeat":0.0,"startSeconds":0.0,\
                    "beatsPerMinute":120.0,"provenance":null}],\
                    "meterChanges":[{"startBar":0,\
                    "timeSignature":{"numerator":4,"denominator":4}}]}""";

            assertThatCode(() -> readMap(json)).doesNotThrowAnyException();
            assertThat(provenances(readMap(json))).containsExactly(Provenance.UNKNOWN);
        }

        private TempoMap readMap(String json) {
            try {
                return ScoreJson.mapper().readValue(json, TempoMap.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
