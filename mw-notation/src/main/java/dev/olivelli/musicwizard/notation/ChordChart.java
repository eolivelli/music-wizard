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
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders a score's harmony as a chord chart.
 *
 * <p>Plain text with bars grouped four to a line, which is what a guitarist or
 * pianist actually reads. This is the project's strongest output, so it is worth
 * getting the layout right rather than dumping a list of chords with timestamps.
 */
public final class ChordChart {

    /** Bars per printed line. */
    private static final int BARS_PER_LINE = 4;

    private ChordChart() {
    }

    /** Renders the chart as plain text. */
    public static String toText(Score score) {
        Objects.requireNonNull(score, "score");
        if (score.chords().isEmpty()) {
            return "(no chords were found)\n";
        }

        StringBuilder out = new StringBuilder();
        score.title().ifPresent(title -> out.append(title).append('\n'));
        score.artist().ifPresent(artist -> out.append(artist).append('\n'));
        if (out.length() > 0) {
            out.append('\n');
        }

        out.append(tempoLine(score));
        out.append("Meter  ").append(score.tempoMap().initialTimeSignature()).append('\n');
        score.primaryKey().ifPresent(key -> out.append("Key    ")
                .append(key.displayName()).append('\n'));
        out.append('\n');

        for (String line : barLines(score)) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * The tempo, in the beat the reader counts.
     *
     * <p>The map stores quarter notes per minute. Printed unqualified next to a
     * {@code Meter 6/8} line that makes it look authoritative, that is a
     * metronome marking 50% fast, because a 6/8 bar is counted in dotted
     * quarters. Identical in every x/4 meter, where the two coincide.
     */
    private static String tempoLine(Score score) {
        double quarterBpm = score.tempoMap().averageTempo(score.durationSeconds());
        TimeSignature meter = score.tempoMap().initialTimeSignature();
        if (meter.beatUnitQuarters() == 1.0) {
            return String.format("Tempo  %.0f BPM%n", quarterBpm);
        }
        return String.format("Tempo  %.0f BPM (%.0f quarter notes/min)%n",
                meter.countedTempo(quarterBpm), quarterBpm);
    }

    /**
     * Groups chords into bars and bars into lines.
     *
     * <p>A chord is placed in the bar its start time falls in. Where a bar holds
     * several chords they are printed together, and where it holds none the
     * previous chord is understood to continue, which is how a chart is normally
     * read.
     */
    static List<String> barLines(Score score) {
        List<Chord> chords = score.chords().chords();
        double barDuration = barDurationSeconds(score);
        if (!(barDuration > 0)) {
            return List.of(chords.stream().map(Chord::symbol)
                    .reduce((a, b) -> a + " | " + b).orElse(""));
        }

        double origin = firstBarStart(score);
        // Sized from where the harmony ends, not from the recording's duration:
        // trailing silence would otherwise print as empty bars, and sizing from
        // chord starts alone would drop the bars a sustained chord holds through.
        double lastEnd = chords.stream().mapToDouble(Chord::endSeconds).max().orElse(origin);
        int barCount = Math.max(1,
                (int) Math.round((lastEnd - origin) / barDuration));
        List<List<String>> bars = new ArrayList<>();
        for (int i = 0; i < barCount; i++) {
            bars.add(new ArrayList<>());
        }
        for (Chord chord : chords) {
            // Rounded, not floored: a chord detected a few milliseconds before
            // its downbeat belongs to the bar it starts, not the one before.
            int bar = (int) Math.round((chord.startSeconds() - origin) / barDuration);
            if (bar >= 0 && bar < bars.size()) {
                bars.get(bar).add(chord.symbol());
            }
        }

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int onThisLine = 0;
        for (int bar = 0; bar < bars.size(); bar++) {
            String cell = bars.get(bar).isEmpty() ? "%" : String.join(" ", bars.get(bar));
            line.append(String.format("| %-12s", cell));
            if (++onThisLine == BARS_PER_LINE) {
                lines.add(line.append('|').toString());
                line = new StringBuilder();
                onThisLine = 0;
            }
        }
        if (onThisLine > 0) {
            lines.add(line.append('|').toString());
        }
        return lines;
    }

    /**
     * How long one bar lasts.
     *
     * <p>Measured from the tracked beats where they exist, rather than derived
     * from the tempo map. The map begins with a synthetic lead-in segment
     * covering the audio before the first tracked beat, and when that gap is a
     * fraction of a beat the segment carries an implausibly fast tempo. Reading
     * bar length out of it therefore drifts, and a drifting bar grid shows up
     * immediately as chords landing in the wrong bar.
     */
    private static double barDurationSeconds(Score score) {
        // Counted beats, not the numerator: the grid holds one pulse per counted
        // beat, so a 6/8 bar is two of the tracked intervals and not six.
        int beatsPerBar = score.tempoMap().initialTimeSignature().beatsPerBar();

        Optional<BeatGrid> grid = score.beatGrid();
        if (grid.isPresent() && grid.get().size() >= 2) {
            List<Double> times = grid.get().beatTimes();
            double[] intervals = new double[times.size() - 1];
            for (int i = 0; i < intervals.length; i++) {
                intervals[i] = times.get(i + 1) - times.get(i);
            }
            java.util.Arrays.sort(intervals);
            // Median, so one dropped beat does not stretch every bar.
            int middle = intervals.length / 2;
            double median = intervals.length % 2 == 1
                    ? intervals[middle]
                    : (intervals[middle - 1] + intervals[middle]) / 2.0;
            if (median > 0) {
                return median * beatsPerBar;
            }
        }

        double quarterBeats = score.tempoMap().initialTimeSignature().quarterBeatsPerBar();
        return score.tempoMap().beatsToSeconds(quarterBeats)
                - score.tempoMap().beatsToSeconds(0);
    }

    /**
     * Where the bar grid starts.
     *
     * <p>Anchored on the first chord rather than the first detected downbeat.
     * Downbeat phase is the least reliable thing this pipeline produces, and
     * when it disagrees with the harmony it is almost always the one that is
     * wrong: measured here, chord changes fell at 0.05s, 1.96s and 3.96s while
     * the detected downbeat sat at 0.96s, half a bar out. Anchoring on the
     * downbeat then pushed the first two chords into a single bar.
     *
     * <p>Chord changes are also what a downbeat detector should be using as
     * evidence in the first place, so trusting them here is not a workaround so
     * much as using the better signal.
     */
    private static double firstBarStart(Score score) {
        return score.chords().chords().stream()
                .mapToDouble(Chord::startSeconds)
                .min()
                .orElse(0.0);
    }

    /**
     * Renders the chart as LilyPond source.
     *
     * <p>Emitted directly from the model rather than by converting MusicXML,
     * which loses information on the way through.
     */
    public static String toLilyPond(Score score) {
        Objects.requireNonNull(score, "score");
        StringBuilder out = new StringBuilder();
        out.append("\\version \"2.24.0\"\n\n");
        out.append("\\header {\n");
        out.append("  title = \"").append(escape(score.title().orElse("Untitled"))).append("\"\n");
        score.artist().ifPresent(artist ->
                out.append("  composer = \"").append(escape(artist)).append("\"\n"));
        out.append("  tagline = ##f\n");
        out.append("}\n\n");

        out.append("\\score {\n");
        out.append("  \\new ChordNames {\n");
        out.append("    \\chordmode {\n      ");

        double barDuration = barDurationSeconds(score);
        for (Chord chord : score.chords().chords()) {
            // chordmode wants pitch, then duration, then modifier -- a1:m, not
            // a:m1, which LilyPond rejects.
            int bars = Math.max(1, (int) Math.round(chord.durationSeconds() / barDuration));
            for (int i = 0; i < bars; i++) {
                if (chord.isNoChord()) {
                    out.append("r1 ");
                } else {
                    out.append(lilyPondRoot(chord)).append('1')
                            .append(lilyPondQuality(chord)).append(' ');
                }
            }
        }
        out.append("\n    }\n  }\n");
        out.append("  \\layout { }\n");
        out.append("}\n");
        return out.toString();
    }

    /** The root in LilyPond note-name form, e.g. {@code c} or {@code bes}. */
    private static String lilyPondRoot(Chord chord) {
        return chord.root().letter().name().toLowerCase(java.util.Locale.ROOT)
                + chord.root().accidental().lilyPondSuffix();
    }

    /** The quality modifier, which follows the duration in chordmode. */
    private static String lilyPondQuality(Chord chord) {
        return switch (chord.quality()) {
            case MINOR, MINOR_SEVENTH, MINOR_SIXTH, MINOR_MAJOR_SEVENTH -> ":m";
            case DIMINISHED, DIMINISHED_SEVENTH, HALF_DIMINISHED_SEVENTH -> ":dim";
            case AUGMENTED -> ":aug";
            case DOMINANT_SEVENTH -> ":7";
            case MAJOR_SEVENTH -> ":maj7";
            case SUSPENDED_SECOND -> ":sus2";
            case SUSPENDED_FOURTH -> ":sus4";
            case SIXTH -> ":6";
            default -> "";
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
