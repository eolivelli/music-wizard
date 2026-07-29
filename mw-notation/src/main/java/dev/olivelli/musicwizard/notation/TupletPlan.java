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
import dev.olivelli.musicwizard.arrange.QuantizedScore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which bars of a score are tuplet bars, as the emitter asks the question.
 *
 * <p>One of these is threaded through {@link StaffNotation} so that the tuplet
 * decision is carried from the quantizer to the page instead of being
 * rediscovered there. {@link #none()} is the answer for a plain {@link
 * dev.olivelli.musicwizard.core.model.Score}, which is what the audio track
 * produces and what every caller before #92 passed: it says "no bar is a tuplet
 * bar" to every question, so the emitter takes exactly the path it took before.
 *
 * <p><b>Only tuplet bars are described.</b> A bar the quantizer divided in
 * halves or quarters is a bar this says nothing about, even though the grid for
 * it exists and is known — because there is nothing to say. Its positions are
 * whole numbers of 64ths already and the emitter's own grid snap is a no-op on
 * them, so describing them would be a second opportunity to disagree with the
 * quantizer and no opportunity to be more right.
 */
final class TupletPlan {

    private final QuantizedScore quantized;

    /**
     * Where the published grids stop, so that a position past all of them is
     * answered without asking the tempo map about it.
     *
     * <p>The largest end over <em>every</em> grid rather than the last one's,
     * and computed once rather than per lookup. {@link QuantizedScore} validates
     * that its grids are ordered by bar and says nothing about their
     * {@code startBeat}s, so a hand-built one whose bar starts disagree with its
     * own tempo map can put the furthest end anywhere in the list. Round 3 of
     * review pointed out that trusting the last entry gives that malformed score
     * a new blast radius — one bad entry at the end would gate the lookup for
     * every bar — where before it only misplaced its own bar.
     */
    private final double beyondTheLastGrid;

    /**
     * Cached, because {@link StaffNotation} asks per note and per bar and the
     * answer is one binary search plus a record allocation each time.
     */
    private final Map<Integer, Optional<TupletBar>> byBar = new HashMap<>();

    private TupletPlan(QuantizedScore quantized) {
        this.quantized = quantized;
        this.beyondTheLastGrid = quantized == null ? 0
                : quantized.grids().stream().mapToDouble(BarGrid::endBeat)
                        .max().orElse(0);
    }

    static TupletPlan of(QuantizedScore quantized) {
        return new TupletPlan(quantized);
    }

    /**
     * The plan for a score that was never quantized, or whose grids are unknown.
     *
     * <p>A new instance rather than a shared constant, which it was until round
     * 1 of review pointed out that a constant carries this class's cache with
     * it: a mutable {@link HashMap} reachable from every thread engraving every
     * unquantized score, safe today only because the null check returns before
     * touching it. An allocation of two fields per engraved part is not worth
     * the invariant.
     */
    static TupletPlan none() {
        return new TupletPlan(null);
    }

    /** The bar, when the quantizer divided it into tuplets. */
    Optional<TupletBar> atBar(int bar) {
        if (quantized == null || bar < 0) {
            return Optional.empty();
        }
        return byBar.computeIfAbsent(bar,
                index -> quantized.gridAtBar(index).flatMap(TupletBar::of));
    }

    /**
     * The tuplet bar a position on the beat axis falls in, if it is one.
     *
     * <p>A position exactly on a bar line belongs to the bar it opens, which is
     * what {@link dev.olivelli.musicwizard.core.model.TempoMap#toMusicalTime}
     * says and what the quantizer used when it placed the note. It makes no
     * difference to where the position lands: step zero of any bar is that bar's
     * downbeat whatever its grid.
     */
    Optional<TupletBar> covering(double beat) {
        if (quantized == null || !Double.isFinite(beat) || beat < 0) {
            return Optional.empty();
        }
        // Past every published grid there is no grid to find, so the answer is
        // known without walking the tempo map for it. Not an optimisation:
        // TempoMap.toMusicalTime refuses a beat past bar 2^31 with a message
        // about bar indices, and a note quantized that far out would therefore
        // have been diagnosed one way through the QuantizedScore overload and
        // another -- with StaffNotation's own message, naming the part
        // responsible -- through the Score one. Two overloads of the same method
        // failing differently on the same score is the shape this project keeps
        // paying for, and it costs one comparison to not have it.
        if (beat >= beyondTheLastGrid) {
            return Optional.empty();
        }
        // Which bar a beat falls in is QuantizedScore's question, not this
        // class's. It was asked here directly until round 3 of review found the
        // two copies had already drifted -- the guard above reached this one and
        // not the accessor -- which is the defect this whole change exists to
        // stop, one level down. The bound belongs there too, next to the grids
        // it is a fact about, and #138 moves it once there is a second caller
        // to justify touching mw-arrange for it.
        return quantized.gridAtBeat(beat).flatMap(grid -> atBar(grid.bar()));
    }
}
