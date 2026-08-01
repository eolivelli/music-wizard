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
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

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

        List<ChartLayout.Bar> bars = ChartLayout.of(score);
        TimeSignature meter = countedIn(score, bars);
        out.append(tempoLine(score, meter));
        out.append("Meter  ").append(meter).append('\n');
        score.primaryKey().ifPresent(key -> out.append("Key    ")
                .append(key.displayName()).append('\n'));
        out.append('\n');

        for (String line : linesOf(bars)) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * The meter the chart is read in: its own first bar's, not the piece's.
     *
     * <p>The two differ only when the harmony starts after a meter change, and
     * then it is the piece's that is wrong: it would name a meter no bar of the
     * chart is in, and -- because the tempo is counted in this same meter --
     * hand a 6/8 chart a metronome mark 50% fast, which is the failure the
     * counted beat exists to prevent, arriving by the other door. Round 2 of
     * review found that on the text chart; it is answered here rather than
     * there because the engraving now needs the same answer, and a second copy
     * of the rule is a second chance for the two charts of one score to be
     * counted differently. The header still names one meter where a chart can
     * hold several, which is #191.
     */
    private static TimeSignature countedIn(Score score, List<ChartLayout.Bar> bars) {
        return bars.isEmpty()
                ? score.tempoMap().initialTimeSignature()
                : bars.get(0).meter();
    }

    /**
     * The tempo, in the beat the reader counts.
     *
     * <p>The map stores quarter notes per minute. Printed unqualified next to a
     * {@code Meter 6/8} line that makes it look authoritative, that is a
     * metronome marking 50% fast, because a 6/8 bar is counted in dotted
     * quarters. Identical in every x/4 meter, where the two coincide.
     */
    private static String tempoLine(Score score, TimeSignature meter) {
        double quarterBpm = score.estimatedTempo();
        // Locale.ROOT, because this number is meant to be typed back in via
        // --tempo and picocli parses it with Double.valueOf. What a default
        // locale changes here is the digits and not the separator: %.0f prints
        // no fractional part, so no decimal comma can arise from it, but under
        // ar_EG it prints Arabic-Indic digits, which Double.valueOf rejects.
        // Round 2 of review on #216 found this comment claiming the comma --
        // AnalyzeCommand prints the same tempo with %.1f, where it is real, and
        // the sentence had been carried across to a formatter that cannot
        // reach it.
        if (meter.beatUnitQuarters() == 1.0) {
            return String.format(Locale.ROOT, "Tempo  %.0f BPM\n", quarterBpm);
        }
        return String.format(Locale.ROOT, "Tempo  %.0f BPM (%.0f quarter notes/min)\n",
                meter.countedTempo(quarterBpm), quarterBpm);
    }

    /**
     * Groups the chart's cells into bars and bars into lines.
     *
     * <p>Where a bar holds several chords they are printed together, and where
     * it holds none the previous chord is understood to continue, which is how a
     * chart is normally read. Which bar is which is {@link ChartLayout}'s
     * answer, not this method's: the engraving reads the same one, so the two
     * cannot disagree about where a bar line falls or which chord is in it.
     */
    static List<String> barLines(Score score) {
        return linesOf(ChartLayout.of(score));
    }

    /**
     * The same, over a layout the caller has already taken.
     *
     * <p>Both emitters take a {@code List<Bar>} as well as a {@link Score},
     * because the layout is two stages with two different promises --
     * {@link ChartLayout#unreduced} drops nothing and {@link ChartLayout#of}
     * reduces on purpose -- and a suite that could only see the second could not
     * tell a chord absorbed deliberately from one lost to arithmetic. That
     * distinction is #174 against #212, and it is the one this file's history
     * turns on.
     */
    static List<String> linesOf(List<ChartLayout.Bar> bars) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int onThisLine = 0;
        for (ChartLayout.Bar bar : bars) {
            String cell = bar.cells().stream()
                    .filter(ChartLayout.Cell::named)
                    .map(ChartLayout.Cell::symbol)
                    .collect(Collectors.joining(" "));
            line.append(String.format("| %-12s", cell.isEmpty() ? "%" : cell));
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
     * Renders the chart as LilyPond source.
     *
     * <p>Emitted directly from the model rather than by converting MusicXML,
     * which loses information on the way through.
     *
     * <p>The page carries what the text chart's first three lines carry: the
     * title, the artist and how fast it goes. A chart is printed to be handed
     * to somebody, and one headed {@code Untitled} with no tempo asks them to
     * remember which recording it came from and to find the tempo by ear --
     * which is #216, observed on the first real commercial recording tried.
     */
    public static String toLilyPond(Score score) {
        Objects.requireNonNull(score, "score");
        return lilyPondOf(score, ChartLayout.of(score));
    }

    /** The same, over a layout the caller has already taken. See {@link #linesOf}. */
    static String lilyPondOf(Score score, List<ChartLayout.Bar> bars) {
        StringBuilder out = new StringBuilder();
        out.append("\\version \"2.24.0\"\n\n");
        out.append("\\header {\n");
        out.append("  title = \"").append(escape(score.title().orElse("Untitled"))).append("\"\n");
        score.artist().ifPresent(artist ->
                out.append("  composer = \"").append(escape(artist)).append("\"\n"));
        out.append("  tagline = ##f\n");
        out.append("}\n\n");

        out.append("\\score {\n");
        // chordChanges, so a chord held across a bar line has its name printed
        // once. The bar checks below require every bar to be written out, and
        // without this the page would say "C C C" where the text chart says
        // "| C | % | % |" -- the same disagreement between the two outputs that
        // deciding the bars twice used to produce.
        out.append("  \\new ChordNames \\with { chordChanges = ##t } {\n");
        // Outside \chordmode, which is where a mark belongs that is not a chord:
        // inside it, every line of the block is a bar whose durations have to
        // sum to the meter, and this one has no duration at all.
        TempoMark.of(score, countedIn(score, bars))
                .ifPresent(mark -> out.append("    ").append(mark.lilyPond()).append('\n'));
        out.append("    \\chordmode {\n");

        for (ChartLayout.Bar bar : bars) {
            if (bar.meterChanged()) {
                out.append("      ").append(LilyPondMeter.time(bar.meter())).append('\n');
            }
            out.append("      ");
            for (ChartLayout.Cell cell : bar.cells()) {
                out.append(chordMode(cell)).append(' ');
            }
            // One bar to a line, so that when LilyPond does complain the line
            // number it prints names the bar that does not add up.
            out.append("|\n");
        }
        out.append("    }\n  }\n");
        out.append("  \\layout { }\n");
        out.append("}\n");
        return out.toString();
    }

    /**
     * One chordmode event: a rest for no-chord, otherwise root, duration,
     * quality and any bass.
     *
     * <p>The duration is the cell's, which is how much of its bar the chord
     * fills -- not the chord's own length. Those differ whenever a chord is held
     * over a bar line, and it is the cell's that has to be written, because the
     * bar check after it is only a check if the durations in the bar sum to the
     * meter.
     *
     * <p>The bass is what makes {@code C/E} engrave as C/E rather than as C.
     * Dropping it printed a chord in root position that the chart said was an
     * inversion, which is a different instruction to a bass player.
     */
    private static String chordMode(ChartLayout.Cell cell) {
        String duration = LilyPondDuration.scaled(cell.lengthQuarters());
        if (cell.chord().isEmpty()) {
            return "r" + duration;
        }
        Chord chord = cell.chord().get();
        if (chord.isNoChord()) {
            return "r" + duration;
        }
        String symbol = chord.root().lilyPondName() + duration + lilyPondQuality(chord);
        return chord.isSlashChord()
                ? symbol + "/" + chord.bass().orElseThrow().lilyPondName()
                : symbol;
    }

    /**
     * The quality modifier, which follows the duration in chordmode.
     *
     * <p>Every quality has its own case and there is no {@code default}, so the
     * switch is exhaustive and adding a constant to {@link ChordQuality} stops
     * the build rather than quietly engraving the wrong chord. It used to have
     * one, and it collapsed four qualities onto {@code :m} and three onto
     * {@code :dim} -- which was invisible while only major, minor and no-chord
     * could reach here, and became visible the moment #115 gave the symbolic
     * estimator a wider vocabulary. A chart that said {@code Am7} engraved as
     * Am, and one that said {@code Bm7b5} engraved as a B diminished triad: a
     * different chord, printed confidently.
     *
     * <p>The modifiers are the ones LilyPond means the same thing by, checked by
     * rendering each and reading the pitches back out of the engraver's own MIDI
     * -- {@code :m7.5-} is a half-diminished seventh and prints as Bø,
     * {@code :m7+} is a minor triad with a major seventh, and both {@code :maj7}
     * and {@code :m7+} print their seventh as the conventional triangle.
     */
    private static String lilyPondQuality(Chord chord) {
        return switch (chord.quality()) {
            case MAJOR -> "";
            case MINOR -> ":m";
            case DIMINISHED -> ":dim";
            case AUGMENTED -> ":aug";
            case SUSPENDED_SECOND -> ":sus2";
            case SUSPENDED_FOURTH -> ":sus4";
            case DOMINANT_SEVENTH -> ":7";
            case MAJOR_SEVENTH -> ":maj7";
            case MINOR_SEVENTH -> ":m7";
            case MINOR_MAJOR_SEVENTH -> ":m7+";
            case HALF_DIMINISHED_SEVENTH -> ":m7.5-";
            case DIMINISHED_SEVENTH -> ":dim7";
            case SIXTH -> ":6";
            case MINOR_SIXTH -> ":m6";
            // Never reached: a no-chord span is written as a rest, which has no
            // root to hang a modifier on. Named rather than defaulted so the
            // exhaustiveness above is real.
            case NONE -> "";
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
