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

package dev.olivelli.musicwizard.arrange;

import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.Objects;
import java.util.Optional;

/**
 * How finely one bar is divided when its notes are snapped to a grid.
 *
 * <p>A resolution counts divisions of the meter's <em>counted beat</em>, not of
 * the quarter note. In every x/4 meter the two are the same thing, which is why
 * the set reads as the familiar {@code 1, 1/2, 1/4, 1/8} plus the triplets
 * {@code 1/3, 1/6}. In compound time they are not: a 6/8 beat is a dotted
 * quarter, so {@link #THIRD_BEAT} is the plain eighth note and
 * {@link #HALF_BEAT} is a duplet. Defining the grid on the quarter instead
 * would be the compound-meter mis-barring bug moved one layer up -- it makes
 * the natural subdivision of a 6/8 bar look like a tuplet, and a genuine duplet
 * unrepresentable.
 *
 * <p>Which divisions count as tuplets therefore depends on the meter, and so
 * does {@link #depthIn(TimeSignature)}, the printed shortness of the note value
 * the grid produces. Both are what a notation back end needs in order to decide
 * between {@code c8 c8 c8} and {@code \tuplet 3/2 { c8 c8 c8 }}.
 */
public enum GridResolution {

    /** One position per counted beat: a quarter in 4/4, a dotted quarter in 6/8. */
    BEAT(1),
    /** Two per beat: an eighth in 4/4, a duplet dotted eighth in 6/8. */
    HALF_BEAT(2),
    /** Three per beat: a triplet eighth in 4/4, the plain eighth in 6/8. */
    THIRD_BEAT(3),
    /** Four per beat: a sixteenth in 4/4, a duplet sixteenth in 6/8. */
    QUARTER_BEAT(4),
    /** Six per beat: a triplet sixteenth in 4/4, the plain sixteenth in 6/8. */
    SIXTH_BEAT(6),
    /** Eight per beat: a thirty-second in 4/4, and an oddity in compound time. */
    EIGHTH_BEAT(8);

    private final int divisionsPerBeat;

    GridResolution(int divisionsPerBeat) {
        this.divisionsPerBeat = divisionsPerBeat;
    }

    /** Grid positions in one counted beat. */
    public int divisionsPerBeat() {
        return divisionsPerBeat;
    }

    /**
     * Length of one grid step in quarter-note beats, the unit everything
     * downstream of the beat grid works in.
     */
    public double stepQuarters(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return timeSignature.beatUnitQuarters() / divisionsPerBeat;
    }

    /** Grid positions in one bar of the given meter. */
    public int divisionsPerBar(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return divisionsPerBeat * timeSignature.beatsPerBar();
    }

    /**
     * True when this division has to be written as a tuplet in this meter.
     *
     * <p>Simple time subdivides by two, so thirds and sixths are tuplets.
     * Compound time subdivides by three, so it is the halves, quarters and
     * eighths that are -- a 6/8 bar divided in two per beat is a duplet, which
     * is exactly the case a quarter-anchored grid cannot say out loud.
     */
    public boolean isTupletIn(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return timeSignature.isCompound() ? divisionsPerBeat % 3 != 0 : divisionsPerBeat % 3 == 0;
    }

    /**
     * The tuplet ratio to print, as (actual, normal): three in the time of two
     * for a triplet in simple time, two in the time of three for a duplet in
     * compound time. Empty when the division needs no bracket: the meter's
     * natural one, or the counted beat itself (#618).
     *
     * <p>The ratio is the same at every depth, because a sixteenth triplet is
     * three sixteenths in the time of two, not six in the time of four; the note
     * value the ratio applies to is {@link #depthIn(TimeSignature)}'s business.
     */
    public Optional<Tuplet> tupletIn(TimeSignature timeSignature) {
        if (!bracketedIn(timeSignature)) {
            return Optional.empty();
        }
        return Optional.of(timeSignature.isCompound() ? new Tuplet(2, 3) : new Tuplet(3, 2));
    }

    /**
     * Whether this grid divides the counted beat against the meter's own
     * subdivision — a run the page must bracket.
     *
     * <p>Not {@link #isTupletIn}: that also charges the quantizer's tuplet
     * penalty, and in compound time it reports {@code BEAT} as a duplet
     * because one is not a multiple of three (#130) — but one position per
     * counted beat divides nothing, a dotted quarter in 6/8 being the beat
     * itself. The exemption was a hand-written copy at every reader before it
     * was named here (#618); the penalty keeps reading {@code isTupletIn},
     * because changing that changes which grids the quantizer picks.
     */
    public boolean bracketedIn(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return this != BEAT && isTupletIn(timeSignature);
    }

    /**
     * How many beams the note value at this grid carries, counted down from
     * the meter's own beat: 0 for the counted beat itself, then 1, 2 and 3 for
     * each halving or thirding below it. In x/4 those are the quarter, eighth,
     * sixteenth and thirty-second; in 7/16 the beat is already a sixteenth, and
     * depth 1 is a thirty-second.
     *
     * <p>This is the quantity the complexity penalty is charged on, because it
     * is what a reader pays: shorter values are harder to read than longer ones,
     * independently of whether they are tuplets. A triplet eighth and a plain
     * eighth are both depth 1 and are separated by the tuplet surcharge instead.
     *
     * <p>The meter is taken and checked but does not change the answer, and it
     * is worth saying why rather than dropping the parameter, because the
     * arithmetic that makes it so is not obvious and would stop being true if
     * the set of resolutions grew. Compound time counts down its own ladder --
     * dotted quarter, eighth, sixteenth -- and every rung of it happens to carry
     * the same number of beams as the rung reached by the same number of
     * divisions in simple time. A third of a 6/8 beat is a plain eighth, one
     * beam, exactly as half of a 4/4 beat is; half of a 6/8 beat is a
     * <em>dotted</em> eighth, still one beam. What differs between the meters is
     * which of those is a tuplet, and that is {@link #isTupletIn} rather than
     * this.
     */
    public int depthIn(TimeSignature timeSignature) {
        Objects.requireNonNull(timeSignature, "timeSignature");
        return switch (this) {
            case BEAT -> 0;
            case HALF_BEAT, THIRD_BEAT -> 1;
            case QUARTER_BEAT, SIXTH_BEAT -> 2;
            case EIGHTH_BEAT -> 3;
        };
    }

    /** A tuplet ratio: {@code actual} notes printed in the time of {@code normal}. */
    public record Tuplet(int actual, int normal) {
        public Tuplet {
            if (actual < 2 || normal < 2) {
                throw new IllegalArgumentException(
                        "a tuplet needs at least two notes on both sides, got "
                                + actual + "/" + normal);
            }
        }

        @Override
        public String toString() {
            return actual + "/" + normal;
        }
    }
}
