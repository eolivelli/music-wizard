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
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
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

/**
 * What {@link Transposer} does to a score, which is #129.
 *
 * <p>Almost everything here is about spelling rather than about pitch. Moving a
 * MIDI number is arithmetic and cannot go wrong; deciding that the result is E
 * flat rather than D sharp is the decision, and it is the one whose failure a
 * user cannot see -- a chart in the wrong key looks completely correct.
 */
class TransposerTest {

    @Nested
    @DisplayName("the key it lands in")
    class TargetKey {

        @ParameterizedTest(name = "{0} by {1} is {2}")
        @CsvSource({
                // The examples #129 and the module javadoc are written around.
                "C4,  MAJOR,  5,  F4,  MAJOR",
                "C4,  MAJOR,  3,  Eb4, MAJOR",
                "C4,  MAJOR,  4,  E4,  MAJOR",
                "C4,  MAJOR, -2,  Bb3, MAJOR",
                "C4,  MAJOR,  1,  Db4, MAJOR",
                "C4,  MAJOR,  2,  D4,  MAJOR",
                // Minor keys are judged by their own signature, so A minor
                // behaves as the natural key it is rather than as A major.
                "A3,  MINOR,  3,  C4,  MINOR",
                "A3,  MINOR,  5,  D4,  MINOR",
                "E3,  MINOR, -3,  C#3, MINOR",
                // Already accidental-heavy keys are simplified rather than
                // pushed further out: C sharp major up two is E flat, not D
                // sharp with nine sharps.
                "C#4, MAJOR,  2,  Eb4, MAJOR",
                "F#4, MAJOR,  1,  G4,  MAJOR",
                "Gb4, MAJOR, -1,  F4,  MAJOR",
        })
        void landsWhereAMusicianWouldWriteIt(String tonic, Mode mode, int semitones,
                                             String expectedTonic, Mode expectedMode) {
            Score score = scoreInKey(tonic, mode);

            Key moved = Transposer.transpose(score, semitones).primaryKey().orElseThrow();

            assertThat(moved.tonic()).isEqualTo(PitchSpelling.parse(expectedTonic));
            assertThat(moved.mode()).isEqualTo(expectedMode);
        }

        @Test
        @DisplayName("a tritone from a natural key is written flat")
        void aTritoneGoesToTheFlatSide() {
            // Six semitones is the one shift whose two answers are exactly as
            // simple as each other -- G flat major and F sharp major both carry
            // six accidentals -- so something has to break the tie. It goes the
            // same way PitchSpeller's does, which is what keeps a chord symbol
            // and the note heads under it agreeing.
            Key moved = Transposer.transpose(scoreInKey("C4", Mode.MAJOR), 6)
                    .primaryKey().orElseThrow();

            assertThat(moved.tonic()).isEqualTo(PitchSpelling.parse("Gb4"));
            assertThat(moved.keySignatureAccidentals()).isEqualTo(-6);
        }

        @Test
        @DisplayName("the printed signature moves with the tonic, not independently of it")
        void theSignatureFollows() {
            // The whole point of doing this on the line of fifths: one
            // displacement is applied to everything, so the number of flats in
            // the signature and the flats on the chord symbols cannot disagree.
            Score moved = Transposer.transpose(scoreInKey("C4", Mode.MAJOR), 3);

            assertThat(moved.primaryKey().orElseThrow().keySignatureAccidentals())
                    .isEqualTo(-3);
            assertThat(symbolsOf(moved)).containsExactly("Eb", "Bb", "Cm", "Ab");
        }
    }

    @Nested
    @DisplayName("chord symbols")
    class Chords {

        @Test
        @DisplayName("I-V-vi-IV in C up a fourth is F, C, Dm, Bb")
        void theProjectsOwnProgression() {
            Score moved = Transposer.transpose(scoreInKey("C4", Mode.MAJOR), 5);

            assertThat(symbolsOf(moved)).containsExactly("F", "C", "Dm", "Bb");
        }

        @Test
        @DisplayName("keep their quality and their timing")
        void onlyThePitchMoves() {
            Score before = scoreInKey("C4", Mode.MAJOR);
            Score after = Transposer.transpose(before, 7);

            for (int i = 0; i < before.chords().size(); i++) {
                Chord was = before.chords().chords().get(i);
                Chord is = after.chords().chords().get(i);
                assertThat(is.quality()).isEqualTo(was.quality());
                assertThat(is.startSeconds()).isEqualTo(was.startSeconds());
                assertThat(is.endSeconds()).isEqualTo(was.endSeconds());
                assertThat(is.startBeat()).isEqualTo(was.startBeat());
                assertThat(is.endBeat()).isEqualTo(was.endBeat());
                assertThat(is.confidence()).isEqualTo(was.confidence());
            }
            assertThat(after.chords().confidence()).isEqualTo(before.chords().confidence());
        }

        @Test
        @DisplayName("a slash chord's bass moves with its root")
        void theBassMovesToo() {
            // C/E up a fourth is F/A. Leaving the bass behind would print an
            // inversion that is a different instruction to a bass player.
            Score score = scoreOf(List.of(
                    Chord.ofSeconds(PitchSpelling.parse("C4"), ChordQuality.MAJOR,
                                    0, 2, Confidence.CERTAIN)
                            .withBass(PitchSpelling.parse("E3"))),
                    List.of(), List.of());

            Chord moved = Transposer.transpose(score, 5).chords().chords().get(0);

            assertThat(moved.symbol()).isEqualTo("F/A");
            assertThat(moved.bass().orElseThrow()).isEqualTo(PitchSpelling.parse("A3"));
        }

        @Test
        @DisplayName("a no-chord span stays a no-chord span")
        void noChordIsUntouched() {
            Score score = scoreOf(List.of(
                    Chord.noChord(0, 2, Confidence.CERTAIN),
                    Chord.ofSeconds(PitchSpelling.parse("C4"), ChordQuality.MAJOR,
                            2, 4, Confidence.CERTAIN)),
                    List.of(), List.of());

            Score moved = Transposer.transpose(score, 5);

            assertThat(moved.chords().chords().get(0).isNoChord()).isTrue();
            assertThat(symbolsOf(moved)).containsExactly("N.C.", "F");
        }

        @Test
        @DisplayName("with no key detected, the roots say where the piece sits")
        void theRootsStandInForAKey() {
            // The audio path has no key detection yet, so this is the branch the
            // shipped tool actually takes. A progression in F -- F, Bb, C, Dm --
            // moved up a fourth has to reach B flat, not A sharp, and the only
            // evidence for that is the symbols themselves.
            Score score = scoreOf(List.of(
                    chordAt("F4", ChordQuality.MAJOR, 0, 2),
                    chordAt("Bb4", ChordQuality.MAJOR, 2, 4),
                    chordAt("C4", ChordQuality.MAJOR, 4, 6),
                    chordAt("D4", ChordQuality.MINOR, 6, 8)),
                    List.of(), List.of());

            assertThat(score.primaryKey()).isEmpty();
            assertThat(symbolsOf(Transposer.transpose(score, 5)))
                    .containsExactly("Bb", "Eb", "F", "Gm");
        }

        @Test
        @DisplayName("a silent bar does not vote on which key the chart lands in")
        void noChordDoesNotVoteOnTheKey() {
            // Chord.noChord parks a C4 root on a span that has no root at all,
            // so counting it would drag a heavily flat piece back towards
            // natural. This fixture is chosen to sit either side of that: with
            // the rest counted the four symbols come out in flats and without it
            // in sharps, so a rest bar added to a chart would silently respell
            // the whole page.
            //
            // The two answers are the same six-accidental key written two ways,
            // which is honest about how much the estimate can be asked to
            // decide. What it must not do is depend on how much silence the
            // recording has in it.
            List<Chord> withoutRest = List.of(
                    chordAt("Db4", ChordQuality.MAJOR, 2, 4),
                    chordAt("Ab4", ChordQuality.MAJOR, 4, 6),
                    chordAt("Bb4", ChordQuality.MINOR, 6, 8),
                    chordAt("Gb4", ChordQuality.MAJOR, 8, 10));
            List<Chord> withRest = new ArrayList<>(withoutRest);
            withRest.add(0, Chord.noChord(0, 2, Confidence.CERTAIN));

            List<String> rested = symbolsOf(Transposer.transpose(
                    scoreOf(withRest, List.of(), List.of()), 5));
            List<String> unrested = symbolsOf(Transposer.transpose(
                    scoreOf(withoutRest, List.of(), List.of()), 5));

            assertThat(rested.subList(1, rested.size())).isEqualTo(unrested);
            assertThat(unrested).containsExactly("F#", "C#", "D#m", "B");
        }
    }

    @Nested
    @DisplayName("notes")
    class Notes {

        @Test
        @DisplayName("move in pitch and are respelled to match the new key")
        void pitchAndSpellingMoveTogether() {
            // E natural in C major, up three semitones, is G natural in E flat
            // major. Spelled from a pitch-class table it would come out as G
            // either way; the case that separates the two is the B flat below.
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(key("C4", Mode.MAJOR)),
                    List.of(spelled(0.0, 60, "C4"), spelled(1.0, 64, "E4"),
                            spelled(2.0, 71, "B4")));

            List<Note> moved = Transposer.transpose(score, 3).tracks().get(0).notes();

            assertThat(moved).extracting(Note::midiPitch).containsExactly(63, 67, 74);
            assertThat(moved).extracting(n -> n.spelling().orElseThrow().displayName())
                    .containsExactly("Eb4", "G4", "D5");
        }

        @Test
        @DisplayName("an unspelled note stays unspelled")
        void nothingIsInvented() {
            // Spelling is derived, and the pipeline derives it later, from the
            // transposed harmony. Guessing one here would pre-empt PitchSpeller
            // with a worse answer -- it has no chord to consult and this does.
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(Note.ofSeconds(0.0, 1.0, 60, Confidence.CERTAIN)));

            Note moved = Transposer.transpose(score, 5).tracks().get(0).notes().get(0);

            assertThat(moved.midiPitch()).isEqualTo(65);
            assertThat(moved.spelling()).isEmpty();
        }

        @Test
        @DisplayName("keep their timing, velocity and confidence")
        void onlyThePitchMoves() {
            Note before = new Note(1.5, 0.75, 60, 42, Optional.of(PitchSpelling.parse("C4")),
                    Optional.of(3.0), Optional.of(1.5), Confidence.of(0.4));
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(before));

            Note after = Transposer.transpose(score, 5).tracks().get(0).notes().get(0);

            assertThat(after.onsetSeconds()).isEqualTo(before.onsetSeconds());
            assertThat(after.durationSeconds()).isEqualTo(before.durationSeconds());
            assertThat(after.velocity()).isEqualTo(before.velocity());
            assertThat(after.onsetBeat()).isEqualTo(before.onsetBeat());
            assertThat(after.durationBeats()).isEqualTo(before.durationBeats());
            assertThat(after.confidence()).isEqualTo(before.confidence());
        }

        @Test
        @DisplayName("a note the shift would push past MIDI 127 fails with the part named")
        void anUnmovableNoteIsRefusedByName() {
            // #57 chose this: Note.transposedBy refuses rather than guessing,
            // and the choice of what to do belongs to the caller. Leaving that
            // one note where it was would produce a page correct everywhere
            // except one note, which nobody would notice -- so the run stops,
            // and it names the part and the moment so the user can find it.
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(spelled(2.5, 126, "F#9")));

            assertThatThrownBy(() -> Transposer.transpose(score, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Voice")
                    .hasMessageContaining("126")
                    .hasMessageContaining("2.500s")
                    .hasMessageContaining("MIDI range");
        }
    }

    @Nested
    @DisplayName("the shift itself")
    class TheShift {

        @Test
        @DisplayName("zero returns the very same score")
        void zeroIsExactlyNothing() {
            // Identity, not equality. A no-op that ran the machinery would
            // re-derive every spelling from the estimated signature and could
            // change one, which is a page the user never asked to have altered.
            Score score = scoreInKey("C4", Mode.MAJOR);

            assertThat(Transposer.transpose(score, 0)).isSameAs(score);
        }

        @ParameterizedTest(name = "by {0} semitones")
        @CsvSource({"12", "-12", "24", "-24"})
        @DisplayName("a whole octave changes nothing but the octave")
        void anOctaveKeepsEverySpelling(int semitones) {
            // C sharp major up an octave must stay C sharp major. Letting the
            // search run would notice that D flat major is simpler and respell a
            // page the user only asked to move -- a correction nobody requested,
            // which on this project counts as wrong output.
            Score score = scoreOf(List.of(chordAt("C#4", ChordQuality.MAJOR, 0, 4),
                            chordAt("G#4", ChordQuality.MAJOR, 4, 8)),
                    List.of(key("C#4", Mode.MAJOR)),
                    List.of(spelled(0.0, 61, "C#4")));

            Score moved = Transposer.transpose(score, semitones);

            assertThat(moved.primaryKey().orElseThrow().tonic().letter())
                    .isEqualTo(NoteLetter.C);
            assertThat(moved.primaryKey().orElseThrow().tonic().accidental())
                    .isEqualTo(Accidental.SHARP);
            assertThat(symbolsOf(moved)).containsExactly("C#", "G#");
            Note note = moved.tracks().get(0).notes().get(0);
            assertThat(note.midiPitch()).isEqualTo(61 + semitones);
            assertThat(note.spelling().orElseThrow().letter()).isEqualTo(NoteLetter.C);
            assertThat(note.spelling().orElseThrow().accidental()).isEqualTo(Accidental.SHARP);
        }

        @ParameterizedTest
        @CsvSource({"25", "-25", "1000", "-1000"})
        @DisplayName("beyond two octaves is refused rather than wrapped")
        void tooFarIsRefused(int semitones) {
            Score score = scoreInKey("C4", Mode.MAJOR);

            assertThatThrownBy(() -> Transposer.transpose(score, semitones))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("-24..24");
        }

        @Test
        @DisplayName("leaves the score it was given alone")
        void theSourceIsNotMutated() {
            Score score = scoreInKey("C4", Mode.MAJOR);

            Transposer.transpose(score, 5);

            assertThat(symbolsOf(score)).containsExactly("C", "G", "Am", "F");
            assertThat(score.primaryKey().orElseThrow().tonic())
                    .isEqualTo(PitchSpelling.parse("C4"));
        }

        @Test
        @DisplayName("does not touch timing, lyrics or the tempo map")
        void everythingThatIsNotAPitchSurvives() {
            Score score = new Score(Optional.of("Song"), Optional.of("Someone"),
                    TempoMap.constant(96, TimeSignature.FOUR_FOUR), Optional.empty(),
                    List.of(key("C4", Mode.MAJOR)), List.of(),
                    List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice",
                            List.of(spelled(0.0, 60, "C4")), Confidence.CERTAIN)),
                    new ChordProgression(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                            Confidence.of(0.8)),
                    new Lyrics(List.of(new LyricLine(
                            List.of(new LyricWord("hello", 0.0, 0.5, Optional.empty(),
                                    Optional.empty(), false, false, Confidence.CERTAIN)),
                            Confidence.CERTAIN)), "en", Confidence.CERTAIN),
                    8.0);

            Score moved = Transposer.transpose(score, 4);

            assertThat(moved.title()).isEqualTo(score.title());
            assertThat(moved.artist()).isEqualTo(score.artist());
            assertThat(moved.tempoMap()).isEqualTo(score.tempoMap());
            assertThat(moved.lyrics()).isEqualTo(score.lyrics());
            assertThat(moved.durationSeconds()).isEqualTo(score.durationSeconds());
            assertThat(moved.tracks().get(0).role()).isEqualTo(PartRole.LEAD_VOCAL);
            assertThat(moved.tracks().get(0).name()).isEqualTo("Voice");
        }

        @Test
        @DisplayName("an accidental no staff can carry falls back to a plain enharmonic")
        void anUnprintableAccidentalDegrades() {
            // Reachable, and only just: the source spelling has to be at a
            // double accidental already and the key has to point the other way.
            // A B double sharp in a piece detected as C flat major, moved up a
            // semitone, wants a triple sharp -- and the target key is C major,
            // where the note is a plain D. Refusing to transpose over it would
            // be worse than printing the enharmonic, since every other note on
            // the page is fine.
            Score score = scoreOf(List.of(chordAt("Cb4", ChordQuality.MAJOR, 0, 8)),
                    List.of(key("Cb4", Mode.MAJOR)),
                    List.of(spelled(0.0, 73, "B##4")));

            Score moved = Transposer.transpose(score, 1);

            assertThat(moved.primaryKey().orElseThrow().tonic())
                    .isEqualTo(PitchSpelling.parse("C4"));
            Note note = moved.tracks().get(0).notes().get(0);
            assertThat(note.midiPitch()).isEqualTo(74);
            assertThat(note.spelling().orElseThrow()).isEqualTo(PitchSpelling.parse("D5"));
        }

        @Test
        @DisplayName("and it degrades to a flat one when the music is going that way")
        void anUnprintableAccidentalDegradesFlatToo() {
            // The mirror image, and it needs its own case because the fallback
            // has to choose a side: a piece heading into flats that hits a triple
            // flat must not come out spelled in sharps for that one note. F
            // double flat in C sharp major, down a semitone, wants one; C major
            // writes it D.
            Score score = scoreOf(List.of(chordAt("C#4", ChordQuality.MAJOR, 0, 8)),
                    List.of(key("C#4", Mode.MAJOR)),
                    List.of(spelled(0.0, 63, "Fbb4")));

            Score moved = Transposer.transpose(score, -1);

            assertThat(moved.primaryKey().orElseThrow().tonic())
                    .isEqualTo(PitchSpelling.parse("C4"));
            Note note = moved.tracks().get(0).notes().get(0);
            assertThat(note.midiPitch()).isEqualTo(62);
            assertThat(note.spelling().orElseThrow()).isEqualTo(PitchSpelling.parse("D4"));
        }

        @Test
        @DisplayName("every spelling it produces is one that can be written down and read back")
        void nothingUnwritableEscapes() {
            // The octave is recovered from the sounding pitch, and C flat sounds
            // in the octave below the one it is written in -- so a spelling can
            // land outside the band PitchSpelling.parse accepts if the recovery
            // is wrong. Swept rather than spot-checked, because the boundary is
            // at the ends of the range where no musical fixture goes.
            for (int semitones = -24; semitones <= 24; semitones++) {
                if (semitones == 0) {
                    continue;
                }
                Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                        List.of(key("C4", Mode.MAJOR)), pitchesFrom(24, 96));
                Score moved = Transposer.transpose(score, semitones);
                for (Note note : moved.tracks().get(0).notes()) {
                    PitchSpelling spelling = note.spelling().orElseThrow();
                    assertThat(spelling.midiPitch())
                            .as("shift %d spelled MIDI %d as %s",
                                    semitones, note.midiPitch(), spelling.displayName())
                            .isEqualTo(note.midiPitch());
                    assertThat(PitchSpelling.parse(spelling.displayName()))
                            .as("shift %d produced an unparseable %s",
                                    semitones, spelling.displayName())
                            .isEqualTo(spelling);
                    assertThat(Math.abs(spelling.accidental().alteration()))
                            .as("shift %d needed %s", semitones, spelling.displayName())
                            .isLessThanOrEqualTo(2);
                }
                for (Chord chord : moved.chords().chords()) {
                    assertThat(chord.root().midiPitch() - 12 * 4)
                            .as("shift %d moved a root out of a writable octave", semitones)
                            .isBetween(-24, 127);
                }
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** I-V-vi-IV in the given key, spelled as that key spells it. */
    private static Score scoreInKey(String tonic, Mode mode) {
        Key inKey = key(tonic, mode);
        int root = inKey.tonic().midiPitch();
        List<Chord> chords = new ArrayList<>();
        int[] degrees = mode == Mode.MINOR
                ? new int[] {0, 7, 3, 8}
                : new int[] {0, 7, 9, 5};
        ChordQuality[] qualities = mode == Mode.MINOR
                ? new ChordQuality[] {ChordQuality.MINOR, ChordQuality.MINOR,
                        ChordQuality.MAJOR, ChordQuality.MAJOR}
                : new ChordQuality[] {ChordQuality.MAJOR, ChordQuality.MAJOR,
                        ChordQuality.MINOR, ChordQuality.MAJOR};
        for (int i = 0; i < degrees.length; i++) {
            chords.add(new Chord(PitchSpeller.spellFromKey(root + degrees[i], inKey),
                    qualities[i], Optional.empty(), i * 2.0, i * 2.0 + 2.0,
                    Optional.empty(), Optional.empty(), Confidence.of(0.8)));
        }
        return scoreOf(chords, List.of(inKey), List.of());
    }

    private static Score scoreOf(List<Chord> chords, List<Key> keys, List<Note> notes) {
        return new Score(Optional.empty(), Optional.empty(),
                TempoMap.constant(120, TimeSignature.FOUR_FOUR), Optional.empty(),
                keys, List.of(),
                notes.isEmpty()
                        ? List.of()
                        : List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes,
                                Confidence.CERTAIN)),
                new ChordProgression(chords, Confidence.of(0.8)), Lyrics.empty(), 16.0);
    }

    /** One note per MIDI pitch in a band, spelled the way C major spells it. */
    private static List<Note> pitchesFrom(int lowest, int highest) {
        List<Note> notes = new ArrayList<>();
        double at = 0.0;
        for (int pitch = lowest; pitch <= highest; pitch++) {
            notes.add(new Note(at, 0.25, pitch, Note.DEFAULT_VELOCITY,
                    Optional.of(PitchSpeller.onLineOfFifths(pitch, 2.0)),
                    Optional.empty(), Optional.empty(), Confidence.CERTAIN));
            at += 0.5;
        }
        return notes;
    }

    private static Note spelled(double at, int midiPitch, String spelling) {
        return new Note(at, 0.5, midiPitch, Note.DEFAULT_VELOCITY,
                Optional.of(PitchSpelling.parse(spelling)),
                Optional.empty(), Optional.empty(), Confidence.CERTAIN);
    }

    private static Key key(String tonic, Mode mode) {
        return Key.ofSeconds(PitchSpelling.parse(tonic), mode, 0, 16, Confidence.CERTAIN);
    }

    private static Chord chordAt(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.CERTAIN);
    }

    private static List<String> symbolsOf(Score score) {
        return score.chords().chords().stream().map(Chord::symbol).toList();
    }
}
