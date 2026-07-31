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

package dev.olivelli.musicwizard.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The active-set solver, against cases whose answer is known independently. */
class NonNegativeLeastSquaresTest {

    /** Squared residual of a candidate, which is the quantity being minimised. */
    private static double residual(double[][] design, double[] x, double[] y) {
        double total = 0;
        for (int row = 0; row < design.length; row++) {
            double predicted = 0;
            for (int column = 0; column < x.length; column++) {
                predicted += design[row][column] * x[column];
            }
            total += (predicted - y[row]) * (predicted - y[row]);
        }
        return total;
    }

    @Nested
    @DisplayName("solutions")
    class Solutions {

        @Test
        @DisplayName("recovers an exactly representable non-negative combination")
        void recoversAnExactCombination() {
            // Orthogonal columns, so the answer is a projection and can be read
            // off by hand: the coefficients are exactly the ones used to build y.
            double[][] design = {
                    {1, 0, 0},
                    {0, 1, 0},
                    {0, 0, 1},
            };
            double[] observation = {3, 0.5, 7};

            double[] x = new NonNegativeLeastSquares(design).solve(observation);

            assertThat(x).containsExactly(new double[] {3, 0.5, 7}, within(1e-9));
        }

        @Test
        @DisplayName("clamps a coefficient the unconstrained fit would make negative")
        void clampsACoefficientTheUnconstrainedFitWouldMakeNegative() {
            // Unconstrained, this is solved by x = (2, -1): the second column has
            // to be subtracted. The whole point of the constraint is that it may
            // not be, so the answer is the best fit on the boundary.
            double[][] design = {
                    {1, 1},
                    {0, 1},
            };
            double[] observation = {1, -1};

            double[] x = new NonNegativeLeastSquares(design).solve(observation);

            assertThat(x[1]).isZero();
            // With the second column pinned at zero the objective is
            // (x0 - 1)^2 + 1, whose minimum is at x0 = 1 with a residual of 1 --
            // the second row simply cannot be fitted without subtracting.
            assertThat(x[0]).isCloseTo(1.0, within(1e-8));
            assertThat(residual(design, x, observation)).isCloseTo(1.0, within(1e-8));
            // And it really is the constrained optimum rather than merely
            // feasible: both neighbouring feasible points score worse.
            assertThat(residual(design, x, observation))
                    .isLessThan(residual(design, new double[] {0, 0}, observation))
                    .isLessThan(residual(design, new double[] {2, 0}, observation));
        }

        @Test
        @DisplayName("never returns a negative coefficient, over random problems")
        void neverReturnsANegativeCoefficient() {
            Random random = new Random(20260730L);
            for (int trial = 0; trial < 200; trial++) {
                int rows = 4 + random.nextInt(8);
                int columns = 2 + random.nextInt(6);
                double[][] design = new double[rows][columns];
                for (int row = 0; row < rows; row++) {
                    for (int column = 0; column < columns; column++) {
                        design[row][column] = random.nextGaussian();
                    }
                }
                double[] observation = new double[rows];
                for (int row = 0; row < rows; row++) {
                    observation[row] = random.nextGaussian();
                }

                double[] x = new NonNegativeLeastSquares(design).solve(observation);

                assertThat(x).hasSize(columns);
                for (double value : x) {
                    assertThat(value).isGreaterThanOrEqualTo(0);
                }
            }
        }

        @Test
        @DisplayName("beats the unconstrained fit clamped at zero, which is the naive answer")
        void beatsTheClampedUnconstrainedFit() {
            // The tempting shortcut is to solve without the constraint and set
            // the negatives to zero. It is wrong, and this is a case where it is
            // visibly wrong: clamping leaves the surviving coefficients at values
            // chosen to work alongside a column that is no longer there.
            double[][] design = {
                    {1, 1},
                    {0, 1},
            };
            double[] observation = {1, -1};
            double[] clampedUnconstrained = {2, 0};

            double[] x = new NonNegativeLeastSquares(design).solve(observation);

            assertThat(residual(design, x, observation))
                    .isLessThan(residual(design, clampedUnconstrained, observation));
        }

        @Test
        @DisplayName("survives duplicate columns, which the note dictionary is full of")
        void survivesDuplicateColumns() {
            // Two notes an octave apart share every partial of the upper one, so
            // near-parallel columns are the normal case rather than a pathology.
            // Exactly parallel is the limit of it, and must not produce an
            // enormous coefficient or a NaN.
            double[][] design = {
                    {1, 1, 0},
                    {2, 2, 1},
            };
            double[] observation = {4, 9};

            double[] x = new NonNegativeLeastSquares(design).solve(observation);

            for (double value : x) {
                assertThat(value).isFinite().isGreaterThanOrEqualTo(0);
            }
            // The two duplicates together must still reproduce the data as well
            // as one of them alone could.
            assertThat(residual(design, x, observation)).isLessThan(1e-6);
        }

        @Test
        @DisplayName("returns all zeros when every column would have to be subtracted")
        void returnsZeroWhenNoColumnHelps() {
            double[][] design = {
                    {1, 0},
                    {0, 1},
            };

            double[] x = new NonNegativeLeastSquares(design).solve(new double[] {-1, -2});

            assertThat(x).containsExactly(new double[] {0, 0}, within(1e-12));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a design that is not finite")
        void rejectsANonFiniteDesign() {
            // A NaN column is the quiet failure: NaN loses every comparison the
            // active-set search makes, so it is simply never selected and the fit
            // silently ignores a note.
            double[][] design = {{1, Double.NaN}, {0, 1}};

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NonNegativeLeastSquares(design))
                    .withMessageContaining("design[0][1]");
        }

        @Test
        @DisplayName("rejects a ragged design")
        void rejectsARaggedDesign() {
            double[][] design = {{1, 2}, {3}};

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NonNegativeLeastSquares(design))
                    .withMessageContaining("design[1]");
        }

        @Test
        @DisplayName("rejects an observation of the wrong length")
        void rejectsAnObservationOfTheWrongLength() {
            NonNegativeLeastSquares solver =
                    new NonNegativeLeastSquares(new double[][] {{1, 0}, {0, 1}});

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> solver.solve(new double[] {1, 2, 3}))
                    .withMessageContaining("expected 2");
        }

        @Test
        @DisplayName("rejects a non-finite observation")
        void rejectsANonFiniteObservation() {
            NonNegativeLeastSquares solver =
                    new NonNegativeLeastSquares(new double[][] {{1, 0}, {0, 1}});

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> solver.solve(new double[] {1, Double.POSITIVE_INFINITY}))
                    .withMessageContaining("observation[1]");
        }

        @Test
        @DisplayName("does not retain the caller's design array")
        void doesNotRetainTheDesign() {
            double[][] design = {{1, 0}, {0, 1}};
            NonNegativeLeastSquares solver = new NonNegativeLeastSquares(design);

            design[0][0] = 1000;

            assertThat(solver.solve(new double[] {3, 4}))
                    .containsExactly(new double[] {3, 4}, within(1e-9));
        }

        @Test
        @DisplayName("holds no state between solves")
        void holdsNoStateBetweenSolves() {
            NonNegativeLeastSquares solver =
                    new NonNegativeLeastSquares(new double[][] {{1, 1}, {0, 1}});

            double[] first = solver.solve(new double[] {1, -1});
            double[] repeat = solver.solve(new double[] {1, -1});
            solver.solve(new double[] {5, 5});
            double[] again = solver.solve(new double[] {1, -1});

            assertThat(repeat).containsExactly(first, within(1e-12));
            assertThat(again).containsExactly(first, within(1e-12));
        }
    }
}
