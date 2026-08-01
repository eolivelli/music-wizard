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

package dev.olivelli.musicwizard.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The lifecycle of a background analysis, driven on a JVM.
 *
 * <p>{@link AnalysisJobs} takes its dispatcher and its analyzer as constructor
 * arguments precisely so that this is possible: the dispatcher here is a queue
 * this test drains, standing in for the main looper, and the analyzer is
 * whatever the case under test needs — including one that throws an
 * {@link Error}, which is what running out of heap on a long take looks like.
 */
public class AnalysisJobsTest {

    /** Long enough for a real machine under load, short enough to fail fast. */
    private static final long TIMEOUT_MILLIS = 10_000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Posted to from the worker thread, drained from this one. */
    private final Queue<Runnable> mainThread = new ConcurrentLinkedQueue<>();

    private File wav;

    @Before
    public void setUp() throws IOException {
        wav = folder.newFile("take.wav");
    }

    /** Records what a screen was told. */
    private static final class Screen implements AnalysisJobs.Listener {

        final List<String> progress = new ArrayList<>();
        Score finished;
        String cacheNote;
        String failure;

        @Override
        public void onProgress(String line) {
            progress.add(line);
        }

        @Override
        public void onFinished(Score score, String note) {
            finished = score;
            cacheNote = note;
        }

        @Override
        public void onFailed(String message) {
            failure = message;
        }
    }

    /**
     * A finished analysis outlives the screen that asked for it.
     *
     * <p>The case: tap Analyze, then go to the home screen — or rotate, which
     * destroys and recreates the activity — before it finishes. Before this was
     * fixed the score was dropped on the floor when no listener was attached,
     * and since {@code score.json} cannot be written on Android below 35 that
     * was the only copy of it: the screen that came back showed "Not analyzed
     * yet" and a minute of DSP had to be spent again.
     */
    @Test
    public void aFinishedResultOutlivesTheScreenThatAskedForIt() {
        Score result = aScore();
        AnalysisJobs jobs = new AnalysisJobs(mainThread::add, (file, progress) -> {
            progress.accept("detecting onsets");
            return result;
        });

        Screen leaving = new Screen();
        jobs.start(wav, leaving);
        jobs.stopObserving(leaving);
        pumpUntil(() -> jobs.lastResult(wav) != null);

        assertNull("the screen was gone, so it should not have been called",
                leaving.finished);
        AnalysisJobs.Result kept = jobs.lastResult(wav);
        assertNotNull("the analysis was thrown away when nobody was watching", kept);
        assertSame(result, kept.score);
    }

    /** Only the take that was analysed is remembered, not every take ever opened. */
    @Test
    public void oneResultIsKept() throws IOException {
        AnalysisJobs jobs = new AnalysisJobs(mainThread::add, (file, progress) -> aScore());
        File other = folder.newFile("other.wav");

        Screen screen = new Screen();
        jobs.start(wav, screen);
        pumpUntil(() -> screen.finished != null);
        assertNotNull(jobs.lastResult(wav));
        assertNull("a different take has no result of its own", jobs.lastResult(other));

        Screen second = new Screen();
        jobs.start(other, second);
        pumpUntil(() -> second.finished != null);
        assertNotNull(jobs.lastResult(other));
        assertNull("the previous take's result should not be held for ever",
                jobs.lastResult(wav));
    }

    /**
     * The cache note travels with the kept result.
     *
     * <p>Provoked by a score file that cannot be written — the WAV's parent is a
     * regular file — which is the same branch Android takes for a different
     * reason, {@code MwAnalysis.writeCache} having no way to serialize a
     * desugared record.
     */
    @Test
    public void theCacheNoteIsKeptWithTheResult() throws IOException {
        File blocker = folder.newFile("blocker");
        File unwritable = new File(blocker, "take.wav");
        AnalysisJobs jobs = new AnalysisJobs(mainThread::add, (file, progress) -> aScore());

        Screen screen = new Screen();
        jobs.start(unwritable, screen);
        pumpUntil(() -> screen.finished != null);

        assertEquals(MwAnalysis.CACHE_UNAVAILABLE_NOTE, screen.cacheNote);
        assertEquals(MwAnalysis.CACHE_UNAVAILABLE_NOTE, jobs.lastResult(unwritable).cacheNote);
    }

    /**
     * An {@link Error} is reported, and does not wedge the job for ever.
     *
     * <p>{@code OutOfMemoryError} is the expected way this fails on a phone: the
     * analysis holds the whole take as {@code float[]} plus a resampled copy
     * plus an STFT matrix over it, which is why the manifest asks for a large
     * heap. It is an {@code Error}, so the previous {@code catch (Exception)}
     * missed it entirely — nothing was posted, the screen sat on its last stage
     * line, and because the job stayed {@code running} in the map every later
     * tap on Analyze short-circuited to "already running". Only killing the
     * process recovered it.
     */
    @Test
    public void anErrorIsReportedAndTheTakeCanBeAnalysedAgain() {
        List<File> attempts = new ArrayList<>();
        Score eventually = aScore();
        AnalysisJobs jobs = new AnalysisJobs(mainThread::add, (file, progress) -> {
            attempts.add(file);
            if (attempts.size() == 1) {
                throw new OutOfMemoryError("Java heap space");
            }
            return eventually;
        });

        Screen first = new Screen();
        jobs.start(wav, first);
        pumpUntil(() -> first.failure != null);

        assertNotNull("an Error escaped instead of reaching the screen", first.failure);
        assertTrue(first.failure, first.failure.contains("OutOfMemoryError"));
        assertTrue("the class name alone does not say what went wrong",
                first.failure.contains("Java heap space"));
        assertNull("a failed analysis has no result to keep", jobs.lastResult(wav));

        // And the take is not wedged: a second attempt actually runs.
        Screen retry = new Screen();
        jobs.start(wav, retry);
        pumpUntil(() -> retry.finished != null);
        assertEquals(2, attempts.size());
        assertSame(eventually, retry.finished);
    }

    /** A checked failure is reported by its message, without the class name. */
    @Test
    public void anExceptionIsReportedByItsMessage() {
        AnalysisJobs jobs = new AnalysisJobs(mainThread::add, (file, progress) -> {
            throw new IOException("the recording holds no audio");
        });

        Screen screen = new Screen();
        jobs.start(wav, screen);
        pumpUntil(() -> screen.failure != null);
        assertEquals("the recording holds no audio", screen.failure);
    }

    /** A second Analyze while one is running attaches instead of starting another. */
    @Test
    public void aRunningAnalysisIsNotStartedTwice() {
        List<File> attempts = new ArrayList<>();
        AnalysisJobs jobs = new AnalysisJobs(mainThread::add, (file, progress) -> {
            attempts.add(file);
            return aScore();
        });

        Screen screen = new Screen();
        jobs.start(wav, screen);
        // Before the worker's completion post is drained, the job is still
        // running as far as this class is concerned.
        Screen second = new Screen();
        jobs.start(wav, second);
        pumpUntil(() -> screen.finished != null || second.finished != null);

        assertEquals("the analysis should have been started once", 1, attempts.size());
    }

    /** A screen that goes away stops hearing about a job it is no longer showing. */
    @Test
    public void progressStopsAtAScreenThatWentAway() {
        AnalysisJobs jobs = new AnalysisJobs(mainThread::add, (file, progress) -> {
            progress.accept("detecting onsets");
            progress.accept("tracking beats");
            return aScore();
        });

        Screen screen = new Screen();
        jobs.start(wav, screen);
        jobs.stopObserving(screen);
        pumpUntil(() -> jobs.lastResult(wav) != null);
        assertTrue(screen.progress.toString(), screen.progress.isEmpty());
        assertEquals("a finished job is out of the map, so it reports no progress",
                "", jobs.progressOf(wav));
    }

    /** A score with no analysis behind it: these tests are about lifecycle, not DSP. */
    private static Score aScore() {
        return Score.empty(TempoMap.constantPulse(120, TimeSignature.FOUR_FOUR), 5.0);
    }

    /**
     * Drains the stand-in main thread until {@code done}, or fails.
     *
     * <p>Busy-drains rather than blocks, because the worker posts from its own
     * thread and the queue is the only handoff.
     */
    private void pumpUntil(BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (!done.getAsBoolean()) {
            Runnable action = mainThread.poll();
            if (action != null) {
                action.run();
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for the analysis to report");
            }
            Thread.yield();
        }
        // Drain whatever else the worker left, so a later assertion sees the
        // final state rather than a half-delivered one.
        for (Runnable action = mainThread.poll(); action != null; action = mainThread.poll()) {
            action.run();
        }
    }
}
