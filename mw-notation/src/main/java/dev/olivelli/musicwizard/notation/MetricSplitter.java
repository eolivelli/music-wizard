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

import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits a span within one bar into the note values a musician would read.
 *
 * <p>A span of music is not a duration. Three quarter beats starting on beat one
 * of 4/4 is a dotted half; the same three beats starting on beat two is a
 * quarter tied to a half, because a single symbol there would hide beat three.
 * The difference is the meter, so this is where the meter is consulted and
 * {@link LilyPondDuration} is left knowing only about lengths.
 *
 * <p>The bar is modelled as a tree of nested metric units. Its top level is
 * {@link TimeSignature#beatStructure()} — the two dotted-quarter groups of a 6/8
 * bar, the four quarters of a 4/4 one. A power-of-two number of groups is
 * gathered into halves first, so a 4/4 bar divides into two half-bars before it
 * divides into beats and a note may legitimately span one of them. Each group
 * then divides in three if the meter is compound and in two otherwise, and by
 * two from there down to a 64th.
 *
 * <p>A span is written as one value when two things hold. Its length must be a
 * length that unit naturally takes — a whole number of the unit's children, or
 * one and a half of them, which is what a dot is. And it must be allowed to
 * <em>start</em> where it does: a symbol long enough to swallow a beat may only
 * begin on one. Otherwise the span is cut at the unit's children and each piece
 * asked the same question.
 *
 * <p>Those two together are what separates the meters. The first four eighths of
 * a 4/4 bar are a dotted quarter; the first four eighths of a 6/8 bar are a
 * dotted quarter tied to an eighth, because four eighths is not a length the
 * compound bar takes. The <em>second</em> to fourth eighths of a 6/8 bar are a
 * quarter tied to an eighth rather than the dotted quarter their length would
 * allow, because a dotted quarter starting there would cover the second beat.
 * See {@link #mayStartHere}, which is where that half of the rule lives.
 *
 * <p><b>Limitation.</b> Tuplets are not represented. Every length here is a
 * whole number of 64th notes; a triplet is not, and the emitter snaps it away
 * before this is reached. See #92.
 */
final class MetricSplitter {

    /** How much longer a dotted value is than the value it dots. */
    private static final double DOT_FACTOR = 1.5;

    private MetricSplitter() {
    }

    /**
     * A metric unit: a span of the bar, and the units it divides into.
     *
     * <p>Children are equal in length, which is what lets {@code childLength}
     * answer for all of them. That holds by construction: a beat structure has
     * one entry per counted beat and they are all the same size, and every
     * subdivision below it halves or thirds.
     *
     * @param start    where the unit begins, in quarter beats from the bar line
     * @param end      where it ends
     * @param children the units it divides into, empty at the shortest value
     */
    private record Unit(double start, double end, List<Unit> children) {

        double length() {
            return end - start;
        }

        double childLength() {
            return children.getFirst().length();
        }
    }

    /**
     * Note values covering {@code [fromInBar, toInBar)}, in order, all within one
     * bar of the given meter.
     *
     * <p>Consecutive values are to be tied by the caller: they are one note, cut
     * where the meter demanded it.
     *
     * @throws IllegalArgumentException if the span leaves the bar or does not
     *         land on the 64th-note grid
     */
    static List<String> split(TimeSignature meter, double fromInBar, double toInBar) {
        Objects.requireNonNull(meter, "meter");
        double barLength = meter.quarterBeatsPerBar();
        requireOnGrid(fromInBar, "fromInBar");
        requireOnGrid(toInBar, "toInBar");
        if (fromInBar < 0 || toInBar > barLength) {
            throw new IllegalArgumentException(
                    "span " + fromInBar + ".." + toInBar + " leaves a " + meter + " bar,"
                            + " which holds " + barLength + " quarter beats");
        }
        List<String> values = new ArrayList<>();
        emit(barTree(meter), fromInBar, toInBar,
                meter.beatUnitQuarters(), meter.isCompound(), values);
        return values;
    }

    private static void requireOnGrid(double beats, String name) {
        if (!Double.isFinite(beats)
                || Math.round(beats / LilyPondDuration.SHORTEST_QUARTERS)
                        * LilyPondDuration.SHORTEST_QUARTERS != beats) {
            throw new IllegalArgumentException(
                    name + " must be a whole number of 1/" + LilyPondDuration.SHORTEST_DENOMINATOR
                            + " notes, got: " + beats);
        }
    }

    private static void emit(Unit unit, double from, double to,
                             double beatUnit, boolean compound, List<String> out) {
        if (!(to > from)) {
            return;
        }
        if (unit.children().isEmpty()) {
            // The shortest unit. The grid guarantees the span is the whole of it,
            // so a missing value here means the tree and the grid disagree.
            out.add(LilyPondDuration.of(to - from).orElseThrow(() -> new IllegalStateException(
                    "no note value for " + (to - from) + " quarter beats at the shortest unit")));
            return;
        }
        double length = to - from;
        if (isNaturalLength(length, unit.childLength(), unit.children().size())
                && mayStartHere(from, length, unit.childLength(), beatUnit, compound)
                && LilyPondDuration.isSingleValue(length)) {
            out.add(LilyPondDuration.of(length).orElseThrow());
            return;
        }
        List<Unit> children = unit.children();
        int index = 0;
        while (index < children.size()) {
            Unit child = children.get(index);
            double childFrom = Math.max(from, child.start());
            double childTo = Math.min(to, child.end());
            if (!(childTo > childFrom)) {
                index++;
                continue;
            }
            if (childFrom == child.start() && childTo == child.end()) {
                // A run starts on a child boundary, and above the beat every
                // child boundary is a counted beat, so mayStartHere is satisfied
                // by construction and is not re-asked here.
                int last = longestRunFrom(children, index, to, child.start());
                if (last >= 0) {
                    out.add(LilyPondDuration.of(children.get(last).end() - child.start())
                            .orElseThrow());
                    index = last + 1;
                    continue;
                }
            }
            emit(child, childFrom, childTo, beatUnit, compound, out);
            index++;
        }
    }

    /**
     * The last child a run starting at {@code first} may reach and still be one
     * value, or -1 when even the first child alone is not.
     *
     * <p>Without this, a unit that is not itself a single value falls apart into
     * every one of its children: a whole 5/4 bar held by one note came out as
     * five tied quarters instead of a whole note tied to a quarter. Taking the
     * longest run rather than the first that fits is what turns seven tied
     * eighths in 7/8 into a dotted half and an eighth.
     */
    private static int longestRunFrom(List<Unit> children, int first, double to, double runStart) {
        int last = -1;
        for (int j = first; j < children.size() && children.get(j).end() <= to; j++) {
            if (LilyPondDuration.isSingleValue(children.get(j).end() - runStart)) {
                last = j;
            }
        }
        return last;
    }

    /**
     * Whether a unit of {@code childCount} children of {@code childLength} may be
     * written as a single value of this length.
     *
     * <p>A whole number of children, or one and a half of them. The half is the
     * dot, and it is why {@code 4.} appears at the start of a 4/4 bar — three of
     * the half-bar's two beats' worth — while the same length in 6/8 is the whole
     * of one group rather than one and a half of anything.
     *
     * <p>Compared exactly: every length here is a dyadic rational built by
     * halving and thirding a bar whose length has a power-of-two denominator, so
     * neither the multiplication nor the division rounds.
     */
    private static boolean isNaturalLength(double length, double childLength, int childCount) {
        if (length == DOT_FACTOR * childLength) {
            return true;
        }
        double children = length / childLength;
        return children == Math.rint(children) && children >= 1 && children <= childCount;
    }

    /**
     * Whether a span of a natural length may be written as one symbol from this
     * starting point, or whether starting here would hide a beat.
     *
     * <p>Length alone is not enough. A dotted quarter is a natural length in 6/8
     * — it is one whole counted beat — so a rule that asked only about length
     * wrote the second-to-fourth-eighth span as {@code 4.}, a beat-long symbol
     * lying across the bar of the second beat, hiding the very grouping the meter
     * exists to show. The conventional writing ties: {@code 4~ 8}.
     *
     * <p>One sentence: <b>a symbol has to begin on a counted beat once it is long
     * enough to swallow one.</b> Long enough means either longer than a dotted
     * beat, or — in compound time only — as long as a beat, because there the
     * three-grouping <em>is</em> the meter and a symbol laid across it removes the
     * only thing distinguishing 6/8 from 3/4. Anything shorter may start
     * anywhere.
     *
     * <p>That threshold is where it is so that ordinary syncopation survives.
     * A quarter on the second eighth of a 4/4 bar is a quarter and a dotted
     * quarter there is a dotted quarter; both cross the next beat, both are how
     * every syncopated pop chart is printed, and refusing them would fill the
     * page with ties nobody writes. One beat further and the symbol stops being a
     * syncopation and starts being a hole in the bar: a half note beginning a
     * sixteenth after beat one of 4/4 swallows the middle of it.
     *
     * <p>Round 1 of review caught the length half of this missing entirely, and
     * round 2 caught the first fix testing the <em>unit's</em> size rather than
     * the <em>symbol's</em>. Those differ in exactly the meters whose beats do not
     * come in a power-of-two count — 3/4, 5/4, 6/4, 7/8 — where the bar divides
     * straight into beats, so no unit is ever longer than one and the first fix
     * never fired. A whole note swallowing four beats of a 5/4 bar from a
     * sixteenth-note offset passed every test in the suite, because the bar still
     * summed and LilyPond still engraved it without a word.
     */
    private static boolean mayStartHere(double from, double length, double childLength,
                                        double beatUnit, boolean compound) {
        boolean couldSwallowABeat = length > DOT_FACTOR * beatUnit
                || (childLength == beatUnit && compound);
        if (!couldSwallowABeat) {
            return true;
        }
        // Exact: a counted beat sits at a whole multiple of the beat unit from
        // the bar line, and IEEE division of one exactly-representable value by
        // another returns the whole number exactly when there is one.
        double beats = from / beatUnit;
        return beats == Math.rint(beats);
    }

    /** The metric tree of one bar. */
    private static Unit barTree(TimeSignature meter) {
        double barLength = meter.quarterBeatsPerBar();
        int groups = meter.beatsPerBar();
        if (groups == 1) {
            return beatGroup(0, barLength, meter.isCompound());
        }
        if (Integer.bitCount(groups) == 1) {
            // A power-of-two count is halved first, so a 4/4 bar has two
            // half-bars a note may span. Without that level, a half note on the
            // downbeat of 4/4 would come out as two tied quarters.
            return halved(0, barLength, groups, meter.isCompound());
        }
        double groupLength = barLength / groups;
        List<Unit> children = new ArrayList<>(groups);
        for (int i = 0; i < groups; i++) {
            children.add(beatGroup(i * groupLength, (i + 1) * groupLength, meter.isCompound()));
        }
        return new Unit(0, barLength, List.copyOf(children));
    }

    /** Halves a run of {@code groups} beat groups until single groups are left. */
    private static Unit halved(double start, double end, int groups, boolean compound) {
        if (groups == 1) {
            return beatGroup(start, end, compound);
        }
        double middle = (start + end) / 2;
        return new Unit(start, end, List.of(
                halved(start, middle, groups / 2, compound),
                halved(middle, end, groups / 2, compound)));
    }

    /**
     * One counted beat: three parts in compound time, two otherwise.
     *
     * <p>This is the level the compound/simple distinction lives at, and it is
     * the reason a 6/8 bar cannot be written as a half note plus an eighth: the
     * dotted-quarter group divides into three eighths, and two of them are a
     * quarter, but four of them are not any single value the group offers.
     */
    private static Unit beatGroup(double start, double end, boolean compound) {
        if (!compound) {
            return halvedToShortest(start, end);
        }
        double third = (end - start) / 3;
        List<Unit> children = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            children.add(halvedToShortest(start + i * third, start + (i + 1) * third));
        }
        return new Unit(start, end, List.copyOf(children));
    }

    /** Halves a span repeatedly, stopping at the shortest value named. */
    private static Unit halvedToShortest(double start, double end) {
        double length = end - start;
        if (length / 2 < LilyPondDuration.SHORTEST_QUARTERS) {
            return new Unit(start, end, List.of());
        }
        double middle = (start + end) / 2;
        return new Unit(start, end, List.of(
                halvedToShortest(start, middle), halvedToShortest(middle, end)));
    }
}
