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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prints what {@link MeterEstimator} reads on every benchmark, for #700.
 *
 * <pre>
 *   java -cp mw-cli/target/mw.jar tools/MeterSweep.java
 * </pre>
 *
 * <p>Every column is one of the estimator's own statistics rather than a
 * reproduction of them, so the constants in that class can be re-derived from
 * this output: {@code p2 p3 p4 p6} are the harmonic periodicities at each
 * candidate bar length and {@code duple triple} the onset periodicities within
 * a pulse, all of them on a null whose expectation is one. {@code meter} is
 * what the estimator decides and {@code want} what {@code samples/list.txt}
 * states; a row where they differ is the reading to explain.
 *
 * <p>The local-only recordings are listed and SKIPPED where they are absent,
 * because a claim about what real mixes do is worth what the mixes behind it
 * are worth.
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
            new Job("footprints-200.mp3", "3/4"),
            new Job("cm-blues-68-95.mp3", "6/8"),
            new Job("slow-68-40.mp3", "6/8"),
            // Barred in four by their own ground-truth cycles. The shuffles are
            // the guard: their swing is a triple subdivision and a detector that
            // reads them as compound is wrong by the corpus's own truth.
            new Job("g-blues-shuffle-cc.mp3", "4/4"),
            new Job("blues-shuffle-a-106bpm.mp3", "4/4"),
            new Job("blues-a-90bpm.mp3", "4/4"),
            new Job("blues-e-90bpm.mp3", "4/4"),
            new Job("bm-blues-slow.mp3", "6/8"),
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
            new Job("synthetic_samples", "hiphop-m7vamp-bbm-90.mp3", "4/4"),
            // Commercial, local-only and stating no meter. Here because the
            // gates above are a claim about real mixes.
            new Job(LOCAL, "la-canzone-del-sole.mp3", ""),
            new Job(LOCAL, "johnny-b-goode.mp3", ""),
            new Job(LOCAL, "generale.mp3", ""),
            new Job(LOCAL, "gli-anni.mp3", ""),
            new Job(LOCAL, "karma-chameleon.mp3", ""),
            new Job(LOCAL, "sweet-home-alabama.mp3", ""),
            new Job(LOCAL, "la-mia-banda-suona-il-rock.mp3", ""),
            new Job(LOCAL, "hanno-ucciso-luomo-ragno.mp3", ""),
            new Job(LOCAL, "bellissimissima.mp3", ""),
            new Job(LOCAL, "islanda.mp3", ""),
            new Job(LOCAL, "sere-doltremare.mp3", ""),
            new Job(LOCAL, "cortez-feel-stripped.mp3", ""),
            new Job(LOCAL, "rxbyn-bad-side.mp3", ""),
            new Job(LOCAL, "josh-woodward-california-lullabye.mp3", ""));

    public static void main(String[] args) {
        System.out.printf("%-30s %7s %7s %7s %7s %7s %7s  %-5s %-5s %s%n",
                "file", "p2", "p3", "p4", "p6", "duple", "triple",
                "meter", "want", "pulses/bar, confidence");
        for (Job job : JOBS) {
            Path file = Path.of(job.corpus()).resolve(job.file());
            if (!Files.isRegularFile(file)) {
                System.out.printf("%-30s SKIPPED (not present)%n", job.file());
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
            System.out.printf("%-30s no beats%n", job.file());
            return;
        }
        List<Double> beatTimes = beats.beatTimes();
        MeterEstimator.Reading reading =
                MeterEstimator.read(beatTimes, frames.beatSynchronous(beatTimes), envelope);
        MeterEstimator.Estimate estimate = MeterEstimator.decide(reading);
        String meter = estimate.meter().toString();
        System.out.printf("%-30s %7.2f %7.2f %7.2f %7.2f %7.0f %7.0f  %-5s %-5s %d, %.2f %s%n",
                job.file(), reading.atTwo(), reading.atThree(), reading.atFour(),
                reading.atSix(), reading.duple(), reading.triple(), meter,
                job.want().isEmpty() ? "-" : job.want(), estimate.pulsesPerBar(),
                estimate.confidence().value(),
                job.want().isEmpty() || job.want().equals(meter) ? "" : "MISMATCH");
    }
}
