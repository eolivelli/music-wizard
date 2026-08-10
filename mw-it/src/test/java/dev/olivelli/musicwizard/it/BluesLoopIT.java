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

package dev.olivelli.musicwizard.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.Chord;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.ChordQuality;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The committed tier-2 gate: a real recording, measured against changes that
 * are known exactly rather than inferred.
 *
 * <p>{@code samples/g-blues-shuffle-cc.mp3} is a twelve-bar blues in G played
 * round and round — {@code samples/list.txt} records the sequence and where the
 * recording came from, and the file is committed and does not change, so the
 * ground truth is a constant rather than an estimate. That combination is rare
 * enough to be worth a gate: every other accuracy number in this repository
 * comes from audio we synthesised, and {@code CLAUDE.md} is blunt about what
 * those are worth.
 *
 * <p>What it is guarding is a regression that already happened once. Before #3
 * a recording like this one produced a single {@code N.C.} span covering all of
 * it — the failure #185 describes — and nothing in the suite noticed, because
 * the synthetic fixtures were all green. Thresholds here are therefore set well
 * below what is measured, so that this fails when the pipeline stops working
 * rather than when it changes.
 *
 * <p>{@code *IT}, so it runs only under {@code -Pintegration}. One analysis
 * serves every assertion.
 *
 * <p>This is the committed gate and not the whole picture. {@code
 * tools/score-samples.py} scores the same question over every benchmark whose
 * ground truth is known, several of which are local-only, with bars taken from
 * the tracked beat grid rather than from this file's measured loop. Its current
 * reading is {@code tools/baselines/score-samples.txt}, which is not restated
 * here: it moves whenever the estimator does, and a copy of it in this comment
 * has already gone stale once.
 *
 * <p>This recording replaced {@code gmajorblues.mp3}, which had no licence or
 * provenance and could not stay committed under the project's own tier-2 rule
 * (#204). It opens with a sparse intro, which the tracker used to read at half
 * rate — about a dozen pulses missing and the end-to-end rate under-reading by
 * roughly two percent. #292 corrects the seed that caused it, and the rate
 * assertion below is tight against the loop again rather than banded around the
 * defect.
 */
class BluesLoopIT {

    /**
     * The changes, from {@code samples/list.txt}, as pitch classes with C = 0.
     *
     * <p>G7 G7 G7 G7 / C7 C7 G7 G7 / D7 C7 G7 D7 — a twelve-bar blues with the
     * standard turnaround.
     */
    private static final int[] CYCLE = {7, 7, 7, 7, 0, 0, 7, 7, 2, 0, 7, 2};

    /** The recording's own rate, in beats a minute — the measurement itself. */
    private static final double LOOP_TEMPO = 105.0;

    /**
     * How long one twelve-bar cycle lasts, in seconds.
     *
     * <p>A property of this recording, measured from it rather than assumed:
     * {@code tools/ScoreBeats.java} finds the onset envelope's autocorrelation
     * peak at 105.000 beats a minute, which over 48 beats is this, and about
     * eleven and a half cycles fill the recording.
     *
     * <p>Bars are taken from this rather than from the tracked beat grid, and
     * that is what lets the two be compared. Scoring chords through the tracker
     * would measure beat tracking and chord recognition together and blame
     * whichever was changed last; here the tracker is what the loop axis is
     * evidence about.
     *
     * <p>Per-bar accuracy is flat within a few hundredths of a second of this
     * value and falls away steeply outside it, so the axis is not balanced on
     * the last digit.
     */
    private static final double CYCLE_SECONDS = 48 * 60.0 / LOOP_TEMPO;

    private static Score score;
    private static Path sample;

    @BeforeAll
    static void transcribeOnce() {
        sample = locateSample();
        score = new AudioTranscriber().transcribe(sample, AudioTranscriber.Options.defaults());
    }

    /**
     * Finds the sample by walking up from the working directory, which surefire
     * sets to the module rather than the repository.
     */
    private static Path locateSample() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("samples").resolve("g-blues-shuffle-cc.mp3");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "samples/g-blues-shuffle-cc.mp3 was not found above "
                        + System.getProperty("user.dir")
                        + "; it is committed, so this means the checkout is incomplete");
    }

    @Test
    @DisplayName("the recording is not one long no-chord span")
    void theRecordingIsNotOneLongNoChordSpan() {
        // The #185 failure in its plainest form, and the one this file exists
        // for. In that state both numbers below are on the far side of their
        // bounds: one span, 100% of the duration.
        ChordProgression chords = score.chords();
        double noChordSeconds = 0;
        for (Chord chord : chords.chords()) {
            if (chord.isNoChord()) {
                noChordSeconds += chord.durationSeconds();
            }
        }

        assertThat(100 * noChordSeconds / score.durationSeconds())
                .as("share of the recording labelled N.C.")
                .isLessThan(10.0);
        // Eleven cycles of three chords cannot be fewer than a few dozen spans.
        // The upper bound is not idle either -- one span per beat would be about
        // 540, and a decoder that chatters that badly has stopped smoothing. The
        // count itself is not asserted because every change to the beat grid or
        // to how spans are merged moves it.
        assertThat(chords.size()).isBetween(60, 450);
    }

    @Test
    @DisplayName("the three chords of the blues are all found, and as sevenths")
    void theThreeChordsAreAllFound() {
        List<String> symbols = score.chords().chords().stream().map(Chord::symbol).toList();

        // Not merely present: present often. A single stray G7 among two hundred
        // spans would satisfy contains() and mean nothing.
        assertThat(symbols).filteredOn("G7"::equals).hasSizeGreaterThan(25);
        assertThat(symbols).filteredOn("C7"::equals).hasSizeGreaterThan(8);
        assertThat(symbols).filteredOn("D7"::equals).hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("most bars carry the chord the twelve-bar cycle says they should")
    void mostBarsCarryTheRightChord() {
        Labelling labelling = labelBars(CYCLE_SECONDS);

        // The floors are set well under what this scores: a gate against the
        // pipeline breaking, not a record of its best day.
        //
        // 58.3% is the number to beat rather than 0%, because seven of the
        // twelve bars are the tonic, so writing G7 in every bar scores that much
        // on both columns while being no transcription at all. The root floor
        // clears it and the quality floor does not, which is honest rather than
        // lax: on this recording the tonic is often heard as a plain triad, so
        // the quality column runs only a little above what G7-everywhere would
        // score. What refuses that labelling is
        // #theSubdominantAndDominantAreFound, not this.
        assertThat(labelling.rootAccuracy())
                .as("bars whose root matches the cycle")
                .isGreaterThan(78.0);
        assertThat(labelling.rootAndQualityAccuracy())
                .as("bars whose root and quality both match")
                .isGreaterThan(55.0);
    }

    @Test
    @DisplayName("the IV and the V are found, not just the tonic")
    void theSubdominantAndDominantAreFound() {
        // The assertion that stops "G7 everywhere" from passing the one above.
        // The IV is the weakest of the three here and the V the strongest, which
        // is the other way round from the recording this replaced: the two D7
        // bars are the turnaround, which is the hardest position in the cycle to
        // catch, and on this one they are caught.
        Labelling labelling = labelBars(CYCLE_SECONDS);

        assertThat(labelling.recall(7)).as("G7 bars found").isGreaterThan(80.0);
        assertThat(labelling.recall(0)).as("C7 bars found").isGreaterThan(62.0);
        assertThat(labelling.recall(2)).as("D7 bars found").isGreaterThan(85.0);
    }

    @Test
    @DisplayName("the tracked tempo is close to the loop's own")
    void theTrackedTempoIsCloseToTheLoops() {
        // The end-to-end rate over every tracked beat, which is exactly the mean
        // interval, so an inserted or dropped pulse moves it. That is what this
        // gates -- #196, where a spacing penalty a forty-eighth of the published
        // one left the grid for every loud offbeat and came back a beat later.
        //
        // Written out rather than calling BeatGrid.steadyPulseRate, deliberately:
        // steadyPulseRate is built not to notice a dropped pulse, which is right
        // for spacing a bar line and wrong here. Substituting it would quietly
        // retire #196's gate.
        //
        // This band used to run down to 101.5, to accommodate the sparse intro
        // being tracked at half rate: the missing pulses are in the end-to-end
        // rate by construction, since the mean interval is that rate. #292
        // removed the cause, and the measured figure moved from 102.99 to
        // 104.91 against the loop's own 105.0 -- from two percent under to
        // under a tenth of one. The lower edge follows it down to a percent,
        // which is the room a dropped pulse or two still needs; the upper edge
        // is unchanged, since nothing explains the tracker running fast here.
        List<Double> beats = score.beatGrid().map(BeatGrid::beatTimes).orElseThrow();
        double tracked = 60.0 * (beats.size() - 1)
                / (beats.get(beats.size() - 1) - beats.get(0));

        assertThat(tracked).isBetween(103.9, 105.6);
    }

    @Test
    @DisplayName("the tempo the chart is spaced at is the recording's, not the grid's median")
    void theSpacingTempoAgreesWithTheLoop() {
        // #200. Score.estimatedTempo() is what ChartLayout divides by to place
        // every bar line after the first, and what the chart header, the staff
        // layout and the MusicXML export all print -- so an error here is an
        // error in every one of them at once, and it compounds with the bar
        // index rather than staying put.
        //
        // Scored against this file's own axis, which is the point: the loop
        // period is measured from the recording rather than taken from the
        // tracker, so this asks whether the printed tempo is the music's rather
        // than whether it agrees with the beat grid. #207 was refuted for
        // measuring the second and calling it the first.
        //
        // The band is 0.4% either way: wide enough that the loop period being
        // itself a measurement does not decide the outcome, narrow enough that
        // passing it means the bar lines stay within a beat of the music over a
        // twelve-bar cycle. The median interval the accessor used to return is
        // outside it, so this distinguishes the two rather than passing on
        // either.
        double spacing = score.estimatedTempo();
        double median = score.beatGrid().orElseThrow()
                .medianTempo(score.tempoMap().initialTimeSignature());

        assertThat(spacing)
                .as("the tempo the chart spaces its bars at")
                .isBetween(LOOP_TEMPO * 0.996, LOOP_TEMPO * 1.004);
        // And it is nearer the music than the statistic it replaced, which is a
        // comparison rather than a band and so cannot pass by a lucky constant.
        assertThat(Math.abs(spacing - LOOP_TEMPO))
                .as("distance from the loop's own tempo, against the median interval's")
                .isLessThan(Math.abs(median - LOOP_TEMPO));
    }

    @Test
    @DisplayName("the tracked beats are one beat apart, not two thirds of one")
    void theTrackedBeatsAreOneBeatApart() {
        // #196's mechanism, where the tempo band above only sees its
        // consequence. This recording is a shuffle, so its loudest events are
        // the swung eighths two thirds of the way through each beat. While the
        // spacing penalty was too small the dynamic program left the grid for
        // them and came back a beat later, and an interval histogram says so
        // plainly: the detour population sits at two thirds of a beat and the
        // catch-up at four thirds.
        //
        // Worth a test of its own because it cannot be traded against anything:
        // a tempo that is right on average is compatible with a grid that is
        // wrong beat by beat, and that is precisely the state #196 found. Both
        // bounds sit between the two populations rather than beside either.
        //
        // The converse holds too, so this and #theTrackedTempoIsCloseToTheLoops
        // are load-bearing together and neither covers the other. Intervals here
        // are measured against the tracked grid's own median, so a grid that was
        // uniformly an octave out would read 100% on grid and pass; it is the
        // tempo band, which compares against the loop, that refuses that. Do not
        // relax either on the grounds that the other has it.
        List<Double> beats = score.beatGrid().map(BeatGrid::beatTimes).orElseThrow();
        double[] intervals = new double[beats.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beats.get(i + 1) - beats.get(i);
        }
        double[] sorted = intervals.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted.length % 2 == 1
                ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;

        int onGrid = 0;
        int detours = 0;
        for (double interval : intervals) {
            double ratio = interval / median;
            if (Math.abs(ratio - 1) < 0.10) {
                onGrid++;
            } else if (Math.abs(ratio - 2.0 / 3) < 0.10) {
                detours++;
            }
        }

        assertThat(100.0 * onGrid / intervals.length)
                .as("share of tracked intervals within a tenth of the median")
                .isGreaterThan(85.0);
        assertThat(100.0 * detours / intervals.length)
                .as("share that are the swung eighth's two thirds of a beat")
                .isLessThan(8.0);
    }

    @Test
    @DisplayName("the bar grid from the tracked downbeats agrees with the loop's")
    void theBarGridFromTheTrackedBeatsAgreesWithTheLoop() {
        // Bars between consecutive tracked downbeats: the axis the chart takes
        // its phase from, and the axis tools/score-samples.py scores every
        // benchmark on. Deliberately not four beats counted from the first one.
        // That is a different question on this recording and gets a much worse
        // answer, because the pulses dropped through the intro are not a
        // multiple of four and every bar line after them falls inside a bar of
        // the music (#292). The downbeat estimator does not inherit that error,
        // and this is where that is asserted rather than assumed.
        //
        // Agreement rather than a floor of its own, because that is the property
        // that says the beat grid is right. A floor could be met by both axes
        // drifting together; a gap of a few points cannot be, since the loop's
        // axis is measured from the recording and does not move when the tracker
        // does.
        List<Double> downbeats = score.beatGrid().map(BeatGrid::downbeatTimes).orElseThrow();
        int bars = downbeats.size() - 1;
        int[] roots = new int[bars];
        for (int bar = 0; bar < bars; bar++) {
            roots[bar] = rootOverlapping(downbeats.get(bar), downbeats.get(bar + 1));
        }
        double tracked = bestRotationAccuracy(roots, bars);
        double loop = labelBars(CYCLE_SECONDS).rootAccuracy();

        assertThat(tracked)
                .as("bars from the tracked downbeats whose root matches")
                .isGreaterThan(75.0);
        // Two-sided: the tracked axis running *ahead* of the loop's would mean
        // the loop constant is wrong, not that beat tracking got better, and
        // that is worth failing on too.
        assertThat(Math.abs(loop - tracked))
                .as("how far the tracked bar grid is from the loop's")
                .isLessThan(8.0);
    }

    // ---- scoring ----

    /** Per-bar labels over the loop, and what they score against the cycle. */
    private record Labelling(int[] roots, boolean[] sevenths, int bars, int rotation) {

        double rootAccuracy() {
            int matches = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (roots[bar] == CYCLE[(bar + rotation) % 12]) {
                    matches++;
                }
            }
            return 100.0 * matches / bars;
        }

        double rootAndQualityAccuracy() {
            int matches = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (roots[bar] == CYCLE[(bar + rotation) % 12] && sevenths[bar]) {
                    matches++;
                }
            }
            return 100.0 * matches / bars;
        }

        /** Share of the bars that should hold {@code root} where it was found. */
        double recall(int root) {
            int wanted = 0;
            int found = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (CYCLE[(bar + rotation) % 12] != root) {
                    continue;
                }
                wanted++;
                if (roots[bar] == root) {
                    found++;
                }
            }
            return wanted == 0 ? 0 : 100.0 * found / wanted;
        }
    }

    /**
     * Labels each bar of the loop with the chord covering most of it.
     *
     * <p>The loop's phase is not known — the recording does not start on bar one
     * of a cycle and nothing says where it does — so every rotation is scored
     * and the best is taken. That is a free parameter with twelve values against
     * a hundred and thirty-odd bars, which is not enough freedom to manufacture
     * a result: labelling every bar G7 scores 58.3% at whichever rotation, and a
     * random labelling about 8%.
     */
    private Labelling labelBars(double cycleSeconds) {
        double barSeconds = cycleSeconds / 12;
        int bars = (int) Math.floor(score.durationSeconds() / barSeconds);
        int[] roots = new int[bars];
        boolean[] sevenths = new boolean[bars];
        for (int bar = 0; bar < bars; bar++) {
            double from = bar * barSeconds;
            Chord chord = chordOverlapping(from, from + barSeconds);
            roots[bar] = chord == null || chord.isNoChord() ? -1 : chord.pitchClasses()[0];
            // The cycle is dominant sevenths, so that is what a bar has to
            // carry: hasSeventh() would credit the minor seventh too, and the
            // audio path can report one since #272.
            sevenths[bar] = chord != null
                    && chord.quality() == ChordQuality.DOMINANT_SEVENTH;
        }
        int rotation = bestRotation(roots, bars);
        return new Labelling(roots, sevenths, bars, rotation);
    }

    private Chord chordOverlapping(double from, double to) {
        Chord best = null;
        double bestOverlap = 0;
        for (Chord chord : score.chords().chords()) {
            double overlap = Math.min(to, chord.endSeconds()) - Math.max(from, chord.startSeconds());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = chord;
            }
        }
        return best;
    }

    private int rootOverlapping(double from, double to) {
        Chord chord = chordOverlapping(from, to);
        return chord == null || chord.isNoChord() ? -1 : chord.pitchClasses()[0];
    }

    private static int bestRotation(int[] roots, int bars) {
        int best = 0;
        int bestMatches = -1;
        for (int rotation = 0; rotation < 12; rotation++) {
            int matches = 0;
            for (int bar = 0; bar < bars; bar++) {
                if (roots[bar] == CYCLE[(bar + rotation) % 12]) {
                    matches++;
                }
            }
            if (matches > bestMatches) {
                bestMatches = matches;
                best = rotation;
            }
        }
        return best;
    }

    private static double bestRotationAccuracy(int[] roots, int bars) {
        int rotation = bestRotation(roots, bars);
        int matches = 0;
        for (int bar = 0; bar < bars; bar++) {
            if (roots[bar] == CYCLE[(bar + rotation) % 12]) {
                matches++;
            }
        }
        return 100.0 * matches / bars;
    }
}
