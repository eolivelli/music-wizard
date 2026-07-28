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

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Mode;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import java.util.List;
import javax.sound.midi.Sequence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the harmony of a MIDI file is worth once it has been read.
 *
 * <p>Every assertion here goes through {@link MidiTranscriber} rather than
 * calling {@link SymbolicChordEstimator} directly, because the defect this
 * fixes was that the two were not connected: a test of the estimator alone
 * would have passed against a transcriber that still discarded its answer.
 *
 * <p>Positions are asserted in beats and exactly. MIDI states its rhythm rather
 * than implying it, so a chord boundary that is nearly on the bar line is a
 * chord boundary that has been through an estimate it never needed.
 */
class SymbolicChordEstimatorTest {

    private static final MidiTranscriber TRANSCRIBER = new MidiTranscriber();

    private static List<String> symbolsOf(Sequence sequence) {
        return TRANSCRIBER.transcribe(sequence).chords().chords().stream()
                .map(Chord::symbol)
                .toList();
    }

    @Test
    @DisplayName("the I-V-vi-IV fixture comes back as the progression it states")
    void fourChordSongYieldsItsProgression() {
        Score score = TRANSCRIBER.transcribe(MidiFixtures.fourChordSong());

        assertThat(score.chords().chords()).extracting(Chord::symbol)
                .containsExactly("N.C.", "C", "G", "Am", "F", "C", "G", "Am", "F");
        // The fixture's first bar is silent, and the eight bars of harmony that
        // follow are one chord each: 4, 8, 12 ... 36 quarter beats.
        assertThat(score.chords().chords()).extracting(
                        c -> c.startBeat().orElseThrow(), c -> c.endBeat().orElseThrow())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(0.0, 4.0),
                        org.assertj.core.api.Assertions.tuple(4.0, 8.0),
                        org.assertj.core.api.Assertions.tuple(8.0, 12.0),
                        org.assertj.core.api.Assertions.tuple(12.0, 16.0),
                        org.assertj.core.api.Assertions.tuple(16.0, 20.0),
                        org.assertj.core.api.Assertions.tuple(20.0, 24.0),
                        org.assertj.core.api.Assertions.tuple(24.0, 28.0),
                        org.assertj.core.api.Assertions.tuple(28.0, 32.0),
                        org.assertj.core.api.Assertions.tuple(32.0, 36.0));
    }

    @Test
    @DisplayName("chords arrive quantized, with seconds taken only from the tempo map")
    void chordsCarryBothAxes() {
        Score score = TRANSCRIBER.transcribe(MidiFixtures.fourChordSong());

        // Named, because an empty progression is vacuously quantized and would
        // satisfy everything below it.
        assertThat(score.chords().size()).isEqualTo(9);
        assertThat(score.chords().isQuantized()).isTrue();
        for (Chord chord : score.chords().chords()) {
            assertThat(chord.startSeconds())
                    .isEqualTo(score.tempoMap().beatsToSeconds(chord.startBeat().orElseThrow()));
            assertThat(chord.endSeconds())
                    .isEqualTo(score.tempoMap().beatsToSeconds(chord.endBeat().orElseThrow()));
        }
        // Contiguous: no gap a reader would have to guess across.
        List<Chord> chords = score.chords().chords();
        for (int i = 1; i < chords.size(); i++) {
            assertThat(chords.get(i).startBeat().orElseThrow())
                    .isEqualTo(chords.get(i - 1).endBeat().orElseThrow());
        }
    }

    @Test
    @DisplayName("a tempo change moves the chords in seconds but not in beats")
    void secondsFollowTheTempoMap() {
        // Same harmony throughout, with the tempo halving at beat 8. Chord
        // boundaries in beats must not notice; boundaries in seconds must.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .tempoAt(8, 60)
                .timeSignature(4, 4)
                .part("Keys", 0)
                .chord(0, 4, 60, 64, 67)
                .chord(4, 4, 62, 65, 69)
                .chord(8, 4, 60, 64, 67)
                .chord(12, 4, 62, 65, 69)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.chords().chords()).extracting(Chord::symbol)
                .containsExactly("C", "Dm", "C", "Dm");
        // At 120 a quarter beat is half a second, at 60 it is a whole one.
        assertThat(score.chords().chords()).extracting(Chord::startSeconds)
                .containsExactly(0.0, 2.0, 4.0, 8.0);
    }

    @Test
    @DisplayName("a bass note held under a moving melody is one chord, not one per note")
    void aHeldBassUnderAMovingLineDoesNotChatter() {
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 8, 36).end()
                .part("Melody", 1)
                .note(0, 1, 60).note(1, 1, 62).note(2, 1, 64).note(3, 1, 65)
                .note(4, 1, 67).note(5, 1, 65).note(6, 1, 64).note(7, 1, 62)
                .build();

        assertThat(symbolsOf(sequence)).hasSize(1);
    }

    @Test
    @DisplayName("an unaccompanied melody gets no chords rather than an invented one")
    void aBareLineHasNoHarmony() {
        assertThat(TRANSCRIBER.transcribe(MidiFixtures.cMajorScale()).chords().isEmpty())
                .isTrue();
    }

    @Test
    @DisplayName("a drone states a note, not a chord")
    void aDroneHasNoHarmony() {
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Organ", 0).note(0, 16, 48)
                .build();

        assertThat(TRANSCRIBER.transcribe(sequence).chords().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a drum-only file has no harmony to report")
    void drumsAloneHaveNoHarmony() {
        MidiFixtures.SequenceBuilder builder = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4);
        MidiFixtures.SequenceBuilder.PartBuilder kit =
                builder.part("Drum Kit", MidiFixtures.DRUM_CHANNEL);
        // Kick, electric snare and high floor tom. Those are General MIDI note
        // numbers 36, 40 and 43, which read as pitches are C, E and G -- a
        // groove that spells a perfect C major triad and contains no harmony
        // whatsoever. Chosen deliberately: a kick-and-hat pattern would come
        // back empty even if drums were counted, so it would prove nothing.
        for (int beat = 0; beat < 8; beat++) {
            kit.note(beat, 1, 36).note(beat + 0.5, 0.5, 40);
            if (beat % 2 == 1) {
                kit.note(beat, 1, 43);
            }
        }

        assertThat(TRANSCRIBER.transcribe(builder.build()).chords().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("adding a drum kit to a chord chart does not change the chords")
    void drumsDoNotDisturbTheHarmony() {
        List<String> before = symbolsOf(MidiFixtures.fourChordSong());
        // Stated rather than merely compared, so that two empty progressions
        // cannot agree with each other and call it a pass.
        assertThat(before).containsExactly("N.C.", "C", "G", "Am", "F", "C", "G", "Am", "F");

        MidiFixtures.SequenceBuilder builder = MidiFixtures.sequence()
                .name("Four chords with a kit")
                .tempo(120)
                .timeSignature(4, 4);
        MidiFixtures.SequenceBuilder.PartBuilder keys = builder.part("Piano", 0);
        MidiFixtures.SequenceBuilder.PartBuilder bass = builder.part("Bass", 1);
        MidiFixtures.SequenceBuilder.PartBuilder kit =
                builder.part("Drum Kit", MidiFixtures.DRUM_CHANNEL);
        int[][] triads = {{60, 64, 67}, {59, 62, 67}, {60, 64, 69}, {60, 65, 69}};
        int[] roots = {36, 43, 45, 41};
        for (int bar = 0; bar < 8; bar++) {
            double barStart = 4 + bar * 4.0;
            keys.chord(barStart, 4, triads[bar % 4]);
            bass.note(barStart, 2, roots[bar % 4]).note(barStart + 2, 2, roots[bar % 4]);
            // 38, 42, 45, 50, 54 and 57 read as pitches are D, F sharp and A in
            // two octaves -- a D major triad, which appears in none of the four
            // bars. Six pieces of the kit ringing for a whole bar is a deliberately
            // absurd groove, and it is absurd on purpose: a realistic kick and
            // hat weigh so little against four beats of block triad that the
            // assertion would hold whether drums were counted or not, and would
            // therefore be testing nothing.
            for (int piece : new int[] {38, 42, 45, 50, 54, 57}) {
                kit.note(barStart, 4, piece);
            }
        }

        assertThat(symbolsOf(builder.build())).isEqualTo(before);
    }

    @Test
    @DisplayName("a 6/8 bar is decided in two dotted-quarter spans, not six eighth ones")
    void compoundTimeSpansTheCountedBeat() {
        // Two bars of 6/8 -- three quarter beats each -- with the chord changing
        // on the second counted beat of the first bar, which is quarter beat
        // 1.5. A decision grid built from the numerator could not express that
        // boundary at all; one built from quarter notes would put it at 1.0.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(6, 8)
                .part("Keys", 0)
                .chord(0, 1.5, 60, 64, 67)
                .chord(1.5, 1.5, 62, 65, 69)
                .chord(3, 3, 60, 64, 67)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.tempoMap().initialTimeSignature().beatUnitQuarters()).isEqualTo(1.5);
        assertThat(score.chords().chords()).extracting(
                        Chord::symbol, c -> c.startBeat().orElseThrow())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("C", 0.0),
                        org.assertj.core.api.Assertions.tuple("Dm", 1.5),
                        org.assertj.core.api.Assertions.tuple("C", 3.0));
    }

    @Test
    @DisplayName("a seventh that sounds is reported; one that passes by is not")
    void seventhsHaveToEarnTheirPlace() {
        Sequence sustained = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 60, 64, 67, 70).chord(4, 4, 60, 64, 67, 70)
                .build();
        assertThat(symbolsOf(sustained)).containsExactly("C7");

        Sequence passing = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 60, 64, 67).chord(4, 4, 60, 64, 67).end()
                .part("Melody", 1).note(3.5, 0.5, 82).note(7.5, 0.5, 82)
                .build();
        assertThat(symbolsOf(passing)).containsExactly("C");
    }

    @Test
    @DisplayName("a bass note that holds under the chord and is not its root gives a slash chord")
    void anInversionIsPrintedAsASlashChord() {
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 4, 40).note(4, 4, 40).end()
                .part("Keys", 1).chord(0, 4, 60, 64, 67).chord(4, 4, 60, 64, 67)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C/E");
    }

    @Test
    @DisplayName("a walking bass under one chord does not produce a slash chord per step")
    void aMovingBassIsNotAnInversion() {
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0)
                // Starting on the third, not the root: a walk that begins on the
                // root agrees with it in the first span and would come back
                // without a slash even if the rule that every span must agree
                // were deleted.
                .note(0, 1, 40).note(1, 1, 43).note(2, 1, 36).note(3, 1, 43)
                .note(4, 1, 40).note(5, 1, 43).note(6, 1, 36).note(7, 1, 43).end()
                .part("Keys", 1).chord(0, 4, 60, 64, 67).chord(4, 4, 60, 64, 67)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C");
    }

    @Test
    @DisplayName("a chord holds while the texture thins to one of its notes")
    void aThinningTextureDoesNotRestateTheHarmony() {
        // Four beats of a stated C major, then four with nothing but its third
        // sustained. A lone E explains itself perfectly as an E chord, and is
        // the lowest thing sounding so it looks like a root too -- but it is one
        // note, and one note is worth less than a triad. Getting this wrong
        // gives a chart that changes to E and then, when that is thrown out for
        // stating nothing, to N.C. in the middle of a held chord.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 4, 36).end()
                .part("Keys", 1).chord(0, 4, 60, 64, 67).note(4, 4, 64)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.chords().chords()).extracting(
                        Chord::symbol, c -> c.startBeat().orElseThrow(),
                        c -> c.endBeat().orElseThrow())
                .containsExactly(org.assertj.core.api.Assertions.tuple("C", 0.0, 8.0));
    }

    @Test
    @DisplayName("with no bass part, the bass is the bottom of the texture and not all of it")
    void theBassIsTheBottomOfTheTexture() {
        // A first-inversion voicing on one keyboard part: E below G below C.
        // The bass is the E, so this is a C major in inversion; treating every
        // note as bass material would find no pitch class dominant and print a
        // root-position C.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 52, 55, 60).chord(4, 4, 52, 55, 60)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C/E");
    }

    @Test
    @DisplayName("the same four pitch classes are named from the bass under them")
    void theBassDecidesTheRoot() {
        // C, E, G and A. As a set they are equally a C major with an added sixth
        // and an A minor seventh, and no histogram can separate them -- which is
        // the ambiguity a chroma-based estimator is stuck with. The bass settles
        // it, and here it is not inferred from register but read off a part that
        // says it is the bass.
        assertThat(symbolsOf(fourNotesOver(36))).containsExactly("C");
        assertThat(symbolsOf(fourNotesOver(33))).containsExactly("Am7");
    }

    private static Sequence fourNotesOver(int bassPitch) {
        return MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 4, bassPitch).note(4, 4, bassPitch).end()
                .part("Keys", 1).chord(0, 4, 60, 64, 67, 69).chord(4, 4, 60, 64, 67, 69)
                .build();
    }

    @Test
    @DisplayName("a chord anticipated before the bar line is still written on the bar line")
    void anAnticipatedChordChangeLandsOnTheDownbeat() {
        // The push: the next chord arrives a fraction of a beat early, which is
        // how a great deal of pop is played and is not how it is written. Beat 3
        // holds a little more G than C, so on evidence alone the change belongs
        // there; a bar line is a cheap enough place to change that the small
        // margin does not buy it.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0)
                .chord(0, 3.4, 60, 64, 67)
                .chord(3.4, 4.6, 55, 59, 62)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.chords().chords()).extracting(
                        Chord::symbol, c -> c.startBeat().orElseThrow())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("C", 0.0),
                        org.assertj.core.api.Assertions.tuple("G", 4.0));
    }

    @Test
    @DisplayName("a bass on a note the chord does not contain gives no slash")
    void aBassOutsideTheChordIsNotASlash() {
        // A held D under a C triad. As a chart symbol that is C/D, a real and
        // common voicing -- but nothing here can tell it from a bass sitting on
        // a passing note, and `Chord.pitchClasses()` is computed from root and
        // quality and would not sound the D that the symbol promises. So the
        // estimator declines to write one, and this test is what says that is a
        // decision rather than an oversight.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 4, 38).note(4, 4, 38).end()
                .part("Keys", 1).chord(0, 4, 60, 64, 67).chord(4, 4, 60, 64, 67)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C");

        // The same shape with the bass moved onto a chord tone does get one, so
        // this is not passing because slashes never appear.
        Sequence inversion = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 4, 40).note(4, 4, 40).end()
                .part("Keys", 1).chord(0, 4, 60, 64, 67).chord(4, 4, 60, 64, 67)
                .build();

        assertThat(symbolsOf(inversion)).containsExactly("C/E");
    }

    @Test
    @DisplayName("a bass alternating root and fifth within the beat is not an inversion")
    void anAlternatingBassIsNotAnInversion() {
        // The um-pah figure: F and C under an F chord, an eighth each. Neither
        // is at the bottom for long enough to be the bass, and the one that
        // would win a bare count is the fifth -- so counting rather than
        // requiring dominance prints every bar of this as F/C.
        MidiFixtures.SequenceBuilder builder = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4);
        MidiFixtures.SequenceBuilder.PartBuilder bass = builder.part("Bass", 0);
        MidiFixtures.SequenceBuilder.PartBuilder keys = builder.part("Keys", 1);
        for (int beat = 0; beat < 8; beat++) {
            bass.note(beat, 0.5, 41).note(beat + 0.5, 0.5, 48);
        }
        keys.chord(0, 4, 65, 69, 72).chord(4, 4, 65, 69, 72);

        assertThat(symbolsOf(builder.build())).containsExactly("F");
    }

    @Test
    @DisplayName("a flat key signature spells roots with flats")
    void rootsFollowTheKeySignature() {
        // Two flats is B flat major. The same three pitch classes printed as
        // A sharp would be arithmetically right and wrong on the page.
        Sequence flat = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .keySignature(-2, Mode.MAJOR)
                .part("Keys", 0).chord(0, 4, 58, 62, 65).chord(4, 4, 58, 62, 65)
                .build();
        assertThat(symbolsOf(flat)).containsExactly("Bb");

        Sequence sharp = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .keySignature(3, Mode.MAJOR)
                .part("Keys", 0).chord(0, 4, 58, 62, 65).chord(4, 4, 58, 62, 65)
                .build();
        assertThat(symbolsOf(sharp)).containsExactly("A#");
    }

    @Test
    @DisplayName("an arpeggio states its triad even though one note sounds at a time")
    void anArpeggioIsStillAChord() {
        MidiFixtures.SequenceBuilder builder = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4);
        MidiFixtures.SequenceBuilder.PartBuilder keys = builder.part("Guitar", 0);
        int[] up = {60, 64, 67, 72};
        for (int bar = 0; bar < 4; bar++) {
            for (int i = 0; i < 4; i++) {
                keys.note(bar * 4 + i, 1, up[i]);
            }
        }

        assertThat(symbolsOf(builder.build())).containsExactly("C");
    }

    @Test
    @DisplayName("a declared bass part is the bass even when it is not the lowest note")
    void aDeclaredBassOutranksTheTexture() {
        // The keys reach down to C2, below the bass part's E3. Reading the
        // bottom of the texture calls this a root-position C; reading the part
        // that says it is the bass calls it the inversion it is. This is the
        // third of the three things the class javadoc claims a chroma cannot
        // carry, and the only test that depends on it.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 4, 52).note(4, 4, 52).end()
                .part("Keys", 1).chord(0, 4, 36, 60, 64, 67).chord(4, 4, 36, 60, 64, 67)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C/E");
    }

    @Test
    @DisplayName("a bass part taking a break does not change the chord")
    void aRestingBassPartIsNotAChordChange() {
        // Keys hold C-E-G-A for four bars; the bass plays C for two of them and
        // then stops, which is what a bassist does at a break. Nothing about the
        // harmony changes, and the chart must not say it did.
        //
        // These four pitch classes are the C6-versus-Am7 pair of #122, so the
        // root bonus from the bass is the only thing holding C over Am7. Read
        // the bass from the declared part alone and it goes silent at bar 3,
        // taking the bonus with it and splitting a held chord in two.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 8, 36).end()
                .part("Keys", 1)
                .chord(0, 8, 60, 64, 67, 69).chord(8, 8, 60, 64, 67, 69)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.chords().chords()).extracting(
                        Chord::symbol, c -> c.startBeat().orElseThrow(),
                        c -> c.endBeat().orElseThrow())
                .containsExactly(org.assertj.core.api.Assertions.tuple("C", 0.0, 16.0));
    }

    @Test
    @DisplayName("where a bass part rests, the bottom of the texture is the bass")
    void aRestingBassPartHandsOverToTheTexture() {
        // The same handover seen from the other side. The bass plays the root of
        // the first chord and then stops, and the second chord is voiced with its
        // third at the bottom. That inversion is only visible if the texture
        // takes over; read the declared part alone and the second chord has no
        // bass at all and prints in root position.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Bass", 0).note(0, 8, 36).end()
                .part("Keys", 1).chord(0, 8, 60, 64, 67).chord(8, 8, 57, 60, 65)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C", "F/A");
    }

    @Test
    @DisplayName("a meter change re-bars the decision grid from the bar it starts on")
    void meterChangesMoveTheGrid() {
        // Two bars of 4/4, then 6/8 -- three quarter beats to the bar and two
        // counted beats of a dotted quarter. The chord changes at quarter beat
        // 9.5, which is the second counted beat of the first 6/8 bar and lands
        // on no boundary the opening meter has. Reading the meter once bars the
        // whole piece in four, and puts this change at 10.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .timeSignatureAt(8, 6, 8)
                .part("Keys", 0)
                .chord(0, 8, 60, 64, 67)
                .chord(8, 1.5, 62, 65, 69)
                .chord(9.5, 4.5, 60, 64, 67)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.chords().chords()).extracting(
                        Chord::symbol, c -> c.startBeat().orElseThrow())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("C", 0.0),
                        org.assertj.core.api.Assertions.tuple("Dm", 8.0),
                        org.assertj.core.api.Assertions.tuple("C", 9.5));
    }

    @Test
    @DisplayName("a piece ending mid-bar keeps its last chord rather than overrunning")
    void aRaggedEndIsClippedToThePiece() {
        // Six and a half beats of 4/4: the last bar is short. The final span has
        // to be cut to the piece, or the last chord claims time the music does
        // not occupy and the seconds axis runs past the score's own duration.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 60, 64, 67).chord(4, 2.5, 62, 65, 69)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.chords().chords()).extracting(
                        Chord::symbol, c -> c.endBeat().orElseThrow())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("C", 4.0),
                        org.assertj.core.api.Assertions.tuple("Dm", 6.5));
        assertThat(score.chords().chords().getLast().endSeconds())
                .isEqualTo(score.durationSeconds());
    }

    @Test
    @DisplayName("two stretches that state nothing become one gap, not two")
    void adjacentGapsAreOneGap() {
        // Between two chords, two bars of two-note writing: a bare fifth, then a
        // bare third a step away. Each is discarded for stating no chord, and
        // they are different discards -- so without merging them the chart would
        // print N.C. twice in a row and imply a boundary that is not there.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0)
                .chord(0, 4, 60, 64, 67)
                .chord(4, 4, 62, 69)
                .chord(8, 4, 64, 71)
                .chord(12, 4, 55, 59, 62)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C", "N.C.", "G");
    }

    @Test
    @DisplayName("a chord before the first key signature is spelled by that signature")
    void chordsBeforeTheFirstKeySignatureStillFollowIt() {
        // A key signature declared part-way in is still the only statement the
        // file makes about how to spell, so a chord before it follows the same
        // one. Two flats is B flat major; A sharp would be arithmetically right
        // and wrong on the page.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .keySignatureAt(8, -2, Mode.MAJOR)
                .part("Keys", 0)
                .chord(0, 4, 58, 62, 65).chord(4, 4, 58, 62, 65)
                .chord(8, 4, 58, 62, 65).chord(12, 4, 58, 62, 65)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("Bb");
    }

    @Test
    @DisplayName("confidence separates a chord nobody would argue about from one they would")
    void confidenceReflectsTheMarginNotJustTheFit() {
        Score plain = TRANSCRIBER.transcribe(MidiFixtures.fourChordSong());
        List<Chord> chords = plain.chords().chords();
        // The silent lead-in is read from the file rather than inferred, so it
        // alone is allowed to be certain.
        assertThat(chords.getFirst().isNoChord()).isTrue();
        assertThat(chords.getFirst().confidence().value()).isEqualTo(1.0);
        // Every chord label is an inference over a vocabulary that is missing
        // qualities, so none of them may be.
        assertThat(chords.stream().filter(c -> !c.isNoChord()))
                .allSatisfy(c -> assertThat(c.confidence().value()).isBetween(0.85, 0.9));

        // The same four pitch classes that #122 is about. Nothing separates C6
        // from Am7 but the bass, and the number says so.
        Chord ambiguous = TRANSCRIBER.transcribe(fourNotesOver(33)).chords().chords().getFirst();
        assertThat(ambiguous.symbol()).isEqualTo("Am7");
        assertThat(ambiguous.confidence().value()).isLessThan(0.4);
    }

    @Test
    @DisplayName("one stray far-out event does not cost a song its harmony")
    void aStrayFarOutEventDoesNotDiscardTheChords() {
        // Eight beats of music and a tempo event a quarter of a million beats
        // later, which is a real shape: a file's declared length is where its
        // last event sits, sounding or not. Deciding a chord for every beat in
        // between is refused, but refusing on the declared length rather than
        // the sounding one would throw away harmony that is plainly there.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .tempoAt(250_000, 100)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 60, 64, 67).chord(4, 4, 62, 65, 69)
                .build();

        assertThat(symbolsOf(sequence)).containsExactly("C", "Dm");
    }

    @Test
    @DisplayName("a file that is almost entirely silence is imported but left without chords")
    void anAbsurdlyLongFileIsLeftAlone() {
        // Two notes a quarter of a million beats apart. The notes import fine;
        // deciding a chord for every beat between them would not.
        Sequence sequence = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 60, 64, 67).chord(250_000, 4, 60, 64, 67)
                .build();

        Score score = TRANSCRIBER.transcribe(sequence);
        assertThat(score.tracks()).hasSize(1);
        assertThat(score.tracks().get(0).size()).isEqualTo(6);
        assertThat(score.chords().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("harmony is read from every pitched part, not only the accompaniment")
    void allPitchedPartsContribute() {
        // No part states a triad on its own: the keys play a bare fifth and the
        // melody supplies the third that decides major from minor.
        Sequence major = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 60, 67).chord(4, 4, 60, 67).end()
                .part("Melody", 1).note(0, 4, 76).note(4, 4, 76)
                .build();
        assertThat(symbolsOf(major)).containsExactly("C");

        Sequence minor = MidiFixtures.sequence()
                .tempo(120)
                .timeSignature(4, 4)
                .part("Keys", 0).chord(0, 4, 60, 67).chord(4, 4, 60, 67).end()
                .part("Melody", 1).note(0, 4, 75).note(4, 4, 75)
                .build();
        assertThat(symbolsOf(minor)).containsExactly("Cm");
    }
}
