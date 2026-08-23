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

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The whole piece on one time axis: an overview that fits the page and a
 * detailed strip that scrolls.
 *
 * <p>Both are inline SVG with no script behind them, so the page they end up in
 * is a file rather than an application. Every lane is drawn only where the score
 * carries what it draws, and {@link #absentLanes()} names the ones that were
 * left out so the picture cannot be read as a claim that a stage found nothing.
 */
final class ReportTimeline {

    /** How wide a second of music is drawn in the scrolling strip. */
    private static final double DETAIL_PX_PER_SECOND = 90;

    /** How wide the strip may become, whatever the recording's length. */
    private static final double MAX_DETAIL_WIDTH = 40_000;

    /** The overview's fixed user-space width; CSS scales it to the page. */
    private static final double OVERVIEW_WIDTH = 1100;

    /** How far apart two bar numbers must be. */
    private static final double MIN_BAR_NUMBER_SPACING = 70;

    /** How far apart two clock readings must be; wider, so they thin out first. */
    private static final double MIN_TIME_TICK_SPACING = 150;

    private static final double[] TICK_STEPS = {1, 2, 5, 10, 15, 30, 60, 120, 300};

    /** Below this width a span is drawn but not labelled: the text would not fit. */
    private static final double MIN_LABEL_WIDTH = 20;

    /**
     * How far apart two grid lines must be to be drawn.
     *
     * <p>Nothing is skipped at the strip's scale. The overview fits a whole
     * recording across a page, where every bar line and every beat would be one
     * grey block rather than a grid.
     */
    private static final double MIN_GRID_SPACING = 7;

    /** How much of the ruler's foot the clock readings have to themselves. */
    private static final int CLOCK_BAND = 11;

    private static final int RULER_HEIGHT = 34;
    private static final int TEMPO_HEIGHT = 46;
    private static final int CHORD_HEIGHT = 34;
    private static final int KEY_HEIGHT = 32;
    private static final int ROLL_HEIGHT = 104;
    private static final int LYRIC_HEIGHT = 30;

    private final Score score;
    private final NoteTrack melody;
    private final NoteTrack playable;
    private final ReportFacts.Bars bars;
    private final List<Lane> lanes = new ArrayList<>();
    private final List<String> absent = new ArrayList<>();

    /**
     * @param playable the reduction to a playable part, or {@code null} where
     *                 there is no melody to reduce
     */
    ReportTimeline(Score score, NoteTrack playable) {
        this.score = score;
        this.melody = score.track(PartRole.LEAD_VOCAL).orElse(null);
        this.playable = playable;
        this.bars = ReportFacts.barLines(score.tempoMap(), score.durationSeconds());
        planLanes();
    }

    /** One horizontal band of the strip, and the row of the label column beside it. */
    private record Lane(String id, String label, String note, int height, double top) {
    }

    private void planLanes() {
        double top = 0;
        top = addLane("ruler", "Bars and beats",
                score.beatGrid().isPresent() ? "tracked" : "from the tempo map",
                RULER_HEIGHT, top, true);
        top = addLane("tempo", "Tempo", "quarter notes a minute", TEMPO_HEIGHT, top, true);
        top = addLane("chords", "Chords", "one block per span", CHORD_HEIGHT, top,
                !score.chords().isEmpty());
        top = addLane("key", "Key", "from the chords", KEY_HEIGHT, top,
                !score.keys().isEmpty());
        top = addLane("melody", "Melody", "as estimated", ROLL_HEIGHT, top,
                melody != null && !melody.isEmpty());
        top = addLane("playable", "Playable part", "reduced from the estimate",
                ROLL_HEIGHT, top, playable != null && !playable.isEmpty());
        addLane("lyrics", "Syllables", "at their aligned times", LYRIC_HEIGHT, top,
                !score.lyrics().isEmpty());
    }

    private double addLane(String id, String label, String note, int height, double top,
                           boolean present) {
        if (!present) {
            absent.add(label);
            return top;
        }
        lanes.add(new Lane(id, label, note, height, top));
        return top + height;
    }

    /** The lane titles the strip drew nothing for, in the order they would have appeared. */
    List<String> absentLanes() {
        return List.copyOf(absent);
    }

    private double height() {
        return lanes.isEmpty() ? 0 : lanes.get(lanes.size() - 1).top()
                + lanes.get(lanes.size() - 1).height();
    }

    private Optional<Lane> lane(String id) {
        return lanes.stream().filter(lane -> lane.id().equals(id)).findFirst();
    }

    boolean barAxisTruncated() {
        return bars.truncated();
    }

    /** The label column, whose rows have to line up with the strip's lanes exactly. */
    String laneLabels() {
        HtmlWriter out = new HtmlWriter();
        for (Lane lane : lanes) {
            out.open("div", "class", "lane-label",
                    "style", "height:" + HtmlWriter.number(lane.height(), 0) + "px");
            out.element("span", lane.label(), "class", "lane-name");
            out.element("span", lane.note(), "class", "lane-note");
            out.line("</div>");
        }
        return out.toString();
    }

    /** The scrolling strip, at a scale that keeps the document bounded. */
    String detail() {
        double width = detailWidth();
        return svg(width / score.durationSeconds(), width, height(), "mw-strip", true);
    }

    /**
     * The same music at a scale that fits the page.
     *
     * <p>Never wider than the strip, so a clip short enough to fit on a page at
     * the strip's own scale gets two drawings of one thing rather than an
     * overview magnifying what it is meant to summarise.
     */
    String overview() {
        double width = Math.min(OVERVIEW_WIDTH, detailWidth());
        return svg(width / score.durationSeconds(), width, height(), "mw-overview", false);
    }

    private double detailWidth() {
        return Math.min(DETAIL_PX_PER_SECOND * score.durationSeconds(), MAX_DETAIL_WIDTH);
    }

    private String svg(double pxPerSecond, double width, double height, String cssClass,
                       boolean labelled) {
        HtmlWriter out = new HtmlWriter();
        String box = "0 0 " + HtmlWriter.number(width, 1) + " " + HtmlWriter.number(height, 1);
        out.open("svg", "class", cssClass, "viewBox", box,
                "width", HtmlWriter.number(width, 1),
                "height", HtmlWriter.number(height, 1),
                "xmlns", "http://www.w3.org/2000/svg",
                "role", "img");
        out.line("");
        barsAndBeats(out, pxPerSecond, height, labelled);
        lane("tempo").ifPresent(lane -> tempo(out, lane, pxPerSecond, labelled));
        lane("chords").ifPresent(lane -> chords(out, lane, pxPerSecond, labelled));
        lane("key").ifPresent(lane -> keys(out, lane, pxPerSecond, labelled));
        lane("melody").ifPresent(lane -> roll(out, lane, melody, pxPerSecond, "melody"));
        lane("playable").ifPresent(lane -> roll(out, lane, playable, pxPerSecond, "playable"));
        lane("lyrics").ifPresent(lane -> syllables(out, lane, pxPerSecond, labelled));
        out.line("</svg>");
        return out.toString();
    }

    private void barsAndBeats(HtmlWriter out, double pxPerSecond, double height,
                              boolean labelled) {
        out.line("<g class=\"grid\">");
        double previous = Double.NEGATIVE_INFINITY;
        for (ReportFacts.BarLine line : bars.lines()) {
            double x = line.seconds() * pxPerSecond;
            if (x - previous < MIN_GRID_SPACING) {
                continue;
            }
            previous = x;
            out.empty("line", "class", "bar-line",
                    "x1", HtmlWriter.number(x, 2), "y1", "0",
                    "x2", HtmlWriter.number(x, 2), "y2", HtmlWriter.number(height, 1));
        }
        out.line("");
        lane("ruler").ifPresent(ruler -> {
            // The ticks stop above the clock readings rather than at the foot of
            // the lane, which is where the readings are drawn.
            double base = ruler.top() + ruler.height() - CLOCK_BAND;
            score.beatGrid().ifPresent(grid -> beatTicks(out, grid, base, pxPerSecond));
            timeTicks(out, ruler, pxPerSecond);
            if (labelled) {
                barNumbers(out, ruler, pxPerSecond);
            }
        });
        out.line("</g>");
    }

    private void beatTicks(HtmlWriter out, BeatGrid grid, double base, double pxPerSecond) {
        double previous = Double.NEGATIVE_INFINITY;
        for (BeatGrid.Beat beat : grid.beats()) {
            double x = beat.seconds() * pxPerSecond;
            // A downbeat is drawn whatever the scale: thinning them out would
            // make the compressed view claim a bar phase the grid does not have.
            if (!beat.downbeat() && x - previous < MIN_GRID_SPACING) {
                continue;
            }
            previous = x;
            double top = beat.downbeat() ? base - 13 : base - 7;
            out.empty("line", "class", beat.downbeat() ? "beat downbeat" : "beat",
                    "x1", HtmlWriter.number(x, 2), "y1", HtmlWriter.number(top, 1),
                    "x2", HtmlWriter.number(x, 2), "y2", HtmlWriter.number(base, 1));
        }
        out.line("");
    }

    private void barNumbers(HtmlWriter out, Lane ruler, double pxPerSecond) {
        double previous = Double.NEGATIVE_INFINITY;
        for (ReportFacts.BarLine line : bars.lines()) {
            double x = line.seconds() * pxPerSecond;
            if (x - previous < MIN_BAR_NUMBER_SPACING) {
                continue;
            }
            previous = x;
            // Bar 1 is what a musician counts; the model counts from zero.
            out.element("text", String.valueOf(line.bar() + 1), "class", "bar-number",
                    "x", HtmlWriter.number(x + 3, 2),
                    "y", HtmlWriter.number(ruler.top() + 11, 1));
        }
        out.line("");
    }

    private void timeTicks(HtmlWriter out, Lane ruler, double pxPerSecond) {
        double step = TICK_STEPS[TICK_STEPS.length - 1];
        for (double candidate : TICK_STEPS) {
            if (candidate * pxPerSecond >= MIN_TIME_TICK_SPACING) {
                step = candidate;
                break;
            }
        }
        for (double seconds = 0; seconds <= score.durationSeconds(); seconds += step) {
            out.element("text", clock(seconds), "class", "time-tick",
                    "x", HtmlWriter.number(seconds * pxPerSecond + 3, 2),
                    "y", HtmlWriter.number(ruler.top() + ruler.height() - 2, 1));
        }
        out.line("");
    }

    private void tempo(HtmlWriter out, Lane lane, double pxPerSecond, boolean labelled) {
        List<TempoMap.TempoSegment> segments = score.tempoMap().segments();
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (TempoMap.TempoSegment segment : segments) {
            lowest = Math.min(lowest, segment.beatsPerMinute());
            highest = Math.max(highest, segment.beatsPerMinute());
        }
        // A constant tempo would otherwise divide by zero and draw nothing.
        double span = highest - lowest > 1e-9 ? highest - lowest : 1;
        double top = lane.top() + 6;
        double usable = lane.height() - 14;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            TempoMap.TempoSegment segment = segments.get(i);
            double x = segment.startSeconds() * pxPerSecond;
            double y = top + usable * (1 - (segment.beatsPerMinute() - lowest) / span);
            path.append(i == 0 ? "M" : "L").append(HtmlWriter.number(x, 2)).append(' ')
                    .append(HtmlWriter.number(y, 2));
            double until = i + 1 < segments.size()
                    ? segments.get(i + 1).startSeconds() : score.durationSeconds();
            path.append('L').append(HtmlWriter.number(until * pxPerSecond, 2)).append(' ')
                    .append(HtmlWriter.number(y, 2));
        }
        out.open("g", "class", "lane tempo");
        out.empty("path", "class", "tempo-line", "d", path.toString());
        if (labelled) {
            out.element("text", bpm(highest), "class", "scale-mark",
                    "x", "3", "y", HtmlWriter.number(top + 8, 1));
            out.element("text", bpm(lowest), "class", "scale-mark",
                    "x", "3", "y", HtmlWriter.number(top + usable, 1));
        }
        out.line("</g>");
    }

    private void chords(HtmlWriter out, Lane lane, double pxPerSecond, boolean labelled) {
        out.open("g", "class", "lane chords");
        for (Chord chord : score.chords().chords()) {
            double x = chord.startSeconds() * pxPerSecond;
            double width = Math.max(1, chord.durationSeconds() * pxPerSecond);
            out.open("g", "class", "span");
            out.element("title", chordTooltip(chord));
            out.empty("rect", "class", chord.isNoChord() ? "chord no-chord" : "chord",
                    "x", HtmlWriter.number(x, 2),
                    "y", HtmlWriter.number(lane.top() + 3, 1),
                    "width", HtmlWriter.number(width, 2),
                    "height", HtmlWriter.number(lane.height() - 6, 1),
                    "fill", chordColour(chord),
                    "fill-opacity", HtmlWriter.number(
                            0.35 + 0.6 * chord.confidence().value(), 3));
            if (labelled && width >= MIN_LABEL_WIDTH) {
                out.element("text", chord.symbol(), "class", "chord-name",
                        "x", HtmlWriter.number(x + 4, 2),
                        "y", HtmlWriter.number(lane.top() + lane.height() - 11, 1));
            }
            out.line("</g>");
        }
        out.line("</g>");
    }

    private void keys(HtmlWriter out, Lane lane, double pxPerSecond, boolean labelled) {
        out.open("g", "class", "lane keys");
        for (Key key : score.keys()) {
            double x = key.startSeconds() * pxPerSecond;
            double width = Math.max(1, (key.endSeconds() - key.startSeconds()) * pxPerSecond);
            out.open("g", "class", "span");
            out.element("title", key.displayNameWithConfidence());
            out.empty("rect", "class", "key",
                    "x", HtmlWriter.number(x, 2),
                    "y", HtmlWriter.number(lane.top() + 3, 1),
                    "width", HtmlWriter.number(width, 2),
                    "height", HtmlWriter.number(lane.height() - 6, 1));
            if (labelled && width >= MIN_LABEL_WIDTH) {
                out.element("text", key.displayName(), "class", "key-name",
                        "x", HtmlWriter.number(x + 4, 2),
                        "y", HtmlWriter.number(lane.top() + lane.height() - 6, 1));
            }
            out.line("</g>");
        }
        out.line("</g>");
    }

    /**
     * A piano roll drawn on the pitch range both rolls share, so the estimate
     * and the part reduced from it can be read against each other.
     */
    private void roll(HtmlWriter out, Lane lane, NoteTrack track, double pxPerSecond,
                      String cssClass) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (NoteTrack drawn : new NoteTrack[] {melody, playable}) {
            if (drawn == null) {
                continue;
            }
            for (Note note : drawn.notes()) {
                lowest = Math.min(lowest, note.midiPitch());
                highest = Math.max(highest, note.midiPitch());
            }
        }
        int semitones = Math.max(1, highest - lowest + 1);
        double rowHeight = (lane.height() - 8) / (double) semitones;
        out.open("g", "class", "lane roll " + cssClass);
        for (Note note : track.notes()) {
            double x = note.onsetSeconds() * pxPerSecond;
            double width = Math.max(1, note.durationSeconds() * pxPerSecond);
            double y = lane.top() + 4 + (highest - note.midiPitch()) * rowHeight;
            out.open("g", "class", "span");
            out.element("title", noteTooltip(note));
            out.empty("rect", "class", "note",
                    "x", HtmlWriter.number(x, 2), "y", HtmlWriter.number(y, 2),
                    "width", HtmlWriter.number(width, 2),
                    "height", HtmlWriter.number(Math.max(2, rowHeight - 1), 2),
                    "fill-opacity", HtmlWriter.number(
                            0.4 + 0.6 * note.confidence().value(), 3));
            out.line("</g>");
        }
        out.line("</g>");
    }

    private void syllables(HtmlWriter out, Lane lane, double pxPerSecond, boolean labelled) {
        out.open("g", "class", "lane lyrics");
        for (LyricWord word : score.lyrics().allWords()) {
            double x = word.startSeconds() * pxPerSecond;
            out.open("g", "class", word.melisma() ? "span melisma" : "span");
            out.element("title", word.text());
            out.empty("line", "class", "syllable-tick",
                    "x1", HtmlWriter.number(x, 2),
                    "y1", HtmlWriter.number(lane.top() + 2, 1),
                    "x2", HtmlWriter.number(x, 2),
                    "y2", HtmlWriter.number(lane.top() + 9, 1));
            if (labelled) {
                out.element("text", word.text() + (word.hyphenatedToNext() ? "-" : ""),
                        "class", "syllable",
                        "x", HtmlWriter.number(x + 1, 2),
                        "y", HtmlWriter.number(lane.top() + lane.height() - 7, 1));
            }
            out.line("</g>");
        }
        out.line("</g>");
    }

    private String chordTooltip(Chord chord) {
        return String.format(Locale.ROOT, "%s  %s to %s  confidence %s",
                chord.symbol(), clock(chord.startSeconds()), clock(chord.endSeconds()),
                HtmlWriter.number(chord.confidence().value(), 2));
    }

    private String noteTooltip(Note note) {
        return String.format(Locale.ROOT, "%s  %ss for %ss  confidence %s",
                note.spellingOrDefault().displayName(),
                HtmlWriter.number(note.onsetSeconds(), 2),
                HtmlWriter.number(note.durationSeconds(), 2),
                HtmlWriter.number(note.confidence().value(), 2));
    }

    private static String chordColour(Chord chord) {
        return chord.isNoChord() ? NO_CHORD_COLOUR : rootColour(chord.root().pitchClass());
    }

    /** What a span with no chord in it is drawn in. */
    static final String NO_CHORD_COLOUR = "#b9bec7";

    /**
     * A colour per root, so a chord repeating through the piece repeats its
     * colour and the harmonic shape is visible before a single label is read.
     * The chord phase draws a legend from the same function.
     */
    static String rootColour(int pitchClass) {
        return "hsl(" + pitchClass * 30 + " 62% 58%)";
    }

    static String clock(double seconds) {
        int whole = (int) Math.floor(seconds);
        return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
    }

    static String bpm(double beatsPerMinute) {
        return HtmlWriter.number(beatsPerMinute, 1);
    }
}
