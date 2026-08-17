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

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.audio.Spectrogram;
import dev.olivelli.musicwizard.dsp.HarmonicRhythm;
import dev.olivelli.musicwizard.dsp.MarkedPulse;
import dev.olivelli.musicwizard.dsp.NnlsChroma;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import dev.olivelli.musicwizard.dsp.TempoEstimator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes the tempo score at a candidate and at its octaves, for #349.
 *
 * <p>{@link TempoEstimator} picks the candidate maximising
 * {@code autocorrelation x perceptualWeight x harmonicSupport}. When a
 * recording comes out at half its counted beat, that product is where it
 * happened, and the factors fail for different reasons: an envelope that
 * genuinely repeats more strongly at the half is not the same defect as a
 * prior tipping a close call. This prints them at the stated tempo and at its
 * octaves, so a candidate rule has a target and a control set rather than four
 * file names.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/TempoOctave.java [ceiling]
 * </pre>
 *
 * <p>The optional argument overrides {@code TempoEstimator.ACCENT_CEILING} in
 * this file's reproduction of the search. At any other value the {@code argmax} and
 * {@code est} columns are reading two different estimators and may differ;
 * a difference there is the sweep working, not a defect.
 *
 * <p>Two autocorrelation columns are printed: {@code plain} reads the envelope
 * and {@code ranked} reads it with the loudest accents held level, which is what
 * the estimator ranks candidates on. The difference between the two columns is
 * the whole of what {@code TempoEstimator.compressAccents} does.
 *
 * <p>The factors are recomputed here rather than read out of the estimator,
 * which exposes none of them. The check that the reimplementation is the
 * estimator's is printed on every line: {@code argmax} is this file's winner
 * and {@code est} is {@link TempoEstimator#estimate}'s, and they must agree.
 *
 * <p>The {@code register} line is the exception: it is
 * {@link MarkedPulse}'s own reading of the voter reference, not a
 * reproduction, and it is what decides whether that rate is halved before
 * anything is tracked (#509).
 */
public final class TempoOctave {

    /** Analysis windows are this long in {@link dev.olivelli.musicwizard.dsp.BeatTracker}. */
    private static final double WINDOW_SECONDS = 25.0;

    private static final double MIN_TEMPO = 40;
    private static final double MAX_TEMPO = 240;
    private static final double STEP = 0.25;
    private static final double PREFERRED_TEMPO = 120.0;
    private static final double PRIOR_WIDTH_OCTAVES = 1.4;

    /**
     * A benchmark and the tempo stated for it.
     *
     * <p>{@code corpus} is the directory it lives in, because the two corpora
     * state a tempo with different authority: a {@code samples/} tempo is
     * either stated by an uploader or measured off the recording, and the
     * corpus marks which — the two are not equally soft — while a
     * {@code synthetic_samples/} spec compiled the MIDI the audio was rendered
     * from. An octave error against the second is arithmetic.
     */
    private record Job(String corpus, String file, double statedTempo, String note) {

        Job(String file, double statedTempo, String note) {
            this("samples", file, statedTempo, note);
        }
    }

    /**
     * The commercial recordings, which live outside the two corpora and are
     * never redistributed: present on the machine that fetched them and
     * SKIPPED everywhere else. They are here because the register reading
     * below is a claim about what happens to real mixes, and fifteen of them
     * are what makes it one.
     */
    private static final String LOCAL = "uncommitted";

    private static final List<Job> JOBS = List.of(
            // The four #349 names.
            new Job("jazz-251-c-140.mp3", 140, "target"),
            new Job("f-blues-swing-170.mp3", 170, "target, swung"),
            new Job("footprints-200.mp3", 200, "target, in three"),
            new Job("bossa-cm.mp3", 149.889, "target, measured by ScoreBeats"),
            // Controls: currently read at the stated rate.
            new Job("g-blues-shuffle-cc.mp3", 105, "control, CI gate"),
            new Job("pop-c-g-am-f-120.mp3", 120, "control"),
            new Job("eb7-vamp-130.mp3", 130, "control"),
            new Job("fm7-vamp-110.mp3", 110, "control"),
            new Job("waltz-am-e7-160.mp3", 160, "control, in three"),
            new Job("ballad-wine-roses-65.mp3", 65, "control"),
            new Job("blues-a-90bpm.mp3", 90, "control"),
            new Job("blues-e-90bpm.mp3", 90, "control"),
            new Job("blues-shuffle-a-106bpm.mp3", 105, "control, loop measures 105.000"),
            new Job("pop-am-f-c-g-144.mp3", 144, "control"),
            // Entangled with #4: a compound meter's beat is not defined
            // downstream, so these are neither target nor clean control.
            new Job("cm-blues-68-95.mp3", 95, "6/8, #4"),
            new Job("slow-68-40.mp3", 40, "6/8, #4"),
            // States no tempo; printed for its factors only.
            new Job("bm-blues-slow.mp3", 0, "no stated tempo"),
            // Synthetic, so the stated tempo is the one the MIDI was compiled
            // at. The first is #509's target and reads its double; the second
            // is a full-band package of the same generator that reads its
            // written tempo, so a rule that moves it has moved something the
            // corpus says is already right.
            new Job("synthetic_samples", "pop-deceptive-f-72.mp3", 72, "target, #509"),
            new Job("synthetic_samples", "pop-axis-g-116.mp3", 116, "control, synthetic"),
            new Job("synthetic_samples", "pop-threechord-c-108.mp3", 108, "control, synthetic"),
            new Job("synthetic_samples", "pop-m6-m7b5-gm-100.mp3", 100, "control, synthetic"),
            new Job("synthetic_samples", "pop-maj7-sixth-c-92.mp3", 92, "control, synthetic"),
            new Job("synthetic_samples", "hiphop-m7vamp-bbm-90.mp3", 90, "control, synthetic"),
            new Job("synthetic_samples", "rocknroll-12bar-a-168.mp3", 168, "control, synthetic"),
            new Job("synthetic_samples", "melody-level2pad-g-84.mp3", 84, "reads 2.16x, #499"),
            // Commercial, local-only. The first two are #378's, which read
            // double; the rest are scored by no harness and are here for the
            // register reading, which is a claim about real mixes.
            new Job(LOCAL, "la-canzone-del-sole.mp3", 93, "reads double, #378"),
            new Job(LOCAL, "sweet-home-alabama.mp3", 97.9, "reads double, #378"),
            new Job(LOCAL, "la-canzone-del-sole-ab.mp3", 87, "control, #378"),
            new Job(LOCAL, "johnny-b-goode.mp3", 0, "control"),
            new Job(LOCAL, "karma-chameleon.mp3", 0, "control"),
            new Job(LOCAL, "generale.mp3", 0, "control"),
            new Job(LOCAL, "gli-anni.mp3", 0, "control"),
            new Job(LOCAL, "islanda.mp3", 0, "control"),
            new Job(LOCAL, "bellissimissima.mp3", 0, "control"),
            new Job(LOCAL, "hanno-ucciso-luomo-ragno.mp3", 0, "control"),
            new Job(LOCAL, "la-mia-banda-suona-il-rock.mp3", 0, "control"),
            new Job(LOCAL, "sere-doltremare.mp3", 0, "control"),
            new Job(LOCAL, "rxbyn-bad-side.mp3", 0, "control"),
            new Job(LOCAL, "cortez-feel-stripped.mp3", 0, "control"),
            new Job(LOCAL, "josh-woodward-california-lullabye.mp3", 0, "control"));

    /** {@code TempoEstimator.ACCENT_CEILING}, which this file's search reproduces. */
    private static final double SHIPPED_CEILING = 3.0;

    /** The ceiling this file's reproduction uses; the shipped one by default. */
    private static double ceiling = SHIPPED_CEILING;

    public static void main(String[] args) {
        if (args.length > 0) {
            ceiling = Double.parseDouble(args[0]);
        }
        System.out.printf("accent ceiling %.2f%n", ceiling);
        for (Job job : JOBS) {
            Path file = Path.of(job.corpus()).resolve(job.file());
            if (!Files.isRegularFile(file)) {
                System.out.printf("%-28s SKIPPED (not present)%n", job.file());
                continue;
            }
            report(job, file);
        }
    }

    private static void report(Job job, Path file) {
        AudioBuffer audio = AudioDecoder.decode(file, AudioDecoder.ANALYSIS_SAMPLE_RATE);
        OnsetEnvelope.Both onsets = OnsetEnvelope.bothFromAudio(audio);
        OnsetEnvelope envelope = onsets.envelope();
        OnsetEnvelope register = onsets.pulseRegister();
        HarmonicRhythm rhythm = HarmonicRhythm.of(NnlsChroma.extract(audio).combined());

        System.out.printf("%n=== %s  (%s)%n", job.file(), job.note());
        whole(job, envelope, rhythm);
        Vote vote = windows(job, envelope, rhythm);
        register(envelope, register, vote, rhythm);
    }

    /** The windows' verdict: the rate they voted for, and whether it is the estimator's. */
    private record Vote(double reference, boolean reproduced) {}

    /**
     * What the bass register says about the rate the windows voted for: the
     * shipped reading rather than a reproduction of it, since a grid whose
     * every second beat is unstated there is halved before anything is tracked
     * (#509). A recording whose register is no louder on the beats than
     * between them is one this abstains on, and most of them are.
     *
     * <p>Printed only where this file's search agreed with the estimator's,
     * because the reading is of a <em>rate</em>: under a swept ceiling the
     * rate is one the shipped code never produces, and a shipped reading of it
     * would look like a shipped decision.
     */
    private static void register(OnsetEnvelope envelope, OnsetEnvelope register, Vote vote,
                                 HarmonicRhythm rhythm) {
        if (!vote.reproduced()) {
            System.out.println("  register: not printed, since the seeds above are this"
                    + " file's rather than the estimator's");
            return;
        }
        // A clip shorter than one window has no voters and one assumed tempo,
        // which is the rate BeatTracker asks the register about.
        double reference = vote.reference() > 0
                ? vote.reference()
                : TempoEstimator.estimate(envelope, rhythm).beatsPerMinute();
        MarkedPulse.Reading reading = MarkedPulse.read(envelope, register, reference);
        System.out.printf("  register at %.2f: contrast %.2f  parity %.3f  envelope %s  ->  %.2f%n",
                reference, reading.contrast(), reading.parity(),
                reading.envelopePrefersHalf() ? "prefers the half" : "keeps this rate",
                reading.callsForHalving() ? reference / 2 : reference);
    }

    /**
     * The factors over the whole clip.
     *
     * <p><b>Not the grid's rate</b> on anything longer than a window, which is
     * every recording here: {@code BeatTracker} calls the estimator over the
     * whole envelope only for a clip that fits in one window, and otherwise
     * seeds each window separately and takes the median. So this line answers a
     * question about the envelope, and {@code voter reference} below answers
     * the question about the recording. Reading a ceiling sweep off this line
     * gave a boundary two tenths away from the pipeline's on #509, and the two
     * genuinely disagree in the band that matters.
     */
    private static void whole(Job job, OnsetEnvelope envelope, HarmonicRhythm rhythm) {
        Scored scored = score(envelope, rhythm);
        TempoEstimator.Estimate estimate = TempoEstimator.estimate(envelope, rhythm);
        System.out.printf("whole clip (not the grid's rate; see voter reference):"
                        + " argmax %.2f  est %.2f%n",
                scored.bestTempo, estimate.beatsPerMinute());
        printCandidates(job, scored, envelope, rhythm);
    }

    private static void printCandidates(Job job, Scored scored, OnsetEnvelope envelope,
                                        HarmonicRhythm rhythm) {
        System.out.printf("  %-10s %-9s %8s %8s %8s %8s %10s%n",
                "candidate", "bpm", "plain", "ranked", "prior", "support", "product");
        List<double[]> rows = new ArrayList<>();
        if (job.statedTempo() > 0) {
            rows.add(new double[] {job.statedTempo() * 2, 4});
            rows.add(new double[] {job.statedTempo(), 1});
            rows.add(new double[] {job.statedTempo() / 2, 2});
            rows.add(new double[] {job.statedTempo() / 3, 3});
        }
        rows.add(new double[] {scored.bestTempo, 0});
        for (double[] row : rows) {
            double tempo = snap(row[0]);
            if (tempo < MIN_TEMPO || tempo > MAX_TEMPO) {
                continue;
            }
            String label = switch ((int) row[1]) {
                case 4 -> "double";
                case 1 -> "stated";
                case 2 -> "half";
                case 3 -> "third";
                default -> "WINNER";
            };
            double plain = scored.plainAt(tempo);
            double value = scored.rankedAt(tempo);
            double prior = perceptualWeight(tempo);
            double support = rhythm.supportFor(60.0 / tempo);
            System.out.printf("  %-10s %9.2f %8.4f %8.4f %8.4f %8.4f %10.5f%n",
                    label, tempo, plain, value, prior, support,
                    value > 0 ? value * prior * support : value * prior);
        }
    }

    private static Vote windows(Job job, OnsetEnvelope envelope, HarmonicRhythm rhythm) {
        int windowFrames = (int) Math.round(WINDOW_SECONDS * envelope.frameRate());
        if (envelope.length() <= windowFrames) {
            return new Vote(0, ceiling == SHIPPED_CEILING);
        }
        int step = windowFrames / 2;
        int at = 0;
        int atHalf = 0;
        int atStated = 0;
        int voters = 0;
        int disagreements = 0;
        List<Double> voterSeeds = new ArrayList<>();
        StringBuilder seeds = new StringBuilder();
        for (int start = 0; start < envelope.length(); start += step) {
            int end = Math.min(envelope.length(), start + windowFrames);
            if (end - start < 16) {
                break;
            }
            // Both, so the sweep means something and the reproduction is
            // checked per window rather than only over the whole clip: `mine`
            // is this file's search at the ceiling in force, `theirs` the
            // estimator's own. They must agree at the shipped ceiling.
            double[] slice = new double[end - start];
            System.arraycopy(envelope.strength(), start, slice, 0, slice.length);
            OnsetEnvelope sliceEnvelope = new OnsetEnvelope(slice, envelope.frameRate());
            // Mirrors estimate()'s flat guard: a silent window reports the
            // prior rather than a search over nothing. Without it the one
            // flat window in a fade "disagrees" at the shipped ceiling and
            // the self-check cries wolf. estimate()'s short-window branch is
            // unreachable here -- the loop breaks below 16 frames.
            double mine = sliceEnvelope.isFlat()
                    ? PREFERRED_TEMPO
                    : score(sliceEnvelope, rhythm).bestTempo();
            double theirs =
                    TempoEstimator.estimateWindow(envelope, start, end, rhythm).beatsPerMinute();
            if (mine != theirs) {
                disagreements++;
            }
            if (end - start >= step) {
                voters++;
                voterSeeds.add(mine);
                if (job.statedTempo() > 0) {
                    if (near(mine, job.statedTempo())) {
                        atStated++;
                    } else if (near(mine, job.statedTempo() / 2)) {
                        atHalf++;
                    }
                }
            }
            seeds.append(String.format("%.2f ", mine));
            at++;
        }
        // BeatTracker.pulseReference's statistic, reproduced: rates[n/2] on
        // the sorted voters -- always an observed rate, never the average of
        // two, exactly as that method's javadoc insists.
        double[] sorted = voterSeeds.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double reference = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
        System.out.printf("  windows %d (%d voters): %d at stated, %d at half,"
                + " voter reference %.2f%s%n", at, voters, atStated, atHalf, reference,
                disagreements == 0 ? "" : "  [" + disagreements + " windows differ from the"
                        + " estimator, expected only when a ceiling is passed]");
        System.out.printf("  seeds: %s%n", seeds.toString().trim());
        return new Vote(reference, disagreements == 0);
    }

    /** Within 3% -- wide enough for the 0.25 grid and a drifting recording. */
    private static boolean near(double a, double b) {
        return Math.abs(a - b) / b < 0.03;
    }

    private static double snap(double tempo) {
        return Math.round(tempo / STEP) * STEP;
    }

    /** The estimator's scoring loop, reproduced so its factors can be read apart. */
    private record Scored(double[] plain, double[] ranked, double frameRate, double bestTempo) {
        double plainAt(double tempo) {
            return interpolate(plain, frameRate * 60.0 / tempo);
        }

        double rankedAt(double tempo) {
            return interpolate(ranked, frameRate * 60.0 / tempo);
        }
    }

    private static Scored score(OnsetEnvelope envelope, HarmonicRhythm rhythm) {
        double frameRate = envelope.frameRate();
        int minLag = (int) Math.max(1, Math.floor(frameRate * 60.0 / MAX_TEMPO));
        int maxLag = (int) Math.min(envelope.length() - 1,
                Math.ceil(frameRate * 60.0 / MIN_TEMPO));
        double[] plain = autocorrelate(envelope.strength(), maxLag + 1);
        double[] ranked = autocorrelate(compressAccents(envelope.strength()), maxLag + 1);
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestTempo = PREFERRED_TEMPO;
        for (double tempo = MIN_TEMPO; tempo <= MAX_TEMPO; tempo += STEP) {
            double lag = frameRate * 60.0 / tempo;
            if (lag < minLag || lag > maxLag) {
                continue;
            }
            double value = interpolate(ranked, lag);
            double s = value * perceptualWeight(tempo);
            if (s > 0) {
                s *= rhythm.supportFor(60.0 / tempo);
            }
            if (s > bestScore) {
                bestScore = s;
                bestTempo = tempo;
            }
        }
        return new Scored(plain, ranked, frameRate, bestTempo);
    }

    /** {@code TempoEstimator.compressAccents}, reproduced, at the ceiling in force. */
    private static double[] compressAccents(double[] signal) {
        double[] out = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            out[i] = Math.clamp(signal[i], -ceiling, ceiling);
        }
        return out;
    }

    private static double perceptualWeight(double beatsPerMinute) {
        double octaves = Math.log(beatsPerMinute / PREFERRED_TEMPO) / Math.log(2);
        double normalised = octaves / PRIOR_WIDTH_OCTAVES;
        return Math.exp(-0.5 * normalised * normalised);
    }

    private static double interpolate(double[] correlation, double lag) {
        int low = (int) Math.floor(lag);
        int high = low + 1;
        if (low < 0 || high >= correlation.length) {
            return 0;
        }
        double fraction = lag - low;
        return correlation[low] + (correlation[high] - correlation[low]) * fraction;
    }

    private static double[] autocorrelate(double[] signal, int maxLag) {
        double[] out = new double[maxLag + 1];
        for (int lag = 0; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < signal.length; i++) {
                sum += signal[i] * signal[i + lag];
            }
            out[lag] = sum / signal.length;
        }
        return out;
    }

    private TempoOctave() {
    }
}
