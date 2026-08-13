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

package dev.olivelli.musicwizard.android.yt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The chunked fetch, driven against a fake transport.
 *
 * <p>The behaviour worth defending here is not obvious from reading the class:
 * the file must be assembled from many ranged requests rather than one plain
 * one, because YouTube paces a plain request at roughly playback speed — 0.03
 * MB/s measured against 2.13 MB/s for the same file in 1 MiB ranges. A change
 * that collapsed this into one request would pass a test that only checked the
 * bytes came out right, so the requests themselves are asserted.
 */
public class StreamDownloadTest {

    /** Smaller than the 1 MiB chunk, so a fixture stays a fixture. */
    private static final int CHUNK = 1 << 20;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static AudioStream streamOf(long length) {
        return new AudioStream(140, "audio/mp4", "https://media.example.invalid/v",
                130_677, length, 44_100, 2, 213_000);
    }

    private static byte[] bytes(int count, int seed) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) (i + seed);
        }
        return out;
    }

    private static StreamDownload.Progress ignoringProgress() {
        return (done, total) -> { };
    }

    /** The real waits are seconds long and prove nothing here. */
    private static StreamDownload downloader(FakeHttp http) {
        return new StreamDownload(http, 0, 0);
    }

    private static final java.util.function.BooleanSupplier NEVER_CANCELLED = () -> false;

    @Test
    public void aFileIsAssembledFromSequentialRanges() throws Exception {
        byte[] whole = bytes(CHUNK * 2 + 512, 7);
        FakeHttp http = new FakeHttp()
                .content(206, Map.of(), slice(whole, 0, CHUNK))
                .content(206, Map.of(), slice(whole, CHUNK, CHUNK))
                .content(206, Map.of(), slice(whole, CHUNK * 2, 512));

        File target = folder.newFile("out.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertArrayEquals(whole, Files.readAllBytes(target.toPath()));
        assertEquals(List.of(
                        "bytes=0-" + (CHUNK - 1),
                        "bytes=" + CHUNK + "-" + (CHUNK * 2 - 1),
                        "bytes=" + (CHUNK * 2) + "-" + (CHUNK * 2 + 511)),
                ranges(http));
    }

    /** One request for a file that fits in one chunk — but still a ranged one. */
    @Test
    public void evenASmallFileIsAskedForByRange() throws Exception {
        byte[] whole = bytes(1024, 3);
        FakeHttp http = new FakeHttp().content(206, Map.of(), whole);

        File target = folder.newFile("small.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertEquals(List.of("bytes=0-1023"), ranges(http));
    }

    /**
     * A redirect must carry the range with it.
     *
     * <p>This is the bug the manual redirect handling exists for: a client that
     * follows a redirect itself drops the request headers, and the second chunk
     * then comes back 403 — which reads exactly like bot detection and sends
     * whoever debugs it a very long way in the wrong direction.
     */
    @Test
    public void aRedirectKeepsTheRangeHeader() throws Exception {
        byte[] whole = bytes(2048, 11);
        FakeHttp http = new FakeHttp()
                .content(302, Map.of("Location", "https://other.example.invalid/v2"), new byte[0])
                .content(206, Map.of(), whole);

        File target = folder.newFile("redirected.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertArrayEquals(whole, Files.readAllBytes(target.toPath()));
        assertEquals(List.of("bytes=0-2047", "bytes=0-2047"), ranges(http));
        assertEquals("https://other.example.invalid/v2", http.requests.get(1).url());
    }

    @Test
    public void aRelativeRedirectIsResolvedAgainstWhereItCameFrom() throws Exception {
        byte[] whole = bytes(512, 2);
        FakeHttp http = new FakeHttp()
                .content(302, Map.of("Location", "/elsewhere"), new byte[0])
                .content(206, Map.of(), whole);

        downloader(http).to(folder.newFile("rel.m4a"), streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertEquals("https://media.example.invalid/elsewhere", http.requests.get(1).url());
    }

    /** Downgrading to plain HTTP mid-fetch is refused rather than followed. */
    @Test
    public void aRedirectOffHttpsIsRefused() throws Exception {
        FakeHttp http = new FakeHttp()
                .content(302, Map.of("Location", "http://insecure.example.invalid/v"),
                        new byte[0]);

        File target = folder.newFile("insecure.m4a");
        assertThrows(IOException.class, () -> downloader(http)
                .to(target, streamOf(1024), ignoringProgress(), NEVER_CANCELLED));
        assertFalse(target.exists());
    }

    /**
     * A 403 that survives every attempt is reported as expiry, which has a
     * specific cure — resolve the video again — so it keeps its own type.
     */
    @Test
    public void aPersistent403IsReportedAsExpired() throws Exception {
        FakeHttp http = new FakeHttp()
                .content(403, Map.of(), new byte[0])
                .content(403, Map.of(), new byte[0])
                .content(403, Map.of(), new byte[0]);

        File target = folder.newFile("expired.m4a");
        assertThrows(StreamDownload.ExpiredException.class, () -> downloader(http)
                .to(target, streamOf(1024), ignoringProgress(), NEVER_CANCELLED));
        assertEquals(3, http.requests.size());
        assertFalse(target.exists());
    }

    /**
     * A 403 partway through is usually a rate limit, not a dead link.
     *
     * <p>Measured against the live endpoint: the first chunk of a fetch never
     * returned one in six attempts, while a whole four-chunk fetch failed one
     * time in four. Believing the first 403 made a quarter of real downloads
     * fail; waiting and asking again clears it.
     */
    @Test
    public void a403ThatClearsIsJustARateLimit() throws Exception {
        byte[] whole = bytes(1024, 21);
        FakeHttp http = new FakeHttp()
                .content(403, Map.of(), new byte[0])
                .content(206, Map.of(), whole);

        File target = folder.newFile("throttled.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertArrayEquals(whole, Files.readAllBytes(target.toPath()));
        assertEquals(2, http.requests.size());
    }

    @Test
    public void aTransientFailureIsRetriedAndTheChunkIsNotDoubled() throws Exception {
        byte[] whole = bytes(1024, 5);
        FakeHttp http = new FakeHttp()
                .content(500, Map.of(), new byte[0])
                .content(206, Map.of(), whole);

        File target = folder.newFile("retried.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertArrayEquals(whole, Files.readAllBytes(target.toPath()));
    }

    /** A range that arrives short is resumed from where it stopped, not restarted. */
    @Test
    public void aShortRangeResumesRatherThanRepeating() throws Exception {
        byte[] whole = bytes(1024, 9);
        FakeHttp http = new FakeHttp()
                .content(206, Map.of(), slice(whole, 0, 400))
                .content(206, Map.of(), slice(whole, 400, 624));

        File target = folder.newFile("partial.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertArrayEquals(whole, Files.readAllBytes(target.toPath()));
        assertEquals(List.of("bytes=0-1023", "bytes=400-1023"), ranges(http));
    }

    /**
     * A connection that dies mid-chunk must contribute nothing to the file.
     *
     * <p>This is the case the in-memory assembly exists for. Appending each read
     * straight to the file would leave the bytes of the failed attempt in front
     * of the bytes of the retry — a container corrupted in the middle, which
     * decodes far enough to look like it worked and gives MW a take with a splice
     * in it.
     */
    @Test
    public void bytesFromADroppedConnectionDoNotReachTheFile() throws Exception {
        byte[] whole = bytes(1024, 13);
        FakeHttp http = new FakeHttp()
                .contentFailingAfter(206, whole, 600)
                .content(206, Map.of(), whole);

        File target = folder.newFile("dropped.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertArrayEquals(whole, Files.readAllBytes(target.toPath()));
        assertEquals(whole.length, target.length());
        // Both attempts asked for the whole range: the first contributed nothing.
        assertEquals(List.of("bytes=0-1023", "bytes=0-1023"), ranges(http));
    }

    @Test
    public void givingUpDeletesThePartialFile() throws Exception {
        FakeHttp http = new FakeHttp()
                .content(500, Map.of(), new byte[0])
                .content(500, Map.of(), new byte[0])
                .content(500, Map.of(), new byte[0]);

        File target = folder.newFile("failed.m4a");
        assertThrows(IOException.class, () -> downloader(http)
                .to(target, streamOf(1024), ignoringProgress(), NEVER_CANCELLED));
        assertFalse(target.exists());
    }

    @Test
    public void cancellingStopsAndLeavesNothingBehind() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        byte[] whole = bytes(CHUNK * 2, 4);
        FakeHttp http = new FakeHttp()
                .content(206, Map.of(), slice(whole, 0, CHUNK))
                .content(206, Map.of(), slice(whole, CHUNK, CHUNK));

        File target = folder.newFile("cancelled.m4a");
        assertThrows(InterruptedIOException.class, () -> downloader(http)
                .to(target, streamOf(whole.length),
                        (done, total) -> cancelled.set(true), cancelled::get));

        assertFalse(target.exists());
        // Stopped between chunks rather than fetching the rest and discarding it.
        assertEquals(1, http.requests.size());
    }

    /**
     * A server that ignores the range sends the whole file for every one asked.
     *
     * <p>Without a cap the file would be written once per chunk, growing instead
     * of completing.
     */
    @Test
    public void aServerThatIgnoresRangesDoesNotMultiplyTheFile() throws Exception {
        byte[] whole = bytes(CHUNK + 100, 6);
        FakeHttp http = new FakeHttp()
                .content(200, Map.of(), whole)
                .content(200, Map.of(), whole);

        File target = folder.newFile("ignored.m4a");
        downloader(http).to(target, streamOf(whole.length),
                ignoringProgress(), NEVER_CANCELLED);

        assertEquals(whole.length, target.length());
    }

    @Test
    public void aFormatWithNoLengthIsRefusedBeforeAnyRequest() throws Exception {
        FakeHttp http = new FakeHttp();
        File target = folder.newFile("nolength.m4a");

        assertThrows(IOException.class, () -> downloader(http)
                .to(target, streamOf(0), ignoringProgress(), NEVER_CANCELLED));
        assertTrue(http.requests.isEmpty());
    }

    @Test
    public void progressCountsUpToTheDeclaredTotal() throws Exception {
        byte[] whole = bytes(CHUNK + 256, 8);
        FakeHttp http = new FakeHttp()
                .content(206, Map.of(), slice(whole, 0, CHUNK))
                .content(206, Map.of(), slice(whole, CHUNK, 256));

        List<Long> seen = new ArrayList<>();
        downloader(http).to(folder.newFile("progress.m4a"), streamOf(whole.length),
                (done, total) -> {
                    assertEquals(whole.length, total);
                    seen.add(done);
                }, NEVER_CANCELLED);

        assertEquals(List.of((long) CHUNK, (long) whole.length), seen);
    }

    @Test
    public void aRedirectLoopGivesUp() throws Exception {
        FakeHttp http = new FakeHttp();
        for (int i = 0; i < 10; i++) {
            http.content(302, Map.of("Location", "https://media.example.invalid/loop"),
                    new byte[0]);
        }

        File target = folder.newFile("loop.m4a");
        assertThrows(IOException.class, () -> downloader(http)
                .to(target, streamOf(1024), ignoringProgress(), NEVER_CANCELLED));
        assertFalse(target.exists());
    }

    private static List<String> ranges(FakeHttp http) {
        List<String> out = new ArrayList<>();
        for (Http.Request request : http.requests) {
            out.add(request.headers().get("Range"));
        }
        return out;
    }

    private static byte[] slice(byte[] whole, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(whole, from, out, 0, length);
        return out;
    }
}
