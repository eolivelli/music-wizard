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

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a score comes out as when it is moved by an interval, which is #129.
 *
 * <p>The chart assertions transpose and then {@link ChordSpeller#respell} it, in
 * that order, because that is what {@code render} does: this stage decides the
 * interval and the key, and the speller decides how the harmony reaching the page
 * is written. Asserting the symbols without the speller would pin an
 * intermediate nothing prints.
 */
class TransposerTest {

    @Nested
    @DisplayName("the key the piece lands in")
    class TheTargetKey {

        @Test
        @DisplayName("C major up three is E flat major, spelled with flats")
        void theIssuesOwnExample() {
            // The whole point: the arithmetic answer is D sharp major, and a
            // page headed D sharp major with an A sharp minor in it is one no
            // musician would accept.
            Score moved = chart(charted(transpose(cMajorPop(), 3)));

            assertThat(keyName(moved)).isEqualTo("Eb major");
            assertThat(symbols(moved)).containsExactly("Eb", "Bb", "Cm", "Ab");
        }

        @Test
        @DisplayName("a minor key stays minor and its tonic moves with it")
        void aMinorKeyMoves() {
            Score moved = charted(transpose(aMinorPop(), 3));

            assertThat(keyName(moved)).isEqualTo("C minor");
            assertThat(moved.primaryKey().orElseThrow().keySignatureAccidentals()).isEqualTo(-3);
        }

        @Test
        @DisplayName("the target is the cheapest signature, so up one is D flat and not C sharp")
        void theCheapestOfTheTwoSpellings() {
            assertThat(keyName(charted(transpose(cMajorPop(), 1)))).isEqualTo("Db major");
            assertThat(keyName(charted(transpose(cMajorPop(), -2)))).isEqualTo("Bb major");
            assertThat(keyName(charted(transpose(cMajorPop(), 8)))).isEqualTo("Ab major");
        }

        @Test
        @DisplayName("six either way is a tie, and it goes flat")
        void sixAccidentalsGoesFlat() {
            // F sharp major and G flat major are the same music at six
            // accidentals each; every other tie in the spelling layer goes flat
            // and so does this one.
            assertThat(keyName(charted(transpose(cMajorPop(), 6)))).isEqualTo("Gb major");
            assertThat(keyName(charted(transpose(cMajorPop(), -6)))).isEqualTo("Gb major");
        }

        @Test
        @DisplayName("a source key written from the far side of the circle is normalised as it moves")
        void anExpensivelyWrittenSourceKeyLandsCheap() {
            // F sharp major is six sharps and G flat major is six flats; the two
            // are the same music, and the tie is why only one of them can be
            // what a transposed score comes out written as. Up one from either
            // is G major, which is what a reader wants and is not a claim that
            // the source was wrong.
            assertThat(keyName(charted(transpose(pop("F#4"), 1)))).isEqualTo("G major");
            assertThat(keyName(charted(transpose(pop("Gb4"), 1)))).isEqualTo("G major");
        }

        @Test
        @DisplayName("no shift of any key needs more than six accidentals")
        void nothingLandsPastSixAccidentals() {
            for (String tonic : TONICS) {
                for (int semitones = -Transposer.MAX_SEMITONES;
                        semitones <= Transposer.MAX_SEMITONES; semitones++) {
                    Score moved = charted(transpose(pop(tonic), semitones));
                    assertThat(Math.abs(moved.primaryKey().orElseThrow()
                            .keySignatureAccidentals()))
                            .as("%s by %+d", tonic, semitones)
                            .isLessThanOrEqualTo(6);
                }
            }
        }
    }

    @Nested
    @DisplayName("the chart the user reads")
    class TheChart {

        @Test
        @DisplayName("no chord symbol anywhere comes out with a double accidental")
        void noDoubleAccidentalsAnywhere() {
            for (String tonic : TONICS) {
                for (int semitones = -Transposer.MAX_SEMITONES;
                        semitones <= Transposer.MAX_SEMITONES; semitones++) {
                    assertThat(symbols(chart(charted(transpose(pop(tonic), semitones)))))
                            .as("%s by %+d", tonic, semitones)
                            .noneMatch(symbol -> symbol.contains("##") || symbol.contains("bb"));
                }
            }
        }

        @Test
        @DisplayName("a chart moved by an interval and back is the chart it started as")
        void movingBackRestoresTheChart() {
            for (String tonic : TONICS) {
                List<String> before = symbols(chart(pop(tonic)));
                for (int semitones = -12; semitones <= 12; semitones++) {
                    Score there = transpose(pop(tonic), semitones).score();
                    Score andBack = transpose(there, -semitones).score();
                    assertThat(symbols(chart(andBack)))
                            .as("%s by %+d and back", tonic, semitones)
                            .isEqualTo(before);
                }
            }
        }

        @Test
        @DisplayName("two spellings of the same music transpose to the same chart")
        void theSourceSpellingDoesNotSurviveIntoTheAnswer() {
            // D sharp major and E flat major are one key written two ways, and
            // the estimator's fixed sharp table produces the first. If the
            // interval were read off the written roots rather than off the key,
            // the two would come apart.
            Score sharp = pop("D#4");
            Score flat = pop("Eb4");

            // Every shift that changes the notation. Zero and the octaves are
            // pinned to leave it exactly as they found it -- see
            // anOctaveIsAPureOctave -- so they carry the difference through
            // rather than resolving it.
            for (int semitones = -11; semitones <= 11; semitones++) {
                if (semitones == 0) {
                    continue;
                }
                assertThat(symbols(chart(charted(transpose(sharp, semitones)))))
                        .as("by %+d", semitones)
                        .isEqualTo(symbols(chart(charted(transpose(flat, semitones)))));
            }
        }

        @Test
        @DisplayName("a keyless score is moved from where its own chords sit")
        void aKeylessScoreIsMovedFromItsChords() {
            // The MIDI path with no key signature, and what the audio path was
            // before #275. There is no signature to move, so the interval comes
            // from the region ChordSpeller would count for these chords.
            Score keyless = Score.empty(TempoMap.constant(120), 8.0)
                    .withChords(progression("A#4", "F4", "G4", "D#4"));

            assertThat(symbols(chart(charted(transpose(keyless, 2)))))
                    .containsExactly("C", "G", "A", "F");
        }

        @Test
        @DisplayName("a no-chord span is carried through rather than moved")
        void aNoChordSpanIsLeftAlone() {
            Score withRest = Score.empty(TempoMap.constant(120), 8.0)
                    .withKeys(List.of(keyOf("C4", Mode.MAJOR, 8.0)))
                    .withChords(new ChordProgression(List.of(
                            Chord.noChord(0, 1, Confidence.of(0.5)),
                            Chord.ofSeconds(PitchSpelling.parse("C4"), ChordQuality.MAJOR,
                                    1, 2, Confidence.of(0.8))), Confidence.of(0.8)));

            Score moved = charted(transpose(withRest, 3));

            assertThat(moved.chords().chords().get(0).isNoChord()).isTrue();
            assertThat(symbols(moved)).containsExactly("N.C.", "Eb");
        }

        @Test
        @DisplayName("a slash bass moves with the chord over it")
        void aSlashBassMoves() {
            Score slash = Score.empty(TempoMap.constant(120), 4.0)
                    .withKeys(List.of(keyOf("C4", Mode.MAJOR, 4.0)))
                    .withChords(new ChordProgression(List.of(
                            Chord.ofSeconds(PitchSpelling.parse("C4"), ChordQuality.MAJOR,
                                            0, 2, Confidence.of(0.8))
                                    .withBass(PitchSpelling.parse("E3"))), Confidence.of(0.8)));

            assertThat(symbols(chart(charted(transpose(slash, 3))))).containsExactly("Eb/G");
        }

        @Test
        @DisplayName("everything about a chord but its written pitches is untouched")
        void onlyThePitchesMove() {
            Chord source = Chord.ofSeconds(PitchSpelling.parse("A#4"),
                            ChordQuality.DOMINANT_SEVENTH, 1.5, 3.25, Confidence.of(0.37))
                    .quantizedTo(3, 6.5);
            Score score = Score.empty(TempoMap.constant(120), 8.0)
                    .withChords(new ChordProgression(List.of(source), Confidence.of(0.8)));

            Chord moved = transpose(score, 2).score().chords().chords().get(0);

            assertThat(moved.quality()).isEqualTo(source.quality());
            assertThat(moved.startSeconds()).isEqualTo(1.5);
            assertThat(moved.endSeconds()).isEqualTo(3.25);
            assertThat(moved.startBeat()).contains(3.0);
            assertThat(moved.endBeat()).contains(6.5);
            assertThat(moved.confidence()).isEqualTo(source.confidence());
        }
    }

    @Nested
    @DisplayName("the notes under the chart")
    class TheNotes {

        @Test
        @DisplayName("a note's spelling is displaced, so a flat degree stays flat")
        void aDeliberateSpellingIsCarriedRatherThanGuessedAgain() {
            // D flat in C major is the flat second, written that way by whoever
            // wrote the file. Two semitones up it is the flat second of D major,
            // which is E flat -- not the D sharp that re-deriving from the
            // sounding pitch and a fresh key would be free to choose.
            Score score = withMelody(keyed("C4", Mode.MAJOR), "Db4");

            assertThat(spellings(transpose(score, 2).score())).containsExactly("Eb4");
        }

        @Test
        @DisplayName("every interval between two notes survives the move")
        void oneNumberMovesEverything() {
            Score score = withMelody(keyed("C4", Mode.MAJOR), "C4", "Db4", "E4", "F#4", "B4");

            assertThat(spellings(transpose(score, 5).score()))
                    .containsExactly("F4", "Gb4", "A4", "B4", "E5");
        }

        @Test
        @DisplayName("a note with no spelling still sounds where it should")
        void anUnspelledNoteMovesToo() {
            Score score = withMelody(keyed("C4", Mode.MAJOR), (String) null);

            Note moved = transpose(score, 3).score().tracks().get(0).notes().get(0);
            assertThat(moved.midiPitch()).isEqualTo(63);
            assertThat(moved.spelling()).isEmpty();
        }

        @Test
        @DisplayName("a spelling the shift would push past a double sharp degrades to a printable one")
        void anUnprintableDisplacementFallsBack() {
            // B sharp sits twelve steps up the line of fifths; seven more would
            // be a triple sharp, which no staff prints. The answer has to be the
            // same sound, as near the position that was wanted as a page allows.
            Score score = withMelody(keyed("C#4", Mode.MAJOR), "B#4");

            List<String> moved = spellings(transpose(score, 1).score());
            assertThat(moved).hasSize(1);
            assertThat(PitchSpelling.parse(moved.get(0)).midiPitch())
                    .isEqualTo(PitchSpelling.parse("B#4").midiPitch() + 1);
            assertThat(moved.get(0)).doesNotContain("###");
        }
    }

    @Nested
    @DisplayName("what it refuses to do")
    class TheRefusals {

        @Test
        @DisplayName("a shift past two octaves is rejected rather than wrapped")
        void tooFarIsRefused() {
            // 50 typed for 5. Pitch classes repeat every twelve, so the chart
            // this would print is indistinguishable from a correct one.
            assertThatThrownBy(() -> Transposer.transpose(cMajorPop(), 50))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("50");
            assertThatThrownBy(() -> Transposer.transpose(cMajorPop(), -25))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(keyName(charted(transpose(cMajorPop(), Transposer.MAX_SEMITONES))))
                    .isEqualTo("C major");
        }

        @Test
        @DisplayName("a part holding a note the shift cannot move is left out and named")
        void anUnmovablePartIsLeftOutAndNamed() {
            Score score = withMelody(keyed("C4", Mode.MAJOR), "F#9");

            Transposer.Result result = Transposer.transpose(score, 12);

            assertThat(result.score().tracks()).isEmpty();
            assertThat(result.partsLeftOut()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("melody")
                    .contains("126")
                    .contains("+12")
                    .contains("0..127");
        }

        @Test
        @DisplayName("the bottom of the range is checked as well")
        void theBottomOfTheRangeIsChecked() {
            Score score = withMelody(keyed("C4", Mode.MAJOR), "C-1");

            assertThat(Transposer.transpose(score, -1).partsLeftOut()).hasSize(1);
            assertThat(Transposer.transpose(score, 1).partsLeftOut()).isEmpty();
        }

        @Test
        @DisplayName("the other parts survive, and so does the chart")
        void everythingElseIsStillTransposed() {
            Score score = keyed("C4", Mode.MAJOR)
                    .withChords(progression("C4", "G4", "A4", "F4"))
                    .withTrack(track(PartRole.LEAD_VOCAL, "melody", "F#9"))
                    .withTrack(track(PartRole.BASS, "bass", "C3"));

            Transposer.Result result = Transposer.transpose(score, 12);

            assertThat(result.partsLeftOut()).hasSize(1);
            assertThat(result.score().tracks()).singleElement()
                    .extracting(NoteTrack::role).isEqualTo(PartRole.BASS);
            assertThat(spellings(result.score())).containsExactly("C4");
            assertThat(symbols(result.score())).containsExactly("C", "G", "A", "F");
        }
    }

    @Nested
    @DisplayName("the shifts that change nothing")
    class TheIdentities {

        @Test
        @DisplayName("zero returns the score it was given")
        void zeroIsIdentity() {
            Score score = cMajorPop();

            Transposer.Result result = Transposer.transpose(score, 0);

            assertThat(result.score()).isSameAs(score);
            assertThat(result.partsLeftOut()).isEmpty();
        }

        @Test
        @DisplayName("an octave moves the notes and leaves the notation alone")
        void anOctaveIsAPureOctave() {
            // C sharp major is seven sharps, and the cheapest way to write that
            // music is D flat major with five flats. Moving it an octave is not
            // an invitation to rewrite it: nobody asked for the correction, and
            // a part written to be read by a player who knows the piece in C
            // sharp would come back unrecognisable.
            Score score = withMelody(keyed("C#4", Mode.MAJOR), "E#4")
                    .withChords(progression("C#4", "G#4", "A#4", "F#4"));

            Transposer.Result result = Transposer.transpose(score, 12);

            assertThat(keyName(result.score())).isEqualTo("C# major");
            assertThat(spellings(result.score())).containsExactly("E#5");
            assertThat(symbols(result.score())).containsExactly("C#", "G#", "A#", "F#");
        }
    }

    @Nested
    @DisplayName("a score that modulates")
    class Modulation {

        @Test
        @DisplayName("each key lands on its own cheapest signature")
        void everyKeyMovesAndStaysCheap() {
            Score score = Score.empty(TempoMap.constant(120), 8.0)
                    .withKeys(List.of(
                            Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MAJOR,
                                    0, 4, Confidence.of(0.9)),
                            Key.ofSeconds(PitchSpelling.parse("Eb4"), Mode.MAJOR,
                                    4, 8, Confidence.of(0.9))))
                    .withChords(progression("C4", "G4", "D#4", "A#4"));

            Score moved = transpose(score, 1).score();

            assertThat(moved.keys().stream().map(Key::displayName).toList())
                    .containsExactly("Db major", "E major");
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * One tonic per pitch class, spelled the way this stage writes a key of that
     * name: at most six accidentals, and the tie at six taken flat. These are the
     * twelve keys a transposed score can land in, which is what lets a chart
     * moved by an interval and back be compared with the one it started as.
     */
    private static final List<String> TONICS = List.of(
            "C4", "Db4", "D4", "Eb4", "E4", "F4", "Gb4", "G4", "Ab4", "A4", "Bb4", "B4");

    private static Transposer.Result transpose(Score score, int semitones) {
        return Transposer.transpose(score, semitones);
    }

    /** The moved score, taken from a result no fixture here expects a refusal from. */
    private static Score charted(Transposer.Result result) {
        assertThat(result.partsLeftOut()).isEmpty();
        return result.score();
    }

    /** What {@code render} prints: the transposed score, re-spelled. */
    private static Score chart(Score score) {
        return ChordSpeller.respell(score);
    }

    /** A I-V-vi-IV in the named major key, spelled as the estimator spells. */
    private static Score pop(String tonic) {
        int root = PitchSpelling.parse(tonic).pitchClass();
        return keyed(tonic, Mode.MAJOR).withChords(new ChordProgression(List.of(
                triad(root, ChordQuality.MAJOR, 0),
                triad(root + 7, ChordQuality.MAJOR, 1),
                triad(root + 9, ChordQuality.MINOR, 2),
                triad(root + 5, ChordQuality.MAJOR, 3)), Confidence.of(0.8)));
    }

    private static Score cMajorPop() {
        return pop("C4");
    }

    /** The same four chords framed on the relative minor. */
    private static Score aMinorPop() {
        return keyed("A4", Mode.MINOR).withChords(new ChordProgression(List.of(
                triad(9, ChordQuality.MINOR, 0),
                triad(5, ChordQuality.MAJOR, 1),
                triad(0, ChordQuality.MAJOR, 2),
                triad(7, ChordQuality.MAJOR, 3)), Confidence.of(0.8)));
    }

    /**
     * One chord, rooted on a pitch class and spelled as {@code ChordEstimator}
     * spells: every black key a sharp. A fixture written in flats would pass
     * whatever this class did to it.
     */
    private static Chord triad(int pitchClass, ChordQuality quality, int atSecond) {
        String[] sharps = {"C4", "C#4", "D4", "D#4", "E4", "F4",
                "F#4", "G4", "G#4", "A4", "A#4", "B4"};
        return Chord.ofSeconds(PitchSpelling.parse(sharps[Math.floorMod(pitchClass, 12)]),
                quality, atSecond, atSecond + 1.0, Confidence.of(0.8));
    }

    private static ChordProgression progression(String... roots) {
        List<Chord> chords = new ArrayList<>(roots.length);
        for (int i = 0; i < roots.length; i++) {
            chords.add(Chord.ofSeconds(PitchSpelling.parse(roots[i]), ChordQuality.MAJOR,
                    i, i + 1.0, Confidence.of(0.8)));
        }
        return new ChordProgression(chords, Confidence.of(0.8));
    }

    /** An otherwise-empty score in one key for all of it. */
    private static Score keyed(String tonic, Mode mode) {
        return Score.empty(TempoMap.constant(120), 8.0)
                .withKeys(List.of(keyOf(tonic, mode, 8.0)));
    }

    private static Key keyOf(String tonic, Mode mode, double until) {
        return Key.ofSeconds(PitchSpelling.parse(tonic), mode, 0, until, Confidence.of(0.9));
    }

    /** The score with a melody part holding the given spellings, one per second. */
    private static Score withMelody(Score score, String... spellings) {
        return score.withTrack(track(PartRole.LEAD_VOCAL, "melody", spellings));
    }

    private static NoteTrack track(PartRole role, String name, String... spellings) {
        List<Note> notes = new ArrayList<>(spellings.length);
        for (int i = 0; i < spellings.length; i++) {
            Optional<PitchSpelling> written =
                    Optional.ofNullable(spellings[i]).map(PitchSpelling::parse);
            notes.add(new Note(i, 1.0, written.map(PitchSpelling::midiPitch).orElse(60),
                    Note.DEFAULT_VELOCITY, written, Optional.empty(), Optional.empty(),
                    Confidence.of(0.8)));
        }
        return new NoteTrack(role, name, notes, Confidence.of(0.8));
    }

    private static List<String> symbols(Score score) {
        return score.chords().chords().stream().map(Chord::symbol).toList();
    }

    private static List<String> spellings(Score score) {
        return score.tracks().stream()
                .flatMap(part -> part.notes().stream())
                .map(note -> note.spelling().orElseThrow().toString())
                .toList();
    }

    private static String keyName(Score score) {
        return score.primaryKey().orElseThrow().displayName();
    }
}
