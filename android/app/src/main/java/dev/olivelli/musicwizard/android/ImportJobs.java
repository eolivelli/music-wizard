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
import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.mw.TakeSource;
import dev.olivelli.musicwizard.android.yt.ExtractionException;
import dev.olivelli.musicwizard.android.yt.Fetch;
import dev.olivelli.musicwizard.android.yt.UrlConnectionHttp;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The import running in the background, and who is watching it.
 *
 * <p>{@link AnalysisJobs}'s sibling, and a separate one rather than a reuse:
 * that class is keyed by a WAV that already exists and its result is a
 * {@code Score}. This one has no take to key on until it has finished making
 * one. It is process-wide for the same reason though — a fetch must survive the
 * screen that started it, a rotation, and a trip to the home screen.
 *
 * <p>One import at a time. A second is refused rather than queued, which is
 * {@code BundleShare}'s rule: two concurrent downloads on a phone buy nothing
 * and double the memory.
 *
 * <p>Threading: one worker does the fetch and the decode; all state here is read
 * and written on the dispatcher's thread only, and the worker reaches it by
 * posting. Nothing is synchronized because there is no second thread to
 * synchronize with — except {@link #cancelled}, which is written by the
 * dispatcher's thread and read by the worker, and is atomic for exactly that.
 */
final class ImportJobs {

    /** What a screen watching an import is told. Always on the dispatcher's thread. */
    interface Listener {

        /** A stage line, and 0..100, or -1 when the size is not known. */
        void onProgress(String line, int percent);

        /** The take is in the library, at this path. */
        void onFinished(File wav);

        void onFailed(String message);

        /** Stopped because it was asked to, which is not a failure. */
        void onCancelled();
    }

    /** Where callbacks land. A test supplies a queue it drains itself. */
    interface Dispatcher {
        void post(Runnable action);
    }

    /** Shared text in, a downloaded container out. The network half. */
    interface Fetcher {
        Fetch.Fetched fetch(String shareText, File directory, Progress progress,
                java.util.function.BooleanSupplier cancelled)
                throws ExtractionException, IOException;
    }

    /** A container in, a mono WAV out. The framework half. */
    interface Decoder {
        int decode(File source, File target, Progress progress,
                java.util.function.BooleanSupplier cancelled) throws IOException;
    }

    /** Progress from either half, in bytes or as a fraction. */
    interface Progress {
        void onProgress(double fraction);
    }

    /** How an import ended. */
    static final class Result {

        /** The take, or null when it failed or was cancelled. */
        final File wav;

        /** Why there is no take, or null. */
        final String failure;

        final boolean cancelled;

        private Result(File wav, String failure, boolean cancelled) {
            this.wav = wav;
            this.failure = failure;
            this.cancelled = cancelled;
        }

        static Result of(File wav) {
            return new Result(wav, null, false);
        }

        static Result failed(String failure) {
            return new Result(null, failure, false);
        }

        static Result cancelled() {
            return new Result(null, null, true);
        }
    }

    /**
     * The download is most of the wait, the decode the rest.
     *
     * <p>Split so a bar that reaches the end of the download does not sit at
     * 100% through a decode that is still going.
     */
    private static final int DOWNLOAD_SHARE = 70;

    /**
     * Refuse to start when the phone is this close to full.
     *
     * <p>Sized for the worst case {@link Fetch#MAX_SECONDS} allows rather than
     * for a typical song, because the length is not known until the fetch has
     * already begun: twenty minutes of Opus is around 24 MB, and the WAV decoded
     * from it is 48000 × 2 bytes a second — over 100 MB — with both alive at
     * once. Failing before the download is a better answer than failing after
     * it.
     */
    private static final long MIN_FREE_BYTES = 250L * 1024 * 1024;

    private static ImportJobs instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mw-import");
        thread.setDaemon(true);
        return thread;
    });

    private final Dispatcher dispatcher;
    private final Fetcher fetcher;
    private final Decoder decoder;

    private boolean running;
    private String progressLine = "";
    private int progressPercent = -1;
    private Listener listener;
    private Result finished;
    private AtomicBoolean cancelled = new AtomicBoolean(false);

    ImportJobs(Dispatcher dispatcher, Fetcher fetcher, Decoder decoder) {
        this.dispatcher = dispatcher;
        this.fetcher = fetcher;
        this.decoder = decoder;
    }

    /** The single instance. Main thread only. */
    static ImportJobs get() {
        if (instance == null) {
            Handler main = new Handler(Looper.getMainLooper());
            Fetch fetch = new Fetch(new UrlConnectionHttp());
            instance = new ImportJobs(main::post,
                    (text, directory, progress, stop) -> fetch.run(text, directory,
                            (done, total) -> progress.onProgress(
                                    total > 0 ? done / (double) total : -1),
                            stop),
                    (source, target, progress, stop) -> AudioImport.decodeToWav(source, target,
                            progress::onProgress, stop));
        }
        return instance;
    }

    boolean isRunning() {
        return running;
    }

    String progressLine() {
        return progressLine;
    }

    int progressPercent() {
        return progressPercent;
    }

    /** How the last import ended, or null if none has. */
    Result lastResult() {
        return finished;
    }

    /** Forgets the last result, once a screen has acted on it. */
    void clearResult() {
        finished = null;
    }

    /**
     * Starts an import, unless one is already running.
     *
     * @return false when one was already running and this was refused
     */
    boolean start(String shareText, File cacheDirectory, RecordingStore store,
            Listener watcher) {
        if (running) {
            return false;
        }
        finished = null;
        running = true;
        progressLine = "";
        progressPercent = -1;
        listener = watcher;
        cancelled = new AtomicBoolean(false);
        AtomicBoolean stop = cancelled;

        worker.submit(() -> run(shareText, cacheDirectory, store, stop));
        return true;
    }

    /** Watches an import already running. */
    boolean observe(Listener watcher) {
        if (!running) {
            return false;
        }
        listener = watcher;
        watcher.onProgress(progressLine, progressPercent);
        return true;
    }

    void stopObserving(Listener watcher) {
        if (listener == watcher) {
            listener = null;
        }
    }

    /** Asks the running import to stop. It reports as cancelled, not failed. */
    void cancel() {
        cancelled.set(true);
    }

    private void run(String shareText, File cacheDirectory, RecordingStore store,
            AtomicBoolean stop) {
        Result result;
        File container = null;
        File decoded = null;
        try {
            prune(cacheDirectory);
            if (cacheDirectory.getUsableSpace() < MIN_FREE_BYTES) {
                throw new IOException("there is not enough free space on this phone");
            }
            report("downloading", 0);

            Fetch.Fetched fetched = fetcher.fetch(shareText, cacheDirectory,
                    fraction -> report("downloading", scale(fraction, 0, DOWNLOAD_SHARE)),
                    stop::get);
            container = fetched.file();

            report("decoding", DOWNLOAD_SHARE);
            decoded = new File(cacheDirectory, fetched.videoId() + ".wav");
            decoder.decode(container, decoded,
                    fraction -> report("decoding", scale(fraction, DOWNLOAD_SHARE, 100)),
                    stop::get);

            report("saving", 100);
            result = Result.of(store(store, decoded, fetched));
            decoded = null;
        } catch (InterruptedIOException cancelledMidway) {
            result = Result.cancelled();
        } catch (ExtractionException refused) {
            result = Result.failed(refused.getMessage());
        } catch (Throwable failure) {
            // Throwable, in the shape AnalysisJobs uses: an Error that vanished
            // into the executor's Future would leave the screen waiting for a
            // callback that is never coming.
            result = Result.failed(describe(failure));
        } finally {
            deleteQuietly(container);
            deleteQuietly(decoded);
        }

        Result outcome = result;
        dispatcher.post(() -> finish(outcome));
    }

    /** Moves the decoded take into the library, or leaves the library as it was. */
    private static File store(RecordingStore store, File decoded, Fetch.Fetched fetched)
            throws IOException {
        File placed = store.newRecordingFile(Instant.now(), ZoneId.systemDefault());
        if (!decoded.renameTo(placed)) {
            java.nio.file.Files.move(decoded.toPath(), placed.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        RecordingStore.Recording recording = new RecordingStore.Recording(placed);
        boolean marked = false;
        try {
            String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                    .format(Instant.now().atZone(ZoneId.systemDefault()));
            // Written before the take is given its final name, so the audio is
            // never in the library under any name without saying where it came
            // from.
            RecordingStore.writeSource(recording,
                    TakeSource.youtube(fetched.url(), fetched.title(), when).toText());
            // Seeded rather than left empty, so the fact travels in the player's
            // own field too — which is what the desktop's report quotes — and so
            // the user sees it and can add to it. Blank line last, so their words
            // start on a line of their own.
            RecordingStore.writeNotes(recording,
                    "Imported from YouTube: " + fetched.title() + "\n"
                            + fetched.url() + "\n\n");
            recording = named(store, recording, fetched.title());
            // Read back rather than assumed: RecordingStore.rename carries the
            // side files best-effort and can leave one behind with the audio
            // already moved.
            marked = TakeSource.parse(RecordingStore.readSource(recording)).isCommercial();
            if (!marked) {
                throw new IOException("the take could not be marked as imported");
            }
            return recording.wav();
        } finally {
            if (!marked) {
                // The user has been told the import failed, so nobody goes
                // looking: what is left behind has to be nothing. Both stems,
                // because the path that gets here is usually a rename that moved
                // the audio and left a side file under the name it came from.
                store.delete(recording);
                if (!placed.equals(recording.wav())) {
                    store.delete(new RecordingStore.Recording(placed));
                }
            }
        }
    }

    /**
     * Gives the take the video's name, or keeps the timestamp if it cannot.
     *
     * <p>Ten copies of one video is not a case to design for beyond not
     * crashing, so the suffixes stop and the timestamped name stands.
     */
    private static RecordingStore.Recording named(RecordingStore store,
            RecordingStore.Recording recording, String title) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            try {
                return store.rename(recording, attempt == 1 ? title : title + " " + attempt);
            } catch (IOException taken) {
                // Either the name is unusable at all, in which case every attempt
                // fails and the timestamp stands, or it is taken and the next
                // suffix is tried.
            }
        }
        return recording;
    }

    /**
     * Ends the job, and hands the outcome to a screen if one is still watching.
     *
     * <p>A result that reached a listener is <em>consumed</em>, and that is the
     * load-bearing half. {@link #lastResult} exists for the screen that was away
     * when the job ended; retaining a result the screen has already acted on
     * turns the next share into a replay of the last one — a new link would be
     * swallowed and the previous take reopened, with no fetch and nothing to say
     * why.
     */
    private void finish(Result result) {
        running = false;
        progressPercent = -1;
        Listener watcher = listener;
        if (watcher == null) {
            finished = result;
            return;
        }
        finished = null;
        if (result.cancelled) {
            watcher.onCancelled();
        } else if (result.failure != null) {
            watcher.onFailed(result.failure);
        } else {
            watcher.onFinished(result.wav);
        }
    }

    private void report(String line, int percent) {
        dispatcher.post(() -> {
            progressLine = line;
            progressPercent = percent;
            if (listener != null) {
                listener.onProgress(line, percent);
            }
        });
    }

    private static int scale(double fraction, int from, int to) {
        if (fraction < 0) {
            return -1;
        }
        return from + (int) Math.round(Math.max(0, Math.min(1, fraction)) * (to - from));
    }

    /** Anything left by an import the process did not outlive. */
    private static void prune(File directory) {
        File[] stale = directory.listFiles();
        if (stale != null) {
            for (File file : stale) {
                deleteQuietly(file);
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    /** {@code AnalysisJobs.describe}'s rule: a message when there is one, else the type. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
