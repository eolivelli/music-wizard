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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private enum Status {
        /** It ran and its output is on disk. */
        RECORDED("recorded", "output on disk"),
        /** It left nothing behind, but this page can run it again and show that. */
        RECOMPUTED("recomputed", "recomputed here"),
        /** The workspace does not say whether it ran, or what it decided. */
        UNTRACED("untraced", "no trace kept"),
        /** Its output would be in the score, and is not. */
        ABSENT("absent", "did not run");

        private final String cssClass;
        private final String label;

        Status(String cssClass, String label) {
            this.cssClass = cssClass;
            this.label = label;
        }
    }

    /** How many buckets a duration or interval histogram is drawn in. */
    private static final int HISTOGRAM_BUCKETS = 24;

    /** The tallest column of a histogram, in pixels. */
    private static final int HISTOGRAM_HEIGHT = 60;

    private final Score score;
    private final NoteTrack melody;
    private final NoteTrack playable;
    private final QuantizedScore quantized;
    private final HtmlWriter out = new HtmlWriter();
    private int number;

    ReportPhases(Score score, NoteTrack melody, NoteTrack playable, QuantizedScore quantized) {
        this.score = score;
        this.melody = melody;
        this.playable = playable;
        this.quantized = quantized;
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
        open("decode", "Decode", Status.UNTRACED,
                "A recording is read to mono samples at the analysis rate, and everything"
                        + " after this point works from those samples. A score read from a"
                        + " MIDI file is read symbolically instead and decodes nothing.");
        inOut("the source file in the workspace",
                "nothing — a decode has no choices to make",
                "one signal, and how long the recording is");
        facts(fact("Length", ReportTimeline.clock(score.durationSeconds())
                + "  (" + HtmlWriter.number(score.durationSeconds(), 2) + "s)"));
        gap("Which of the two happened is not written to the workspace, and neither are"
                + " the sample rate, the channel count and the decoder that read the file"
                + " (#674).");
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
        gap("Whether a separator ran, which provider it was, and whether the melody stage"
                + " read its stem or fell back to the mix are not recorded — so this page"
                + " cannot tell you which signal the melody below was read from (#674).");
        close();
    }

    private void beatsAndTempo() {
        boolean tracked = score.beatGrid().isPresent();
        open("beats", "Beat tracking, tempo and the bar axis",
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
        gap("The tempo candidates the tracker weighed against each other, and whether the"
                + " grid was believed or vetoed (#429), are decided inside the stage and not"
                + " recorded (#675).");
        close();
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
            note("This score holds no melody part; see --melody on analyze.");
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
        gap("Whether the words were supplied or transcribed, and the path the aligner took"
                + " through them, are not recorded — the workspace holds the result and not"
                + " its provenance (#674).");
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

    private void open(String id, String title, Status status, String what) {
        number++;
        out.open("article", "class", "phase " + status.cssClass, "id", "phase-" + id);
        out.open("h3");
        out.element("span", String.valueOf(number), "class", "phase-number");
        out.text(title);
        out.element("span", status.label, "class", "status");
        out.line("</h3>");
        out.open("p", "class", "what").text(what).line("</p>");
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
