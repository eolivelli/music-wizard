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

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.SwingFeel;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;

/**
 * Music that more than one emitter's tests engrave.
 *
 * <p>Here because a fixture two test classes each hold a copy of is a fixture
 * that agrees today and need not tomorrow.
 * {@code triplet-eighths.ly} and {@code triplet-eighths.musicxml} once described
 * different music under one name, and the first fix — copying the notes
 * across — could reproduce the same defect in one edit, because nothing made the
 * two copies stay equal. This is the half of the fix that makes them one thing.
 *
 * <p>The other half is in {@code MusicXmlExportTest.assertGolden}, which
 * requires the LilyPond it generates to equal the committed {@code .ly} golden
 * of the same name. That catches drift even in the fixtures that are still held
 * separately, which is why both exist: this removes the duplication, and that
 * catches whatever duplication is left.
 */
final class Fixtures {

    private Fixtures() {
    }

    /**
     * The triplet fixture, with the quantizer verdict it is read against.
     *
     * @param plan  the score and its per-bar grids
     * @param voice the one part, which is what an emitter takes
     */
    record Quantized(QuantizedScore plan, NoteTrack voice) {

        /** The plain score, for the emitter overloads that take one. */
        Score score() {
            return plan.score();
        }
    }

    /**
     * Four bars on a triplet grid, which is the case #92 is about.
     *
     * <p>Bar 1 is plain eighths, so the bracketed bars have something to be read
     * against. Bar 2 is four beats of triplet eighths. Bar 3 is the same grid
     * subdivided in only two of its four beats — the other two must come out as
     * plain quarters, because a bracket says a beat was divided in three and
     * printing one round a beat that was not is as wrong as leaving it off the
     * beat that was. Bar 4 is a whole note on the same grid, which needs no
     * bracket at all.
     */
    static Quantized tripletPractice() {
        List<Note> notes = new ArrayList<>();
        String[] scale = {"C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5"};
        for (int i = 0; i < 8; i++) {
            notes.add(note(i * 0.5, 0.5, scale[i]));
        }
        String[] run = {"C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5", "D5", "E5", "D5", "C5"};
        for (int i = 0; i < 12; i++) {
            notes.add(note(4 + thirds(i), thirds(1), run[i]));
        }
        notes.add(note(8, thirds(1), "C4"));
        notes.add(note(8 + thirds(1), thirds(2), "D4"));
        notes.add(note(9, 1 + thirds(1), "E4"));
        notes.add(note(10 + thirds(1), thirds(2), "F4"));
        notes.add(note(11, 1, "G4"));
        notes.add(note(12, 4, "C4"));

        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        Score score = Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 60)
                .withTrack(voice)
                .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MAJOR,
                        0, 60, Confidence.CERTAIN)))
                .withMetadata("Triplet Practice", "Anonymous");
        return new Quantized(quantized(score, GridResolution.HALF_BEAT,
                GridResolution.THIRD_BEAT, GridResolution.THIRD_BEAT, GridResolution.THIRD_BEAT),
                voice);
    }

    /** A note with musical timing, as the quantizer would leave it. */
    private static Note note(double onsetBeat, double beats, String spelling) {
        PitchSpelling written = PitchSpelling.parse(spelling);
        // The seconds are what a 120 BPM reading of the beats would give. They
        // are deliberately not what an emitter reads; if one ever did, these
        // tests would still pass and the beat axis would have stopped mattering.
        return Note.ofSeconds(onsetBeat / 2 + 0.5, beats / 2, written.midiPitch(),
                        Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(written);
    }

    /** A position or length of {@code steps} triplet eighths, in quarter beats. */
    private static double thirds(double steps) {
        return steps / 3.0;
    }

    /**
     * A quantizer verdict for a score: one grid per bar, in bar order.
     *
     * <p>Built by hand rather than by running the quantizer, so that these tests
     * say what an emitter does with a given decision rather than what the
     * quantizer happens to decide this week. The end-to-end proof that the two
     * agree is in {@code mw-it}.
     */
    private static QuantizedScore quantized(Score score, GridResolution... perBar) {
        List<BarGrid> grids = new ArrayList<>(perBar.length);
        double startBeat = 0;
        for (int bar = 0; bar < perBar.length; bar++) {
            TimeSignature meter = score.tempoMap().timeSignatureAtBar(bar);
            grids.add(new BarGrid(bar, startBeat, perBar[bar], meter));
            startBeat += meter.quarterBeatsPerBar();
        }
        return new QuantizedScore(score, grids, SwingFeel.STRAIGHT);
    }
}
