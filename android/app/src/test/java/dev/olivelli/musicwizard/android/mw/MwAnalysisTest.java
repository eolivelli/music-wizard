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

package dev.olivelli.musicwizard.android.mw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The app's glue to the analysis, exercised on the JVM.
 *
 * <p>These run against the same dependency graph the APK is dexed from —
 * Gradle's unit-test classpath is the {@code implementation} configuration —
 * so what they prove about {@code mw-ml} and ONNX being absent is a property of
 * the shipped app, not of a test fixture.
 */
public class MwAnalysisTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The app must analyse at the rate the desktop pipeline analyses at.
     *
     * <p>Read reflectively on purpose. {@code AudioDecoder.ANALYSIS_SAMPLE_RATE}
     * is a compile-time constant, so a plain reference would be inlined into
     * this class and the assertion would compare 22050 with itself — and it
     * would also load the one class in the reactor that touches
     * {@code javax.sound}, which the app must never do. Reflection reads the
     * field that is really there.
     */
    @Test
    public void analysisRateMatchesTheDesktopPipeline() throws Exception {
        Class<?> decoder = Class.forName("dev.olivelli.musicwizard.audio.AudioDecoder");
        Field field = decoder.getField("ANALYSIS_SAMPLE_RATE");
        assertEquals("the app resamples to the rate mw analyze uses; if that rate moves,"
                        + " the same take gives a different chart on the phone than on the desktop",
                field.getInt(null), MwAnalysis.ANALYSIS_SAMPLE_RATE);
    }

    /** Halving 44100 is an exact decimation, and the buffer says so afterwards. */
    @Test
    public void aRecordedBufferIsBroughtToTheAnalysisRate() {
        AudioBuffer recorded = new AudioBuffer(
                new float[MwAnalysis.RECORD_SAMPLE_RATE], MwAnalysis.RECORD_SAMPLE_RATE);
        AudioBuffer analysed = MwAnalysis.atAnalysisRate(recorded);
        assertEquals(MwAnalysis.ANALYSIS_SAMPLE_RATE, analysed.sampleRate());
        assertEquals(1.0, analysed.durationSeconds(), 1e-9);
    }

    /** A buffer already at the analysis rate is passed through untouched. */
    @Test
    public void aBufferAtTheAnalysisRateIsNotResampled() {
        AudioBuffer already = new AudioBuffer(new float[1000], MwAnalysis.ANALYSIS_SAMPLE_RATE);
        assertTrue(already == MwAnalysis.atAnalysisRate(already));
    }

    /**
     * ONNX Runtime is not in the app's dependency graph.
     *
     * <p>{@code mw-ml} exists only to pull ONNX Runtime in — desktop natives of
     * the wrong ABI for a phone. #247 took it out of {@code mw-transcribe}'s
     * dependencies and {@code app/build.gradle} excludes it as a backstop, so
     * both would have to be undone before an APK could ship tens of megabytes
     * it cannot use — which is what this fails on.
     */
    @Test
    public void onnxRuntimeIsNotOnTheClasspath() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            fail("ONNX Runtime is on the app's classpath; the exclusions in"
                    + " app/build.gradle have stopped working");
        } catch (ClassNotFoundException expected) {
            // Exactly what the exclusion is for.
        }
    }

    /**
     * The whole harmony path runs with {@code mw-ml} excluded.
     *
     * <p>This is the question epic #236 raised and issue #247 answered. It runs
     * the real {@code AudioTranscriber} over real samples on the app's own
     * classpath: onsets, beat tracking, chroma and chord estimation. If any of
     * those reached for a provider, this would be a {@code NoClassDefFoundError}
     * rather than a chart.
     *
     * <p>The audio is synthetic and the assertions say nothing about accuracy —
     * synthetic audio is systematically easier than real audio, and what is
     * being pinned here is that the stages run and produce a score, not how
     * good it is.
     */
    @Test
    public void analysisRunsWithMwMlAbsent() throws IOException {
        File wav = folder.newFile("triads.wav");
        writeChordLoop(wav);

        List<String> stages = new ArrayList<>();
        Score score = MwAnalysis.analyze(wav, stages::add);

        assertNotNull(score);
        assertTrue("the transcriber should have reported its stages", stages.size() > 3);
        assertTrue("the recording carries a pulse, so a tempo should come out",
                score.estimatedTempo() > 0);
        assertTrue("the beat tracker found nothing at all",
                score.beatGrid().isPresent() && !score.beatGrid().get().beats().isEmpty());
        assertFalse("no chord spans came out of the estimator", score.chords().isEmpty());

        String chart = MwAnalysis.chartText(score);
        assertFalse(chart.isEmpty());
        assertTrue(chart, chart.contains("Tempo"));
        assertTrue(MwAnalysis.summary(score).contains("quarter notes/min"));
    }

    /** The cache beside the audio, written and read back. */
    @Test
    public void theScoreCacheRoundTripsBesideTheAudio() throws IOException {
        File wav = folder.newFile("cached.wav");
        writeChordLoop(wav);
        Score score = MwAnalysis.analyze(wav, null);

        File cache = MwAnalysis.scoreFileFor(wav);
        assertEquals("cached.score.json", cache.getName());
        assertEquals(wav.getParentFile(), cache.getParentFile());
        assertNull("nothing is cached before an analysis is written",
                MwAnalysis.readCache(cache));

        assertNull("the cache should have been written", MwAnalysis.writeCache(cache, score));
        Score reloaded = MwAnalysis.readCache(cache);
        assertNotNull(reloaded);
        // The chart is what the result screen shows, so it is the thing that
        // has to survive the round trip -- not merely some field of the score.
        assertEquals(MwAnalysis.chartText(score), MwAnalysis.chartText(reloaded));
        // estimatedTempo() is derived rather than serialized; it has to come
        // out of the reloaded object all the same.
        assertEquals(score.estimatedTempo(), reloaded.estimatedTempo(), 1e-9);
    }

    /**
     * A cache that cannot be written costs the caching, not the analysis.
     *
     * <p>Provoked here by a path that cannot hold a file. The case that makes
     * this branch matter on a phone is different and cannot be reproduced on a
     * JVM — Jackson cannot serialize the model's records once D8 has desugared
     * them, which is every Android version below 35 — but it arrives at exactly
     * this branch, and what the branch does is what is being pinned.
     */
    @Test
    public void aCacheThatCannotBeWrittenIsReportedRatherThanThrown() throws IOException {
        File notADirectory = folder.newFile("in-the-way");
        File impossible = new File(notADirectory, "nested.score.json");
        File wav = folder.newFile("short.wav");
        writeChordLoop(wav);
        Score score = MwAnalysis.analyze(wav, null);

        assertEquals(MwAnalysis.CACHE_UNAVAILABLE_NOTE, MwAnalysis.writeCache(impossible, score));
        assertFalse(impossible.isFile());
        // And the score itself is untouched: the chart is still there to show.
        assertFalse(MwAnalysis.chartText(score).isEmpty());
    }

    /** A cache that cannot be parsed is treated as no cache, not as an error. */
    @Test
    public void anUnreadableCacheIsIgnored() throws IOException {
        File cache = folder.newFile("broken.score.json");
        try (OutputStream out = new FileOutputStream(cache)) {
            out.write("{ this is not a score".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertNull(MwAnalysis.readCache(cache));
    }

    /** A recording with no audio in it is reported rather than analysed. */
    @Test
    public void anEmptyRecordingIsRejected() throws IOException {
        File wav = folder.newFile("empty.wav");
        try (OutputStream out = new FileOutputStream(wav)) {
            out.write(WavFile.header(MwAnalysis.RECORD_SAMPLE_RATE, 1, 0));
        }
        try {
            MwAnalysis.analyze(wav, null);
            fail("expected an empty recording to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no audio"));
        }
    }

    /**
     * Eight seconds of four plucked triads at 120 BPM, written as the app records.
     *
     * <p>Each beat is a fresh attack with an exponential decay, so the onset
     * detector has something to find; the triads change every two beats so the
     * chord estimator does.
     */
    private static void writeChordLoop(File file) throws IOException {
        int rate = MwAnalysis.RECORD_SAMPLE_RATE;
        double beat = 0.5;
        int beats = 16;
        // C major, F major, G major, C major -- two beats each.
        double[][] chords = {
                {261.63, 329.63, 392.00},
                {349.23, 440.00, 523.25},
                {392.00, 493.88, 587.33},
                {261.63, 329.63, 392.00},
        };

        int frames = (int) (beats * beat * rate);
        byte[] audio = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = i / (double) rate;
            int beatIndex = (int) (t / beat);
            double[] chord = chords[(beatIndex / 2) % chords.length];
            double intoBeat = t - beatIndex * beat;
            double envelope = Math.exp(-6 * intoBeat);
            double value = 0;
            for (double partial : chord) {
                // A second harmonic at a third of the amplitude: a bare sine
                // triad has no partial structure for the chroma to fold.
                value += Math.sin(2 * Math.PI * partial * t)
                        + 0.33 * Math.sin(4 * Math.PI * partial * t);
            }
            int sample = (int) Math.round(envelope * value / 4.0 * 20000);
            sample = Math.max(-32768, Math.min(32767, sample));
            audio[2 * i] = (byte) (sample & 0xFF);
            audio[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        try (OutputStream out = new FileOutputStream(file)) {
            out.write(WavFile.header(rate, 1, audio.length));
            out.write(audio);
        }
    }
}
