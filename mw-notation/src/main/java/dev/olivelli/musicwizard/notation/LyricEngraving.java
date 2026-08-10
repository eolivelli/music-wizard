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
import dev.olivelli.musicwizard.core.text.Hyphenator;

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
        // LilyPond drops a hyphen it cannot fit, and syllables of a sung word
        // sit close together -- so "go -- te" came out as "go te" on a real
        // page, reading as two words rather than one split in two. This is the
        // gap it will make room for rather than drop the hyphen.
        out.append("    \\override LyricHyphen.minimum-distance = #0.8\n");
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
                // A hyphen joins this syllable to the next one, so it is written
                // only when there is a next one to join: a chain running off the
                // end of the lyric, or one whose next syllable did not fit the
                // chart, leaves a hyphen with nothing on its right and LilyPond
                // reports an unterminated hyphen -- into the output this tool
                // reads to decide whether engraving went well.
                boolean joins = syllable.hyphenated() && at + 1 < syllables.size();
                line.append(joins ? " -- " : " ");
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
     * <p>One {@code Lyrics} context is a single lane and runs forwards only, so
     * a word that would land behind the lane's cursor is pushed up to the next
     * free unit instead, and <b>dropped only when that push runs past the last
     * bar</b>. A word is dropped one at a time rather than taken as the end of
     * the lyric, because words are not globally ordered — see
     * {@link dev.olivelli.musicwizard.core.model.Lyrics#allWords()} — so one
     * stray onset says nothing about the next.
     *
     * <p>Two things follow, and neither is claimed away. A word sung after the
     * chart's last bar has nowhere to go at all, because the chart spans the
     * harmony rather than the song. And a line overlapping the tail of the line
     * before it is not engraved where it was sung: its words come out crammed
     * against the cursor, and any that run off the end are lost. Engraving such
     * lines properly wants a second {@code Lyrics} context, which is #329. The
     * text sheet shows every word at its own moment and is the output to read
     * when the two differ.
     */
    private static List<Syllable> placed(Score score, List<ChartLayout.Bar> bars,
                                         long[] barStart) {
        // Absent for a language with no patterns, and for the "und" a lyric file
        // carries until something establishes one -- then a word stays whole,
        // which is what the page did before syllables were split at all.
        Optional<Hyphenator> hyphenator = Hyphenator.forLanguage(score.lyrics().language());
        List<Syllable> syllables = new ArrayList<>();
        long chartEnd = barStart[bars.size()];
        long previous = Long.MIN_VALUE;
        for (LyricLine line : score.lyrics().lines()) {
            for (LyricWord word : line.words()) {
                List<String> parts = hyphenator
                        .map(h -> h.syllables(word.text()))
                        .orElseGet(() -> List.of(word.text()));
                // All of a word or none of it -- see fitted. The unsplit word is
                // tried when the syllables will not fit, and only then is the
                // word dropped.
                List<Syllable> placed = fitted(parts, word, bars, barStart, chartEnd, previous);
                if (placed.isEmpty() && parts.size() > 1) {
                    placed = fitted(List.of(word.text()), word, bars, barStart,
                            chartEnd, previous);
                }
                if (!placed.isEmpty()) {
                    syllables.addAll(placed);
                    previous = placed.get(placed.size() - 1).unit();
                }
            }
        }
        return syllables;
    }

    /**
     * One word's syllables on the grid, or empty when they do not all fit.
     *
     * <p>All or nothing. Each syllable claims a grid unit, so a long word near
     * the end of the chart can run off it partway through, and printing what
     * fitted leaves a fragment on the page — which reads as a transcription
     * rather than as the omission it is. The units are
     * strictly increasing from {@code previous}, which is what gives every
     * syllable a duration of at least one grid step: two can otherwise land
     * together, the grid being finer than any singer and a short word's
     * syllables closer together still, and a zero-length syllable is one
     * LilyPond cannot name.
     */
    private static List<Syllable> fitted(List<String> parts, LyricWord word,
                                         List<ChartLayout.Bar> bars, long[] barStart,
                                         long chartEnd, long previous) {
        List<Syllable> placed = new ArrayList<>(parts.size());
        long cursor = previous;
        for (int i = 0; i < parts.size(); i++) {
            // Spread evenly across the word. Nothing knows where inside a word
            // its second syllable begins -- that is the note it is sung on, and
            // there is no melody yet (#8) -- so the even share is the honest
            // guess, and it keeps the syllables in the bar the word was sung in.
            double at = word.startSeconds()
                    + (word.endSeconds() - word.startSeconds()) * i / parts.size();
            long unit = Math.max(unitOf(at, bars, barStart), cursor + 1);
            if (unit >= chartEnd) {
                return List.of();
            }
            // Every syllable but the last joins the next with a hyphen; the last
            // carries whatever the word itself said.
            boolean joins = i + 1 < parts.size() || word.hyphenatedToNext();
            placed.add(new Syllable(unit, parts.get(i), joins));
            cursor = unit;
        }
        return placed;
    }

    /** Where a moment falls on the grid, by the bar holding it. */
    private static long unitOf(double seconds, List<ChartLayout.Bar> bars, long[] barStart) {
        int index = 0;
        while (index + 1 < bars.size() && bars.get(index + 1).startSeconds() <= seconds) {
            index++;
        }
        ChartLayout.Bar bar = bars.get(index);
        // The bar's own rate, so the last bar is measured like every other and a
        // tempo change inside the chart cannot be read at a neighbour's tempo.
        double perQuarter = bar.secondsPerQuarter();
        double into = perQuarter > 0 ? (seconds - bar.startSeconds()) / perQuarter : 0;
        long unit = barStart[index] + Math.round(into / UNIT);
        return Math.max(barStart[0], Math.min(unit, barStart[index + 1]));
    }

    private static String skip(long units) {
        return "\\skip " + LilyPondDuration.scaled(units * UNIT);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
