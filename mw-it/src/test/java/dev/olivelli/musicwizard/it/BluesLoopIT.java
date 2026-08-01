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
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.transcribe.AudioTranscriber;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The project's first tier-2 gate: a real recording, measured against changes
 * that are known exactly rather than inferred.
 *
 * <p>{@code samples/gmajorblues.mp3} is 711 seconds of a twelve-bar blues in G
 * played round and round — {@code samples/list.txt} records the sequence, and
 * this file is committed and does not change, so the ground truth is a constant
 * rather than an estimate. That combination is rare enough to be worth a gate:
 * every other accuracy number in this repository comes from audio we
 * synthesised, and {@code CLAUDE.md} is blunt about what those are worth.
 *
 * <p>What it is guarding is a regression that already happened once. Before #3
 * this recording produced a single chord span, {@code N.C.}, covering all 711
 * seconds — the failure #185 describes — and nothing in the suite noticed,
 * because the synthetic fixtures were all green. Thresholds here are therefore
 * set well below what is measured, so that this fails when the pipeline stops
 * working rather than when it changes.
 *
 * <p>{@code *IT}, so it runs only under {@code -Pintegration}: it decodes and
 * analyses twelve minutes of audio. One analysis serves every assertion, which
 * is what keeps it to about seven seconds.
 *
 * <p>The recording it depends on carries no licence or provenance note in the
 * repository (#204). That is a question about the file rather than about this
 * test, and it is the reason this comment mentions it: a gate that depends on a
 * committed binary should say where the binary stands.
 *
 * <p>This is the committed gate and not the whole picture, and the difference
 * is large enough to say here. {@code tools/score-samples.py} scores the same
 * question over every benchmark whose ground truth is known, three of which are
 * local-only. Through the shipped CLI, with bars taken from the tracked beat
 * grid rather than from this file's measured loop:
 *
 * <pre>
 *   recording                     root    root+quality    before #196
 *   gmajorblues.mp3               84.1%      84.1%       50.2%   48.9%
 *   blues-a-90bpm.mp3             87.6%      84.1%       88.5%   83.2%
 *   blues-shuffle-a-106bpm.mp3    94.4%      16.8%       50.7%    3.3%
 *   blues-e-90bpm.mp3             99.1%      10.8%       48.7%    5.1%
 *   bossa-cm.mp3                  15.3%       2.4%       14.2%    1.9%
 * </pre>
 *
 * <p>Every one of those was 0.0% before #3, on a pipeline returning one N.C.
 * span per recording, so the direction is not in doubt. Two things in the
 * spread are worth a maintainer's attention rather than a footnote. The gap
 * between the first column and the 85.7% this file measures used to be the beat
 * grid drifting; #196 closed it, which is what moved four of the five rows and
 * left {@code blues-a-90bpm.mp3} — the one recording whose grid was already
 * right — where it was, one bar down. And the collapse of the quality column on
 * two of the blues tracks is the estimator finding the right roots and calling
 * their sevenths plain triads (#208).
 *
 * <p>{@code bossa-cm.mp3} is the row that is not about bars at all: the tempo
 * estimator reads that recording at about four thirds of its true rate, so
 * there is no phase at which its bars can be right. That is #231 and it is
 * untouched by this file.
 */
class BluesLoopIT {

    /**
     * The changes, from {@code samples/list.txt}, as pitch classes with C = 0.
     *
     * <p>G7 G7 G7 G7 / C7 C7 G7 G7 / D7 C7 G7 D7 — a twelve-bar blues with the
     * standard turnaround.
     */
    private static final int[] CYCLE = {7, 7, 7, 7, 0, 0, 7, 7, 2, 0, 7, 2};

    /**
     * How long one twelve-bar cycle lasts, in seconds.
     *
     * <p>A property of this recording, measured from it rather than assumed:
     * the chroma's self-similarity over lag peaks at 27.15 s, which is 106.1
     * beats a minute in four, and 26.2 cycles fill the recording.
     *
     * <p>Bars are taken from this rather than from the tracked beat grid, and
     * the distinction still matters even now that the two agree. Until #196 the
     * tracker reported 108.1 BPM, about 2% fast, so its bars slid against the
     * music by a whole beat every cycle and by many bars over twelve minutes;
     * scoring chords through that grid would have measured beat tracking and
     * chord recognition together and blamed whichever was changed last. Keeping
     * this axis independent of the tracker is what lets {@link
     * #theBarGridFromTheTrackedBeatsAgreesWithTheLoop} say something: the two
     * are now within a couple of points of each other, and that is a claim
     * about beat tracking that a shared axis could not have made.
     *
     * <p>The score is sensitive to this figure, because an error compounds over
     * 26 cycles, and every neighbouring value scores lower — so a wrong constant
     * here can only understate the result. That is an argument for the wide
     * margin on the thresholds rather than against the measurement.
     */
    private static final double CYCLE_SECONDS = 27.15;

    private static Score score;
    private static Path sample;

    @BeforeAll
    static void transcribeOnce() {
        sample = locateSample();
        score = new AudioTranscriber().transcribe(sample, AudioTranscriber.Options.defaults());
    }

    /**
     * Finds {@code samples/gmajorblues.mp3} by walking up from the working
     * directory, which surefire sets to the module rather than the repository.
     */
    private static Path locateSample() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("samples").resolve("gmajorblues.mp3");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "samples/gmajorblues.mp3 was not found above " + System.getProperty("user.dir")
                        + "; it is committed, so this means the checkout is incomplete");
    }

    @Test
    @DisplayName("the recording is not one long no-chord span")
    void theRecordingIsNotOneLongNoChordSpan() {
        // The #185 failure in its plainest form, and the one this file exists
        // for. Before #3 both numbers below were on the far side of their
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
        // Twenty-six cycles of three chords cannot be fewer than a few dozen
        // spans. Measured: 666, from 740 before #196 -- span boundaries are
        // tracked beat times, so removing 24 spurious beats removes the spans
        // they could start. The upper bound is not idle either -- one span per
        // beat would be about 1260, and a decoder that chatters that badly has
        // stopped smoothing.
        assertThat(chords.size()).isBetween(100, 1100);
    }

    @Test
    @DisplayName("the three chords of the blues are all found, and as sevenths")
    void theThreeChordsAreAllFound() {
        List<String> symbols = score.chords().chords().stream().map(Chord::symbol).toList();

        // Not merely present: present often. A single stray G7 among six hundred
        // spans would satisfy contains() and mean nothing.
        assertThat(symbols).filteredOn("G7"::equals).hasSizeGreaterThan(50);
        assertThat(symbols).filteredOn("C7"::equals).hasSizeGreaterThan(20);
        assertThat(symbols).filteredOn("D7"::equals).hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("most bars carry the chord the twelve-bar cycle says they should")
    void mostBarsCarryTheRightChord() {
        Labelling labelling = labelBars(CYCLE_SECONDS);

        // Measured 85.7% and 85.7%, from 86.6% and 86.3% before #196. The floors
        // are set well under those: this is a gate against the pipeline
        // breaking, not a record of its best day.
        //
        // The two figures moving together, and downwards, is expected rather
        // than alarming: chroma is averaged per tracked beat, so changing which
        // beats there are changes the spans, and this axis is the one the change
        // could not help -- its bars were already on the music. The axis that
        // could is #theBarGridFromTheTrackedBeatsAgreesWithTheLoop's.
        //
        // 58.3% is the number to beat rather than 0%, because seven of the
        // twelve bars are the tonic, so writing G7 in every bar scores that much
        // while being no transcription at all. Both floors clear it.
        assertThat(labelling.rootAccuracy())
                .as("bars whose root matches the cycle")
                .isGreaterThan(75.0);
        assertThat(labelling.rootAndQualityAccuracy())
                .as("bars whose root and quality both match")
                .isGreaterThan(72.0);
    }

    @Test
    @DisplayName("the IV and the V are found, not just the tonic")
    void theSubdominantAndDominantAreFound() {
        // The assertion that stops "G7 everywhere" from passing the one above.
        // Measured: G 90%, C 88%, D 68%, against G 88%, C 96%, D 68% before
        // #196. The D7 floor is the lowest because the figure is -- the two D7
        // bars are one bar each in a turnaround, which is the hardest position
        // in the cycle to catch, and a third of them are still missed.
        //
        // The C7 floor is now the closest to its measurement, by eight points
        // rather than eleven. Left where it was rather than lowered: the run is
        // deterministic, so a floor is only reached by behaviour changing, which
        // is what it is for.
        Labelling labelling = labelBars(CYCLE_SECONDS);

        assertThat(labelling.recall(7)).as("G7 bars found").isGreaterThan(78.0);
        assertThat(labelling.recall(0)).as("C7 bars found").isGreaterThan(85.0);
        assertThat(labelling.recall(2)).as("D7 bars found").isGreaterThan(55.0);
    }

    @Test
    @DisplayName("the tracked tempo is close to the loop's own")
    void theTrackedTempoIsCloseToTheLoops() {
        // 48 beats to the cycle at 27.15 s is 106.1 BPM. The tracker used to say
        // 108.1, which is 1.9% fast -- close enough to be the right tempo and
        // wrong enough that the bar lines walked off the music over twelve
        // minutes (#196). It now says 106.0.
        //
        // The band was 103 to 111 and is now about 1% either side, which is the
        // point of the fix rather than a decoration on it. A rate error is a
        // drift: after N beats the grid is N times the error away from the
        // music, so the old band's upper edge -- 4.6% -- admitted a grid a
        // whole bar out inside two of the recording's twenty-six cycles. The
        // 1.9% the tracker actually ran at took four, which is what #196
        // reported. A band is still the right shape, since the loop length this
        // is compared against is itself a measurement, but it has to be narrow
        // enough that passing it means the bars stay on the music.
        List<Double> beats = score.beatGrid().map(BeatGrid::beatTimes).orElseThrow();
        double tracked = 60.0 * (beats.size() - 1)
                / (beats.get(beats.size() - 1) - beats.get(0));

        assertThat(tracked).isBetween(105.0, 107.2);
    }

    @Test
    @DisplayName("the tracked beats are one beat apart, not two thirds of one")
    void theTrackedBeatsAreOneBeatApart() {
        // #196's mechanism on the recording itself, where the tempo band above
        // only sees its consequence. This recording is a shuffle, so its loudest
        // events are the swung eighths two thirds of the way through each beat.
        // While the spacing penalty was a forty-eighth of the published one the
        // dynamic program left the grid for them and came back a beat later, and
        // the interval histogram says so plainly:
        //
        //   share of intervals   within 10% of      before #196   after
        //   one beat                                   55.5%      96.4%
        //   two thirds of a beat (the detour)          24.1%       0.7%
        //   four thirds of a beat (the catch-up)       19.5%       0.4%
        //
        // Worth a test of its own because it cannot be traded against anything:
        // a tempo that is right on average is compatible with a grid that is
        // wrong beat by beat, and that is precisely the state this recording was
        // in. Both bounds sit between the two populations rather than beside
        // either.
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
                .as("share of tracked intervals within 10%% of the median")
                .isGreaterThan(85.0);
        assertThat(100.0 * detours / intervals.length)
                .as("share that are the swung eighth's two thirds of a beat")
                .isLessThan(8.0);
    }

    @Test
    @DisplayName("the bar grid from the tracked beats agrees with the loop's")
    void theBarGridFromTheTrackedBeatsAgreesWithTheLoop() {
        // Deliberately measuring what a reader of the engraved chart would get,
        // rather than what the chord stage alone is worth: bars of four tracked
        // beats, which is how the chart is actually laid out.
        //
        // This test used to assert the opposite -- that this axis scored at
        // least twenty points *worse* than the loop's -- and said that the day
        // the gap closed the assertion should fail and be replaced. #196 closed
        // it: 47.5% against 86.6% became 84.1% against 85.7%, and what is pinned
        // now is that the two axes agree.
        //
        // Agreement rather than a floor of its own, because that is the property
        // that says the beat grid is right. A floor could be met by both axes
        // drifting together; a gap of a few points cannot be, since the loop's
        // axis is measured from the recording and does not move when the tracker
        // does.
        List<Double> beats = score.beatGrid().map(BeatGrid::beatTimes).orElseThrow();
        int bars = (beats.size() - 1) / 4;
        int[] roots = new int[bars];
        for (int bar = 0; bar < bars; bar++) {
            roots[bar] = rootOverlapping(
                    beats.get(bar * 4), beats.get(Math.min(bar * 4 + 4, beats.size() - 1)));
        }
        double tracked = bestRotationAccuracy(roots, bars);
        double loop = labelBars(CYCLE_SECONDS).rootAccuracy();

        assertThat(tracked)
                .as("bars from the tracked beat grid whose root matches")
                .isGreaterThan(75.0);
        // Measured 1.6 points apart. Two-sided: the tracked axis running *ahead*
        // of the loop's would mean the loop constant is wrong, not that beat
        // tracking got better, and that is worth failing on too.
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
     * 314 bars, which is not enough freedom to manufacture a result: labelling
     * every bar G7 scores 58.3% at whichever rotation, and a random labelling
     * about 8%.
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
            sevenths[bar] = chord != null && !chord.isNoChord() && chord.quality().hasSeventh();
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
