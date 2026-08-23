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

import dev.olivelli.musicwizard.arrange.PlayableMelody;
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import dev.olivelli.musicwizard.arrange.Quantizer;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What MW did to a recording, as one HTML file.
 *
 * <p>Everything is inline — the styles, the script and every graphic — so the
 * page opens from a phone with no network, exactly as the PDFs do. Nothing here
 * reads audio or a model: the whole document comes from the workspace's saved
 * score plus what {@code mw-arrange} recomputes from it, which is why it can sit
 * beside the engravers rather than in the analysis path.
 *
 * <p>Some stages leave no trace in a workspace. Those say so on the page and
 * name the issue that would change it, rather than showing a picture of
 * reasoning nobody recorded.
 */
public final class AnalysisReport {

    /**
     * What the workspace knows about the recording that the score does not.
     *
     * <p>Every field may be {@code null}: a score can be rendered without a
     * workspace descriptor behind it, and a page that omits a line is better
     * than one that invents it.
     */
    public record Recording(String fileName, String sha256, String createdAt) {

        public static Recording unknown() {
            return new Recording(null, null, null);
        }
    }

    private AnalysisReport() {
    }

    /**
     * The report for a score, as a whole HTML document.
     *
     * <p>The score is the workspace's own, spelled for the page but otherwise
     * untouched. A caller that hands in a score it has moved gets a page saying
     * MW read the moved chords, with the analysed confidences beside them.
     */
    public static String toHtml(Score score, Recording recording) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(recording, "recording");

        NoteTrack melody = score.track(PartRole.LEAD_VOCAL).orElse(null);
        NoteTrack playable = melody == null || melody.isEmpty()
                ? null : PlayableMelody.reduce(score);
        QuantizedScore quantized = Quantizer.quantize(score);
        ReportTimeline timeline = new ReportTimeline(score, playable);

        HtmlWriter out = new HtmlWriter();
        out.line("<!DOCTYPE html>");
        out.line("<html lang=\"en\">");
        out.line("<head>");
        out.line("<meta charset=\"utf-8\">");
        out.line("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        out.element("title", pageTitle(score)).line("");
        out.open("style").raw(resource("report.css")).line("</style>");
        out.line("</head>");
        out.line("<body>");
        header(out, score, recording);
        overview(out, timeline);
        strip(out, timeline);
        out.line("<section id=\"phases\">");
        out.element("h2", "Phase by phase").line("");
        out.open("p", "class", "lede")
                .text("Each stage below answers the same three questions from the workspace"
                        + " and from nothing else: what reached it, what it decided, and what"
                        + " it left behind. Where it decided something the workspace does not"
                        + " keep, it says so.")
                .line("</p>");
        out.open("p", "class", "lede")
                .text("These are the stages of the audio pipeline, in the order it runs them."
                        + " A score can also be read symbolically from a MIDI file, where the"
                        + " first of them have nothing to do — and the workspace does not"
                        + " record which path produced this one, so each stage describes what"
                        + " it does rather than asserting that it ran.")
                .line("</p>");
        out.raw(new ReportPhases(score, melody, playable, quantized).render());
        out.line("</section>");
        footer(out);
        out.open("script").raw(resource("report.js")).line("</script>");
        out.line("</body>");
        out.line("</html>");
        return out.toString();
    }

    private static void header(HtmlWriter out, Score score, Recording recording) {
        out.line("<header>");
        out.element("h1", score.title().orElse("Untitled recording")).line("");
        score.artist().ifPresent(artist ->
                out.element("p", artist, "class", "artist").line(""));
        out.open("p", "class", "lede")
                .text("Everything Music Wizard did to turn this recording into a chart and a"
                        + " playable part, in the order it did it.")
                .line("</p>");

        List<String[]> summary = new ArrayList<>();
        summary.add(new String[] {"Length", ReportTimeline.clock(score.durationSeconds())});
        summary.add(new String[] {"Tempo",
                ReportTimeline.bpm(score.estimatedTempo()) + " quarter notes a minute"});
        summary.add(new String[] {"Meter",
                score.tempoMap().initialTimeSignature().numerator() + "/"
                        + score.tempoMap().initialTimeSignature().denominator()});
        score.primaryKey().ifPresent(key ->
                summary.add(new String[] {"Key", key.displayName()}));
        summary.add(new String[] {"Chord spans", String.valueOf(score.chords().size())});
        score.beatGrid().ifPresent(grid ->
                summary.add(new String[] {"Beats tracked", String.valueOf(grid.size())}));
        score.track(PartRole.LEAD_VOCAL).ifPresent(melody ->
                summary.add(new String[] {"Melody notes", String.valueOf(melody.size())}));
        if (!score.lyrics().isEmpty()) {
            summary.add(new String[] {"Syllables",
                    String.valueOf(score.lyrics().allWords().size())});
        }
        if (recording.fileName() != null) {
            summary.add(new String[] {"Source", recording.fileName()});
        }
        if (recording.sha256() != null) {
            summary.add(new String[] {"Source digest", recording.sha256()});
        }
        if (recording.createdAt() != null) {
            summary.add(new String[] {"Workspace created", recording.createdAt()});
        }

        out.line("<dl class=\"summary\">");
        for (String[] entry : summary) {
            out.element("dt", entry[0]);
            out.element("dd", entry[1]);
            out.line("");
        }
        out.line("</dl>");
        score.primaryKey().ifPresent(key -> keyCaveat(out, key));
        out.line("</header>");
    }

    /**
     * The one summary figure that is routinely read as more certain than it is:
     * which of a relative pair is home.
     */
    private static void keyCaveat(HtmlWriter out, Key key) {
        key.tonicConfidence().ifPresent(tonic -> out.open("p", "class", "caveat")
                .text("The key above is two decisions. Confidence in the key signature is "
                        + ReportPhases.percent(key.signatureConfidence().orElse(key.confidence()))
                        + " and confidence in which of the relative pair is home is "
                        + ReportPhases.percent(tonic) + ".")
                .line("</p>"));
    }

    private static void overview(HtmlWriter out, ReportTimeline timeline) {
        out.line("<section id=\"overview\">");
        out.element("h2", "The whole piece").line("");
        out.open("p", "class", "lede")
                .text("The recording end to end, at a scale that fits the page. Bar lines and"
                        + " beats are drawn only where they are far enough apart to be seen;"
                        + " the strip below has all of them.")
                .line("</p>");
        out.open("div", "class", "overview-frame").raw(timeline.overview()).line("</div>");
        out.line("</section>");
    }

    private static void strip(HtmlWriter out, ReportTimeline timeline) {
        out.line("<section id=\"timeline\">");
        out.element("h2", "The timeline").line("");
        out.open("p", "class", "lede")
                .text("Everything MW read out of the recording on one time axis. Scroll it"
                        + " sideways; hover or tap anything on it to read what it is.")
                .line("</p>");
        out.line("<div class=\"timeline-frame\">");
        out.open("div", "class", "lane-labels").line("").raw(timeline.laneLabels())
                .line("</div>");
        out.open("div", "class", "strip-scroll", "id", "mw-scroll")
                .line("").raw(timeline.detail()).line("</div>");
        out.line("</div>");
        out.open("p", "class", "readout", "id", "mw-readout").line("</p>");
        if (!timeline.absentLanes().isEmpty()) {
            out.open("p", "class", "note")
                    .text("No lane was drawn for: " + String.join(", ", timeline.absentLanes())
                            + ". The phases below say why for each.")
                    .line("</p>");
        }
        if (timeline.barAxisTruncated()) {
            out.open("p", "class", "note")
                    .text("The bar axis stops early: this tempo map spans more bars than the"
                            + " page draws, so the strip ends before the recording does.")
                    .line("</p>");
        }
        out.line("</section>");
    }

    private static void footer(HtmlWriter out) {
        out.line("<footer>");
        out.open("p")
                .text("This page is a reading of one workspace: its saved score, plus the"
                        + " grid and the reduction that the engraver recomputes from it. It"
                        + " is not a log of the run — a stage that decided something the"
                        + " workspace does not keep is marked as such above rather than"
                        + " reconstructed. A transposition asked for on the command line"
                        + " moves the engraved parts and not this page, which is about the"
                        + " recording rather than about what is being played from.")
                .line("</p>");
        out.open("p")
                .text("Generated by Music Wizard. Everything it needs is in this file.")
                .line("</p>");
        out.line("</footer>");
    }

    private static String pageTitle(Score score) {
        return score.title().orElse("Untitled recording") + " — what Music Wizard did";
    }

    /**
     * The page's own styles and script, which ship as resources so they can be
     * edited as CSS and JavaScript rather than as Java string literals.
     */
    private static String resource(String name) {
        try (InputStream in = AnalysisReport.class.getResourceAsStream("/report/" + name)) {
            if (in == null) {
                throw new IllegalStateException("the report resource " + name
                        + " is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the report resource " + name, e);
        }
    }
}
