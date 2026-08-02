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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The failure paths of one send.
 *
 * <p>The happy path is what the emulator run shows; these are the ones a screen
 * cannot easily be made to take, and each of them is a way for the send to end
 * without the screen ever being told — which leaves Send disabled under
 * "uploading…" for the life of the process.
 */
public class ReportJobTest {

    private static final Instant SENT_AT = Instant.parse("2026-08-02T15:11:22Z");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File wav;
    private File scratch;

    @Before
    public void setUp() throws IOException {
        wav = folder.newFile("take.wav");
        try (OutputStream out = new FileOutputStream(wav)) {
            out.write(new byte[] {1, 2, 3, 4});
        }
        scratch = new File(folder.getRoot(), "report-upload.flac");
    }

    /** Records the uploads it is asked for and answers from a script. */
    private static final class FakeHttp implements Http {

        final List<Request> sent = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final Deque<Response> replies = new ArrayDeque<>();

        FakeHttp(Response... scripted) {
            for (Response reply : scripted) {
                replies.add(reply);
            }
        }

        @Override
        public Response send(Request request) throws IOException {
            sent.add(request);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            if (request.body() != null) {
                request.body().writeTo(captured);
            }
            bodies.add(captured.toByteArray());
            return replies.removeFirst();
        }
    }

    private static FakeHttp workingHub() {
        return new FakeHttp(
                new Http.Response(200, "{\"upload_url\":\"https://uploads/assets\"}"),
                new Http.Response(201, "{\"browser_download_url\":\"https://asset\"}"),
                new Http.Response(201, "{\"html_url\":\"https://comment\"}"));
    }

    private static TakeReport report() {
        return new TakeReport("take", 4, "G C D", null, "0.1", "Android 15");
    }

    private ReportJob.Outcome run(ReportJob.Encoder encoder, Http http) {
        return ReportJob.run(encoder, new GitHubReporter(http, "o", "r", "tok"),
                "field-takes", 7, wav, scratch, "take", report(), SENT_AT);
    }

    /** The compressed copy goes up, and the scratch file does not survive the send. */
    @Test
    public void theCompressedCopyIsWhatIsUploadedAndIsThenRemoved() throws IOException {
        FakeHttp http = workingHub();
        byte[] compressed = {'f', 'L', 'a', 'C', 9, 9};

        ReportJob.Outcome outcome = run((in, out) -> {
            try (OutputStream stream = new FileOutputStream(out)) {
                stream.write(compressed);
            }
        }, http);

        assertNull(outcome.failure());
        assertNull(outcome.encoderFailure());
        assertEquals("https://asset", outcome.sent().assetUrl());
        assertEquals("https://comment", outcome.sent().commentUrl());
        assertEquals("https://uploads/assets?name=take-20260802T151122Z.flac",
                http.sent.get(1).url());
        assertEquals("audio/flac", http.sent.get(1).body().contentType());
        assertEquals(compressed.length, http.bodies.get(1).length);
        assertFalse("the scratch copy is left in the cache directory", scratch.exists());
    }

    /**
     * An encoder that fails sends the WAV, and says which failure it was.
     *
     * <p>A device with no FLAC encoder, a full cache and a take whose header is
     * all there is of it all arrive here, and the screen used to call every one
     * of them "this phone has no FLAC encoder".
     */
    @Test
    public void anEncoderFailureFallsBackToTheWavAndKeepsTheReason() throws IOException {
        FakeHttp http = workingHub();

        ReportJob.Outcome outcome = run((in, out) -> {
            throw new IOException("the FLAC encoder produced no audio");
        }, http);

        assertNotNull(outcome.sent());
        assertEquals("the FLAC encoder produced no audio", outcome.encoderFailure());
        assertEquals("https://uploads/assets?name=take-20260802T151122Z.wav",
                http.sent.get(1).url());
        assertEquals("audio/wav", http.sent.get(1).body().contentType());
        assertArrayEqualsBytes(new byte[] {1, 2, 3, 4}, http.bodies.get(1));
    }

    /**
     * An {@link Error} out of the encoder is a fallback, not a lost send.
     *
     * <p>Running out of heap compressing a long take is an Error, and an
     * Exception-only catch would let it out of the job: no outcome, no
     * callback, and a screen that never learns what happened.
     */
    @Test
    public void anErrorWhileEncodingStillSendsTheWav() throws IOException {
        FakeHttp http = workingHub();

        ReportJob.Outcome outcome = run((in, out) -> {
            throw new OutOfMemoryError("Java heap space");
        }, http);

        assertNotNull(outcome.sent());
        // Named by its class: "Java heap space" alone does not tell anyone
        // their take was too long for this phone.
        assertEquals("OutOfMemoryError: Java heap space", outcome.encoderFailure());
        assertEquals("audio/wav", http.sent.get(1).body().contentType());
    }

    /**
     * An {@link Error} out of the upload is an outcome, not an escape.
     *
     * <p>This is the one {@code AnalysisJobs} carries a paragraph about: an
     * Exception-only catch leaves the job neither finished nor failed, so the
     * screen stays disabled and every retry short-circuits.
     */
    @Test
    public void anErrorWhileUploadingIsReportedRatherThanThrown() throws IOException {
        ReportJob.Outcome outcome = ReportJob.run((in, out) -> {
            try (OutputStream stream = new FileOutputStream(out)) {
                stream.write(new byte[] {1});
            }
        }, new GitHubReporter(new Http() {
            @Override
            public Response send(Request request) {
                throw new StackOverflowError();
            }
        }, "o", "r", "tok"), "field-takes", 7, wav, scratch, "take", report(), SENT_AT);

        assertNull(outcome.sent());
        assertEquals("StackOverflowError", outcome.failure());
        assertFalse("the scratch copy is left behind after a failure", scratch.exists());
    }

    /** A refused request comes back as its message, with the audio already gone. */
    @Test
    public void aRefusedRequestIsAnOutcomeWithTheReasonInIt() throws IOException {
        FakeHttp http = new FakeHttp(new Http.Response(401, "{\"message\":\"Bad credentials\"}"));

        ReportJob.Outcome outcome = run((in, out) -> {
            try (OutputStream stream = new FileOutputStream(out)) {
                stream.write(new byte[] {1});
            }
        }, http);

        assertNull(outcome.sent());
        assertTrue(outcome.failure(), outcome.failure().contains("Bad credentials"));
        assertNull("the FLAC was written, so nothing failed to encode",
                outcome.encoderFailure());
        assertFalse(scratch.exists());
    }

    /**
     * A failure that cannot be described is still an outcome.
     *
     * <p>{@code describe} builds a string, which is exactly what a heap at the
     * wall cannot do, so the throwable it is handed can throw again on the way
     * out. The screen turns a null reason into "the send stopped without saying
     * why"; what must not happen is the job escaping and answering nobody.
     */
    @Test
    public void anIndescribableFailureIsStillAnOutcome() {
        ReportJob.Outcome outcome = run((in, out) -> {
            throw new Error() {
                @Override
                public String getMessage() {
                    throw new IllegalStateException("not even this");
                }
            };
        }, workingHub());

        // The encoder failed indescribably; the WAV still went, so the send
        // itself worked.
        assertNotNull(outcome.sent());
        assertNull(outcome.encoderFailure());
        assertFalse(scratch.exists());
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("byte " + i, expected[i], actual[i]);
        }
    }
}
