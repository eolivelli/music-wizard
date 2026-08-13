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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The transport, behind an interface so the requests can be asserted on a JVM.
 *
 * <p>{@link InnerTube} assembles a request whose every header and body field
 * matters, and that assembly is the part that is easy to get wrong and
 * impossible to see from the outside: a wrong client name or a missing
 * {@code visitorData} comes back as {@code LOGIN_REQUIRED}, which is
 * indistinguishable from the bot check that the same field exists to pass. A
 * test supplies its own {@code Http}, checks each request and hands back a
 * canned reply; {@link UrlConnectionHttp} is the implementation the app runs.
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

    /**
     * Sends a request and hands back the reply unread, for a body too big to hold.
     *
     * <p>The player response is 75 KB and {@link #send} suits it. One chunk of
     * audio is a megabyte and there may be dozens of them, so those are copied
     * straight to disk rather than through a {@code String}. The caller closes
     * what comes back.
     */
    Content open(Request request) throws IOException;

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

    /**
     * What came back: the status and the whole body, errors included.
     *
     * <p>No headers, because nothing reads one on this path — the player call
     * cares only about the JSON. The ranged fetch does read headers, and it
     * uses {@link Content}.
     */
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
     * A reply whose body is still on the wire.
     *
     * <p>{@link #stream()} may be read once. Closing this closes the connection
     * whether or not the body was read to its end, which is what makes a
     * cancelled download hang up rather than drain.
     */
    interface Content extends Closeable {

        int status();

        /** A header by name, case-insensitively, or null. */
        String header(String name);

        InputStream stream();
    }

    /** A request body of known length. */
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
    }

    /**
     * Finds a header whatever case it arrived in.
     *
     * <p>HTTP header names are case-insensitive and the two sides of this
     * interface disagree in practice: the fixtures spell {@code Location} and
     * {@code Content-Range} the way a person would, while HTTP/2 lower-cases
     * every name on the wire. A downloader that compared exactly would verify
     * nothing against a real server and everything against its own tests.
     */
    static String lookup(Map<String, String> headers, String name) {
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    /** The first value of each header, in the shape {@link Content} wants. */
    static Map<String, String> firstValues(Map<String, List<String>> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            if (field.getKey() != null && field.getValue() != null && !field.getValue().isEmpty()) {
                out.put(field.getKey(), field.getValue().get(0));
            }
        }
        return out;
    }
}
