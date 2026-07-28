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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.olivelli.musicwizard.arrange.BarGrid;
import dev.olivelli.musicwizard.arrange.GridResolution;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic {@link StaffNotation} relies on when it writes a bracket,
 * checked over every meter and grid rather than argued for in a comment.
 *
 * <p>Three of the four claims here are the reason there is no guard in the code
 * for the case they exclude. A guard for a remainder that cannot arise is a
 * branch no test can reach; a sweep that shows it cannot arise is a test that
 * fails the day the set of resolutions grows.
 */
class TupletBarTest {

    /** Every meter the model admits: numerator 1..64 over a power of two up to 64. */
    private static List<TimeSignature> everyMeter() {
        List<TimeSignature> meters = new ArrayList<>();
        for (int denominator = 1; denominator <= 64; denominator *= 2) {
            for (int numerator = 1; numerator <= 64; numerator++) {
                meters.add(new TimeSignature(numerator, denominator));
            }
        }
        return meters;
    }

    /** Every bar that is a tuplet bar, one per meter and grid pair. */
    private static List<TupletBar> everyTupletBar() {
        List<TupletBar> bars = new ArrayList<>();
        for (TimeSignature meter : everyMeter()) {
            for (GridResolution resolution : GridResolution.values()) {
                TupletBar.of(new BarGrid(0, 0, resolution, meter)).ifPresent(bars::add);
            }
        }
        return bars;
    }

    @Test
    @DisplayName("only the grids that need a bracket get one, and it is the meter that decides")
    void tupletBarsAreExactlyTheGridsTheMeterCallsTuplets() {
        // Simple time subdivides in two, so thirds and sixths are tuplets;
        // compound time subdivides in three, so it is the halves that are. The
        // one that trips people up is 6/8, where the plain eighth is the third
        // of a beat and the duplet is the half.
        assertThat(TupletBar.of(new BarGrid(0, 0, GridResolution.THIRD_BEAT,
                TimeSignature.FOUR_FOUR)).map(TupletBar::ratio)).contains("3/2");
        assertThat(TupletBar.of(new BarGrid(0, 0, GridResolution.HALF_BEAT,
                TimeSignature.FOUR_FOUR))).isEmpty();
        assertThat(TupletBar.of(new BarGrid(0, 0, GridResolution.HALF_BEAT,
                TimeSignature.SIX_EIGHT)).map(TupletBar::ratio)).contains("2/3");
        assertThat(TupletBar.of(new BarGrid(0, 0, GridResolution.THIRD_BEAT,
                TimeSignature.SIX_EIGHT))).isEmpty();

        // The one place this disagrees with GridResolution, and it is a defect
        // there rather than a judgement here: one position per counted beat in
        // compound time is the dotted quarter itself, and isTupletIn calls it a
        // duplet because one is not a multiple of three. A bracket of two over a
        // beat holding one step has nothing to hold. See #130.
        assertThat(GridResolution.BEAT.isTupletIn(TimeSignature.SIX_EIGHT)).isTrue();
        assertThat(TupletBar.of(new BarGrid(0, 0, GridResolution.BEAT,
                TimeSignature.SIX_EIGHT))).isEmpty();

        // And the one place it declines for a reason of its own: a bracketed
        // value shorter than the 64th note this emitter's shortest value. Two
        // meters reach it, both absurd, and #131 records that they are engraved
        // as they were before rather than well.
        assertThat(TupletBar.of(new BarGrid(0, 0, GridResolution.SIXTH_BEAT,
                new TimeSignature(1, 32)))).isEmpty();
        assertThat(TupletBar.of(new BarGrid(0, 0, GridResolution.EIGHTH_BEAT,
                new TimeSignature(6, 32)))).isEmpty();

        // Everything else it brackets is a tuplet, and everything it refuses is
        // one of the two families above. Listed by grid and denominator rather
        // than recomputed from the rule under test, which would only assert that
        // the implementation agrees with itself: "a sixth of a beat is
        // unwritable from 1/32 down" is a fact about note values, and a change
        // that moved the boundary should have to say so here.
        Set<String> declined = new TreeSet<>();
        for (TimeSignature meter : everyMeter()) {
            for (GridResolution resolution : GridResolution.values()) {
                boolean bracketed = TupletBar.of(new BarGrid(0, 0, resolution, meter)).isPresent();
                if (bracketed) {
                    assertThat(resolution.isTupletIn(meter))
                            .as("%s in %s is bracketed but is not a tuplet", resolution, meter)
                            .isTrue();
                } else if (resolution.isTupletIn(meter)) {
                    declined.add(resolution + " in " + (meter.isCompound() ? "compound" : "simple")
                            + " x/" + meter.denominator());
                }
            }
        }
        assertThat(declined).containsExactlyInAnyOrder(
                // A beat divided once is the beat -- #130.
                "BEAT in compound x/8", "BEAT in compound x/16",
                "BEAT in compound x/32", "BEAT in compound x/64",
                // Printed shorter than a 64th note -- #131.
                "THIRD_BEAT in simple x/64",
                "SIXTH_BEAT in simple x/32", "SIXTH_BEAT in simple x/64",
                "QUARTER_BEAT in compound x/64",
                "EIGHTH_BEAT in compound x/32", "EIGHTH_BEAT in compound x/64");
    }

    @Test
    @DisplayName("a bracket divides the bar and the beat exactly, in every meter")
    void bracketsDivideTheBarWithoutRemainder() {
        for (TupletBar bar : everyTupletBar()) {
            String label = bar.meter() + " " + bar.ratio() + " step " + bar.stepQuarters();
            assertThat(bar.brackets() * bar.stepsPerBracket())
                    .as("%s: brackets cover the bar", label)
                    .isEqualTo(bar.divisions());
            assertThat(bar.brackets() * bar.bracketLength())
                    .as("%s: brackets are as long as the bar", label)
                    .isEqualTo(bar.meter().quarterBeatsPerBar());
            // The bracket is worth what it holds: a full bracket of steps,
            // printed at writtenStep each and played at normal over actual,
            // lasts exactly as long as the bracket does. Read back off the
            // printed ratio rather than from the field that produced it, so a
            // ratio printed one way and applied another would show up here.
            int slash = bar.ratio().indexOf('/');
            int actual = Integer.parseInt(bar.ratio().substring(0, slash));
            int normal = Integer.parseInt(bar.ratio().substring(slash + 1));
            assertThat(actual).as("%s: bracket holds the ratio's own count", label)
                    .isEqualTo(bar.stepsPerBracket());
            assertThat(bar.writtenLength(actual) * normal / actual)
                    .as("%s: the bracket is worth what it holds", label)
                    .isEqualTo(bar.bracketLength());
        }
    }

    @Test
    @DisplayName("every length inside a bracket is a note value, so nothing has to be tied")
    void everySpanInsideABracketIsASingleValue() {
        for (TupletBar bar : everyTupletBar()) {
            for (int steps = 1; steps <= bar.stepsPerBracket(); steps++) {
                assertThat(LilyPondDuration.of(bar.writtenLength(steps)))
                        .as("%s %s: %d of %d steps", bar.meter(), bar.ratio(), steps,
                                bar.stepsPerBracket())
                        .isPresent();
            }
        }
    }

    @Test
    @DisplayName("a bracket boundary is always a whole number of 64ths, so the splitter accepts it")
    void bracketBoundariesLandOnTheGridTheSplitterInsistsOn() {
        // This is what lets an unbracketed stretch of a tuplet bar go to
        // MetricSplitter unchanged. If it ever stopped being true the splitter
        // would throw rather than mis-engrave, but it would throw on ordinary
        // music, so it is checked here instead.
        double shortest = LilyPondDuration.SHORTEST_QUARTERS;
        for (TupletBar bar : everyTupletBar()) {
            for (int bracket = 0; bracket <= bar.brackets(); bracket++) {
                double start = bar.bracketStart(bracket);
                assertThat(start)
                        .as("%s %s: bracket %d starts at %s, off the 64th-note grid",
                                bar.meter(), bar.ratio(), bracket, start)
                        .isEqualTo(Math.rint(start / shortest) * shortest);
            }
        }
    }

    @Test
    @DisplayName("a step survives the round trip through the beat axis, in every meter")
    void stepsRoundTripThroughPositions() {
        for (TupletBar bar : everyTupletBar()) {
            for (int step = 0; step <= bar.divisions(); step++) {
                assertThat(bar.stepAt(bar.beatOfStep(step)))
                        .as("%s %s: step %d", bar.meter(), bar.ratio(), step)
                        .isEqualTo(step);
            }
        }
    }

    @Test
    @DisplayName("a position built by adding lengths still lands on the step it was meant for")
    void accumulatedPositionsFindTheirStep() {
        // How the quantizer builds an offset: onset plus duration, both of which
        // are multiples of a step that is not representable. The result is not
        // the position multiplication reaches, and the difference is what the
        // rounding in stepAt exists for.
        for (TupletBar bar : everyTupletBar()) {
            for (int from = 0; from < bar.divisions(); from++) {
                // One note's worth from every step, and the whole bar from the
                // first: the error grows with the number of additions, so the
                // long walk is the one worth doing in full and the short ones
                // are what cover starting off the bar line.
                int last = from == 0 ? bar.divisions() : Math.min(from + 8, bar.divisions());
                double position = bar.beatOfStep(from);
                for (int step = from; step <= last; step++) {
                    assertThat(bar.stepAt(position))
                            .as("%s %s: %d steps on from %d", bar.meter(), bar.ratio(),
                                    step - from, from)
                            .isEqualTo(step);
                    position += bar.stepQuarters();
                }
            }
        }
    }

    @Test
    @DisplayName("the length from a step to the bar line is a duration LilyPond can read")
    void everyPickupLengthIsWritable() {
        for (TupletBar bar : everyTupletBar()) {
            for (int step = 0; step < bar.divisions(); step++) {
                String duration = bar.scaledLengthToBarLine(step);
                assertThat(LilyPondNotes.quartersOf(duration))
                        .as("%s %s: from step %d of %d", bar.meter(), bar.ratio(), step,
                                bar.divisions())
                        .isCloseTo((bar.divisions() - step) * bar.stepQuarters(), within(1e-12));
            }
        }
    }
}
