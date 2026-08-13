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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A transport that answers from a script and records what it was asked.
 *
 * <p>Every test in this package drives one of these. Nothing here opens a
 * socket, which is the point: the request assembly is the part that is easy to
 * get wrong and invisible from the outside, since a wrong header comes back as
 * {@code LOGIN_REQUIRED} — the same answer the bot check gives.
 */
final class FakeHttp implements Http {

    /** Every request made, in order. */
    final List<Request> requests = new ArrayList<>();

    /** Every streamed reply handed out, so a test can check they were closed. */
    private final List<Canned> handedOut = new ArrayList<>();

    private final Deque<Object> replies = new ArrayDeque<>();

    /** Queues a reply for {@link #send}. */
    FakeHttp reply(int status, String body) {
        replies.add(new Http.Response(status, body));
        return this;
    }

    /** Queues a reply for {@link #open}. */
    FakeHttp content(int status, Map<String, String> headers, byte[] body) {
        replies.add(new Canned(status, headers, body, Integer.MAX_VALUE));
        return this;
    }

    /**
     * Queues a reply whose stream dies partway.
     *
     * <p>A connection dropped mid-chunk is the case that would corrupt a file if
     * chunks were appended as they arrived rather than assembled first.
     */
    FakeHttp contentFailingAfter(int status, byte[] body, int bytesBeforeFailure) {
        replies.add(new Canned(status, Map.of(), body, bytesBeforeFailure));
        return this;
    }

    /** Queues a failure, thrown rather than returned. */
    FakeHttp fail(IOException failure) {
        replies.add(failure);
        return this;
    }

    /** The body of the request at {@code index}, as text. */
    String bodyOf(int index) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        requests.get(index).body().writeTo(out);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    @Override
    public Response send(Request request) throws IOException {
        Object next = take(request);
        if (next instanceof Response) {
            return (Response) next;
        }
        throw new AssertionError("send() reached a reply queued for open()");
    }

    @Override
    public Content open(Request request) throws IOException {
        Object next = take(request);
        if (next instanceof Canned) {
            handedOut.add((Canned) next);
            return (Canned) next;
        }
        throw new AssertionError("open() reached a reply queued for send()");
    }

    /** How many streamed replies were handed out but never closed. */
    int unclosedContents() {
        int leaked = 0;
        for (Canned content : handedOut) {
            if (!content.closed) {
                leaked++;
            }
        }
        return leaked;
    }

    private Object take(Request request) throws IOException {
        requests.add(request);
        if (replies.isEmpty()) {
            throw new AssertionError("no reply queued for " + request.url());
        }
        Object next = replies.poll();
        if (next instanceof IOException) {
            throw (IOException) next;
        }
        return next;
    }

    /** Reads a fixture from {@code src/test/resources/yt}. */
    static String fixture(String name) throws IOException {
        try (InputStream in = FakeHttp.class.getResourceAsStream("/yt/" + name)) {
            if (in == null) {
                throw new IOException("no fixture named " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 13];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** A reply whose body is already in memory. */
    private static final class Canned implements Content {

        private final int status;
        private final Map<String, String> headers;
        private final InputStream stream;
        boolean closed;

        Canned(int status, Map<String, String> headers, byte[] body, int bytesBeforeFailure) {
            this.status = status;
            this.headers = headers;
            this.stream = bytesBeforeFailure == Integer.MAX_VALUE
                    ? new ByteArrayInputStream(body)
                    : new DyingStream(body, bytesBeforeFailure);
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
            closed = true;
        }
    }

    /** Delivers some bytes and then the connection failure a real one would. */
    private static final class DyingStream extends InputStream {

        private final byte[] body;
        private final int limit;
        private int position;

        DyingStream(byte[] body, int limit) {
            this.body = body;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (position >= limit) {
                throw new IOException("the connection dropped");
            }
            int count = Math.min(length, Math.min(limit, body.length) - position);
            if (count <= 0) {
                throw new IOException("the connection dropped");
            }
            System.arraycopy(body, position, into, offset, count);
            position += count;
            return count;
        }
    }
}
