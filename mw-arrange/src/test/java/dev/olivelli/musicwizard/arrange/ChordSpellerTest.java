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

    /**
     * One tonic per pitch class, spelled the way a key of that name is written.
     *
     * <p>Not {@link #root}, whose sharps would make keys of C sharp major and
     * D sharp major -- real enough on paper and written by nobody, and their
     * chromatic windows run past what a single accidental can print.
     */
    private static final List<String> ALL_TONICS = List.of(
            "C4", "Db4", "D4", "Eb4", "E4", "F4", "F#4", "G4", "Ab4", "A4", "Bb4", "B4");

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
        @DisplayName("one chromatic chord does not flip the chart across the half turn")
        void aSingleChromaticChordDoesNotFlipTheChart() {
            // Round 1 of review, by execution: eight cycles of B F#m G#m E and
            // one C#7 reach the tie at six fifths either way, where the region
            // is exactly as close written from +6 as from -6 and the flat-first
            // scan took the flat. One chord in 33 turned every symbol on the
            // page into Cb Gb Abm Fb. The accidental count is what separates
            // them: 2 a cycle against 4.
            List<Chord> chords = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                chords.add(major("B4"));
                chords.add(major("F#4"));
                chords.add(minor("G#4"));
                chords.add(major("E4"));
            }
            chords.add(seventh("C#4"));

            assertThat(symbols(ChordSpeller.respell(progression(chords), Optional.empty())))
                    .startsWith("B", "F#", "G#m", "E").endsWith("C#7");
        }

        @Test
        @DisplayName("a chromatic chord is not paid for with accidentals on every other one")
        void theRegionDoesNotBuyClosenessWithAccidentals() {
            // Ranking distance before the accidental count answered this with
            // G# D# E#m C#: four accidentals a cycle instead of three, bought to
            // put the one A7 two steps nearer the region. E sharp minor is not a
            // chord any chart writes, and A7 is the commonest colour there is.
            List<Chord> chords = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                chords.add(major("G#4"));
                chords.add(major("D#4"));
                chords.add(minor("F4"));
                chords.add(major("C#4"));
            }
            chords.add(seventh("A4"));

            assertThat(symbols(ChordSpeller.respell(progression(chords), Optional.empty())))
                    .startsWith("Ab", "Eb", "Fm", "Db").endsWith("A7");
        }

        @Test
        @DisplayName("a detected key decides what the roots alone cannot")
        void aDetectedKeyDecides() {
            // F# C# G#m D# and Gb Db Abm Eb carry four accidentals either way
            // and are the same distance from their own regions, so nothing in
            // the roots can choose and the scan takes the flat. A written
            // six-sharp signature says which piece this is.
            ChordProgression tied = progression(
                    major("F#4"), major("C#4"), minor("G#4"), major("D#4"));
            Key fSharpMajor = Key.ofSeconds(PitchSpelling.parse("F#4"), Mode.MAJOR,
                    0, 8, Confidence.of(0.9));

            assertThat(symbols(ChordSpeller.respell(tied, Optional.empty())))
                    .containsExactly("Gb", "Db", "Abm", "Eb");
            assertThat(symbols(ChordSpeller.respell(tied, Optional.of(fSharpMajor))))
                    .containsExactly("F#", "C#", "G#m", "D#");
        }

        @Test
        @DisplayName("a key's raised fourth is sharp, whether the key is major or minor")
        void aKeyIsReadFromItsTonicRatherThanItsSignature() {
            // Both halves of round 2's finding, and of round 1's before it. A
            // chart of D, G and A has its roots centred a fifth flat of D, so
            // counting alone writes a passing G# diminished as Abdim -- under a
            // header reading "D major". Measuring from the signature instead of
            // the tonic does the same to A minor, whose leading-tone chord is
            // also a G# and whose signature is C major's.
            Key dMajor = Key.ofSeconds(PitchSpelling.parse("D4"), Mode.MAJOR,
                    0, 8, Confidence.of(0.9));
            Key aMinor = Key.ofSeconds(PitchSpelling.parse("A3"), Mode.MINOR,
                    0, 8, Confidence.of(0.9));

            ChordProgression inD = progression(
                    major("D4"), major("G4"), major("A4"), diminished("G#4"));
            assertThat(symbols(ChordSpeller.respell(inD, Optional.of(dMajor))))
                    .containsExactly("D", "G", "A", "G#dim");

            ChordProgression inAMinor = progression(
                    minor("A4"), minor("D4"), major("E4"), diminished("G#4"));
            assertThat(symbols(ChordSpeller.respell(inAMinor, Optional.of(aMinor))))
                    .containsExactly("Am", "Dm", "E", "G#dim");
            // And the count, which has no tonic to measure from, happens to get
            // this one right on its own -- it is the sparse chart above that it
            // cannot.
            assertThat(symbols(ChordSpeller.respell(inAMinor, Optional.empty())))
                    .containsExactly("Am", "Dm", "E", "G#dim");
        }

        @Test
        @DisplayName("every key writes its roots within five flat and six sharp of its tonic")
        void everyKeySpellsTheWholeChromaticWindow() {
            // The window TONIC_TO_ROOT_CENTRE is the middle of: the Neapolitan
            // five fifths flat of the tonic, the flat sixth, third and seventh
            // at -4, -3 and -2, the seven diatonic degrees at -1 to +5, and the
            // raised fourth at +6. Twelve consecutive positions, one per pitch
            // class -- so this asserts the design rather than a table of
            // expected names, and it holds for a minor key only because the
            // centre is measured from the tonic and not from the signature.
            //
            // Every degree is checked in every key. The full chromatic window is
            // checked only where the window itself is printable within one
            // accidental, which runs from E flat major to F sharp major: beyond
            // that the window asks for a double, the cap refuses, and the answer
            // is the nearest printable spelling instead -- the Neapolitan of D
            // flat major is a D natural rather than an E double flat, which is
            // what a chart writes anyway.
            for (String tonic : ALL_TONICS) {
                for (Mode mode : Mode.values()) {
                    Key key = Key.ofSeconds(PitchSpelling.parse(tonic), mode,
                            0, 8, Confidence.of(0.9));
                    int home = fifths(key.tonic());
                    boolean windowIsPrintable = home >= -3 && home <= 6;
                    int from = windowIsPrintable ? home - 5 : home - 1;
                    int to = windowIsPrintable ? home + 6 : home + 5;
                    for (int degree = from; degree <= to; degree++) {
                        Chord written = ChordSpeller.respell(
                                progression(major(root(Math.floorMod(degree * 7, 12)))),
                                Optional.of(key)).chords().get(0);
                        assertThat(fifths(written.root()))
                                .as("%s in %s", written.symbol(), key.displayName())
                                .isEqualTo(degree);
                    }
                }
            }
        }

        @Test
        @DisplayName("no root is ever written with a double accidental")
        void everyChartComesBackPrintable() {
            // Two things this holds off. The region search's distance repeats
            // every twelve steps, because moving the region a whole turn of the
            // circle moves every root with it, so C G Am F is as close written
            // from -12, where its roots are Dbb Abb Bbbm Gbb, as from 0. And in
            // a region as flat as A flat major's, a plain A7 is nearer Bbb than
            // A -- which is what an unbounded search printed. Every key against
            // every chromatic addition, which is where round 1 found the second.
            for (int tonic = 0; tonic < 12; tonic++) {
                for (int added = 0; added < 12; added++) {
                    List<Chord> chords = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        chords.add(major(root(tonic)));
                        chords.add(major(root(tonic + 7)));
                        chords.add(minor(root(tonic + 9)));
                        chords.add(major(root(tonic + 5)));
                    }
                    chords.add(seventh(root(tonic + added)));

                    assertThat(symbols(ChordSpeller.respell(
                            progression(chords), Optional.empty())))
                            .as("tonic %d plus %d", tonic, added)
                            .allSatisfy(symbol -> assertThat(symbol)
                                    .doesNotContain("##").doesNotContain("bb"));
                }
            }
        }
    }

    @Nested
    @DisplayName("what re-spelling leaves alone")
    class WhatSurvives {

        @Test
        @DisplayName("a bass that is not a chord tone is re-spelled from the region")
        void aForeignBassFollowsTheRegion() {
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
        @DisplayName("a bass that is a chord tone is spelled by its chord, not by the region")
        void aChordToneBassFollowsItsChord() {
            // Round 1 of review, by execution through the CLI: E/G# came out as
            // E/Ab, and A flat is not a note of E major. The region has nothing
            // to say about a note the chord above it already spells -- the third
            // of E is a G of some kind whatever the rest of the piece does.
            Chord slash = Chord.ofSeconds(PitchSpelling.parse("E4"), ChordQuality.MAJOR,
                    0, 1, Confidence.of(0.8)).withBass(PitchSpelling.parse("G#4"));
            ChordProgression chords = progression(
                    List.of(major("C4"), slash, minor("A4"), major("F4")));

            assertThat(symbols(ChordSpeller.respell(chords, Optional.empty())))
                    .containsExactly("C", "E/G#", "Am", "F");
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
        @DisplayName("a modulation is followed, key by key, rather than averaged")
        void eachChordIsWrittenFromTheKeyUnderIt() {
            // Round 3 of review, through the CLI: a MIDI modulating from B flat
            // to B major arrives here already spelled per span, because
            // SymbolicChordEstimator reads the key signature in force at each
            // chord. One region for the whole piece overwrote that and printed
            // the last chorus as Cb Gb Ab -- a stage that replaces a decision
            // has to be at least as fine-grained as the decision it replaces.
            List<Chord> chords = List.of(
                    Chord.ofSeconds(PitchSpelling.parse("A#4"), ChordQuality.MAJOR,
                            0, 1, Confidence.of(0.8)),
                    Chord.ofSeconds(PitchSpelling.parse("D#4"), ChordQuality.MAJOR,
                            1, 2, Confidence.of(0.8)),
                    Chord.ofSeconds(PitchSpelling.parse("B4"), ChordQuality.MAJOR,
                            2, 3, Confidence.of(0.8)),
                    Chord.ofSeconds(PitchSpelling.parse("E4"), ChordQuality.MAJOR,
                            3, 4, Confidence.of(0.8)));
            Score score = Score.empty(TempoMap.constant(120), 4.0)
                    .withChords(asGiven(chords))
                    .withKeys(List.of(
                            Key.ofSeconds(PitchSpelling.parse("Bb4"), Mode.MAJOR,
                                    0, 2, Confidence.of(0.9)),
                            Key.ofSeconds(PitchSpelling.parse("B4"), Mode.MAJOR,
                                    2, 4, Confidence.of(0.9))));

            assertThat(symbols(ChordSpeller.respell(score).chords()))
                    .containsExactly("Bb", "Eb", "B", "E");
        }

        @Test
        @DisplayName("a chord no key covers is written from the count, not from the nearest key")
        void aChordOutsideEveryKeyFallsBackToTheCount() {
            // A lead-in before the first key signature. Written from the key
            // that starts after it, this one would come out A# D# E# -- B
            // major's window reaches E sharp and the count does not go near it.
            List<Chord> chords = List.of(
                    Chord.ofSeconds(PitchSpelling.parse("A#4"), ChordQuality.MAJOR,
                            0, 1, Confidence.of(0.8)),
                    Chord.ofSeconds(PitchSpelling.parse("D#4"), ChordQuality.MAJOR,
                            1, 2, Confidence.of(0.8)),
                    Chord.ofSeconds(PitchSpelling.parse("F4"), ChordQuality.MAJOR,
                            2, 3, Confidence.of(0.8)),
                    Chord.ofSeconds(PitchSpelling.parse("B4"), ChordQuality.MAJOR,
                            3, 4, Confidence.of(0.8)));
            Score score = Score.empty(TempoMap.constant(120), 4.0)
                    .withChords(asGiven(chords))
                    .withKeys(List.of(Key.ofSeconds(PitchSpelling.parse("B4"), Mode.MAJOR,
                            3, 4, Confidence.of(0.9))));

            assertThat(symbols(ChordSpeller.respell(score).chords()))
                    .containsExactly("Bb", "Eb", "F", "B");
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

    private static Chord seventh(String root) {
        return Chord.ofSeconds(PitchSpelling.parse(root), ChordQuality.DOMINANT_SEVENTH,
                0, 1, Confidence.of(0.8));
    }

    private static Chord diminished(String root) {
        return Chord.ofSeconds(PitchSpelling.parse(root), ChordQuality.DIMINISHED,
                0, 1, Confidence.of(0.8));
    }

    /**
     * Where a written pitch sits on the line of fifths, C being zero.
     *
     * <p>The test's own copy on purpose: asserting a property of the answer
     * against the same table that produced it would assert nothing.
     */
    private static int fifths(PitchSpelling written) {
        int[] letterFifths = {0, 2, 4, -1, 1, 3, 5};
        return letterFifths[written.letter().diatonicStep()]
                + 7 * written.accidental().alteration();
    }

    /** A root a number of semitones above C4, spelled the way the estimator does. */
    private static String root(int semitonesAboveC) {
        String[] names = {"C4", "C#4", "D4", "D#4", "E4", "F4",
                "F#4", "G4", "G#4", "A4", "A#4", "B4"};
        return names[Math.floorMod(semitonesAboveC, 12)];
    }
}
