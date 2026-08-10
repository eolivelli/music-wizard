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

import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Score;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The engraved lyric line: a {@code Lyrics} context under the chord chart.
 *
 * <p>Written in {@code \lyricmode} with an explicit duration on every syllable,
 * rather than as an {@code \addlyrics} or {@code \lyricsto} attached to a
 * {@code NullVoice}. Two reasons, both established by engraving the alternatives
 * rather than by reading about them:
 *
 * <ul>
 *   <li>What this stage has is onsets and durations. It has no pitches, and a
 *       {@code NullVoice} needs one invented per syllable purely to hang the
 *       lyric on — a second encoding of the same rhythm, which is the defect
 *       {@link ChartLayout} is careful not to have.
 *   <li><b>It is the only route where LilyPond reports a mismatch.</b> Bar checks
 *       work in {@code \lyricmode} and are read against the same timing the
 *       chords are. In the {@code NullVoice} route a surplus syllable is dropped
 *       and a surplus note left unlyricked, both in silence — so a golden file
 *       over the generated text would pass on a wrong page.
 * </ul>
 *
 * <p>The cost is extender lines: {@code __} is discarded in this mode, so a
 * held syllable draws no line. A chart with no melody has no melisma to draw one
 * over, which is why the trade is worth taking here and would not be on a staff.
 *
 * <p>Two mechanics of the mode decide how this is written. <b>A digit terminates
 * a syllable</b>, because durations are digits: {@code 1999} is a hard error and
 * {@code Apollo8} silently becomes "Apollo" with a duration of 8. Every syllable
 * is therefore quoted, unconditionally, since recognised lyrics carry years and
 * the like. And <b>an omitted duration repeats the previous one</b> rather than
 * defaulting to anything, so every syllable carries its own even where it
 * matches its neighbour.
 */
final class LyricEngraving {

    /** The grid syllables are placed on: the shortest value a duration can name. */
    private static final double UNIT = LilyPondDuration.SHORTEST_QUARTERS;

    private LyricEngraving() {
    }

    /** One syllable, on the chart's own grid. */
    private record Syllable(long unit, String text, boolean hyphenated) {
    }

    /**
     * The {@code \new Lyrics} block for this score, or empty when there is
     * nothing to place under the bars.
     *
     * @param bars the chart's bars, whose positions this reads rather than
     *             deriving a second time from the tempo map
     */
    static Optional<String> block(Score score, List<ChartLayout.Bar> bars) {
        if (score.lyrics().isEmpty() || bars.isEmpty()) {
            return Optional.empty();
        }
        long[] barStart = barStarts(bars);
        List<Syllable> syllables = placed(score, bars, barStart);
        if (syllables.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder out = new StringBuilder();
        out.append("  \\new Lyrics \\with {\n");
        // DOWN, not the context's own default of UP. Read top to bottom the
        // affinities must not increase, and ChordNames is already DOWN, so a
        // Lyrics below it pointing up is the one arrangement LilyPond complains
        // about -- on every run, into output this tool parses to decide whether
        // engraving went well. The repeat-bracket lane above is DOWN for the
        // same reason, which is what makes the three of them legal together.
        out.append("    \\override VerticalAxisGroup.staff-affinity = #DOWN\n");
        out.append("    \\override VerticalAxisGroup"
                + ".nonstaff-nonstaff-spacing.basic-distance = #3\n");
        // Chord names are left-aligned on their moment and lyric syllables are
        // centred on theirs, so without this a syllable sits half its width to
        // the left of the chord it belongs to.
        out.append("    \\override LyricText.self-alignment-X = #LEFT\n");
        out.append("  } \\lyricmode {\n");

        int at = 0;
        for (int i = 0; i < bars.size(); i++) {
            long from = barStart[i];
            long to = barStart[i + 1];
            StringBuilder line = new StringBuilder();
            long cursor = from;
            while (at < syllables.size() && syllables.get(at).unit() < to) {
                Syllable syllable = syllables.get(at);
                if (syllable.unit() > cursor) {
                    line.append(skip(syllable.unit() - cursor)).append(' ');
                    cursor = syllable.unit();
                }
                long until = at + 1 < syllables.size()
                        ? Math.min(syllables.get(at + 1).unit(), to) : to;
                line.append('"').append(escape(syllable.text())).append('"')
                        .append(LilyPondDuration.scaled((until - cursor) * UNIT));
                // A hyphen joins this syllable to the next one on the page, and
                // is written between them with spaces of its own.
                line.append(syllable.hyphenated() ? " -- " : " ");
                cursor = until;
                at++;
            }
            if (cursor < to) {
                line.append(skip(to - cursor)).append(' ');
            }
            // One bar to a line and a check closing each, so a duration that does
            // not sum is reported against the bar it is in rather than against
            // the block. This is the whole reason for the mode -- see the class
            // javadoc -- and LilyPondRenderer already reads those warnings.
            out.append("    ").append(line).append("|\n");
        }
        out.append("  }\n");
        return Optional.of(out.toString());
    }

    /**
     * Each bar's start on the grid, with one extra entry for the chart's end.
     *
     * <p>Accumulated from the first bar's own position rather than read off each
     * bar, so the boundaries are contiguous by construction: a syllable cannot
     * fall in a crack between two bars, and the durations of one bar always sum
     * to the distance to the next.
     */
    private static long[] barStarts(List<ChartLayout.Bar> bars) {
        long[] starts = new long[bars.size() + 1];
        starts[0] = Math.round(bars.get(0).startQuarters() / UNIT);
        for (int i = 0; i < bars.size(); i++) {
            starts[i + 1] = starts[i] + Math.round(bars.get(i).lengthQuarters() / UNIT);
        }
        return starts;
    }

    /**
     * Every lyric word as a syllable on the grid, in order and strictly
     * increasing.
     *
     * <p>A word is placed by the bar it falls in and how far through that bar it
     * is, which is what makes this read the chart's own axis rather than rebuild
     * one: the bars already carry both their position and their moment, and the
     * two routes that build them disagree about how seconds and beats relate.
     *
     * <p><b>Words past the end of the chart are dropped.</b> The chart spans the
     * harmony, and a lyric sung after the last chord has no bar to sit in; the
     * alternative is piling them onto the final unit, where they would print on
     * top of one another and the bar would no longer sum. The text sheet shows
     * every word and is the output to read when the two differ.
     */
    private static List<Syllable> placed(Score score, List<ChartLayout.Bar> bars,
                                         long[] barStart) {
        List<Syllable> syllables = new ArrayList<>();
        long chartEnd = barStart[bars.size()];
        long previous = Long.MIN_VALUE;
        for (LyricLine line : score.lyrics().lines()) {
            for (LyricWord word : line.words()) {
                long unit = unitOf(word.startSeconds(), bars, barStart);
                // Strictly increasing, so every syllable gets a duration of at
                // least one grid step. Two words can land on one unit -- the
                // grid is finer than any singer, but a recognition segment can
                // still hand out equal onsets -- and a zero-length syllable is
                // one LilyPond cannot name.
                unit = Math.max(unit, previous + 1);
                if (unit >= chartEnd) {
                    return syllables;
                }
                syllables.add(new Syllable(unit, word.text(), word.hyphenatedToNext()));
                previous = unit;
            }
        }
        return syllables;
    }

    /** Where a moment falls on the grid, by the bar holding it. */
    private static long unitOf(double seconds, List<ChartLayout.Bar> bars, long[] barStart) {
        int index = 0;
        while (index + 1 < bars.size() && bars.get(index + 1).startSeconds() <= seconds) {
            index++;
        }
        ChartLayout.Bar bar = bars.get(index);
        double perQuarter = secondsPerQuarter(bars, index);
        double into = perQuarter > 0 ? (seconds - bar.startSeconds()) / perQuarter : 0;
        long unit = barStart[index] + Math.round(into / UNIT);
        return Math.max(barStart[0], Math.min(unit, barStart[index + 1]));
    }

    /**
     * How long a quarter beat lasts in one bar, measured from the bar after it.
     *
     * <p>Measured rather than taken from {@link Score#estimatedTempo()} because
     * the chart's bars are where the two routes have already agreed: on a piece
     * that changes tempo, one estimated rate would place a syllable in a
     * different bar from the chord sounding under it.
     */
    private static double secondsPerQuarter(List<ChartLayout.Bar> bars, int index) {
        int from = Math.min(index, bars.size() - 2);
        if (from < 0) {
            // A one-bar chart has no next bar to measure against. Its own length
            // is all there is, and a syllable inside it is placed by proportion.
            ChartLayout.Bar only = bars.get(0);
            return only.lengthQuarters() > 0 ? 1.0 / only.lengthQuarters() : 0;
        }
        double span = bars.get(from + 1).startSeconds() - bars.get(from).startSeconds();
        double quarters = bars.get(from).lengthQuarters();
        return quarters > 0 ? span / quarters : 0;
    }

    private static String skip(long units) {
        return "\\skip " + LilyPondDuration.scaled(units * UNIT);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
