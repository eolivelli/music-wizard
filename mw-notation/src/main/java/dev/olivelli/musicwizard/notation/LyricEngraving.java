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
 * <p>Extender lines survive the mode only beside a staff. {@code __} is
 * discarded unless the lane names an {@code associatedVoice}, so a lane under a
 * melody staff names its voice and writes {@code __} after each syllable marked
 * as a melisma (#625). LilyPond draws the line to the next syllable however far
 * away that is, and draws one nothing terminates to the end of the piece, both
 * in silence — and either would claim the singer holds notes the model never
 * said are sung. So a melisma whose recorded extent ends before the next
 * syllable is closed by a syllable of empty text on its ending unit, which
 * prints nothing. A chart has no staff to name, so a held syllable there still
 * draws nothing.
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

    /**
     * The grid syllables are placed on, shared with every other lane the chart
     * carries -- see {@link ChartGrid}, which owns it and the placement.
     */
    private static final double UNIT = ChartGrid.UNIT;

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

    /**
     * One syllable, on the chart's own grid. A melisma is a syllable whose
     * {@code heldTo} reaches past its own unit — the marked word's recorded
     * extent (#597), which is where the extender drawn after it must stop
     * unless another syllable stops it sooner.
     */
    private record Syllable(long unit, String text, boolean hyphenated, long heldTo) {

        boolean melisma() {
            return heldTo > unit;
        }
    }

    /**
     * One {@code Lyrics} context being filled, and the two extents of what it
     * holds: where its drawing has reached on the grid, and how far through the
     * song it has reached in sung time.
     *
     * <p>The two are different questions — see {@link #laneFor} — and both are
     * advanced by {@link #add}, at the one point a lane gains anything, because
     * a lane whose extents disagree engraves a line over one already there.
     */
    private static final class Lane {

        private final List<Syllable> syllables = new ArrayList<>();

        /** The unit the last syllable took, which the next one must clear. */
        private long lastUnit;

        /** The latest of the moments its syllables are sung at. */
        private double sungThrough = Double.NEGATIVE_INFINITY;

        Lane(long opening) {
            lastUnit = opening - 1;
        }

        /**
         * Adds one word's syllables, the last of them sung at {@code seconds}.
         *
         * <p>The greatest such moment, not the last one given: within a line a
         * held word can still be sounding when the word written after it is
         * sung, so the moments do not arrive in order.
         */
        void add(List<Syllable> word, double seconds) {
            syllables.addAll(word);
            lastUnit = word.get(word.size() - 1).unit();
            sungThrough = Math.max(sungThrough, seconds);
        }
    }

    /**
     * The {@code \new Lyrics} blocks for this score, one per lane, or empty when
     * there is nothing to place under the bars.
     *
     * @param bars the chart's bars, whose positions this reads rather than
     *             deriving a second time from the tempo map
     */
    /**
     * The same, leaning towards whatever it is written under and opening where
     * the staff beside it opens.
     *
     * <p>A {@code \partial} is a claim about the score's shared timing, so a
     * lane that still wrote bar zero full sits one bar less the pickup behind
     * the music from bar one onwards. LilyPond reports that once and then
     * resynchronises, so the whole lane is displaced behind a single failed bar
     * check (#601).
     *
     * @param associatedVoice the melody voice each lane names, where the score
     *                        engraves one; the class javadoc says what naming
     *                        it draws
     */
    static Optional<String> block(Score score, List<ChartLayout.Bar> bars,
                                  Attachment attachment,
                                  Optional<StaffNotation.Pickup> pickup,
                                  Optional<String> associatedVoice) {
        if (score.lyrics().isEmpty() || bars.isEmpty()) {
            return Optional.empty();
        }
        long[] barStart = ChartGrid.barStarts(bars);
        Opening opening = Opening.of(barStart, pickup);
        List<List<Syllable>> lanes = placed(score, bars, barStart, opening.unit());
        if (lanes.isEmpty()) {
            return Optional.empty();
        }
        if (associatedVoice.isPresent()) {
            lanes = lanes.stream().map(LyricEngraving::terminated).toList();
        }

        StringBuilder out = new StringBuilder();
        for (List<Syllable> syllables : lanes) {
            lane(out, syllables, bars, barStart, attachment, opening, associatedVoice);
        }
        return Optional.of(out.toString());
    }

    /**
     * Where a lane's first bar begins: the grid unit its syllables start at, and
     * the length by which the pickup reaches back past that unit.
     *
     * <p>The cut moves the origin and nothing else. A syllable is an event, so
     * the chord names' answer — dropping what came before the pickup — is not
     * available: {@link #placed} pushes a word sung before the staff enters onto
     * the opening rather than off the page.
     *
     * @param unit                the first grid unit inside the printed music
     * @param residualNumerator   how far {@code unit} falls short of the pickup,
     *                            as a fraction of a whole note; zero when the
     *                            pickup lands on the grid
     * @param residualDenominator that fraction's denominator
     */
    private record Opening(long unit, long residualNumerator, long residualDenominator) {

        /** The opening for a pickup, whose length the grid can rarely name exactly. */
        static Opening of(long[] barStart, Optional<StaffNotation.Pickup> pickup) {
            if (pickup.isEmpty()) {
                return new Opening(barStart[0], 0, 1);
            }
            long numerator = pickup.get().wholeNoteNumerator();
            long denominator = pickup.get().wholeNoteDenominator();
            // Floored, so what is left over is never negative, and clamped for
            // the case where the chart's first bar is shorter than the pickup —
            // the whole of it is then inside the printed music and the rest of
            // the pickup is lead-in the chart does not reach.
            long units = Math.min(barStart[1] - barStart[0],
                    numerator * ChartGrid.UNITS_PER_WHOLE / denominator);
            return new Opening(barStart[1] - units,
                    numerator * ChartGrid.UNITS_PER_WHOLE - units * denominator,
                    denominator * ChartGrid.UNITS_PER_WHOLE);
        }

        /** The rest that fills the gap between the pickup's start and {@link #unit}. */
        Optional<String> leadIn() {
            return residualNumerator == 0 ? Optional.empty()
                    : Optional.of(ChartGrid.skip(residualNumerator, residualDenominator));
        }
    }

    /**
     * Which way a lyric lane leans, which is decided by what is above it.
     *
     * <p>Not a preference. A {@code Lyrics} context attaches to a neighbouring
     * staff, and reading top to bottom the staff affinities must not increase —
     * so the answer is forced by the context above, and getting it wrong makes
     * LilyPond complain on every run, into output this tool parses to decide
     * whether engraving went well.
     */
    enum Attachment {
        /**
         * Under the chord names of a chart, which point down because they have
         * no staff of their own. Equal affinities do not increase, which is what
         * makes any number of lanes legal under them.
         */
        BELOW_CHORDS("#DOWN"),
        /** Under the melody staff of a lead sheet, which is a staff and is above. */
        BELOW_STAFF("#UP");

        private final String affinity;

        Attachment(String affinity) {
            this.affinity = affinity;
        }

        String affinity() {
            return affinity;
        }
    }

    /**
     * The lane with a syllable of empty text closing each melisma no written
     * syllable closes.
     *
     * <p>The extender is drawn to whatever syllable comes next, so a melisma
     * whose extent reaches the next syllable needs nothing — and one whose
     * extent ends first, or that has no next syllable at all, gets one put on
     * its ending unit. Empty text prints nothing, takes the units the melisma's
     * tail already occupied, and stops the line. After this pass every melisma
     * has a syllable after it, which is what lets {@link #lane} write {@code
     * __} unconditionally.
     */
    private static List<Syllable> terminated(List<Syllable> syllables) {
        List<Syllable> out = new ArrayList<>(syllables.size());
        for (int i = 0; i < syllables.size(); i++) {
            Syllable syllable = syllables.get(i);
            out.add(syllable);
            long next = i + 1 < syllables.size()
                    ? syllables.get(i + 1).unit() : Long.MAX_VALUE;
            if (syllable.melisma() && syllable.heldTo() < next) {
                out.add(new Syllable(syllable.heldTo(), "", false, syllable.heldTo()));
            }
        }
        return out;
    }

    /**
     * One lane's {@code \new Lyrics} block, bar by bar.
     *
     * <p>Every lane spans the whole chart — a lane holding nothing in a bar
     * skips it — so the lanes stay aligned with the chords and with each other
     * whatever falls in them.
     */
    private static void lane(StringBuilder out, List<Syllable> syllables,
                             List<ChartLayout.Bar> bars, long[] barStart,
                             Attachment attachment, Opening opening,
                             Optional<String> associatedVoice) {
        out.append("  \\new Lyrics \\with {\n");
        // Which way is not this lane's choice; see Attachment.
        out.append("    \\override VerticalAxisGroup.staff-affinity = ")
                .append(attachment.affinity()).append('\n');
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
        associatedVoice.ifPresent(voice ->
                out.append("    \\set associatedVoice = \"").append(voice).append("\"\n"));

        int at = 0;
        for (int i = 0; i < bars.size(); i++) {
            long from = i == 0 ? opening.unit() : barStart[i];
            long to = barStart[i + 1];
            StringBuilder line = new StringBuilder();
            if (i == 0) {
                opening.leadIn().ifPresent(rest -> line.append(rest).append(' '));
            }
            long cursor = from;
            while (at < syllables.size() && syllables.get(at).unit() < to) {
                Syllable syllable = syllables.get(at);
                if (syllable.unit() > cursor) {
                    line.append(ChartGrid.skip(syllable.unit() - cursor)).append(' ');
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
                // No next-syllable guard on the extender: terminated() has
                // already put one after every melisma, precisely because an
                // extender nothing terminates is drawn to the end of the
                // piece without a word of complaint.
                boolean extender = syllable.melisma() && associatedVoice.isPresent();
                line.append(joins ? " -- " : extender ? " __ " : " ");
                cursor = until;
                at++;
            }
            if (cursor < to) {
                line.append(ChartGrid.skip(to - cursor)).append(' ');
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
     * their first word: a line goes in the first lane whose syllables have all
     * been sung before the line begins, and past {@link #LANES} into whichever
     * lane was free longest ago. A line goes into one lane whole, because {@code
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
                                               long[] barStart, long opening) {
        // Absent for a language with no patterns, and for the "und" a lyric file
        // carries until something establishes one -- then a word stays whole,
        // which is what the page did before syllables were split at all.
        Optional<Hyphenator> hyphenator = Hyphenator.forLanguage(score.lyrics().language());
        List<Lane> lanes = new ArrayList<>();
        for (int i = 0; i < LANES; i++) {
            lanes.add(new Lane(opening));
        }
        long chartEnd = barStart[bars.size()];
        for (LyricLine line : score.lyrics().lines()) {
            Lane lane = lanes.get(laneFor(line.startSeconds(), lanes));
            List<LyricWord> words = line.words();
            int at = 0;
            while (at < words.size()) {
                // The list is not always one word to a word: an aligner that
                // measures syllables hands each of them over separately, and a
                // compound is written as one word and joined as several. A run
                // of joined words is one word on the page, so fitted's rule
                // holds over the run rather than over each of them.
                int end = at;
                while (end + 1 < words.size() && words.get(end).hyphenatedToNext()) {
                    end++;
                }
                List<LyricWord> run = words.subList(at, end + 1);
                // The unsplit run is tried when the syllables will not fit, and
                // only then is it dropped.
                Placed placed = fittedRun(run, hyphenator, true, bars, barStart,
                        chartEnd, lane.lastUnit);
                if (placed == null) {
                    placed = fittedRun(run, hyphenator, false, bars, barStart,
                            chartEnd, lane.lastUnit);
                }
                if (placed != null) {
                    lane.add(placed.syllables(), placed.sungThrough());
                }
                at = end + 1;
            }
        }
        // A lane nothing reached is not engraved: lyrics whose lines follow one
        // another come out the one block they always did.
        return lanes.stream().filter(lane -> !lane.syllables.isEmpty())
                .map(lane -> lane.syllables).toList();
    }

    /** A run that fitted: its syllables, and when the last of them is sung. */
    private record Placed(List<Syllable> syllables, double sungThrough) {
    }

    /**
     * One run of hyphen-joined words on the grid, or {@code null} when any part
     * of it does not fit.
     *
     * <p>{@code split} chooses between the hyphenator's syllables and the words
     * as written, which is what makes the retry above a weaker attempt at the
     * same run rather than a different one.
     */
    private static Placed fittedRun(List<LyricWord> run, Optional<Hyphenator> hyphenator,
                                    boolean split, List<ChartLayout.Bar> bars,
                                    long[] barStart, long chartEnd, long previous) {
        List<Syllable> all = new ArrayList<>();
        long cursor = previous;
        double sungThrough = Double.NEGATIVE_INFINITY;
        for (LyricWord word : run) {
            List<Hyphenator.Syllable> parts = split
                    ? hyphenator.map(h -> h.syllables(word.text()))
                            .orElseGet(() -> List.of(new Hyphenator.Syllable(word.text(), false)))
                    : List.of(new Hyphenator.Syllable(word.text(), false));
            List<Syllable> placed = fitted(parts, word, bars, barStart, chartEnd, cursor,
                    hyphenator);
            if (placed.isEmpty()) {
                return null;
            }
            all.addAll(placed);
            cursor = placed.get(placed.size() - 1).unit();
            // The greatest, as Lane.add takes: a held piece can still be
            // sounding when the one written after it is sung.
            sungThrough = Math.max(sungThrough,
                    syllableSeconds(word, placed.size() - 1, placed.size()));
        }
        return new Placed(List.copyOf(all), sungThrough);
    }

    /**
     * The lane a line beginning at {@code startSeconds} goes in.
     *
     * <p>The first lane sung through before then, so a line takes a fresh lane
     * only where it would be engraved beside something still being sung — which
     * is what a second row of words on the page says. Syllables rather than
     * words, because a word's syllables are spread across its own length, and
     * reading the word's own start would free the lane after the first of them.
     *
     * <p>Sung time rather than the grid the placement runs on. A unit is
     * rounded, clamped into the chart and pushed clear of its neighbour, so two
     * lines sung one after the other can land on one unit and would read as
     * simultaneous. And a lane is held by the syllables it engraved rather than
     * by the lines it was given, so a word dropped for want of room does not
     * send the lines after it into a lane of their own.
     *
     * <p>When every lane is held the line still has to go somewhere, and it goes
     * where the push costs least: the lane free longest. That is the behaviour a
     * single lane always had.
     */
    private static int laneFor(double startSeconds, List<Lane> lanes) {
        int least = 0;
        for (int lane = 0; lane < lanes.size(); lane++) {
            if (lanes.get(lane).sungThrough < startSeconds) {
                return lane;
            }
            if (lanes.get(lane).sungThrough < lanes.get(least).sungThrough) {
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
                                         long chartEnd, long previous,
                                         Optional<Hyphenator> hyphenator) {
        List<Syllable> placed = new ArrayList<>(parts.size());
        long cursor = previous;
        for (int i = 0; i < parts.size(); i++) {
            long unit = Math.max(ChartGrid.unitOf(syllableSeconds(word, i, parts.size()), bars, barStart),
                    cursor + 1);
            if (unit >= chartEnd) {
                return List.of();
            }
            // Whether this syllable continues into the next, and whether a
            // hyphen is drawn between them, are two questions. A compound
            // already carries the hyphen it was written with, so drawing one
            // after it gives well--known -- and a syllable can reach here from
            // an aligner rather than from the hyphenator, carrying only the
            // first answer. So the second is derived from the text, in the one
            // place that defines what a separator is.
            String text = parts.get(i).text();
            boolean joins = (i + 1 < parts.size() || word.hyphenatedToNext())
                    && hyphenator.map(h -> !h.endsAtItsOwnBreak(text)).orElse(true);
            // The extent lands on the last piece only: the model marks the
            // word, the line is drawn after the syllable the word ends on, and
            // a piece that continues into the next word is joined by its
            // hyphen instead. Clamped inside the chart, and to its own unit --
            // no melisma at all -- where the extent has no room to reach past
            // it.
            boolean marked = word.melisma() && !word.hyphenatedToNext()
                    && i == parts.size() - 1;
            long heldTo = marked
                    ? Math.max(unit, Math.min(chartEnd - 1,
                            ChartGrid.unitOf(word.endSeconds(), bars, barStart)))
                    : unit;
            placed.add(new Syllable(unit, text, joins, heldTo));
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
     */
    private static double syllableSeconds(LyricWord word, int index, int parts) {
        return word.startSeconds()
                + (word.endSeconds() - word.startSeconds()) * index / parts;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
