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
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteLetter;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the melody looks like once it has been reduced to a playable part (#592).
 *
 * <p>The fixtures are built at one beat per second so that a duration in
 * seconds and the same figure in beats are the same number, which is what keeps
 * the ornament thresholds readable here.
 */
class PlayableMelodyTest {

    @Nested
    @DisplayName("grouping by syllable")
    class Grouping {

        @Test
        @DisplayName("a syllable prints one note however many the estimate cut out of it")
        void oneNotePerSyllable() {
            // The shape of bar 10 of la-canzone-del-sole: a scoop the segmenter
            // cut into pieces, then the note it arrives at, under one syllable.
            Score score = sung(
                    notes(note(0.0, 0.15, 62), note(0.15, 0.17, 63), note(0.32, 0.18, 63),
                            note(0.5, 0.22, 60), note(0.72, 0.21, 61)),
                    line(word("le", 0.05, 0.30), word("bion", 0.35, 0.65),
                            word("de", 0.70, 0.93)));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(63, 60, 61);
        }

        @Test
        @DisplayName("a note between two lyric lines is left where it is")
        void anInstrumentalNoteIsNotClaimed() {
            Score score = sung(
                    notes(note(0.0, 0.4, 60), note(4.0, 0.9, 67), note(8.0, 0.4, 62)),
                    line(word("one", 0.0, 0.4)), line(word("two", 8.0, 8.4)));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(60, 67, 62);
        }

        @Test
        @DisplayName("a note in the instrumental gap a line's hull spans is not welded to it")
        void anInstrumentalGapInsideALineIsNotClaimed() {
            // One lyric line whose two words sit a long way apart, and a note
            // played between them. The line's hull covers the gap, so the note
            // is inside it and equally near both words (#598).
            Score score = sung(
                    notes(note(0.0, 0.3, 60), note(20.0, 0.3, 67), note(40.0, 0.3, 62)),
                    line(word("one", 0.0, 0.3), word("two", 40.0, 40.3)));

            NoteTrack reduced = PlayableMelody.reduce(score);

            assertThat(pitches(reduced)).containsExactly(60, 67, 62);
            assertThat(reduced.notes().get(0).offsetSeconds()).isEqualTo(0.3);
            assertThat(reduced.notes().get(1).onsetSeconds()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("a note far from every syllable of the line it sits in is not claimed either")
        void aNoteFarFromEverySyllableIsNotClaimed() {
            // The second note is nearer this line's middle word than either of
            // the others, so the hull is not what welds it -- there is simply
            // no syllable close enough for it to have been sung on.
            Score score = sung(
                    notes(note(6.0, 0.3, 60), note(12.0, 0.3, 67), note(20.0, 0.3, 62)),
                    line(word("one", 0.0, 0.3), word("two", 5.0, 6.3),
                            word("three", 20.0, 20.3)));

            NoteTrack reduced = PlayableMelody.reduce(score);

            assertThat(pitches(reduced)).containsExactly(60, 67, 62);
            assertThat(reduced.notes().get(0).offsetSeconds()).isEqualTo(6.3);
        }

        @Test
        @DisplayName("the bound does not reject a note that starts late inside its own syllable")
        void aLongSyllableStillClaimsWhatSoundsUnderIt() {
            Score score = sung(
                    notes(note(0.0, 4.0, 60), note(4.0, 4.0, 62)),
                    line(word("aaah", 0.0, 8.0)));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(62);
        }

        @Test
        @DisplayName("a syllable marked as a melisma is not collapsed to one note")
        void aMelismaIsNotCollapsed() {
            Score score = sung(
                    notes(note(0.0, 0.4, 60), note(0.4, 0.4, 62), note(0.8, 0.4, 64)),
                    line(word("aaah", 0.0, 1.2).withMelisma(true)));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(60, 62, 64);
        }

        @Test
        @DisplayName("but its ornaments are still ornaments")
        void aMelismaIsNotExemptFromTheOrnamentRule() {
            Score score = sung(
                    notes(note(0.0, 0.1, 60), note(0.1, 0.9, 64)),
                    line(word("aaah", 0.0, 1.0).withMelisma(true)));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(64);
        }

        @Test
        @DisplayName("the group runs from its first onset to its last release")
        void theSpanCoversWhatItReplaced() {
            Score score = sung(
                    notes(note(1.0, 0.2, 60), note(1.2, 0.5, 64)),
                    line(word("held", 1.05, 1.6)));

            Note only = PlayableMelody.reduce(score).notes().get(0);
            assertThat(only.onsetSeconds()).isEqualTo(1.0);
            assertThat(only.offsetSeconds()).isEqualTo(1.7);
        }

        @Test
        @DisplayName("the estimate is left alone")
        void theEstimateSurvives() {
            Score score = sung(
                    notes(note(0.0, 0.15, 62), note(0.15, 0.4, 64)),
                    line(word("one", 0.0, 0.5)));

            PlayableMelody.reduce(score);

            assertThat(score.track(PartRole.LEAD_VOCAL).orElseThrow().size()).isEqualTo(2);
        }

        @Test
        @DisplayName("a score with no melody is an error rather than an empty part")
        void nothingToReduce() {
            Score score = new Score(java.util.Optional.empty(), java.util.Optional.empty(),
                    TempoMap.constant(60), java.util.Optional.empty(), List.of(), List.of(),
                    List.of(), ChordProgression.empty(), Lyrics.empty(), 10);

            assertThatThrownBy(() -> PlayableMelody.reduce(score))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no melody");
        }
    }

    @Nested
    @DisplayName("the pitch a group settles on")
    class Settling {

        @Test
        @DisplayName("the arrival, not the pitch that sounds longest")
        void theArrivalWins() {
            // The approach sounds longer than the note it climbs to, so any rule
            // that weighs the whole group answers 60 where 61 was sung. This is
            // the syllable "de" of bar 10, whose two pieces are that close.
            Score score = sung(
                    notes(note(0.0, 0.22, 60), note(0.22, 0.21, 61)),
                    line(word("de", 0.0, 0.43)));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(61);
        }

        @Test
        @DisplayName("the reduced note keeps the spelling of the note it printed")
        void theSpellingRidesAlong() {
            Score score = sung(
                    notes(note(0.0, 0.2, 60),
                            note(0.2, 0.4, 61).spelledAs(
                                    new PitchSpelling(NoteLetter.D, Accidental.FLAT, 4))),
                    line(word("de", 0.0, 0.6)));

            assertThat(PlayableMelody.reduce(score).notes().get(0).spelling())
                    .containsInstanceOf(PitchSpelling.class)
                    .get().extracting(PitchSpelling::letter).isEqualTo(NoteLetter.D);
        }

        @Test
        @DisplayName("a quantized melody stays quantized")
        void quantizedInQuantizedOut() {
            Score score = sung(
                    notes(note(0.0, 0.5, 60).quantizedTo(0, 0.5),
                            note(0.5, 0.5, 64).quantizedTo(0.5, 0.5)),
                    line(word("one", 0.0, 1.0)));

            Note only = PlayableMelody.reduce(score).notes().get(0);
            assertThat(only.isQuantized()).isTrue();
            assertThat(only.onsetBeat()).contains(0.0);
            assertThat(only.durationBeats()).contains(1.0);
        }
    }

    @Nested
    @DisplayName("chord tones as a tie-break")
    class TheChartsSay {

        @Test
        @DisplayName("a chord tone wins where the group barely settled anywhere")
        void theChordSeparatesThem() {
            // The last note is far shorter than the pitch that dominates the
            // group, so the group has arguably not settled at all; C sounds
            // under the C chord and B does not.
            Score score = sungOver(shortTail(), chart(ChordQuality.MAJOR, 0.8));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(60);
        }

        @Test
        @DisplayName("a chart the pipeline does not trust says nothing")
        void theFloorHolds() {
            Score score = sungOver(shortTail(), chart(ChordQuality.MAJOR, 0.4));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(59);
        }

        @Test
        @DisplayName("an N.C. span says nothing either")
        void noChordSaysNothing() {
            Score score = sungOver(shortTail(), new ChordProgression(
                    List.of(Chord.noChord(0.0, 4.0, Confidence.CERTAIN)), Confidence.CERTAIN));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(59);
        }

        @Test
        @DisplayName("a group that settled on its own is not second-guessed")
        void theSettledPitchIsNotOverridden() {
            // B sounds nearly as long as C, so this group did settle -- and a
            // rule that let the chord in here would be the rounding prior #571
            // measured and rejected, wearing a tie-break's clothes.
            Score score = sungOver(
                    notes(note(0.0, 0.5, 60), note(0.5, 0.45, 59)),
                    chart(ChordQuality.MAJOR, 1.0));

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(59);
        }

        private List<Note> shortTail() {
            return notes(note(0.0, 0.5, 60), note(0.5, 0.2, 59));
        }
    }

    @Nested
    @DisplayName("with no lyrics to group by")
    class TheFallback {

        @Test
        @DisplayName("an ornament joins the note it leads into")
        void theOrnamentIsAbsorbed() {
            Score score = played(notes(note(0.0, 0.2, 62), note(0.2, 0.8, 64)),
                    ChordProgression.empty());

            NoteTrack reduced = PlayableMelody.reduce(score);
            assertThat(pitches(reduced)).containsExactly(64);
            assertThat(reduced.notes().get(0).onsetSeconds()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("a run of even notes is a rhythm and survives whole")
        void aRealRunIsLeftAlone() {
            // Every note here is shorter than an ornament may be; what stops
            // them being read as ornaments is that none is short beside its
            // neighbour. A threshold on length alone would take the lot.
            Score score = played(notes(note(0.0, 0.25, 60), note(0.25, 0.25, 62),
                    note(0.5, 0.25, 64), note(0.75, 0.25, 65)), ChordProgression.empty());

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(60, 62, 64, 65);
        }

        @Test
        @DisplayName("a short note with a rest after it is a note, not an ornament")
        void aDetachedShortNoteSurvives() {
            Score score = played(notes(note(0.0, 0.2, 62), note(1.0, 0.8, 64)),
                    ChordProgression.empty());

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(62, 64);
        }

        @Test
        @DisplayName("a long note is never an ornament, however long the one after it")
        void lengthIsWhatMakesAnOrnament() {
            Score score = played(notes(note(0.0, 0.5, 62), note(0.5, 3.0, 64)),
                    ChordProgression.empty());

            assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(62, 64);
        }

        @Test
        @DisplayName("how short an ornament is, is a length in beats and not in seconds")
        void theThresholdIsOnTheBeatAxis() {
            // The same two notes under a tempo four times as fast. The first is
            // an ornament of the second at one beat a second and a note of its
            // own at four, and every other fixture here runs at the one tempo
            // where those two answers coincide.
            List<Note> pair = notes(note(0.0, 0.2, 62), note(0.2, 0.8, 64));

            assertThat(pitches(PlayableMelody.reduce(atTempo(pair, 60))))
                    .containsExactly(64);
            assertThat(pitches(PlayableMelody.reduce(atTempo(pair, 240))))
                    .containsExactly(62, 64);
        }
    }

    /** One syllable held over the whole of a short group, so the chart has a tie to break. */
    private static Score sungOver(List<Note> notes, ChordProgression chords) {
        double end = notes.get(notes.size() - 1).offsetSeconds();
        return score(notes, chords, new Lyrics(
                List.of(line(word("ah", notes.get(0).onsetSeconds(), end))), "en",
                Confidence.of(0.8)));
    }

    private static ChordProgression chart(ChordQuality quality, double confidence) {
        return new ChordProgression(List.of(new Chord(
                new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4), quality,
                java.util.Optional.empty(), 0.0, 4.0,
                java.util.Optional.empty(), java.util.Optional.empty(),
                Confidence.of(confidence))), Confidence.of(confidence));
    }

    private static Note note(double onsetSeconds, double durationSeconds, int midiPitch) {
        return Note.ofSeconds(onsetSeconds, durationSeconds, midiPitch, Confidence.of(0.7));
    }

    private static List<Note> notes(Note... notes) {
        return Arrays.asList(notes);
    }

    private static LyricWord word(String text, double startSeconds, double endSeconds) {
        return LyricWord.ofSeconds(text, startSeconds, endSeconds, Confidence.of(0.8));
    }

    private static LyricLine line(LyricWord... words) {
        return new LyricLine(Arrays.asList(words), Confidence.of(0.8));
    }

    private static Score sung(List<Note> notes, LyricLine... lines) {
        return score(notes, ChordProgression.empty(),
                new Lyrics(Arrays.asList(lines), "it", Confidence.of(0.8)));
    }

    private static Score played(List<Note> notes, ChordProgression chords) {
        return score(notes, chords, Lyrics.empty());
    }

    private static Score score(List<Note> notes, ChordProgression chords, Lyrics lyrics) {
        // One beat a second, so that a duration in seconds reads as the same
        // number of beats.
        return atTempo(notes, chords, lyrics, 60);
    }

    private static Score atTempo(List<Note> notes, double beatsPerMinute) {
        return atTempo(notes, ChordProgression.empty(), Lyrics.empty(), beatsPerMinute);
    }

    private static Score atTempo(List<Note> notes, ChordProgression chords, Lyrics lyrics,
                                 double beatsPerMinute) {
        return new Score(java.util.Optional.empty(), java.util.Optional.empty(),
                TempoMap.constant(beatsPerMinute), java.util.Optional.empty(),
                List.of(), List.of(),
                List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.of(0.7))),
                chords, lyrics, 30);
    }

    private static List<Integer> pitches(NoteTrack track) {
        List<Integer> out = new ArrayList<>(track.size());
        for (Note note : track.notes()) {
            out.add(note.midiPitch());
        }
        return out;
    }
}
