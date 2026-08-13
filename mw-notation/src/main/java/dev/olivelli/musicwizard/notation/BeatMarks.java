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
import dev.olivelli.musicwizard.core.model.Score;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A lane of marks under the chord names, one per tracked beat.
 *
 * <p>The chart draws bar lines and nothing between them, so a reader can see
 * which bar a chord or a syllable is in and not which beat of it (#416). These
 * are the marks that answer that, and where they come from is the whole design:
 *
 * <p><b>They are placed from the beat grid's own times, never from the bar
 * axis.</b> A tick drawn at "this bar's start plus a quarter of its length"
 * is derived from the axis a reader would be using it to check, so it would sit
 * neatly inside every bar however wrong the bars were — the page would look
 * most correct exactly where the axis was worst. Drawn from the times the
 * tracker reported, a mark that does not line up with its bar line <em>is</em>
 * the disagreement, on the page. An instrument derived from the thing it
 * measures shows internal agreement and nothing else (#207).
 *
 * <p>Each mark is the beat's position within its bar, counted from one, so the
 * reader answers "beat 1 or beat 2" by reading rather than by counting marks —
 * and a "1" that does not sit on a bar line says the tracker and the chart
 * disagree about where the bar begins. A grid whose downbeats were never
 * decided has no position to print and prints a tick instead.
 */
final class BeatMarks {

    private BeatMarks() {
    }

    /** One placed mark: its grid unit, and what is printed there. */
    private record Mark(long unit, String text) {
    }

    /**
     * The {@code \new Lyrics} block of beat marks, or empty when there is
     * nothing to draw — no bars, no tracked grid, or a grid that falls entirely
     * outside the bars the chart covers.
     *
     * @param bars the chart's bars, whose positions this reads rather than
     *             deriving a second time from the tempo map
     */
    static Optional<String> block(Score score, List<ChartLayout.Bar> bars) {
        if (bars.isEmpty() || score.beatGrid().isEmpty()) {
            return Optional.empty();
        }
        long[] barStart = ChartGrid.barStarts(bars);
        List<Mark> marks = placed(score.beatGrid().get(), bars, barStart);
        if (marks.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        lane(out, marks, bars, barStart);
        return Optional.of(out.toString());
    }

    /**
     * Every tracked beat the chart has room for, in order and strictly
     * increasing on the grid.
     *
     * <p>A beat outside the bars is left out rather than clamped onto the first
     * or last of them: the chart spans the harmony and the grid spans the
     * recording, so a lead-in and a fade both fall outside, and a clamped mark
     * would pile marks onto one moment and read as a beat that was tracked
     * there.
     *
     * <p>Two beats can still round onto one unit, the grid being finer than any
     * tracker but not infinitely fine. The second is nudged one unit along
     * rather than dropped — a displacement far below what the page can show,
     * against a missing mark, which would read as a beat the tracker never
     * found.
     */
    private static List<Mark> placed(BeatGrid grid, List<ChartLayout.Bar> bars,
                                     long[] barStart) {
        double from = bars.get(0).startSeconds();
        double to = bars.get(bars.size() - 1).endSeconds();
        long chartEnd = barStart[bars.size()];
        List<Mark> marks = new ArrayList<>();
        long cursor = barStart[0] - 1;
        for (BeatGrid.Beat beat : grid.beats()) {
            if (beat.seconds() < from || beat.seconds() >= to) {
                continue;
            }
            long unit = Math.max(ChartGrid.unitOf(beat.seconds(), bars, barStart), cursor + 1);
            if (unit >= chartEnd) {
                break;
            }
            marks.add(new Mark(unit, text(beat)));
            cursor = unit;
        }
        return marks;
    }

    /** What a beat prints: its position in the bar, or a tick for an unphased one. */
    private static String text(BeatGrid.Beat beat) {
        return beat.positionInBar() < 0 ? "." : String.valueOf(beat.positionInBar() + 1);
    }

    /**
     * The lane, bar by bar.
     *
     * <p>A {@code Lyrics} context rather than a staff of hidden note heads, for
     * the reason {@link LyricEngraving} is one: this stage has onsets and no
     * pitches, and a staff needs a pitch invented per mark purely to hang the
     * mark on. It also puts the marks through the same bar checks the chords and
     * the syllables go through, so a lane that does not sum to its bar is
     * reported by LilyPond rather than engraved wrong in silence.
     */
    private static void lane(StringBuilder out, List<Mark> marks,
                             List<ChartLayout.Bar> bars, long[] barStart) {
        out.append("  \\new Lyrics \\with {\n");
        // DOWN for the reason every lane here is: read top to bottom the staff
        // affinities must not increase, and ChordNames above is already DOWN.
        out.append("    \\override VerticalAxisGroup.staff-affinity = #DOWN\n");
        out.append("    \\override VerticalAxisGroup"
                + ".nonstaff-nonstaff-spacing.basic-distance = #2\n");
        // Centred, unlike the syllables above, which are left-aligned to sit
        // under the chord name they belong to. A mark stands for a moment and
        // nothing else, and a moment is where its middle is.
        out.append("    \\override LyricText.self-alignment-X = #CENTER\n");
        // Small and grey: this is an annotation over the chart, and at the size
        // and weight of a chord name a page of them reads as the content.
        out.append("    \\override LyricText.font-size = #-4\n");
        out.append("    \\override LyricText.color = #(x11-color 'grey50)\n");
        out.append("  } \\lyricmode {\n");

        int at = 0;
        for (int i = 0; i < bars.size(); i++) {
            long to = barStart[i + 1];
            StringBuilder line = new StringBuilder();
            long cursor = barStart[i];
            while (at < marks.size() && marks.get(at).unit() < to) {
                Mark mark = marks.get(at);
                if (mark.unit() > cursor) {
                    line.append(ChartGrid.skip(mark.unit() - cursor)).append(' ');
                    cursor = mark.unit();
                }
                long until = at + 1 < marks.size()
                        ? Math.min(marks.get(at + 1).unit(), to) : to;
                // Quoted, because a digit terminates a syllable in this mode and
                // every mark here is one: bare 3 would be read as a duration.
                line.append('"').append(mark.text()).append('"')
                        .append(LilyPondDuration.scaled((until - cursor) * ChartGrid.UNIT))
                        .append(' ');
                cursor = until;
                at++;
            }
            if (cursor < to) {
                line.append(ChartGrid.skip(to - cursor)).append(' ');
            }
            // One bar to a line and a check closing each, so a duration that does
            // not sum names the bar it is in rather than the block.
            out.append("    ").append(line).append("|\n");
        }
        out.append("  }\n");
    }
}
