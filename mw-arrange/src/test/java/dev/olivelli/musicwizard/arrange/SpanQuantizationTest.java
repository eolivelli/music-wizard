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
import static org.assertj.core.api.Assertions.within;

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
import dev.olivelli.musicwizard.core.model.Section;
import dev.olivelli.musicwizard.core.model.SectionKind;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The half of the model that #91 left in seconds: chords, sections and keys.
 *
 * <p>Every fixture here is deliberately <em>off</em> the grid, because a span
 * already on it is snapped correctly by any rule at all -- including doing
 * nothing. Where a test does want an already-aligned span, it says so and the
 * point of the test is that nothing moved.
 */
class SpanQuantizationTest {

    private static final double BPM = 120;

    @Nested
    @DisplayName("a chord symbol sits on a counted beat")
    class Chords {

        @Test
        @DisplayName("a change detected just off the beat is written on it")
        void aChordSnapsToTheNearestCountedBeat() {
            // 120 BPM in 4/4: a beat is half a second, a bar is two. The C is
            // heard 30 ms late and the G 40 ms early, which is inside what any
            // chroma-based estimator can resolve.
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.03, 2.04), chord("G4", 2.04, 3.96));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(beats(quantized)).containsExactly("C 0.0..4.0", "G 4.0..8.0");
        }

        @Test
        @DisplayName("the seconds are left exactly as they were")
        void secondsAreNotTouched() {
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.03, 2.04), chord("G4", 2.04, 3.96));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(quantized.get(0).startSeconds()).isEqualTo(0.03);
            assertThat(quantized.get(0).endSeconds()).isEqualTo(2.04);
            assertThat(quantized.get(1).endSeconds()).isEqualTo(3.96);
            // Asserted here as well as in the test above, because a pass that
            // did nothing at all would satisfy the three lines before this one.
            assertThat(quantized).allSatisfy(c -> assertThat(c.isQuantized()).isTrue());
        }

        @Test
        @DisplayName("in 6/8 the beat it lands on is the dotted quarter, not the quarter")
        void aCompoundBarCountsInDottedQuarters() {
            // Quarter beat 1 is the second of the six eighths, which is not a
            // place harmony changes; the counted beat in 6/8 is one and a half
            // quarters and this puts the change there.
            //
            // A hand-built boundary rather than one the audio path produces:
            // TempoMap.fromBeatTimes takes the meter's own counted beat as the
            // pulse, so a tracked beat in 6/8 is already a dotted quarter and
            // that path cannot state this position. What can is a supplied
            // --tempo disagreeing with the tracked pulse, or any other producer
            // whose spans are finer than the meter's beat.
            Score score = chordsOnly(sixEight(),
                    chord("C4", 0.0, 0.5), chord("F4", 0.5, 1.5));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(beats(quantized)).containsExactly("C 0.0..1.5", "F 1.5..3.0");
        }

        @Test
        @DisplayName("a chord already on a counted beat is not moved at all")
        void anAlignedChordIsUnmoved() {
            // Both shapes of "already aligned" in one fixture, because only one
            // of them exercises anything. The first is what #115 produces --
            // one decision per counted beat, carried on the beat axis -- and it
            // has to come through untouched. The second states the same
            // positions in seconds only, and the pass has to arrive at exactly
            // the doubles the first was handed.
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.0, 2.0).quantizedTo(0, 4),
                    chord("F4", 2.0, 4.0));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(quantized.get(0).startBeat()).contains(0.0);
            assertThat(quantized.get(0).endBeat()).contains(4.0);
            assertThat(quantized.get(1).startBeat()).contains(4.0);
            assertThat(quantized.get(1).endBeat()).contains(8.0);
        }

        @Test
        @DisplayName("a beat position already carried wins over the seconds beside it")
        void theCarriedPositionIsWhatIsSnapped() {
            // Seconds and beats disagree, which a hand-assembled score can do
            // and a round trip through a tempo map with a rounding step will do
            // by an ulp. The beat axis is what the notation stage reads, so it
            // is the axis that is put on the grid.
            Score score = chordsOnly(fourFour(),
                    chord("C4", 20.0, 24.0).quantizedTo(1.7, 5.3));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(quantized.get(0).startBeat()).contains(2.0);
            assertThat(quantized.get(0).endBeat()).contains(5.0);
        }

        @Test
        @DisplayName("a printed symbol is never further than half a counted beat from its change")
        void aPlacedSymbolStaysWithinHalfACountedBeat() {
            // The bound the class claims for #158, measured rather than argued.
            // A chord symbol may be printed before the harmony arrives -- that
            // is how an anticipation lands on the downbeat it anticipates -- and
            // what makes it acceptable rather than a lie is that it cannot be
            // printed further out than half the counted beat of the bar the
            // change falls in. Prose asserting that is prose; this is the
            // measurement, over four meters plus a map that changes meter
            // underneath the progression, with the clamp that resolves the
            // tolerated overlap exercised in half the trials.
            //
            // Not a restatement of the snapping rule: the placed position comes
            // from max(snap(start), furthestEnd), so a boundary can be pushed
            // past its own nearest beat by a neighbour, and that path has no
            // rounding bound of its own to appeal to.
            java.util.Random random = new java.util.Random(20260729);
            List<TempoMap> maps = List.of(
                    TempoMap.constant(BPM, TimeSignature.FOUR_FOUR),
                    TempoMap.constant(BPM, TimeSignature.SIX_EIGHT),
                    TempoMap.constant(BPM, new TimeSignature(7, 8)),
                    TempoMap.constant(BPM, TimeSignature.THREE_FOUR),
                    TempoMap.constant(BPM, new TimeSignature(7, 8))
                            .withMeterChange(1, TimeSignature.FOUR_FOUR)
                            .withMeterChange(3, TimeSignature.SIX_EIGHT));
            int placed = 0;
            int withdrawn = 0;
            for (TempoMap tempoMap : maps) {
                for (int trial = 0; trial < 200; trial++) {
                    List<Chord> spans = new ArrayList<>();
                    double at = random.nextDouble() * 0.5;
                    for (int i = 0; i < 6; i++) {
                        // Between about one and three counted beats at 120 BPM,
                        // so most trials place and some do not.
                        double next = at + 0.4 + random.nextDouble() * 1.1;
                        // Half the trials hand the pass the microsecond of
                        // overlap ChordProgression tolerates.
                        double start = i > 0 && trial % 2 == 0 ? at - 4e-7 : at;
                        spans.add(chord(i % 2 == 0 ? "C4" : "G4", start, next));
                        at = next;
                    }
                    QuantizedScore quantized =
                            Quantizer.quantize(chordsOnly(tempoMap, spans.toArray(new Chord[0])));
                    if (quantized.unplaceableChords() > 0) {
                        withdrawn++;
                        continue;
                    }
                    for (Chord placedChord : quantized.score().chords().chords()) {
                        double heard = tempoMap.secondsToBeats(placedChord.startSeconds());
                        double unit = tempoMap.timeSignatureAtBar(
                                tempoMap.toMusicalTime(heard).bar()).beatUnitQuarters();
                        assertThat(Math.abs(placedChord.startBeat().orElseThrow() - heard))
                                .as("%s printed at %s, heard at %s", placedChord.symbol(),
                                        placedChord.startBeat().orElseThrow(), heard)
                                .isLessThanOrEqualTo(unit / 2 + 1e-9);
                        placed++;
                    }
                }
            }
            // Both populations stated, so the sweep cannot pass by placing
            // nothing or by withdrawing everything.
            assertThat(placed).isGreaterThan(3000);
            assertThat(withdrawn).isGreaterThan(0);
        }

        @Test
        @DisplayName("a progression that gains beats is one the chart may bar from")
        void theProgressionReportsItselfQuantized() {
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.03, 2.04), chord("G4", 2.04, 3.96));

            assertThat(score.chords().isQuantized()).isFalse();
            assertThat(Quantizer.quantize(score).score().chords().isQuantized()).isTrue();
        }
    }

    @Nested
    @DisplayName("a chord with nowhere to go costs the beat axis, never itself")
    class Collapse {

        @Test
        @DisplayName("a chord shorter than the beat it falls in is not deleted")
        void aCollapsedChordIsNotDeleted() {
            // Three one-quarter-beat spans in 6/8, where a counted beat is one
            // and a half. The middle one has both its boundaries rounded onto
            // the same dotted quarter and cannot be given a length -- so no
            // chord here gets beats, and all three are still named.
            Score score = chordsOnly(sixEight(),
                    chord("C4", 0.0, 0.5), chord("D4", 0.5, 1.0), chord("F4", 1.0, 1.5));

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(symbols(quantized.score().chords().chords()))
                    .containsExactly("C", "D", "F");
            assertThat(quantized.score().chords().isQuantized()).isFalse();
            assertThat(quantized.unplaceableChords()).isEqualTo(1);
            assertThat(quantized.isFullyQuantized()).isFalse();
        }

        @Test
        @DisplayName("the seconds of a progression left off the beat axis are untouched")
        void theFallbackKeepsTheSecondsExactly() {
            // The pass's standing promise is that it adds musical timing and
            // takes nothing away. Withdrawing the progression from the beat axis
            // has to leave it exactly as it arrived, or the fallback is its own
            // kind of loss.
            Score score = chordsOnly(sixEight(),
                    chord("C4", 0.0, 0.5), chord("D4", 0.5, 1.0), chord("F4", 1.0, 1.5));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(quantized).isEqualTo(score.chords().chords());
        }

        @Test
        @DisplayName("a forced tempo that disagrees by a factor does not rename the progression")
        void aForcedTempoDoesNotRenameTheProgression() {
            // #157, and the reason the ranking above falls the way it does.
            // Material heard at 120 read against a supplied --tempo 60: every
            // chord is half a counted beat, so every boundary is a tie and every
            // other chord used to collapse and be dropped. The chart then named
            // I-vi-I-vi over music playing I-V-vi-IV, with nothing to say so.
            Score score = chordsOnly(TempoMap.constant(60, TimeSignature.FOUR_FOUR),
                    chord("C4", 0.00, 0.50), chord("G4", 0.50, 1.00),
                    chord("A4", 1.00, 1.50), chord("F4", 1.50, 2.00),
                    chord("C4", 2.00, 2.50), chord("G4", 2.50, 3.00),
                    chord("A4", 3.00, 3.50), chord("F4", 3.50, 4.00));

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(symbols(quantized.score().chords().chords()))
                    .containsExactly("C", "G", "A", "F", "C", "G", "A", "F");
            assertThat(quantized.score().chords().isQuantized()).isFalse();
            // Four of the eight, which is what a caller needs to tell a user
            // that the tempo they supplied is half the one the harmony moves at.
            assertThat(quantized.unplaceableChords()).isEqualTo(4);
        }

        @Test
        @DisplayName("the chord at the downbeat is the one sounding there, not the next")
        void theDownbeatKeepsTheChordThatSounds() {
            // #158. C sounds at the downbeat and used to be dropped for being
            // shorter than a beat, whereupon D snapped back onto the position it
            // had vacated and the chart printed D over the downbeat's C.
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.0, 0.2), chord("D4", 0.2, 0.5), chord("G4", 0.5, 2.0));

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(symbols(quantized.score().chords().chords()))
                    .containsExactly("C", "D", "G");
            assertThat(quantized.score().chords().chords().get(0).startSeconds()).isZero();
            assertThat(quantized.unplaceableChords()).isEqualTo(1);
        }

        @Test
        @DisplayName("a collapsed first chord costs the axis like any other")
        void aCollapsedFirstChordIsKeptToo() {
            Score score = chordsOnly(sixEight(),
                    chord("D4", 0.0, 0.3), chord("F4", 0.3, 1.5));

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(symbols(quantized.score().chords().chords())).containsExactly("D", "F");
            assertThat(quantized.unplaceableChords()).isEqualTo(1);
        }

        @Test
        @DisplayName("only the chords come off the beat axis; the notes and structure stay on it")
        void theFallbackReachesTheChordsAndNothingElse() {
            // The all-or-nothing is forced by ChordProgression.isQuantized()
            // being one verdict, and that verdict covers chords alone. A pass
            // that withdrew the notes or the sections as well would be treating
            // an unplaceable chord as a failure of the whole quantization, which
            // it is not.
            TempoMap tempoMap = fourFour();
            Performance performance = new Performance(tempoMap, 21);
            performance.section(0, 8).section(8, 16);
            performance.run(60, 0.5, Performance.evenly(4, 4.0, 8));
            ChordProgression chords = new ChordProgression(List.of(
                    chord("C4", 0.0, 0.2), chord("D4", 0.2, 0.5),
                    chord("G4", 0.5, 8.0)), Confidence.CERTAIN);

            QuantizedScore quantized = Quantizer.quantize(performance.score(chords));

            assertThat(quantized.unplaceableChords()).isEqualTo(1);
            assertThat(quantized.score().chords().isQuantized()).isFalse();
            assertThat(quantized.score().tracks())
                    .allSatisfy(t -> assertThat(t.isQuantized()).isTrue());
            assertThat(quantized.score().sections())
                    .allSatisfy(s -> assertThat(s.isQuantized()).isTrue());
            assertThat(quantized.grids()).isNotEmpty();
        }

        @Test
        @DisplayName("stale beats on a withdrawn progression are taken off, not left behind")
        void theFallbackStripsStaleBeats() {
            // The mirror of aCollapsedSpanDoesNotKeepBeatsFromNowhere, and
            // reachable the same single way: a hand-assembled score whose
            // carried beats were already off the grid. Not from re-quantizing
            // against a corrected tempo -- a carried position wins over the
            // seconds beside it, so a progression this pass placed comes back
            // through it unchanged whatever map it is read against, which is
            // #171 rather than this.
            //
            // A progression left half on the beat axis would be the worst of
            // both: isQuantized() false, so no consumer reads the beats, and
            // beats sitting on positions that no longer mean anything for the
            // next reader who does.
            TempoMap tempoMap = fourFour();
            Chord stale = chord("C4", 0.0, 1.0).quantizedTo(0.0, 0.3);
            Chord alongside = chord("G4", 1.0, 2.0).quantizedTo(0.3, 1.0);
            Score score = chordsOnly(tempoMap, stale, alongside);

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(symbols(quantized.score().chords().chords()))
                    .containsExactly("C", "G");
            assertThat(quantized.score().chords().chords())
                    .allSatisfy(c -> assertThat(c.isQuantized()).isFalse());
            assertThat(quantized.unplaceableChords()).isEqualTo(1);
        }

        @Test
        @DisplayName("a progression left in seconds stays exactly there on a second pass")
        void theFallbackIsIdempotent() {
            Score score = chordsOnly(sixEight(),
                    chord("C4", 0.0, 0.5), chord("D4", 0.5, 1.0), chord("F4", 1.0, 1.5));

            QuantizedScore once = Quantizer.quantize(score);
            QuantizedScore twice = Quantizer.quantize(once.score());

            // Stated rather than only compared, because two passes that both
            // dropped the same chord would agree with each other just as
            // happily: what has to survive the second pass is the fallback, not
            // merely stability.
            assertThat(once.unplaceableChords()).isEqualTo(1);
            assertThat(twice.unplaceableChords()).isEqualTo(1);
            assertThat(twice.score().chords().chords())
                    .isEqualTo(once.score().chords().chords());
        }

        @Test
        @DisplayName("whatever goes in comes out: same chords, same order, only the beats differ")
        void theProgressionIsNeverEditedOnlyAnnotated() {
            // The whole of #157 in one property, and the one claim the rest of
            // the argument rests on: this pass annotates a progression, it does
            // not edit one. Everything else about the change is a judgement
            // about which chart is less bad; this is the part that is simply
            // true or not, so it is swept rather than reasoned.
            //
            // Deliberately hostile input: spans from a twentieth of a beat to
            // three beats, so most trials collapse something; the tolerated
            // overlap in a third of them; meters whose counted beat is a
            // quarter, a dotted quarter and an eighth, and a map that changes
            // between them underneath the progression.
            java.util.Random random = new java.util.Random(157158);
            List<TempoMap> maps = List.of(
                    TempoMap.constant(BPM, TimeSignature.FOUR_FOUR),
                    TempoMap.constant(BPM, TimeSignature.SIX_EIGHT),
                    TempoMap.constant(BPM, new TimeSignature(7, 8)),
                    TempoMap.constant(60, TimeSignature.FOUR_FOUR),
                    TempoMap.constant(BPM, new TimeSignature(7, 8))
                            .withMeterChange(1, TimeSignature.FOUR_FOUR)
                            .withMeterChange(2, TimeSignature.SIX_EIGHT));
            int withdrawn = 0;
            int placed = 0;
            for (TempoMap tempoMap : maps) {
                for (int trial = 0; trial < 300; trial++) {
                    List<Chord> in = new ArrayList<>();
                    double at = random.nextDouble() * 0.3;
                    int count = 2 + random.nextInt(7);
                    for (int i = 0; i < count; i++) {
                        double next = at + 0.025 + random.nextDouble() * 1.5;
                        double start = i > 0 && trial % 3 == 0 ? at - 4e-7 : at;
                        in.add(chord(ROOTS.get(i % ROOTS.size()), start, next));
                        at = next;
                    }

                    QuantizedScore out = Quantizer.quantize(
                            chordsOnly(tempoMap, in.toArray(new Chord[0])));
                    List<Chord> published = out.score().chords().chords();

                    assertThat(published)
                            .as("chord for chord, in order, seconds and all")
                            .hasSameSizeAs(in);
                    for (int i = 0; i < in.size(); i++) {
                        assertThat(withoutBeats(published.get(i)))
                                .as("chord %d of %d, %s", i, in.size(), tempoMap)
                                .isEqualTo(withoutBeats(in.get(i)));
                    }
                    if (out.unplaceableChords() > 0) {
                        withdrawn++;
                        assertThat(published).allSatisfy(
                                c -> assertThat(c.isQuantized()).isFalse());
                    } else {
                        placed++;
                        assertThat(published).allSatisfy(
                                c -> assertThat(c.isQuantized()).isTrue());
                    }
                }
            }
            // Both outcomes reached in quantity, so the sweep cannot pass by
            // exercising only the easy half.
            assertThat(placed).isGreaterThan(200);
            assertThat(withdrawn).isGreaterThan(200);
        }

        @Test
        @DisplayName("the count is of collapses, and is not how much harmony is finer than the pulse")
        void theCountIsOfCollapsesAndNotOfShortChords() {
            // The javadoc used to prescribe a sentence a caller might print --
            // "four of your eight chords are shorter than a beat at this tempo"
            // -- from a number that does not measure that. Pinned as the
            // property rather than as golden numbers: five memorised counts
            // would change together the moment the clamp's phase behaviour did,
            // and a reader could not tell whether the count had broken or merely
            // moved. What must not change is that the two quantities differ.
            for (double tempo : List.of(60.0, 40.0, 30.0, 24.0, 15.0)) {
                TempoMap tempoMap = TempoMap.constant(tempo, TimeSignature.FOUR_FOUR);
                Score score = chordsOnly(tempoMap, everyHalfSecond(0.0));

                QuantizedScore quantized = Quantizer.quantize(score);

                long shorterThanTheirBeat = score.chords().chords().stream()
                        .filter(c -> tempoMap.secondsToBeats(c.endSeconds())
                                - tempoMap.secondsToBeats(c.startSeconds()) < 1.0)
                        .count();
                assertThat(shorterThanTheirBeat)
                        .as("the fixture only tests anything if every chord is short at %s BPM",
                                tempo)
                        .isEqualTo(8);
                assertThat(quantized.unplaceableChords())
                        .as("the count at %s BPM is not the number of short chords", tempo)
                        .isLessThan((int) shorterThanTheirBeat);
            }

            // And "fewer" is not a rule either -- two chords too short to place
            // at all give a count equal to the number of short chords. So the
            // relation is not an offset to be corrected for; the quantities are
            // simply different, which is the whole point.
            QuantizedScore bothTooShort = Quantizer.quantize(chordsOnly(fourFour(),
                    chord("C4", 0.0, 0.01), chord("G4", 0.01, 0.02)));
            assertThat(bothTooShort.unplaceableChords()).isEqualTo(2);
        }

        @Test
        @DisplayName("the same disagreement gives a different count at a different phase")
        void theCountMovesWithThePhaseAndNotOnlyTheDisagreement() {
            // The mechanism the javadoc names for why the count is not a measure
            // of the disagreement: the clamp resolves an overlapping boundary
            // forward, so whether two short chords in a row leave the second one
            // placeable depends on where in the beat the progression starts.
            // Same tempo, same spans, same disagreement -- four phases, and one
            // of them differs. An earlier version argued this from two tempos
            // instead and got the arithmetic wrong: 30 and 24 BPM are a factor
            // of four and five against the harmony, not a doubling.
            TempoMap tempoMap = TempoMap.constant(40, TimeSignature.FOUR_FOUR);
            List<Integer> counts = new ArrayList<>();
            for (double offset : List.of(0.0, 0.25, 0.50, 0.75)) {
                counts.add(Quantizer.quantize(chordsOnly(tempoMap, everyHalfSecond(offset)))
                        .unplaceableChords());
            }

            assertThat(counts).containsExactly(5, 5, 5, 6);
        }

        @Test
        @DisplayName("the bound does not cover a progression re-read against another map")
        void theBoundDoesNotCoverACarriedProgression() {
            // The carve-out on the class's half-beat bound, pinned so that
            // deleting the words would fail rather than merely overstate. A
            // carried beat wins over the seconds, so a placed progression read
            // against a corrected --tempo keeps the positions it was given and
            // the clock is never consulted: nothing collapses, nothing is
            // reported, and the chords are printed a long way from where they
            // sound. That is #171 rather than a corner of #158, and this test
            // exists to stop the bound being read as covering it.
            TempoMap fast = fourFour();
            Score placed = Quantizer.quantize(chordsOnly(fast,
                    chord("C4", 0.0, 1.0), chord("G4", 1.0, 2.0),
                    chord("A4", 2.0, 3.0), chord("F4", 3.0, 4.0))).score();

            TempoMap halved = TempoMap.constant(60, TimeSignature.FOUR_FOUR);
            Score reread = new Score(placed.title(), placed.artist(), halved,
                    placed.beatGrid(), placed.keys(), placed.sections(), placed.tracks(),
                    placed.chords(), placed.lyrics(), placed.durationSeconds());
            QuantizedScore requantized = Quantizer.quantize(reread);

            assertThat(requantized.unplaceableChords())
                    .as("nothing collapses, so nothing warns")
                    .isZero();
            Chord last = requantized.score().chords().chords().get(3);
            double printed = last.startBeat().orElseThrow();
            double sounds = halved.secondsToBeats(last.startSeconds());
            assertThat(printed).isEqualTo(6.0);
            assertThat(sounds).isEqualTo(3.0);
            assertThat(printed - sounds)
                    .as("three counted beats out, six times the bound the class states")
                    .isEqualTo(3.0);
        }

        @Test
        @DisplayName("a change on a rounding midpoint is written on the beat it anticipates")
        void aChordChangeOnAMidpointGoesForward() {
            // The tie rule, pinned on a fixture that still reaches the beat
            // axis. An un-syncopated eighth-note anticipation sits exactly on
            // the midpoint of its rounding cell, and belongs on the beat ahead:
            // that is what a chart prints. Math.rint breaks a tie to the even
            // step -- 2.5 down to beat 2, 6.5 down to beat 6 -- so under it half
            // the anticipations in a progression come out a beat early, the half
            // chosen by the parity of the beat index. Two of them here, one from
            // an odd cell and one from an even, so a rule that alternates cannot
            // pass by luck.
            TempoMap tempoMap = fourFour();
            Score score = chordsOnly(tempoMap,
                    chord("C4", at(tempoMap, 0), at(tempoMap, 2.5)),
                    chord("G4", at(tempoMap, 2.5), at(tempoMap, 6.5)),
                    chord("F4", at(tempoMap, 6.5), at(tempoMap, 12)));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(beats(quantized))
                    .containsExactly("C 0.0..3.0", "G 3.0..7.0", "F 7.0..12.0");
        }

        @Test
        @DisplayName("a chord as long as its counted beat is always placed, at any offset")
        void nothingAsLongAsItsOwnUnitIsEverDropped() {
            // The bound on what the merge can take. Two positions less than a
            // unit apart can share a rounding cell; two a unit or more apart
            // cannot, whatever the offset. Swept over four hundred offsets in a
            // simple, a compound and an odd meter, and over the eight bars a
            // pair of chords can be placed in rather than only the first.
            for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.SIX_EIGHT,
                    new TimeSignature(7, 8), TimeSignature.THREE_FOUR)) {
                TempoMap tempoMap = TempoMap.constant(BPM, meter);
                double secondsPerUnit = meter.beatUnitQuarters() * 60 / BPM;
                for (int step = 0; step < 100; step++) {
                    double from = step * secondsPerUnit / 100
                            + 4 * meter.quarterBeatsPerBar() * 60 / BPM;
                    Score score = chordsOnly(tempoMap,
                            chord("C4", from, from + secondsPerUnit),
                            chord("G4", from + secondsPerUnit, from + 2 * secondsPerUnit));

                    List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

                    assertThat(quantized).as("%s, offset %d of a unit", meter, step).hasSize(2);
                }
            }
        }

        @Test
        @DisplayName("a tolerated overlap can still cost a span of exactly one unit its beats")
        void theToleratedOverlapIsTheOneExceptionToTheBound() {
            // The limit of the invariant above, pinned rather than disclaimed.
            // ChordProgression admits a microsecond of overlap, onGrid resolves
            // it by taking the later position as the single boundary, and a
            // chord that is exactly one beat long is then a microsecond short of
            // one -- which is enough to collapse it when it also lands on a
            // rounding midpoint. 1.25 s is beat 2.5 at 120 BPM, the midpoint of
            // the cell around beat 3.
            //
            // What it costs is the progression's beat axis rather than the span
            // itself, which is the safer of the two failures: the chart is
            // placed by seconds and still names G where G sounded.
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.0, 1.2500004),
                    chord("G4", 1.2499996, 1.7499996),
                    chord("F4", 1.7499996, 4.0));

            assertThat(score.chords().chords().get(1).durationSeconds() * 2)
                    .as("the chord that goes is exactly one beat long")
                    .isCloseTo(1.0, within(1e-9));

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(symbols(quantized.score().chords().chords()))
                    .containsExactly("C", "G", "F");
            assertThat(quantized.unplaceableChords()).isEqualTo(1);
        }

        @Test
        @DisplayName("and it costs only the offsets that land on a midpoint")
        void theToleratedOverlapCostsOnlyTheMidpoints() {
            // The sweep above run a second time with a sub-microsecond overlap
            // injected into every span, which is the version that would have
            // caught the over-claimed invariant.
            //
            // What is lost is stated as *which* offsets rather than as how many,
            // because a count is satisfied by losing four of anything. And the
            // rate is not one in a hundred: it is the single offset whose shared
            // boundary lands on a rounding midpoint, so it tracks the sweep's own
            // resolution -- 200 steps still loses one per meter, at step 100 --
            // and a sweep with no such offset loses none. Measure zero, not one
            // per cent.
            //
            // "Lost" now means the progression came back in seconds rather than
            // a span came back not at all. The set is the same set: what the
            // overlap reaches is unchanged, only what it costs.
            double overlap = 4e-7;
            List<String> lost = new ArrayList<>();
            int swept = 0;
            for (TimeSignature meter : List.of(TimeSignature.FOUR_FOUR, TimeSignature.SIX_EIGHT,
                    new TimeSignature(7, 8), TimeSignature.THREE_FOUR)) {
                TempoMap tempoMap = TempoMap.constant(BPM, meter);
                double unit = meter.beatUnitQuarters() * 60 / BPM;
                double base = 4 * meter.quarterBeatsPerBar() * 60 / BPM;
                for (int step = 0; step < 100; step++) {
                    // The middle chord declares exactly one unit and has each of
                    // its boundaries stated a shade earlier than the neighbour
                    // states it, which is what an estimator whose spans do not
                    // agree to the last bit produces.
                    double at = base + unit + step * unit / 100;
                    QuantizedScore overlapping = Quantizer.quantize(chordsOnly(tempoMap,
                            chord("C4", base, at + overlap),
                            chord("G4", at - overlap, at + unit - overlap),
                            chord("F4", at + unit - overlap, base + 8 * unit)));
                    List<String> clean = beats(Quantizer.quantize(chordsOnly(tempoMap,
                            chord("C4", base, at),
                            chord("G4", at, at + unit),
                            chord("F4", at + unit, base + 8 * unit)))
                            .score().chords().chords());
                    swept++;
                    // Every chord survives at every offset, which is the part
                    // that is no longer a trade-off at all.
                    assertThat(symbols(overlapping.score().chords().chords()))
                            .as("%s, offset %d", meter, step)
                            .containsExactly("C", "G", "F");
                    if (overlapping.unplaceableChords() > 0) {
                        lost.add(meter + "#" + step);
                    } else {
                        // Nothing else moves: away from the midpoint the overlap
                        // is invisible, position for position.
                        assertThat(beats(overlapping.score().chords().chords()))
                                .as("%s, offset %d", meter, step).isEqualTo(clean);
                    }
                }
            }

            assertThat(swept).isEqualTo(400);
            assertThat(lost).containsExactly("4/4#50", "6/8#50", "7/8#50", "3/4#50");
        }

        @Test
        @DisplayName("across a meter change the unit that matters is the longer one")
        void aMeterChangeToALongerBeatMovesTheBound() {
            // Stated because the bound above is easy to over-claim. In 7/8 the
            // counted beat is an eighth and in 4/4 it is a quarter, so a chord
            // of exactly one 7/8 beat that straddles the change has both ends
            // inside the quarter-note cell and goes. A chord as long as the
            // *longer* of the two units survives.
            // A 7/8 bar is 3.5 quarter beats, so the meter changes at quarter
            // beat 3.5 and the chord below straddles it.
            TempoMap tempoMap = TempoMap.constant(BPM, new TimeSignature(7, 8))
                    .withMeterChange(1, TimeSignature.FOUR_FOUR);

            Score straddling = chordsOnly(tempoMap,
                    chord("C4", at(tempoMap, 0), at(tempoMap, 3.3)),
                    chord("D4", at(tempoMap, 3.3), at(tempoMap, 3.8)),
                    chord("F4", at(tempoMap, 3.8), at(tempoMap, 7.5)));
            QuantizedScore quantized = Quantizer.quantize(straddling);
            assertThat(quantized.unplaceableChords()).isEqualTo(1);
            assertThat(symbols(quantized.score().chords().chords()))
                    .containsExactly("C", "D", "F");

            Score longer = chordsOnly(tempoMap,
                    chord("C4", at(tempoMap, 0), at(tempoMap, 3.3)),
                    chord("D4", at(tempoMap, 3.3), at(tempoMap, 4.3)),
                    chord("F4", at(tempoMap, 4.3), at(tempoMap, 7.5)));
            assertThat(beats(Quantizer.quantize(longer).score().chords().chords()))
                    .containsExactly("C 0.0..3.5", "D 3.5..4.5", "F 4.5..7.5");
        }
    }

    @Nested
    @DisplayName("the two axes cannot disagree about the order")
    class Ordering {

        @Test
        @DisplayName("a microsecond of overlap in seconds does not become a beat of it")
        void theToleratedOverlapDoesNotCrossABoundary() {
            // ChordProgression admits an overlap of up to a microsecond, and a
            // microsecond straddling a rounding boundary snaps to two positions
            // a whole beat apart. 1.25 s is beat 2.5 at 120 BPM, which is
            // exactly the midpoint between beat 2 and beat 3.
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.0, 1.2500004), chord("G4", 1.2499996, 3.0));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(quantized.get(0).endBeat()).contains(3.0);
            assertThat(quantized.get(1).startBeat()).contains(3.0);
            assertThat(quantized.get(1).startBeat().orElseThrow())
                    .isGreaterThanOrEqualTo(quantized.get(0).endBeat().orElseThrow());
        }

        @Test
        @DisplayName("the same overlap between two sections does not reach the Score")
        void sectionsSurviveTheSameOverlap() {
            // Score rejects quantized sections that overlap on the beat axis, so
            // an unguarded pass would not merely mis-order them: it would fail
            // to build the score at all.
            TempoMap tempoMap = fourFour();
            List<Section> sections = List.of(
                    section(0.0, 1.0000004), section(0.9999996, 4.0));
            Score score = new Score(Optional.empty(), Optional.empty(), tempoMap,
                    Optional.empty(), List.of(), sections, List.of(),
                    ChordProgression.empty(), Lyrics.empty(), 8.0);

            List<Section> quantized = Quantizer.quantize(score).score().sections();

            assertThat(quantized.get(0).endBeat()).contains(4.0);
            assertThat(quantized.get(1).startBeat()).contains(4.0);
        }
    }

    @Nested
    @DisplayName("structure goes to a bar line")
    class Structure {

        @Test
        @DisplayName("a section boundary in the first half of a bar falls back to its line")
        void aSectionBoundaryRoundsBackToTheBarLine() {
            // Beat 5.2 is bar 1 plus one and a fifth beats. On the note grid it
            // would be a sixteenth position; a double bar cannot go there.
            Score score = sectionsOnly(fourFour(), section(2.6, 6.0));

            List<Section> quantized = Quantizer.quantize(score).score().sections();

            assertThat(quantized.get(0).startBeat()).contains(4.0);
            assertThat(quantized.get(0).endBeat()).contains(12.0);
        }

        @Test
        @DisplayName("a section boundary in the second half of a bar goes on to the next")
        void aSectionBoundaryRoundsForwardToTheNextBarLine() {
            // Beat 7.2 is bar 1 plus three and a fifth beats, so the nearest bar
            // line is bar 2's. Rounding down would move a whole formal division.
            Score score = sectionsOnly(fourFour(), section(3.6, 6.0));

            List<Section> quantized = Quantizer.quantize(score).score().sections();

            assertThat(quantized.get(0).startBeat()).contains(8.0);
        }

        @Test
        @DisplayName("a key change lands on a bar line, which is where it is engraved")
        void aKeyChangeGoesToABarLine() {
            TempoMap tempoMap = fourFour();
            List<Key> keys = List.of(
                    Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MAJOR, 0, 4.1,
                            Confidence.CERTAIN),
                    Key.ofSeconds(PitchSpelling.parse("A4"), Mode.MINOR, 4.1, 7.4,
                            Confidence.CERTAIN));
            Score score = new Score(Optional.empty(), Optional.empty(), tempoMap,
                    Optional.empty(), keys, List.of(), List.of(),
                    ChordProgression.empty(), Lyrics.empty(), 8.0);

            List<Key> quantized = Quantizer.quantize(score).score().keys();

            assertThat(quantized.get(0).startBeat()).contains(0.0);
            assertThat(quantized.get(0).endBeat()).contains(8.0);
            assertThat(quantized.get(1).startBeat()).contains(8.0);
            assertThat(quantized.get(1).endBeat()).contains(16.0);
        }

        @Test
        @DisplayName("in 6/8 a bar line is three quarter beats apart, not four")
        void aCompoundBarLineFollowsTheMeter() {
            Score score = sectionsOnly(sixEight(), section(1.6, 3.0));

            List<Section> quantized = Quantizer.quantize(score).score().sections();

            assertThat(quantized.get(0).startBeat()).contains(3.0);
            assertThat(quantized.get(0).endBeat()).contains(6.0);
        }

        @Test
        @DisplayName("a key too short for a bar keeps its seconds rather than disappearing")
        void anUnplaceableKeyIsNotDeleted() {
            // Deleting it would engrave its music under the next key's
            // signature: eight bars of six sharps over a passage that opened in
            // C. It keeps its seconds, says it has no beats, and the score says
            // so too.
            TempoMap tempoMap = fourFour();
            List<Key> keys = List.of(
                    Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MAJOR, 0, 0.8,
                            Confidence.CERTAIN),
                    Key.ofSeconds(PitchSpelling.parse("F#4"), Mode.MAJOR, 0.8, 8.0,
                            Confidence.CERTAIN));
            Score score = new Score(Optional.empty(), Optional.empty(), tempoMap,
                    Optional.empty(), keys, List.of(), List.of(),
                    ChordProgression.empty(), Lyrics.empty(), 8.0);

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(quantized.score().keys()).hasSize(2);
            assertThat(quantized.score().keys().get(0).displayName()).isEqualTo("C major");
            assertThat(quantized.score().keys().get(0).isQuantized()).isFalse();
            assertThat(quantized.score().keys().get(0).startSeconds()).isEqualTo(0.0);
            assertThat(quantized.score().keys().get(1).startBeat()).contains(0.0);
            assertThat(quantized.isFullyQuantized()).isFalse();
        }

        @Test
        @DisplayName("a section too short for a bar keeps its seconds too")
        void anUnplaceableSectionIsNotDeleted() {
            Score score = sectionsOnly(fourFour(),
                    section(0.0, 0.75), section(0.75, 8.0));

            List<Section> quantized = Quantizer.quantize(score).score().sections();

            assertThat(quantized).hasSize(2);
            assertThat(quantized.get(0).isQuantized()).isFalse();
            assertThat(quantized.get(1).startBeat()).contains(0.0);
        }

        @Test
        @DisplayName("however many spans go in, that many come out, whatever collapses")
        void everyKindOfSpanSurvivesInItsOwnNumber() {
            // The totality of onGrid, which is what makes "nothing is ever
            // deleted" a property of the code rather than a convention every
            // caller has to keep. Before this PR the collapse handler could
            // return null and the loop would skip the span; now there is no way
            // to spell that, so the loop adds exactly one span per iteration.
            //
            // Swept over sections and keys as well as chords, because the
            // handler is shared and the chord path is the only one whose
            // behaviour this PR meant to change. At 60 BPM a bar is four seconds
            // and a counted beat is one, so every span below is far shorter than
            // the unit it is snapped to and most of them collapse.
            //
            // The three "and the collapses really happened" assertions are not
            // ceremony: the first draft of this test used a 120 BPM map, where
            // the chords are exactly one counted beat and nothing collapses at
            // all, and the three size assertions passed on a pass that had
            // nothing to decide. They caught it.
            TempoMap tempoMap = TempoMap.constant(60, TimeSignature.FOUR_FOUR);
            List<Section> shortSections = new ArrayList<>();
            List<Key> shortKeys = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                shortSections.add(section(i * 0.375, (i + 1) * 0.375));
                shortKeys.add(Key.ofSeconds(PitchSpelling.parse("C4"), Mode.MAJOR,
                        i * 0.375, (i + 1) * 0.375, Confidence.CERTAIN));
            }
            Score score = new Score(Optional.empty(), Optional.empty(), tempoMap,
                    Optional.empty(), shortKeys, shortSections, List.of(),
                    new ChordProgression(List.of(everyHalfSecond(0.0)), Confidence.CERTAIN),
                    Lyrics.empty(), 32.0);

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(quantized.score().sections()).hasSize(8);
            assertThat(quantized.score().keys()).hasSize(8);
            assertThat(quantized.score().chords().chords()).hasSize(8);
            // And the collapses really happened, or the sizes above are the
            // sizes of a pass that had nothing to decide.
            assertThat(quantized.score().sections())
                    .anySatisfy(s -> assertThat(s.isQuantized()).isFalse());
            assertThat(quantized.score().keys())
                    .anySatisfy(k -> assertThat(k.isQuantized()).isFalse());
            assertThat(quantized.unplaceableChords()).isPositive();
        }

        @Test
        @DisplayName("stale beats on a span that cannot be placed are taken off it")
        void aCollapsedSpanDoesNotKeepBeatsFromNowhere() {
            // Only a hand-assembled score reaches this -- anything this pass
            // placed is a whole bar long and cannot collapse on a second run --
            // but a section left carrying beats that sit on no bar line would
            // publish a double bar the score does not have.
            TempoMap tempoMap = fourFour();
            Section stale = new Section(SectionKind.VERSE, "Verse", 0.0, 0.75,
                    Optional.of(1.0), Optional.of(1.2), Optional.empty(), Confidence.CERTAIN);
            Score score = new Score(Optional.empty(), Optional.empty(), tempoMap,
                    Optional.empty(), List.of(), List.of(stale), List.of(),
                    ChordProgression.empty(), Lyrics.empty(), 8.0);

            List<Section> quantized = Quantizer.quantize(score).score().sections();

            assertThat(quantized).hasSize(1);
            assertThat(quantized.get(0).isQuantized()).isFalse();
        }

        @Test
        @DisplayName("a boundary exactly half a bar in goes forward, not to the even bar")
        void aBarLineTieGoesForward() {
            // Two ties in a row, one from an odd bar and one from an even. Under
            // round-half-to-even they would go opposite ways.
            Score score = sectionsOnly(fourFour(),
                    section(1.0, 3.0), section(3.0, 8.0));

            List<Section> quantized = Quantizer.quantize(score).score().sections();

            assertThat(quantized.get(0).startBeat()).contains(4.0);
            assertThat(quantized.get(0).endBeat()).contains(8.0);
            assertThat(quantized.get(1).startBeat()).contains(8.0);
        }

        @Test
        @DisplayName("the grid may change for free at the bar the section is published at")
        void theGridPriorReadsThePublishedBar() {
            // Two bars of eighths then two of sixteenths, with the section
            // boundary heard four fifths of the way through bar 1 -- so the bar
            // it *sounds* in is bar 1 and the bar it is *engraved* at is bar 2.
            // The change penalty is set high enough that the subdivision can
            // only change where a section makes it free, which is what makes the
            // difference between the two answers visible.
            TempoMap tempoMap = fourFour();
            Performance performance = new Performance(tempoMap, 11);
            performance.section(0, 7.2).section(7.2, 16);
            performance.run(60, 0.5, 0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5,
                    4, 4.5, 5, 5.5, 6, 6.5, 7, 7.5);
            performance.run(60, 0.25, 8, 8.25, 8.5, 8.75, 9, 9.25, 9.5, 9.75,
                    10, 10.25, 10.5, 10.75, 11, 11.25, 11.5, 11.75,
                    12, 12.25, 12.5, 12.75, 13, 13.25, 13.5, 13.75,
                    14, 14.25, 14.5, 14.75, 15, 15.25, 15.5, 15.75);

            QuantizedScore quantized = Quantizer.quantize(performance.score(),
                    QuantizationSettings.DEFAULT.withGridChangePenalty(100));

            assertThat(quantized.gridAtBar(0).orElseThrow().resolution())
                    .isEqualTo(GridResolution.HALF_BEAT);
            assertThat(quantized.gridAtBar(1).orElseThrow().resolution())
                    .isEqualTo(GridResolution.HALF_BEAT);
            assertThat(quantized.gridAtBar(2).orElseThrow().resolution())
                    .isEqualTo(GridResolution.QUARTER_BEAT);
            assertThat(quantized.gridAtBar(3).orElseThrow().resolution())
                    .isEqualTo(GridResolution.QUARTER_BEAT);
        }
    }

    @Nested
    @DisplayName("a score without notes is still worth quantizing")
    class WithoutNotes {

        @Test
        @DisplayName("a chord chart from the audio path has no note track and still gets beats")
        void chordsAloneAreQuantized() {
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.03, 2.04), chord("G4", 2.04, 3.96));

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(quantized.score().chords().isQuantized()).isTrue();
            assertThat(quantized.grids()).isEmpty();
            assertThat(quantized.swing()).isEqualTo(SwingFeel.STRAIGHT);
            assertThat(quantized.isFullyQuantized()).isTrue();
        }

        @Test
        @DisplayName("a score with nothing in it at all comes back untouched")
        void anEmptyScoreIsReturnedAsItIs() {
            Score score = Score.empty(fourFour(), 8.0);

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(quantized.score()).isSameAs(score);
            assertThat(quantized.grids()).isEmpty();
        }

        @Test
        @DisplayName("a chord ringing past the last note is placed, not clamped into it")
        void theBarTableSpansTheChordsToo() {
            // Sizing the bar table from the notes alone left barOf clamping
            // everything past the last note into the last bar it had, so a final
            // chord came out on top of the one before it.
            TempoMap tempoMap = fourFour();
            Performance performance = new Performance(tempoMap, 12);
            performance.run(60, 1, 0, 1, 2, 3);
            ChordProgression chords = new ChordProgression(List.of(
                    chord("C4", 0.0, 2.0), chord("F4", 2.0, 16.0)), Confidence.CERTAIN);

            List<Chord> quantized =
                    Quantizer.quantize(performance.score(chords)).score().chords().chords();

            assertThat(beats(quantized)).containsExactly("C 0.0..4.0", "F 4.0..32.0");
        }
    }

    @Nested
    @DisplayName("the pass agrees with itself")
    class Stability {

        @Test
        @DisplayName("quantizing an already quantized score changes nothing")
        void quantizationIsIdempotent() {
            TempoMap tempoMap = fourFour();
            Performance performance = new Performance(tempoMap, 13);
            performance.section(0, 8).section(8, 16);
            performance.run(60, 0.5, Performance.evenly(4, 4.0, 8));
            ChordProgression chords = new ChordProgression(List.of(
                    chord("C4", 0.03, 2.04), chord("G4", 2.04, 4.01)), Confidence.CERTAIN);
            List<Key> keys = List.of(Key.ofSeconds(PitchSpelling.parse("D4"), Mode.MAJOR,
                    0, 4.1, Confidence.CERTAIN));

            Score once = Quantizer.quantize(performance.score(chords, keys)).score();
            Score twice = Quantizer.quantize(once).score();

            // Asserted before the comparisons below, which would otherwise hold
            // for a pass that placed nothing at all: three lists of empty
            // Optionals are equal to three lists of empty Optionals.
            assertThat(once.chords().isQuantized()).isTrue();
            assertThat(once.sections()).allSatisfy(s -> assertThat(s.isQuantized()).isTrue());
            assertThat(once.keys()).allSatisfy(k -> assertThat(k.isQuantized()).isTrue());
            assertThat(twice.chords().chords()).isEqualTo(once.chords().chords());
            assertThat(twice.sections()).isEqualTo(once.sections());
            assertThat(twice.keys()).isEqualTo(once.keys());
        }

        @Test
        @DisplayName("a shuffle is taken out of the notes and not out of the chords")
        void spansAreNotDeSwung() {
            // A chord change on the swung off-beat of beat 2 is an anticipation
            // of beat 3 and belongs there. De-swinging it first would pull it
            // back to the halfway point, which rounds to beat 2 instead -- the
            // wrong side of the boundary it was pushing against.
            Score score = shuffleWithChordAt(2 + 2.0 / 3);

            QuantizedScore quantized = Quantizer.quantize(score);

            assertThat(quantized.swing().swung()).isTrue();
            assertThat(quantized.score().chords().chords().get(1).startBeat()).contains(3.0);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static TempoMap fourFour() {
        return TempoMap.constant(BPM, TimeSignature.FOUR_FOUR);
    }

    private static TempoMap sixEight() {
        return TempoMap.constant(BPM, TimeSignature.SIX_EIGHT);
    }

    /** A quarter-beat position as the wall-clock time a fixture states it in. */
    private static double at(TempoMap tempoMap, double quarterBeat) {
        return tempoMap.beatsToSeconds(quarterBeat);
    }

    private static Chord chord(String root, double startSeconds, double endSeconds) {
        return Chord.ofSeconds(PitchSpelling.parse(root), ChordQuality.MAJOR,
                startSeconds, endSeconds, Confidence.CERTAIN);
    }

    private static Section section(double startSeconds, double endSeconds) {
        return Section.ofSeconds(SectionKind.VERSE, "Verse", startSeconds, endSeconds,
                null, Confidence.CERTAIN);
    }

    /** A score whose only content is a chord progression, as the audio path leaves it. */
    private static Score chordsOnly(TempoMap tempoMap, Chord... chords) {
        return new Score(Optional.empty(), Optional.empty(), tempoMap, Optional.empty(),
                List.of(), List.of(), List.of(),
                new ChordProgression(List.of(chords), Confidence.CERTAIN),
                Lyrics.empty(), 32.0);
    }

    private static Score sectionsOnly(TempoMap tempoMap, Section... sections) {
        return new Score(Optional.empty(), Optional.empty(), tempoMap, Optional.empty(),
                List.of(), List.of(sections), List.of(),
                ChordProgression.empty(), Lyrics.empty(), 32.0);
    }

    /**
     * Eight bars of swung eighths, played to the tick so the feel is
     * unmistakable, with a chord change at a nominal beat position.
     */
    private static Score shuffleWithChordAt(double changeBeat) {
        TempoMap tempoMap = fourFour();
        List<Note> notes = new ArrayList<>();
        for (int beat = 0; beat < 8 * 4; beat++) {
            notes.add(Note.ofSeconds(tempoMap.beatsToSeconds(beat), 0.3, 60, Confidence.CERTAIN));
            notes.add(Note.ofSeconds(tempoMap.beatsToSeconds(beat + 2.0 / 3), 0.15, 62,
                    Confidence.CERTAIN));
        }
        double changeSeconds = tempoMap.beatsToSeconds(changeBeat);
        ChordProgression chords = new ChordProgression(List.of(
                chord("C4", 0.0, changeSeconds),
                chord("G4", changeSeconds, tempoMap.beatsToSeconds(32))), Confidence.CERTAIN);
        NoteTrack track = new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.CERTAIN);
        return new Score(Optional.empty(), Optional.empty(), tempoMap, Optional.empty(),
                List.of(), List.of(), List.of(track), chords, Lyrics.empty(), 20.0);
    }

    /** Each chord as {@code symbol start..end}, which is what the assertions read. */
    private static List<String> beats(List<Chord> chords) {
        return chords.stream()
                .map(c -> c.symbol() + " " + c.startBeat().orElseThrow()
                        + ".." + c.endBeat().orElseThrow())
                .toList();
    }

    /** Roots the sweeps cycle through, so a reordering would be visible. */
    private static final List<String> ROOTS = List.of("C4", "G4", "A4", "F4", "D4", "E4", "B4");

    /**
     * #157's own fixture: eight chords a half-second apart, which is one per
     * beat of material heard at 120, started at a chosen offset so that the same
     * disagreement can be read at several phases.
     */
    private static Chord[] everyHalfSecond(double offsetSeconds) {
        Chord[] chords = new Chord[8];
        for (int i = 0; i < chords.length; i++) {
            chords[i] = chord(ROOTS.get(i % 4), offsetSeconds + i * 0.5,
                    offsetSeconds + (i + 1) * 0.5);
        }
        return chords;
    }

    /**
     * A chord with its beat fields taken off, which is the only thing the pass
     * is allowed to change about one.
     */
    private static Chord withoutBeats(Chord chord) {
        return new Chord(chord.root(), chord.quality(), chord.bass(), chord.startSeconds(),
                chord.endSeconds(), Optional.empty(), Optional.empty(), chord.confidence());
    }

    /**
     * Just the symbols, in order, for the assertions about what the chart names.
     *
     * <p>Separate from {@link #beats} rather than folded into it, because a
     * progression left in seconds has no beats to print and the interesting
     * assertion about it is precisely that the harmony is all still there.
     */
    private static List<String> symbols(List<Chord> chords) {
        return chords.stream().map(Chord::symbol).toList();
    }
}
