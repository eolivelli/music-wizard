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

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
import dev.olivelli.musicwizard.core.workspace.ChromaTrace;
import dev.olivelli.musicwizard.core.workspace.KeyTrace;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.core.workspace.RunTraceJson;
import dev.olivelli.musicwizard.core.workspace.RunTraces;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The analysis report against its golden files, and against the two properties
 * a golden file cannot state.
 *
 * <p>The first is that the page is <em>self-contained</em>: it has to open on a
 * phone with no network, so a URL that reaches one is a defect however good the
 * page looks. The second is that a page describing a workspace where only some
 * stages ran says so for each — which is checked by rendering exactly that.
 */
class AnalysisReportTest {

    private static final AnalysisReport.Recording RECORDING =
            new AnalysisReport.Recording("fixture.mp3",
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    "2026-01-01T00:00:00Z");

    @Test
    @DisplayName("a workspace every stage wrote to")
    void everyStage() {
        Goldens.assertGolden("report-full", ".html",
                AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING));
    }

    @Test
    @DisplayName("a workspace analysed without melody or lyrics")
    void chordsOnly() {
        Goldens.assertGolden("report-chords-only", ".html",
                AnalysisReport.toHtml(ReportFixtures.chordsOnly(), RECORDING));
    }

    @Test
    @DisplayName("a score carrying nothing but a tempo map")
    void bare() {
        Goldens.assertGolden("report-bare", ".html",
                AnalysisReport.toHtml(ReportFixtures.bare(), AnalysisReport.Recording.unknown()));
    }

    @Test
    @DisplayName("a workspace whose run recorded itself")
    void withARunManifest() {
        Goldens.assertGolden("report-with-manifest", ".html", AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run()));
    }

    @Test
    @DisplayName("a workspace whose stages recorded what they weighed")
    void withStageTraces() {
        Goldens.assertGolden("report-with-traces", ".html", AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run(),
                ReportFixtures.weighed()));
    }

    @Test
    @DisplayName("the beat trace is drawn, and its absence is stated rather than filled")
    void theBeatTraceIsDrawnOrItsAbsenceStated() {
        String weighed = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed());
        String blank = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run());

        // The two rivals the fixture's first window weighed, the octave the
        // register moved, and the window that did not vote.
        assertThat(weighed).contains("What the tracker chose between",
                "Pulse the windows agreed on", "so it was halved to 120.3 a minute",
                "puts the halved rate above it", "Every analysis window");
        assertThat(weighed).doesNotContain("This workspace does not hold what the tracker");

        assertThat(blank).contains("This workspace does not hold what the tracker weighed");
        assertThat(blank).doesNotContain("What the tracker chose between");
    }

    @Test
    @DisplayName("what the chroma front end read is drawn, or its absence stated")
    void theChromaTraceIsDrawnOrItsAbsenceStated() {
        String weighed = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed());
        String blank = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run());

        assertThat(weighed).contains("What the front end read",
                // The tuning, the model behind it, and the readings themselves.
                "3.8 cents sharp of A440", "A0 to C7",
                // The treble fold is full over a range and fades either side of
                // it, and the page says so rather than naming one edge.
                "everything from A3 to C6, fading out again to nothing at C7",
                "Every chord span, and what it was read from",
                // The chord span whose major seventh the fit needs most, which
                // is why that span is named with one.
                "<td class=\"symbol\">Fmaj7</td>", "E 2.1, F 1.31, C 0.58");
        assertThat(weighed).doesNotContain("This workspace does not hold what the front end");

        assertThat(blank).contains("This workspace does not hold what the front end read",
                "(#676)");
        // And the gap does not deny the tuning, which the run's own line above
        // it prints.
        assertThat(blank).contains("<dt>tuning</dt><dd>3.8 cents sharp of A440</dd>");
        assertThat(blank).doesNotContain("What the front end read");
    }

    @Test
    @DisplayName("a front end that folded nothing onto spans says so and draws nothing")
    void aChromaTraceWithNoSpansIsStated() {
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(
                        ReportFixtures.chromaWithoutSpans()));

        assertThat(page).contains("No chord span was summarised.",
                "the spectrum held no peaks to read one from");
        assertThat(page).doesNotContain("Every chord span, and what it was read from");
    }

    @Test
    @DisplayName("why each chord carries its label is drawn, or its absence stated")
    void theDecoderTraceIsDrawnOrItsAbsenceStated() {
        String weighed = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed());
        String blank = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run());

        assertThat(weighed).contains("What the decoder chose between",
                "Every chord span, and what it beat",
                // The span the run's own chroma renamed, which is a fact the
                // chart cannot show.
                "the run weighed against its own chroma",
                "How each root's third and seventh were settled",
                // The readings a gate compared, and the degree it withheld.
                "What the residual said about each span's root", "<td>withheld</td>");
        assertThat(weighed).doesNotContain("Which candidate roots lost");

        assertThat(blank).contains("Which candidate roots lost", "(#677)");
        assertThat(blank).doesNotContain("What the decoder chose between");
    }

    @Test
    @DisplayName("a span named by a count over its root says so, and the count says what it read")
    void aSpanNamedAcrossItsRootIsDrawn() {
        // The fact a reader of the chart cannot guess: this span reads as it
        // does because of the other spans on its root.
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(
                        ReportFixtures.chordsSettledAcrossTheRoot()));

        assertThat(page).contains("the root's third count",
                "<td>a minority of this root's beats hold a minor third</td>",
                "<td>a minority of this root's beats state this seventh</td>",
                // And what each count actually rewrote, which the reading alone
                // does not say.
                "<td>4 of 12 beats</td>");
    }

    @Test
    @DisplayName("the two traces on one span describe one measurement, not two")
    void theTwoTracesAgreeOnTheResidual() {
        // A reader following a span from the front end's table into the gate
        // table reads the same quantity twice, so a fixture whose two halves
        // disagreed would be documenting a page no run could produce.
        List<ChromaTrace.Span> read = ReportFixtures.chroma().spans();
        List<ChordTrace.Span> decided = ReportFixtures.chordDecisions().spans();
        assertThat(decided).hasSameSizeAs(read);
        for (int i = 0; i < decided.size(); i++) {
            int root = rootOf(decided.get(i).chord());
            for (ChordTrace.Gate gate : decided.get(i).gates()) {
                assertThat(gate.reading())
                        .as("span %d, the %s of %s", i + 1, gate.degree(),
                                decided.get(i).chord())
                        .isEqualTo(read.get(i).significance()
                                .get((root + SEMITONES.get(gate.degree())) % 12));
            }
        }
    }

    private static final Map<String, Integer> SEMITONES = Map.of(
            "minor third", 3, "major third", 4, "diminished fifth", 6,
            "sixth", 9, "major seventh", 11);

    /** The pitch class a fixture chord symbol is built on, sharps only. */
    private static int rootOf(String chord) {
        List<String> names = List.of("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A",
                "A#", "B");
        String root = chord.length() > 1 && chord.charAt(1) == '#'
                ? chord.substring(0, 2) : chord.substring(0, 1);
        return names.indexOf(root);
    }

    @Test
    @DisplayName("a count that read no beat is not reported as an even split")
    void aCountOverNoBeatIsNotAnEvenSplit() {
        // A rule that excluded every beat on a root -- a sixth is no evidence
        // about a seventh -- reads zero of zero, and a rule comparing that
        // against half of zero would announce a tie it never saw.
        ChordTrace uncounted = new ChordTrace(List.of(),
                List.of(new ChordTrace.Root("F",
                        new ChordTrace.Count(1, 1, "majority", 0),
                        new ChordTrace.Count(0, 0, "none", 0))));

        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(uncounted));

        assertThat(page).contains("<td>this rule counted no beat on this root</td>");
        assertThat(page).doesNotContain("exactly half of them state this seventh");
    }

    @Test
    @DisplayName("a decoder that recorded no decision says so and draws nothing")
    void aDecoderTraceWithNoSpansIsStated() {
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(
                        ReportFixtures.chordsWithoutDecisions()));

        assertThat(page).contains("The decoder recorded no span and no root");
        assertThat(page).doesNotContain("Every chord span, and what it beat",
                "Which candidate roots lost");
    }

    @Test
    @DisplayName("what the key's two decisions were weighed from is drawn, or its absence stated")
    void theKeyTraceIsDrawnOrItsAbsenceStated() {
        String weighed = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed());
        String blank = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run());

        assertThat(weighed).contains("What the two decisions were weighed from",
                // The signature decision, and the best key outside the winning
                // pair -- which the page cannot get from the key alone.
                "<dt>Best key in another signature</dt><dd>F major</dd>",
                // The tonic decision, and the one column that separated the
                // pair here: the minor spends longer on its own tonic chord.
                "<dt>Its relative</dt><dd>C major</dd>",
                "<td class=\"symbol\">A minor</td><td>1.143</td><td>1 chord, 2s</td>",
                "Every key that was scored");
        assertThat(weighed).doesNotContain("The chord evidence each of the two decisions");

        assertThat(blank).contains("The chord evidence each of the two decisions weighed is"
                + " not recorded", "(#678)");
        assertThat(blank).doesNotContain("What the two decisions were weighed from");
    }

    @Test
    @DisplayName("the key trace and the score it is drawn beside describe one piece of music")
    void theTraceAndTheScoreNameOneKey() {
        // A page whose key facts and key evidence disagreed would be
        // documenting a page no run could produce: the key is read off these
        // chords, so the winner and what it was weighed on both follow from
        // them.
        Score score = ReportFixtures.everything();
        KeyTrace trace = ReportFixtures.keyDecisions();
        assertThat(trace.tonic().winner())
                .isEqualTo(score.primaryKey().orElseThrow().displayName());
        assertThat(candidate(trace, "A minor").tonicChordSeconds())
                .isEqualTo(soundingOn(score, "Am"));
        assertThat(candidate(trace, "C major").tonicChordSeconds())
                .isEqualTo(soundingOn(score, "C"));
        assertThat(trace.soundingSeconds()).isEqualTo(score.chords().chords().stream()
                .filter(chord -> !chord.isNoChord())
                .mapToDouble(Chord::durationSeconds).sum());

        Score tied = ReportFixtures.tiedRelativePair();
        KeyTrace atTheFloor = ReportFixtures.tiedKeyDecisions();
        assertThat(atTheFloor.tonic().winner())
                .isEqualTo(tied.primaryKey().orElseThrow().displayName());
        assertThat(candidate(atTheFloor, "A minor").tonicChordSeconds())
                .isEqualTo(candidate(atTheFloor, "C major").tonicChordSeconds())
                .isEqualTo(soundingOn(tied, "Am"));
    }

    private static KeyTrace.Candidate candidate(KeyTrace trace, String key) {
        return trace.candidates().stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst().orElseThrow();
    }

    /** How long one chord symbol sounds for in a score. */
    private static double soundingOn(Score score, String symbol) {
        return score.chords().chords().stream()
                .filter(chord -> chord.symbol().equals(symbol))
                .mapToDouble(Chord::durationSeconds).sum();
    }

    @Test
    @DisplayName("a tonic decision at its floor is stated as a decision, not as an answer")
    void aTonicDecisionAtItsFloorSaysSo() {
        // The failure mode the estimator is designed around: nothing in the
        // harmony separates the relative pair, so a stated preference chose and
        // the confidence beside it is the coin flip that is worth.
        String page = AnalysisReport.toHtml(ReportFixtures.tiedRelativePair(), RECORDING,
                ReportFixtures.run(),
                RunTraceJson.of(Map.of(KeyTrace.STAGE, ReportFixtures.tiedKeyDecisions())));

        assertThat(page).contains("Here the two came to one score",
                "reported the coin flip a stated preference is worth",
                "<dt>Margin</dt><dd>0</dd>");
        // A tie is the two scores cancelling, which is not the two rules
        // staying silent: both of these keys hold their own tonic chord, and
        // the page must not tell the reader otherwise while showing it.
        assertThat(page).contains("<td class=\"symbol\">C major</td><td>1.125</td>"
                        + "<td>1 chord, 2s</td>",
                "<td class=\"symbol\">A minor</td><td>1.125</td><td>1 chord, 2s</td>");
        assertThat(page).doesNotContain("neither of those said anything",
                "no dominant in it and equal time on both tonics");
        // And the signature decision above it, which was not at its floor, is
        // not worded as though it were.
        assertThat(page).contains("The two scores lie apart, so the harmony chose the"
                + " signature.");
    }

    @Test
    @DisplayName("the pair is drawn winner first, whatever order the trace scored them in")
    void theRelativePairIsDrawnWinnerFirst() {
        // The trace lists candidates in the order they were scored, which puts
        // the relative major above its minor whatever won. Reading the first
        // row as the answer is the obvious thing to do, so it has to be one.
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed());

        String pair = page.substring(page.indexOf("Which of the relative pair is home"));
        pair = pair.substring(0, pair.indexOf("</table>"));
        assertThat(pair.indexOf("A minor")).isLessThan(pair.indexOf("C major"));
        assertThat(ReportFixtures.keyDecisions().candidates())
                .as("and the trace itself scored them the other way round")
                .extracting(KeyTrace.Candidate::key)
                .containsSubsequence("C major", "A minor");
    }

    @Test
    @DisplayName("a key the file declared says nothing was weighed")
    void aDeclaredKeyIsNotDrawnAsAnEstimate() {
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(),
                RunTraceJson.of(Map.of(KeyTrace.STAGE, KeyTrace.declared())));

        assertThat(page).contains("The file declares its key signature and whether it is"
                + " major or minor, so nothing was weighed");
        assertThat(page).doesNotContain("Every key that was scored");
    }

    @Test
    @DisplayName("a key trace naming no comparison is stated, not drawn as a decision")
    void aKeyTraceWithNoComparisonIsStated() {
        // What a trace written by a build that renamed either decision reads
        // as: unknown properties are ignored and missing ones default, so the
        // page has to say so rather than draw a comparison of nothing.
        RunTraces renamed = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"key\":{\"source\":\"chords\",\"chosen\":\"A minor\"}}}");

        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), renamed);

        assertThat(page).contains("This workspace's record of the key names no comparison");
        assertThat(page).doesNotContain("What the two decisions were weighed from",
                "Every key that was scored");
    }

    @Test
    @DisplayName("a residual nothing was read from is not drawn as six readings of nothing")
    void gatesOverAnUnreadResidualAreNotDrawn() {
        // A run the fit needed nothing on clears every share with every value,
        // so rows of zero against zero would read as the fit admitting each
        // degree when it measured none of them.
        ChordTrace ungated = new ChordTrace(
                ReportFixtures.chordDecisions().spans().stream()
                        .map(AnalysisReportTest::withoutGates).toList(),
                ReportFixtures.chordDecisions().roots());

        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(ungated));

        assertThat(page).contains("No span carries a gate reading");
        assertThat(page).doesNotContain("What the residual said about each span's root");
    }

    private static ChordTrace.Span withoutGates(ChordTrace.Span span) {
        return new ChordTrace.Span(span.fromSeconds(), span.toSeconds(), span.fromBeat(),
                span.toBeat(), span.chord(), span.fromRun(), span.settledBy(), span.decoded(),
                span.runnerUp(), span.bassRoot(), span.bassOnDecoded(),
                span.majorSeventhBeats(), List.of());
    }

    @Test
    @DisplayName("a residual that was never measured is not drawn as a blank one")
    void anUnmeasuredResidualIsNotDrawn() {
        // A reading of no width is what a run with no ablation records, and an
        // empty figure would read as a fit that needed no pitch class at all.
        ChromaTrace withoutResidual = new ChromaTrace(0.0375, true, null,
                ReportFixtures.chroma().spans().stream()
                        .map(span -> withResidual(span, List.of()))
                        .toList());

        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(withoutResidual));

        assertThat(page).contains("What the front end read", "<td>not measured</td>",
                "No residual was measured over these spans",
                // The fit itself is absent here too, and its absence is stated
                // rather than left to a reader to notice.
                "<dt>Model the spectrum was fitted with</dt><dd>not recorded</dd>");
        assertThat(page).doesNotContain("How much of each span's spectrum",
                "Notes the dictionary models");
    }

    @Test
    @DisplayName("one span with no residual among spans that have one is drawn as unmeasured")
    void aSpanWithNoResidualIsNotDrawnAsZero() {
        // Twelve cells at no opacity are what a fit that needed nothing looks
        // like, so a span that was never measured has to look like neither
        // that nor a reading.
        List<ChromaTrace.Span> spans = new ArrayList<>(ReportFixtures.chroma().spans());
        spans.set(1, withResidual(spans.get(1), List.of()));

        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(
                        new ChromaTrace(0.0375, true, null, spans)));

        assertThat(page).contains("How much of each span's spectrum",
                "<div class=\"pc-column unmeasured\" title=\"C at 0:01: not measured\">",
                // And the table beneath it separates the two absences too.
                "<td>not measured</td>");
        assertThat(page).doesNotContain("No residual was measured over these spans");
    }

    @Test
    @DisplayName("a residual measured over every span and zero on all of them says so")
    void aResidualThatFoundNothingIsStated() {
        // Measured and empty-handed is a different statement from not measured,
        // and an undrawn figure says neither on its own.
        List<Double> nothing = new ArrayList<>();
        while (nothing.size() < 12) {
            nothing.add(0.0);
        }
        ChromaTrace flat = new ChromaTrace(0.0375, true, null,
                ReportFixtures.chroma().spans().stream()
                        .map(span -> withResidual(span, nothing))
                        .toList());

        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(flat));

        assertThat(page).contains("Where the residual was measured, no pitch class was the"
                + " only explanation of anything");
        assertThat(page).doesNotContain("No residual was measured over these spans",
                "How much of each span's spectrum");
    }

    @Test
    @DisplayName("a span whose chord label a later build dropped is an absent trace")
    void aSpanWithNoChordIsRefused() {
        // The page prints the label, so a null one would abort the render --
        // and a render that throws takes the engraving with it.
        RunTraces renamed = RunTraceJson.fromJson("{\"schemaVersion\":1,\"traces\":"
                + "{\"chroma\":{\"tuningMeasured\":true,\"spans\":[{\"symbol\":\"C\"}]}}}");

        assertThat(renamed.trace(ChromaTrace.STAGE, ChromaTrace.class)).isEmpty();
        String page = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run(), renamed);
        assertThat(page).contains("This workspace does not hold what the front end read");
    }

    private static ChromaTrace.Span withResidual(ChromaTrace.Span span, List<Double> residual) {
        return new ChromaTrace.Span(span.fromSeconds(), span.toSeconds(), span.fromBeat(),
                span.toBeat(), span.chord(), span.combined(), span.treble(), span.bass(),
                residual);
    }

    @Test
    @DisplayName("a trace this build cannot read is an absent trace, not a broken page")
    void anUnreadableTraceIsStatedAsAbsent() {
        // What a workspace written by a build whose beat trace has moved on
        // looks like. The page has to say the same thing it says for a
        // workspace that traced nothing.
        RunTraces unreadable = RunTraceJson.fromJson(
                "{\"schemaVersion\":1,\"traces\":{\"beats\":\"a sentence, not a trace\"}}");

        String page = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run(), unreadable);

        assertThat(page).contains("This workspace does not hold what the tracker weighed");
        assertThat(page).doesNotContain("What the tracker chose between");
    }

    @Test
    @DisplayName("a register reading that is not a number is not printed as one")
    void degenerateRegisterReadingsAreWorded() {
        // Both are ordinary readings rather than corrupt ones: the contrast is
        // unbounded wherever the register is silent between the beats, which
        // is what a clean synthetic sample looks like, and every figure is
        // absent together where no window held enough beats to measure.
        String silentBetween = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(new BeatTrace.Octave(
                        false, Double.POSITIVE_INFINITY, 0.0, 1.0, 2, 0, false)));
        String nothingToRead = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING,
                ReportFixtures.run(), ReportFixtures.weighed(new BeatTrace.Octave(
                        false, Double.NaN, Double.NaN, Double.NaN, 0, 0, false)));

        assertThat(silentBetween).doesNotContain("Infinity", "NaN")
                .contains("the register is silent between the beats");
        assertThat(nothingToRead).doesNotContain("Infinity", "NaN")
                .contains("no window of it held enough tracked beats to measure")
                .doesNotContain("Marked-beat contrast");
    }

    @Test
    @DisplayName("a grid that marks no bar is not said to have agreed on a phase")
    void anAxisWithNoDownbeatNamesWhatItWasHungOn() {
        // BarLines anchors on the first chord here and never asks for a phase,
        // so naming #233's mechanism would describe a decision that was not
        // made -- and would contradict the downbeat count printed beside it.
        String page = AnalysisReport.toHtml(ReportFixtures.noDownbeats(), RECORDING);

        assertThat(page).contains("The grid marks no downbeat, so there is no bar phase",
                "<dt>Downbeats the grid marks</dt><dd>0</dd>");
        assertThat(page).doesNotContain("agreed on no offset", "(#233)");
    }

    @Test
    @DisplayName("the chart's bar-line veto is stated with the reason it gave")
    void theBarAxisVetoStatesItsReason() {
        // The fixture's grid is exact, so its downbeats are the bar lines. A
        // grid with one bar of the wrong length is refused whole, and the page
        // has to say which of the veto's two conditions refused it rather than
        // only that something did.
        String followed = AnalysisReport.toHtml(ReportFixtures.evenGrid(), RECORDING);
        String refused = AnalysisReport.toHtml(ReportFixtures.jitteredGrid(), RECORDING);

        assertThat(followed).contains("How the chart hangs its bar lines",
                "the tracked downbeats are the bar lines");
        assertThat(refused).contains(
                "a bar the grid marks sits too far from the rate the rest of them keep",
                "one bar length throughout");
    }

    @Test
    @DisplayName("a workspace with no record of its run says so rather than inventing one")
    void withoutARunManifest() {
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING);

        assertThat(page).contains("recorded nothing about its own run");
        // The four the record would answer keep saying they are unanswered.
        assertThat(page).contains(
                "Nothing in this workspace says what this file held",
                "Nothing in this workspace says whether a separator ran",
                "Nothing in this workspace says which signal these notes were read from",
                "Nothing in this workspace says whether the words were supplied");
        assertThat(statusOf(page, "decode")).isEqualTo("no trace kept");
        assertThat(page).doesNotContain("Last run");
    }

    @Test
    @DisplayName("what the run did and what the score holds are two statements, not one")
    void theRunAndTheScoreAreStatedSeparately() {
        String page = AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run());

        // Where the score cannot speak for a stage, the run's outcome is what
        // the page has.
        assertThat(statusOf(page, "decode")).isEqualTo("ran");
        assertThat(statusOf(page, "separation")).isEqualTo("failed");
        // Where it can, the badge stays the score's, because the phase goes on
        // to draw what the score holds -- and the run's line says what the run
        // did with it, which is a different question and here a different
        // answer.
        assertThat(statusOf(page, "beats")).isEqualTo("output on disk");
        assertThat(statusOf(page, "melody")).isEqualTo("output on disk");
        assertThat(page).contains("Last run</span>beats: from the cache");
        assertThat(page).contains("Last run</span>melody: did not run — not asked for");
        // A question the record answers is not also asked.
        assertThat(page).doesNotContain(
                "Nothing in this workspace says whether the words were supplied");
        // Every gap the record does not fill is still stated.
        assertThat(page).contains("(#676)", "(#677)", "(#678)", "(#679)",
                "(#680)", "(#684)");
    }

    @Test
    @DisplayName("reading a MIDI file symbolically is what the decode phase says happened")
    void theSymbolicPathAnswersTheDecodePhase() {
        // A MIDI workspace decodes nothing and its record names no decode, so
        // the phase has to take the other stage as its answer: describing the
        // arm that did not run is what the page is for not doing.
        RunManifest readSymbolically = new RunManifest(
                RunManifest.CURRENT_SCHEMA_VERSION, "1.2.3-test",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z", Map.of(),
                List.of(new RunManifest.StageRun("read-midi",
                        RunManifest.Outcome.COMPUTED, null, Map.of("tracks", "4"))));

        String page = AnalysisReport.toHtml(
                ReportFixtures.chordsOnly(), RECORDING, readSymbolically);

        assertThat(statusOf(page, "decode")).isEqualTo("ran");
        assertThat(page).contains("Last run</span>read midi: ran");
        // And the phase describes the arm that ran, not the other one.
        assertThat(page).contains("the events the file declares, and how long it plays");
        assertThat(page).doesNotContain("one signal, and how long the recording is");
        assertThat(page).doesNotContain("Nothing in this workspace says what this file held");
        assertThat(page).doesNotContain("recorded nothing about its own run");
    }

    @Test
    @DisplayName("a stage that ran and found nothing is not badged as one that did not run")
    void aStageThatRanAndFoundNothingIsNotCalledSkipped() {
        // The badge is the score's statement and the line under it is the
        // run's, so the two must be worded on their own axes: a tracker that
        // ran and found no pulse leaves a score with no grid, and neither
        // sentence may deny the other.
        RunManifest foundNothing = new RunManifest(
                RunManifest.CURRENT_SCHEMA_VERSION, "1.2.3-test",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z", Map.of(),
                List.of(new RunManifest.StageRun("beats", RunManifest.Outcome.COMPUTED,
                        "no pulse was found", Map.of())));

        String page = AnalysisReport.toHtml(ReportFixtures.bare(), RECORDING, foundNothing);

        assertThat(statusOf(page, "beats")).isEqualTo("nothing in the score");
        assertThat(page).contains("Last run</span>beats: ran — no pulse was found");
        assertThat(page).doesNotContain("<span class=\"status\">did not run</span>");
    }

    @Test
    @DisplayName("a stage this build has no phase for is still reported")
    void anUnknownStageIsNotDropped() {
        // What a workspace analysed by a newer build looks like. The page has
        // no phase to put it under and must not swallow it.
        assertThat(AnalysisReport.toHtml(
                ReportFixtures.everything(), RECORDING, ReportFixtures.run()))
                .contains("hummed bass");
    }

    @Test
    @DisplayName("the stages that left nothing behind are named, not left out")
    void theMissingStagesAreStated() {
        String page = AnalysisReport.toHtml(ReportFixtures.chordsOnly(), RECORDING);
        // The phases exist whatever the workspace holds: an absent stage that
        // simply vanished from the page would read as a stage that never was.
        assertThat(page).contains("id=\"phase-melody\"", "id=\"phase-lyrics\"",
                "id=\"phase-reduction\"");
        assertThat(page).contains("This score holds no melody",
                "This score holds no lyrics",
                "There is no melody to reduce");
        assertThat(page).contains("No lane was drawn for: Melody, Playable part, Syllables");
    }

    @Test
    @DisplayName("no stage is asserted to have run")
    void noStageIsClaimedToHaveRun() {
        // A score read from a MIDI file decodes no samples, separates nothing
        // and fits no chroma, and the workspace does not record which path
        // produced it -- so the page may describe those stages and may not
        // claim them. Their status label is the one that says so.
        String page = AnalysisReport.toHtml(ReportFixtures.everything(), RECORDING);
        for (String phase : new String[] {"decode", "separation", "chroma"}) {
            assertThat(statusOf(page, phase)).isEqualTo("no trace kept");
        }
        assertThat(page).contains("These are the stages of the audio pipeline");
        assertThat(page).contains(
                "A score read from a MIDI file has its chords named from the notes",
                "A score read from a MIDI file takes the key its own meta event declares");
    }

    @Test
    @DisplayName("an absent key names both ways of not having one")
    void theKeysAbsenceIsWordedForBothPaths() {
        // A MIDI file that declares no key signature carries none however much
        // of it sounds, so "no chord sounds" is not the only way to get here.
        assertThat(AnalysisReport.toHtml(ReportFixtures.bare(), RECORDING))
                .contains("a MIDI file that declares no key signature leaves none either")
                .doesNotContain("which is what happens when no chord sounds.");
    }

    @Test
    @DisplayName("the melody's absence is worded as the renderer words it (#500)")
    void theMelodyAdviceIsNotRestated() {
        // "--melody is what writes one" is advice this page cannot keep either:
        // on a MIDI workspace the flag does nothing and no melody role is ever
        // assigned.
        assertThat(AnalysisReport.toHtml(ReportFixtures.chordsOnly(), RECORDING))
                .contains("This score holds no melody part; see --melody on analyze.")
                .doesNotContain("is what writes one");
    }

    @Test
    @DisplayName("the last syllable to finish is the one reported, not the last to start")
    void theLastSyllableIsTheLatestEnd() {
        // Sung spans overlap, and the words are ordered by where they start.
        assertThat(AnalysisReport.toHtml(ReportFixtures.overlappingSyllables(), RECORDING))
                .contains("<dt>Last sung at</dt><dd>0:07</dd>");
    }

    @Test
    @DisplayName("nothing on the page reaches the network")
    void thePageIsSelfContained() {
        for (Score score : new Score[] {ReportFixtures.everything(),
                ReportFixtures.chordsOnly(), ReportFixtures.bare()}) {
            String page = AnalysisReport.toHtml(score, RECORDING);
            // The SVG namespace is a name rather than a fetch -- no browser
            // resolves it -- so it is the one URL allowed through.
            String withoutNamespace = page.replace("http://www.w3.org/2000/svg", "");
            assertThat(withoutNamespace)
                    .as("a report that fetches anything cannot open from a phone")
                    .doesNotContain("http://", "https://", "//cdn", "src=", "@import", "url(");
        }
    }

    @Test
    @DisplayName("the inlined resources cannot end their own element")
    void theInlinedResourcesAreInert() {
        // A style sheet or a script holding its own closing tag ends the element
        // early and spills the rest of itself into the document as text.
        assertThat(resource("report.css")).doesNotContain("</style", "</script");
        assertThat(resource("report.js")).doesNotContain("</script", "</style");
    }

    @Test
    @DisplayName("user text is escaped wherever it lands")
    void userTextIsEscaped() {
        Score score = ReportFixtures.everything()
                .withMetadata("<script>alert(1)</script>", "A & B \"quoted\"");
        String page = AnalysisReport.toHtml(score, new AnalysisReport.Recording(
                "<img src=x>.mp3", "&amp;", "'"));
        assertThat(page).doesNotContain("<script>alert(1)</script>", "<img src=x>");
        assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt;",
                "A &amp; B \"quoted\"", "&lt;img src=x&gt;.mp3", "&amp;amp;");
        // One script element, the report's own, and no second opener smuggled
        // through a title.
        assertThat(count(page, "<script")).isEqualTo(1);
    }

    @Test
    @DisplayName("the two views stack the same lanes, and the strip is never the narrower")
    void theTwoViewsAgree() {
        // A short clip, where the strip's own scale already fits a page, and a
        // recording long enough that it cannot: the overview must summarise in
        // the second case and must not magnify in the first.
        double[] widths = viewWidths(ReportFixtures.everything());
        assertThat(widths[1]).isEqualTo(widths[0]);
        double[] longer = viewWidths(Score.empty(TempoMap.constant(120), 200));
        assertThat(longer[1]).isGreaterThan(longer[0]);
    }

    /** The overview's width then the strip's, having checked they stack alike. */
    private static double[] viewWidths(Score score) {
        String page = AnalysisReport.toHtml(score, RECORDING);
        Matcher views = Pattern.compile("<svg class=\"(mw-overview|mw-strip)\" "
                + "viewBox=\"0 0 ([0-9.]+) ([0-9.]+)\"").matcher(page);
        assertThat(views.find()).isTrue();
        assertThat(views.group(1)).isEqualTo("mw-overview");
        double overview = Double.parseDouble(views.group(2));
        String height = views.group(3);
        assertThat(views.find()).isTrue();
        assertThat(views.group(1)).isEqualTo("mw-strip");
        assertThat(views.group(3)).as("the two views stack the same lanes").isEqualTo(height);
        return new double[] {overview, Double.parseDouble(views.group(2))};
    }

    /** The status label a phase carries, read out of its heading. */
    private static String statusOf(String page, String phase) {
        Matcher heading = Pattern.compile("id=\"phase-" + phase
                + "\">\\s*<h3>.*?<span class=\"status\">([^<]*)</span>", Pattern.DOTALL)
                .matcher(page);
        assertThat(heading.find()).as("the %s phase is on the page", phase).isTrue();
        return heading.group(1);
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    private static String resource(String name) {
        try (InputStream in = AnalysisReport.class.getResourceAsStream("/report/" + name)) {
            assertThat(in).as("the report resource %s ships with the module", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read the report resource " + name, e);
        }
    }
}
