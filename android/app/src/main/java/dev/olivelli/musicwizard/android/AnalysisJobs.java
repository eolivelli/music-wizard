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

/**
 * Analyses running in the background, and who is watching them.
 *
 * <p>Process-wide rather than owned by an activity, because analysing a take is
 * the one thing in the app that outlives a screen: a rotation, or a trip to the
 * home screen and back, must not restart a minute of DSP or lose its result.
 * The result screen attaches to whatever is already running for its file.
 *
 * <p>Threading: one worker thread runs the analysis; <em>all</em> state in this
 * class is read and written on the main thread only, and the worker reaches it
 * exclusively by posting. That is why nothing here is synchronized — there is
 * no second thread to synchronize with.
 */
final class AnalysisJobs {

    /** What a screen watching an analysis is told. Always on the main thread. */
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

    /** One analysis of one file. */
    private static final class Job {
        String progress = "";
        boolean running = true;
        Listener listener;
        String cacheNote;
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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Job> jobs = new HashMap<>();

    private AnalysisJobs() {
    }

    /** The single instance. Main thread only. */
    static AnalysisJobs get() {
        if (instance == null) {
            instance = new AnalysisJobs();
        }
        return instance;
    }

    /** The last stage line reported for a file, or empty if none yet. */
    String progressOf(File wav) {
        Job job = jobs.get(key(wav));
        return job == null ? "" : job.progress;
    }

    /**
     * Starts analysing {@code wav}, unless it already is.
     *
     * <p>The cache is written before the listener hears about it, so a screen
     * that reloads immediately finds it on disk. Whether it could be written is
     * passed on rather than assumed -- see {@link MwAnalysis#writeCache}.
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
     * <p>Called when a screen goes away. The analysis keeps running: it is the
     * expensive thing, and the job is kept until it finishes so that a screen
     * coming back attaches to it rather than starting a second one.
     */
    void stopObserving(Listener listener) {
        for (Job job : jobs.values()) {
            if (job.listener == listener) {
                job.listener = null;
            }
        }
    }

    private void run(String key, File wav, Job job) {
        try {
            Score score = MwAnalysis.analyze(wav, line -> main.post(() -> {
                job.progress = line;
                if (job.listener != null) {
                    job.listener.onProgress(line);
                }
            }));
            // A cache that could not be written does not spoil an analysis
            // that succeeded; the screen says so and shows the chart.
            String cacheNote = MwAnalysis.writeCache(MwAnalysis.scoreFileFor(wav), score);
            main.post(() -> {
                job.cacheNote = cacheNote;
                finish(key, job, score, null);
            });
        } catch (Exception e) {
            // Anything at all: a broken WAV, a stage that could not cope with
            // the audio, or a device that ran out of memory part-way. The
            // screen says so and the take is still on disk to try again.
            String message = e.getMessage() != null && !e.getMessage().isEmpty()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            main.post(() -> finish(key, job, null, message));
        }
    }

    private void finish(String key, Job job, Score score, String failure) {
        job.running = false;
        Listener listener = job.listener;
        // Removed on completion: the job's only job was to survive the screen,
        // and a map that keeps every file ever analysed is a leak with a
        // gentler name.
        jobs.remove(key);
        if (listener == null) {
            return;
        }
        if (failure != null) {
            listener.onFailed(failure);
        } else {
            listener.onFinished(score, job.cacheNote);
        }
    }

    private static String key(File wav) {
        return wav.getAbsolutePath();
    }
}
