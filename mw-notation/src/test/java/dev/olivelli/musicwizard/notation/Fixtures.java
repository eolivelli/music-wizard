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
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.arrange.SwingFeel;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
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
import java.util.Optional;

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

    /**
     * Four bars of chords with no melody, for the chart and its MusicXML twin.
     *
     * <p>Two chords to a bar and one held across a bar line, so that a cell is
     * named and the next is not; a slash chord; an estimated silence; and a
     * flat root, which a spelling carries and a MIDI number does not.
     */
    static Score chordChart() {
        List<Chord> chords = List.of(
                chord("C4", ChordQuality.MAJOR, 0, 1),
                chord("G4", ChordQuality.MAJOR, 1, 2).withBass(PitchSpelling.parse("B3")),
                chord("A4", ChordQuality.MINOR_SEVENTH, 2, 6),
                Chord.noChord(6, 7, Confidence.CERTAIN),
                chord("Db4", ChordQuality.MAJOR, 7, 8));
        return Score.empty(TempoMap.constant(120, TimeSignature.FOUR_FOUR), 8)
                .withChords(new ChordProgression(chords, Confidence.CERTAIN))
                .withMetadata("Chart Practice", "Anonymous");
    }

    private static Chord chord(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.CERTAIN);
    }

    /**
     * The chart with words under it: one sung on a cell's first moment, two
     * joined halves of a word cutting a cell in two, and one sung inside the
     * estimated silence.
     */
    static Score chordsOverLyrics() {
        List<LyricWord> words = List.of(
                LyricWord.ofSeconds("Sing", 0, 1, Confidence.CERTAIN),
                new LyricWord("hap", 1, 1.5, Optional.empty(), Optional.empty(),
                        true, false, Confidence.CERTAIN),
                LyricWord.ofSeconds("py", 1.5, 2, Confidence.CERTAIN),
                LyricWord.ofSeconds("song", 2, 4, Confidence.CERTAIN),
                LyricWord.ofSeconds("now", 6.5, 7, Confidence.CERTAIN));
        return chordChart().withLyrics(new Lyrics(
                List.of(new LyricLine(words, Confidence.CERTAIN)), "en", Confidence.CERTAIN));
    }

    /**
     * A melody entering on beat four, with words under it, the first of them
     * sung at {@code firstWordBeat}.
     *
     * <p>The pair the defect needed and no fixture had (#601): a staff that
     * opens with a pickup and a lyric lane that has to open with it. The first
     * word's beat is the parameter because a word sung inside the pickup and a
     * word sung before the staff enters are the two cases the opening decides
     * between. {@code LeadSheetTest} holds its LilyPond golden and
     * {@code MusicXmlSheetsTest} its MusicXML one, from this one score.
     */
    static QuantizedScore leadSheetWithPickupAndLyrics(double firstWordBeat) {
        TempoMap map = TempoMap.constant(120, TimeSignature.FOUR_FOUR);
        NoteTrack voice = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(
                noteAt(map, 3, 1, "G4"),
                noteAt(map, 4, 4, "C5"),
                noteAt(map, 8, 4, "E5"),
                noteAt(map, 12, 4, "D5")), Confidence.CERTAIN);
        String[] sung = {"one", "two", "three", "four"};
        double[] at = {firstWordBeat, 4, 8, 12};
        double[] until = {4, 8, 12, 16};
        List<LyricWord> words = new ArrayList<>();
        for (int i = 0; i < sung.length; i++) {
            words.add(LyricWord.ofSeconds(sung[i], map.beatsToSeconds(at[i]),
                    map.beatsToSeconds(until[i]), Confidence.CERTAIN));
        }
        Score score = Score.empty(map, 16 / (120 / 60.0))
                .withTrack(voice)
                .withChords(new ChordProgression(List.of(
                        chordAt(map, "C4", ChordQuality.MAJOR, 0, 4),
                        chordAt(map, "F4", ChordQuality.MAJOR, 4, 8),
                        chordAt(map, "G4", ChordQuality.DOMINANT_SEVENTH, 8, 12),
                        chordAt(map, "C4", ChordQuality.MAJOR, 12, 16)),
                        Confidence.of(0.9)))
                .withLyrics(new Lyrics(List.of(new LyricLine(words, Confidence.CERTAIN)),
                        "en", Confidence.CERTAIN));
        return Quantizer.quantize(score);
    }

    /** A note timed by the map, as the lead-sheet fixtures are. */
    private static Note noteAt(TempoMap map, double onsetBeat, double beats, String spelling) {
        PitchSpelling written = PitchSpelling.parse(spelling);
        return Note.ofSeconds(map.beatsToSeconds(onsetBeat),
                        map.beatsToSeconds(onsetBeat + beats) - map.beatsToSeconds(onsetBeat),
                        written.midiPitch(), Confidence.CERTAIN)
                .quantizedTo(onsetBeat, beats)
                .spelledAs(written);
    }

    private static Chord chordAt(TempoMap map, String root, ChordQuality quality,
                                 double fromBeat, double toBeat) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality,
                        map.beatsToSeconds(fromBeat), map.beatsToSeconds(toBeat),
                        Confidence.of(0.9))
                .quantizedTo(fromBeat, toBeat);
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
    static QuantizedScore quantized(Score score, GridResolution... perBar) {
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
