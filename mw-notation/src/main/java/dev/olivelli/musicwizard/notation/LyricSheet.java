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
 * <p><b>No bar lines and no beat axis.</b> Placement here is by time and column,
 * which is all the format has ever offered: the convention has no grammar and
 * never had one, and the only thing ever written down about it is that the file
 * is monospaced. That is a feature for this stage — a syllable needs the note it
 * is sung on before it can go on a beat (#150), and nothing here pretends
 * otherwise. Engraving, where a syllable does have to land on a bar line, is
 * #309.
 */
public final class LyricSheet {

    /**
     * How wide a chord row with no words under it may run.
     *
     * <p>Seventy-five columns and a monospaced font is the whole of what the
     * chords-over-lyrics convention ever had written down — the 1997 archive
     * submission rules that named the {@code .crd} file. The geometry was never
     * specified anywhere, but the width was, so it is worth keeping.
     */
    private static final int BREAK_COLUMNS = 75;

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
     * Prints the chords falling before a moment as rows with no words under
     * them, and returns the index of the first chord after them.
     *
     * <p>Wrapped, unlike a chord row over a line, whose width the words it sits
     * above already bound. Nothing bounds this one: an instrumental stretch — or
     * a lyric file that covers only the first verse, which is the ordinary case
     * while lyrics are supplied by hand — leaves every remaining change with
     * nowhere else to go. On a real recording that ran to hundreds of symbols on
     * one line. The width is the one thing the chords-over-lyrics convention has
     * ever actually specified: monospaced, and no wider than a terminal.
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
                if (row.length() + 1 + symbol.length() > BREAK_COLUMNS) {
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
     */
    private static String chordRow(List<Placed> chords, Laid laid) {
        StringBuilder row = new StringBuilder();
        for (Placed chord : chords) {
            int at = laid.columnAt(chord.seconds());
            if (row.length() > 0) {
                at = Math.max(at, row.length() + 1);
            }
            while (row.length() < at) {
                row.append(' ');
            }
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
            columns[i] = text.length();
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
