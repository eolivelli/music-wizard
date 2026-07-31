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

import java.util.Objects;

/**
 * Solves {@code min ‖Ex − y‖²} subject to {@code x ≥ 0} by the Lawson–Hanson
 * active-set method.
 *
 * <p>The non-negativity is the whole point, not a technicality. {@code E} here
 * is a dictionary of idealised note spectra and {@code y} is an observed
 * spectrum, so a negative activation would mean a note cancelling another note's
 * partials — which is not a thing sound does. An unconstrained least squares
 * fits the same data better and returns an answer that is physically meaningless
 * and, worse, dense: every note slightly on, which is exactly the flat chroma
 * this front end exists to avoid.
 *
 * <p>The method keeps a <em>passive set</em> of columns allowed to be non-zero,
 * solves an ordinary least squares restricted to it, and either accepts the
 * result or backs off along the segment towards it until the first coordinate
 * hits zero. It terminates because each outer iteration strictly decreases the
 * residual and there are finitely many passive sets.
 *
 * <p>One design point matters for speed. The textbook formulation recomputes
 * {@code Eᵀ(y − Ex)} against the full {@code E} every iteration, which is
 * {@code O(rows · columns)} per iteration and is repeated for every frame of a
 * recording — several thousand solves against the same {@code E}. Following
 * Bro and de Jong's "fast NNLS", the normal-equation quantities {@code EᵀE} and
 * {@code Eᵀy} are formed once and everything after that works in
 * {@code columns} dimensions only. {@code EᵀE} depends solely on the dictionary,
 * so it is computed once per instance and reused across every frame; only
 * {@code Eᵀy} is per-frame. On the 262×77 dictionary this front end uses, that
 * is the difference between a solve costing tens of thousands of multiplies and
 * one costing millions.
 *
 * <p>Instances are immutable and safe to share; {@link #solve} allocates its own
 * working state and holds none between calls.
 */
public final class NonNegativeLeastSquares {

    /**
     * Ridge added to the diagonal before factorising, relative to the mean
     * diagonal of {@code EᵀE}.
     *
     * <p>Adjacent note profiles share most of their partials, so the Gram matrix
     * is badly conditioned by construction — a C and its octave differ only in
     * the odd harmonics. Without this, the Cholesky of a passive set holding two
     * near-parallel columns fails or returns something enormous, and a single
     * frame emits an activation of 1e12. It is small enough not to bias the
     * fit and large enough to keep the factorisation real.
     */
    private static final double RIDGE = 1e-10;

    /** Gradient below which no column is worth admitting to the passive set. */
    private static final double TOLERANCE = 1e-10;

    private final int rows;
    private final int columns;

    /** {@code Eᵀ}, {@code [column][row]}: the layout the per-frame matvec wants. */
    private final double[][] transposedDesign;

    /** {@code EᵀE}, computed once because it does not depend on the frame. */
    private final double[][] gram;

    private final double ridge;

    /**
     * Prepares a solver for a fixed design matrix.
     *
     * @param design {@code E}, {@code [row][column]}, copied defensively
     * @throws IllegalArgumentException if it is empty or ragged, or holds a
     *     non-finite entry — a NaN in the dictionary would propagate into every
     *     frame's activations silently, since NaN fails every comparison the
     *     active-set search makes and so simply never gets selected
     */
    public NonNegativeLeastSquares(double[][] design) {
        Objects.requireNonNull(design, "design");
        if (design.length == 0) {
            throw new IllegalArgumentException("design must have at least one row");
        }
        this.rows = design.length;
        this.columns = design[0] == null ? 0 : design[0].length;
        if (columns == 0) {
            throw new IllegalArgumentException("design must have at least one column");
        }
        this.transposedDesign = new double[columns][rows];
        for (int row = 0; row < rows; row++) {
            double[] values = design[row];
            if (values == null || values.length != columns) {
                throw new IllegalArgumentException("design[" + row + "] has "
                        + (values == null ? "null" : values.length + " columns")
                        + ", expected " + columns);
            }
            for (int column = 0; column < columns; column++) {
                if (!Double.isFinite(values[column])) {
                    throw new IllegalArgumentException("design[" + row + "][" + column
                            + "] is " + values[column] + "; the design must be finite");
                }
                transposedDesign[column][row] = values[column];
            }
        }

        this.gram = new double[columns][columns];
        double trace = 0;
        for (int i = 0; i < columns; i++) {
            for (int j = i; j < columns; j++) {
                double sum = 0;
                double[] left = transposedDesign[i];
                double[] right = transposedDesign[j];
                for (int row = 0; row < rows; row++) {
                    sum += left[row] * right[row];
                }
                gram[i][j] = sum;
                gram[j][i] = sum;
            }
            trace += gram[i][i];
        }
        // Scaled to the matrix rather than absolute: the dictionary's overall
        // amplitude is a free choice, and an absolute ridge would be a no-op at
        // one scaling and dominate at another.
        this.ridge = RIDGE * Math.max(trace / columns, Double.MIN_NORMAL);
    }

    /** Number of columns in the design, which is the length of a solution. */
    public int columns() {
        return columns;
    }

    /** Number of rows in the design, which is the length of an observation. */
    public int rows() {
        return rows;
    }

    /**
     * Solves for one observation.
     *
     * @param observation {@code y}, of length {@link #rows()}
     * @return {@code x ≥ 0}, of length {@link #columns()}, freshly allocated
     * @throws IllegalArgumentException if the observation is the wrong length or
     *     holds a non-finite value
     */
    public double[] solve(double[] observation) {
        Objects.requireNonNull(observation, "observation");
        if (observation.length != rows) {
            throw new IllegalArgumentException("observation has " + observation.length
                    + " values, expected " + rows);
        }
        for (int row = 0; row < rows; row++) {
            if (!Double.isFinite(observation[row])) {
                throw new IllegalArgumentException("observation[" + row + "] is "
                        + observation[row] + "; the observation must be finite");
            }
        }

        double[] correlation = new double[columns];
        for (int column = 0; column < columns; column++) {
            double sum = 0;
            double[] basis = transposedDesign[column];
            for (int row = 0; row < rows; row++) {
                sum += basis[row] * observation[row];
            }
            correlation[column] = sum;
        }
        return solveNormalEquations(correlation);
    }

    /**
     * The active-set loop itself, in {@code columns} dimensions.
     *
     * <p>Split out from {@link #solve} because it is the whole algorithm, and
     * because the projection onto the dictionary is the only part that touches
     * the observation.
     */
    private double[] solveNormalEquations(double[] correlation) {
        double[] x = new double[columns];
        boolean[] passive = new boolean[columns];
        int passiveCount = 0;

        // The negative gradient of the objective at x: how much admitting each
        // still-zero column would reduce the residual.
        double[] gradient = correlation.clone();

        // Lawson and Hanson prove termination, but on a rank-deficient Gram a
        // coordinate can be admitted and evicted repeatedly at round-off scale.
        // Three passes over the columns is far more than a real frame needs --
        // measured over a whole recording the passive set never exceeded a
        // couple of dozen -- and turns a hang into a slightly-suboptimal fit.
        int maxOuterIterations = 3 * columns;

        for (int outer = 0; outer < maxOuterIterations && passiveCount < columns; outer++) {
            int candidate = -1;
            double best = TOLERANCE;
            for (int column = 0; column < columns; column++) {
                if (!passive[column] && gradient[column] > best) {
                    best = gradient[column];
                    candidate = column;
                }
            }
            if (candidate < 0) {
                break;
            }

            passive[candidate] = true;
            passiveCount++;

            double[] trial = leastSquaresOnPassiveSet(correlation, passive, passiveCount);
            if (trial == null) {
                // The passive set with this column is numerically singular even
                // with the ridge. Refusing the column is the conservative
                // answer: it leaves x feasible and the objective no worse.
                passive[candidate] = false;
                passiveCount--;
                gradient[candidate] = 0;
                continue;
            }

            // Inner loop: while the unconstrained solution leaves the feasible
            // region, walk towards it only as far as the first zero crossing and
            // drop whatever landed on the boundary.
            int innerGuard = 0;
            while (innerGuard++ <= columns) {
                double smallest = Double.POSITIVE_INFINITY;
                for (int column = 0; column < columns; column++) {
                    if (passive[column]) {
                        smallest = Math.min(smallest, trial[column]);
                    }
                }
                if (smallest > 0) {
                    break;
                }

                double alpha = 1;
                for (int column = 0; column < columns; column++) {
                    if (passive[column] && trial[column] <= 0) {
                        double denominator = x[column] - trial[column];
                        // x is feasible and trial[column] <= 0, so the
                        // denominator is non-negative; it is zero only when the
                        // coordinate is already pinned at zero, in which case
                        // the step length is zero and the column is evicted
                        // below without moving x at all.
                        double step = denominator > 0 ? x[column] / denominator : 0;
                        alpha = Math.min(alpha, step);
                    }
                }

                for (int column = 0; column < columns; column++) {
                    x[column] = passive[column] ? x[column] + alpha * (trial[column] - x[column]) : 0;
                }
                for (int column = 0; column < columns; column++) {
                    if (passive[column] && x[column] <= TOLERANCE) {
                        passive[column] = false;
                        passiveCount--;
                        x[column] = 0;
                    }
                }
                if (passiveCount == 0) {
                    trial = new double[columns];
                    break;
                }
                double[] next = leastSquaresOnPassiveSet(correlation, passive, passiveCount);
                if (next == null) {
                    // Same conservative answer as above: keep the last feasible
                    // x rather than an unfactorisable one.
                    trial = x.clone();
                    break;
                }
                trial = next;
            }

            x = trial;
            for (int column = 0; column < columns; column++) {
                if (!passive[column]) {
                    x[column] = 0;
                }
            }

            // gradient = Eᵀy − EᵀE x, recomputed from x rather than updated
            // incrementally: the incremental form accumulates the error of every
            // step taken so far, and this runs to convergence per frame.
            for (int column = 0; column < columns; column++) {
                double sum = correlation[column];
                double[] gramRow = gram[column];
                for (int k = 0; k < columns; k++) {
                    if (x[k] != 0) {
                        sum -= gramRow[k] * x[k];
                    }
                }
                gradient[column] = sum;
            }
        }
        return x;
    }

    /**
     * Ordinary least squares restricted to the passive columns, by Cholesky of
     * the corresponding sub-block of {@code EᵀE}.
     *
     * @return a full-length vector, zero outside the passive set, or null when
     *     the sub-block is not positive definite even after the ridge
     */
    private double[] leastSquaresOnPassiveSet(double[] correlation, boolean[] passive, int size) {
        int[] index = new int[size];
        int next = 0;
        for (int column = 0; column < columns; column++) {
            if (passive[column]) {
                index[next++] = column;
            }
        }

        double[][] sub = new double[size][size];
        double[] rhs = new double[size];
        for (int i = 0; i < size; i++) {
            rhs[i] = correlation[index[i]];
            double[] gramRow = gram[index[i]];
            for (int j = 0; j < size; j++) {
                sub[i][j] = gramRow[index[j]];
            }
            sub[i][i] += ridge;
        }

        double[] solution = choleskySolve(sub, rhs);
        if (solution == null) {
            return null;
        }
        double[] out = new double[columns];
        for (int i = 0; i < size; i++) {
            if (!Double.isFinite(solution[i])) {
                return null;
            }
            out[index[i]] = solution[i];
        }
        return out;
    }

    /**
     * Solves {@code Ax = b} for symmetric positive definite {@code A}, in place
     * over a copy.
     *
     * @return the solution, or null if {@code A} is not positive definite
     */
    private static double[] choleskySolve(double[][] a, double[] b) {
        int n = b.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= a[i][k] * a[j][k];
                }
                if (i == j) {
                    if (!(sum > 0)) {
                        return null;
                    }
                    a[i][i] = Math.sqrt(sum);
                } else {
                    a[i][j] = sum / a[j][j];
                }
            }
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = b[i];
            for (int k = 0; k < i; k++) {
                sum -= a[i][k] * y[k];
            }
            y[i] = sum / a[i][i];
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = y[i];
            for (int k = i + 1; k < n; k++) {
                sum -= a[k][i] * x[k];
            }
            x[i] = sum / a[i][i];
        }
        return x;
    }
}
