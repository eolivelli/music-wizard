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
 * <p>Every column is one of the estimator's own statistics rather than a
 * reproduction of them, so the constants in that class can be re-derived from
 * this output: {@code p2 p3 p4 p6} are the harmonic periodicity at each period
 * it reads, on a null whose expectation is one, and {@code in3} and {@code in2}
 * are how much of the onset envelope's periodicity at the pulse a triple and a
 * duple division of it carry, over the {@code pulse} column, which is how much
 * of the envelope's energy sits at the pulse for them to be shares of. {@code meter} is what the estimator decides and
 * {@code want} what {@code samples/list.txt} states; a row where they differ is
 * the reading to explain.
 *
 * <p><b>Every recording under {@code uncommitted/} is swept</b>, listed from the
 * directory rather than by name, because a claim about what real mixes do is
 * worth what the mixes behind it are worth and a hand-written list is one
 * commercial track away from being out of date. None of them states a meter.
 */
public final class MeterSweep {

    private static final String LOCAL = "uncommitted";

    /**
     * A benchmark and the meter stated for it.
     *
     * <p>{@code want} is empty where nothing states one: the commercial
     * recordings carry no confirmed meter, and printing a guess beside the
     * reading would turn this sweep into a scorer against truth it does not
     * have.
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
            // Synthetic, where the spec compiled the MIDI: 4/4 by construction,
            // since the arranger writes nothing else (#702).
            new Job("synthetic_samples", "pop-axis-g-116.mp3", "4/4"),
            new Job("synthetic_samples", "pop-deceptive-f-72.mp3", "4/4"),
            new Job("synthetic_samples", "rocknroll-12bar-a-168.mp3", "4/4"),
            // Commercial, local-only, is added from the directory rather than
            // named here; see localJobs.
            new Job("synthetic_samples", "hiphop-m7vamp-bbm-90.mp3", "4/4"));

    /**
     * Every recording under {@code uncommitted/}, which states no meter and is
     * present only on the machine that fetched it.
     *
     * <p>Listed rather than named, so a track added to that directory is swept
     * without anyone remembering to add it here — which is the whole value of
     * these rows, the gates above being a claim about real mixes.
     */
    private static List<Job> localJobs() {
        Path corpus = Path.of(LOCAL);
        if (!Files.isDirectory(corpus)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(corpus)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".mp3"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(file -> new Job(LOCAL, file.getFileName().toString(), ""))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        System.out.printf("%-38s %8s %8s %8s %8s %7s %7s %7s  %-5s %-5s %s%n",
                "file", "p2", "p3", "p4", "p6", "pulse", "in3", "in2", "meter", "want",
                "pulses/bar, confidence");
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
                "%-38s %8.2f %8.2f %8.2f %8.2f %7.2f %7.2f %7.2f  %-5s %-5s %d, %.2f %s%n",
                job.file(), reading.atTwo(), reading.atThree(), reading.atFour(),
                reading.atSix(), reading.onThePulse(), reading.inThree(), reading.inTwo(), meter,
                job.want().isEmpty() ? "-" : job.want(),
                estimate.pulsesPerBar(), estimate.confidence().value(),
                job.want().isEmpty() || job.want().equals(meter) ? "" : "MISMATCH");
    }
}
