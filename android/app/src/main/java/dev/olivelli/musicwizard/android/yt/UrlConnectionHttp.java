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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * {@link Http} on {@code HttpURLConnection} — the platform's own client, so the
 * app takes no dependency to make its calls.
 *
 * <p>Bodies are sent with {@code setFixedLengthStreamingMode}. Without it
 * {@code HttpURLConnection} buffers the whole request in memory to work out its
 * length, on a heap that is already sized for the analysis.
 *
 * <p>Redirects are <em>not</em> followed here. That is deliberate and it is the
 * one surprising thing about this class: a redirect handled inside the platform
 * client drops the request headers on the new hop, and losing {@code Range}
 * turns a partial fetch into a {@code 403} that reads exactly like bot
 * detection. {@link StreamDownload} follows them itself, re-setting its headers
 * each time, and can be tested doing so because the hop comes back through this
 * interface as an ordinary reply.
 */
public final class UrlConnectionHttp implements Http {

    /** Enough for a phone that has drifted out of coverage to give up. */
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;

    /**
     * The wait for one packet, not for a whole transfer: a megabyte chunk over a
     * slow link is many short reads, none of which should trip this.
     */
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    @Override
    public Response send(Request request) throws IOException {
        HttpURLConnection connection = connect(request);
        try {
            int status = connection.getResponseCode();
            Map<String, String> headers = Http.firstValues(connection.getHeaderFields());
            try (InputStream reply = decoded(connection, body(connection, status))) {
                return new Response(status, headers, read(reply));
            }
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public Content open(Request request) throws IOException {
        HttpURLConnection connection = connect(request);
        int status;
        InputStream stream;
        try {
            status = connection.getResponseCode();
            stream = decoded(connection, body(connection, status));
        } catch (IOException | RuntimeException failure) {
            connection.disconnect();
            throw failure;
        }
        Map<String, String> headers = Http.firstValues(connection.getHeaderFields());
        return new ConnectionContent(connection, status, headers, stream);
    }

    private static HttpURLConnection connect(Request request) throws IOException {
        URL url = new URL(request.url());
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("refusing a request that is not https: " + request.url());
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(request.method());
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }

        Body body = request.body();
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", body.contentType());
            connection.setFixedLengthStreamingMode(body.length());
            try (OutputStream out = connection.getOutputStream()) {
                body.writeTo(out);
            }
        }
        return connection;
    }

    /**
     * The error stream, not the input stream, once the status is 4xx or 5xx:
     * {@code getInputStream()} throws there, and YouTube puts the reason a
     * request was refused in exactly that body.
     */
    private static InputStream body(HttpURLConnection connection, int status) throws IOException {
        return status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    }

    /**
     * Unwraps gzip when the reply says it is gzipped.
     *
     * <p>{@code HttpURLConnection} does this itself for an {@code Accept-Encoding}
     * it added, but not for one the caller set — and the player request sets it,
     * because the response is 75 KB of JSON.
     */
    private static InputStream decoded(HttpURLConnection connection, InputStream in)
            throws IOException {
        if (in == null) {
            return null;
        }
        String encoding = connection.getContentEncoding();
        return "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(in) : in;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 13];
        for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** A reply still on the wire, holding its connection open until closed. */
    private static final class ConnectionContent implements Content {

        private final HttpURLConnection connection;
        private final int status;
        private final Map<String, String> headers;
        private final InputStream stream;

        ConnectionContent(HttpURLConnection connection, int status,
                Map<String, String> headers, InputStream stream) {
            this.connection = connection;
            this.status = status;
            this.headers = headers;
            this.stream = stream == null ? InputStream.nullInputStream() : stream;
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public String header(String name) {
            return Http.lookup(headers, name);
        }

        @Override
        public InputStream stream() {
            return stream;
        }

        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Closing a stream the caller has abandoned mid-chunk is expected
                // to fail; the disconnect below is what actually frees the socket.
            }
            connection.disconnect();
        }
    }
}
