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

    /** The transposed score, for the majority of cases that expect no part to be lost. */
    private static Score moved(Score score, int semitones) {
        Transposer.Result result = Transposer.transpose(score, semitones);
        assertThat(result.partsLeftOut()).as("a part was unexpectedly left out").isEmpty();
        return result.score();
    }

    @Nested
    @DisplayName("the key it lands in")
    class TargetKey {

        @ParameterizedTest(name = "{0} by {2} is {3}")
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

            Key landed = moved(score, semitones).primaryKey().orElseThrow();

            assertThat(landed.tonic()).isEqualTo(PitchSpelling.parse(expectedTonic));
            assertThat(landed.mode()).isEqualTo(expectedMode);
        }

        @Test
        @DisplayName("a tritone from a natural key is written flat")
        void aTritoneGoesToTheFlatSide() {
            // Six semitones is the one shift whose two answers are exactly as
            // simple as each other -- G flat major and F sharp major both carry
            // six accidentals -- so something has to break the tie. It goes the
            // same way PitchSpeller's does, which is what keeps a chord symbol
            // and the note heads under it agreeing.
            Key landed = moved(scoreInKey("C4", Mode.MAJOR), 6).primaryKey().orElseThrow();

            assertThat(landed.tonic()).isEqualTo(PitchSpelling.parse("Gb4"));
            assertThat(landed.keySignatureAccidentals()).isEqualTo(-6);
        }

        @Test
        @DisplayName("the printed signature moves with the symbols, not independently of them")
        void theSignatureFollows() {
            // One displacement decides the tonic and one region decides the
            // roots, and they are the same number, so the flats in the signature
            // and the flats on the chord symbols cannot come out disagreeing.
            Score landed = moved(scoreInKey("C4", Mode.MAJOR), 3);

            assertThat(landed.primaryKey().orElseThrow().keySignatureAccidentals())
                    .isEqualTo(-3);
            assertThat(symbolsOf(landed)).containsExactly("Eb", "Bb", "Cm", "Ab");
        }
    }

    @Nested
    @DisplayName("chord symbols")
    class Chords {

        @Test
        @DisplayName("I-V-vi-IV in C up a fourth is F, C, Dm, Bb")
        void theProjectsOwnProgression() {
            assertThat(symbolsOf(moved(scoreInKey("C4", Mode.MAJOR), 5)))
                    .containsExactly("F", "C", "Dm", "Bb");
        }

        @Test
        @DisplayName("keep their quality and their timing")
        void onlyThePitchMoves() {
            Score before = scoreInKey("C4", Mode.MAJOR);
            Score after = moved(before, 7);

            for (int i = 0; i < before.chords().size(); i++) {
                Chord was = before.chords().chords().get(i);
                Chord is = after.chords().chords().get(i);
                assertThat(is.quality()).isEqualTo(was.quality());
                assertThat(is.startSeconds()).isEqualTo(was.startSeconds());
                assertThat(is.endSeconds()).isEqualTo(was.endSeconds());
                assertThat(is.startBeat()).isEqualTo(was.startBeat());
                assertThat(is.endBeat()).isEqualTo(was.endBeat());
                assertThat(is.confidence()).isEqualTo(was.confidence());
                assertThat(is.root().midiPitch()).isEqualTo(was.root().midiPitch() + 7);
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

            Chord landed = moved(score, 5).chords().chords().get(0);

            assertThat(landed.symbol()).isEqualTo("F/A");
            assertThat(landed.bass().orElseThrow()).isEqualTo(PitchSpelling.parse("A3"));
        }

        @Test
        @DisplayName("a no-chord span is left exactly as it was")
        void noChordIsUntouched() {
            // Chord.noChord parks a placeholder C on a span that has no root at
            // all. Moving a placeholder would invent a fact, and nothing prints
            // it: the chart writes N.C. and the engraver writes a rest.
            Chord rest = Chord.noChord(0, 2, Confidence.CERTAIN);
            Score score = scoreOf(List.of(rest,
                    chordAt("C4", ChordQuality.MAJOR, 2, 4)), List.of(), List.of());

            Score landed = moved(score, 5);

            assertThat(landed.chords().chords().get(0)).isEqualTo(rest);
            assertThat(symbolsOf(landed)).containsExactly("N.C.", "F");
        }

        @Test
        @DisplayName("with no key detected, the roots say where the piece sits")
        void theRootsStandInForAKey() {
            // The audio path has no key detection yet, so this is the branch the
            // shipped tool actually takes. A progression in F -- F, Bb, C, Dm --
            // moved up a fourth has to reach B flat, not A sharp, and the only
            // evidence for that is the sounding roots themselves.
            Score score = scoreOf(List.of(
                    chordAt("F4", ChordQuality.MAJOR, 0, 2),
                    chordAt("Bb4", ChordQuality.MAJOR, 2, 4),
                    chordAt("C4", ChordQuality.MAJOR, 4, 6),
                    chordAt("D4", ChordQuality.MINOR, 6, 8)),
                    List.of(), List.of());

            assertThat(score.primaryKey()).isEmpty();
            assertThat(symbolsOf(moved(score, 5)))
                    .containsExactly("Bb", "Eb", "F", "Gm");
        }

        @Test
        @DisplayName("a silent bar does not vote on where the piece sits")
        void noChordDoesNotVoteOnTheRegion() {
            // A rest carries a placeholder root, and reading it would be reading
            // a fact that is not there.
            //
            // Honest about its own strength: no fixture found so far tells the
            // two apart. The region search compares whole spellings against a
            // candidate region and one extra natural root moves that answer only
            // at a boundary that also has to change the transposed spelling --
            // four hundred thousand random progressions crossed with every shift
            // produced none. So this pins a guarantee rather than catching a
            // known break, and it would catch the estimator becoming sensitive
            // to silence later.
            List<Chord> voiced = List.of(
                    chordAt("Db4", ChordQuality.MAJOR, 2, 4),
                    chordAt("Ab4", ChordQuality.MAJOR, 4, 6),
                    chordAt("Bb4", ChordQuality.MINOR, 6, 8),
                    chordAt("Gb4", ChordQuality.MAJOR, 8, 10));
            List<Chord> withRest = new ArrayList<>(voiced);
            withRest.add(0, Chord.noChord(0, 2, Confidence.CERTAIN));

            for (int semitones = -12; semitones <= 12; semitones++) {
                if (semitones == 0) {
                    continue;
                }
                List<String> rested = symbolsOf(
                        moved(scoreOf(withRest, List.of(), List.of()), semitones));
                assertThat(rested.subList(1, rested.size()))
                        .as("shift %d", semitones)
                        .isEqualTo(symbolsOf(moved(scoreOf(voiced, List.of(), List.of()),
                                semitones)));
            }
        }
    }

    @Nested
    @DisplayName("a chord root is written afresh rather than displaced")
    class RootsAreRederived {

        /**
         * A I-V-vi-IV whose roots are spelled the way the pipeline really spells
         * them: every black key a sharp, from a fixed table.
         *
         * <p>{@code ChordEstimator.spell} does exactly this and says in its own
         * javadoc that the key estimator re-spells the progression afterwards --
         * a stage that does not exist. So on the audio path a piece in E flat
         * arrives here as {@code D# A# Cm G#}.
         */
        private static Score asTheAudioPathSpellsIt(int tonic) {
            int[] degrees = {0, 7, 9, 5};
            ChordQuality[] qualities = {ChordQuality.MAJOR, ChordQuality.MAJOR,
                    ChordQuality.MINOR, ChordQuality.MAJOR};
            List<Chord> chords = new ArrayList<>();
            for (int i = 0; i < degrees.length; i++) {
                chords.add(Chord.ofSeconds(PitchSpelling.ofMidiPitchSharp(tonic + degrees[i]),
                        qualities[i], i * 2.0, i * 2.0 + 2.0, Confidence.CERTAIN));
            }
            return scoreOf(chords, List.of(), List.of());
        }

        @ParameterizedTest(name = "by {0} semitones")
        @CsvSource({"2", "5", "-1", "3", "6", "-7"})
        @DisplayName("so the chart is the same whichever way its roots arrived spelled")
        void theSpellingItArrivesWithDoesNotMatter(int semitones) {
            // Round 1 of review, confirmed by execution: displacing these
            // spellings read a fixed table as intent and produced F C Ebbm Bb
            // for the second row -- an E double flat minor chord on an engraved
            // page, exit 0. Both fixtures sound identical, so both charts must
            // read identical.
            Score sharpTable = asTheAudioPathSpellsIt(63);
            Score properlySpelled = scoreOf(List.of(
                    chordAt("Eb4", ChordQuality.MAJOR, 0, 2),
                    chordAt("Bb4", ChordQuality.MAJOR, 2, 4),
                    chordAt("C4", ChordQuality.MINOR, 4, 6),
                    chordAt("Ab4", ChordQuality.MAJOR, 6, 8)),
                    List.of(), List.of());

            assertThat(symbolsOf(moved(sharpTable, semitones)))
                    .isEqualTo(symbolsOf(moved(properlySpelled, semitones)));
        }

        @Test
        @DisplayName("and no chart gains an accidental a chart cannot carry")
        void noDoubleAccidentalsAnywhere() {
            // The measurement round 1 made, run as an assertion. Before the fix
            // one chart in eighteen over this sweep carried a double accidental
            // and one in thirteen mixed sharps with flats, against a baseline of
            // none for both -- the sharp table produces neither.
            for (int tonic = 60; tonic < 72; tonic++) {
                for (int semitones = -24; semitones <= 24; semitones++) {
                    if (semitones == 0) {
                        continue;
                    }
                    boolean sharp = false;
                    boolean flat = false;
                    for (Chord chord : moved(asTheAudioPathSpellsIt(tonic), semitones)
                            .chords().chords()) {
                        int alteration = chord.root().accidental().alteration();
                        assertThat(Math.abs(alteration))
                                .as("tonic %d shifted %d gave %s", tonic, semitones,
                                        chord.symbol())
                                .isLessThanOrEqualTo(1);
                        sharp |= alteration > 0;
                        flat |= alteration < 0;
                    }
                    assertThat(sharp && flat)
                            .as("tonic %d shifted %d mixed sharps with flats", tonic, semitones)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("an octave leaves the symbols exactly where they were")
        void anOctaveIsStillTheSameChart() {
            // Found by that sweep rather than by a fixture, and worth its own
            // case. The region search's cost repeats every twelve -- C major is
            // as cheap written from -12, where its roots are D double flat and A
            // double flat, as from 0 -- so it used to return the flattest of the
            // equal minima. Every ordinary shift hid it, because the displacement
            // was chosen from the same wrong region and cancelled it; an octave
            // is pinned to no displacement and has nothing to cancel with, so
            // C G Am F an octave down came out as Dbb Abb Bbbm Gbb.
            Score audio = asTheAudioPathSpellsIt(60);

            for (int semitones : new int[] {12, -12, 24, -24}) {
                assertThat(symbolsOf(moved(audio, semitones)))
                        .as("shift %d", semitones)
                        .containsExactly("C", "G", "Am", "F");
            }
        }

        @Test
        @DisplayName("a borrowed root keeps the side of the line a chart wants")
        void borrowedRootsLandWhereAChartWantsThem() {
            // What ROOT_CENTRE_OFFSET is calibrated on, and the reason it is not
            // PitchSpeller's centre. In C major the flat second is D flat and the
            // raised fourth is F sharp; judged from where a key's *notes* sit,
            // two fifths sharper, the first comes out C sharp instead. Up two,
            // both have to stay on the side they started.
            Score score = scoreOf(List.of(
                    chordAt("C4", ChordQuality.MAJOR, 0, 2),
                    chordAt("Db4", ChordQuality.MAJOR, 2, 4),
                    chordAt("G4", ChordQuality.MAJOR, 4, 6),
                    chordAt("F#4", ChordQuality.MAJOR, 6, 8)),
                    List.of(key("C4", Mode.MAJOR)), List.of());

            assertThat(symbolsOf(moved(score, 2)))
                    .containsExactly("D", "Eb", "A", "G#");
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
            // either way; the case that separates the two is the B below.
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(key("C4", Mode.MAJOR)),
                    List.of(spelled(0.0, 60, "C4"), spelled(1.0, 64, "E4"),
                            spelled(2.0, 71, "B4")));

            List<Note> landed = moved(score, 3).tracks().get(0).notes();

            assertThat(landed).extracting(Note::midiPitch).containsExactly(63, 67, 74);
            assertThat(landed).extracting(n -> n.spelling().orElseThrow().displayName())
                    .containsExactly("Eb4", "G4", "D5");
        }

        @Test
        @DisplayName("an unspelled note stays unspelled")
        void nothingIsInvented() {
            // Spelling is derived, and the pipeline derives it later, from the
            // transposed harmony. Guessing one here would pre-empt PitchSpeller
            // with a worse answer -- it has the sounding chord to consult and
            // this does not.
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(Note.ofSeconds(0.0, 1.0, 60, Confidence.CERTAIN)));

            Note landed = moved(score, 5).tracks().get(0).notes().get(0);

            assertThat(landed.midiPitch()).isEqualTo(65);
            assertThat(landed.spelling()).isEmpty();
        }

        @Test
        @DisplayName("keep their timing, velocity and confidence")
        void onlyThePitchMoves() {
            Note before = new Note(1.5, 0.75, 60, 42, Optional.of(PitchSpelling.parse("C4")),
                    Optional.of(3.0), Optional.of(1.5), Confidence.of(0.4));
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(before));

            Note after = moved(score, 5).tracks().get(0).notes().get(0);

            assertThat(after.onsetSeconds()).isEqualTo(before.onsetSeconds());
            assertThat(after.durationSeconds()).isEqualTo(before.durationSeconds());
            assertThat(after.velocity()).isEqualTo(before.velocity());
            assertThat(after.onsetBeat()).isEqualTo(before.onsetBeat());
            assertThat(after.durationBeats()).isEqualTo(before.durationBeats());
            assertThat(after.confidence()).isEqualTo(before.confidence());
        }

        @Test
        @DisplayName("a part the shift cannot move is left out and named, not fatal")
        void anUnmovablePartIsLeftOutAndNamed() {
            // #57 chose the refusal: Note.transposedBy will not guess, and what
            // that means is the caller's decision. Round 1 of review found the
            // first answer here -- failing the whole run -- wrong, because
            // render --parts chords then died over a note in a part no
            // implemented emitter would ever have written. The chart is still
            // produced and the part that could not come with it is named.
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(spelled(2.5, 126, "F#9")));

            Transposer.Result result = Transposer.transpose(score, 3);

            assertThat(result.score().tracks()).isEmpty();
            assertThat(symbolsOf(result.score())).containsExactly("Eb");
            assertThat(result.partsLeftOut()).singleElement().asString()
                    .contains("Voice")
                    .contains("126")
                    .contains("2.500s")
                    .contains("+3 semitones")
                    .contains("0..127");
        }

        @Test
        @DisplayName("only the part that cannot move is left out")
        void otherPartsSurvive() {
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(spelled(0.0, 60, "C4")))
                    .withTrack(new NoteTrack(PartRole.BASS, "Bass",
                            List.of(spelled(2.5, 126, "F#9")), Confidence.CERTAIN));

            Transposer.Result result = Transposer.transpose(score, 3);

            assertThat(result.score().tracks()).singleElement()
                    .extracting(NoteTrack::name).isEqualTo("Voice");
            assertThat(result.partsLeftOut()).singleElement().asString().contains("Bass");
        }

        @Test
        @DisplayName("and downwards too, not only past the top")
        void theBottomOfTheRangeIsCheckedAsWell() {
            Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 8)),
                    List.of(), List.of(spelled(0.0, 1, "C#-1")));

            Transposer.Result result = Transposer.transpose(score, -3);

            assertThat(result.score().tracks()).isEmpty();
            assertThat(result.partsLeftOut()).singleElement().asString()
                    .contains("-3 semitones");
        }
    }

    @Nested
    @DisplayName("the shift itself")
    class TheShift {

        @Test
        @DisplayName("zero returns the very same score")
        void zeroIsExactlyNothing() {
            // Identity, not equality. A no-op that ran the machinery below would
            // re-derive every chord root from the estimated region and could
            // change one, which is a page the user never asked to have altered.
            Score score = scoreInKey("C4", Mode.MAJOR);

            Transposer.Result result = Transposer.transpose(score, 0);

            assertThat(result.score()).isSameAs(score);
            assertThat(result.partsLeftOut()).isEmpty();
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

            Score landed = moved(score, semitones);

            assertThat(landed.primaryKey().orElseThrow().tonic().letter())
                    .isEqualTo(NoteLetter.C);
            assertThat(landed.primaryKey().orElseThrow().tonic().accidental())
                    .isEqualTo(Accidental.SHARP);
            assertThat(symbolsOf(landed)).containsExactly("C#", "G#");
            Note note = landed.tracks().get(0).notes().get(0);
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

            moved(score, 5);

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

            Score landed = moved(score, 4);

            assertThat(landed.title()).isEqualTo(score.title());
            assertThat(landed.artist()).isEqualTo(score.artist());
            assertThat(landed.tempoMap()).isEqualTo(score.tempoMap());
            assertThat(landed.lyrics()).isEqualTo(score.lyrics());
            assertThat(landed.durationSeconds()).isEqualTo(score.durationSeconds());
            assertThat(landed.tracks().get(0).role()).isEqualTo(PartRole.LEAD_VOCAL);
            assertThat(landed.tracks().get(0).name()).isEqualTo("Voice");
        }
    }

    @Nested
    @DisplayName("a displacement that cannot be written down")
    class Unwritable {

        @Test
        @DisplayName("falls back to the region's own spelling when it needs a third accidental")
        void anUnprintableAccidentalDegrades() {
            // Reachable, and only just: the source spelling has to be at a double
            // accidental already and the key has to point the other way. A B
            // double sharp in a score detected as C flat major, moved up a
            // semitone, wants a triple sharp -- and the target key is C major,
            // where the note is a plain D. Refusing to transpose over it would be
            // worse than writing the enharmonic, since every other note on the
            // page is fine.
            Score score = scoreOf(List.of(chordAt("Cb4", ChordQuality.MAJOR, 0, 8)),
                    List.of(key("Cb4", Mode.MAJOR)),
                    List.of(spelled(0.0, 73, "B##4")));

            Score landed = moved(score, 1);

            assertThat(landed.primaryKey().orElseThrow().tonic())
                    .isEqualTo(PitchSpelling.parse("C4"));
            Note note = landed.tracks().get(0).notes().get(0);
            assertThat(note.midiPitch()).isEqualTo(74);
            assertThat(note.spelling().orElseThrow()).isEqualTo(PitchSpelling.parse("D5"));
        }

        @Test
        @DisplayName("and when the accidental is fine but the octave is not")
        void anUnwritableOctaveDegradesToo() {
            // Round 1 of review found this one. PitchSpeller centralised the
            // octave-band check in atOctave so that no route to a spelling could
            // skip it, and displacing one was a new route: B sharp sounding as
            // MIDI 12 is legal, and an octave down it is B sharp in octave -2,
            // which PitchSpelling.parse refuses. The accidental is a plain sharp,
            // so nothing about the alteration catches it.
            Score score = scoreOf(List.of(chordAt("C#4", ChordQuality.MAJOR, 0, 8)),
                    List.of(key("C#4", Mode.MAJOR)),
                    List.of(new Note(0.0, 0.5, 12, Note.DEFAULT_VELOCITY,
                            Optional.of(new PitchSpelling(NoteLetter.B, Accidental.SHARP, -1)),
                            Optional.empty(), Optional.empty(), Confidence.CERTAIN)));

            Note note = moved(score, -12).tracks().get(0).notes().get(0);

            assertThat(note.midiPitch()).isZero();
            assertThat(note.spelling().orElseThrow()).isEqualTo(PitchSpelling.parse("C-1"));
        }

        @Test
        @DisplayName("so every spelling it produces can be written down and read back")
        void nothingUnwritableEscapes() {
            // Swept over the whole MIDI range rather than over a musical band,
            // because both failures above live at the ends of it: the octave
            // recovery is exact in the middle and the enharmonics only cross an
            // octave boundary near 0 and 127.
            for (int semitones = -24; semitones <= 24; semitones++) {
                if (semitones == 0) {
                    continue;
                }
                Score score = scoreOf(List.of(chordAt("C4", ChordQuality.MAJOR, 0, 800)),
                        List.of(key("C4", Mode.MAJOR)),
                        everyPitchFrom(Math.max(0, -semitones),
                                Math.min(127, 127 - semitones)));
                for (Note note : moved(score, semitones).tracks().get(0).notes()) {
                    PitchSpelling spelling = note.spelling().orElseThrow();
                    assertThat(spelling.midiPitch())
                            .as("shift %d spelled MIDI %d as %s",
                                    semitones, note.midiPitch(), spelling.displayName())
                            .isEqualTo(note.midiPitch());
                    assertThat(PitchSpelling.parse(spelling.displayName()))
                            .as("shift %d produced an unparseable %s",
                                    semitones, spelling.displayName())
                            .isEqualTo(spelling);
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
                new ChordProgression(chords, Confidence.of(0.8)), Lyrics.empty(), 1000.0);
    }

    /** One note per MIDI pitch in a band, spelled the way C major spells it. */
    private static List<Note> everyPitchFrom(int lowest, int highest) {
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
        return Key.ofSeconds(PitchSpelling.parse(tonic), mode, 0, 1000, Confidence.CERTAIN);
    }

    private static Chord chordAt(String root, ChordQuality quality, double from, double to) {
        return Chord.ofSeconds(PitchSpelling.parse(root), quality, from, to, Confidence.CERTAIN);
    }

    private static List<String> symbolsOf(Score score) {
        return score.chords().chords().stream().map(Chord::symbol).toList();
    }
}
