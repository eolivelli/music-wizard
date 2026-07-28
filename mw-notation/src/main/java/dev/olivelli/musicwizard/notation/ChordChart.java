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

import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
        double quarterBpm = score.estimatedTempo();
        TimeSignature meter = score.tempoMap().initialTimeSignature();
        // Locale.ROOT, because this number is meant to be typed back in via
        // --tempo and picocli parses it with Double.valueOf. Under fr_FR the
        // chart would print "120,0", which that rejects; under ar_EG it would
        // print Arabic-Indic digits.
        if (meter.beatUnitQuarters() == 1.0) {
            return String.format(Locale.ROOT, "Tempo  %.0f BPM\n", quarterBpm);
        }
        return String.format(Locale.ROOT, "Tempo  %.0f BPM (%.0f quarter notes/min)\n",
                meter.countedTempo(quarterBpm), quarterBpm);
    }

    /**
     * Which bar each chord starts in, counted from the first chord's bar, and
     * how many bars the chart runs to.
     *
     * <p>Taken from the beat axis whenever the progression carries one, and from
     * seconds only when it does not. The two agree at a constant tempo and
     * disagree everywhere else, because the seconds route divides by a
     * <em>single</em> bar length derived from {@link Score#estimatedTempo()}: on
     * a piece that changes tempo the grid drifts, and chords pile into one bar
     * while later bars come out empty. A MIDI import states its rhythm exactly
     * and has done since #115, so on that path the question has an answer and
     * estimating it would be a step backwards.
     *
     * <p>Seconds remain the route for the audio path, whose chords are not
     * quantized -- which is exactly what {@link ChordProgression#isQuantized()}
     * is for.
     *
     * @param barOfChord bar index per chord, parallel to the progression
     * @param barCount   bars in the chart, including those a chord holds through
     */
    private record BarGrid(int[] barOfChord, int barCount) {
    }

    /**
     * Nudge back off a chord's end before asking which bar it lies in.
     *
     * <p>A chord ending exactly on a bar line ends at the first instant of the
     * next bar, and does not occupy it. Small enough not to move a boundary that
     * is genuinely inside a bar, since the shortest span anything here produces
     * is a sixty-fourth of a quarter beat.
     */
    private static final double BAR_END_NUDGE = 1e-9;

    private static BarGrid barGrid(Score score, double barDuration) {
        List<Chord> chords = score.chords().chords();
        int[] barOfChord = new int[chords.size()];
        int lastBar = 0;

        if (score.chords().isQuantized()) {
            TempoMap map = score.tempoMap();
            int origin = map.toMusicalTime(chords.get(0).startBeat().orElseThrow()).bar();
            for (int i = 0; i < chords.size(); i++) {
                Chord chord = chords.get(i);
                double startBeat = chord.startBeat().orElseThrow();
                double lastBeat = Math.max(startBeat, chord.endBeat().orElseThrow() - BAR_END_NUDGE);
                barOfChord[i] = map.toMusicalTime(startBeat).bar() - origin;
                lastBar = Math.max(lastBar, map.toMusicalTime(lastBeat).bar() - origin);
            }
            return new BarGrid(barOfChord, Math.max(1, lastBar + 1));
        }

        double origin = firstBarStart(score);
        // Sized from where the harmony ends, not from the recording's duration:
        // trailing silence would otherwise print as empty bars, and sizing from
        // chord starts alone would drop the bars a sustained chord holds through.
        double lastEnd = chords.stream().mapToDouble(Chord::endSeconds).max().orElse(origin);
        for (int i = 0; i < chords.size(); i++) {
            // Rounded, not floored: a chord detected a few milliseconds before
            // its downbeat belongs to the bar it starts, not the one before.
            barOfChord[i] = (int) Math.round((chords.get(i).startSeconds() - origin) / barDuration);
        }
        return new BarGrid(barOfChord,
                Math.max(1, (int) Math.round((lastEnd - origin) / barDuration)));
    }

    /**
     * Groups chords into bars and bars into lines.
     *
     * <p>Where a bar holds several chords they are printed together, and where
     * it holds none the previous chord is understood to continue, which is how a
     * chart is normally read.
     */
    static List<String> barLines(Score score) {
        List<Chord> chords = score.chords().chords();
        double barDuration = barDurationSeconds(score);
        if (!(barDuration > 0) && !score.chords().isQuantized()) {
            return List.of(chords.stream().map(Chord::symbol)
                    .reduce((a, b) -> a + " | " + b).orElse(""));
        }

        BarGrid grid = barGrid(score, barDuration);
        List<List<String>> bars = new ArrayList<>();
        for (int i = 0; i < grid.barCount(); i++) {
            bars.add(new ArrayList<>());
        }
        for (int i = 0; i < chords.size(); i++) {
            int bar = grid.barOfChord()[i];
            if (bar >= 0 && bar < bars.size()) {
                bars.get(bar).add(chords.get(i).symbol());
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
     * <p>Derived from {@link Score#estimatedTempo()}, which is also what
     * {@link #tempoLine} prints, so the header and the bar lines cannot disagree.
     * They used to: the header read the tempo map while this measured the tracked
     * beats, and {@code --tempo} moves only the map, so a chart could be headed
     * 60 BPM above bars a musician would count at 120.
     *
     * <p>That accessor keeps the reason this method preferred the grid in the
     * first place -- the map's synthetic lead-in segment carries an implausible
     * tempo when the first tracked beat is a fraction of a beat in, and a
     * drifting bar grid shows up immediately as chords landing in the wrong bar.
     * It just keeps it in one place rather than two.
     */
    private static double barDurationSeconds(Score score) {
        double quarterBpm = score.estimatedTempo();
        if (!(quarterBpm > 0) || !Double.isFinite(quarterBpm)) {
            return 0;
        }
        return score.tempoMap().initialTimeSignature().quarterBeatsPerBar() * 60.0 / quarterBpm;
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
        List<Chord> chords = score.chords().chords();
        // From the beat axis where there is one, for the reason on barGrid: a
        // tempo change otherwise gives one chord two bars and the next none, and
        // this is the source LilyPond actually engraves.
        BarGrid grid = chords.isEmpty() ? null : barGrid(score, barDuration);
        for (int index = 0; index < chords.size(); index++) {
            Chord chord = chords.get(index);
            // chordmode wants pitch, then duration, then modifier -- a1:m, not
            // a:m1, which LilyPond rejects.
            int bars = score.chords().isQuantized()
                    ? Math.max(1, (index + 1 < chords.size()
                            ? grid.barOfChord()[index + 1] : grid.barCount())
                            - grid.barOfChord()[index])
                    : Math.max(1, (int) Math.round(chord.durationSeconds() / barDuration));
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
