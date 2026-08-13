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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Fetches one audio format to a file, a megabyte at a time.
 *
 * <p><strong>The chunking is not an optimisation and must not be simplified
 * away.</strong> YouTube paces an unranged {@code GET} at roughly playback
 * speed: measured on a 3.4 MB track, one plain request delivered 4 MB in 107
 * seconds — 0.03 MB/s — while the same file fetched as sequential 1 MiB {@code
 * Range} requests took 1.62 seconds, 2.13 MB/s. That is sixty-six times, and a
 * "tidy-up" to a single request would look obviously correct and make every
 * import unusable.
 *
 * <p>Sequential rather than parallel: at 2 MB/s a four-minute track is already
 * under two seconds, and concurrency would buy nothing for three more ways to
 * fail.
 *
 * <p>Redirects are followed here rather than by the transport, and the {@code
 * Range} header is set again on every hop. A client that drops it on redirect
 * gets a {@code 403} on the second chunk, which reads exactly like bot detection
 * and costs a day.
 */
public final class StreamDownload {

    /** Big enough that the per-request overhead vanishes, small enough to cancel promptly. */
    private static final int CHUNK_BYTES = 1 << 20;

    /** googlevideo redirects once in practice; five is room to be wrong. */
    private static final int MAX_HOPS = 5;

    /** Transient failures worth one more go before the whole fetch is abandoned. */
    private static final int CHUNK_ATTEMPTS = 3;

    private static final long RETRY_BACKOFF_MILLIS = 500;

    /** A 403 is the server asking for a pause, so it gets a real one. */
    private static final long THROTTLED_BACKOFF_MILLIS = 1_500;

    private final Http http;
    private final long retryBackoffMillis;
    private final long throttledBackoffMillis;

    public StreamDownload(Http http) {
        this(http, RETRY_BACKOFF_MILLIS, THROTTLED_BACKOFF_MILLIS);
    }

    /** Visible for tests, which have no reason to spend the waits. */
    StreamDownload(Http http, long retryBackoffMillis, long throttledBackoffMillis) {
        this.http = http;
        this.retryBackoffMillis = retryBackoffMillis;
        this.throttledBackoffMillis = throttledBackoffMillis;
    }

    /** Progress, in bytes written of bytes expected. */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /**
     * The media URL has expired or been revoked.
     *
     * <p>Its own type because the cure is specific and cheap: resolve the video
     * again and start over with fresh URLs. They last about six hours, so this
     * is rare in a fetch that begins immediately, and possible in one that was
     * confirmed and left.
     */
    public static final class ExpiredException extends IOException {

        private static final long serialVersionUID = 1L;

        public ExpiredException(String message) {
            super(message);
        }
    }

    /**
     * A failure that retrying cannot mend.
     *
     * <p>Most {@code IOException}s here are worth another go — a dropped
     * connection, a 500. A redirect off https and a redirect loop are not: they
     * are the server saying something this app will refuse every time, so
     * retrying just spends three attempts arriving at the same place.
     */
    private static final class FatalIOException extends IOException {

        private static final long serialVersionUID = 1L;

        FatalIOException(String message) {
            super(message);
        }

        FatalIOException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Writes {@code stream} to {@code target}, whole or not at all.
     *
     * @throws InterruptedIOException when {@code cancelled} went true; the target
     *                                is deleted first
     * @throws ExpiredException       when the URL is no longer good and the caller
     *                                should resolve the video again
     */
    public void to(File target, AudioStream stream, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        long total = stream.contentLength();
        if (total <= 0) {
            throw new IOException("YouTube declared no length for this audio format.");
        }

        boolean complete = false;
        try (OutputStream out = new FileOutputStream(target)) {
            long done = 0;
            while (done < total) {
                if (cancelled.getAsBoolean()) {
                    throw new InterruptedIOException("the download was cancelled");
                }
                long last = Math.min(done + CHUNK_BYTES, total) - 1;
                done += chunk(stream.url(), done, last, out, cancelled);
                progress.onProgress(done, total);
            }

            if (done != total) {
                throw new IOException("the download ended at " + done + " of " + total + " bytes");
            }
            complete = true;
        } finally {
            if (!complete) {
                // A partial container decodes to a truncated take, and MW would
                // analyse the short version without complaint.
                deleteQuietly(target);
            }
        }
    }

    /**
     * One range, retried on a transient failure.
     *
     * <p>The chunk is assembled in memory and only then written on. A retry that
     * appended straight to the file would leave the bytes of the failed attempt
     * in front of the bytes of the successful one — a container corrupted in the
     * middle, which decodes far enough to look like it worked.
     *
     * <p><strong>A 403 is retried here rather than believed.</strong> It reads
     * like an expired link and usually is not: measured against the live
     * endpoint, the first chunk of a fetch never returned one in six attempts,
     * while a whole four-chunk fetch failed one time in four — so it arrives
     * partway through, which is a rate limit on quick successive ranges and not
     * a URL that has gone bad. Waiting clears it. Only a 403 that survives every
     * attempt is reported as expiry, which is what makes
     * {@link Fetch}'s resolve-and-start-again worth doing at all.
     */
    private long chunk(String url, long from, long to, OutputStream out, BooleanSupplier cancelled)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= CHUNK_ATTEMPTS; attempt++) {
            try {
                byte[] data = readRange(url, from, to, cancelled);
                out.write(data);
                return data.length;
            } catch (InterruptedIOException | FatalIOException fatal) {
                throw fatal;
            } catch (IOException failure) {
                last = failure;
                if (attempt < CHUNK_ATTEMPTS) {
                    backoff(attempt, failure instanceof ExpiredException);
                }
            }
        }
        // Still an ExpiredException if that is what it was, so the caller can
        // tell "resolve it again" from "give up".
        throw last;
    }

    private byte[] readRange(String url, long from, long to, BooleanSupplier cancelled)
            throws IOException {
        String target = url;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Range", "bytes=" + from + "-" + to);

            try (Http.Content content =
                    http.open(new Http.Request("GET", target, headers, null))) {
                int status = content.status();

                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    target = redirect(target, content.header("Location"));
                    continue;
                }
                if (status == 403 || status == 410) {
                    throw new ExpiredException(
                            "the media link is no longer valid (HTTP " + status + ")");
                }
                if (status != 206 && status != 200) {
                    throw new IOException("the server answered HTTP " + status
                            + " for bytes " + from + "-" + to);
                }
                return read(content.stream(), to - from + 1, cancelled);
            }
        }
        throw new FatalIOException(
                "the media link redirected more than " + MAX_HOPS + " times");
    }

    private static String redirect(String from, String location) throws IOException {
        if (location == null || location.isEmpty()) {
            throw new FatalIOException("the server redirected without saying where");
        }
        try {
            URI resolved = new URI(from).resolve(location);
            if (!"https".equalsIgnoreCase(resolved.getScheme())) {
                throw new FatalIOException("refusing a redirect that is not https: " + resolved);
            }
            return resolved.toString();
        } catch (URISyntaxException malformed) {
            throw new FatalIOException("the server redirected somewhere unreadable: " + location,
                    malformed);
        }
    }

    /**
     * Reads at most {@code expected} bytes, stopping early only on cancellation.
     *
     * <p>A server answering 200 rather than 206 sends the whole file for every
     * range asked, so the count is capped rather than trusted; without the cap a
     * range-ignoring server would deliver the file once per chunk.
     */
    private static byte[] read(InputStream in, long expected, BooleanSupplier cancelled)
            throws IOException {
        byte[] chunk = new byte[(int) expected];
        int filled = 0;
        while (filled < expected) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("the download was cancelled");
            }
            int read = in.read(chunk, filled, (int) expected - filled);
            if (read < 0) {
                break;
            }
            filled += read;
        }
        if (filled == 0) {
            throw new IOException("the server sent nothing for this range");
        }
        if (filled == expected) {
            return chunk;
        }
        // A short range is not fatal: the loop above resumes from where this
        // left off, and a genuinely truncated file is caught by the total.
        byte[] exact = new byte[filled];
        System.arraycopy(chunk, 0, exact, 0, filled);
        return exact;
    }

    /**
     * Waits before trying a chunk again.
     *
     * <p>Longer after a 403, because that one is a rate limit and coming back
     * immediately is what provoked it.
     */
    private void backoff(int attempt, boolean rateLimited) throws InterruptedIOException {
        long millis = (rateLimited ? throttledBackoffMillis : retryBackoffMillis) * attempt;
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("the download was interrupted");
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}
