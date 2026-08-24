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
import dev.olivelli.musicwizard.core.model.PitchSpelling;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
import dev.olivelli.musicwizard.core.workspace.ChromaTrace;
import dev.olivelli.musicwizard.core.workspace.KeyTrace;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.core.workspace.RunTraces;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

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

    /** How many pitch classes of a reading the span table names. */
    private static final int STRONGEST_SHOWN = 3;

    /** MIDI middle C, which {@link #pitchClassName} spells a pitch class through. */
    private static final int MIDDLE_C = 60;

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
                    : "unbounded — the register is silent between the beats"));
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
        open(ChromaTrace.STAGE, "Chroma", Status.UNTRACED,
                "An NNLS fit over the spectrum gives the energy on each of the twelve pitch"
                        + " classes, tuning-corrected, in a bass register and a treble one."
                        + " It is taken from the full mix and never from a stem, and it is"
                        + " what both the beat tracker's harmonic rhythm and every chord"
                        + " decision below are read from.");
        inOut("the decoded mix, and the tracked beats to average over",
                "the recording's tuning, and how the fit divides between the registers",
                "beat-synchronous chroma, and the fit's residual");
        Optional<ChromaTrace> trace = traces == null
                ? Optional.empty() : traces.trace(ChromaTrace.STAGE, ChromaTrace.class);
        trace.ifPresentOrElse(this::whatTheFitRead,
                () -> gap("This workspace does not hold what the front end read: not the model"
                        + " it fitted with, not the fit's own residual, not what any chord span"
                        + " was read from (#676)."));
        close();
    }

    /** The tuning, the model behind it, and what each chord span was read from (#676). */
    private void whatTheFitRead(ChromaTrace trace) {
        out.element("h4", "What the front end read").line("");
        List<Fact> table = new ArrayList<>();
        table.add(fact("Tuning", tuningRead(trace)));
        ChromaTrace.Fit fit = trace.fit();
        if (fit == null) {
            table.add(fact("Model the spectrum was fitted with", "not recorded"));
        } else {
            table.add(fact("Analysis window", fit.windowSize() + " samples  ("
                    + HtmlWriter.number(seconds(fit.windowSize(), fit.sampleRate()), 3) + "s)"));
            table.add(fact("Hop", fit.hopSize() + " samples  ("
                    + HtmlWriter.number(seconds(fit.hopSize(), fit.sampleRate()), 3) + "s)"));
            table.add(fact("Frames", fit.frames() + " at "
                    + HtmlWriter.number(fit.frameRate(), 1) + " a second"));
            table.add(fact("Grid the spectrum is resampled onto",
                    fit.binsPerSemitone() + " bins a semitone"));
            table.add(fact("Notes the dictionary models",
                    noteName(fit.lowestNoteMidi()) + " to " + noteName(fit.highestNoteMidi())));
            table.add(fact("Bass fold", "everything at and below "
                    + noteName(fit.crossfadeLowMidi()) + ", fading out to nothing at "
                    + noteName(fit.crossfadeHighMidi())));
            table.add(fact("Treble fold", "nothing at and below "
                    + noteName(fit.crossfadeLowMidi()) + ", everything from "
                    + noteName(fit.crossfadeHighMidi()) + " to "
                    + noteName(fit.trebleRollOffMidi()) + ", fading out again to nothing at "
                    + noteName(fit.highestNoteMidi())));
        }
        table.add(fact("Chord spans summarised", String.valueOf(trace.spans().size())));
        facts(table.toArray(new Fact[0]));
        if (trace.spans().isEmpty()) {
            note("No chord span was summarised.");
            return;
        }
        pitchClassGrid("What each chord span holds on each pitch class, in the chroma the"
                        + " root is decoded from. Shaded against the strongest reading on"
                        + " this figure, so a column says what stands out within the piece"
                        + " rather than how loud the span was.",
                "No chroma was recorded for these spans.",
                "Where a chroma was recorded, no pitch class carried anything.",
                trace.spans(), ChromaTrace.Span::combined);
        pitchClassGrid("How much of each span's spectrum only that pitch class can explain:"
                        + " delete its notes from the dictionary, fit the span again, and read"
                        + " what that costs. A pitch class the chroma carries but this leaves"
                        + " blank is one the fit did not need.",
                "No residual was measured over these spans, so what only one pitch class could"
                        + " explain is not shown.",
                "Where the residual was measured, no pitch class was the only explanation"
                        + " of anything.",
                trace.spans(), ChromaTrace.Span::significance);
        spanTable(trace.spans());
        note("Summarised over the chord spans and not the beats inside them, so a chord whose"
                + " own beats disagree reads here as their average. Which of these readings"
                + " each gate compared, and against what, is under the chord phase below.");
    }

    private static double seconds(int samples, int sampleRate) {
        return sampleRate > 0 ? (double) samples / sampleRate : 0;
    }

    private static String tuningRead(ChromaTrace trace) {
        if (!trace.tuningMeasured()) {
            return "not measured — the spectrum held no peaks to read one from, so concert"
                    + " pitch was assumed";
        }
        double cents = 100 * trace.tuningOffsetSemitones();
        return HtmlWriter.number(Math.abs(cents), 1) + " cents "
                + (cents < 0 ? "flat" : "sharp") + " of A440";
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
        Optional<ChordTrace> trace = traces == null
                ? Optional.empty() : traces.trace(ChordTrace.STAGE, ChordTrace.class);
        trace.ifPresentOrElse(this::howEachLabelWasChosen,
                () -> gap("Which candidate roots lost, what the residual gate admitted or"
                        + " refused, how the third was settled across the root, and what the"
                        + " bass prior contributed are not in this workspace. A recording"
                        + " analysed again records them; a score read from a MIDI file has"
                        + " none of them to record (#677)."));
        close();
    }

    /**
     * What the decoder chose between, and the two decisions taken over a root
     * rather than a span, from the trace the run left (#677).
     */
    private void howEachLabelWasChosen(ChordTrace trace) {
        if (trace.spans().isEmpty() && trace.roots().isEmpty()) {
            note("The decoder recorded no span and no root, which is what an estimate over"
                    + " a recording with no beat to decode over looks like.");
            return;
        }
        out.element("h4", "What the decoder chose between").line("");
        note("Each state is scored over the span's own beats in the decoder's own units, the"
                + " emission and the bass prior together. It is not a posterior and it does"
                + " not compare between spans. The decoder picks a path rather than a state"
                + " per beat, so the state it held can score below one it passed over — that"
                + " is the transition prior keeping the chord, and it is why a margin here"
                + " can be negative.");
        spanDecisionTable(trace.spans());
        note("Three labels in the order the span passed through them: what the decoder held,"
                + " what the run's own chroma made of it, and what the chart prints. The last"
                + " column names the decision that set the printed one — where it is a count,"
                + " this span was named by other spans on its root, and the table below says"
                + " what that count read. What the bass names is the root its register argued"
                + " for over these beats, beside what that argument added to the decoded"
                + " root, which is never positive: the prior ranks roots against each other"
                + " and never argues that a chord is sounding at all.");
        gateTable(trace.spans());
        rootDecisionTable(trace.roots());
    }

    /** What each span was decoded as, what it nearly was, and what the bass said. */
    private void spanDecisionTable(List<ChordTrace.Span> spans) {
        if (spans.isEmpty()) {
            note("No span was decoded.");
            return;
        }
        out.line("<details class=\"table\">");
        out.element("summary", "Every chord span, and what it beat");
        out.line("<table><thead><tr><th>#</th><th>From</th><th>Chord</th>"
                + "<th>The decoder held</th><th>The run read</th><th>Runner-up</th>"
                + "<th>Margin</th><th>The bass names</th>"
                + "<th>Major seventh in reach</th><th>Named by</th>"
                + "</tr></thead><tbody>");
        for (int i = 0; i < spans.size(); i++) {
            ChordTrace.Span span = spans.get(i);
            ChordTrace.Candidate runnerUp = span.runnerUp();
            out.open("tr");
            out.element("td", String.valueOf(i + 1));
            out.element("td", ReportTimeline.clock(span.fromSeconds()));
            out.element("td", span.chord(), "class", "symbol");
            out.element("td", candidate(span.decoded()));
            out.element("td", span.fromRun(), "class", "symbol");
            out.element("td", runnerUp == null ? "nothing else was scored" : candidate(runnerUp));
            out.element("td", runnerUp == null ? "—"
                    : HtmlWriter.number(span.decoded().score() - runnerUp.score(), 2));
            out.element("td", span.bassRoot() == null ? "nothing"
                    : span.bassRoot() + "  " + HtmlWriter.number(span.bassOnDecoded(), 2));
            out.element("td", span.majorSeventhBeats() == null ? "no root to admit one on"
                    : span.majorSeventhBeats() + " of "
                            + (span.toBeat() - span.fromBeat()) + " beats");
            out.element("td", namedBy(span.settledBy()));
            out.line("</tr>");
        }
        out.line("</tbody></table>");
        out.line("</details>");
    }

    /** The residual readings each span's quality decision compared, and against what. */
    private void gateTable(List<ChordTrace.Span> spans) {
        if (spans.stream().allMatch(span -> span.gates().isEmpty())) {
            note("No span carries a gate reading. That is what a run given no residual leaves,"
                    + " and equally what a run whose every span the fit needed nothing on"
                    + " leaves — including a run that named no chord at all, which has no root"
                    + " to read a degree against.");
            return;
        }
        out.line("<details class=\"table\">");
        out.element("summary", "What the residual said about each span's root");
        out.line("<table><thead><tr><th>#</th><th>Chord</th><th>Degree</th>"
                + "<th>Compared against</th><th>Read</th><th>Needed</th><th>Outcome</th>"
                + "</tr></thead><tbody>");
        for (int i = 0; i < spans.size(); i++) {
            ChordTrace.Span span = spans.get(i);
            for (ChordTrace.Gate gate : span.gates()) {
                out.open("tr");
                out.element("td", String.valueOf(i + 1));
                out.element("td", span.chord(), "class", "symbol");
                out.element("td", gate.degree());
                out.element("td", gate.rule());
                out.element("td", HtmlWriter.number(gate.reading(), 3));
                out.element("td", HtmlWriter.number(gate.required(), 3));
                out.element("td", gate.counted() ? "counted" : "withheld");
                out.line("</tr>");
            }
        }
        out.line("</tbody></table>");
        out.line("</details>");
        note("How much of the run's spectrum only that degree explains, against what the rule"
                + " asks of it. The outcome is the degree's, not the row's: the major third"
                + " has a row for each of its two comparisons and is withheld only where it"
                + " fails both. A degree the printed chord does not state is here too,"
                + " because a candidate that was refused is as much of the answer as the one"
                + " that won. A span missing from this table is one there was nothing to read"
                + " on: no root, or a root the fit needed nothing on.");
    }

    /**
     * The third and the seventh as they were settled over every run on a root,
     * which is why a span's quality is often decided by other spans.
     */
    private void rootDecisionTable(List<ChordTrace.Root> roots) {
        if (roots.isEmpty()) {
            return;
        }
        out.line("<details class=\"table\">");
        out.element("summary", "How each root's third and seventh were settled");
        out.line("<table><thead><tr><th>Root</th><th>Minor thirds</th><th>The count read</th>"
                + "<th>Runs changed</th><th>Minor sevenths</th><th>The count read</th>"
                + "<th>Runs changed</th></tr></thead><tbody>");
        for (ChordTrace.Root root : roots) {
            out.open("tr");
            out.element("td", root.root(), "class", "symbol");
            out.element("td", count(root.thirds()));
            out.element("td", countRead(root.thirds().read(), "hold a minor third"));
            out.element("td", String.valueOf(root.thirds().runsChanged()));
            out.element("td", count(root.sevenths()));
            out.element("td", countRead(root.sevenths().read(), "state this seventh"));
            out.element("td", String.valueOf(root.sevenths().runsChanged()));
            out.line("</tr>");
        }
        out.line("</tbody></table>");
        out.line("</details>");
        note("Each of these is one count read over every beat the recording puts on that root,"
                + " and it settles every run on it at once. A minority of minor thirds"
                + " withdraws the minor third from every run on the root; a minority of"
                + " sevenths withdraws the seventh and a majority adds one to the minor"
                + " triads. The two are counted over different beats: a run stating a sixth is"
                + " no evidence about a seventh and is left out of that count altogether.");
    }

    private static String count(ChordTrace.Count count) {
        return count.stated() + " of " + count.beats() + " beats";
    }

    /** The count as it fell, worded for the degree it was taken on. */
    private static String countRead(String read, String degree) {
        return switch (read) {
            case "minority" -> "a minority of this root's beats " + degree;
            case "even" -> "exactly half of them " + degree;
            case "majority" -> "most of them " + degree;
            case "none" -> "this rule counted no beat on this root";
            default -> read;
        };
    }

    private static String namedBy(String settledBy) {
        return switch (settledBy) {
            case "decoder" -> "the decoder";
            case "run" -> "the run weighed against its own chroma";
            case "sevenths" -> "the root's seventh count";
            case "thirds" -> "the root's third count";
            default -> settledBy;
        };
    }

    private static String candidate(ChordTrace.Candidate candidate) {
        return candidate.chord() + "  " + HtmlWriter.number(candidate.score(), 2);
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
        Optional<KeyTrace> trace = traces == null
                ? Optional.empty() : traces.trace(KeyTrace.STAGE, KeyTrace.class);
        trace.ifPresentOrElse(this::whatTheKeyWasWeighedFrom,
                () -> gap("The chord evidence each of the two decisions weighed is not"
                        + " recorded, so the page can show that they disagree but not why"
                        + " (#678). A recording analysed again records it."));
        close();
    }

    /** What each of the key's two decisions was weighed from (#678). */
    private void whatTheKeyWasWeighedFrom(KeyTrace trace) {
        if (KeyTrace.DECLARED.equals(trace.source())) {
            note("The file declares its key signature and whether it is major or minor, so"
                    + " nothing was weighed: which of the relative pair is home is read off"
                    + " the file's own flag rather than chosen, and both confidences above"
                    + " are certain because the writer said so.");
            return;
        }
        if (trace.signature() == null || trace.tonic() == null) {
            note("This workspace's record of the key names no comparison, so there is nothing"
                    + " here to draw.");
            return;
        }
        out.element("h4", "What the two decisions were weighed from").line("");
        facts(fact("Chords sounded for",
                        HtmlWriter.number(trace.soundingSeconds(), 2) + "s of "
                                + HtmlWriter.number(trace.spanSeconds(), 2) + "s"),
                fact("Both confidences scaled by", Math.round(100 * trace.weighed()) + "%"));
        note("A margin says which key won and nothing about how much was weighed, and the two"
                + " come apart: half a second of one chord inside four minutes of silence wins"
                + " by as wide a margin as a whole recording. So both confidences are scaled"
                + " by the share above.");
        signatureDecision(trace.signature());
        tonicDecision(trace.tonic(), trace.candidates());
        candidateTable(trace.candidates());
    }

    /** Which key signature won, and the best key outside the winning relative pair. */
    private void signatureDecision(KeyTrace.Decision signature) {
        out.element("h5", "The key signature").line("");
        facts(fact("Named", signature.winner()),
                fact("Best key in another signature", signature.runnerUp()),
                fact("Margin", HtmlWriter.number(signature.margin(), 3)));
        note("separated".equals(signature.read())
                ? "The two scores lie apart, so the harmony chose the signature."
                : "The two scores are one score, so nothing in the harmony chose between"
                        + " these signatures and a stated editorial preference did — the"
                        + " simpler key signature. The confidence beside it is at the floor"
                        + " that leaves.");
    }

    /**
     * Which of the relative pair is home, and the only two things able to say
     * so — a relative pair shares every scale note.
     */
    private void tonicDecision(KeyTrace.Decision tonic, List<KeyTrace.Candidate> candidates) {
        out.element("h5", "Which of the relative pair is home").line("");
        facts(fact("Named", tonic.winner()),
                fact("Its relative", tonic.runnerUp()),
                fact("Margin", HtmlWriter.number(tonic.margin(), 3)));
        relativePairTable(tonic, candidates);
        note("A key and its relative minor share every note of the scale, so the scale cannot"
                + " separate them. Two things can: a chord on the fifth degree of the minor"
                + " key whose third is that key's raised seventh, which the relative major"
                + " does not hold, and the key's own tonic chord, which is worth half again"
                + " beyond fitting the scale. Where a chord opens or closes the recording is"
                + " read for nothing at all — on a recording those two spans are a fade-in"
                + " and a tail decoded from almost no signal.");
        if (!"separated".equals(tonic.read())) {
            note("Here the two scored the same, so neither of those said anything and the"
                    + " estimator did not guess. It wrote the major, which is the more often"
                    + " right of the two in this repertoire, and reported the coin flip that"
                    + " a stated preference is worth. A loop built from the shared seven"
                    + " notes with no dominant in it and equal time on both tonics reaches"
                    + " here by design, and a listener may well hear the minor.");
        }
    }

    /** The two members of the pair side by side, with what each was weighed on. */
    private void relativePairTable(KeyTrace.Decision tonic, List<KeyTrace.Candidate> candidates) {
        List<KeyTrace.Candidate> pair = candidates.stream()
                .filter(candidate -> candidate.key().equals(tonic.winner())
                        || candidate.key().equals(tonic.runnerUp()))
                .toList();
        if (pair.isEmpty()) {
            return;
        }
        out.line("<table><thead><tr><th>Key</th><th>Score</th><th>Its own tonic chord</th>"
                + "<th>Its raised seventh</th></tr></thead><tbody>");
        for (KeyTrace.Candidate candidate : pair) {
            out.open("tr");
            out.element("td", candidate.key(), "class", "symbol");
            out.element("td", HtmlWriter.number(candidate.score(), 3));
            out.element("td", sounded(candidate.tonicChordSpans(),
                    candidate.tonicChordSeconds()));
            out.element("td", sounded(candidate.raisedSeventhSpans(),
                    candidate.raisedSeventhSeconds()));
            out.line("</tr>");
        }
        out.line("</tbody></table>");
    }

    /** Every key that was scored, so the winner can be read against the field. */
    private void candidateTable(List<KeyTrace.Candidate> candidates) {
        if (candidates.isEmpty()) {
            note("No candidate key was scored.");
            return;
        }
        out.line("<details class=\"table\">");
        out.element("summary", "Every key that was scored");
        out.line("<table><thead><tr><th>Key</th><th>Score</th><th>Its own tonic chord</th>"
                + "<th>Its raised seventh</th></tr></thead><tbody>");
        for (KeyTrace.Candidate candidate : candidates.stream()
                .sorted(Comparator.comparingDouble(KeyTrace.Candidate::score).reversed())
                .toList()) {
            out.open("tr");
            out.element("td", candidate.key(), "class", "symbol");
            out.element("td", HtmlWriter.number(candidate.score(), 3));
            out.element("td", sounded(candidate.tonicChordSpans(),
                    candidate.tonicChordSeconds()));
            out.element("td", sounded(candidate.raisedSeventhSpans(),
                    candidate.raisedSeventhSeconds()));
            out.line("</tr>");
        }
        out.line("</tbody></table>");
        out.line("</details>");
        note("The score is a duration-weighted average over each chord's triad: how much of"
                + " it lies in the key's scale, and half again where the chord is the key's"
                + " own tonic chord. A seventh or a sixth is read for nothing, since a colour"
                + " tone routinely leaves the key.");
    }

    private static String sounded(int spans, double seconds) {
        return spans == 0 ? "nothing"
                : spans + (spans == 1 ? " chord, " : " chords, ")
                        + HtmlWriter.number(seconds, 2) + "s";
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

    /**
     * One column per chord span, twelve cells tall, shaded by the reading
     * (#676).
     *
     * <p>Against the strongest cell on the figure rather than per column: a
     * column scaled to itself would show every span as equally decided. The
     * span the numbers belong to is in the table below, which is why a cell
     * carries no reading of its own — one per cell is what a long recording
     * pays for in page weight.
     *
     * <p>A span that left no reading is drawn as a column of its own kind and
     * never as a column of zeros, which would be a measurement read off an
     * absence.
     */
    private void pitchClassGrid(String caption, String unmeasured, String nothing,
                                List<ChromaTrace.Span> spans,
                                Function<ChromaTrace.Span, List<Double>> reading) {
        if (spans.stream().allMatch(span -> reading.apply(span).isEmpty())) {
            note(unmeasured);
            return;
        }
        double strongest = spans.stream()
                .flatMap(span -> reading.apply(span).stream())
                .mapToDouble(Double::doubleValue)
                .filter(Double::isFinite)
                .max().orElse(0);
        if (!(strongest > 0)) {
            note(nothing);
            return;
        }
        out.line("<figure class=\"pc-grid\">");
        out.open("div", "class", "pc-columns");
        out.open("div", "class", "pc-names");
        for (int pitchClass = 11; pitchClass >= 0; pitchClass--) {
            out.element("span", pitchClassName(pitchClass), "class", "pc-name");
        }
        out.line("</div>");
        for (ChromaTrace.Span span : spans) {
            List<Double> values = reading.apply(span);
            String where = span.chord() + " at " + ReportTimeline.clock(span.fromSeconds());
            if (values.isEmpty()) {
                out.open("div", "class", "pc-column unmeasured",
                        "title", where + ": not measured");
            } else {
                out.open("div", "class", "pc-column", "title", where);
            }
            for (int pitchClass = 11; pitchClass >= 0; pitchClass--) {
                if (values.isEmpty()) {
                    out.empty("span", "class", "pc-cell");
                    continue;
                }
                double value = pitchClass < values.size() ? values.get(pitchClass) : 0;
                double share = Double.isFinite(value) ? value / strongest : 0;
                out.empty("span", "class", "pc-cell",
                        "style", "opacity:" + HtmlWriter.number(Math.clamp(share, 0, 1), 2));
            }
            out.line("</div>");
        }
        out.line("</div>");
        out.element("figcaption", caption);
        out.line("</figure>");
    }

    /** What each chord span was read from, as the strongest few of each reading. */
    private void spanTable(List<ChromaTrace.Span> spans) {
        out.line("<details class=\"table\">");
        out.element("summary", "Every chord span, and what it was read from");
        out.line("<table><thead><tr><th>#</th><th>Chord</th><th>From</th><th>Beats</th>"
                + "<th>Chroma</th><th>Treble</th><th>Bass</th><th>Residual</th>"
                + "</tr></thead><tbody>");
        for (int i = 0; i < spans.size(); i++) {
            ChromaTrace.Span span = spans.get(i);
            out.open("tr");
            out.element("td", String.valueOf(i + 1));
            out.element("td", span.chord(), "class", "symbol");
            out.element("td", ReportTimeline.clock(span.fromSeconds()));
            out.element("td", String.valueOf(span.toBeat() - span.fromBeat()));
            out.element("td", strongest(span.combined(), true));
            out.element("td", strongest(span.treble(), true));
            out.element("td", strongest(span.bass(), true));
            out.element("td", strongest(span.significance(), false));
            out.line("</tr>");
        }
        out.line("</tbody></table>");
        out.line("</details>");
    }

    /**
     * The pitch classes carrying most of a reading, largest first. Shares are
     * written as percentages and the residual as the ratio it is.
     */
    private static String strongest(List<Double> values, boolean asShare) {
        if (values.isEmpty()) {
            return "not measured";
        }
        List<Integer> order = new ArrayList<>();
        for (int pitchClass = 0; pitchClass < values.size(); pitchClass++) {
            if (Double.isFinite(values.get(pitchClass)) && values.get(pitchClass) > 0) {
                order.add(pitchClass);
            }
        }
        order.sort((a, b) -> Double.compare(values.get(b), values.get(a)));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(STRONGEST_SHOWN, order.size()); i++) {
            int pitchClass = order.get(i);
            text.append(text.isEmpty() ? "" : ", ").append(pitchClassName(pitchClass)).append(' ')
                    .append(asShare
                            ? HtmlWriter.number(100 * values.get(pitchClass), 0) + "%"
                            : HtmlWriter.number(values.get(pitchClass), 2));
        }
        return text.isEmpty() ? "nothing" : text.toString();
    }

    /** How a pitch class is written where nothing has spelled it. */
    private static String pitchClassName(int pitchClass) {
        PitchSpelling spelling = PitchSpelling.ofMidiPitchSharp(MIDDLE_C + pitchClass);
        return spelling.letter().name() + spelling.accidental().displaySuffix();
    }

    private static String noteName(int midi) {
        return PitchSpelling.ofMidiPitchSharp(midi).displayName();
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
