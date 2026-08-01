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
import static org.junit.Assert.assertFalse;
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
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * How many callbacks the worker has handed over.
     *
     * <p>The handover is the only thing this test can wait on when the case
     * under test has deliberately detached the screen: with no listener to
     * observe and no result to appear, counting posts is what says the worker
     * has finished rather than not started.
     */
    private final AtomicInteger posts = new AtomicInteger();

    private File wav;

    @Before
    public void setUp() throws IOException {
        wav = folder.newFile("take.wav");
    }

    /** The stand-in main looper, counting what it is handed. */
    private AnalysisJobs.Dispatcher dispatcher() {
        return action -> {
            mainThread.add(action);
            posts.incrementAndGet();
        };
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
        assertNull("a failed analysis has no score to keep", jobs.lastResult(wav).score);
        assertEquals("but it is what the screen coming back must be told",
                first.failure, jobs.lastResult(wav).failure);

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
        assertNotNull("the screen that asked second is the one watching now", second.finished);
        assertNull("the screen it replaced should no longer be told", screen.finished);
    }

    /**
     * A screen that comes back to a running analysis watches it.
     *
     * <p>{@link AnalysisJobs#observe} is the first thing
     * {@code ResultActivity.onResume} calls, and the three states it has to
     * separate are all here: running, so watch it; finished, so there is
     * nothing to watch and {@link AnalysisJobs#lastResult} answers instead; and
     * never started.
     */
    @Test
    public void aScreenComingBackWatchesTheRunningAnalysisAndThenReadsItsResult() {
        Score result = aScore();
        AnalysisJobs jobs = new AnalysisJobs(dispatcher(), (file, progress) -> {
            progress.accept("detecting onsets");
            return result;
        });

        Screen never = new Screen();
        assertFalse("nothing has been started for this take",
                jobs.observe(wav, never));

        Screen leaving = new Screen();
        jobs.start(wav, leaving);
        jobs.stopObserving(leaving);

        Screen returning = new Screen();
        assertTrue("a running analysis must be watched, not restarted",
                jobs.observe(wav, returning));

        pumpUntil(() -> returning.finished != null);
        assertSame(result, returning.finished);
        assertTrue("the stage line reached the screen that came back",
                returning.progress.contains("detecting onsets"));
        assertNull("the screen that left should have heard nothing", leaving.finished);

        Screen later = new Screen();
        assertFalse("a finished analysis is not something to watch",
                jobs.observe(wav, later));
        assertSame("and lastResult is what answers for it",
                result, jobs.lastResult(wav).score);
    }

    /**
     * A failure with no message to report is still a failure.
     *
     * <p>{@code describe} allocates, and it runs inside the catch of the error
     * this class exists to survive: an {@code OutOfMemoryError} there leaves the
     * completion with neither a score nor a reason. Treating that as success
     * hands the screen a null score — an immediate crash drawing the chart, and
     * then the same crash on every later attempt to open the take, because the
     * null is the retained result too.
     */
    @Test
    public void aCompletionWithNeitherScoreNorReasonIsReportedAsAFailure() {
        List<File> attempts = new ArrayList<>();
        Score eventually = aScore();
        AnalysisJobs jobs = new AnalysisJobs(dispatcher(), (file, progress) -> {
            attempts.add(file);
            if (attempts.size() == 1) {
                throw new SpeechlessError();
            }
            return eventually;
        });

        Screen screen = new Screen();
        jobs.start(wav, screen);
        pumpUntil(() -> screen.failure != null || screen.finished != null);

        assertNull("a completion with no score is not a finished analysis", screen.finished);
        assertNotNull("the screen was left with nothing to show", screen.failure);
        assertNull("nor may a scoreless result be kept", jobs.lastResult(wav).score);
        assertNotNull(jobs.lastResult(wav).failure);

        Screen retry = new Screen();
        jobs.start(wav, retry);
        pumpUntil(() -> retry.finished != null);
        assertSame(eventually, retry.finished);
    }

    /**
     * A failed re-analysis is the current answer; the run before it is not.
     *
     * <p>Analyse, re-analyse, have the second run fail, and rotate the phone.
     * If only successes were kept, the screen coming back would draw the first
     * run's chart with nothing to say the re-run failed — the previous answer
     * presented as this one's, on the screen whose purpose is reading what
     * changed between runs.
     */
    @Test
    public void aFailedReanalysisSupersedesTheScoreBeforeIt() {
        List<File> attempts = new ArrayList<>();
        Score first = aScore();
        AnalysisJobs jobs = new AnalysisJobs(dispatcher(), (file, progress) -> {
            attempts.add(file);
            if (attempts.size() == 1) {
                return first;
            }
            throw new IOException("the recording holds no audio");
        });

        Screen screen = new Screen();
        jobs.start(wav, screen);
        pumpUntil(() -> screen.finished != null);
        assertSame(first, jobs.lastResult(wav).score);

        Screen reanalyzing = new Screen();
        jobs.start(wav, reanalyzing);
        jobs.stopObserving(reanalyzing);
        pumpUntil(() -> jobs.lastResult(wav) != null
                && jobs.lastResult(wav).failure != null);

        assertNull("the superseded score must not come back", jobs.lastResult(wav).score);
        assertEquals("the recording holds no audio", jobs.lastResult(wav).failure);
    }

    /**
     * A renamed take keeps the analysis it already has.
     *
     * <p>The store moves {@code score.json} with the audio; below Android 35
     * that file does not exist, so the copy in memory is the analysis, and it is
     * keyed by a path that the rename has just changed.
     */
    @Test
    public void aRenamedTakeKeepsItsAnalysis() {
        Score result = aScore();
        AnalysisJobs jobs = new AnalysisJobs(dispatcher(), (file, progress) -> result);
        File renamed = new File(folder.getRoot(), "renamed.wav");

        Screen screen = new Screen();
        jobs.start(wav, screen);
        pumpUntil(() -> screen.finished != null);

        jobs.moved(wav, renamed);
        assertSame("the analysis was left behind under the old name",
                result, jobs.lastResult(renamed).score);
        assertNull("and must not answer for the name it no longer has",
                jobs.lastResult(wav));
    }

    /** A take renamed while it is being analysed is still the take being analysed. */
    @Test
    public void aTakeRenamedMidAnalysisIsStillTheTakeBeingAnalysed() {
        Score result = aScore();
        AnalysisJobs jobs = new AnalysisJobs(dispatcher(), (file, progress) -> result);
        File renamed = new File(folder.getRoot(), "renamed.wav");

        Screen screen = new Screen();
        jobs.start(wav, screen);
        jobs.stopObserving(screen);
        // Before anything is drained, so the completion is still in flight.
        jobs.moved(wav, renamed);

        pumpUntil(() -> posts.get() >= 1);
        assertSame(result, jobs.lastResult(renamed).score);
        assertNull(jobs.lastResult(wav));
    }

    /**
     * A deleted take takes its analysis with it.
     *
     * <p>Both halves matter, and the second is the one that shows wrong data
     * rather than none: {@code RecordingStore.rename} refuses only names that
     * are taken, so a deleted take's name is free for another take to be
     * renamed onto, and it would inherit a chart computed from audio it has
     * never held.
     */
    @Test
    public void aDeletedTakeLeavesNothingForTheNextTakeOfThatName() {
        Score result = aScore();
        AnalysisJobs jobs = new AnalysisJobs(dispatcher(), (file, progress) -> result);

        Screen screen = new Screen();
        jobs.start(wav, screen);
        pumpUntil(() -> screen.finished != null);
        jobs.forget(wav);
        assertNull("a deleted take has no analysis", jobs.lastResult(wav));

        // And one deleted while it was being analysed does not file its result
        // under the freed name afterwards.
        posts.set(0);
        Screen watching = new Screen();
        jobs.start(wav, watching);
        jobs.forget(wav);
        pumpUntil(() -> posts.get() >= 1);
        assertNull("the analysis of a deleted take was filed under its name anyway",
                jobs.lastResult(wav));
    }

    /** An error that cannot even say what it is; see {@code describe}. */
    private static final class SpeechlessError extends Error {

        SpeechlessError() {
            super(null, null, false, false);
        }

        @Override
        public String getMessage() {
            throw new OutOfMemoryError("no room left to describe the last one");
        }
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
