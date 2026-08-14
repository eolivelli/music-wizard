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
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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

    /** A tag per line, or none at all when the caller did not ask for them. */
    private static List<Optional<String>> tagsOf(List<String> lines, ChartOptions options) {
        return options.repeatTags() ? LineRepeats.tagsOf(lines)
                : lines.stream().map(line -> Optional.<String>empty()).toList();
    }

    /** Renders the chart as plain text. */
    public static String toText(Score score) {
        return toText(score, ChartOptions.defaults());
    }

    /** The same, with whatever the caller wants annotated over it. */
    public static String toText(Score score, ChartOptions options) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(options, "options");
        if (score.chords().isEmpty()) {
            return "(no chords were found)\n";
        }

        List<ChartLayout.Bar> bars = ChartLayout.of(score);
        StringBuilder out = new StringBuilder(header(score, bars));

        List<String> lines = linesOf(bars);
        List<Optional<String>> tags = tagsOf(lines, options);
        // Only when there is something to read: a legend for a notation the
        // chart does not use is a line of the header spent on nothing. Named
        // in seven characters, like the rows above it.
        if (tags.stream().anyMatch(Optional::isPresent)) {
            out.append("Tags   [A] marks lines that print identically\n");
        }
        out.append('\n');

        for (int i = 0; i < lines.size(); i++) {
            out.append(lines.get(i));
            // After the closing bar line, so a tag cannot be read as a chord in
            // the last bar, and so an untagged chart prints what it always did.
            tags.get(i).ifPresent(tag -> out.append("  [").append(tag).append(']'));
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * The rows every text output opens with: title, artist, tempo, meter, key.
     *
     * <p>Shared rather than written twice. The two text files are two views of
     * one score, and a reader holding them side by side must not find them
     * disagreeing about the tempo — least of all in a compound meter, where
     * {@link #tempoLine} and {@link #countedIn} between them decide a number
     * that is wrong by half if either is skipped.
     *
     * <p>Takes the bars because {@link #countedIn} answers from the chart's own
     * first bar rather than from the piece's meter, and the two differ whenever
     * the harmony starts after a meter change. A score with no harmony yields an
     * empty list and gets the piece's meter, which is the answer that case has
     * always had.
     */
    static String header(Score score, List<ChartLayout.Bar> bars) {
        StringBuilder out = new StringBuilder();
        score.title().ifPresent(title -> out.append(title).append('\n'));
        score.artist().ifPresent(artist -> out.append(artist).append('\n'));
        if (out.length() > 0) {
            out.append('\n');
        }
        TimeSignature meter = countedIn(score, bars);
        out.append(tempoLine(score, meter, bars));
        out.append("Meter  ").append(meter).append(more(meterChanges(bars))).append('\n');
        score.primaryKey().ifPresent(key -> out.append(keyLine(key)));
        return out.toString();
    }

    /**
     * How many times the chart's own bars change meter.
     *
     * <p>Read off the bars rather than off the piece's {@code meterChanges}: a
     * chart beginning after a change, or ending before one, must not report it.
     * That is the same rule {@link #countedIn} applies to
     * the meter it names, and it is #191 -- the header stated one meter where
     * the engraving of the same score restates {@code \time} wherever a change
     * falls, so the two disagreed with no cue in the text that anything had
     * changed.
     */
    private static int meterChanges(List<ChartLayout.Bar> bars) {
        int changes = 0;
        for (int i = 1; i < bars.size(); i++) {
            changes += bars.get(i).meterChanged() ? 1 : 0;
        }
        return changes;
    }

    /**
     * What a header row adds when the value it names does not hold throughout.
     *
     * <p>Worded as {@code AnalyzeCommand}'s declared block words it, deliberately
     * and by hand: {@code mw-notation} cannot depend on {@code mw-cli}, and a
     * reader holding the chart beside the analysis summary should not find one
     * piece of news reported in two idioms.
     *
     * <p>A count rather than a list of the changes and where they fall. The list
     * is what a reader would rather have and it grows without bound on a piece
     * that changes often, which is why #191 offered it as one option of three;
     * the page already carries every meter change at the bar it falls on, so
     * what the text chart owes the reader is that there is more to know.
     */
    private static String more(int changes) {
        return changes == 0
                ? ""
                : " at the start, changed " + changes + (changes == 1 ? " time" : " times")
                        + " later";
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
     * counted differently. A chart holding several meters says so beside this
     * one; see {@link #meterChanges}.
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
     *
     * <p><b>A piece that states more than one tempo is headed with the one the
     * chart opens on, and told if it changes within the chart.</b> That is #66,
     * and which figure that is belongs to {@link TempoMark#headline} rather than
     * here: the engraved chart carries the same number as a metronome mark, and
     * the two lines a reader takes off the text file are the ones the page has
     * to agree with. Both ask at {@link #opensAt}, and the count runs from there
     * to the last bar's end, exactly as {@link #meterChanges} runs over the
     * chart's own bars: a chart that ends before a change does not hold it any
     * more than one beginning after it does. The seconds route's spacing asks
     * one moment earlier and cannot ask this one; {@link
     * ChartLayout#quarterNoteSeconds} says why the two agree wherever it
     * matters.
     */
    private static String tempoLine(Score score, TimeSignature meter,
                                    List<ChartLayout.Bar> bars) {
        double opensAt = opensAt(bars);
        double endsAt = bars.isEmpty() ? 0 : bars.get(bars.size() - 1).endSeconds();
        return "Tempo  " + tempo(TempoMark.headline(score, opensAt), meter)
                + more(TempoMark.statedChangesIn(score, opensAt, endsAt)) + "\n";
    }

    /**
     * Where the chart's first bar line falls, in seconds, or the start of the
     * piece when it has no bars.
     *
     * <p>Both ends of what the tempo row describes are the page's own -- this
     * and the last bar's end -- for the same reason {@link #meterChanges} reads
     * the chart's bars: a row in this header is a claim about the bars under it.
     * A chart whose first bar holds a tempo change has to say so, and taking the
     * left edge from the harmony instead lost exactly that, since the first bar
     * can open a bar before the first chord does.
     */
    private static double opensAt(List<ChartLayout.Bar> bars) {
        return bars.isEmpty() ? 0 : bars.get(0).startSeconds();
    }

    /** One tempo, in the beat {@code meter} is counted in. */
    private static String tempo(double quarterBpm, TimeSignature meter) {
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
            return String.format(Locale.ROOT, "%.0f BPM", quarterBpm);
        }
        return String.format(Locale.ROOT, "%.0f BPM (%.0f quarter notes/min)",
                meter.countedTempo(quarterBpm), quarterBpm);
    }

    /**
     * The key, with how much the pipeline trusts it.
     *
     * <p>Qualified rather than stated flat, because on the audio path this row
     * is an estimate whose failure mode is invisible: a key and its relative
     * minor share every note, so a wrong answer reads exactly as well as a right
     * one and only the number distinguishes them.
     */
    private static String keyLine(Key key) {
        return String.format(Locale.ROOT, "Key    %s (%.0f%% confidence)\n",
                key.displayName(), 100 * key.confidence().value());
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
     *
     * <p>It also carries the bars, which the text chart has always drawn and
     * the page did not: see the {@code ChordNames} context settings in {@link
     * #lilyPondOf}.
     */
    public static String toLilyPond(Score score) {
        return toLilyPond(score, ChartOptions.defaults());
    }

    /** The same, with whatever the caller wants annotated over it. */
    public static String toLilyPond(Score score, ChartOptions options) {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(options, "options");
        return lilyPondOf(score, ChartLayout.of(score), false, options);
    }

    /** The same, over a layout the caller has already taken. See {@link #linesOf}. */
    static String lilyPondOf(Score score, List<ChartLayout.Bar> bars) {
        return lilyPondOf(score, bars, false, ChartOptions.defaults());
    }

    /**
     * The same, optionally with the lyrics engraved beneath the chords.
     *
     * <p>Off for {@code chords.ly}, on for the lyric sheet's own. The chart and
     * the sheet are two engravings of one score for the same reason they are two
     * text files: a reader who wants the changes and their lengths is reading a
     * different thing from one who wants the words, and adding lyrics to the
     * chart would silently change what {@code chords.pdf} has always been.
     */
    static String lilyPondOf(Score score, List<ChartLayout.Bar> bars, boolean withLyrics,
                             ChartOptions options) {
        StringBuilder out = new StringBuilder();
        out.append("\\version \"2.24.0\"\n\n");
        out.append("\\header {\n");
        out.append("  title = \"").append(escape(score.title().orElse("Untitled"))).append("\"\n");
        score.artist().ifPresent(artist ->
                out.append("  composer = \"").append(escape(artist)).append("\"\n"));
        out.append("  tagline = ##f\n");
        out.append("}\n\n");

        out.append("\\score {\n");
        List<Optional<String>> tags = tagsOf(linesOf(bars), options);
        boolean tagged = tags.stream().anyMatch(Optional::isPresent);
        // The lyric block is built before anything is written, because whether
        // there is one decides that the score needs a parallel block at all.
        Optional<String> lyrics = withLyrics
                ? LyricEngraving.block(score, bars) : Optional.empty();
        Optional<String> beats = options.beatMarks()
                ? BeatMarks.block(score, bars) : Optional.empty();
        boolean parallel = tagged || lyrics.isPresent() || beats.isPresent();
        if (parallel) {
            out.append("  <<\n");
        }
        if (tagged) {
            repeatBrackets(out, bars, tags);
        }
        out.append(chordNamesBlock(score, bars, true));
        // After the chord names, not before: read top to bottom the staff
        // affinities must not increase, and every lane here points DOWN as the
        // chord names do. Equal affinities do not increase, which is what makes
        // any number of these legal under the chords.
        //
        // The marks before the words, which is the order a reader compares them
        // in: the chords, then where the beats fell, then what was sung.
        beats.ifPresent(out::append);
        lyrics.ifPresent(out::append);
        if (parallel) {
            out.append("  >>\n");
        }
        out.append("  \\layout { }\n");
        out.append("}\n");
        return out.toString();
    }

    /**
     * The {@code \new ChordNames} expression alone, indented two spaces.
     *
     * <p>Shared with {@link LeadSheet}, which places the same chord names over a
     * melody staff. The bars come in rather than being taken here, so that the
     * two engravings of one score cannot come to disagree about where a bar is
     * — which is the failure {@link ChartLayout}'s own javadoc is about.
     *
     * @param carriesMarks whether this context also carries the tempo mark and
     *     the closing bar line. True on a chart, where the chord names are the
     *     only context there is; false on a lead sheet, where the staff carries
     *     both and a second copy would engrave the tempo twice.
     */
    static String chordNamesBlock(Score score, List<ChartLayout.Bar> bars,
                                  boolean carriesMarks) {
        return chordNamesBlock(score, bars, carriesMarks, Optional.empty());
    }

    /**
     * The same, in a score whose first bar has been shortened to a pickup.
     *
     * <p>A {@code \partial} is a claim about the score's shared timing, not
     * about the staff that wrote it, so a chord context that still opened with a
     * full bar would be exactly one pickup ahead of the music from bar one
     * onwards — and LilyPond reports that as a failed bar check on the chord
     * names, which reads as a defect in the chart rather than a disagreement
     * between two layouts. The chart's own convention is the opposite one: it
     * writes bar zero full and leads in with a rest. Both are right for a page
     * that holds only one of them, and on a lead sheet the staff's convention is
     * the one the reader counts by, so the chord names give theirs up here.
     */
    static String chordNamesBlock(Score score, List<ChartLayout.Bar> bars,
                                  boolean carriesMarks,
                                  Optional<StaffNotation.Pickup> pickup) {
        StringBuilder out = new StringBuilder();
        // chordChanges, so a chord held across a bar line has its name printed
        // once. The bar checks below require every bar to be written out, and
        // without this the page would say "C C C" where the text chart says
        // "| C | % | % |" -- the same disagreement between the two outputs that
        // deciding the bars twice used to produce.
        //
        // Bar_engraver, because ChordNames is not given one: the | that closes
        // every bar below is only a check, and a check draws nothing. The page
        // was a continuous stream of chord names, so a bar holding "C G" could
        // not be told from two bars holding one chord each -- which is the one
        // thing a chart is read for (#217).
        //
        // bar-extent, because the engraver alone is not enough. A bar line is
        // drawn the height of its staff, and a ChordNames context has no staff,
        // so the lines engrave with an empty vertical extent: present in the
        // score, invisible on the page. LilyPond's own Lyrics context carries
        // the same override for the same reason, and 2 staff spaces either side
        // of the chord names is the value its manual uses for ChordNames.
        //
        // Bar lines cost one thing, and it is #225: LilyPond may break a system
        // only where there is one, so a bar wider than the line no longer wraps
        // -- it runs past the margin and then off the sheet, silently. It takes
        // far more chords in one bar than any real recording tried has produced,
        // and how many depends on how wide their names are, but the bar holding
        // the lead-in is exempt from the harmonic-rhythm reduction and so is not
        // bounded by the meter. ChordChartEngravingIT reads each system's right
        // edge back out of LilyPond, since LilyPond itself says nothing about
        // it.
        out.append("  \\new ChordNames \\with {\n");
        out.append("    chordChanges = ##t\n");
        out.append("    \\consists \"Bar_engraver\"\n");
        out.append("    \\override BarLine.bar-extent = #'(-2 . 2)\n");
        out.append("  } {\n");
        // Outside \chordmode, which is where a mark belongs that is not a chord:
        // inside it, every line of the block is a bar whose durations have to
        // sum to the meter, and this one has no duration at all.
        if (carriesMarks) {
            TempoMark.of(score, countedIn(score, bars), opensAt(bars))
                    .ifPresent(mark -> out.append("    ").append(mark.lilyPond()).append('\n'));
        }
        out.append("    \\chordmode {\n");

        for (int index = 0; index < bars.size(); index++) {
            ChartLayout.Bar bar = bars.get(index);
            if (bar.meterChanged()) {
                out.append("      ").append(LilyPondMeter.time(bar.meter())).append('\n');
            }
            out.append("      ");
            List<String> written = index == 0 && pickup.isPresent()
                    ? intoPickup(bar, pickup.get())
                    : bar.cells().stream().map(ChordChart::chordMode).toList();
            for (String token : written) {
                out.append(token).append(' ');
            }
            // One bar to a line, so that when LilyPond does complain the line
            // number it prints names the bar that does not add up.
            out.append("|\n");
        }
        out.append("    }\n");
        // Outside \chordmode for the same reason the tempo mark is: it has no
        // duration, and every line inside that block is a bar that has to sum to
        // the meter. Written at all so the chart ends the way the staff parts
        // do -- StaffNotation closes every part with the same mark, and a reader
        // handed both should not have to wonder whether the chart's last page
        // is the last page. Skipped when there are no bars, because a final bar
        // line after no bars marks the end of nothing.
        if (carriesMarks && !bars.isEmpty()) {
            out.append("    \\bar \"|.\"\n");
        }
        out.append("  }\n");
        return out.toString();
    }

    /**
     * The last of a bar's chords, filling exactly the staff's pickup.
     *
     * <p>Taken from the end rather than by dropping the chart's lead-in rest,
     * because the two quantities are not the same one: the chart leads in to its
     * first <em>chord</em> and the staff's pickup runs to its first
     * <em>note</em>, and a melody entering before the harmony makes the rest
     * longer than the pickup needs. Cutting from the end gives a first bar of
     * the right length either way, and a chord that was already sounding keeps
     * its name — shortened, which is what a chord partly outside the printed
     * music is.
     *
     * <p>Counted in exact subdivisions of a whole note rather than in beats,
     * and this is the whole reason the pickup travels as a fraction. A pickup
     * entering inside a triplet is a sixth of a whole note; subtract that as a
     * double and every remaining chord is a length no duration can write, which
     * {@link LilyPondDuration} refuses by throwing — so {@code render} exited
     * non-zero and wrote nothing at all, chart included, on the packages whose
     * melody happens to enter inside a bracket.
     */
    private static List<String> intoPickup(ChartLayout.Bar bar, StaffNotation.Pickup pickup) {
        long units = leastCommonMultiple(LilyPondDuration.SHORTEST_DENOMINATOR,
                pickup.wholeNoteDenominator());
        long barUnits = Math.round(bar.meter().quarterBeatsPerBar() * units / QUARTERS_PER_WHOLE);
        long dropped = barUnits
                - pickup.wholeNoteNumerator() * (units / pickup.wholeNoteDenominator());
        List<String> written = new ArrayList<>();
        long at = 0;
        for (ChartLayout.Cell cell : bar.cells()) {
            long end = at + Math.round(cell.lengthQuarters() * units / QUARTERS_PER_WHOLE);
            if (end > dropped) {
                written.add(chordMode(cell.chord(),
                        LilyPondDuration.scaled(end - Math.max(at, dropped), units)));
            }
            at = end;
        }
        return written;
    }

    /** Quarter notes to a whole note, as the subdivision arithmetic needs it. */
    private static final double QUARTERS_PER_WHOLE = 4;

    private static long leastCommonMultiple(long a, long b) {
        return a / greatestCommonDivisor(a, b) * b;
    }

    private static long greatestCommonDivisor(long a, long b) {
        return b == 0 ? a : greatestCommonDivisor(b, a % b);
    }

    /**
     * A bracket over each line the chart prints more than once, labelled with
     * {@link LineRepeats}' tag for it.
     *
     * <p>A bracket rather than a {@code \mark}: a mark is a rehearsal mark and
     * names a point, and a bracket states both of its ends, which is as much as
     * one repeated line supports. See {@link LineRepeats} for why that
     * difference is the whole of #218. The bracket runs from the line's first
     * chord to its last, so two adjacent tagged lines read as two brackets
     * rather than one.
     *
     * <p>It rides in a context of its own beside the chord names, and the
     * reason is the bar lines: {@code \startTextSpan} is a post-event, so
     * inside {@code \chordmode} it would have to be written against a chord, on
     * the line the bar check closes. Those lines are read back as the chart's
     * bars -- by {@code tools/score-chart.py}, which scores what the chart
     * prints, and by {@code ChordChartEngravingIT}, which counts them against
     * the bar lines LilyPond drew. Here nothing but chords is ever written on
     * one. {@code Dynamics} is the context LilyPond provides for exactly this:
     * a lane of spanners and markup with no notes of its own.
     *
     * <p>The spacers mirror the chord block cell for cell, from the same cell
     * list and through the same {@link LilyPondDuration#scaled} call, so that a
     * bracket ends on the moment its line's last chord starts. That is two
     * loops agreeing rather than one derivation, which is the shape {@link
     * ChartLayout}'s own javadoc is about, so a test compares the two token for
     * token. A tagged line always holds at least
     * two of them -- a line short of {@link #BARS_PER_LINE} bars prints fewer
     * bar lines, so it is never character-equal to a full one and never tagged,
     * and every bar holds at least one cell -- so no bracket is ever asked to
     * begin and end on one moment, which LilyPond would refuse.
     */
    private static void repeatBrackets(StringBuilder out, List<ChartLayout.Bar> bars,
            List<Optional<String>> tags) {
        out.append("  \\new Dynamics \\with {\n");
        out.append("    \\override TextSpanner.style = #'line\n");
        out.append("    \\override TextSpanner.bound-details.left.stencil-align-dir-y = #CENTER\n");
        out.append("    \\override TextSpanner.bound-details.right.text ="
                + " \\markup { \\draw-line #'(0 . -1) }\n");
        // The two ends a broken bracket grows, which are not ends of anything.
        // LilyPond may break a system anywhere in the chart, and the four-bar
        // line has no say in where -- so a bracket is routinely drawn in two
        // pieces. Left to inherit the settings above, each piece takes the
        // label and the closing hook and reads as a whole bracket over part of
        // a line, which is the one thing this annotation must not say. Silent:
        // LilyPond has nothing to warn about.
        out.append("    \\override TextSpanner.bound-details.left-broken.text = ##f\n");
        out.append("    \\override TextSpanner.bound-details.right-broken.text = ##f\n");
        out.append("    \\override VerticalAxisGroup.staff-affinity = #DOWN\n");
        out.append("  } {\n");
        for (int line = 0; line < tags.size(); line++) {
            int first = line * BARS_PER_LINE;
            int last = Math.min(first + BARS_PER_LINE, bars.size()) - 1;
            Optional<String> tag = tags.get(line);
            if (tag.isPresent()) {
                out.append("      \\once \\override TextSpanner.bound-details.left.text ="
                                + " \\markup { \\bold \"")
                        .append(tag.orElseThrow())
                        .append("\" \\draw-line #'(0 . -1) }\n");
            }
            for (int i = first; i <= last; i++) {
                List<ChartLayout.Cell> cells = bars.get(i).cells();
                out.append("     ");
                for (int c = 0; c < cells.size(); c++) {
                    out.append(" s").append(LilyPondDuration.scaled(cells.get(c).lengthQuarters()));
                    if (tag.isPresent() && i == first && c == 0) {
                        out.append("\\startTextSpan");
                    }
                    if (tag.isPresent() && i == last && c == cells.size() - 1) {
                        out.append("\\stopTextSpan");
                    }
                }
                out.append('\n');
            }
        }
        out.append("  }\n");
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
        return chordMode(cell.chord(), LilyPondDuration.scaled(cell.lengthQuarters()));
    }

    /** The same, for a length already spelled — see {@link #intoPickup}. */
    private static String chordMode(Optional<Chord> named, String duration) {
        if (named.isEmpty()) {
            return "r" + duration;
        }
        Chord chord = named.get();
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
