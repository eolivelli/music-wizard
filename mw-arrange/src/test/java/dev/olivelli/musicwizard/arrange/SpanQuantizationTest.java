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
            // The audio estimator is beat-synchronous and a tracked beat is a
            // quarter beat, so it can put a chord boundary at quarter beat 1 --
            // the second of the six eighths, which is not a place harmony
            // changes. The counted beat in 6/8 is one and a half quarters.
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
        @DisplayName("a progression that gains beats is one the chart may bar from")
        void theProgressionReportsItselfQuantized() {
            Score score = chordsOnly(fourFour(),
                    chord("C4", 0.03, 2.04), chord("G4", 2.04, 3.96));

            assertThat(score.chords().isQuantized()).isFalse();
            assertThat(Quantizer.quantize(score).score().chords().isQuantized()).isTrue();
        }
    }

    @Nested
    @DisplayName("a chord with nowhere to go is merged, not rejected")
    class Collapse {

        @Test
        @DisplayName("a chord shorter than the beat it falls in is dropped")
        void aCollapsedChordIsDropped() {
            // Three one-quarter-beat spans in 6/8, where a counted beat is one
            // and a half. The middle one has both its boundaries rounded onto
            // the same dotted quarter and cannot be given a length.
            Score score = chordsOnly(sixEight(),
                    chord("C4", 0.0, 0.5), chord("D4", 0.5, 1.0), chord("F4", 1.0, 1.5));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(beats(quantized)).containsExactly("C 0.0..1.5", "F 1.5..3.0");
        }

        @Test
        @DisplayName("what is left is still contiguous where it was contiguous before")
        void theNeighboursMeetWhereItCollapsed() {
            Score score = chordsOnly(sixEight(),
                    chord("C4", 0.0, 0.5), chord("D4", 0.5, 1.0), chord("F4", 1.0, 1.5));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(quantized.get(0).endBeat()).isEqualTo(quantized.get(1).startBeat());
            assertThat(Quantizer.quantize(score).score().chords().isQuantized()).isTrue();
        }

        @Test
        @DisplayName("a collapsed first chord takes nothing with it")
        void aCollapsedFirstChordIsDroppedToo() {
            Score score = chordsOnly(sixEight(),
                    chord("D4", 0.0, 0.3), chord("F4", 0.3, 1.5));

            List<Chord> quantized = Quantizer.quantize(score).score().chords().chords();

            assertThat(beats(quantized)).containsExactly("F 0.0..3.0");
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
}
