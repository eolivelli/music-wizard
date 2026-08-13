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
import static org.junit.Assert.assertTrue;

import dev.olivelli.musicwizard.android.mw.RecordingStore;
import dev.olivelli.musicwizard.android.mw.TakeSource;
import dev.olivelli.musicwizard.android.mw.WavFile;
import dev.olivelli.musicwizard.android.mw.WavWriter;
import dev.olivelli.musicwizard.android.yt.Fetch;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The import's lifecycle, driven on a JVM.
 *
 * <p>Neither half of the real work is here — the network is
 * {@code FetchTest}'s and the decode can only be run on a device — so what this
 * pins is everything around them: that a fetch outlives the screen that started
 * it, that a failure is reported once and cleaned up after, and that a take
 * reaching the library carries the provenance the desktop needs. That last one
 * is the reason this file exists at all.
 */
public class ImportJobsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File cache;
    private RecordingStore store;
    /**
     * Concurrent, because the worker posts into it while the test thread drains
     * it — a plain ArrayDeque here dropped runnables and threw
     * ArrayIndexOutOfBoundsException, which shows up as a rare hang in whichever
     * test happened to lose the race.
     */
    private final Queue<Runnable> posted = new ConcurrentLinkedQueue<>();

    /** Runs callbacks when the test says so, standing in for the main looper. */
    private final ImportJobs.Dispatcher dispatcher = posted::add;

    @Before
    public void setUp() throws IOException {
        cache = folder.newFolder("imports");
        store = new RecordingStore(folder.newFolder("recordings"));
    }

    private void drain() {
        while (!posted.isEmpty()) {
            posted.poll().run();
        }
    }

    /** Waits for the worker to hand its result to the dispatcher. */
    private void settle(ImportJobs jobs) throws InterruptedException {
        for (int i = 0; i < 200 && jobs.isRunning(); i++) {
            drain();
            if (!jobs.isRunning()) {
                break;
            }
            Thread.sleep(10);
        }
        drain();
    }

    private ImportJobs.Fetcher fetcherWriting(String title) {
        return (shareText, directory, progress, cancelled) -> {
            File container = new File(directory, "video.m4a");
            java.nio.file.Files.write(container.toPath(), new byte[] {1, 2, 3});
            progress.onProgress(1);
            return fetched(container, title);
        };
    }

    /** A decoder that writes a real, readable WAV, so the take can be inspected. */
    private static ImportJobs.Decoder decoderWriting(int rate, int frames) {
        return (source, target, progress, cancelled) -> {
            try (WavWriter writer = new WavWriter(target, rate)) {
                writer.write(new short[frames], frames, 1);
                writer.finish();
            }
            progress.onProgress(1);
            return rate;
        };
    }

    /** The real value type, so the fields a take is named and marked from are the real ones. */
    private static Fetch.Fetched fetched(File file, String title) {
        return new Fetch.Fetched(file, "dQw4w9WgXcQ", title, "Someone", 213);
    }

    private static final class Watcher implements ImportJobs.Listener {
        File finished;
        String failure;
        boolean cancelled;
        int progressCalls;

        @Override
        public void onProgress(String line, int percent) {
            progressCalls++;
        }

        @Override
        public void onFinished(File wav) {
            finished = wav;
        }

        @Override
        public void onFailed(String message) {
            failure = message;
        }

        @Override
        public void onCancelled() {
            cancelled = true;
        }
    }

    @Test
    public void aFinishedImportIsATakeNamedAfterTheVideo() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher,
                fetcherWriting("Rick Astley - Never Gonna Give You Up"),
                decoderWriting(48_000, 100));
        Watcher watcher = new Watcher();

        assertTrue(jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher));
        settle(jobs);

        assertNull(watcher.failure);
        assertNotNull(watcher.finished);
        assertEquals("Rick Astley - Never Gonna Give You Up",
                new RecordingStore.Recording(watcher.finished).displayName());
        assertEquals(1, store.list().size());
        // The decoder's rate, carried rather than converted.
        assertEquals(48_000, WavFile.readFormat(watcher.finished).sampleRate());
    }

    /**
     * The take carries where it came from.
     *
     * <p>The load-bearing assertion in this file: without it the desktop cannot
     * tell a commercial recording from a field recording, and the two have
     * different destinations in the corpus.
     */
    @Test
    public void aFinishedImportIsMarkedAsCommercialAudio() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        RecordingStore.Recording take = new RecordingStore.Recording(watcher.finished);
        TakeSource source = TakeSource.parse(RecordingStore.readSource(take));
        assertTrue("an imported take was not marked as commercial", source.isCommercial());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", source.url());
        // And in the player's field too, which is what the desktop report quotes.
        assertTrue(RecordingStore.readNotes(take).contains("dQw4w9WgXcQ"));
    }

    @Test
    public void theCacheIsEmptyWhenAnImportFinishes() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                decoderWriting(44_100, 10));
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, new Watcher());
        settle(jobs);

        assertEquals("the container or the decoded copy outlived the import",
                0, cache.listFiles().length);
    }

    @Test
    public void aFailedFetchIsReportedAndLeavesNoTake() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher,
                (shareText, directory, progress, cancelled) -> {
                    throw new IOException("no route to host");
                },
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertEquals("no route to host", watcher.failure);
        assertNull(watcher.finished);
        assertTrue(store.list().isEmpty());
        assertEquals(0, cache.listFiles().length);
    }

    /** A decode that fails must not leave a half-take in the library. */
    @Test
    public void aFailedDecodeLeavesTheLibraryUntouched() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                (source, target, progress, cancelled) -> {
                    throw new IOException("the decoder produced no audio");
                });
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertEquals("the decoder produced no audio", watcher.failure);
        assertTrue(store.list().isEmpty());
        assertEquals(0, cache.listFiles().length);
    }

    /** Cancellation is its own outcome: the user asked, so it is not a failure. */
    @Test
    public void cancellingIsReportedAsCancelledRatherThanFailed() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher,
                (shareText, directory, progress, cancelled) -> {
                    throw new InterruptedIOException("the download was cancelled");
                },
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertTrue(watcher.cancelled);
        assertNull(watcher.failure);
        assertTrue(store.list().isEmpty());
    }

    /**
     * An {@code Error} is reported, not swallowed.
     *
     * <p>{@code AnalysisJobs}'s rule: one that vanished into the executor's
     * {@code Future} would leave the screen waiting for a callback that never
     * comes, with the progress bar still turning.
     */
    @Test
    public void anErrorIsReportedRatherThanLostInTheExecutor() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher,
                (shareText, directory, progress, cancelled) -> {
                    throw new OutOfMemoryError("Java heap space");
                },
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertEquals("Java heap space", watcher.failure);
        assertFalse(jobs.isRunning());
    }

    /** A second share while one is running is refused, and the running one survives. */
    @Test
    public void aSecondImportIsRefusedWhileOneIsRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ImportJobs jobs = new ImportJobs(dispatcher,
                (shareText, directory, progress, cancelled) -> {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    File container = new File(directory, "video.m4a");
                    java.nio.file.Files.write(container.toPath(), new byte[] {1});
                    return fetched(container, "Some Song");
                },
                decoderWriting(44_100, 10));

        Watcher first = new Watcher();
        assertTrue(jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, first));
        assertFalse("a second import was allowed to start",
                jobs.start("https://youtu.be/9bZkp7q19f0", cache, store, new Watcher()));

        release.countDown();
        settle(jobs);

        assertNotNull("the running import did not survive the refused one", first.finished);
        assertEquals(1, store.list().size());
    }

    /**
     * A result outlives the screen that started it.
     *
     * <p>A rotation, or a trip to the home screen and back, must not lose a
     * finished download — the take is already on disk and the screen coming
     * back has to be able to find out.
     */
    @Test
    public void aResultSurvivesTheScreenThatStartedIt() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);

        // The screen goes away before the worker finishes.
        jobs.stopObserving(watcher);
        settle(jobs);

        assertNull("a detached screen was still called back", watcher.finished);
        ImportJobs.Result last = jobs.lastResult();
        assertNotNull("the result was lost with the screen", last);
        assertNotNull(last.wav);
        assertTrue(last.wav.isFile());

        jobs.clearResult();
        assertNull(jobs.lastResult());
    }

    /** Anything an interrupted process left behind goes before the next fetch. */
    @Test
    public void staleCacheFilesArePrunedBeforeAnImport() throws Exception {
        File stale = new File(cache, "left-behind.part");
        java.nio.file.Files.write(stale.toPath(), new byte[1024]);

        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                decoderWriting(44_100, 10));
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, new Watcher());
        settle(jobs);

        assertFalse(stale.exists());
    }

    /** A title the store cannot use leaves the timestamped name rather than failing. */
    @Test
    public void anUnusableTitleKeepsTheTimestampedName() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("..."),
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertNotNull(watcher.finished);
        assertTrue(watcher.finished.getName(), watcher.finished.getName().endsWith(".wav"));
        assertEquals(1, store.list().size());
        // Still marked, whatever it ended up called.
        assertTrue(TakeSource.parse(RecordingStore.readSource(
                new RecordingStore.Recording(watcher.finished))).isCommercial());
    }

    /**
     * A result the screen has already been given is not handed out again.
     *
     * <p>Retaining it turns the next share into a replay: a fresh screen finds
     * the old result before it has a chance to fetch, opens the previous take,
     * and the new link is never fetched or even shown. Two shares in one process
     * is the ordinary case, so this is not an edge.
     */
    @Test
    public void aDeliveredResultIsNotHandedOutAgain() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertNotNull("the watching screen was never told", watcher.finished);
        assertNull("the next share would reopen this take instead of fetching",
                jobs.lastResult());
    }

    /** The same for a failure the screen was told about. */
    @Test
    public void aDeliveredFailureIsNotHandedOutAgain() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher,
                (shareText, directory, progress, cancelled) -> {
                    throw new IOException("no route to host");
                },
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertEquals("no route to host", watcher.failure);
        assertNull(jobs.lastResult());
    }

    /**
     * Commercial audio never sits in the library unmarked.
     *
     * <p>If anything fails between moving the take in and marking it, the take
     * has to go with it. The user has been told the import failed, so nobody
     * goes looking — and what is left behind is indistinguishable from a field
     * recording, which is the one thing the committed corpus is allowed to hold.
     *
     * <p>The failure is planted where a full disk would land: the move into the
     * library succeeds, and writing the provenance beside it does not. A
     * directory standing at the {@code .source.txt} path does that reliably,
     * and the take's stem is a timestamp, so a few seconds' worth are blocked to
     * cover the clock ticking mid-test.
     */
    @Test
    public void aFailureAfterTheMoveLeavesNoUnmarkedTake() throws Exception {
        store.ensureDirectory();
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
        Instant now = Instant.now();
        for (int second = 0; second <= 3; second++) {
            String stem = stamp.format(now.plusSeconds(second).atZone(ZoneId.systemDefault()));
            assertTrue(new File(store.directory(), stem + ".source.txt").mkdirs());
        }

        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                decoderWriting(44_100, 10));
        Watcher watcher = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, watcher);
        settle(jobs);

        assertNotNull("the failure was not reported", watcher.failure);
        assertNull(watcher.finished);
        assertTrue("an unmarked take was left in the library: " + store.list(),
                store.list().isEmpty());
    }

    /** A second import of the same video does not collide with the first. */
    @Test
    public void aRepeatedImportGetsItsOwnName() throws Exception {
        ImportJobs jobs = new ImportJobs(dispatcher, fetcherWriting("Some Song"),
                decoderWriting(44_100, 10));

        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, new Watcher());
        settle(jobs);
        Watcher second = new Watcher();
        jobs.start("https://youtu.be/dQw4w9WgXcQ", cache, store, second);
        settle(jobs);

        assertNotNull(second.finished);
        assertEquals(2, store.list().size());
    }
}
