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
import dev.olivelli.musicwizard.core.model.LyricLine;
import dev.olivelli.musicwizard.core.model.LyricWord;
import dev.olivelli.musicwizard.core.model.Score;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The chords-and-lyrics sheet: chord symbols on a line of their own, each one
 * standing over the word it falls on.
 *
 * <p>This is a different layout from {@link ChordChart}, not the same one with
 * text added, and the two exist side by side because they answer different
 * questions. A bar grid tells a player how long each chord lasts and is what to
 * read when there is nothing to sing; a chords-over-lyrics sheet says where in
 * the words each change arrives and is what to read when there is. Interleaving
 * them would give a file that is worse at both — and would break every reader of
 * {@code chords.txt}, which includes the measurement harness.
 *
 * <p><b>The text sheet has no bar lines and no beat axis</b>: placement in
 * {@link #toText} is by time and column. That is also why it does not apply the
 * bar-level reduction the chart does — {@code ChartLayout} decides how many
 * chords a <em>bar</em> can hold, and there are no bars there. The text sheet
 * prints every change; the chart prints what fits. {@link #toLilyPond} does have
 * bars, since a page needs them, and that difference is why the two can disagree
 * about which words appear. Syllables stay in seconds either way: putting one on
 * a beat needs the note it is sung on, which is #150.
 */
public final class LyricSheet {

    /**
     * How wide a chord row with no words beneath it may run before it wraps.
     *
     * <p>Seventy-five columns and a monospaced font is the whole of what the
     * chords-over-lyrics convention ever had written down — the 1997 archive
     * submission rules that named the {@code .crd} file. Where a symbol sits
     * was never specified anywhere; how wide the page is, was.
     */
    private static final int ROW_COLUMNS = 75;

    private LyricSheet() {
    }

    /**
     * Renders the sheet.
     *
     * <p>Callers check {@link Score#lyrics()} first: a score with none produces
     * the same explanatory line rather than an empty file, so a sheet that
     * exists is always a sheet with something in it.
     */
    public static String toText(Score score) {
        Objects.requireNonNull(score, "score");
        if (score.lyrics().isEmpty()) {
            return "(no lyrics were found)\n";
        }

        StringBuilder out = new StringBuilder(
                ChordChart.header(score, ChartLayout.of(score)));
        out.append('\n');

        List<LyricLine> lines = score.lyrics().lines();
        List<Placed> chords = changes(score);
        int next = 0;

        // Chords sounding before anyone sings: an intro, printed on its own.
        next = appendChordsBefore(out, chords, next, lines.get(0).startSeconds());

        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            Laid laid = lay(line);
            // Everything up to the end of this line belongs over its words. Not
            // up to the next line's start: a chord arriving in the silence after
            // a line is an instrumental break and reads better on its own row
            // than jammed over the last syllable it did not accompany.
            int from = next;
            while (next < chords.size() && chords.get(next).seconds() < line.endSeconds()) {
                next++;
            }
            String row = chordRow(chords.subList(from, next), laid);
            if (!row.isEmpty()) {
                out.append(row).append('\n');
            }
            out.append(laid.text()).append('\n');

            double until = i + 1 < lines.size()
                    ? lines.get(i + 1).startSeconds() : Double.POSITIVE_INFINITY;
            int before = next;
            next = appendChordsBefore(out, chords, next, until);
            if (next > before && i + 1 < lines.size()) {
                // A break was printed, so the stanza it separates has ended.
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The sheet as LilyPond: the chord chart with the lyrics engraved under it.
     *
     * <p>A separate engraving from {@link ChordChart#toLilyPond}, not a flag on
     * it, for the reason the text files are separate — the chart is read for the
     * changes and their lengths, the sheet for where the words fall, and putting
     * lyrics on the chart would change what {@code chords.pdf} has always been.
     *
     * <p>The two sheets can differ about which words appear and where: this one
     * hangs syllables on bars, and a bar axis cannot carry everything a column
     * can. {@link LyricEngraving} is where that rule is derived and is the one
     * place it is stated.
     */
    public static String toLilyPond(Score score) {
        return toLilyPond(score, ChartOptions.defaults());
    }

    /** The same, with whatever the caller wants annotated over it. */
    public static String toLilyPond(Score score, ChartOptions options) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(options, "options");
        return ChordChart.lilyPondOf(score, ChartLayout.of(score), true, options);
    }

    /**
     * Prints the chords falling before a moment as rows with no words under
     * them, and returns the index of the first chord after them.
     *
     * <p>Wrapped at {@link #ROW_COLUMNS}. Nothing else bounds it: an
     * instrumental stretch, or the tail of a song whose supplied lyrics cover
     * only the first verse, leaves every remaining change with nowhere else to
     * go.
     */
    private static int appendChordsBefore(StringBuilder out, List<Placed> chords, int from,
                                          double untilSeconds) {
        int next = from;
        while (next < chords.size() && chords.get(next).seconds() < untilSeconds) {
            next++;
        }
        if (next == from) {
            return next;
        }
        StringBuilder row = new StringBuilder();
        for (int i = from; i < next; i++) {
            String symbol = chords.get(i).symbol();
            if (row.length() > 0) {
                if (DisplayWidth.of(row.toString()) + 1 + DisplayWidth.of(symbol) > ROW_COLUMNS) {
                    out.append(row).append('\n');
                    row.setLength(0);
                } else {
                    row.append(' ');
                }
            }
            row.append(symbol);
        }
        out.append(row).append('\n');
        return next;
    }

    /**
     * The chord row for one lyric line, each symbol at the column of the word it
     * arrives on.
     *
     * <p>A symbol that would overlap the one before it is pushed right instead,
     * keeping one space between them. That loses the exact column, and losing it
     * is correct: two chord symbols cannot occupy one column, and a reader can
     * see that two changes fall close together whereas a truncated symbol tells
     * them nothing at all.
     *
     * <p>The row is <b>never wrapped</b>, so it can run wider than the page when
     * a line holds more changes than it has letters. Wrapping moves a symbol to
     * column zero of a new row, and column zero is the line's <em>first</em>
     * word, so whatever test decides when to wrap, the symbol it moves lands over
     * a word it did not accompany. Guarding on where the symbol came from does
     * not help, because the hazard is where it goes. A wide row is ugly; a chord
     * over the wrong word is wrong, and placement is the whole of what this file
     * offers over {@link ChordChart}.
     *
     * <p>A row with no words beneath it has no such column to collide with, and
     * {@link #appendChordsBefore} does wrap.
     *
     * @return the row, or empty when there are no chords
     */
    private static String chordRow(List<Placed> chords, Laid laid) {
        StringBuilder row = new StringBuilder();
        for (Placed chord : chords) {
            int column = laid.columnAt(chord.seconds());
            int width = DisplayWidth.of(row.toString());
            int at = row.isEmpty() ? column : Math.max(column, width + 1);
            DisplayWidth.padTo(row, at);
            row.append(chord.symbol());
        }
        return row.toString();
    }

    /**
     * One lyric line as printed, with the column each word starts at.
     *
     * <p>Built in one pass with the text so the two cannot drift: a column read
     * off a separately reconstructed string is the same two-readers defect this
     * project keeps paying for, one layer along.
     */
    private record Laid(String text, double[] starts, int[] columns) {

        /** The column of the last word that had begun by this moment. */
        int columnAt(double seconds) {
            int found = 0;
            for (int i = 0; i < starts.length; i++) {
                if (starts[i] <= seconds) {
                    found = i;
                } else {
                    break;
                }
            }
            return columns[found];
        }
    }

    private static Laid lay(LyricLine line) {
        List<LyricWord> words = line.words();
        StringBuilder text = new StringBuilder();
        double[] starts = new double[words.size()];
        int[] columns = new int[words.size()];
        for (int i = 0; i < words.size(); i++) {
            LyricWord word = words.get(i);
            starts[i] = word.startSeconds();
            columns[i] = DisplayWidth.of(text.toString());
            text.append(word.text());
            // The same rule LyricLine.text() applies: a hyphenated syllable joins
            // the next one without a space, so a word split for engraving still
            // reads as one word here.
            if (i < words.size() - 1 && !word.hyphenatedToNext()) {
                text.append(' ');
            }
        }
        return new Laid(text.toString(), starts, columns);
    }

    /** A chord symbol and when it arrives. */
    private record Placed(double seconds, String symbol) {
    }

    /**
     * The chord changes, in order, with runs of one symbol collapsed to the
     * first of them.
     *
     * <p>Collapsing is not cosmetic here. Chord estimation returns a span per
     * beat-ish region rather than per change, so a real recording yields
     * hundreds of them and a sheet printing each would be unreadable — the same
     * reason the text chart writes {@code %} and the page sets
     * {@code chordChanges}. This is a third copy of that rule and it has to be,
     * because the other two work in bars and this one works in columns; what
     * they share is the decision, not the arithmetic.
     */
    private static List<Placed> changes(Score score) {
        List<Placed> placed = new ArrayList<>();
        String previous = null;
        for (Chord chord : score.chords().chords()) {
            String symbol = chord.symbol();
            if (!symbol.equals(previous)) {
                placed.add(new Placed(chord.startSeconds(), symbol));
                previous = symbol;
            }
        }
        return placed;
    }
}
