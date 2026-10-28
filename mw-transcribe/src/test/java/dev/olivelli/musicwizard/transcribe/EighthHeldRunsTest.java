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

package dev.olivelli.musicwizard.transcribe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber.HeldSection;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber.MeterChangeAt;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bars of 3/4 with the eighth held, found inside 6/8 and only proposed (#895). */
class EighthHeldRunsTest {

    private static final double PULSE = 0.45;

    private static final int PULSES = 40;

    private static final List<HeldSection> SIX_EIGHT =
            List.of(new HeldSection(0, TimeSignature.SIX_EIGHT, 1.5, 2));

    private static List<Double> beats(int pulses) {
        List<Double> beats = new ArrayList<>();
        for (int i = 0; i < pulses; i++) {
            beats.add(0.1 + i * PULSE);
        }
        return beats;
    }

    private static NoteTrack line(List<Double> positions) {
        return line(beats(PULSES), positions);
    }

    /** A note at each position, in pulses of {@code beats}. */
    private static NoteTrack line(List<Double> beats, List<Double> positions) {
        List<Note> notes = new ArrayList<>();
        for (double position : positions) {
            int pulse = (int) Math.floor(position);
            double seconds = beats.get(pulse)
                    + (position - pulse) * (beats.get(pulse + 1) - beats.get(pulse));
            notes.add(Note.ofSeconds(seconds, 0.2, 76, Confidence.of(0.9)));
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, "Flute", notes, Confidence.of(0.9));
    }

    /** A 6/8 body of quarter-eighth pulses, with {@code bars} bars of even quarters from pulse 10. */
    private static List<Double> withRun(int bars, double jitter) {
        List<Double> positions = new ArrayList<>();
        for (int pulse = 0; pulse < 10; pulse++) {
            positions.add((double) pulse);
            positions.add(pulse + 2.0 / 3);
        }
        for (int step = 0; step < 3 * bars; step++) {
            positions.add(10 + step * 2.0 / 3 + (step % 2 == 1 ? jitter : -jitter / 2));
        }
        for (int pulse = 10 + 2 * bars; pulse < PULSES - 1; pulse++) {
            positions.add((double) pulse);
            positions.add(pulse + 2.0 / 3);
        }
        return positions;
    }

    @Test
    @DisplayName("proposes 3/4 with the eighth held over whole bars of even quarters, and 6/8 after")
    void proposesTheRunAndTheWayBack() {
        List<Double> beats = beats(PULSES);

        assertThat(EighthHeldRuns.propose(beats, SIX_EIGHT, line(withRun(3, 0.1))))
                .containsExactly(
                        new MeterChangeAt(beats.get(10), TimeSignature.THREE_FOUR, true),
                        new MeterChangeAt(beats.get(16), TimeSignature.SIX_EIGHT, true));
    }

    @Test
    @DisplayName("proposes nothing for a body of quarter-eighth pulses or one hemiola bar")
    void leavesTheBodyAndAHemiolaAlone() {
        List<Double> beats = beats(PULSES);

        assertThat(EighthHeldRuns.propose(beats, SIX_EIGHT, line(withRun(0, 0)))).isEmpty();
        assertThat(EighthHeldRuns.propose(beats, SIX_EIGHT, line(withRun(1, 0)))).isEmpty();
    }

    @Test
    @DisplayName("proposes nothing where the quarters stray towards a duplet")
    void needsTheRunEven() {
        assertThat(EighthHeldRuns.propose(beats(PULSES), SIX_EIGHT, line(withRun(3, 0.2))))
                .isEmpty();
    }

    @Test
    @DisplayName("proposes nothing in 3/4 at the quarter pulse, where the run is triplet quarters")
    void needsTheCompoundPulse() {
        List<HeldSection> threeFour = List.of(new HeldSection(0, TimeSignature.THREE_FOUR, 1, 3));

        assertThat(EighthHeldRuns.propose(beats(PULSES), threeFour, line(withRun(3, 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("proposes nothing where the pulse under the run does not hold")
    void needsTheSteadyPulse() {
        List<Double> beats = beats(PULSES);
        for (int i = 13; i < PULSES; i++) {
            beats.set(i, beats.get(i) + 0.15);
        }

        assertThat(EighthHeldRuns.propose(beats, SIX_EIGHT, line(beats, withRun(3, 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("ends the run into a later change that holds the eighth without a way back")
    void runsIntoAnEighthHeldChange() {
        List<Double> beats = beats(PULSES);
        List<HeldSection> sections = List.of(SIX_EIGHT.get(0),
                new HeldSection(16, new TimeSignature(9, 8), 1.5, 3, true));

        assertThat(EighthHeldRuns.propose(beats, sections, line(withRun(3, 0))))
                .containsExactly(new MeterChangeAt(beats.get(10), TimeSignature.THREE_FOUR, true));
    }

    @Test
    @DisplayName("hands back to 6/8 a bar early before a change counted from the beat, or a 3/4")
    void handsBackBeforeAnyOtherChange() {
        List<Double> beats = beats(PULSES);
        for (HeldSection next : List.of(new HeldSection(16, new TimeSignature(9, 8), 1.5, 3),
                new HeldSection(16, TimeSignature.THREE_FOUR, 1.5, 2, true))) {
            assertThat(EighthHeldRuns.propose(beats, List.of(SIX_EIGHT.get(0), next),
                    line(withRun(3, 0))))
                    .as("before %s", next)
                    .containsExactly(
                            new MeterChangeAt(beats.get(10), TimeSignature.THREE_FOUR, true),
                            new MeterChangeAt(beats.get(14), TimeSignature.SIX_EIGHT, true));
        }
    }

    @Test
    @DisplayName("proposes nothing for a run of two bars that runs into a change counted from the beat")
    void leavesAShortRunIntoAChange() {
        List<HeldSection> sections = List.of(SIX_EIGHT.get(0),
                new HeldSection(14, new TimeSignature(9, 8), 1.5, 3));

        assertThat(EighthHeldRuns.propose(beats(PULSES), sections, line(withRun(2, 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("pasted beside a later change, leaves that change barred as it was supplied")
    void pastingKeepsTheSuppliedChange() {
        List<Double> beats = beats(PULSES);
        for (MeterChangeAt supplied : List.of(
                new MeterChangeAt(beats.get(16), new TimeSignature(9, 8)),
                new MeterChangeAt(beats.get(16), new TimeSignature(9, 8), true),
                new MeterChangeAt(beats.get(16), new TimeSignature(2, 4)),
                new MeterChangeAt(beats.get(16), TimeSignature.THREE_FOUR, true))) {
            List<HeldSection> sections = AudioTranscriber.heldSections(beats,
                    TimeSignature.SIX_EIGHT, 1.5, 2, 0, List.of(supplied), new ArrayList<>());
            List<MeterChangeAt> pasted = new ArrayList<>(
                    EighthHeldRuns.propose(beats, sections, line(withRun(3, 0))));
            assertThat(pasted).as("before %s", supplied).isNotEmpty();
            pasted.add(supplied);
            pasted.sort(java.util.Comparator.comparingDouble(MeterChangeAt::seconds));

            assertThat(AudioTranscriber.heldSections(beats, TimeSignature.SIX_EIGHT, 1.5, 2, 0,
                    pasted, new ArrayList<>()).getLast())
                    .as("before %s", supplied)
                    .isEqualTo(sections.getLast());
        }
    }

    @Test
    @DisplayName("proposes nothing for a run of eighths in threes (#902)")
    void leavesTripletEighthsAlone() {
        List<Double> positions = new ArrayList<>();
        for (int third = 0; third < 3 * (PULSES - 1); third++) {
            positions.add(third / 3.0);
        }

        assertThat(EighthHeldRuns.propose(beats(PULSES), SIX_EIGHT, line(positions))).isEmpty();
    }

    @Test
    @DisplayName("writes the whole value, every change at the beat it takes effect on, in order")
    void writesAValueToPaste() {
        List<Double> beats = beats(PULSES);
        List<HeldSection> sections = List.of(
                new HeldSection(0, TimeSignature.THREE_FOUR, 1, 3),
                new HeldSection(6, TimeSignature.SIX_EIGHT, 1.5, 2),
                new HeldSection(30, TimeSignature.THREE_FOUR, 1.5, 2, true));

        String value = EighthHeldRuns.timeSignatureValue(beats, sections,
                List.of(new MeterChangeAt(beats.get(10), TimeSignature.THREE_FOUR, true),
                        new MeterChangeAt(beats.get(16), TimeSignature.SIX_EIGHT, true)));

        assertThat(value).isEqualTo("3/4,6/8@2.80,3/4@4.60=8,6/8@7.30=8,3/4@13.60=8");
    }
}
