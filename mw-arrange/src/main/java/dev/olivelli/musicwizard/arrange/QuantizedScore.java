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

import dev.olivelli.musicwizard.core.model.Lyrics;
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
 * @param unplaceableChords how many chords collapsed, meaning their two
 *              boundaries snapped to one position so they could not be given a
 *              beat of their own. Zero on anything that came out on the beat
 *              axis; non-zero only on a progression left entirely in seconds.
 *              <b>Not</b> a count of how much harmony is finer than the pulse --
 *              see {@link #unplaceableChords()}
 */
public record QuantizedScore(Score score, List<BarGrid> grids, SwingFeel swing,
                             int unplaceableChords) {

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
        if (unplaceableChords < 0 || unplaceableChords > score.chords().size()) {
            throw new IllegalArgumentException(
                    "unplaceableChords must be between 0 and the " + score.chords().size()
                            + " chords in the progression, got: " + unplaceableChords);
        }
        // The count and the axis are two readings of one fact, and pairing a
        // score with somebody else's count is the mistake withScore exists to
        // prevent for the grids. A progression that lost a chord lost the beat
        // axis with it: there is no half-placed progression to report.
        if (unplaceableChords > 0 && score.chords().isQuantized()) {
            throw new IllegalArgumentException(
                    "a progression reporting " + unplaceableChords + " unplaceable chords"
                            + " cannot also be on the beat axis; one unplaceable chord takes"
                            + " the beat axis off the whole progression");
        }
    }

    /**
     * The three-argument form, for a stage that placed every chord it was given.
     *
     * <p>Which is every caller that is not {@link Quantizer} itself: a score
     * whose chords are on the beat axis, or which has no chords, has nothing to
     * report here.
     */
    public QuantizedScore(Score score, List<BarGrid> grids, SwingFeel swing) {
        this(score, grids, swing, 0);
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
     *
     * <p><b>It can throw</b>, for a score that changed the harmony rather than
     * the notes: the count came from the old progression, and the constructor's
     * validation is applied to the new one. That is deliberate rather than an
     * oversight left visible -- clamping the count to zero instead would
     * manufacture the claim that every chord was placed, about a progression
     * sitting in seconds, which is worse than either alternative.
     *
     * <p>It is a check and <b>not a guarantee</b>, and the difference is worth
     * stating because the reassuring version of this paragraph is what a caller
     * would act on. Two rewrites are caught: one leaving fewer chords than the
     * count, and one putting the same chords back on the beat axis. A rewrite
     * that keeps at least as many chords and leaves them in seconds is
     * <em>not</em> caught -- swap {@code [C, G]} for {@code [D, E]} and a count
     * of one measured on the first rides along onto the second in silence. A
     * stage that rewrites harmony should build the record itself and state its
     * own count. None does today: {@code spell} passes the progression through
     * by reference.
     *
     * @throws IllegalArgumentException if the new score's progression has fewer
     *         chords than this count, or is on the beat axis while this count is
     *         non-zero
     */
    public QuantizedScore withScore(Score newScore) {
        return new QuantizedScore(newScore, grids, swing, unplaceableChords);
    }

    /**
     * How many chords collapsed, which is why the whole progression is still in
     * seconds.
     *
     * <p><b>The canonical statement of what a withdrawal is</b>, referenced from
     * elsewhere in this file rather than restated, because it has now been got
     * wrong twice by being paraphrased. A chord <em>collapsed</em> when its two
     * boundaries snapped to a single position, leaving it no length. That is
     * usually because it is shorter than the counted beat it falls in, and it is
     * not the same thing: a chord of exactly one counted beat collapses when the
     * microsecond of overlap {@code ChordProgression} tolerates resolves its
     * start forward onto a rounding midpoint, and one of exactly one 7/8 beat
     * collapses when it straddles a meter change into 4/4, where both its ends
     * fall inside a single quarter-note cell. In both, no chord in the
     * progression is shorter than its counted beat and the progression is
     * withdrawn regardless.
     *
     * <p>Zero on every score that came out on the beat axis, and the two are
     * exactly complementary: {@link Quantizer} does not discard a chord, so a
     * progression it could not place completely it does not place at all. That
     * is #157. The alternative -- dropping the short ones -- turns
     * {@code --tempo 60} against material heard at 120 into a chart that names
     * I-vi-I-vi where the music played I-V-vi-IV, silently.
     *
     * <p><b>What it is not</b> takes more saying than what it is, because the
     * obvious reading is wrong and an earlier version of this javadoc printed
     * it. This is <em>not</em> a count of the chords that are shorter than their
     * counted beat, and a caller must not say "four of your eight chords are
     * shorter than a beat at this tempo" from it. Only the chords whose
     * boundaries actually landed on one position are counted, and the difference
     * is not a constant offset: it turns on where in the beat the progression
     * happens to start. The eight-chord fixture from #157, every chord of which
     * is shorter than a counted beat at every tempo below, read against a
     * supplied tempo of 40 BPM:
     *
     * <pre>
     * first chord at 0.00 s   unplaceable 5 of 8
     * first chord at 0.25 s   unplaceable 5 of 8
     * first chord at 0.50 s   unplaceable 5 of 8
     * first chord at 0.75 s   unplaceable 6 of 8
     * </pre>
     *
     * <p>Nor is it a scale of how badly the pulse disagrees, though it does
     * broadly rise with it -- 4, 5, 6, 6, 7 at 60, 40, 30, 24 and 15 BPM against
     * that fixture. It is not even monotone in the disagreement once the phase
     * is allowed to vary with it. An earlier draft of this paragraph said it
     * "does not grow with the disagreement", which the numbers on the line above
     * refute; the honest form is that it does not grow <em>in proportion</em>,
     * and that phase moves it independently.
     *
     * <p>So what a caller may say from it is what it measures: that the harmony
     * could not be put on the beat axis, and that this many chord spans had no
     * beat of their own. A caller wanting to tell a user how badly the supplied
     * tempo disagrees should measure that itself, from the spans and the map,
     * where the question has an exact answer.
     */
    public int unplaceableChords() {
        return unplaceableChords;
    }

    /**
     * True when every note, chord, section and key carries musical timing.
     *
     * <p>Those four and no others, which is worth saying rather than writing
     * "everything with a position": {@link Lyrics} carries the same optional
     * beat fields on every word and is <em>not</em> included, because
     * {@link Quantizer} does not place lyrics. Chord-over-lyric placement needs
     * the chords first and is #9; the gap is #150. Naming the four is the honest
     * form -- an accessor called "fully quantized" that quietly excluded a fifth
     * kind is exactly the claim a later caller would believe.
     *
     * <p>The spans are included rather than assumed, because for the whole of
     * #91 they were the half that was missing and nothing said so. A caller
     * asking this before engraving is asking whether the beat axis is the whole
     * story, and a score whose notes are on it while its harmony is still in
     * seconds is precisely the case where the answer is no.
     *
     * <p>False is a legitimate answer for a score this class produced. A section
     * or key too short to sit between two bar lines keeps its seconds and no
     * beats, and says so here; so does a whole progression withdrawn from the
     * beat axis, and {@link #unplaceableChords()} is then the reason why and
     * says what withdrawal means. Deliberately not restated here: this is the
     * third place in this file that would describe the same fact, and the
     * previous attempt at the third one got it wrong -- it said the progression
     * is withdrawn "because one of its chords was shorter than a counted beat",
     * which is the usual cause and not the condition, and is false for the two
     * cases {@code theToleratedOverlapIsTheOneExceptionToTheBound} and
     * {@code aMeterChangeToALongerBeatMovesTheBound} pin.
     */
    public boolean isFullyQuantized() {
        return score.tracks().stream().allMatch(t -> t.isQuantized())
                && score.chords().isQuantized()
                && score.sections().stream().allMatch(s -> s.isQuantized())
                && score.keys().stream().allMatch(k -> k.isQuantized());
    }
}
