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
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
import dev.olivelli.musicwizard.core.workspace.ChromaTrace;
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
                        stage("chroma", RunManifest.Outcome.COMPUTED, null,
                                "tuning", "3.8 cents sharp of A440",
                                "spans summarised", "5"),
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
     * on, a chroma front end that read a tuning and summarised the same spans
     * the chords name, a decoder that wrote down why each of them carries its
     * label, and one stage this build's page has no phase for.
     */
    static RunTraces weighed() {
        return weighed(defaultOctave(), chroma(), chordDecisions());
    }

    /** The same, with a register reading of the caller's choosing. */
    static RunTraces weighed(BeatTrace.Octave octave) {
        return weighed(octave, chroma(), chordDecisions());
    }

    /** The same, with a chroma trace of the caller's choosing. */
    static RunTraces weighed(ChromaTrace chroma) {
        return weighed(defaultOctave(), chroma, chordDecisions());
    }

    /** The same, with a decoder trace of the caller's choosing. */
    static RunTraces weighed(ChordTrace chords) {
        return weighed(defaultOctave(), chroma(), chords);
    }

    private static BeatTrace.Octave defaultOctave() {
        return new BeatTrace.Octave(true, 6.5, 0.04, 0.82, 2, 1, true);
    }

    private static RunTraces weighed(BeatTrace.Octave octave, ChromaTrace chroma,
                                     ChordTrace chords) {
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
        collected.put(ChromaTrace.STAGE, chroma);
        collected.put(ChordTrace.STAGE, chords);
        collected.put("hummed-bass", Map.of("hummed", true));
        return RunTraceJson.of(collected);
    }

    /**
     * Why each of {@link #chords()}'s spans carries its label: one the run's own
     * chroma renamed, one whose runner-up outscored the state the decoder held —
     * which is the transition prior keeping a chord, and the one case where the
     * margin is negative.
     *
     * <p>No count rewrites anything here, and none could: every root of that
     * progression carries one run, so a count over it is all of its beats or
     * none of them. {@link #chordsSettledAcrossTheRoot()} is the recurring root.
     */
    static ChordTrace chordDecisions() {
        return new ChordTrace(
                List.of(
                        new ChordTrace.Span(0, 1, 0, 2, "N.C.", "N.C.", "decoder",
                                new ChordTrace.Candidate("N.C.", -25.54),
                                new ChordTrace.Candidate("C", -31.08),
                                null, 0, null, List.of()),
                        new ChordTrace.Span(1, 2, 2, 4, "C", "C", "decoder",
                                new ChordTrace.Candidate("C", -9.42),
                                new ChordTrace.Candidate("Am", -11.27),
                                "C", 0, 0,
                                gatesOn("0.31 0.02 0.01 0.04 0.02", "0.21 0.01 0.21 0.21 0.52",
                                        true)),
                        new ChordTrace.Span(2, 4, 4, 8, "Am", "Am", "run",
                                new ChordTrace.Candidate("A", -8.83),
                                new ChordTrace.Candidate("C", -9.91),
                                "A", -1.62, 0,
                                gatesOn("0.02 0.74 0.01 0.21 0.35", "0.22 0.01 0.22 0.22 0.56",
                                        false)),
                        new ChordTrace.Span(4, 6, 8, 12, "Fmaj7", "Fmaj7", "decoder",
                                new ChordTrace.Candidate("Fmaj7", -7.94),
                                new ChordTrace.Candidate("F", -8.61),
                                "F", 0, 4,
                                gatesOn("0.44 0.03 0.00 0.12 1.29", "0.26 0.01 0.26 0.26 0.66",
                                        true)),
                        new ChordTrace.Span(6, 8, 12, 16, "G7", "G7", "run",
                                new ChordTrace.Candidate("G", -10.55),
                                new ChordTrace.Candidate("G7", -10.21),
                                "G", 0, 0,
                                gatesOn("0.71 0.05 0.02 0.03 0.40", "0.24 0.01 0.24 0.24 0.59",
                                        true))),
                List.of(
                        new ChordTrace.Root("C",
                                new ChordTrace.Count(0, 2, "minority", 0),
                                new ChordTrace.Count(0, 2, "minority", 0)),
                        new ChordTrace.Root("F",
                                new ChordTrace.Count(0, 4, "minority", 0),
                                new ChordTrace.Count(0, 4, "minority", 0)),
                        new ChordTrace.Root("G",
                                new ChordTrace.Count(0, 4, "minority", 0),
                                new ChordTrace.Count(0, 4, "minority", 0)),
                        new ChordTrace.Root("A",
                                new ChordTrace.Count(4, 4, "majority", 0),
                                new ChordTrace.Count(0, 4, "minority", 0))));
    }

    /**
     * A root the recording puts three runs on, whose last run both per-root
     * counts rewrote — the shape {@code ChordEstimationTest.ThirdPerRoot} shows
     * the estimator producing, and the only one in which a count can act at all.
     */
    static ChordTrace chordsSettledAcrossTheRoot() {
        return new ChordTrace(
                List.of(
                        new ChordTrace.Span(0, 4, 0, 8, "A", "A", "decoder",
                                new ChordTrace.Candidate("A", -7.61),
                                new ChordTrace.Candidate("F#m", -9.02),
                                "A", 0, 0,
                                gatesOn("0.29 0.01 0.00 0.02 0.04", "0.19 0.01 0.19 0.19 0.48",
                                        true)),
                        new ChordTrace.Span(4, 6, 8, 12, "D", "D", "decoder",
                                new ChordTrace.Candidate("D", -8.14),
                                new ChordTrace.Candidate("A", -9.30),
                                "D", 0, 0,
                                gatesOn("0.34 0.02 0.01 0.03 0.05", "0.22 0.01 0.22 0.22 0.55",
                                        true)),
                        new ChordTrace.Span(6, 8, 12, 16, "A7", "Am7", "thirds",
                                new ChordTrace.Candidate("Am", -8.77),
                                new ChordTrace.Candidate("A", -8.95),
                                "A", 0, 0,
                                gatesOn("0.02 0.31 0.01 0.02 0.06", "0.19 0.01 0.19 0.19 0.48",
                                        false))),
                List.of(
                        new ChordTrace.Root("D",
                                new ChordTrace.Count(0, 4, "minority", 0),
                                new ChordTrace.Count(0, 4, "minority", 0)),
                        new ChordTrace.Root("A",
                                new ChordTrace.Count(4, 12, "minority", 1),
                                new ChordTrace.Count(4, 12, "minority", 1))));
    }

    /** A decoder that named the spans and left no reasoning behind it. */
    static ChordTrace chordsWithoutDecisions() {
        return new ChordTrace(List.of(), List.of());
    }

    /**
     * The six comparisons the quality gates make on one root: the reading each
     * gated degree left in the fit, and the bar it was held to. In both rows,
     * the major third, the minor third, the diminished fifth, the sixth and the
     * major seventh, in that order.
     *
     * <p>The major third has a row for each of its two comparisons and one
     * outcome between them, which is the shape the estimator records.
     */
    private static List<ChordTrace.Gate> gatesOn(String read, String needed,
                                                 boolean majorThirdCounted) {
        List<Double> reading = reading(read);
        List<Double> bar = reading(needed);
        List<ChordTrace.Gate> gates = new ArrayList<>();
        gates.add(new ChordTrace.Gate("major third", "share of the root",
                reading.get(0), bar.get(0), majorThirdCounted));
        gates.add(new ChordTrace.Gate("major third", "the minor third",
                reading.get(0), reading.get(1), majorThirdCounted));
        String[] degrees = {"minor third", "diminished fifth", "sixth", "major seventh"};
        for (int i = 0; i < degrees.length; i++) {
            gates.add(new ChordTrace.Gate(degrees[i], "share of the root",
                    reading.get(i + 1), bar.get(i + 1),
                    reading.get(i + 1) >= bar.get(i + 1)));
        }
        return gates;
    }

    /** What the front end read, over the same spans {@link #chords()} names. */
    static ChromaTrace chroma() {
        return new ChromaTrace(0.0375, true, fit(),
                List.of(
                        chromaSpan(0, 1, 0, 2, "N.C.",
                                reading(".08 .08 .09 .08 .1 .08 .08 .09 .08 .08 .08 .08"),
                                reading(".08 .08 .08 .08 .1 .08 .08 .08 .08 .08 .09 .09"),
                                reading(".1 .08 .08 .08 .08 .08 .08 .08 .08 .08 .09 .09"),
                                reading(".01 0 .01 0 .02 0 0 .01 0 0 0 .01")),
                        chromaSpan(1, 2, 2, 4, "C",
                                reading(".37 .01 .06 .02 .2 .04 .02 .19 .02 .03 .01 .03"),
                                reading(".31 .01 .05 .01 .25 .04 .02 .25 .01 .02 .01 .02"),
                                reading(".52 .03 .07 .04 .06 .04 .01 .12 .03 .04 .02 .02"),
                                reading("1.04 0 .02 0 .31 .01 0 .28 0 .01 0 .02")),
                        chromaSpan(2, 4, 4, 8, "Am",
                                reading(".17 .01 .03 .01 .23 .02 .01 .02 .01 .33 .01 .15"),
                                reading(".12 .01 .02 .01 .26 .02 .01 .02 .01 .35 .01 .16"),
                                reading(".09 .02 .04 .02 .12 .03 .02 .03 .02 .48 .03 .1"),
                                reading(".21 0 .01 0 .74 0 0 .02 0 1.12 0 .35")),
                        chromaSpan(4, 6, 8, 12, "Fmaj7",
                                reading(".22 .01 .02 .01 .17 .29 .01 .03 .01 .1 .01 .12"),
                                reading(".24 .01 .02 .01 .19 .26 .01 .03 .01 .09 .01 .12"),
                                reading(".12 .02 .03 .02 .09 .44 .02 .04 .02 .08 .02 .1"),
                                reading(".58 0 0 0 .44 1.31 0 .03 0 .12 0 .29")),
                        chromaSpan(6, 8, 12, 16, "G7",
                                reading(".04 .01 .26 .01 .03 .15 .01 .28 .01 .04 .01 .15"),
                                reading(".03 .01 .29 .01 .03 .13 .01 .31 .01 .03 .01 .13"),
                                reading(".06 .02 .18 .02 .04 .09 .02 .45 .02 .05 .02 .03"),
                                reading(".05 0 .71 0 .02 .34 0 1.18 0 .03 0 .4"))));
    }

    /** A front end that ran and folded nothing onto spans. */
    static ChromaTrace chromaWithoutSpans() {
        return new ChromaTrace(0, false, fit(), List.of());
    }

    private static ChromaTrace.Fit fit() {
        return new ChromaTrace.Fit(22050, 8192, 1024, 21.533203125, 172, 3, 21, 96, 45, 57, 84);
    }

    private static ChromaTrace.Span chromaSpan(double fromSeconds, double toSeconds, int fromBeat,
                                         int toBeat, String chord, List<Double> combined,
                                         List<Double> treble, List<Double> bass,
                                         List<Double> significance) {
        return new ChromaTrace.Span(fromSeconds, toSeconds, fromBeat, toBeat, chord,
                combined, treble, bass, significance);
    }

    /** Twelve readings, C first, written as a row so a fixture stays legible. */
    private static List<Double> reading(String values) {
        return Arrays.stream(values.split(" ")).map(Double::valueOf).toList();
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
