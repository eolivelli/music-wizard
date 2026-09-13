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

package dev.olivelli.musicwizard.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** A line alone's note values deciding the octave of its pulse (#844). */
@DisplayName("note values")
class NoteValuesTest {

    private static final double FRAME_RATE = 172.0;

    /** A line of the given values, in beats at {@code rate}, back to back. */
    private static NoteTrack line(double rate, double... beats) {
        List<Note> notes = new ArrayList<>();
        double at = 0.5;
        for (double value : beats) {
            double seconds = value * 60 / rate;
            notes.add(Note.ofSeconds(at, seconds, 72, Confidence.UNKNOWN));
            at += seconds;
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, "line", notes, Confidence.UNKNOWN);
    }

    /** The flute take's shape: a dotted half and a quarter, alternating. */
    private static double[] dottedRhythm(int pairs) {
        double[] values = new double[2 * pairs + 1];
        for (int i = 0; i < pairs; i++) {
            values[2 * i] = 3;
            values[2 * i + 1] = 1;
        }
        values[2 * pairs] = 3;
        return values;
    }

    private static TempoEstimator.Estimate sweep(double chosen, double... rivals) {
        List<TempoEstimator.Candidate> candidates = new ArrayList<>();
        candidates.add(new TempoEstimator.Candidate(chosen, 0.5, true));
        for (double rival : rivals) {
            candidates.add(new TempoEstimator.Candidate(rival, 0.4, false));
        }
        return new TempoEstimator.Estimate(chosen, 0.5, 0.5, candidates);
    }

    @Nested
    @DisplayName("the decision")
    class Decision {

        @Test
        @DisplayName("a line of dotted halves and quarters, the half ranked, is halved")
        void dottedHalvesAndQuartersAreHalved() {
            NoteValues.Octave octave = NoteValues.resolve(224,
                    line(224, dottedRhythm(7)), List.of(sweep(224, 111, 74)));

            assertThat(octave.halved()).isTrue();
            assertThat(octave.rate()).isEqualTo(112);
            assertThat(octave.reading().subdivisionShare()).isZero();
            assertThat(octave.reading().longShare()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("a line of quarters keeps its pulse")
        void quartersKeepThePulse() {
            double[] quarters = new double[16];
            Arrays.fill(quarters, 1);

            NoteValues.Octave octave =
                    NoteValues.resolve(100, line(100, quarters), List.of(sweep(100, 50)));

            assertThat(octave.halved()).isFalse();
            assertThat(octave.rate()).isEqualTo(100);
            assertThat(octave.reading().longShare()).isZero();
        }

        @Test
        @DisplayName("a line whose halves outnumber its quarters but carries eighths keeps its pulse")
        void aCommonEighthKeepsThePulse() {
            // Halved, those eighths would be sixteenths.
            NoteValues.Octave octave = NoteValues.resolve(120,
                    line(120, 2, 0.5, 2, 0.5, 2, 0.5, 2, 0.5, 2, 2, 2, 2),
                    List.of(sweep(120, 60)));

            assertThat(octave.reading().longShare()).isGreaterThan(0.5);
            assertThat(octave.reading().subdivisionShare()).isGreaterThan(0.2);
            assertThat(octave.halved()).isFalse();
        }

        @Test
        @DisplayName("the half is restored only where the sweep ranked it")
        void anUnrankedHalfIsNotInvented() {
            NoteTrack shape = line(224, dottedRhythm(7));

            assertThat(NoteValues.resolve(224, shape, List.of(sweep(224, 74, 56))).halved())
                    .isFalse();
            assertThat(NoteValues.resolve(224, shape, List.of(sweep(224, 111))).halved())
                    .isTrue();
        }

        @Test
        @DisplayName("most windows must rank the half, not one")
        void aMinorityOfWindowsDoesNotDecide() {
            NoteTrack shape = line(224, dottedRhythm(7));
            List<TempoEstimator.Estimate> oneOfThree =
                    List.of(sweep(224, 111), sweep(224, 74), sweep(224, 56));
            List<TempoEstimator.Estimate> twoOfThree =
                    List.of(sweep(224, 111), sweep(224, 112), sweep(224, 56));

            assertThat(NoteValues.resolve(224, shape, oneOfThree).halved()).isFalse();
            assertThat(NoteValues.resolve(224, shape, twoOfThree).halved()).isTrue();
        }

        @Test
        @DisplayName("a handful of notes decides nothing")
        void tooFewNotesDecideNothing() {
            NoteValues.Octave octave =
                    NoteValues.resolve(224, line(224, 3, 1, 3, 1, 3), List.of(sweep(224, 111)));

            assertThat(octave.halved()).isFalse();
            assertThat(octave.reading().longShare()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("the halved line does not halve again")
        void theHalvedLineStands() {
            // At the halved rate the quarters are eighths: a common subdivision.
            NoteValues.Octave octave = NoteValues.resolve(112,
                    line(224, dottedRhythm(7)), List.of(sweep(112, 56)));

            assertThat(octave.halved()).isFalse();
            assertThat(octave.reading().subdivisionShare()).isGreaterThan(0.2);
        }

        @Test
        @DisplayName("no melody, or a half below the sweep's floor, takes no reading")
        void noReadingWithoutNotesOrRoom() {
            assertThat(NoteValues.resolve(224, null, List.of(sweep(224, 111))).reading())
                    .isNull();
            assertThat(NoteValues.resolve(224, NoteTrack.empty(PartRole.LEAD_VOCAL, "line"),
                    List.of(sweep(224, 111))).reading()).isNull();
            NoteValues.Octave floor = NoteValues.resolve(70,
                    line(70, dottedRhythm(7)), List.of(sweep(70, 35)));
            assertThat(floor.reading()).isNull();
            assertThat(floor.rate()).isEqualTo(70);
        }
    }

    @Nested
    @DisplayName("through the tracker")
    class ThroughTheTracker {

        /** A line as the tracker sees it: the flux, the flux with the notes lifted in, and the notes. */
        private record Line(OnsetEnvelope heard, OnsetEnvelope lifted, NoteTrack notes) {
        }

        /**
         * The flute take's shape (#844): a line in dotted halves and quarters
         * at the unit's rate, played with a human's give in each value, over
         * a flux that hears the note's onset and a faint articulation at each
         * unit inside it. The sweep puts the unit's rate first and the beat
         * second, with or without the notes lifted in.
         */
        private Line dottedLine(double unitRate, double seconds) {
            double unit = 60 / unitRate;
            int frames = (int) (seconds * FRAME_RATE);
            double[] flux = new double[frames];
            java.util.Random noise = new java.util.Random(7);
            for (int i = 0; i < frames; i++) {
                flux[i] = 0.05 * noise.nextGaussian();
            }
            List<Note> notes = new ArrayList<>();
            int index = 0;
            for (double t = 0.5; t + 3 * unit < seconds; index++) {
                double written = index % 2 == 0 ? 3 * unit : unit;
                double length = written * (1 + 0.08 * noise.nextGaussian());
                notes.add(Note.ofSeconds(t, length, 72 + index % 3, Confidence.UNKNOWN));
                attack(flux, t, index % 2 == 0 ? 2.5 : 1.5);
                for (double inside = t + unit; inside < t + length - unit / 2; inside += unit) {
                    attack(flux, inside, 0.8);
                }
                t += length;
            }
            NoteTrack line = new NoteTrack(PartRole.LEAD_VOCAL, "line", notes, Confidence.UNKNOWN);
            OnsetEnvelope heard = normalised(flux);
            return new Line(heard, heard.withNoteOnsets(line), line);
        }

        private void attack(double[] flux, double seconds, double height) {
            int at = (int) Math.round(seconds * FRAME_RATE);
            for (int k = -2; k <= 2; k++) {
                if (at + k >= 0 && at + k < flux.length) {
                    flux[at + k] += height * (1 - Math.abs(k) / 3.0);
                }
            }
        }

        @Test
        @DisplayName("the notes halve a pulse tracked at their unit, in one window and in several")
        void theNotesHalveAPulseTrackedAtTheirUnit() {
            for (double seconds : new double[] {12, 40}) {
                Line line = dottedLine(222, seconds);

                BeatTracker.Result withoutValues =
                        BeatTracker.track(line.lifted(), HarmonicRhythm.none(), null, line.heard());
                BeatTracker.Result withValues = BeatTracker.track(line.lifted(),
                        HarmonicRhythm.none(), null, line.heard(), line.notes());

                assertThat(withoutValues.beatsPerMinute()).isCloseTo(222, within(3.0));
                assertThat(withValues.beatsPerMinute()).isCloseTo(111, within(3.0));
                assertThat(withValues.trace().noteValuesMoved()).isTrue();
                assertThat(withValues.trace().referencePulse())
                        .isCloseTo(withValues.trace().agreedPulse() / 2, within(1e-9));
                for (BeatTrace.Window window : withValues.trace().windows()) {
                    assertThat(window.trackedPulse()).isCloseTo(111, within(3.0));
                }
            }
        }

        @Test
        @DisplayName("without the notes the tracker reads as before")
        void withoutNotesNothingMoves() {
            Line line = dottedLine(222, 12);

            BeatTracker.Result result = BeatTracker.track(line.lifted(), HarmonicRhythm.none(),
                    null, line.heard(), null);

            assertThat(result.trace().noteValues()).isNull();
            assertThat(result.beatsPerMinute()).isCloseTo(222, within(3.0));
        }
    }

    private static OnsetEnvelope normalised(double[] values) {
        double mean = Arrays.stream(values).average().orElseThrow();
        double deviation = Math.sqrt(Arrays.stream(values)
                .map(v -> (v - mean) * (v - mean)).average().orElseThrow());
        return new OnsetEnvelope(Arrays.stream(values).map(v -> (v - mean) / deviation).toArray(),
                FRAME_RATE);
    }
}
