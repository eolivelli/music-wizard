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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The transport, behind an interface so the requests can be asserted on a JVM.
 *
 * <p>{@link GitHubReporter} assembles three requests and reads three replies,
 * and that assembly is the part that is easy to get wrong and impossible to see
 * from the outside: a wrong header or a wrong URL comes back as a 404 that looks
 * like a missing release. A test supplies its own {@code Http}, checks each
 * request, and hands back a canned reply; {@link UrlConnectionHttp} is the
 * implementation the app runs.
 */
public interface Http {

    /**
     * Sends a request and reads the whole reply.
     *
     * @throws IOException when the request never got an answer — no network, DNS,
     *                     TLS, a timeout. A reply with an error status is not an
     *                     exception here; it is a {@link Response} to interpret.
     */
    Response send(Request request) throws IOException;

    /** Method, URL, headers and an optional body. */
    final class Request {

        private final String method;
        private final String url;
        private final Map<String, String> headers;
        private final Body body;

        public Request(String method, String url, Map<String, String> headers, Body body) {
            this.method = method;
            this.url = url;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body;
        }

        public String method() {
            return method;
        }

        public String url() {
            return url;
        }

        public Map<String, String> headers() {
            return headers;
        }

        /** The body, or null for a request that has none. */
        public Body body() {
            return body;
        }
    }

    /** What came back: the status and the whole body, error bodies included. */
    final class Response {

        private final int status;
        private final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * A request body of known length.
     *
     * <p>Streamed rather than handed over as an array: a take is the whole point
     * of the upload and can be tens of megabytes, and the phone should not hold
     * a second copy of it just to send it.
     */
    interface Body {

        String contentType();

        long length();

        void writeTo(OutputStream out) throws IOException;

        static Body json(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return new Body() {
                @Override
                public String contentType() {
                    return "application/json; charset=utf-8";
                }

                @Override
                public long length() {
                    return bytes.length;
                }

                @Override
                public void writeTo(OutputStream out) throws IOException {
                    out.write(bytes);
                }
            };
        }

        static Body file(File file, String contentType) {
            return new Body() {
                @Override
                public String contentType() {
                    return contentType;
                }

                @Override
                public long length() {
                    return file.length();
                }

                @Override
                public void writeTo(OutputStream out) throws IOException {
                    try (InputStream in = new FileInputStream(file)) {
                        byte[] buffer = new byte[1 << 16];
                        for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
            };
        }
    }
}
