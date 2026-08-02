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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The transport against a real socket.
 *
 * <p>{@link GitHubReporterTest} asserts what the reporter asks for; this asserts
 * that what it asks for is what leaves the machine, which is not the same check
 * — a header can be set on a connection and never sent.
 *
 * <p>What {@code setFixedLengthStreamingMode} buys is <em>only</em> that the
 * body is not buffered first. {@code HttpURLConnection} sends a
 * {@code Content-Length} either way and chunks only if asked to, so no
 * assertion about the headers can see the call at all;
 * {@link #aBodyReachesTheSocketWhileItIsStillBeingWritten} is the one that can,
 * and it is there because a take is the size of a take.
 *
 * <p>The server is a few dozen lines on a {@link ServerSocket} rather than a
 * library or the JDK's own: Android's unit-test compile classpath does not
 * carry {@code com.sun.net.httpserver}, and a mock web server would be a
 * dependency added to check that the app has none.
 */
public class UrlConnectionHttpTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ServerSocket socket;
    private Thread server;
    private String base;

    /** What the last request looked like from the other end. */
    private volatile String seenRequestLine;
    private volatile Map<String, String> seenHeaders;
    private volatile byte[] seenBody;

    /** Counted down as soon as one byte of a request body has been read. */
    private final CountDownLatch firstBodyByte = new CountDownLatch(1);

    private volatile String replyStatus = "200 OK";
    private volatile byte[] replyBody = "{}".getBytes(StandardCharsets.UTF_8);

    @Before
    public void startServer() throws IOException {
        socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        base = "http://127.0.0.1:" + socket.getLocalPort();
        server = new Thread(this::serve, "test-http");
        server.setDaemon(true);
        server.start();
    }

    @After
    public void stopServer() throws IOException, InterruptedException {
        socket.close();
        server.join(5_000);
    }

    /** One connection at a time, for as long as the socket is open. */
    private void serve() {
        while (!socket.isClosed()) {
            try (Socket connection = socket.accept()) {
                InputStream in = connection.getInputStream();
                Map<String, String> headers = new LinkedHashMap<>();
                seenRequestLine = readLine(in);
                for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                    int colon = line.indexOf(':');
                    // A header with no colon is not something HttpURLConnection
                    // sends; skipping it beats an exception that would kill this
                    // thread and leave the test hanging on connect.
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT),
                                line.substring(colon + 1).trim());
                    }
                }
                String length = headers.get("content-length");
                byte[] body = new byte[length == null ? 0 : Integer.parseInt(length)];
                for (int read = 0; read < body.length; ) {
                    int step = in.read(body, read, body.length - read);
                    if (step < 0) {
                        break;
                    }
                    read += step;
                    firstBodyByte.countDown();
                }
                seenHeaders = headers;
                seenBody = body;

                OutputStream out = connection.getOutputStream();
                out.write(("HTTP/1.1 " + replyStatus + "\r\nContent-Length: " + replyBody.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(replyBody);
                out.flush();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** One CRLF-terminated line, byte at a time so the body after it is untouched. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c >= 0 && c != '\n'; c = in.read()) {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    /** Method, headers and an empty body for the release lookup. */
    @Test
    public void aGetCarriesItsHeadersAndNoBody() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer tok");
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "music-wizard-android");

        Http.Response response = new UrlConnectionHttp()
                .send(new Http.Request("GET", base + "/releases/tags/field-takes", headers, null));

        assertEquals(200, response.status());
        assertTrue(response.isSuccess());
        assertEquals("{}", response.body());
        assertEquals("GET /releases/tags/field-takes HTTP/1.1", seenRequestLine);
        assertEquals("Bearer tok", seenHeaders.get("authorization"));
        assertEquals("application/vnd.github+json", seenHeaders.get("accept"));
        assertEquals("2022-11-28", seenHeaders.get("x-github-api-version"));
        assertEquals("music-wizard-android", seenHeaders.get("user-agent"));
        assertEquals(0, seenBody.length);
    }

    /**
     * A take goes up whole, with a length and no chunking.
     *
     * <p>The bytes are the point: this is the one request whose body is the
     * artefact rather than a description of it.
     */
    @Test
    public void anUploadStreamsTheFileWithItsLengthAndContentType() throws IOException {
        byte[] audio = new byte[300_000];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i * 31);
        }
        File file = folder.newFile("take.flac");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(audio);
        }

        Http.Response response = new UrlConnectionHttp().send(new Http.Request(
                "POST", base + "/assets?name=take.flac", new LinkedHashMap<>(),
                Http.Body.file(file, "audio/flac")));

        assertEquals(200, response.status());
        assertEquals("POST /assets?name=take.flac HTTP/1.1", seenRequestLine);
        assertEquals("audio/flac", seenHeaders.get("content-type"));
        assertEquals(String.valueOf(audio.length), seenHeaders.get("content-length"));
        // True either way — this pins the shape GitHub is sent, not the
        // streaming; the test below is the one that can see that.
        assertNull(seenHeaders.get("transfer-encoding"));
        assertArrayEquals(audio, seenBody);
    }

    /**
     * The body goes to the socket as it is written, not into memory first.
     *
     * <p>The only observable difference {@code setFixedLengthStreamingMode}
     * makes, and the reason it is there: without it a take is held a second
     * time in a heap already sized for the analysis. The headers cannot show
     * it — {@code Content-Length} is sent either way — so this holds the write
     * open and asks whether the far end has the first byte yet.
     */
    @Test
    public void aBodyReachesTheSocketWhileItIsStillBeingWritten() throws IOException {
        AtomicBoolean arrivedEarly = new AtomicBoolean();
        Http.Body halting = new Http.Body() {
            @Override
            public String contentType() {
                return "audio/flac";
            }

            @Override
            public long length() {
                return 2;
            }

            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(1);
                out.flush();
                try {
                    // Bounded, so a buffered body fails the assertion rather
                    // than hanging the suite.
                    arrivedEarly.set(firstBodyByte.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                out.write(2);
            }
        };

        new UrlConnectionHttp().send(
                new Http.Request("POST", base + "/assets", new LinkedHashMap<>(), halting));

        assertTrue("the body was buffered whole before anything was sent",
                arrivedEarly.get());
        assertArrayEquals(new byte[] {1, 2}, seenBody);
    }

    /** The comment's JSON arrives as UTF-8, non-ASCII included. */
    @Test
    public void aJsonBodyArrivesAsUtf8() throws IOException {
        String json = "{\"body\":\"café 🎸\"}";
        new UrlConnectionHttp().send(new Http.Request(
                "POST", base + "/comments", new LinkedHashMap<>(), Http.Body.json(json)));

        assertEquals(json, new String(seenBody, StandardCharsets.UTF_8));
        assertEquals(String.valueOf(json.getBytes(StandardCharsets.UTF_8).length),
                seenHeaders.get("content-length"));
        assertTrue(seenHeaders.get("content-type"),
                seenHeaders.get("content-type").startsWith("application/json"));
    }

    /**
     * An error body is read, not thrown away.
     *
     * <p>{@code getInputStream()} throws on 4xx and 5xx, and GitHub puts the
     * reason a request was refused in exactly that body. Reading the wrong
     * stream costs the screen the only sentence that names the thing to fix.
     */
    @Test
    public void anErrorStatusStillYieldsGitHubsBody() throws IOException {
        replyStatus = "401 Unauthorized";
        replyBody = "{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8);

        Http.Response response = new UrlConnectionHttp()
                .send(new Http.Request("GET", base + "/releases", new LinkedHashMap<>(), null));

        assertEquals(401, response.status());
        assertFalse(response.isSuccess());
        assertEquals("{\"message\":\"Bad credentials\"}", response.body());
    }

    /** A reply with no body at all is a response, not a crash. */
    @Test
    public void anEmptyReplyIsAnEmptyBody() throws IOException {
        replyStatus = "204 No Content";
        replyBody = new byte[0];

        Http.Response response = new UrlConnectionHttp()
                .send(new Http.Request("GET", base + "/x", new LinkedHashMap<>(), null));

        assertEquals(204, response.status());
        assertTrue(response.isSuccess());
        assertEquals("", response.body());
    }
}
