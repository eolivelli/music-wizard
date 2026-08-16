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
 * speed, while sequential {@code Range} requests run at full speed — measured
 * at nearly two orders of magnitude apart on one track. A "tidy-up" to a
 * single request would look obviously correct and make every import
 * unusable.
 *
 * <p>Sequential rather than parallel: chunked, a whole track already fetches
 * in seconds, and concurrency would buy nothing for three more ways to
 * fail.
 *
 * <p>Redirects are followed here rather than by the transport, and the {@code
 * Range} header is set again on every hop. A client that drops it on redirect
 * gets a {@code 403} on the second chunk, which reads exactly like bot detection
 * and costs a day.
 *
 * <p>Several things below are refused rather than worked around, and they share
 * one reason worth stating once: the alternative is a container of the right
 * length holding the wrong bytes, which still decodes. A download that fails is
 * a message on a screen; a download that succeeds and is wrong is a chart of a
 * song nobody played.
 */
public final class StreamDownload {

    /**
     * Big enough that the per-request overhead vanishes, small enough to cancel
     * promptly.
     *
     * <p>Package-private so the tests read this rather than restating it: three
     * copies of a constant is how a change to one of them turns into three test
     * failures about arithmetic instead of one about behaviour.
     */
    static final int CHUNK_BYTES = 1 << 20;

    /** googlevideo redirects once in practice; five is room to be wrong. */
    private static final int MAX_HOPS = 5;

    /** Transient failures worth one more go before the whole fetch is abandoned. */
    private static final int CHUNK_ATTEMPTS = 3;

    private static final long RETRY_BACKOFF_MILLIS = 500;

    /** A 403 is the server asking for a pause, so it gets a real one. */
    private static final long THROTTLED_BACKOFF_MILLIS = 1_500;

    private final Http http;
    private final Trace trace;
    private final long retryBackoffMillis;
    private final long throttledBackoffMillis;

    public StreamDownload(Http http) {
        this(http, Trace.NONE);
    }

    public StreamDownload(Http http, Trace trace) {
        this(http, trace, RETRY_BACKOFF_MILLIS, THROTTLED_BACKOFF_MILLIS);
    }

    /** Visible for tests, which have no reason to spend the waits. */
    StreamDownload(Http http, long retryBackoffMillis, long throttledBackoffMillis) {
        this(http, Trace.NONE, retryBackoffMillis, throttledBackoffMillis);
    }

    StreamDownload(Http http, Trace trace, long retryBackoffMillis,
            long throttledBackoffMillis) {
        this.http = http;
        this.trace = trace;
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
     * connection, a 500. A redirect off https, a redirect loop, and a server
     * that ignores {@code Range} are not: they are the server saying something
     * this app will refuse every time, so retrying just spends three attempts
     * arriving at the same place.
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
        // Before the file is opened, so a format this will never fetch does not
        // cost the caller the file it handed over.
        declaredLength(stream);

        boolean complete = false;
        try {
            // Closed inside, so that a close which itself fails — a deferred
            // out-of-space on a FUSE-backed cache directory is the way that
            // happens — is a failure like any other rather than one reported
            // beside a file left on disk.
            try (OutputStream out = new FileOutputStream(target)) {
                writeTo(out, stream, progress, cancelled);
            }
            complete = true;
        } finally {
            if (!complete) {
                deleteQuietly(target);
            }
        }
    }

    /**
     * The same, into any sink.
     *
     * <p>Visible for tests, which need one that can fail partway through a write —
     * the case that decides whether a failed write is retried into a duplicate,
     * and which cannot be produced through a {@link File}.
     */
    void writeTo(OutputStream out, AudioStream stream, Progress progress,
            BooleanSupplier cancelled) throws IOException {
        long total = declaredLength(stream);

        // Where the last redirect led. Carried between chunks so the hop is
        // walked once rather than once per megabyte.
        String url = stream.url();
        long done = 0;

        while (done < total) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("the download was cancelled");
            }
            long last = Math.min(done + CHUNK_BYTES, total) - 1;
            Chunk chunk = fetch(url, done, last, cancelled);
            url = chunk.url;

            // Outside the retry, deliberately: see fetch's javadoc.
            out.write(chunk.data);
            done += chunk.data.length;
            progress.onProgress(done, total);
        }

        if (done != total) {
            throw new IOException("the download ended at " + done + " of " + total + " bytes");
        }
    }

    /** The length to fetch and verify against, refusing a format that declares none. */
    private static long declaredLength(AudioStream stream) throws IOException {
        long total = stream.contentLength();
        if (total <= 0) {
            throw new IOException("YouTube declared no length for this audio format.");
        }
        return total;
    }

    /** One range's bytes, and the URL they finally came from. */
    private static final class Chunk {

        final byte[] data;
        final String url;

        Chunk(byte[] data, String url) {
            this.data = data;
            this.url = url;
        }
    }

    /**
     * One range, retried on a transient failure.
     *
     * <p>The chunk is assembled in memory and handed back rather than written as
     * it arrives, and the caller writes it only once this has returned. Both
     * halves of that matter, and each closes a way of splicing the file in the
     * middle:
     *
     * <ul>
     *   <li>A connection that dies mid-chunk must contribute nothing, or the
     *       retry's bytes land behind the failed attempt's.
     *   <li>A <em>write</em> that fails partway — a full cache partition — must
     *       not be retried as though the download had failed, or the bytes it
     *       did write are joined by a whole second copy while this reports only
     *       one. That leaves the file and the caller's count permanently
     *       disagreeing, which the total check at the end cannot see.
     * </ul>
     *
     * <p><strong>A 403 is retried here rather than believed.</strong> It
     * reads like an expired link and usually is not: measured against the
     * live endpoint it arrives partway through a fetch rather than on the
     * first chunk, which is a rate limit on quick successive ranges and not a
     * URL that has gone bad, and waiting clears it. Only a 403 that survives
     * every attempt is reported as expiry, which is what makes {@link Fetch}'s
     * resolve-and-start-again worth doing at all.
     */
    private Chunk fetch(String url, long from, long to, BooleanSupplier cancelled)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= CHUNK_ATTEMPTS; attempt++) {
            try {
                return readRange(url, from, to, cancelled);
            } catch (InterruptedIOException | FatalIOException fatal) {
                throw fatal;
            } catch (IOException failure) {
                last = failure;
                trace.line("  attempt " + attempt + " failed: " + failure.getMessage());
                if (attempt < CHUNK_ATTEMPTS) {
                    backoff(attempt, failure instanceof ExpiredException);
                }
            }
        }
        // Still an ExpiredException if that is what it was, so the caller can
        // tell "resolve it again" from "give up".
        throw last;
    }

    private Chunk readRange(String url, long from, long to, BooleanSupplier cancelled)
            throws IOException {
        String target = url;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Range", "bytes=" + from + "-" + to);

            try (Http.Content content =
                    http.open(new Http.Request("GET", target, headers, null))) {
                int status = content.status();
                // Host and status, never the URL: see Trace.
                trace.line("range " + from + "-" + to + " -> HTTP " + status
                        + " from " + hostOf(target)
                        + (hop > 0 ? " (hop " + hop + ")" : ""));

                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    target = redirect(target, content.header("Location"));
                    trace.line("  redirected to " + hostOf(target));
                    continue;
                }
                if (status == 403 || status == 410) {
                    throw new ExpiredException(
                            "the media link is no longer valid (HTTP " + status + ")");
                }
                if (status == 200) {
                    // The range was ignored, so this is the file from byte zero.
                    // Harmless for the first chunk; for any later one, capping
                    // the read would file the opening bytes under an offset they
                    // do not belong to. An intermediary that strips Range — a
                    // captive portal, a carrier proxy — is the realistic cause,
                    // and there is nothing to retry, so say so.
                    if (from != 0) {
                        throw new FatalIOException("the server ignored the range request"
                                + " and answered from the start of the file");
                    }
                } else if (status != 206) {
                    throw new IOException("the server answered HTTP " + status
                            + " for bytes " + from + "-" + to);
                } else {
                    verifyRange(content.header("Content-Range"), from);
                }
                return new Chunk(read(content.stream(), to - from + 1, cancelled), target);
            }
        }
        throw new FatalIOException(
                "the media link redirected more than " + MAX_HOPS + " times");
    }

    /**
     * Checks that a 206 is answering the range that was asked for.
     *
     * <p>A reply that starts somewhere else would be written where the asked-for
     * bytes belong. The header is advisory — its absence is not treated as a
     * failure — but when it is there it is believed.
     */
    private static void verifyRange(String contentRange, long from) throws IOException {
        if (contentRange == null || contentRange.isEmpty()) {
            return;
        }
        String value = contentRange.trim();
        int space = value.indexOf(' ');
        int dash = value.indexOf('-', space + 1);
        if (space < 0 || dash < 0) {
            return;
        }
        try {
            long start = Long.parseLong(value.substring(space + 1, dash).trim());
            if (start != from) {
                throw new FatalIOException("the server answered with bytes from " + start
                        + " when " + from + " was asked for");
            }
        } catch (NumberFormatException unreadable) {
            // An unparseable header is not evidence of anything; the total length
            // check at the end is the backstop.
        }
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

    /** Reads at most {@code expected} bytes, stopping early only on cancellation. */
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

    /** Just the host, which is the part worth reporting and the part that is safe. */
    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "?" : host;
        } catch (URISyntaxException unreadable) {
            return "?";
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}
