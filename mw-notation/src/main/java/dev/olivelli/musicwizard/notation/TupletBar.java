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

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.Optional;

/**
 * One bar the quantizer divided into tuplets, in the terms the emitter writes
 * in.
 *
 * <p>Built from a {@link BarGrid} and nothing else. The tuplet ratio is
 * <em>read</em> from the grid rather than guessed at from the note lengths that
 * came out of it, because it cannot be guessed at: three onsets a third of a
 * beat apart and three a half beat apart are both legal on the sixth-of-a-beat
 * grid, and only one of them is a triplet. See #92.
 *
 * <h2>Two units, and the arithmetic between them</h2>
 *
 * <p>A tuplet has a <b>sounding</b> length and a <b>written</b> one, and
 * LilyPond wants the written one: {@code \tuplet 3/2 { c8 c8 c8 }} draws three
 * eighths — a beat and a half as written — and plays them in one beat, because
 * the bracket scales what is inside it by {@code normal/actual}. So a span of
 * {@code n} grid steps is printed at {@code n *} {@link #writtenStep()}, and the
 * emitter never writes a sounding length inside a bracket.
 *
 * <p>One bracket holds {@link #stepsPerBracket()} steps, which is the ratio's
 * {@code actual}: three for a triplet in simple time, two for a duplet in
 * compound. That makes a triplet-sixteenth bar two brackets to the beat rather
 * than one of six, which is how the rhythm is conventionally printed, and it
 * follows from the ratio rather than being a second decision — {@link
 * GridResolution} says the ratio is 3/2 at every depth precisely because a
 * sixteenth triplet is three sixteenths in the time of two.
 *
 * <h2>Why the positions are exact</h2>
 *
 * <p>A grid step of a third of a beat is not a representable double, so a
 * position built by adding steps together is not the position built by
 * multiplying: {@code (8 + 1/3) + 1/3} and {@code 8 + 2 * (1/3)} differ in the
 * last bit, and a note's offset is the first while its bar's grid is the
 * second. The emitter therefore works in whole step counts — {@link #stepAt} in,
 * {@link #beatOfStep} out — and every position it compares is rebuilt the same
 * way from the same integer.
 *
 * <p>Bracket boundaries are built from {@link #bracketLength()} instead, which
 * is the counted beat divided by a power of two and so is exact. That is what
 * lets a stretch of untuplet-ed music inside a tuplet bar go to
 * {@link MetricSplitter}, which insists on the 64th-note grid: a bracket
 * boundary is always a whole number of 64ths, and a position that is not is
 * always inside a bracket.
 *
 * @param startBeat       the bar's downbeat in quarter-note beats
 * @param meter           the signature in force in this bar
 * @param stepQuarters    one grid step, sounding, in quarter-note beats
 * @param divisions       grid steps in the bar
 * @param stepsPerBracket steps under one bracket, the ratio's {@code actual}
 * @param bracketLength   one bracket's sounding length in quarter-note beats
 * @param writtenStep     one grid step as it is printed inside the bracket
 * @param normal          the ratio's {@code normal}: how many of the written
 *                        value the bracket is played in
 */
record TupletBar(double startBeat, TimeSignature meter, double stepQuarters, int divisions,
                 int stepsPerBracket, double bracketLength, double writtenStep, int normal) {

    /**
     * The bar as a tuplet bar, or empty when its grid is the meter's natural
     * subdivision and needs no bracket.
     *
     * <p>A bar that needs no bracket is deliberately not described here at all,
     * rather than described and then ignored. Everything this class touches is
     * new behaviour, so a bar it declines to build for is emitted by exactly the
     * code that emitted it before — including the snap onto the 64th-note grid,
     * which for such a bar is a no-op the quantizer has already performed.
     */
    static Optional<TupletBar> of(BarGrid grid) {
        Optional<GridResolution.Tuplet> tuplet = grid.tuplet();
        if (tuplet.isEmpty()) {
            return Optional.empty();
        }
        int actual = tuplet.get().actual();
        int normal = tuplet.get().normal();
        int divisionsPerBeat = grid.resolution().divisionsPerBeat();
        // Whole for every pair a ratio is returned for -- the counted beat,
        // whose divisions would not be, never gets one (#618): simple time
        // tuplets three or six ways against actual = 3, compound time two,
        // four or eight ways against actual = 2. TupletBarTest sweeps the
        // pairs.
        int bracketsPerBeat = divisionsPerBeat / actual;
        double bracketLength = grid.timeSignature().beatUnitQuarters() / bracketsPerBeat;
        double writtenStep = bracketLength / normal;
        for (int steps = 1; steps <= actual; steps++) {
            if (!LilyPondDuration.isSingleValue(steps * writtenStep)) {
                // A grid finer than the note values this writes. It takes an
                // extreme meter to get here -- a sixth of a beat in 1/32, or an
                // eighth of one in 6/32, where the printed value would be half a
                // 64th -- and such a bar is emitted the way it was before, which
                // is to say badly. Refusing it is not the same as engraving it
                // wrongly on purpose: the alternative is a bracket whose
                // contents have no duration to write. See #131.
                return Optional.empty();
            }
        }
        return Optional.of(new TupletBar(grid.startBeat(), grid.timeSignature(),
                grid.stepQuarters(), grid.divisions(), actual, bracketLength,
                writtenStep, normal));
    }

    /**
     * The ratio as LilyPond spells it, e.g. {@code 3/2}.
     *
     * <p>Derived from the two numbers rather than carried as a third field, so
     * that a bracket's printed ratio and the {@code actual}/{@code normal} pair
     * MusicXML wants cannot say different things.
     */
    String ratio() {
        return stepsPerBracket + "/" + normal;
    }

    /** The next bar's downbeat in quarter-note beats. */
    double endBeat() {
        return startBeat + meter.quarterBeatsPerBar();
    }

    /** Brackets in the bar. */
    int brackets() {
        return divisions / stepsPerBracket;
    }

    /**
     * Which grid step a position on the beat axis is, counted from the bar line.
     *
     * <p>Rounded rather than trusted. The positions reaching here were built by
     * the quantizer, which is to say by adding a length to an onset, and on a
     * triplet grid that lands a few ulps off the position the same grid would
     * have produced by multiplication.
     */
    int stepAt(double beat) {
        return (int) Math.clamp(Math.rint((beat - startBeat) / stepQuarters), 0, divisions);
    }

    /**
     * Where a grid step falls on the beat axis.
     *
     * <p>Built through the bracket it belongs to, so that a step on a bracket
     * boundary comes out as the exact dyadic value {@link #bracketStart} gives
     * and not as a third of a beat multiplied back up. The bar's own end is
     * taken from the meter for the same reason.
     */
    double beatOfStep(int step) {
        int bracket = step / stepsPerBracket;
        int within = step - bracket * stepsPerBracket;
        return within == 0 ? bracketStart(bracket) : bracketStart(bracket) + within * stepQuarters;
    }

    /** Where a bracket begins on the beat axis; the last one ends at the bar line. */
    double bracketStart(int bracket) {
        return bracket == brackets() ? endBeat() : startBeat + bracket * bracketLength;
    }

    /** Which bracket a grid step belongs to. */
    int bracketOf(int step) {
        return step / stepsPerBracket;
    }

    /**
     * How long a run of grid steps is printed as, inside the bracket.
     *
     * <p>Always a single note value. A bracket is printed only where a note
     * boundary falls strictly inside it, so a run inside a printed bracket is
     * shorter than the bracket: one or two steps of a triplet, one of a duplet.
     * One step is the value itself and two are the value above it.
     *
     * <p>The shortest step reaching here is a 64th exactly, from
     * {@code SIXTH_BEAT} in a meter over sixteen — the denominator-64 meters
     * that would produce a 256th are refused by {@link #of} above.
     * {@code TupletBarTest} checks the conclusion over every meter and grid.
     */
    double writtenLength(int steps) {
        return steps * writtenStep;
    }

    /**
     * The distance from a grid step to the bar line, as an exact fraction of a
     * whole note.
     *
     * <p>What {@code \partial} needs, and the one length in a tuplet bar that
     * cannot be written as a note value plus a count of 64ths: a pickup entering
     * two triplet eighths before the bar line is two thirds of a quarter, and
     * {@link LilyPondDuration#wholeNoteFraction} refuses it — correctly, since
     * no whole number of 64ths is that long.
     *
     * <p>Computed as an exact fraction of a whole note rather than from the
     * position, because the position is a third of a beat and the fraction is
     * not: a bar holds {@code numerator} of its denominator units and
     * {@link #divisions} grid steps, so a run of {@code n} steps is
     * {@code n * numerator} over {@code denominator * divisions} whole notes,
     * with no division performed anywhere.
     *
     * <p>A fraction rather than a formatted duration because a second emitter
     * now asks the same question and wants a different answer written: MusicXML
     * marks a short opening bar rather than naming its length. The arithmetic is
     * the part that is easy to get wrong, so it is the part that is shared.
     *
     * @return {@code {numerator, denominator}} in whole notes
     */
    long[] lengthToBarLine(int step) {
        return new long[] {(long) (divisions - step) * meter.numerator(),
                (long) meter.denominator() * divisions};
    }
}
