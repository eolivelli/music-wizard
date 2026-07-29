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

package dev.olivelli.musicwizard.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * How a value in a score came to be known.
 *
 * <p>This exists because the pipeline kept deriving the answer twice. A value's
 * origin is known exactly once -- by the stage that produced it -- and every
 * reader afterwards had to guess it back from the shape of the artifact:
 * {@link Score#estimatedTempo()} recognised a user-supplied tempo by counting
 * tempo segments, and a command that wanted to know whether a score came from a
 * recording or from a MIDI file re-read the input file. Both guesses were right
 * for the cases they were written against and wrong just outside them. Carrying
 * the fact costs one enum; deriving it costs a bug per reader.
 *
 * <p><b>This is not confidence.</b> {@link Confidence} says how much to trust an
 * estimate; provenance says whether the value is an estimate at all. They are
 * orthogonal: a {@link #MEASURED} beat grid can be highly confident, and a
 * {@link #DECLARED} tempo is not an estimate of anything, so no confidence
 * figure describes it. Nor does {@link #DECLARED} mean exact -- MIDI stores
 * microseconds per quarter note as an integer, so a file that meant 140 BPM
 * declares 140.00014 -- it means somebody stated the value rather than the
 * pipeline inferring it.
 *
 * <p><b>An unrecognised name reads as {@link #UNKNOWN} rather than failing the
 * whole file.</b> This enum's javadoc promises new constants, and without that a
 * {@code score.json} written by a build that has one would be unreadable in its
 * entirety by a build that does not -- #22 arriving from the other direction.
 * The cost is that a misspelt label in a hand-edited file reads as "not
 * recorded" instead of being rejected, which is the right trade for a field
 * whose whole purpose is to be honest about not knowing. Other enums in this
 * model are not forward-compatible in the same way and are not made so here;
 * #142 covers them, because the remedy differs -- a null on a component that has
 * no null reading is not an improvement.
 *
 * <p>Deliberately an enum rather than a boolean. The sources are already four
 * today and M2 (separated stems) and M5 (advisor-proposed values) add more; a
 * flag that only distinguishes "from the file" from "from the signal" would be
 * wrong within a milestone. New constants are added as producers for them
 * appear, which is why there is no {@code ADVISED} yet: an unused constant is a
 * claim about behaviour that does not exist.
 */
public enum Provenance {

    /**
     * Not recorded. A value read from a score file written before its producer
     * carried provenance, or built by a caller that did not say.
     *
     * <p>Readers must treat this as "unknown", never as a default origin.
     * Guessing here is exactly the failure the enum exists to remove -- but a
     * reader that must still answer may fall back on whatever heuristic it used
     * before, since for these values nothing better exists.
     */
    UNKNOWN,

    /**
     * Derived from the signal by an analysis stage: tracked beats, estimated
     * chords, tracked pitch. An estimate, and on this project usually the
     * shakiest stage in the pipeline -- but not the weakest origin in this enum,
     * which is {@link #ASSUMED}. A measurement is at least evidence about this
     * recording; a default is evidence about nothing.
     */
    MEASURED,

    /**
     * Stated by the source file itself -- a MIDI tempo, meter or key-signature
     * meta event. The pipeline read it rather than inferring it.
     */
    DECLARED,

    /**
     * Given by a human: {@code --tempo}, {@code --first-downbeat}, or a later
     * edit. Outranks everything the pipeline worked out for itself, because a
     * correction that loses to the value it corrects is not a correction.
     */
    SUPPLIED,

    /**
     * A documented default substituted because nothing stated the value and
     * nothing measured it -- 120 BPM and 4/4 where a MIDI file declares neither,
     * or where beat tracking found nothing to measure.
     *
     * <p>Distinct from {@link #DECLARED} on purpose: a file that declares 120
     * and a file that declares nothing produce the same number, and reporting
     * both as read from the file states a fact the file does not contain.
     */
    ASSUMED,

    /**
     * Computed from other facts already in the score rather than from the input:
     * chords named from imported notes, or a tempo-map lead-in stretched to land
     * on the first tracked pulse.
     *
     * <p>Kept apart from the origin it was computed from, because a derived
     * value is not a second statement of that fact. A lead-in built to anchor a
     * supplied tempo carries a rate nobody asked for -- it is an artefact of
     * where the first pulse fell -- and reading it as {@code SUPPLIED} would
     * report that artefact as the user's instruction.
     */
    DERIVED;

    /**
     * Whether somebody asserted this value rather than the pipeline working it
     * out: {@link #DECLARED} or {@link #SUPPLIED}.
     *
     * <p>{@link #ASSUMED} is deliberately excluded. A default is nobody's
     * statement; it is the pipeline filling a gap, and it must not win over
     * measured evidence the way a statement does.
     */
    public boolean isStated() {
        return this == DECLARED || this == SUPPLIED;
    }

    /**
     * Reads a stored label, answering {@link #UNKNOWN} for any name this build
     * does not have.
     *
     * <p>Scoped to this enum on purpose. Turning unknown enum values into nulls
     * globally on the mapper would also do it for {@code ChordQuality} and
     * {@code PartRole}, where there is no "not recorded" reading and a null
     * would fail a component's own validation several frames later. #142.
     *
     * <p>It swallows a misspelt label as well as a future one, and what that
     * costs is worth tabulating rather than summarising, because it is neither
     * "the value is not recorded" nor monotonic in the number of typos. Take the
     * map {@code --tempo 60} builds for a twelve-second recording whose first
     * tracked pulse falls at 0.2s: a {@link #DERIVED} anchor running at 300 for
     * that 0.2s, then the {@link #SUPPLIED} 60. Beside it, the grid it was built
     * from, tracked at 120. {@link Score#estimatedTempo()} answers:
     *
     * <pre>
     *   labels lost   with that grid   with no beat grid
     *   none                60                 60
     *   one (the supplied) 120                 60
     *   all                 60                 64
     * </pre>
     *
     * <p>Each wrong cell is wrong for its own reason. With the grid, losing one
     * label leaves the map recording <em>some</em> provenance, so neither a
     * supplied tempo nor the shape proxy answers and the grid does -- the
     * correction is discarded. With no grid, losing <em>every</em> label sends
     * the map to the proxy, which gives up, and the fallback then has no
     * {@code DERIVED} label to tell it which segment is the anchor: the anchor's
     * 300 over 0.2s and the 60 over the remaining 11.8s average to 64. A figure
     * nobody supplied and the music never had -- and one that moves with the
     * recording's length, unlike the 60 and the 120 beside it, which are the
     * supplied value and the grid's median.
     *
     * <p>The two cells that come out right are not right for the same reason.
     * Losing one label without a grid is right <em>by design</em>: the surviving
     * {@code DERIVED} label is doing exactly its job, and that cell is the only
     * assertion in this module standing between the anchor-skip and a widening
     * of it that would look like an improvement. Losing every label with the
     * grid is a coincidence: the shape proxy exempts the lead-in positionally,
     * by the rule spelled out on {@link Score#estimatedTempo()}, so that cell
     * holds only while the grid's beats still line up with the map's. Move the
     * grid off its map and it reads the grid's median instead. <b>The table is
     * for the grid the map was built from</b>, and claims nothing for a grid
     * that has drifted from it.
     *
     * <p>So the honest summary is that a typo degrades to whatever the score's
     * <em>other</em> evidence says, which on this particular correction is the
     * highest-value action a user can take. Nothing this tool writes can produce
     * one; a hand-edited file can, and
     * {@code ProvenanceTest.aMistypedLabelCostsWhateverTheScoreElseSays} pins
     * every cell of that table so this paragraph cannot quietly stop being
     * true.
     *
     * <p>It also declines an ordinal, which Jackson would otherwise accept: a
     * bare {@code 2} used to read as {@code DECLARED} and now reads as
     * {@code UNKNOWN}. Positional labels are not something a file should be able
     * to rely on for an enum whose javadoc promises new constants.
     *
     * <p>The null branch is for direct callers only. Jackson resolves a null
     * token before it reaches a delegating creator, so an explicit
     * {@code "provenance": null} in a file is handled by
     * {@link TempoMap.TempoSegment}'s compact constructor instead, and always
     * was.
     */
    @JsonCreator
    public static Provenance fromJson(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        for (Provenance candidate : values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }
}
