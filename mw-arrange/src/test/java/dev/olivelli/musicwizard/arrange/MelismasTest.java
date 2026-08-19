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

import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Lyrics;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which syllables are sung over a run of notes (#597).
 *
 * <p>Fixtures are at one beat per second, so a duration in seconds and the same
 * figure in beats are the same number. Each case asserts the printed part on
 * both sides of the decision: what the reduction does with the syllable
 * unmarked is what it did before anything marked one.
 */
class MelismasTest {

    @Test
    @DisplayName("a syllable the melody moves under is marked, and then prints its notes")
    void aRunIsMarked() {
        Score score = sung(
                notes(note(0.0, 0.4, 60), note(0.4, 0.4, 62), note(0.8, 0.4, 64)),
                line(word("aaah", 0.0, 1.2)));

        assertThat(pitches(PlayableMelody.reduce(score))).containsExactly(64);

        Score marked = score.withLyrics(Melismas.marked(score));

        assertThat(marked.lyrics().allWords()).extracting(LyricWord::melisma)
                .containsExactly(true);
        assertThat(pitches(PlayableMelody.reduce(marked))).containsExactly(60, 62, 64);
    }

    @Test
    @DisplayName("a syllable that is merely long is not")
    void aLongNoteIsNotARun() {
        // One note sustained under a syllable held four times as long as the
        // run above. Length is not the evidence; movement is.
        Score score = sung(notes(note(0.0, 4.0, 60)), line(word("aaah", 0.0, 4.0)));

        Score marked = score.withLyrics(Melismas.marked(score));

        assertThat(marked.lyrics().allWords()).extracting(LyricWord::melisma)
                .containsExactly(false);
        assertThat(pitches(PlayableMelody.reduce(marked))).containsExactly(60);
    }

    @Test
    @DisplayName("nor is one re-articulated on the same pitch")
    void aRepeatedNoteIsNotARun() {
        Score score = sung(
                notes(note(0.0, 0.8, 60), note(1.2, 0.8, 60)),
                line(word("aaah", 0.0, 2.0)));

        Score marked = score.withLyrics(Melismas.marked(score));

        assertThat(marked.lyrics().allWords()).extracting(LyricWord::melisma)
                .containsExactly(false);
        assertThat(pitches(PlayableMelody.reduce(marked))).containsExactly(60);
    }

    @Test
    @DisplayName("nor is a scoop into a note the singer holds")
    void aScoopIsNotARun() {
        // The scoop is an ornament of the note it arrives at, so the syllable
        // prints one head either way and marking it would only free the
        // fragment to print on its own.
        Score score = sung(
                notes(note(0.0, 0.1, 57), note(0.1, 0.9, 64)),
                line(word("aaah", 0.0, 1.0)));

        Score marked = score.withLyrics(Melismas.marked(score));

        assertThat(marked.lyrics().allWords()).extracting(LyricWord::melisma)
                .containsExactly(false);
        assertThat(pitches(PlayableMelody.reduce(marked))).containsExactly(64);
    }

    @Test
    @DisplayName("nor is a syllable whose notes are an octave apart")
    void anOctaveFoldIsNotARun() {
        Score score = sung(
                notes(note(0.0, 0.5, 60), note(0.5, 0.5, 72)),
                line(word("aaah", 0.0, 1.0)));

        Score marked = score.withLyrics(Melismas.marked(score));

        assertThat(marked.lyrics().allWords()).extracting(LyricWord::melisma)
                .containsExactly(false);
    }

    @Test
    @DisplayName("a note no syllable claims decides nothing")
    void anUnclaimedRunLeavesTheSyllableAlone() {
        Score score = sung(
                notes(note(0.0, 0.3, 60), note(20.0, 0.3, 62), note(20.4, 0.3, 65)),
                line(word("one", 0.0, 0.3), word("two", 40.0, 40.3)));

        assertThat(Melismas.marked(score).allWords()).extracting(LyricWord::melisma)
                .containsExactly(false, false);
    }

    @Test
    @DisplayName("the answer comes from the melody, not from the mark already there")
    void theMarkIsDecidedAfresh() {
        Score score = sung(
                notes(note(0.0, 4.0, 60)),
                line(word("aaah", 0.0, 4.0).withMelisma(true)));

        assertThat(Melismas.marked(score).allWords()).extracting(LyricWord::melisma)
                .containsExactly(false);

        Score run = sung(
                notes(note(0.0, 0.4, 60), note(0.4, 0.4, 62), note(0.8, 0.4, 64)),
                line(word("aaah", 0.0, 1.2)));
        Lyrics once = Melismas.marked(run);

        assertThat(Melismas.marked(run.withLyrics(once))).isEqualTo(once);
    }

    @Test
    @DisplayName("lyrics with no melody under them are returned as they are")
    void noMelodyDecidesNothing() {
        Lyrics lyrics = new Lyrics(List.of(line(word("aaah", 0.0, 1.2))), "it",
                Confidence.of(0.8));
        Score score = new Score(Optional.empty(), Optional.empty(),
                TempoMap.constant(60), Optional.empty(), List.of(), List.of(), List.of(),
                ChordProgression.empty(), lyrics, 30);

        assertThat(Melismas.marked(score)).isSameAs(lyrics);
    }

    @Test
    @DisplayName("a negative interval is rejected")
    void negativeIntervalsAreRejected() {
        Score score = sung(notes(note(0.0, 1.0, 60)), line(word("aaah", 0.0, 1.0)));

        assertThatThrownBy(() -> Melismas.marked(score, -1, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Melismas.marked(score, 2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- fixtures

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
        return new Score(Optional.empty(), Optional.empty(), TempoMap.constant(60),
                Optional.empty(), List.of(), List.of(),
                List.of(new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.of(0.7))),
                ChordProgression.empty(),
                new Lyrics(Arrays.asList(lines), "it", Confidence.of(0.8)), 60);
    }

    private static List<Integer> pitches(NoteTrack track) {
        List<Integer> out = new ArrayList<>(track.size());
        for (Note note : track.notes()) {
            out.add(note.midiPitch());
        }
        return out;
    }
}
