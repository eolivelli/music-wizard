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
 * The engraved lyric line: one or more {@code Lyrics} contexts under the chord
 * chart.
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

    /**
     * How many {@code Lyrics} contexts the page carries.
     *
     * <p>Two, which is what the overlaps the model states need: a recognition
     * span running into the line after it, and two lines written on one moment
     * (#340). Bounded at all because otherwise the count is whatever recognition
     * happened to overlap, and a lane costs vertical space on every system where
     * it holds a word. A line sung over more than one other falls back to the
     * forward push — see {@link #placed}.
     */
    private static final int LANES = 2;

    private LyricEngraving() {
    }

    /** One syllable, on the chart's own grid. */
    private record Syllable(long unit, String text, boolean hyphenated) {
    }

    /**
     * The {@code \new Lyrics} blocks for this score, one per lane, or empty when
     * there is nothing to place under the bars.
     *
     * @param bars the chart's bars, whose positions this reads rather than
     *             deriving a second time from the tempo map
     */
    static Optional<String> block(Score score, List<ChartLayout.Bar> bars) {
        if (score.lyrics().isEmpty() || bars.isEmpty()) {
            return Optional.empty();
        }
        long[] barStart = barStarts(bars);
        List<List<Syllable>> lanes = placed(score, bars, barStart);
        if (lanes.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder out = new StringBuilder();
        for (List<Syllable> syllables : lanes) {
            lane(out, syllables, bars, barStart);
        }
        return Optional.of(out.toString());
    }

    /**
     * One lane's {@code \new Lyrics} block, bar by bar.
     *
     * <p>Every lane spans the whole chart — a lane holding nothing in a bar
     * skips it — so the lanes stay aligned with the chords and with each other
     * whatever falls in them.
     */
    private static void lane(StringBuilder out, List<Syllable> syllables,
                             List<ChartLayout.Bar> bars, long[] barStart) {
        out.append("  \\new Lyrics \\with {\n");
        // DOWN, not the context's own default of UP. Read top to bottom the
        // affinities must not increase, and ChordNames is already DOWN, so a
        // Lyrics below it pointing up is the one arrangement LilyPond complains
        // about -- on every run, into output this tool parses to decide whether
        // engraving went well. The repeat-bracket lane above is DOWN for the
        // same reason, and equal affinities do not increase, which is what makes
        // any number of these lanes legal under the chords.
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
     * Every lyric word as a syllable on the grid, split into lanes, each lane in
     * order and strictly increasing.
     *
     * <p>A word is placed by the bar it falls in and how far through that bar it
     * is, which is what makes this read the chart's own axis rather than rebuild
     * one: the bars already carry both their position and their moment, and the
     * two routes that build them disagree about how seconds and beats relate.
     *
     * <p>A {@code Lyrics} context is a single lane and runs forwards only, and
     * lines may overlap in time — recognition spans on sung speech do, see
     * {@link dev.olivelli.musicwizard.core.model.Lyrics#allWords()}. So lines
     * take lanes first-fit in the order {@code Lyrics} keeps them, which is by
     * their first word: a line goes in the first lane whose last syllable was
     * sung before the line begins, and past {@link #LANES} into whichever lane
     * was free longest ago. A line goes into one lane whole, because {@code
     * hyphenatedToNext} joins a word to the next word <em>in its own line</em>,
     * so a line split across lanes would split a hyphen chain.
     *
     * <p>Within a lane a word that would still land behind the cursor is pushed
     * up to the next free unit, and <b>dropped only when that push runs past the
     * last bar</b>. A word is dropped one at a time rather than taken as the end
     * of the lyric, because words are not globally ordered either, so one stray
     * onset says nothing about the next.
     *
     * <p>Two things follow, and neither is claimed away. A word sung after the
     * chart's last bar has nowhere to go at all, because the chart spans the
     * harmony rather than the song. And a line pushed for want of a lane is not
     * engraved where it was sung: its words come out crammed against the cursor,
     * and any that run off the end are lost. The text sheet shows every word at
     * its own moment and is the output to read when the two differ.
     */
    private static List<List<Syllable>> placed(Score score, List<ChartLayout.Bar> bars,
                                               long[] barStart) {
        // Absent for a language with no patterns, and for the "und" a lyric file
        // carries until something establishes one -- then a word stays whole,
        // which is what the page did before syllables were split at all.
        Optional<Hyphenator> hyphenator = Hyphenator.forLanguage(score.lyrics().language());
        List<List<Syllable>> lanes = new ArrayList<>();
        // When the last syllable each lane engraved was sung. See laneFor.
        double[] sungAt = new double[LANES];
        for (int i = 0; i < LANES; i++) {
            lanes.add(new ArrayList<>());
            sungAt[i] = Double.NEGATIVE_INFINITY;
        }
        long chartEnd = barStart[bars.size()];
        for (LyricLine line : score.lyrics().lines()) {
            int lane = laneFor(line.startSeconds(), sungAt);
            List<Syllable> syllables = lanes.get(lane);
            long previous = syllables.isEmpty() ? Long.MIN_VALUE
                    : syllables.get(syllables.size() - 1).unit();
            for (LyricWord word : line.words()) {
                List<Hyphenator.Syllable> parts = hyphenator
                        .map(h -> h.syllables(word.text()))
                        .orElseGet(() -> List.of(new Hyphenator.Syllable(word.text(), false)));
                // All of a word or none of it -- see fitted. The unsplit word is
                // tried when the syllables will not fit, and only then is the
                // word dropped.
                List<Syllable> placed = fitted(parts, word, bars, barStart, chartEnd, previous);
                if (placed.isEmpty() && parts.size() > 1) {
                    placed = fitted(List.of(new Hyphenator.Syllable(word.text(), false)),
                            word, bars, barStart, chartEnd, previous);
                }
                if (!placed.isEmpty()) {
                    syllables.addAll(placed);
                    previous = placed.get(placed.size() - 1).unit();
                    sungAt[lane] = syllableSeconds(word, placed.size() - 1, placed.size());
                }
            }
        }
        // A lane nothing reached is not engraved: lyrics whose lines follow one
        // another come out the one block they always did.
        return lanes.stream().filter(lane -> !lane.isEmpty()).toList();
    }

    /**
     * The lane a line beginning at {@code startSeconds} goes in.
     *
     * <p>The first lane whose last syllable was sung before then, so a line
     * takes a fresh lane only where it would be engraved beside something still
     * being sung — which is what a second row of words on the page says. The
     * syllable rather than the word, because a word is spread across its own
     * length: a lane holding one word of three syllables sung over six seconds
     * is occupied for all six, and reading the word's own start would free it
     * after the first.
     *
     * <p>Held in sung time rather than in grid units, which the placement runs
     * on. A unit is rounded, clamped into the chart and pushed clear of its
     * neighbour, so two lines sung one after the other can land on one unit and
     * would read as simultaneous. And a lane is held by the syllables it
     * engraved rather than by the lines it was given, so a word dropped for want
     * of room does not send the lines after it into a lane of their own.
     *
     * <p>When every lane is held the line still has to go somewhere, and it goes
     * where the push costs least: the lane free longest. That is the behaviour a
     * single lane always had.
     */
    private static int laneFor(double startSeconds, double[] sungAt) {
        int least = 0;
        for (int lane = 0; lane < sungAt.length; lane++) {
            if (sungAt[lane] < startSeconds) {
                return lane;
            }
            if (sungAt[lane] < sungAt[least]) {
                least = lane;
            }
        }
        return least;
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
    private static List<Syllable> fitted(List<Hyphenator.Syllable> parts, LyricWord word,
                                         List<ChartLayout.Bar> bars, long[] barStart,
                                         long chartEnd, long previous) {
        List<Syllable> placed = new ArrayList<>(parts.size());
        long cursor = previous;
        for (int i = 0; i < parts.size(); i++) {
            long unit = Math.max(unitOf(syllableSeconds(word, i, parts.size()), bars, barStart),
                    cursor + 1);
            if (unit >= chartEnd) {
                return List.of();
            }
            // Only a break the hyphenator chose is drawn: a compound already
            // carries the hyphen it was written with, and a second one gives
            // well--known. The last syllable takes whatever the word itself said.
            boolean joins = i + 1 < parts.size()
                    ? parts.get(i).hyphenToNext() : word.hyphenatedToNext();
            placed.add(new Syllable(unit, parts.get(i).text(), joins));
            cursor = unit;
        }
        return placed;
    }

    /**
     * When the {@code index}th of a word's {@code parts} syllables is sung.
     *
     * <p>Spread evenly across the word. Nothing knows where inside a word its
     * second syllable begins — that is the note it is sung on, and there is no
     * melody yet (#8) — so the even share is the honest guess, and it keeps the
     * syllables in the bar the word was sung in.
     *
     * <p>Named because {@link #placed} reads it too, for the moment the last
     * syllable of a word is sung. Deriving that a second time is how a lane
     * comes to be held to a different moment from the one it was drawn at.
     */
    private static double syllableSeconds(LyricWord word, int index, int parts) {
        return word.startSeconds()
                + (word.endSeconds() - word.startSeconds()) * index / parts;
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
