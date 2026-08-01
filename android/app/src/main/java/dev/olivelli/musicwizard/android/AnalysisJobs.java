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

import android.os.Handler;
import android.os.Looper;
import dev.olivelli.musicwizard.android.mw.MwAnalysis;
import dev.olivelli.musicwizard.core.model.Score;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Analyses running in the background, and who is watching them.
 *
 * <p>Process-wide rather than owned by an activity, because analysing a take is
 * the one thing in the app that outlives a screen: a rotation, or a trip to the
 * home screen and back, must not restart a minute of DSP or lose its result.
 *
 * <p>It holds the last finished result as well as the running ones. That is not
 * a nicety: with {@code score.json} unwritable on Android below 35 (see
 * {@link MwAnalysis#writeCache}) this object is the <em>only</em> place a
 * finished analysis lives, and dropping it when the screen happens to be away
 * would mean a minute of DSP thrown away for a rotation.
 *
 * <p>Threading: one worker thread runs the analysis; <em>all</em> state in this
 * class is read and written on the dispatcher's thread only — the main looper in
 * the app — and the worker reaches it exclusively by posting. That is why
 * nothing here is synchronized: there is no second thread to synchronize with.
 */
final class AnalysisJobs {

    /** What a screen watching an analysis is told. Always on the dispatcher's thread. */
    interface Listener {

        /** A stage started; the transcriber's own wording. */
        void onProgress(String line);

        /**
         * The analysis finished.
         *
         * @param cacheNote null when the result was cached beside the audio,
         *                  otherwise {@code MwAnalysis.CACHE_UNAVAILABLE_NOTE}
         *                  — the score is good either way
         */
        void onFinished(Score score, String cacheNote);

        void onFailed(String message);
    }

    /**
     * Where callbacks land.
     *
     * <p>The app's posts to the main looper. A test supplies a queue it drains
     * itself, which is what lets the lifecycle behaviour here be exercised on a
     * JVM instead of argued about.
     */
    interface Dispatcher {
        void post(Runnable action);
    }

    /**
     * The analysis a job runs.
     *
     * <p>A seam for the same reason: a test needs to make an analysis fail in a
     * particular way — including with an {@link Error} — without spending a
     * second of real DSP to get there.
     */
    interface Analyzer {
        Score analyze(File wav, Consumer<String> progress) throws Exception;
    }

    /** A finished analysis, and whether it reached the disk. */
    static final class Result {

        final Score score;
        final String cacheNote;

        Result(Score score, String cacheNote) {
            this.score = score;
            this.cacheNote = cacheNote;
        }
    }

    /** One analysis of one file. */
    private static final class Job {
        String progress = "";
        boolean running = true;
        Listener listener;
    }

    private static AnalysisJobs instance;

    /**
     * One at a time, deliberately.
     *
     * <p>The analysis is CPU-bound over the whole recording and allocates a
     * spectrogram the size of it; two at once on a phone is how the app gets
     * killed for memory rather than how it gets faster.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mw-analysis");
        thread.setDaemon(true);
        return thread;
    });

    private final Dispatcher dispatcher;
    private final Analyzer analyzer;
    private final Map<String, Job> jobs = new HashMap<>();

    /**
     * The most recent finished analysis, by file.
     *
     * <p>One entry, not a growing cache: the screen that comes back is always
     * the one for the take just analysed, and holding every score ever produced
     * would be a memory leak with a friendlier name.
     */
    private String finishedKey;
    private Result finishedResult;

    AnalysisJobs(Dispatcher dispatcher, Analyzer analyzer) {
        this.dispatcher = dispatcher;
        this.analyzer = analyzer;
    }

    /** The single instance. Main thread only. */
    static AnalysisJobs get() {
        if (instance == null) {
            Handler main = new Handler(Looper.getMainLooper());
            instance = new AnalysisJobs(main::post, MwAnalysis::analyze);
        }
        return instance;
    }

    /** The last stage line reported for a file, or empty if none yet. */
    String progressOf(File wav) {
        Job job = jobs.get(key(wav));
        return job == null ? "" : job.progress;
    }

    /**
     * The most recent finished analysis of a file, or null.
     *
     * <p>Checked <em>before</em> the file beside the audio, because when a
     * re-analysis succeeded and could not be cached, the disk still holds the
     * previous one and this holds the new one.
     */
    Result lastResult(File wav) {
        return key(wav).equals(finishedKey) ? finishedResult : null;
    }

    /**
     * Starts analysing {@code wav}, unless it already is.
     *
     * <p>Whether the result could be cached is passed on rather than assumed —
     * see {@link MwAnalysis#writeCache}.
     */
    void start(File wav, Listener listener) {
        String key = key(wav);
        Job existing = jobs.get(key);
        if (existing != null && existing.running) {
            existing.listener = listener;
            listener.onProgress(existing.progress);
            return;
        }

        Job job = new Job();
        job.listener = listener;
        jobs.put(key, job);

        worker.submit(() -> run(key, wav, job));
    }

    /**
     * Watches an analysis already running for {@code wav}.
     *
     * @return true when there was one to watch
     */
    boolean observe(File wav, Listener listener) {
        Job job = jobs.get(key(wav));
        if (job == null || !job.running) {
            return false;
        }
        job.listener = listener;
        listener.onProgress(job.progress);
        return true;
    }

    /**
     * Stops delivering to {@code listener}.
     *
     * <p>Called when a screen goes away. The analysis keeps running, because it
     * is the expensive thing, and its result is kept in {@link #lastResult} for
     * whichever screen comes back to it.
     */
    void stopObserving(Listener listener) {
        for (Job job : jobs.values()) {
            if (job.listener == listener) {
                job.listener = null;
            }
        }
    }

    private void run(String key, File wav, Job job) {
        Score analysed = null;
        String note = null;
        String failure = null;
        try {
            analysed = analyzer.analyze(wav, line -> dispatcher.post(() -> {
                job.progress = line;
                if (job.listener != null) {
                    job.listener.onProgress(line);
                }
            }));
            // A cache that could not be written does not spoil an analysis that
            // succeeded; the screen says so and shows the chart.
            note = MwAnalysis.writeCache(MwAnalysis.scoreFileFor(wav), analysed);
        } catch (Throwable t) {
            // Throwable, not Exception. A long take on a mid-range phone runs
            // out of heap -- the manifest asks for a large one precisely because
            // the default is not enough -- and OutOfMemoryError is an Error. An
            // Exception-only catch let it escape into the executor's Future,
            // where nothing reads it: no callback, no log, and a job left
            // "running" forever so that every retry short-circuited.
            analysed = null;
            failure = describe(t);
        } finally {
            // In a finally block so that the screen is answered even if the
            // catch itself could not run -- an unattended screen is the failure
            // this whole class exists to prevent.
            Score score = analysed;
            String cacheNote = note;
            String message = failure;
            dispatcher.post(() -> finish(key, job, score, cacheNote, message));
        }
    }

    /**
     * A sentence for the screen.
     *
     * <p>An {@link Error} is named by its class: "Java heap space" on its own
     * does not tell anyone their recording was too long for this phone.
     */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        boolean hasMessage = message != null && !message.isEmpty();
        if (failure instanceof Error) {
            return hasMessage
                    ? failure.getClass().getSimpleName() + ": " + message
                    : failure.getClass().getSimpleName();
        }
        return hasMessage ? message : failure.getClass().getSimpleName();
    }

    private void finish(String key, Job job, Score score, String cacheNote, String failure) {
        job.running = false;
        Listener listener = job.listener;
        // Removed on completion: a job's only purpose was to survive the screen,
        // and a map that keeps every file ever analysed is a leak with a gentler
        // name. What is kept instead is the one result, below.
        jobs.remove(key);

        if (failure == null) {
            finishedKey = key;
            finishedResult = new Result(score, cacheNote);
        }
        if (listener == null) {
            // No screen is attached. The result is not lost: lastResult() hands
            // it to whichever screen opens this take next.
            return;
        }
        if (failure != null) {
            listener.onFailed(failure);
        } else {
            listener.onFinished(score, cacheNote);
        }
    }

    private static String key(File wav) {
        return wav.getAbsolutePath();
    }
}
