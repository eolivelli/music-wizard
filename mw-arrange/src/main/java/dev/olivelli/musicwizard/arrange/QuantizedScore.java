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

import dev.olivelli.musicwizard.core.model.Score;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link Quantizer} produces: a score whose notes now carry musical time,
 * plus the two decisions the notation layer cannot re-derive for itself.
 *
 * <p>The grid a bar was quantized against is not recoverable from the snapped
 * positions alone. Three notes at 0, 1/3 and 2/3 of a beat are a triplet; three
 * notes at 0, 1/2 and 1 are two eighths and a beat, and both are legal on the
 * sixth-of-a-beat grid. Guessing wrongly prints a tuplet bracket around a plain
 * pair of eighths, so the decision is carried rather than inferred.
 *
 * @param score the score, with {@code onsetBeat} and {@code durationBeats}
 *              filled in on every note and the seconds left exactly as they were
 * @param grids one entry per bar in which a note sounds, ordered by bar --
 *              including a bar a held note only passes through, whose tied tail
 *              still has to be engraved on that bar's grid
 * @param swing the shuffle that was taken out before snapping, or
 *              {@link SwingFeel#STRAIGHT}. One verdict for the whole score, and
 *              it does not apply to compound bars -- use {@link #swingIn(int)}
 *              rather than printing this over every system
 */
public record QuantizedScore(Score score, List<BarGrid> grids, SwingFeel swing) {

    public QuantizedScore {
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(swing, "swing");
        grids = List.copyOf(Objects.requireNonNull(grids, "grids"));
        for (int i = 1; i < grids.size(); i++) {
            if (grids.get(i).bar() <= grids.get(i - 1).bar()) {
                throw new IllegalArgumentException(
                        "grids must be strictly ordered by bar; entry " + i + " is bar "
                                + grids.get(i).bar() + " after bar " + grids.get(i - 1).bar());
            }
        }
    }

    /** The grid chosen for a bar, or empty when nothing sounds in that bar. */
    public Optional<BarGrid> gridAtBar(int bar) {
        // Binary search: a long piece has thousands of bars and the notation
        // layer asks this once per note.
        int low = 0;
        int high = grids.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int at = grids.get(mid).bar();
            if (at == bar) {
                return Optional.of(grids.get(mid));
            }
            if (at < bar) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Optional.empty();
    }

    /**
     * The grid covering a position on the beat axis, or empty when nothing
     * sounds in the bar it falls in.
     *
     * @throws IllegalArgumentException if the beat is not finite and non-negative
     */
    public Optional<BarGrid> gridAtBeat(double beat) {
        return gridAtBar(score.tempoMap().toMusicalTime(beat).bar());
    }

    /**
     * The feel in force in one bar.
     *
     * <p>{@link #swing()} is one verdict for the whole score, but the quantizer
     * deliberately leaves compound bars un-straightened: a shuffle is compound
     * time written in a simple meter, so there is nothing to take out of a bar
     * that is already compound. Printing the score's feel over such a bar would
     * tell a reader to shuffle music that is already literal, which is the same
     * defect as engraving it in duplets and only looks different.
     *
     * <p>Asked of the tempo map rather than of the published grid. The question
     * is what meter the bar is in, and the grid can only answer it for bars that
     * hold notes -- so a rest bar in the middle of a 6/8 system came back swung,
     * which is the same wrong direction printed over the same music, in the one
     * bar the grid cannot see.
     *
     * @throws IllegalArgumentException if the bar index is negative
     */
    public SwingFeel swingIn(int bar) {
        if (bar < 0) {
            throw new IllegalArgumentException("bar must be non-negative, got: " + bar);
        }
        return score.tempoMap().timeSignatureAtBar(bar).isCompound() ? SwingFeel.STRAIGHT : swing;
    }

    /**
     * Returns a copy carrying a different score, for a stage that transforms the
     * notes without moving them.
     *
     * <p>{@link PitchSpeller#spell(Score)} is the one that does: spelling
     * changes how a pitch is written and nothing about when it sounds, so the
     * grids and the feel still describe the result. Rebuilding the record by
     * hand is legal and is one argument order away from pairing a score with
     * somebody else's grids.
     */
    public QuantizedScore withScore(Score newScore) {
        return new QuantizedScore(newScore, grids, swing);
    }

    /** True when every track's notes carry musical timing. */
    public boolean isFullyQuantized() {
        return score.tracks().stream().allMatch(t -> t.isQuantized());
    }
}
