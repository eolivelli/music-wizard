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

/** A line alone's note values deciding the octave of its pulse (#844, #851). */
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
        @DisplayName("a line of dotted halves and quarters above the prior's centre, the half ranked, is halved")
        void dottedHalvesAndQuartersAreHalved() {
            NoteValues.Octave octave = NoteValues.resolve(224, false,
                    line(224, dottedRhythm(7)), List.of(sweep(224, 111, 74)));

            assertThat(octave.halved()).isTrue();
            assertThat(octave.rate()).isEqualTo(112);
            assertThat(octave.reading().subdivisionShare()).isZero();
            assertThat(octave.reading().priorPrefersHalf()).isTrue();
        }

        @Test
        @DisplayName("whether the line ends on the long value or the short one changes nothing")
        void theClosingNoteDoesNotDecide() {
            double[] endsShort = Arrays.copyOf(dottedRhythm(7), 14);
            double[] split = Arrays.copyOf(dottedRhythm(7), 16);
            split[14] = 1.5;
            split[15] = 1.5;

            for (double[] shape : new double[][] {dottedRhythm(7), endsShort, split}) {
                assertThat(NoteValues.resolve(224, false, line(224, shape), List.of(sweep(224, 111)))
                        .halved()).isTrue();
            }
        }

        @Test
        @DisplayName("the same line below the prior's centre keeps its pulse")
        void aSlowLineKeepsItsPulse() {
            NoteValues.Octave octave =
                    NoteValues.resolve(88, false, line(88, dottedRhythm(7)), List.of(sweep(88, 44)));

            assertThat(octave.reading().unit()).isTrue();
            assertThat(octave.reading().priorPrefersHalf()).isFalse();
            assertThat(octave.halved()).isFalse();
        }

        @Test
        @DisplayName("a line of quarters leaves the octave to the prior")
        void quartersLeaveTheOctaveToThePrior() {
            double[] quarters = new double[16];
            Arrays.fill(quarters, 1);

            assertThat(NoteValues.resolve(100, false, line(100, quarters), List.of(sweep(100, 50)))
                    .rate()).isEqualTo(100);
            assertThat(NoteValues.resolve(200, false, line(200, quarters), List.of(sweep(200, 100)))
                    .rate()).isEqualTo(100);
        }

        @Test
        @DisplayName("a line with a common eighth is not tracked at its unit and stands")
        void aCommonEighthKeepsThePulse() {
            NoteValues.Octave octave = NoteValues.resolve(224, false,
                    line(224, 3, 0.5, 3, 0.5, 3, 0.5, 3, 0.5, 3, 3, 3, 3),
                    List.of(sweep(224, 112)));

            assertThat(octave.reading().priorPrefersHalf()).isTrue();
            assertThat(octave.reading().subdivisionShare()).isGreaterThan(0.2);
            assertThat(octave.halved()).isFalse();
        }

        @Test
        @DisplayName("the half is restored only where the sweep ranked it")
        void anUnrankedHalfIsNotInvented() {
            NoteTrack shape = line(224, dottedRhythm(7));

            assertThat(NoteValues.resolve(224, false, shape, List.of(sweep(224, 74, 56))).halved())
                    .isFalse();
            assertThat(NoteValues.resolve(224, false, shape, List.of(sweep(224, 111))).halved())
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

            assertThat(NoteValues.resolve(224, false, shape, oneOfThree).halved()).isFalse();
            assertThat(NoteValues.resolve(224, false, shape, twoOfThree).halved()).isTrue();
        }

        @Test
        @DisplayName("a handful of notes decides nothing")
        void tooFewNotesDecideNothing() {
            NoteValues.Octave octave =
                    NoteValues.resolve(224, false, line(224, 3, 1, 3, 1, 3), List.of(sweep(224, 111)));

            assertThat(octave.halved()).isFalse();
            assertThat(octave.reading().unit()).isFalse();
        }

        @Test
        @DisplayName("the halved line does not halve again")
        void theHalvedLineStands() {
            // At the halved rate the quarters are eighths: a common subdivision.
            NoteValues.Octave octave = NoteValues.resolve(112, false,
                    line(224, dottedRhythm(7)), List.of(sweep(112, 56)));

            assertThat(octave.halved()).isFalse();
            assertThat(octave.reading().subdivisionShare()).isGreaterThan(0.2);
        }

        @Test
        @DisplayName("no melody takes no reading")
        void noReadingWithoutNotes() {
            assertThat(NoteValues.resolve(224, false, null, List.of(sweep(224, 111))).reading())
                    .isNull();
            assertThat(NoteValues.resolve(224, false, NoteTrack.empty(PartRole.LEAD_VOCAL, "line"),
                    List.of(sweep(224, 111))).reading()).isNull();
        }

        /** The half-tempo shape (#851): quarters, eighths and halves read at half their pulse. */
        private static double[] sixteenthsRhythm(int bars) {
            double[] values = new double[6 * bars];
            for (int i = 0; i < bars; i++) {
                double[] bar = {0.25, 0.25, 0.5, 0.5, 0.25, 0.25};
                System.arraycopy(bar, 0, values, 6 * i, bar.length);
            }
            return values;
        }

        @Test
        @DisplayName("a line with a common sixteenth below the prior's centre, the double ranked, is doubled")
        void aCommonSixteenthDoublesASlowPulse() {
            NoteValues.Octave octave = NoteValues.resolve(42, false,
                    line(42, sixteenthsRhythm(4)), List.of(sweep(42, 83.5)));

            assertThat(octave.doubled()).isTrue();
            assertThat(octave.halved()).isFalse();
            assertThat(octave.rate()).isEqualTo(84);
            assertThat(octave.reading().quarterShare()).isGreaterThan(0.2);
            assertThat(octave.reading().priorPrefersDouble()).isTrue();
        }

        @Test
        @DisplayName("the same line above the prior's crossover keeps its pulse")
        void aQuickLineInSixteenthsKeepsItsPulse() {
            NoteValues.Octave octave = NoteValues.resolve(100, false,
                    line(100, sixteenthsRhythm(4)), List.of(sweep(100, 200)));

            assertThat(octave.reading().inQuarters()).isTrue();
            assertThat(octave.reading().doubleRanked()).isTrue();
            assertThat(octave.reading().priorPrefersDouble()).isFalse();
            assertThat(octave.doubled()).isFalse();
        }

        @Test
        @DisplayName("a line whose shortest common value is an eighth is not doubled")
        void aCommonEighthDoesNotDouble() {
            double[] eighthsAndQuarters = new double[24];
            for (int i = 0; i < eighthsAndQuarters.length; i++) {
                eighthsAndQuarters[i] = i % 3 == 2 ? 1 : 0.5;
            }
            NoteValues.Octave octave = NoteValues.resolve(60, false,
                    line(60, eighthsAndQuarters), List.of(sweep(60, 120)));

            assertThat(octave.reading().priorPrefersDouble()).isTrue();
            assertThat(octave.reading().quarterShare()).isLessThan(0.2);
            assertThat(octave.doubled()).isFalse();
        }

        @Test
        @DisplayName("the double is restored only where most windows ranked it")
        void anUnrankedDoubleIsNotInvented() {
            NoteTrack shape = line(42, sixteenthsRhythm(4));

            assertThat(NoteValues.resolve(42, false, shape, List.of(sweep(42, 63))).doubled())
                    .isFalse();
            assertThat(NoteValues.resolve(42, false, shape,
                    List.of(sweep(42, 84), sweep(42, 63), sweep(42, 63))).doubled()).isFalse();
            assertThat(NoteValues.resolve(42, false, shape,
                    List.of(sweep(42, 84), sweep(42, 83), sweep(42, 63))).doubled()).isTrue();
        }

        @Test
        @DisplayName("the doubled line does not double again, and is not halved back")
        void theDoubledLineStands() {
            NoteValues.Octave octave = NoteValues.resolve(84, false,
                    line(42, sixteenthsRhythm(4)), List.of(sweep(84, 42, 168)));

            assertThat(octave.doubled()).isFalse();
            assertThat(octave.halved()).isFalse();
            assertThat(octave.reading().quarterShare()).isZero();
            assertThat(octave.reading().subdivisionShare()).isGreaterThan(0.2);
        }

        @Test
        @DisplayName("a rate the register halved is not doubled back")
        void aRegisterHalvedRateIsNotDoubledBack() {
            NoteTrack shape = line(60, sixteenthsRhythm(4));
            List<TempoEstimator.Estimate> seeds = List.of(sweep(120, 60));

            assertThat(NoteValues.resolve(60, false, shape, seeds).doubled()).isTrue();
            NoteValues.Octave kept = NoteValues.resolve(60, true, shape, seeds);
            assertThat(kept.doubled()).isFalse();
            assertThat(kept.rate()).isEqualTo(60);
            assertThat(kept.reading().callsForDoubling()).isTrue();
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

        /** A broad rise in the flux, several frames either side of a moment. */
        private void swell(double[] flux, double seconds, double height) {
            int at = (int) Math.round(seconds * FRAME_RATE);
            for (int k = -8; k <= 8; k++) {
                if (at + k >= 0 && at + k < flux.length) {
                    flux[at + k] += height * (1 - Math.abs(k) / 9.0);
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

        /**
         * The half-tempo shape (#851): a line in halves, quarters and eighths
         * with a note on every half note, over a flux that hears the notes
         * faintly and swells at each half note, as a sustained instrument's
         * flux does at a legato change. The sweep puts the half note's rate
         * first and the beat second.
         */
        private Line halfNoteLine(double beatRate, double seconds) {
            double beat = 60 / beatRate;
            int frames = (int) (seconds * FRAME_RATE);
            double[] flux = new double[frames];
            java.util.Random noise = new java.util.Random(11);
            for (int i = 0; i < frames; i++) {
                flux[i] = 0.05 * noise.nextGaussian();
            }
            double[] cycle = {2, 2, 2, 0.5, 0.5, 1, 0.5, 0.5, 1, 2, 2, 2, 1, 1, 2, 0.5, 0.5, 1, 2};
            List<Note> notes = new ArrayList<>();
            int index = 0;
            double position = 0;
            for (double t = 0.5; t + 2 * beat < seconds; index++) {
                double value = cycle[index % cycle.length];
                double written = value * beat;
                double length = written * (1 + 0.05 * noise.nextGaussian());
                notes.add(Note.ofSeconds(t, length, 72 + index % 5, Confidence.UNKNOWN));
                attack(flux, t, 0.5);
                if (position % 2 == 0) {
                    swell(flux, t, 3.0);
                }
                t += length;
                position += value;
            }
            NoteTrack line = new NoteTrack(PartRole.LEAD_VOCAL, "line", notes, Confidence.UNKNOWN);
            OnsetEnvelope heard = normalised(flux);
            return new Line(heard, heard.withNoteOnsets(line), line);
        }

        @Test
        @DisplayName("the notes double a slow pulse tracked at the line's half note")
        void theNotesDoubleAPulseTrackedAtTheHalfNote() {
            for (double seconds : new double[] {12, 40}) {
                Line line = halfNoteLine(84, seconds);

                BeatTracker.Result withoutValues =
                        BeatTracker.track(line.lifted(), HarmonicRhythm.none(), null, line.heard());
                BeatTracker.Result withValues = BeatTracker.track(line.lifted(),
                        HarmonicRhythm.none(), null, line.heard(), line.notes());

                assertThat(withoutValues.beatsPerMinute()).isCloseTo(42, within(2.0));
                assertThat(withValues.beatsPerMinute()).isCloseTo(84, within(3.0));
                assertThat(withValues.trace().noteValuesMoved()).isTrue();
                assertThat(withValues.trace().noteValues().doubled()).isTrue();
                assertThat(withValues.trace().referencePulse())
                        .isCloseTo(withValues.trace().agreedPulse() * 2, within(1e-9));
                for (BeatTrace.Window window : withValues.trace().windows()) {
                    assertThat(window.trackedPulse()).isCloseTo(84, within(3.0));
                }
            }
        }

        @Test
        @DisplayName("the register's halving stands against a line with a common sixteenth")
        void theRegistersHalvingIsNotDoubledBack() {
            // The #509 fixture: a kick on the quarters under a hat on every
            // eighth, which the envelope and the prior read at the eighth and
            // the register halves. A line of quarters, eighths and sixteenth
            // pairs over it has a common value at a quarter of the halved
            // pulse, whose double is the very rate the register left.
            double quarters = 60;
            OnsetEnvelope.Both onsets =
                    BeatTrackingTest.bothOf(BeatTrackingTest.kickAndHat(quarters, 60, 3));
            List<Note> notes = new ArrayList<>();
            double beat = 60 / quarters;
            double[] bar = {1, 0.5, 0.5, 1, 0.25, 0.25, 0.5};
            int index = 0;
            for (double t = 0.5; t + beat < 60; index++) {
                double written = bar[index % bar.length] * beat;
                notes.add(Note.ofSeconds(t, written * 0.9, 60 + index % 7, Confidence.UNKNOWN));
                t += written;
            }
            NoteTrack line = new NoteTrack(PartRole.LEAD_VOCAL, "line", notes, Confidence.UNKNOWN);

            BeatTracker.Result register = BeatTracker.track(onsets.envelope(),
                    HarmonicRhythm.none(), onsets.pulseRegister());
            BeatTracker.Result registerAndLine = BeatTracker.track(onsets.envelope(),
                    HarmonicRhythm.none(), onsets.pulseRegister(), onsets.envelope(), line);

            assertThat(register.trace().octaveMoved()).isTrue();
            assertThat(register.beatsPerMinute()).isCloseTo(quarters, within(3.0));
            assertThat(registerAndLine.trace().octaveMoved()).isTrue();
            BeatTrace.NoteValues values = registerAndLine.trace().noteValues();
            assertThat(values.quarterShare()).isGreaterThan(0.2);
            assertThat(values.doubleRanked()).isTrue();
            assertThat(values.priorPrefersDouble()).isTrue();
            assertThat(values.doubled()).isFalse();
            assertThat(registerAndLine.trace().noteValuesMoved()).isFalse();
            assertThat(registerAndLine.beatsPerMinute()).isCloseTo(quarters, within(3.0));
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
