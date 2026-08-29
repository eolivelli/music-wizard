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
import dev.olivelli.musicwizard.dsp.BeatTracker;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.HarmonicRhythm;
import dev.olivelli.musicwizard.dsp.MeterEstimator;
import dev.olivelli.musicwizard.dsp.NnlsChroma;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Prints what {@link MeterEstimator} reads on every benchmark, for #700.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/MeterSweep.java
 * </pre>
 *
 * <p>{@code p2 p3 p4 p6}, {@code pulse}, {@code in3} and {@code in2} are the
 * estimator's own statistics rather than a reproduction of them, so the
 * constants in that class can be re-derived from this output: {@code p2 p3 p4
 * p6} are the harmonic periodicity at each period it reads, on a null whose
 * expectation is one, and {@code in3} and {@code in2} are how much of the onset
 * envelope's periodicity at the pulse a triple and an even division of it carry,
 * over the {@code pulse} column, which is how much of the envelope's energy sits
 * at the pulse for them to be shares of. {@code meter} is what the estimator
 * decides and {@code want} what {@code samples/list.txt} states; a row where
 * they differ is the reading to explain.
 *
 * <p>{@code mid} is none of those and nothing decides on it: it is what #701
 * asks whether a four-pulse bar may be promoted to 12/8 on. See
 * {@link #middleOfThePulse}.
 *
 * <p><b>Read {@code bpm} before {@code in3}, {@code in2} or {@code mid}.</b>
 * Each of those divides the tracked pulse, so on a recording the tracker took at
 * a multiple or a fraction of the counted beat they divide something the music
 * is not counted in — a swung eighth can land where a triple division of two
 * counted beats is looked for, and print as a division in three. Nothing else in
 * the row says which pulse was tracked; this does. It reproduces the estimator's
 * own median interval rather than reading it, so a change to how that class
 * derives its pulse leaves this printing the old one.
 *
 * <p><b>Every recording under {@code uncommitted/} is swept</b>, listed from the
 * directory rather than by name, because a claim about what real mixes do is
 * worth what the mixes behind it are worth and a hand-written list is one
 * commercial track away from being out of date. A local recording whose meter
 * a musician has confirmed is named in {@link #LOCAL_METERS} so that its row
 * is scored like any other; the rest state none.
 */
public final class MeterSweep {

    private static final String LOCAL = "uncommitted";

    /**
     * A benchmark and the meter stated for it.
     *
     * <p>{@code want} is empty where nothing states one: most of the commercial
     * recordings have never been listened to for their meter, and printing a
     * guess beside the reading would turn this sweep into a scorer against
     * truth it does not have.
     */
    private record Job(String corpus, String file, String want) {

        Job(String file, String want) {
            this("samples", file, want);
        }
    }

    private static final List<Job> JOBS = List.of(
            // The meters samples/list.txt states.
            new Job("waltz-am-e7-160.mp3", "3/4"),
            // Its entry states no denominator, and "in three" does not choose
            // between 3/4 and 6/4.
            new Job("footprints-200.mp3", ""),
            new Job("cm-blues-68-95.mp3", "6/8"),
            new Job("slow-68-40.mp3", "6/8"),
            // Barred in four by their own ground-truth cycles. The shuffles are
            // the guard: their swing is a triple subdivision and a detector that
            // reads them as compound is wrong by the corpus's own truth.
            new Job("g-blues-shuffle-cc.mp3", "4/4"),
            new Job("blues-shuffle-a-106bpm.mp3", "4/4"),
            new Job("blues-a-90bpm.mp3", "4/4"),
            new Job("blues-e-90bpm.mp3", "4/4"),
            // Its entry states a bar of six tracked pulses and not a spelling,
            // so nothing here is scored against it.
            new Job("bm-blues-slow.mp3", ""),
            new Job("f-blues-swing-170.mp3", "4/4"),
            new Job("jazz-251-c-140.mp3", "4/4"),
            new Job("fm7-vamp-110.mp3", "4/4"),
            new Job("eb7-vamp-130.mp3", "4/4"),
            new Job("bossa-cm.mp3", "4/4"),
            new Job("pop-c-g-am-f-120.mp3", "4/4"),
            new Job("pop-am-f-c-g-144.mp3", "4/4"),
            new Job("ballad-wine-roses-65.mp3", "4/4"),
            // Synthetic, where the spec compiled the MIDI, so the meter is
            // truth by construction rather than by ear.
            new Job("synthetic_samples", "pop-axis-g-116.mp3", "4/4"),
            new Job("synthetic_samples", "pop-deceptive-f-72.mp3", "4/4"),
            new Job("synthetic_samples", "rocknroll-12bar-a-168.mp3", "4/4"),
            // Commercial, local-only, is added from the directory rather than
            // named here; see localJobs.
            new Job("synthetic_samples", "hiphop-m7vamp-bbm-90.mp3", "4/4"),
            // The meters #702 built, each isolating one case. The two 6/8
            // packages differ in their harmonic rate alone and the two blues in
            // whether the middle eighth of each beat sounds, so a column that
            // moves between a pair is that one difference and nothing else.
            new Job("synthetic_samples", "pop-waltz-d-108.mp3", "3/4"),
            new Job("synthetic_samples", "pop-68-vamp-am-144.mp3", "6/8"),
            new Job("synthetic_samples", "pop-68-twobar-am-144.mp3", "6/8"),
            new Job("synthetic_samples", "blues-shuffle-e-84.mp3", "4/4"),
            new Job("synthetic_samples", "blues-compound-e-126.mp3", "12/8"));

    /**
     * The meters a musician has confirmed by ear for local-only recordings,
     * {@code uncommitted/list.txt} carrying the confirmation per file.
     *
     * <p>Real audio, so these are the rows the gates above are ultimately for,
     * and a reading that leaves one is a mismatch rather than a curiosity.
     * {@code tools/score-samples.py} scores the same truth, which is what makes
     * a regression here fail a gate rather than only print differently.
     */
    private static final List<Job> LOCAL_METERS =
            List.of(new Job(LOCAL, "balorda-nostalgia.mp3", "6/8"));

    /**
     * Every recording under {@code uncommitted/}, present only on the machine
     * that fetched it.
     *
     * <p>Listed rather than named, so a track added to that directory is swept
     * without anyone remembering to add it here — which is the whole value of
     * these rows, the gates above being a claim about real mixes. What a row
     * is scored against, where anything states it, comes from
     * {@link #LOCAL_METERS}.
     */
    private static List<Job> localJobs() {
        Path corpus = Path.of(LOCAL);
        if (!Files.isDirectory(corpus)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(corpus)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".mp3"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(file -> new Job(LOCAL, file.getFileName().toString(),
                            confirmedMeter(file.getFileName().toString())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The meter confirmed for a local recording, or none. */
    private static String confirmedMeter(String file) {
        return LOCAL_METERS.stream()
                .filter(job -> job.file().equals(file))
                .map(Job::want)
                .findFirst()
                .orElse("");
    }

    public static void main(String[] args) {
        System.out.printf("%-38s %8s %8s %8s %8s %7s %7s %7s %7s %7s  %-5s %-5s %s%n",
                "file", "p2", "p3", "p4", "p6", "bpm", "pulse", "in3", "in2", "mid", "meter",
                "want", "pulses/bar, confidence");
        List<Job> jobs = new ArrayList<>(JOBS);
        jobs.addAll(localJobs());
        for (Job job : jobs) {
            Path file = Path.of(job.corpus()).resolve(job.file());
            if (!Files.isRegularFile(file)) {
                System.out.printf("%-38s SKIPPED (not present)%n", job.file());
                continue;
            }
            report(job, file);
        }
    }

    private static void report(Job job, Path file) {
        AudioBuffer audio = AudioDecoder.decode(file, AudioDecoder.ANALYSIS_SAMPLE_RATE);
        OnsetEnvelope.Both onsets = OnsetEnvelope.bothFromAudio(audio);
        OnsetEnvelope envelope = onsets.envelope();
        Spectrogram transform = NnlsChroma.transform(audio);
        Chroma frames = NnlsChroma.extract(transform, Chroma.estimateTuning(transform)).combined();
        BeatTracker.Result beats =
                BeatTracker.track(envelope, HarmonicRhythm.of(frames), onsets.pulseRegister());
        if (beats.isEmpty()) {
            System.out.printf("%-38s no beats%n", job.file());
            return;
        }
        List<Double> beatTimes = beats.beatTimes();
        MeterEstimator.Reading reading =
                MeterEstimator.read(beatTimes, frames.beatSynchronous(beatTimes), envelope);
        MeterEstimator.Estimate estimate = MeterEstimator.decide(reading);
        String meter = estimate.meter().toString();
        System.out.printf(
                "%-38s %8.2f %8.2f %8.2f %8.2f %7.1f %7.2f %7.2f %7.2f %7.2f"
                        + "  %-5s %-5s %d, %.2f %s%n",
                job.file(), reading.atTwo(), reading.atThree(), reading.atFour(),
                reading.atSix(), trackedPulseRate(beatTimes), reading.onThePulse(),
                reading.inThree(), reading.inTwo(),
                middleOfThePulse(envelope, beatTimes), meter,
                job.want().isEmpty() ? "-" : job.want(),
                estimate.pulsesPerBar(), estimate.confidence().value(),
                job.want().isEmpty() || job.want().equals(meter) ? "" : "MISMATCH");
    }

    /** Positions the pulse is folded into, divisible by both two and three. */
    private static final int POSITIONS = 12;

    /**
     * Tracked pulses a minute, from the middle interval between beats — the
     * pulse the columns beside it are shares of, and the one thing that says
     * whether they are shares of the counted beat.
     */
    private static double trackedPulseRate(List<Double> beatTimes) {
        List<Double> intervals = new ArrayList<>(Math.max(0, beatTimes.size() - 1));
        for (int beat = 1; beat < beatTimes.size(); beat++) {
            intervals.add(beatTimes.get(beat) - beatTimes.get(beat - 1));
        }
        if (intervals.isEmpty()) {
            return 0;
        }
        Collections.sort(intervals);
        double middle = intervals.get(intervals.size() / 2);
        return middle > 0 ? 60 / middle : 0;
    }

    /**
     * What the onset envelope carries at the middle of a triple division of the
     * tracked pulse, as a share of what it carries at the pulse itself.
     *
     * <p>The question #701 turns on, and the one column here the estimator does
     * not read. A compound bar sounds all three of its subdivisions and a
     * shuffle leaves the middle one out, so that middle position is where the
     * two differ; {@link MeterEstimator.Reading#inThree()} cannot say it,
     * being the stronger of the two lags a triple division peaks at, which a
     * shuffle striking two of three positions reaches as readily as a compound
     * striking three.
     */
    private static double middleOfThePulse(OnsetEnvelope envelope, List<Double> beatTimes) {
        double[] fold = foldOntoThePulse(envelope, beatTimes);
        double floor = Arrays.stream(fold).min().orElse(0);
        double atThePulse = fold[0] - floor;
        return atThePulse > 0 ? (fold[POSITIONS / 3] - floor) / atThePulse : 0;
    }

    /**
     * The onset envelope averaged over the tracked pulse's own phase.
     *
     * <p>Folded over each pulse's measured length rather than a mean one: a
     * fold that drifts against the beat smears the positions it exists to keep
     * apart. Each position takes the strongest frame within half a position of
     * it, because a division of the pulse is played by hand and lands near its
     * arithmetic position rather than on it — or nothing at all, the envelope
     * being centred, where the window only falls.
     */
    private static double[] foldOntoThePulse(OnsetEnvelope envelope, List<Double> beatTimes) {
        double[] strength = envelope.strength();
        double[] fold = new double[POSITIONS];
        int pulses = 0;
        for (int beat = 0; beat + 1 < beatTimes.size(); beat++) {
            double from = beatTimes.get(beat);
            double span = beatTimes.get(beat + 1) - from;
            if (!(span > 0)) {
                continue;
            }
            double framesPerPosition = span * envelope.frameRate() / POSITIONS;
            for (int position = 0; position < POSITIONS; position++) {
                double centre = from * envelope.frameRate() + position * framesPerPosition;
                double strongest = 0;
                int first = (int) Math.round(centre - framesPerPosition / 2);
                int last = (int) Math.round(centre + framesPerPosition / 2);
                for (int frame = first; frame <= last; frame++) {
                    if (frame >= 0 && frame < strength.length) {
                        strongest = Math.max(strongest, strength[frame]);
                    }
                }
                fold[position] += strongest;
            }
            pulses++;
        }
        for (int position = 0; pulses > 0 && position < POSITIONS; position++) {
            fold[position] /= pulses;
        }
        return fold;
    }
}
