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
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.core.workspace.RunTraceJson;
import dev.olivelli.musicwizard.core.workspace.RunTraces;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The scores the analysis report's golden files are written from.
 *
 * <p>Every figure here is a constant, because a golden file compares the whole
 * page: a fixture built from a random stream or from the clock would rewrite
 * itself on every run.
 */
final class ReportFixtures {

    /** Fast enough that every beat lands on a round number of seconds. */
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
     * What one run recorded about itself: every outcome the page draws
     * differently, and a stage the page has no phase for.
     *
     * <p>Not one run the CLI could produce: it is one of each outcome.
     *
     * <p>The clock and the build are constants for the same reason every figure
     * above is: a golden file compares the whole page, and a run's own
     * timestamps would rewrite it every time.
     */
    static RunManifest run() {
        return new RunManifest(RunManifest.CURRENT_SCHEMA_VERSION, "1.2.3-test",
                "2026-01-01T00:00:00Z", "2026-01-01T00:02:03Z",
                new LinkedHashMap<>(Map.of("source", "audio")),
                List.of(
                        stage("decode", RunManifest.Outcome.COMPUTED, null,
                                "format", "MPEG-1, Layer 3",
                                "sample rate as stored", "44100 Hz",
                                "read as", "mono at 22050 Hz"),
                        stage("separation", RunManifest.Outcome.FAILED,
                                "the model could not be reached", "provider", "spleeter-2stems"),
                        stage("beats", RunManifest.Outcome.CACHED, null),
                        stage("melody", RunManifest.Outcome.SKIPPED,
                                "not asked for; analyze --melody reads one"),
                        stage("lyrics", RunManifest.Outcome.COMPUTED, null,
                                "words from", "the file song.lrc", "language", "en"),
                        stage("lyric-alignment", RunManifest.Outcome.SKIPPED,
                                "no alignment provider is configured (ml.alignmentProvider)"),
                        // A stage this build's page has no phase for, which is
                        // what a manifest written by a newer build looks like.
                        stage("hummed-bass", RunManifest.Outcome.COMPUTED, null)));
    }

    /**
     * What that run's stages weighed: a tracker whose two windows disagreed
     * about the octave, with the bass register halving the pulse they settled
     * on, and one stage this build's page has no phase for.
     */
    static RunTraces weighed() {
        return weighed(new BeatTrace.Octave(true, 6.5, 0.04, 0.82, 2, 1, true));
    }

    /** The same, with a register reading of the caller's choosing. */
    static RunTraces weighed(BeatTrace.Octave octave) {
        BeatTrace beats = new BeatTrace(240.5, 120.25, octave,
                List.of(
                        new BeatTrace.Window(0, 25, true, 240.5, 0.61, 0.88, 120.25,
                                List.of(new BeatTrace.Candidate(240.5, 0.47, true),
                                        new BeatTrace.Candidate(120.25, 0.31, false),
                                        new BeatTrace.Candidate(80.5, 0.09, false))),
                        new BeatTrace.Window(12.5, 30, false, 120.25, 0.55, 0.9, 120.25,
                                List.of(new BeatTrace.Candidate(120.25, 0.44, true),
                                        new BeatTrace.Candidate(60.0, 0.28, false)))));
        Map<String, Object> collected = new LinkedHashMap<>();
        collected.put(BeatTrace.STAGE, beats);
        collected.put("hummed-bass", Map.of("hummed", true));
        return RunTraceJson.of(collected);
    }

    private static RunManifest.StageRun stage(
            String name, RunManifest.Outcome outcome, String reason, String... facts) {
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = 0; i + 1 < facts.length; i += 2) {
            table.put(facts[i], facts[i + 1]);
        }
        return new RunManifest.StageRun(name, outcome, reason, table);
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

    /**
     * A grid whose pulse is exact and whose bar phase wanders — a tracker that
     * kept the beat and lost the bar, which is what the chart's bar-line veto
     * refuses whole.
     */
    static Score jitteredGrid() {
        return gridded(0, 4, 10, 12);
    }

    /** The same grid barred as the pulse implies, which the veto admits. */
    static Score evenGrid() {
        return gridded(0, 4, 8, 12);
    }

    /**
     * Harmony over a grid that marks no bar at all, which leaves the chart no
     * phase to hang on and nothing to refuse.
     */
    static Score noDownbeats() {
        return gridded();
    }

    private static Score gridded(int... downbeats) {
        Set<Integer> marked = Arrays.stream(downbeats).boxed().collect(Collectors.toSet());
        List<BeatGrid.Beat> beats = new ArrayList<>();
        int position = 0;
        for (int beat = 0; beat < BARS * 4; beat++) {
            boolean downbeat = marked.contains(beat);
            position = downbeat ? 0 : position + 1;
            beats.add(new BeatGrid.Beat(beat * SECONDS_PER_BEAT, downbeat, position));
        }
        return withHarmony().withBeatGrid(
                new BeatGrid(beats, Confidence.of(0.8), Confidence.of(0.5)));
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

    /**
     * The same score, with a syllable that starts after another and finishes
     * before it -- which is what recognition spans on sung speech do.
     */
    static Score overlappingSyllables() {
        return withHarmony().withLyrics(new Lyrics(List.of(new LyricLine(List.of(
                LyricWord.ofSeconds("held", 4 * SECONDS_PER_BEAT, 15 * SECONDS_PER_BEAT,
                        Confidence.of(0.9)),
                LyricWord.ofSeconds("brief", 5 * SECONDS_PER_BEAT, 6 * SECONDS_PER_BEAT,
                        Confidence.of(0.9))), Confidence.of(0.9))),
                "en", Confidence.of(0.85)));
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
