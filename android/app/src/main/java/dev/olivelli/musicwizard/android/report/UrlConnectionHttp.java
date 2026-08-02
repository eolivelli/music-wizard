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

package dev.olivelli.musicwizard.android.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link Http} on {@code HttpURLConnection} — the platform's own client, so the
 * app takes no dependency to make three calls.
 *
 * <p>Bodies are sent with {@code setFixedLengthStreamingMode}. Without it
 * {@code HttpURLConnection} buffers the whole request in memory to work out its
 * length, which for a take is the file all over again on a heap that is already
 * sized for the analysis.
 */
public final class UrlConnectionHttp implements Http {

    /** Enough for a phone that has drifted out of coverage to give up. */
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;

    /**
     * Generous, because the second call is an upload over whatever the field
     * has. This is the wait for one packet, not for the whole transfer.
     */
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    @Override
    public Response send(Request request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
        try {
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

            int status = connection.getResponseCode();
            // The error stream, not the input stream, once the status is 4xx or
            // 5xx: getInputStream() throws there, and GitHub puts the reason a
            // request was refused in exactly that body.
            InputStream reply = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            return new Response(status, read(reply));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 13];
            for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
