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

package dev.olivelli.musicwizard.notation;

import dev.olivelli.musicwizard.core.model.Accidental;
import dev.olivelli.musicwizard.core.model.BeatGrid;
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

/**
 * The scores the analysis report's golden files are written from.
 *
 * <p>Every figure here is a constant, because a golden file compares the whole
 * page: a fixture built from a random stream or from the clock would rewrite
 * itself on every run.
 */
final class ReportFixtures {

    /** Four bars at this tempo, which makes every beat land on a round number. */
    private static final double BEATS_PER_MINUTE = 120;

    private static final double SECONDS_PER_BEAT = 60 / BEATS_PER_MINUTE;

    private static final int BARS = 4;

    private static final double DURATION = BARS * 4 * SECONDS_PER_BEAT;

    private ReportFixtures() {
    }

    /** A score every stage left something in. */
    static Score everything() {
        return withHarmony()
                .withTrack(melody())
                .withLyrics(lyrics());
    }

    /** What a workspace analysed without {@code --melody} and without lyrics holds. */
    static Score chordsOnly() {
        return withHarmony();
    }

    /**
     * A score with a tempo map and nothing else, which is what an audio run
     * whose beat tracker found no pulse writes.
     */
    static Score bare() {
        return Score.empty(TempoMap.constant(BEATS_PER_MINUTE, TimeSignature.FOUR_FOUR),
                DURATION).withMetadata("Silence", null);
    }

    private static Score withHarmony() {
        return Score.empty(TempoMap.constant(BEATS_PER_MINUTE, TimeSignature.FOUR_FOUR), DURATION)
                .withMetadata("Report Fixture", "The Test Suite")
                .withBeatGrid(beats())
                .withChords(chords())
                .withKeys(List.of(Key.estimated(
                        new PitchSpelling(NoteLetter.C, Accidental.NATURAL, 4), Mode.MAJOR,
                        0, DURATION, Confidence.of(0.7), Confidence.of(0.4))));
    }

    private static BeatGrid beats() {
        List<Double> times = new ArrayList<>();
        for (int beat = 0; beat < BARS * 4; beat++) {
            // Every fourth beat is a hair late, so the interval histogram has
            // something to draw and the steady rate is not the median.
            times.add(beat * SECONDS_PER_BEAT + (beat % 4 == 3 ? 0.02 : 0));
        }
        return BeatGrid.ofTimes(times, 4, Confidence.of(0.8));
    }

    private static ChordProgression chords() {
        List<Chord> spans = new ArrayList<>();
        spans.add(Chord.noChord(0, 2 * SECONDS_PER_BEAT, Confidence.of(0.9)));
        spans.add(span(NoteLetter.C, ChordQuality.MAJOR, 2, 4, 0.85));
        spans.add(span(NoteLetter.A, ChordQuality.MINOR, 4, 8, 0.6));
        spans.add(span(NoteLetter.F, ChordQuality.MAJOR_SEVENTH, 8, 12, 0.75));
        spans.add(span(NoteLetter.G, ChordQuality.DOMINANT_SEVENTH, 12, 16, 0.5));
        return new ChordProgression(spans, Confidence.of(0.72));
    }

    private static Chord span(NoteLetter root, ChordQuality quality, double fromBeat,
                              double toBeat, double confidence) {
        return Chord.ofSeconds(new PitchSpelling(root, Accidental.NATURAL, 4), quality,
                fromBeat * SECONDS_PER_BEAT, toBeat * SECONDS_PER_BEAT,
                Confidence.of(confidence));
    }

    private static NoteTrack melody() {
        List<Note> notes = new ArrayList<>();
        int[] pitches = {72, 74, 76, 77, 76, 74, 72, 71};
        for (int i = 0; i < pitches.length; i++) {
            // Two short notes among the long ones, so the duration histogram
            // has a spread rather than one column.
            double length = (i == 2 || i == 5 ? 0.25 : 1) * SECONDS_PER_BEAT;
            notes.add(Note.ofSeconds((4 + i) * SECONDS_PER_BEAT, length, pitches[i],
                    Confidence.of(0.6 + 0.03 * i)));
        }
        return new NoteTrack(PartRole.LEAD_VOCAL, "Voice", notes, Confidence.of(0.66));
    }

    private static Lyrics lyrics() {
        List<LyricWord> first = new ArrayList<>();
        first.add(syllable("Ev", 4, true, false));
        first.add(syllable("ery", 5, false, false));
        first.add(syllable("thing", 6, false, true));
        List<LyricWord> second = new ArrayList<>();
        second.add(syllable("it", 8, false, false));
        second.add(syllable("did", 9, false, false));
        return new Lyrics(
                List.of(new LyricLine(first, Confidence.of(0.9)),
                        new LyricLine(second, Confidence.of(0.8))),
                "en", Confidence.of(0.85));
    }

    private static LyricWord syllable(String text, int beat, boolean hyphenated,
                                      boolean melisma) {
        return LyricWord.ofSeconds(text, beat * SECONDS_PER_BEAT,
                        (beat + 1) * SECONDS_PER_BEAT, Confidence.of(0.9))
                .withHyphenToNext(hyphenated)
                .withMelisma(melisma);
    }
}
