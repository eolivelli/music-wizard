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
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Mode;
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
 * How a piece's chord symbols come out written, which is #227.
 *
 * <p>The fixtures are spelled the way {@code ChordEstimator} spells -- every
 * black key a sharp -- because that is the input this stage exists for. A
 * fixture already written in flats would pass whatever this class did.
 */
class ChordSpellerTest {

    @Nested
    @DisplayName("the region the piece lives in")
    class TheRegion {

        @Test
        @DisplayName("a B flat major chart is written with flats, not sharps")
        void theIssuesOwnExample() {
            // The Karma Chameleon chart: I-V-vi-IV in B flat, which the audio
            // path saved as A# F Gm D# and printed that way on a real page.
            // Worth noting that counting accidentals cannot decide this -- A#
            // and Bb are one accidental each, as are D# and Eb, so both charts
            // cost two. What decides is that Bb F G Eb spans four steps of the
            // line of fifths and A# F G D# spans eleven.
            ChordProgression sharp = progression(
                    major("A#4"), major("F4"), minor("G4"), major("D#4"));

            assertThat(symbols(ChordSpeller.respell(sharp, Optional.empty())))
                    .containsExactly("Bb", "F", "Gm", "Eb");
        }

        @Test
        @DisplayName("a sharp piece stays sharp, however its roots arrived")
        void aSharpPieceIsNotFlattened() {
            // The other direction, and the one a fix that simply preferred flats
            // would get wrong. E major given with a flat root comes back sharp,
            // because the region and not the input spelling is what decides.
            ChordProgression mixed = progression(
                    major("E4"), major("B4"), minor("Db4"), major("A4"));

            assertThat(symbols(ChordSpeller.respell(mixed, Optional.empty())))
                    .containsExactly("E", "B", "C#m", "A");
        }

        @Test
        @DisplayName("the same harmony is written the same way however it arrived")
        void enharmonicInputsAgree() {
            ChordProgression sharp = progression(
                    major("A#4"), major("F4"), minor("G4"), major("D#4"));
            ChordProgression flat = progression(
                    major("Bb4"), major("F4"), minor("G4"), major("Eb4"));

            assertThat(symbols(ChordSpeller.respell(sharp, Optional.empty())))
                    .isEqualTo(symbols(ChordSpeller.respell(flat, Optional.empty())));
        }

        @Test
        @DisplayName("a root that sounds often outweighs one that sounds once")
        void theRegionIsWeightedByHowOftenARootSounds() {
            // Eight bars of B flat and E flat against a single chromatic F sharp.
            // Counting distinct pitch classes would let the one passing chord
            // drag the region sharp; counting spans does not, and the F sharp is
            // then written as the region's own G flat.
            List<Chord> chords = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                chords.add(major("A#4"));
                chords.add(major("D#4"));
            }
            chords.add(major("F#4"));

            List<String> written = symbols(
                    ChordSpeller.respell(progression(chords), Optional.empty()));
            assertThat(written).startsWith("Bb", "Eb").endsWith("Gb");
        }

        @Test
        @DisplayName("a no-chord span is not counted towards the region")
        void noChordSpansAreNotPriced() {
            // A no-chord span carries a placeholder root of C, and there are twice
            // as many of them here as there are chords. Priced, they would pull
            // the region towards C and print the third of this E major piece as
            // D flat minor.
            List<Chord> chords = new ArrayList<>();
            for (Chord chord : List.of(major("E4"), major("B4"), minor("C#4"), major("A4"))) {
                chords.add(chord);
                chords.add(Chord.noChord(0, 1, Confidence.of(0.8)));
                chords.add(Chord.noChord(0, 1, Confidence.of(0.8)));
            }

            assertThat(symbols(ChordSpeller.respell(progression(chords), Optional.empty())))
                    .containsExactly("E", "N.C.", "N.C.", "B", "N.C.", "N.C.",
                            "C#m", "N.C.", "N.C.", "A", "N.C.", "N.C.");
        }

        @Test
        @DisplayName("a detected key decides instead of counting")
        void aDetectedKeyWins() {
            // Written key signatures are evidence; a count is an inference from
            // one. These three roots sit exactly on the boundary where the two
            // spellings cost the same and lie the same distance from natural, so
            // counting alone falls back on its tie-break and writes them flat.
            // In A major they are the iii, the VI and the II, and the signature
            // says so outright.
            ChordProgression chords = progression(
                    minor("C#4"), major("F#4"), major("B4"));
            Key aMajor = Key.ofSeconds(PitchSpelling.parse("A3"), Mode.MAJOR,
                    0, 8, Confidence.of(0.9));

            assertThat(symbols(ChordSpeller.respell(chords, Optional.empty())))
                    .containsExactly("Dbm", "Gb", "Cb");
            assertThat(symbols(ChordSpeller.respell(chords, Optional.of(aMajor))))
                    .containsExactly("C#m", "F#", "B");
        }

        @Test
        @DisplayName("no root is ever written with a double accidental")
        void everyKeyComesBackPrintable() {
            // The tie-break the region search needs. Its cost repeats every
            // twelve steps, because moving the region one whole turn of the
            // circle moves every root with it, so cheapest alone allows C G Am F
            // to come back as Dbb Abb Bbbm Gbb -- as cheap, and unreadable.
            for (int tonic = 0; tonic < 12; tonic++) {
                ChordProgression iViViIv = progression(
                        major(root(tonic)), major(root(tonic + 7)),
                        minor(root(tonic + 9)), major(root(tonic + 5)));

                assertThat(symbols(ChordSpeller.respell(iViViIv, Optional.empty())))
                        .allSatisfy(symbol -> assertThat(symbol)
                                .doesNotContain("##").doesNotContain("bb"));
            }
        }
    }

    @Nested
    @DisplayName("what re-spelling leaves alone")
    class WhatSurvives {

        @Test
        @DisplayName("a slash chord's bass is re-spelled with its root")
        void theBassMovesToo() {
            // Left out, this prints Bb/D# -- a chord and its own bass written
            // from two different regions, which is worse than either alone.
            Chord slash = Chord.ofSeconds(PitchSpelling.parse("A#4"), ChordQuality.MAJOR,
                    0, 1, Confidence.of(0.8)).withBass(PitchSpelling.parse("D#4"));
            ChordProgression chords = progression(
                    List.of(slash, major("F4"), minor("G4"), major("D#4")));

            assertThat(symbols(ChordSpeller.respell(chords, Optional.empty())))
                    .containsExactly("Bb/Eb", "F", "Gm", "Eb");
        }

        @Test
        @DisplayName("the quality, the timing on both axes and the confidence are untouched")
        void onlyTheSpellingChanges() {
            Chord estimated = new Chord(PitchSpelling.parse("A#4"), ChordQuality.DOMINANT_SEVENTH,
                    Optional.empty(), 1.5, 3.25, Optional.of(3.0), Optional.of(6.5),
                    Confidence.of(0.37));

            Chord written = ChordSpeller.respell(
                    asGiven(List.of(estimated,
                            Chord.ofSeconds(PitchSpelling.parse("D#4"), ChordQuality.MAJOR,
                                    3.25, 4.25, Confidence.of(0.8)),
                            Chord.ofSeconds(PitchSpelling.parse("F4"), ChordQuality.MAJOR,
                                    4.25, 5.25, Confidence.of(0.8)))),
                    Optional.empty()).chords().get(0);

            assertThat(written.root()).isEqualTo(PitchSpelling.parse("Bb4"));
            assertThat(written.quality()).isEqualTo(ChordQuality.DOMINANT_SEVENTH);
            assertThat(written.startSeconds()).isEqualTo(1.5);
            assertThat(written.endSeconds()).isEqualTo(3.25);
            assertThat(written.startBeat()).contains(3.0);
            assertThat(written.endBeat()).contains(6.5);
            assertThat(written.confidence().value()).isEqualTo(0.37);
        }

        @Test
        @DisplayName("a root outside MIDI range is printed as it was given")
        void anUnreachableRootIsLeftAlone() {
            // B sharp in octave 9 sounds above MIDI 127, where no spelling lands
            // in a writable octave. Nothing in the pipeline writes a root there
            // -- estimators use octave 4 -- but a score is read back from a file.
            // Printing the symbol as given beats failing the render over it.
            Chord unreachable = Chord.ofSeconds(new PitchSpelling(
                    dev.olivelli.musicwizard.core.model.NoteLetter.B,
                    dev.olivelli.musicwizard.core.model.Accidental.SHARP, 9),
                    ChordQuality.MAJOR, 0, 1, Confidence.of(0.8));
            ChordProgression chords = progression(
                    List.of(unreachable, major("A#4"), major("D#4")));

            assertThatCode(() -> ChordSpeller.respell(chords, Optional.empty()))
                    .doesNotThrowAnyException();
            assertThat(symbols(ChordSpeller.respell(chords, Optional.empty())))
                    .containsExactly("B#", "Bb", "Eb");
        }

        @Test
        @DisplayName("a score with no harmony comes back as it went in")
        void anEmptyProgressionPassesThrough() {
            Score empty = Score.empty(TempoMap.constant(120), 8.0);

            assertThat(ChordSpeller.respell(empty)).isSameAs(empty);
            assertThat(ChordSpeller.respell(ChordProgression.empty(), Optional.empty()))
                    .isEqualTo(ChordProgression.empty());
        }

        @Test
        @DisplayName("a score keeps everything but its chords")
        void theRestOfTheScoreSurvives() {
            Score score = Score.empty(TempoMap.constant(120), 8.0)
                    .withMetadata("Karma Chamaleon", "Culture Club")
                    .withChords(progression(major("A#4"), major("F4")));

            Score written = ChordSpeller.respell(score);

            assertThat(written.title()).contains("Karma Chamaleon");
            assertThat(written.artist()).contains("Culture Club");
            assertThat(written.tempoMap()).isEqualTo(score.tempoMap());
            assertThat(symbols(written.chords())).containsExactly("Bb", "F");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static List<String> symbols(ChordProgression chords) {
        return chords.chords().stream().map(Chord::symbol).toList();
    }

    private static ChordProgression progression(Chord... chords) {
        return progression(List.of(chords));
    }

    /**
     * A progression of one-second spans, one per chord in the order given.
     *
     * <p>The timing is supplied here rather than by each fixture because
     * {@link ChordProgression} refuses chords that overlap, and none of these
     * tests is about when a chord sounds. {@link #asGiven} is for the one that
     * is.
     */
    private static ChordProgression progression(List<Chord> chords) {
        List<Chord> inSequence = new ArrayList<>(chords.size());
        for (int i = 0; i < chords.size(); i++) {
            Chord chord = chords.get(i);
            inSequence.add(new Chord(chord.root(), chord.quality(), chord.bass(),
                    i, i + 1.0, Optional.empty(), Optional.empty(), chord.confidence()));
        }
        return new ChordProgression(inSequence, Confidence.of(0.8));
    }

    /** A progression keeping the timing its chords were built with. */
    private static ChordProgression asGiven(List<Chord> chords) {
        return new ChordProgression(chords, Confidence.of(0.8));
    }

    private static Chord major(String root) {
        return Chord.ofSeconds(PitchSpelling.parse(root), ChordQuality.MAJOR,
                0, 1, Confidence.of(0.8));
    }

    private static Chord minor(String root) {
        return Chord.ofSeconds(PitchSpelling.parse(root), ChordQuality.MINOR,
                0, 1, Confidence.of(0.8));
    }

    /** A root a number of semitones above C4, spelled the way the estimator does. */
    private static String root(int semitonesAboveC) {
        String[] names = {"C4", "C#4", "D4", "D#4", "E4", "F4",
                "F#4", "G4", "G#4", "A4", "A#4", "B4"};
        return names[Math.floorMod(semitonesAboveC, 12)];
    }
}
