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

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.core.workspace.RunTraces;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The phase-by-phase half of {@link AnalysisReport}.
 *
 * <p>Each stage gets the same three questions — what reached it, what it
 * decided, what it left behind — answered from the workspace alone. A stage
 * whose decisions the workspace does not hold says so in that many words and
 * names the issue tracking it, because a picture of reasoning nobody recorded
 * would be a picture of this class's guesses.
 */
final class ReportPhases {

    /** What the workspace can say about a stage. */
    private record Status(String cssClass, String label) {

        /** It ran and its output is on disk. */
        static final Status RECORDED = new Status("recorded", "output on disk");
        /** It left nothing behind, but this page can run it again and show that. */
        static final Status RECOMPUTED = new Status("recomputed", "recomputed here");
        /** The workspace does not say whether it ran, or what it decided. */
        static final Status UNTRACED = new Status("untraced", "no trace kept");
        /** Its output would be in the score, and is not. */
        static final Status ABSENT = new Status("absent", "nothing in the score");

        /** What the run itself said, for a stage the score cannot speak for. */
        static Status of(RunManifest.StageRun entry) {
            return new Status(ReportRun.cssClass(entry.outcome()),
                    ReportRun.label(entry.outcome()));
        }
    }

    /** How many buckets a duration or interval histogram is drawn in. */
    private static final int HISTOGRAM_BUCKETS = 24;

    /** The tallest column of a histogram, in pixels. */
    private static final int HISTOGRAM_HEIGHT = 60;

    /** The tallest tick of a tempo-candidate lane, in pixels. */
    private static final int LANE_HEIGHT = 14;

    private final Score score;
    private final NoteTrack melody;
    private final NoteTrack playable;
    private final QuantizedScore quantized;
    private final RunManifest manifest;
    private final RunTraces traces;
    private final HtmlWriter out = new HtmlWriter();
    private int number;
    private String phase;

    ReportPhases(Score score, NoteTrack melody, NoteTrack playable, QuantizedScore quantized,
                 RunManifest manifest, RunTraces traces) {
        this.score = score;
        this.melody = melody;
        this.playable = playable;
        this.quantized = quantized;
        this.manifest = manifest;
        this.traces = traces;
    }

    String render() {
        decode();
        separation();
        beatsAndTempo();
        chroma();
        chords();
        key();
        melodyNotes();
        lyrics();
        quantization();
        reduction();
        return out.toString();
    }

    // ---------------------------------------------------------------- phases

    private void decode() {
        // Two stages under one phase, and never both: reading a MIDI file is
        // what happens instead of decoding, so either answers the phase.
        Optional<RunManifest.StageRun> read =
                recorded("decode").or(() -> recorded("read-midi"));
        boolean symbolic = read.map(entry -> entry.stage().equals("read-midi")).orElse(false);
        open("decode", "Decode", read.map(Status::of).orElse(Status.UNTRACED),
                "A recording is read to mono samples at the analysis rate, and everything"
                        + " after this point works from those samples. A score read from a"
                        + " MIDI file is read symbolically instead and decodes nothing.");
        inOut("the source file in the workspace",
                "nothing — neither arm has a choice to make",
                symbolic
                        ? "the events the file declares, and how long it plays"
                        : "one signal, and how long the recording is");
        whatRan("read-midi");
        facts(fact("Length", ReportTimeline.clock(score.durationSeconds())
                + "  (" + HtmlWriter.number(score.durationSeconds(), 2) + "s)"));
        if (read.isEmpty()) {
            gap("Nothing in this workspace says what this file held, or the rate it was"
                    + " read at (#674).");
        }
        close();
    }

    private void separation() {
        open("separation", "Separation", Status.UNTRACED,
                "A separator splits the mix into a vocal and an accompaniment. Only the"
                        + " melody stage reads it. Chords are always estimated from the full"
                        + " mix, because separation artifacts destroy the partial structure"
                        + " chroma estimation depends on.");
        inOut("the decoded mix",
                "whether a separator could be reached at all",
                "a vocal stem, held in memory and never written to the workspace");
        gapUnlessRecorded("Nothing in this workspace says whether a separator ran, or"
                + " which provider it was (#674).");
        close();
    }

    private void beatsAndTempo() {
        boolean tracked = score.beatGrid().isPresent();
        open(BeatTrace.STAGE, "Beat tracking, tempo and the bar axis",
                tracked ? Status.RECORDED : Status.ABSENT,
                "Onsets are detected, a pulse is tracked through them, and the phase of"
                        + " the bar is chosen from where the harmony changes. The tempo map"
                        + " built on that grid is the only sanctioned conversion between"
                        + " seconds and beats; every stage below reads it rather than a rate"
                        + " of its own.");
        inOut("the decoded mix, and frame-level chroma for the harmonic rhythm",
                "the pulse, which pulse begins a bar, and the rate to hold",
                "a beat grid and a tempo map");
        if (!tracked) {
            note("This score carries no beat grid. That is what every score read from a"
                    + " MIDI file looks like, where the beats are declared rather than"
                    + " heard; on the audio path it means the tracker found no pulse.");
        }
        TempoMap tempoMap = score.tempoMap();
        List<Fact> table = new ArrayList<>();
        score.beatGrid().ifPresent(grid -> {
            table.add(fact("Beats tracked", String.valueOf(grid.size())));
            table.add(fact("Downbeats", String.valueOf(grid.downbeatTimes().size())));
            ReportFacts.PulseSpread spread = ReportFacts.pulseSpread(grid);
            if (spread != null) {
                table.add(fact("Steady pulse rate",
                        ReportTimeline.bpm(spread.steady()) + " a minute"));
                table.add(fact("Median pulse rate",
                        ReportTimeline.bpm(spread.median()) + " a minute"));
                table.add(fact("Shortest gap between beats",
                        HtmlWriter.number(spread.shortest(), 3) + "s"));
                table.add(fact("Longest gap between beats",
                        HtmlWriter.number(spread.longest(), 3) + "s"));
            }
            grid.pulseQuarters().ifPresent(quarters -> table.add(fact(
                    "Quarter notes in a tracked pulse", HtmlWriter.number(quarters, 3))));
        });
        table.add(fact("Meter", tempoMap.initialTimeSignature().numerator() + "/"
                + tempoMap.initialTimeSignature().denominator()));
        table.add(fact("Meter changes", String.valueOf(tempoMap.meterChanges().size() - 1)));
        table.add(fact("Tempo segments", String.valueOf(tempoMap.segments().size())));
        table.add(fact("Tempo at the start",
                ReportTimeline.bpm(tempoMap.initialTempo()) + " quarter notes a minute"));
        table.add(fact("Average tempo past the lead-in",
                ReportTimeline.bpm(tempoMap.averageTempoIgnoringLeadIn(score.durationSeconds()))
                        + " quarter notes a minute"));
        facts(table.toArray(new Fact[0]));
        confidences(score.beatGrid().map(grid -> List.of(
                new Reading("Confidence in the beats", grid.beatConfidence()),
                new Reading("Confidence in the bar phase", grid.downbeatConfidence())))
                .orElse(List.of()));
        provenance(tempoMap);
        score.beatGrid().ifPresent(this::beatIntervalHistogram);
        Optional<BeatTrace> trace = traces == null
                ? Optional.empty() : traces.trace(BeatTrace.STAGE, BeatTrace.class);
        // Only where a tracker ran: a score with no grid was read from a MIDI
        // file or found no pulse, and neither is a missing record.
        trace.ifPresentOrElse(this::howThePulseWasChosen, () -> {
            if (tracked) {
                gap("This workspace does not hold what the tracker weighed: the tempo"
                        + " candidates, and whether the bass register moved the octave."
                        + " Re-analysing it records them.");
            }
        });
        barAxis();
        close();
    }

    /**
     * What the tracker chose between, from the trace the run left (#675).
     *
     * <p>Not the same question as how good the grid is: a window can weigh its
     * rivals and still be wrong, and the confidences above are what say so.
     */
    private void howThePulseWasChosen(BeatTrace trace) {
        out.element("h4", "What the tracker chose between").line("");
        List<Fact> table = new ArrayList<>();
        table.add(fact("Analysis windows", String.valueOf(trace.windows().size())));
        table.add(fact("Pulse the windows agreed on",
                ReportTimeline.bpm(trace.agreedPulse()) + " a minute"));
        BeatTrace.Octave octave = trace.octave();
        if (octave == null) {
            table.add(fact("Bass register", "not read, so the octave is where the envelope"
                    + " and the tempo prior put it"));
        } else if (octave.windowsRead() == 0) {
            // Every figure below is absent together in this one case, and a
            // page that printed them would be printing the absence as a
            // measurement.
            table.add(fact("Bass register", "read, and no window of it held enough tracked"
                    + " beats to measure, so the octave is where the envelope and the"
                    + " tempo prior put it"));
        } else {
            table.add(fact("Bass register", octave.halved()
                    ? "states only every second beat of that pulse, so it was halved to "
                            + ReportTimeline.bpm(trace.referencePulse()) + " a minute"
                    : "leaves that pulse where it is"));
            table.add(fact("Windows the register was read over",
                    octave.windowsRead() + ", of which " + octave.windowsRefused()
                            + " marked too few beats to read"));
            table.add(fact("Marked-beat contrast", Double.isFinite(octave.contrast())
                    ? HtmlWriter.number(octave.contrast(), 2)
                    : "unbounded — the register is silent between the beats, which is"
                            + " as strongly as a grid can be stated"));
            table.add(fact("Evenness of the two half-beats",
                    HtmlWriter.number(octave.parity(), 2)));
            table.add(fact("Share of the louder half the register marks",
                    HtmlWriter.number(100 * octave.statedShare(), 0) + "%"));
            table.add(fact("The envelope's own ranking", octave.envelopePrefersHalf()
                    ? "puts the halved rate above it" : "keeps that pulse above the halved rate"));
        }
        facts(table.toArray(new Fact[0]));
        candidateLanes(trace);
        windowTable(trace);
    }

    /**
     * How the chart hangs its bar lines, recomputed here (#675).
     *
     * <p>Recomputed rather than read back, because it is decided from the score
     * at engraving time and this page holds the same score.
     */
    private void barAxis() {
        Optional<ChartLayout.Axis> axis = ChartLayout.axis(score);
        if (axis.isEmpty()) {
            return;
        }
        ChartLayout.Axis reading = axis.get();
        out.element("h4", "How the chart hangs its bar lines").line("");
        note(switch (reading.hungOn()) {
            case DOWNBEATS -> "Every bar the grid marks is a plausible bar, so the tracked"
                    + " downbeats are the bar lines and the chart is not uniform in seconds"
                    + " (#187). What the bar lines are wrong by is what the grid is wrong"
                    + " by, and nothing else.";
            case AGREED_OFFSET -> "The grid's downbeats are not the bar lines: "
                    + reading.refusedBecause() + ". The chart is one bar length throughout,"
                    + " hung on the offset within the bar that leaves the smallest total"
                    + " distance to every downbeat (#233).";
            case FIRST_DOWNBEAT -> "The grid's downbeats are not the bar lines: "
                    + reading.refusedBecause() + ". The chart is one bar length throughout,"
                    + " hung on the first downbeat, the downbeats having agreed on no offset"
                    + " within a counted beat (#233).";
            case FIRST_CHORD -> "The grid marks no downbeat, so there is no bar phase to"
                    + " hang on at all. The chart is one bar length throughout, opening"
                    + " where the harmony starts.";
        });
        facts(fact("Downbeats the grid marks", String.valueOf(reading.downbeats())));
    }

    private void chroma() {
        open("chroma", "Chroma", Status.UNTRACED,
                "An NNLS fit over the spectrum gives the energy on each of the twelve pitch"
                        + " classes, tuning-corrected, in a bass register and a treble one."
                        + " It is taken from the full mix and never from a stem, and it is"
                        + " what both the beat tracker's harmonic rhythm and every chord"
                        + " decision below are read from.");
        inOut("the decoded mix, and the tracked beats to average over",
                "the recording's tuning, and how the fit divides between the registers",
                "beat-synchronous chroma, and the fit's residual");
        gap("None of it is written to the workspace: not the tuning, not the frames, not"
                + " the residual. So the page can show what the chroma was used for but"
                + " not what it looked like (#676).");
        close();
    }

    private void chords() {
        boolean any = !score.chords().isEmpty();
        open("chords", "Chord decoding and the quality decision",
                any ? Status.RECORDED : Status.ABSENT,
                "On a recording, a decoder reads a root out of both registers with the bass"
                        + " as a prior over roots, and the quality out of the treble once per"
                        + " chord rather than once per beat; the two vocabularies are not the"
                        + " same one, since a quality the decoder may choose competes across"
                        + " roots. A score read from a MIDI file has its chords named from"
                        + " the notes the file declares instead.");
        inOut("beat-synchronous chroma, its residual and the tracked beats on a recording;"
                        + " the declared notes on a MIDI file",
                "where a chord changes, its root, and its quality",
                "one labelled span per chord");
        if (!any) {
            note("This score holds no chord progression.");
            close();
            return;
        }
        List<Chord> spans = score.chords().chords();
        Set<String> roots = new LinkedHashSet<>();
        int noChord = 0;
        for (Chord chord : spans) {
            if (chord.isNoChord()) {
                noChord++;
            } else {
                roots.add(chord.root().displayName());
            }
        }
        facts(fact("Spans", String.valueOf(spans.size())),
                fact("Spans named N.C.", String.valueOf(noChord)),
                fact("Distinct roots", String.valueOf(roots.size())),
                fact("Shortest span", HtmlWriter.number(
                        spans.stream().mapToDouble(Chord::durationSeconds).min().orElse(0), 2)
                        + "s"),
                fact("Longest span", HtmlWriter.number(
                        spans.stream().mapToDouble(Chord::durationSeconds).max().orElse(0), 2)
                        + "s"));
        confidences(List.of(new Reading("Confidence in the progression",
                score.chords().confidence())));
        qualityChart();
        rootLegend(spans);
        chordTable(spans);
        gap("Which candidate roots lost, what the residual gate admitted or refused, how"
                + " the third was settled across the run, and what the bass prior"
                + " contributed are all decided inside the estimator and none of it is"
                + " recorded. Only the answer reaches the workspace (#677).");
        close();
    }

    private void key() {
        boolean any = !score.keys().isEmpty();
        open("key", "Key", any ? Status.RECORDED : Status.ABSENT,
                "On a recording the key is read from the estimated chords rather than from"
                        + " chroma, and it is two decisions of very different reliability:"
                        + " which key signature the piece is written in, and which of a"
                        + " relative pair is home. The second is the one that fails. A score"
                        + " read from a MIDI file takes the key its own meta event declares,"
                        + " and is certain of it because the file said so.");
        inOut("the chord spans on a recording; a declared key signature on a MIDI file",
                "a key signature, and a tonic within it",
                "one key span, with a confidence for each decision");
        if (!any) {
            note("This score carries no key. On a recording that is what happens when no"
                    + " chord sounds; a MIDI file that declares no key signature leaves"
                    + " none either, however much of it sounds.");
            close();
            return;
        }
        List<Fact> table = new ArrayList<>();
        List<Reading> readings = new ArrayList<>();
        for (Key key : score.keys()) {
            table.add(fact("Key", key.displayName()));
            table.add(fact("Key signature", accidentals(key)));
            table.add(fact("Sounds from", ReportTimeline.clock(key.startSeconds())
                    + " to " + ReportTimeline.clock(key.endSeconds())));
            key.signatureConfidence().ifPresent(confidence -> readings.add(
                    new Reading("Confidence in the key signature", confidence)));
            key.tonicConfidence().ifPresent(confidence -> readings.add(
                    new Reading("Confidence in which of the pair is home", confidence)));
            readings.add(new Reading("Confidence in the key as a whole", key.confidence()));
        }
        facts(table.toArray(new Fact[0]));
        confidences(readings);
        gap("The chord evidence each of the two decisions weighed is not recorded, so the"
                + " page can show that they disagree but not why (#678).");
        close();
    }

    private void melodyNotes() {
        boolean any = melody != null && !melody.isEmpty();
        open("melody", "Melody notes", any ? Status.RECORDED : Status.ABSENT,
                "A monophonic pitch tracker follows the loudest periodic line in whatever"
                        + " signal it is handed, and the result is segmented into notes. On"
                        + " a full mix that line is usually not the singer, which is why the"
                        + " stage is off unless it is asked for.");
        inOut("the vocal stem where a separator could be had, and the mix otherwise",
                "where one note ends and the next begins",
                "a note track, in seconds");
        if (!any) {
            note("This score holds no melody part"
                    + (recorded().isPresent() ? "." : "; see --melody on analyze."));
            close();
            return;
        }
        double[] durations = melody.notes().stream()
                .mapToDouble(Note::durationSeconds).toArray();
        double[] sorted = durations.clone();
        Arrays.sort(sorted);
        facts(fact("Notes", String.valueOf(melody.size())),
                fact("Range", melody.pitchRange().map(Object::toString).orElse("none")),
                fact("Shortest note", HtmlWriter.number(sorted[0], 3) + "s"),
                fact("Median note", HtmlWriter.number(sorted[sorted.length / 2], 3) + "s"),
                fact("Longest note",
                        HtmlWriter.number(sorted[sorted.length - 1], 3) + "s"));
        confidences(List.of(new Reading("Confidence in the track", melody.confidence())));
        histogram("How long the notes are, in seconds", durations);
        gapUnlessRecorded("Nothing in this workspace says which signal these notes were"
                + " read from (#674).");
        gap("The pitch track and the voicedness the segmentation read are not recorded, so"
                + " the page shows the notes and not the decision that cut them (#679).");
        close();
    }

    private void lyrics() {
        boolean any = !score.lyrics().isEmpty();
        open("lyrics", "Lyric alignment", any ? Status.RECORDED : Status.ABSENT,
                "Words come either from an LRC file the user supplied or from the"
                        + " transcriber, are split into syllables by the hyphenation patterns"
                        + " for their language, and are placed against the vocal stem.");
        inOut("a vocal stem, and either supplied words or transcribed ones",
                "where each syllable is sung, and which syllable the melody moves under",
                "lines of timed syllables");
        // Three stages under one phase: where the words came from, what the
        // recognizer did when it was asked, and whether anything measured them.
        whatRan("lyric-transcription");
        whatRan("lyric-alignment");
        if (!any) {
            note("This score holds no lyrics.");
            close();
            return;
        }
        List<LyricWord> words = score.lyrics().allWords();
        long hyphenated = words.stream().filter(LyricWord::hyphenatedToNext).count();
        long melismas = words.stream().filter(LyricWord::melisma).count();
        facts(fact("Language", score.lyrics().language()),
                fact("Lines", String.valueOf(score.lyrics().lines().size())),
                fact("Syllables", String.valueOf(words.size())),
                fact("Syllables hyphenated to the next", String.valueOf(hyphenated)),
                fact("Syllables marked as a melisma", String.valueOf(melismas)),
                fact("First sung at", ReportTimeline.clock(words.get(0).startSeconds())),
                // The latest end rather than the last word's, because sung spans
                // overlap and allWords() is ordered by where a syllable starts.
                fact("Last sung at", ReportTimeline.clock(words.stream()
                        .mapToDouble(LyricWord::endSeconds).max().orElse(0))));
        confidences(List.of(new Reading("Confidence in the alignment",
                score.lyrics().confidence())));
        lineTable();
        gapUnlessRecorded("Nothing in this workspace says whether the words were supplied"
                + " or transcribed (#674).");
        gap("What the aligner did inside a line — the window it searched, and why a line"
                + " kept its parsed times instead of being measured — is not recorded"
                + " (#684).");
        close();
    }

    private void quantization() {
        open("quantization", "Quantization", Status.RECOMPUTED,
                "Every sounding thing is put on a grid of beat subdivisions, chosen per bar,"
                        + " because a page has to name a duration. Three onsets a third of a"
                        + " beat apart and three a half beat apart are both legal on the"
                        + " underlying grid, so the subdivision is a decision and not a"
                        + " rounding.");
        inOut("the score's notes, chords, keys and sections, and the tempo map",
                "a subdivision per bar, and a swing feel",
                "the same music on the beat axis");
        note("The grid below was recomputed for this page exactly as the engraver"
                + " recomputes it. It is not persisted: the workspace keeps the snapped"
                + " positions, and the chosen subdivision cannot be read back off them.");
        List<Fact> table = new ArrayList<>();
        table.add(fact("Bars on the tempo map's axis", String.valueOf(
                ReportFacts.barCount(score.tempoMap(), score.durationSeconds()))));
        table.add(fact("Bars with a chosen subdivision",
                String.valueOf(quantized.grids().size())));
        table.add(fact("Swing", quantized.swing().displayName()));
        table.add(fact("Chords placed on the beat axis",
                String.valueOf(quantized.score().chords().chords().stream()
                        .filter(Chord::isQuantized).count())));
        facts(table.toArray(new Fact[0]));
        if (quantized.grids().isEmpty()) {
            note("No subdivision was chosen for any bar, which is the ordinary case for a"
                    + " score with no notes in it: chords are put on the beat axis without"
                    + " needing one.");
        } else {
            gridChart();
            gridStrip();
        }
        close();
    }

    private void reduction() {
        boolean any = playable != null && !playable.isEmpty();
        open("reduction", "Reduction to the playable part",
                any ? Status.RECOMPUTED : Status.ABSENT,
                "The estimate is reduced to one note per sung syllable, so a player reads a"
                        + " line rather than every pitch the tracker returned. It is an"
                        + " arrangement of MW's own estimate rather than a reading of the"
                        + " recording, which is why it is written only when it is asked for.");
        inOut("the melody notes, the syllables, the chords and the tempo map",
                "which notes belong to one syllable, and which head that syllable prints",
                "a second note track, drawn under the estimate on the timeline above");
        if (!any) {
            note("There is no melody to reduce.");
            close();
            return;
        }
        ReportFacts.Reduction reduction = ReportFacts.reduction(melody, playable);
        facts(fact("Notes in the estimate", String.valueOf(reduction.estimateNotes())),
                fact("Notes in the playable part", String.valueOf(reduction.playableNotes())),
                fact("Carried through untouched", String.valueOf(reduction.carried())));
        gap("Which rule accounted for each note the estimate lost — absorbed as an"
                + " ornament, claimed by a neighbouring syllable, or pulled back from an"
                + " excursion — is decided inside the reduction and not recorded (#680).");
        close();
    }

    // --------------------------------------------------------------- pieces

    private record Fact(String name, String value) {
    }

    private record Reading(String name, Confidence confidence) {
    }

    private static Fact fact(String name, String value) {
        return new Fact(name, value);
    }

    /**
     * Opens a phase under what the workspace holds for it, which is the score
     * where the score speaks for the stage and the run's own record where
     * nothing else can.
     *
     * <p>The two answer different questions and can disagree without either
     * being wrong — a run served a cached score ran no stage, and the score
     * still holds every stage's output — so each label is worded on its own
     * axis. The phase's own name is the stage's name in the manifest, which is
     * what lets a stage that starts recording light up its phase without this
     * class being taught about it.
     */
    private void open(String id, String title, Status fallback, String what) {
        phase = id;
        Status status = fallback.equals(Status.UNTRACED)
                ? recorded().map(Status::of).orElse(fallback)
                : fallback;
        number++;
        out.open("article", "class", "phase " + status.cssClass(), "id", "phase-" + id);
        out.open("h3");
        out.element("span", String.valueOf(number), "class", "phase-number");
        out.text(title);
        out.element("span", status.label(), "class", "status");
        out.line("</h3>");
        out.open("p", "class", "what").text(what).line("</p>");
    }

    /** What the run recorded about the phase being written, if anything. */
    private Optional<RunManifest.StageRun> recorded() {
        return recorded(phase);
    }

    /** The same for a stage of another name. */
    private Optional<RunManifest.StageRun> recorded(String stage) {
        return manifest == null ? Optional.empty() : manifest.stage(stage);
    }

    private void close() {
        out.line("</article>");
    }

    private void inOut(String in, String decided, String produced) {
        out.line("<dl class=\"io\">");
        out.element("dt", "In").open("dd").text(in).line("</dd>");
        out.element("dt", "Decided").open("dd").text(decided).line("</dd>");
        out.element("dt", "Out").open("dd").text(produced).line("</dd>");
        out.line("</dl>");
        whatRan();
    }

    /**
     * What the run wrote down about this phase, said as the run's own
     * statement rather than as the page's: the two can disagree, and a reader
     * has to be able to see which is which.
     */
    private void whatRan() {
        whatRan(phase);
    }

    /** The same for a stage whose record belongs under a phase of another name. */
    private void whatRan(String stage) {
        recorded(stage).ifPresent(entry -> {
            out.open("p", "class", "ran");
            out.element("span", "Last run", "class", "ran-label");
            out.text(stage.replace('-', ' ') + ": " + ReportRun.label(entry.outcome())
                    + (entry.reason() == null || entry.reason().isBlank()
                            ? "" : " — " + entry.reason()));
            out.line("</p>");
            facts(entry.facts().entrySet().stream()
                    .map(fact -> fact(fact.getKey(), fact.getValue()))
                    .toArray(Fact[]::new));
        });
    }

    private void facts(Fact... entries) {
        if (entries.length == 0) {
            return;
        }
        out.line("<dl class=\"facts\">");
        for (Fact entry : entries) {
            out.element("dt", entry.name());
            out.element("dd", entry.value());
            out.line("");
        }
        out.line("</dl>");
    }

    private void confidences(List<Reading> readings) {
        if (readings.isEmpty()) {
            return;
        }
        out.line("<div class=\"readings\">");
        for (Reading reading : readings) {
            out.open("div", "class", "reading");
            out.element("span", reading.name(), "class", "reading-name");
            out.open("span", "class", "bar");
            out.empty("span", "class", "fill",
                    "style", "width:" + percent(reading.confidence()));
            out.line("</span>");
            out.element("span", percent(reading.confidence()), "class", "reading-value");
            out.line("</div>");
        }
        out.line("</div>");
    }

    private void note(String text) {
        out.open("p", "class", "note").text(text).line("</p>");
    }

    private void gap(String text) {
        out.open("p", "class", "gap");
        out.element("span", "Not recorded", "class", "gap-label");
        out.text(text).line("</p>");
    }

    /**
     * A gap the workspace's record of its run fills where it names the stage
     * (#674).
     *
     * <p>Worded about the workspace rather than about the tool, because a
     * workspace that records nothing and a record that does not reach this
     * stage leave the reader in the same place; which of the two it is, the
     * page says once, above.
     */
    private void gapUnlessRecorded(String text) {
        if (recorded().isEmpty()) {
            gap(text);
        }
    }

    // -------------------------------------------------------------- charts

    private void qualityChart() {
        Map<ChordQuality, Integer> counts = ReportFacts.chordQualities(score.chords());
        int highest = counts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        out.line("<div class=\"tally\">");
        for (Map.Entry<ChordQuality, Integer> entry : counts.entrySet()) {
            out.open("div", "class", "tally-row");
            out.element("span", qualityName(entry.getKey()), "class", "tally-name");
            out.open("span", "class", "bar");
            out.empty("span", "class", "fill", "style",
                    "width:" + HtmlWriter.number(100.0 * entry.getValue() / highest, 2) + "%");
            out.line("</span>");
            out.element("span", String.valueOf(entry.getValue()), "class", "tally-value");
            out.line("</div>");
        }
        out.line("</div>");
    }

    private void gridChart() {
        Map<GridResolution, Integer> counts = ReportFacts.gridResolutions(quantized);
        int highest = counts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        out.line("<div class=\"tally\">");
        for (Map.Entry<GridResolution, Integer> entry : counts.entrySet()) {
            out.open("div", "class", "tally-row");
            out.element("span", entry.getKey().divisionsPerBeat() + " per beat",
                    "class", "tally-name");
            out.open("span", "class", "bar");
            // In the colour the strip below draws that subdivision, so the two
            // can be read against each other.
            out.empty("span", "class", "fill", "style",
                    "width:" + HtmlWriter.number(100.0 * entry.getValue() / highest, 2) + "%"
                            + ";background:" + gridColour(entry.getKey()));
            out.line("</span>");
            out.element("span", String.valueOf(entry.getValue()), "class", "tally-value");
            out.line("</div>");
        }
        out.line("</div>");
    }

    /** What each colour on the chord lane above means. */
    private void rootLegend(List<Chord> spans) {
        Map<Integer, String> roots = new TreeMap<>();
        boolean anyNoChord = false;
        for (Chord chord : spans) {
            if (chord.isNoChord()) {
                anyNoChord = true;
            } else {
                roots.putIfAbsent(chord.root().pitchClass(), chord.root().displayName());
            }
        }
        out.open("div", "class", "legend");
        out.element("span", "On the timeline:", "class", "legend-lead");
        for (Map.Entry<Integer, String> root : roots.entrySet()) {
            out.open("span", "class", "chip");
            out.empty("span", "class", "swatch",
                    "style", "background:" + ReportTimeline.rootColour(root.getKey()));
            out.text(root.getValue());
            out.line("</span>");
        }
        if (anyNoChord) {
            out.open("span", "class", "chip");
            out.empty("span", "class", "swatch",
                    "style", "background:" + ReportTimeline.NO_CHORD_COLOUR);
            out.text("no chord");
            out.line("</span>");
        }
        out.line("</div>");
    }

    /** One block per bar, coloured by the subdivision that bar was quantized on. */
    private void gridStrip() {
        out.open("div", "class", "strip",
                "title", "one block per bar, in order, coloured by its subdivision");
        for (BarGrid grid : quantized.grids()) {
            out.empty("span", "class", "cell",
                    "style", "background:" + gridColour(grid.resolution()),
                    "title", "bar " + (grid.bar() + 1) + ", "
                            + grid.resolution().divisionsPerBeat() + " per beat");
        }
        out.line("</div>");
    }

    /**
     * One lane per analysis window, with a tick at every rate its sweep
     * weighed.
     *
     * <p>Each lane is scaled to its own strongest candidate: a score is
     * comparable within a window and not between windows.
     */
    private void candidateLanes(BeatTrace trace) {
        List<BeatTrace.Candidate> every = trace.everyCandidate();
        double low = every.stream().mapToDouble(BeatTrace.Candidate::beatsPerMinute)
                .min().orElse(0);
        double high = every.stream().mapToDouble(BeatTrace.Candidate::beatsPerMinute)
                .max().orElse(0);
        if (!(high > low)) {
            return;
        }
        out.line("<figure class=\"tempo-lanes\">");
        for (BeatTrace.Window window : trace.windows()) {
            double strongest = window.candidates().stream()
                    .mapToDouble(BeatTrace.Candidate::score).max().orElse(0);
            out.open("div", "class", "tempo-lane");
            out.element("span", ReportTimeline.clock(window.fromSeconds()),
                    "class", "tempo-time");
            out.open("span", "class", "tempo-track");
            for (BeatTrace.Candidate candidate : window.candidates()) {
                double height = strongest > 0
                        ? LANE_HEIGHT * candidate.score() / strongest : 0;
                out.empty("span", "class", candidate.chosen() ? "tempo-tick chosen" : "tempo-tick",
                        "style", "left:" + HtmlWriter.number(
                                100 * (candidate.beatsPerMinute() - low) / (high - low), 2) + "%"
                                + ";height:" + HtmlWriter.number(height, 2) + "px",
                        "title", ReportTimeline.bpm(candidate.beatsPerMinute()) + " a minute"
                                + (candidate.chosen() ? ", this window's seed" : ""));
            }
            out.line("</span>");
            out.line("</div>");
        }
        out.open("div", "class", "axis");
        out.element("span", ReportTimeline.bpm(low));
        out.element("span", ReportTimeline.bpm(high));
        out.line("</div>");
        out.element("figcaption", "The rates each window's sweep weighed, one lane per window"
                + " down the recording; the taller tick is the stronger candidate within its"
                + " own lane, and the marked one is the rate that window was seeded with."
                + " A rival at half or double the seed is the reading a listener is most"
                + " likely to disagree with.");
        out.line("</figure>");
    }

    /** Every window's seed, and the rate it was folded onto. */
    private void windowTable(BeatTrace trace) {
        out.line("<details class=\"table\">");
        out.element("summary", "Every analysis window");
        out.line("<table><thead><tr><th>#</th><th>From</th><th>To</th><th>Seeded at</th>"
                + "<th>Tracked at</th><th>Periodicity</th><th>Peakiness</th>"
                + "<th>Voted</th></tr></thead><tbody>");
        List<BeatTrace.Window> windows = trace.windows();
        for (int i = 0; i < windows.size(); i++) {
            BeatTrace.Window window = windows.get(i);
            out.open("tr");
            out.element("td", String.valueOf(i + 1));
            out.element("td", ReportTimeline.clock(window.fromSeconds()));
            out.element("td", ReportTimeline.clock(window.toSeconds()));
            out.element("td", ReportTimeline.bpm(window.seedPulse()));
            out.element("td", ReportTimeline.bpm(window.trackedPulse()));
            out.element("td", HtmlWriter.number(window.periodicity(), 3));
            out.element("td", HtmlWriter.number(window.peakiness(), 3));
            out.element("td", window.voted() ? "yes" : "no");
            out.line("</tr>");
        }
        out.line("</tbody></table>");
        out.line("</details>");
    }

    private void beatIntervalHistogram(BeatGrid grid) {
        histogram("How far apart the tracked beats are, in seconds",
                ReportFacts.beatIntervals(grid));
    }

    /** Nothing is drawn for values that all sit at one place, which has no spread. */
    private void histogram(String caption, double[] values) {
        double low = Arrays.stream(values).min().orElse(0);
        double high = Arrays.stream(values).max().orElse(0);
        if (values.length == 0 || !(high > low)) {
            return;
        }
        int[] counts = ReportFacts.histogram(values, low, high, HISTOGRAM_BUCKETS);
        int highest = Arrays.stream(counts).max().orElse(1);
        out.line("<figure class=\"histogram\">");
        out.open("div", "class", "columns");
        for (int count : counts) {
            out.empty("span", "class", "column",
                    "style", "height:" + HtmlWriter.number(
                            HISTOGRAM_HEIGHT * (double) count / Math.max(1, highest), 2) + "px",
                    "title", count + " of them");
        }
        out.line("</div>");
        out.open("div", "class", "axis");
        out.element("span", HtmlWriter.number(low, 3));
        out.element("span", HtmlWriter.number(high, 3));
        out.line("</div>");
        out.element("figcaption", caption);
        out.line("</figure>");
    }

    private void chordTable(List<Chord> spans) {
        out.line("<details class=\"table\">");
        out.element("summary", "Every chord span");
        out.line("<table><thead><tr><th>#</th><th>Chord</th><th>From</th><th>To</th>"
                + "<th>Length</th><th>Confidence</th></tr></thead><tbody>");
        for (int i = 0; i < spans.size(); i++) {
            Chord chord = spans.get(i);
            out.open("tr");
            out.element("td", String.valueOf(i + 1));
            out.element("td", chord.symbol(), "class", "symbol");
            out.element("td", ReportTimeline.clock(chord.startSeconds()));
            out.element("td", ReportTimeline.clock(chord.endSeconds()));
            out.element("td", HtmlWriter.number(chord.durationSeconds(), 2) + "s");
            out.element("td", percent(chord.confidence()));
            out.line("</tr>");
        }
        out.line("</tbody></table>");
        out.line("</details>");
    }

    private void lineTable() {
        out.line("<details class=\"table\">");
        out.element("summary", "Every lyric line");
        out.line("<table><thead><tr><th>#</th><th>From</th><th>Line</th>"
                + "<th>Syllables</th></tr></thead><tbody>");
        List<LyricLine> lines = score.lyrics().lines();
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            out.open("tr");
            out.element("td", String.valueOf(i + 1));
            out.element("td", ReportTimeline.clock(line.startSeconds()));
            out.element("td", line.text(), "class", "words");
            out.element("td", String.valueOf(line.words().size()));
            out.line("</tr>");
        }
        out.line("</tbody></table>");
        out.line("</details>");
    }

    private void provenance(TempoMap tempoMap) {
        Map<Provenance, Integer> counts = new EnumMap<>(Provenance.class);
        for (TempoMap.TempoSegment segment : tempoMap.segments()) {
            counts.merge(segment.provenance(), 1, Integer::sum);
        }
        List<Fact> table = new ArrayList<>();
        for (Map.Entry<Provenance, Integer> entry : counts.entrySet()) {
            table.add(fact("Tempo segments " + provenanceName(entry.getKey()),
                    String.valueOf(entry.getValue())));
        }
        facts(table.toArray(new Fact[0]));
    }

    // -------------------------------------------------------------- wording

    private static String provenanceName(Provenance provenance) {
        return switch (provenance) {
            case MEASURED -> "measured from the signal";
            case DECLARED -> "declared by the source file";
            case SUPPLIED -> "supplied by the user";
            case ASSUMED -> "assumed, because nothing stated or measured one";
            case DERIVED -> "derived from other facts in the score";
            case UNKNOWN -> "of unrecorded origin";
        };
    }

    private static String qualityName(ChordQuality quality) {
        return quality == ChordQuality.NONE ? "no chord" : quality.name()
                .toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String accidentals(Key key) {
        int count = key.keySignatureAccidentals();
        if (count == 0) {
            return "no sharps or flats";
        }
        String kind = count > 0 ? "sharp" : "flat";
        int many = Math.abs(count);
        return many + " " + kind + (many == 1 ? "" : "s");
    }

    /** A colour per subdivision, ordered so a finer grid reads as a stronger block. */
    private static String gridColour(GridResolution resolution) {
        int steps = GridResolution.values().length;
        int hue = 210 - 180 * resolution.ordinal() / Math.max(1, steps - 1);
        return "hsl(" + hue + " 58% 60%)";
    }

    static String percent(Confidence confidence) {
        return HtmlWriter.number(100 * confidence.value(), 0) + "%";
    }
}
