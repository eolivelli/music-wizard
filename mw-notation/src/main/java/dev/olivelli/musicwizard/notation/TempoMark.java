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

import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.Locale;
import java.util.Optional;

/**
 * The one metronome mark a score is engraved with: how fast, in the beat the
 * reader counts.
 *
 * <p>Three emitters print it — {@link ChordChart} on a chart,
 * {@link StaffNotation} on a staff, and {@link MusicXmlExport} for whatever
 * opens the file next — and they get it from here rather than each deciding for
 * itself, because every part of the decision has already been got wrong once on
 * this project. The beat unit is the one #4 is about: the map stores quarter
 * notes per minute, so an unqualified figure in 6/8 is a metronome marking 50%
 * fast. The figure itself has to be {@link Score#estimatedTempo()}, which is
 * what the text chart and the {@code analyze} summary print, or the page
 * contradicts the two lines the user read before looking at it. And how much to
 * trust it is {@link #ESTIMATE}, which round 1 of review found stated two ways
 * in two goldens of one fixture.
 *
 * <p>How each emitter reaches it differs, and only the decision is shared. The
 * chart asks {@link #of} directly. A staff goes through {@link StaffLayout},
 * which asks {@link #of} and publishes the unit and the count through
 * {@link StaffWriter#tempo}; {@link StaffNotation} then formats them with
 * {@link #lilyPond()} and {@link MusicXmlExport} spells them as
 * {@code <metronome>} beside {@link #ESTIMATE}.
 *
 * <p><b>Marked as an estimate, always.</b> The mark reads {@code ca. (♩ = 159)}
 * rather than {@code ♩ = 159}, because one figure for a whole piece is an
 * estimate even when every tempo in the map was stated: a file that changes
 * tempo states no single number, and {@link Score#estimatedTempo()} answers with
 * a duration-weighted average nothing declared. On the input this project exists
 * for — a recording — it is an estimate outright, from the least reliable stage
 * in the pipeline. The one case the qualifier understates is a constant tempo
 * read from a MIDI file or typed at {@code --tempo}, and that is the trade taken
 * deliberately: printing an estimate as exact is the error CLAUDE.md calls a
 * defect, and printing an exact figure as approximate is a page a musician can
 * still play.
 *
 * @param unit      the note value the tempo counts, dotted in a compound meter
 * @param perMinute how many of them a minute holds
 */
record TempoMark(NoteValue unit, long perMinute) {

    /**
     * The word that says the figure is an estimate, in the one place both
     * spellings of the mark read it from.
     *
     * <p>Three emitters print this mark -- LilyPond twice and MusicXML once --
     * and a constant is what stops two of them agreeing on the number while
     * disagreeing about how much to trust it. Round 1 of review found exactly
     * that: the qualifier had reached {@link StaffNotation} and not
     * {@link MusicXmlExport}, so one fixture's two goldens stated the same
     * tempo with two different confidences.
     *
     * <p>"ca." rather than "≈" or "approx.": it is the abbreviation engraving
     * already uses for this, and unlike "≈" it cannot be misread as "=" at
     * print size.
     */
    static final String ESTIMATE = "ca.";

    /**
     * The mark for a score, or empty when it has no tempo worth printing.
     *
     * <p>Empty in two cases, and both are silence rather than a guess: a meter
     * whose counted beat is not a single note value has no note head to hang the
     * mark on, and a tempo below one beat a minute is not a tempo a page can
     * carry. Neither is reachable from a score the pipeline builds today — every
     * meter it produces is counted in a quarter or a dotted quarter — so this is
     * a guard rather than a branch with a fixture behind it.
     *
     * @param meter the meter the mark is counted in, which is the one the
     *              engraving opens in rather than the piece's, since those
     *              differ after a meter change and it is the opening bar the
     *              mark sits over
     */
    static Optional<TempoMark> of(Score score, TimeSignature meter) {
        Optional<NoteValue> unit = LilyPondDuration.valueOf(meter.beatUnitQuarters());
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        double counted = meter.countedTempo(score.estimatedTempo());
        if (!Double.isFinite(counted) || counted < 1) {
            return Optional.empty();
        }
        return Optional.of(new TempoMark(unit.get(), Math.round(counted)));
    }

    /**
     * The mark as LilyPond, without indentation or a trailing newline.
     *
     * <p>{@code \tempo <markup> <unit> = <count>} rather than a markup carrying
     * its own note glyph, which is the other way to write "about this fast".
     * LilyPond draws the note head itself in this form, and — measured on 2.26 —
     * it is also the only one of the two that reaches MIDI: a source whose mark
     * is pure markup engraves identically and exports at 60 BPM, so a
     * {@code \midi} block added to one of our files would play at a tempo
     * nothing in the score mentions.
     */
    String lilyPond() {
        // Locale.ROOT because the digits are the hazard, not a decimal
        // separator: the count is a long and %d never writes one. What a
        // default locale does write is its own numbering system, so this emits
        // Arabic-Indic digits under ar-EG and hands LilyPond a count it cannot
        // parse. The text chart's tempo line has the same hazard for the same
        // reason -- its %.0f prints no fractional part either. A decimal comma
        // is a third way to lose such a number and is reachable only where a
        // fractional digit is printed, which is AnalyzeCommand's %.1f.
        return String.format(Locale.ROOT, "\\tempo \\markup { \\italic \"%s\" } %s = %d",
                ESTIMATE, unit.lilyPondToken(), perMinute);
    }
}
