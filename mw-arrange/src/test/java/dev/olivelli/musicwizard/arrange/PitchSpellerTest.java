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

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PitchSpellerTest {

    private static final Key D_FLAT_MAJOR = key("Db4", Mode.MAJOR);
    private static final Key C_MAJOR = key("C4", Mode.MAJOR);
    private static final Key A_MINOR = key("A3", Mode.MINOR);
    private static final Key E_MINOR = key("E3", Mode.MINOR);
    private static final Key G_FLAT_MAJOR = key("Gb3", Mode.MAJOR);

    @Nested
    @DisplayName("the sounding chord decides first")
    class FromTheChord {

        @Test
        @DisplayName("the fifth of D flat is A flat, not G sharp")
        void theIssuesOwnExample() {
            Chord dFlat = chord("Db4", ChordQuality.MAJOR);
            assertThat(PitchSpeller.spell(68, Optional.of(dFlat), Optional.of(D_FLAT_MAJOR)))
                    .isEqualTo(PitchSpelling.parse("Ab4"));
            assertThat(PitchSpeller.spell(65, Optional.of(dFlat), Optional.of(D_FLAT_MAJOR)))
                    .isEqualTo(PitchSpelling.parse("F4"));
        }

        @Test
        @DisplayName("a diminished seventh spells its seventh as a seventh")
        void diminishedSeventhKeepsItsLetters() {
            Chord bDim7 = chord("B3", ChordQuality.DIMINISHED_SEVENTH);
            assertThat(spellAll(bDim7, 59, 62, 65, 68))
                    .containsExactly("B3", "D4", "F4", "Ab4");
        }

        @Test
        @DisplayName("a sixth chord and a diminished seventh disagree about nine semitones")
        void theSameIntervalIsSpelledByQualityNotBySemitones() {
            assertThat(spellAll(chord("C4", ChordQuality.SIXTH), 69)).containsExactly("A4");
            assertThat(spellAll(chord("C4", ChordQuality.DIMINISHED_SEVENTH), 69))
                    .containsExactly("Bbb4");
        }

        @ParameterizedTest
        @CsvSource({
                "C4, AUGMENTED,          68, G#4",
                "C4, SUSPENDED_FOURTH,   65, F4",
                "C4, SUSPENDED_SECOND,   62, D4",
                "C4, MINOR,              63, Eb4",
                "C4, HALF_DIMINISHED_SEVENTH, 70, Bb4",
                "F#4, MAJOR,             70, A#4",
                "F#4, MINOR,             69, A4",
                "Eb4, DOMINANT_SEVENTH,  73, Db5",
                "Ab4, MAJOR_SEVENTH,     79, G5",
        })
        void spellsEveryQualityByItsDiatonicSteps(String root, ChordQuality quality,
                                                  int midiPitch, String expected) {
            assertThat(PitchSpeller.spell(midiPitch, Optional.of(chord(root, quality)),
                    Optional.of(C_MAJOR)))
                    .isEqualTo(PitchSpelling.parse(expected));
        }

        @Test
        @DisplayName("a slash chord's written bass is honoured rather than re-derived")
        void slashChordBass() {
            Chord cOverE = chord("C4", ChordQuality.MAJOR)
                    .withBass(new PitchSpelling(
                            dev.olivelli.musicwizard.core.model.NoteLetter.F,
                            Accidental.FLAT, 3));
            assertThat(PitchSpeller.spell(52, Optional.of(cOverE), Optional.of(C_MAJOR)))
                    .isEqualTo(PitchSpelling.parse("Fb3"));
        }

        @Test
        @DisplayName("a pitch outside the chord falls through to the key")
        void nonChordTonesUseTheKey() {
            Chord dFlat = chord("Db4", ChordQuality.MAJOR);
            // B flat is not in a D flat triad, but the key still says flat.
            assertThat(PitchSpeller.spell(70, Optional.of(dFlat), Optional.of(D_FLAT_MAJOR)))
                    .isEqualTo(PitchSpelling.parse("Bb4"));
        }

        @Test
        @DisplayName("a chord tone needing a triple flat falls back rather than throwing")
        void anUnprintableChordToneFallsBack() {
            Chord absurd = new Chord(
                    new PitchSpelling(dev.olivelli.musicwizard.core.model.NoteLetter.F,
                            Accidental.DOUBLE_FLAT, 4),
                    ChordQuality.DIMINISHED_SEVENTH, Optional.empty(),
                    0, 1, Optional.empty(), Optional.empty(), Confidence.CERTAIN);
            // The diminished seventh of F double flat is E triple flat.
            PitchSpelling spelled = PitchSpeller.spell(60, Optional.of(absurd),
                    Optional.of(C_MAJOR));
            assertThat(spelled.midiPitch()).isEqualTo(60);
            assertThat(spelled.accidental().alteration()).isBetween(-2, 2);
        }

        @Test
        @DisplayName("a no-chord span is no help, so the key answers")
        void noChordDefersToTheKey() {
            Chord silence = Chord.noChord(0, 1, Confidence.CERTAIN);
            assertThat(PitchSpeller.spell(68, Optional.of(silence), Optional.of(D_FLAT_MAJOR)))
                    .isEqualTo(PitchSpelling.parse("Ab4"));
        }
    }

    @Nested
    @DisplayName("failing that, distance along the line of fifths from the local tonic")
    class FromTheKey {

        @Test
        @DisplayName("a chromatic line in a flat key spells flat")
        void chromaticInDFlat() {
            List<PitchSpelling> octave = new ArrayList<>();
            for (int pitch = 60; pitch < 72; pitch++) {
                octave.add(PitchSpeller.spellFromKey(pitch, D_FLAT_MAJOR));
            }
            assertThat(octave).noneMatch(s -> s.accidental() == Accidental.SHARP
                    || s.accidental() == Accidental.DOUBLE_SHARP);
            assertThat(octave.stream().map(PitchSpelling::displayName))
                    .containsExactly("C4", "Db4", "D4", "Eb4", "Fb4", "F4",
                            "Gb4", "G4", "Ab4", "A4", "Bb4", "Cb5");
        }

        @Test
        @DisplayName("the same line in a sharp-leaning key spells sharp where the key does")
        void chromaticInEMinor() {
            assertThat(PitchSpeller.spellFromKey(63, E_MINOR))
                    .isEqualTo(PitchSpelling.parse("D#4"));
            assertThat(PitchSpeller.spellFromKey(66, E_MINOR))
                    .isEqualTo(PitchSpelling.parse("F#4"));
        }

        @Test
        @DisplayName("a minor key spells its leading tone as a leading tone")
        void minorKeysAreCentredSharpOfTheirSignature() {
            // A minor and C major share a signature. Only the mode can tell G
            // sharp from A flat, and it has to, because G sharp is the leading
            // tone of A minor and A flat is not in it at all.
            assertThat(PitchSpeller.spellFromKey(68, A_MINOR))
                    .isEqualTo(PitchSpelling.parse("G#4"));
            assertThat(PitchSpeller.spellFromKey(68, C_MAJOR))
                    .isEqualTo(PitchSpelling.parse("Ab4"));
        }

        @Test
        @DisplayName("an exact tie prefers fewer accidentals, then flats")
        void tieBreaking() {
            // A flat and G sharp are equidistant from C major's centre.
            assertThat(PitchSpeller.spellFromKey(68, C_MAJOR).accidental())
                    .isEqualTo(Accidental.FLAT);
            // A natural and B double flat are equidistant from D flat's centre,
            // and printing a plain A as a double flat would be absurd.
            assertThat(PitchSpeller.spellFromKey(69, D_FLAT_MAJOR))
                    .isEqualTo(PitchSpelling.parse("A4"));
        }

        @Test
        @DisplayName("an enharmonic spelling still sounds as the pitch it was asked about")
        void everySpellingSoundsAsItsPitch() {
            for (Key key : List.of(C_MAJOR, D_FLAT_MAJOR, A_MINOR, E_MINOR, G_FLAT_MAJOR)) {
                for (int pitch = 0; pitch <= 127; pitch++) {
                    assertThat(PitchSpeller.spellFromKey(pitch, key).midiPitch())
                            .describedAs("%s in %s", pitch, key)
                            .isEqualTo(pitch);
                }
            }
        }

        @Test
        @DisplayName("C flat lands in the octave above the B it sounds as")
        void octavesFollowTheLetterNotThePitch() {
            PitchSpelling cFlat = PitchSpeller.spellFromKey(59, G_FLAT_MAJOR);
            assertThat(cFlat.displayName()).isEqualTo("Cb4");
            assertThat(cFlat.midiPitch()).isEqualTo(59);
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> PitchSpeller.spellFromKey(60, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> PitchSpeller.spell(60, null, Optional.of(C_MAJOR)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> PitchSpeller.spell(60, Optional.empty(), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> PitchSpeller.spell((Score) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("with neither chord nor key, C major answers")
        void theDefaultCentreIsCMajor() {
            assertThat(PitchSpeller.spell(66, Optional.empty(), Optional.empty()))
                    .isEqualTo(PitchSpelling.parse("F#4"));
            assertThat(PitchSpeller.spell(63, Optional.empty(), Optional.empty()))
                    .isEqualTo(PitchSpelling.parse("Eb4"));
        }
    }

    @Nested
    @DisplayName("spelling a whole score")
    class WholeScore {

        @Test
        @DisplayName("every note comes back spelled")
        void spellsEveryTrack() {
            Score score = scoreOf(List.of(60, 63, 66, 68), progression(), List.of(D_FLAT_MAJOR));
            assertThat(score.tracks().get(0).notes()).allMatch(n -> n.spelling().isEmpty());

            Score spelled = PitchSpeller.spell(score);

            assertThat(spelled.tracks().get(0).notes()).allMatch(n -> n.spelling().isPresent());
            assertThat(spelled.tracks().get(0).notes())
                    .allSatisfy(n -> assertThat(n.spelling().orElseThrow().midiPitch())
                            .isEqualTo(n.midiPitch()));
        }

        @Test
        @DisplayName("an earlier guess at a spelling is replaced, not kept")
        void replacesAPreviousSpelling() {
            Score score = scoreOf(List.of(68), progression(), List.of(D_FLAT_MAJOR));
            Note wrong = score.tracks().get(0).notes().get(0)
                    .spelledAs(PitchSpelling.parse("G#4"));
            Score withWrong = score.withTrack(
                    score.tracks().get(0).withNotes(List.of(wrong)));

            Score spelled = PitchSpeller.spell(withWrong);

            assertThat(spelled.tracks().get(0).notes().get(0).spelling())
                    .contains(PitchSpelling.parse("Ab4"));
        }

        @Test
        @DisplayName("with no key, the chord symbols say which end of the line of fifths we are on")
        void fallsBackToTheChordRootsWhenNoKeyIsKnown() {
            // A flat-side progression and no key detection. A note that no chord
            // covers still has to come out flat.
            ChordProgression flatSide = new ChordProgression(List.of(
                    chordAt("Db4", ChordQuality.MAJOR, 0, 1),
                    chordAt("Ab4", ChordQuality.MAJOR, 1, 2),
                    chordAt("Bb3", ChordQuality.MINOR, 2, 3),
                    chordAt("Gb4", ChordQuality.MAJOR, 3, 4)), Confidence.CERTAIN);
            // The note sits at 4.5 s, after the last chord ends.
            Score score = new Score(Optional.empty(), Optional.empty(),
                    TempoMap.constant(120, TimeSignature.FOUR_FOUR), Optional.empty(),
                    List.of(), List.of(),
                    List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                            List.of(Note.ofSeconds(4.5, 0.5, 68, Confidence.CERTAIN)),
                            Confidence.CERTAIN)),
                    flatSide, Lyrics.empty(), 10);

            Score spelled = PitchSpeller.spell(score);

            assertThat(spelled.tracks().get(0).notes().get(0).spelling())
                    .contains(PitchSpelling.parse("Ab4"));
        }

        @Test
        @DisplayName("once both are quantized, the chord is looked up on the beat axis")
        void quantizedLookupsUseBeatsRatherThanSeconds() {
            // The two axes are made to disagree on purpose: in seconds the note
            // sits under the first chord, in beats under the second. The
            // engraved accidental has to agree with the engraved chord symbol,
            // and only the beat axis is what gets engraved.
            Chord first = chordAt("C4", ChordQuality.MAJOR, 0, 1).quantizedTo(0, 4);
            Chord second = chordAt("Db4", ChordQuality.MAJOR, 1, 2).quantizedTo(4, 8);
            Note note = Note.ofSeconds(0.9, 0.5, 68, Confidence.CERTAIN).quantizedTo(5.0, 1.0);

            Score score = new Score(Optional.empty(), Optional.empty(),
                    TempoMap.constant(120, TimeSignature.FOUR_FOUR), Optional.empty(),
                    List.of(), List.of(),
                    List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", List.of(note),
                            Confidence.CERTAIN)),
                    new ChordProgression(List.of(first, second), Confidence.CERTAIN),
                    Lyrics.empty(), 10);

            Score spelled = PitchSpeller.spell(score);

            // Under C major, 68 would be A flat only by tie-break; under D flat
            // major it is the chord's own fifth. The distinguishing case is 61:
            // C sharp under C major, D flat under the D flat chord.
            assertThat(spelled.tracks().get(0).notes().get(0).spelling())
                    .contains(PitchSpelling.parse("Ab4"));

            Note cSharp = Note.ofSeconds(0.9, 0.5, 61, Confidence.CERTAIN).quantizedTo(5.0, 1.0);
            Score withCSharp = score.withTrack(score.tracks().get(0).withNotes(List.of(cSharp)));
            assertThat(PitchSpeller.spell(withCSharp).tracks().get(0).notes().get(0).spelling())
                    .contains(PitchSpelling.parse("Db4"));
        }

        @Test
        @DisplayName("the chord roots understate the centre, and the offset is what corrects it")
        void theChordRootFallbackIsOffsetFromTheRootsThemselves() {
            // G, D, A and C average to 1.5 on the line of fifths, but the notes
            // of a key with those chords centre a fifth or so sharper. G sharp
            // and A flat straddle exactly that difference: from the raw root
            // average the flat is nearer, from the corrected centre the sharp
            // is, and the sharp is what a player reading a I-V-ii-IV in G wants
            // when the dominant of A comes round.
            ChordProgression sharpSide = new ChordProgression(List.of(
                    chordAt("G3", ChordQuality.MAJOR, 0, 1),
                    chordAt("D4", ChordQuality.MAJOR, 1, 2),
                    chordAt("A3", ChordQuality.MAJOR, 2, 3),
                    chordAt("C4", ChordQuality.MAJOR, 3, 4)), Confidence.CERTAIN);
            Score score = new Score(Optional.empty(), Optional.empty(),
                    TempoMap.constant(120, TimeSignature.FOUR_FOUR), Optional.empty(),
                    List.of(), List.of(),
                    List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                            List.of(Note.ofSeconds(4.5, 0.5, 68, Confidence.CERTAIN)),
                            Confidence.CERTAIN)),
                    sharpSide, Lyrics.empty(), 10);

            assertThat(PitchSpeller.spell(score).tracks().get(0).notes().get(0).spelling())
                    .contains(PitchSpelling.parse("G#4"));
        }

        @Test
        @DisplayName("an enharmonic that would fall out of the writable octaves is passed over")
        void spellingsStayInAWritableOctave() {
            // In a five-sharp key, MIDI 0 is nearer B sharp than C on the line
            // of fifths, and a B sharp sounding as MIDI 0 sits in octave -2 --
            // a legal record that PitchSpelling.parse refuses, so it could not
            // survive a config value or an advisor's reply.
            Key bMajor = key("B3", Mode.MAJOR);
            for (int pitch : new int[] {0, 1, 126, 127}) {
                PitchSpelling spelled = PitchSpeller.spellFromKey(pitch, bMajor);
                assertThat(spelled.midiPitch()).isEqualTo(pitch);
                assertThat(PitchSpelling.parse(spelled.displayName())).isEqualTo(spelled);
            }
        }

        @Test
        @DisplayName("every spelling of every pitch in every key survives a round trip through text")
        void everySpellingIsWritable() {
            for (int accidentals = -7; accidentals <= 7; accidentals++) {
                for (Mode mode : Mode.values()) {
                    Key any = keyWithSignature(accidentals, mode);
                    for (int pitch = 0; pitch <= 127; pitch++) {
                        PitchSpelling spelled = PitchSpeller.spellFromKey(pitch, any);
                        assertThat(PitchSpelling.parse(spelled.displayName()))
                                .describedAs("pitch %s in %s", pitch, any)
                                .isEqualTo(spelled);
                    }
                }
            }
        }

        @Test
        @DisplayName("a part built elsewhere can be spelled against the score's harmony")
        void spellsATrackAgainstAnotherScore() {
            Score harmony = scoreOf(List.of(60), progression(), List.of(D_FLAT_MAJOR));
            NoteTrack piano = new NoteTrack(PartRole.PIANO_RIGHT_HAND, "Piano",
                    List.of(Note.ofSeconds(0.1, 0.5, 68, Confidence.CERTAIN)), Confidence.CERTAIN);

            NoteTrack spelled = PitchSpeller.spell(piano, harmony);

            assertThat(spelled.role()).isEqualTo(PartRole.PIANO_RIGHT_HAND);
            assertThat(spelled.notes().get(0).spelling()).contains(PitchSpelling.parse("Ab4"));
        }

        @Test
        void anEmptyScoreIsUnchanged() {
            Score empty = Score.empty(TempoMap.constant(120), 10);
            assertThat(PitchSpeller.spell(empty).tracks()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A key with the given number of sharps (positive) or flats (negative). */
    private static Key keyWithSignature(int accidentals, Mode mode) {
        for (dev.olivelli.musicwizard.core.model.NoteLetter letter
                : dev.olivelli.musicwizard.core.model.NoteLetter.values()) {
            for (Accidental accidental : Accidental.values()) {
                Key candidate = Key.ofSeconds(new PitchSpelling(letter, accidental, 4), mode,
                        0, 1000, Confidence.CERTAIN);
                if (candidate.keySignatureAccidentals() == accidentals) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("no " + mode + " key has " + accidentals);
    }

    private static Key key(String tonic, Mode mode) {
        return Key.ofSeconds(PitchSpelling.parse(tonic), mode, 0, 1000, Confidence.CERTAIN);
    }

    private static Chord chord(String root, ChordQuality quality) {
        return chordAt(root, quality, 0, 1000);
    }

    private static Chord chordAt(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.CERTAIN);
    }

    private static List<String> spellAll(Chord chord, int... pitches) {
        List<String> spelled = new ArrayList<>();
        for (int pitch : pitches) {
            spelled.add(PitchSpeller.spell(pitch, Optional.of(chord), Optional.of(C_MAJOR))
                    .displayName());
        }
        return spelled;
    }

    private static ChordProgression progression() {
        return new ChordProgression(List.of(chordAt("Db4", ChordQuality.MAJOR, 0, 1000)),
                Confidence.CERTAIN);
    }

    private static Score scoreOf(List<Integer> pitches, ChordProgression chords, List<Key> keys) {
        List<Note> notes = new ArrayList<>();
        double at = 0.1;
        for (int pitch : pitches) {
            notes.add(Note.ofSeconds(at, 0.4, pitch, Confidence.CERTAIN));
            at += 0.5;
        }
        return new Score(Optional.empty(), Optional.empty(),
                TempoMap.constant(120, TimeSignature.FOUR_FOUR), Optional.empty(),
                keys, List.of(),
                List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN)),
                chords, Lyrics.empty(), 1000);
    }
}
