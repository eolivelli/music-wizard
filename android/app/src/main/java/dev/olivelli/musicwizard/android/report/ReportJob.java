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
import java.io.IOException;
import java.time.Instant;

/**
 * One send, off the main thread: compress, upload, comment, tidy up.
 *
 * <p>Separate from the screen, and taking its encoder as a seam, so that all of
 * it but the platform encoder itself runs on a JVM. What has to be tested here
 * is not the happy path — the emulator shows that — but the failure paths, and
 * they are the ones a screen cannot easily be made to take.
 */
public final class ReportJob {

    /**
     * Compresses a take.
     *
     * <p>A seam because {@code MediaCodec} exists only on a device;
     * {@code FlacEncoder} is the implementation the app runs.
     */
    public interface Encoder {
        void encode(File wav, File out) throws IOException;
    }

    /**
     * How one send ended.
     *
     * @param sent           where it landed, or null if it did not
     * @param failure        why it did not, or null if it did
     * @param encoderFailure why the WAV went up uncompressed, or null when the
     *                       FLAC did. Orthogonal to the other two, because a
     *                       take that could not be compressed still sends, and
     *                       never null when the encoder failed — see
     *                       {@link #UNDESCRIBABLE}
     */
    public record Outcome(GitHubReporter.Sent sent, String failure, String encoderFailure) {
    }

    private ReportJob() {
    }

    /**
     * Runs the send, answering with an {@link Outcome} for every failure it can
     * put into words.
     *
     * <p>Not an absolute, and the caller must still guard: a send that ends
     * without answering leaves the take marked in flight and Send disabled for
     * the life of the process, which is the failure {@code AnalysisJobs}
     * carries a paragraph about. The scratch file is gone either way.
     *
     * @param scratch where the compressed copy is written; deleted before this
     *                returns, whatever happened
     */
    public static Outcome run(Encoder encoder, GitHubReporter reporter, String releaseTag,
                              int inboxIssue, File wav, File scratch, String takeName,
                              TakeReport report, Instant sentAt) {
        File payload = wav;
        String contentType = "audio/wav";
        String extension = ".wav";
        String encoderFailure = null;
        try {
            try {
                encoder.encode(wav, scratch);
                payload = scratch;
                contentType = "audio/flac";
                extension = ".flac";
            } catch (Throwable t) {
                // Throwable: the platform encoder reports a refused format as an
                // unchecked exception, and running out of heap compressing a long
                // take is an Error. Neither should cost the user the take — the
                // WAV is lossless too, only bigger. Why it happened travels with
                // the outcome, because "no FLAC encoder" is one of several
                // reasons and the screen would otherwise claim it every time.
                encoderFailure = safeDescribe(t);
            }
            GitHubReporter.Sent sent = reporter.send(releaseTag, inboxIssue,
                    GitHubReporter.assetName(takeName, sentAt, extension),
                    Http.Body.file(payload, contentType), report);
            return new Outcome(sent, null, encoderFailure);
        } catch (Throwable t) {
            return new Outcome(null, safeDescribe(t), encoderFailure);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            scratch.delete();
        }
    }

    /** Stands in when a throwable cannot even be turned into a sentence. */
    static final String UNDESCRIBABLE = "the reason could not be read";

    /**
     * {@link #describe}, or {@link #UNDESCRIBABLE} when even that failed.
     *
     * <p>A string rather than null, and a constant rather than a built one:
     * both readers of these fields treat null as "there was no such failure",
     * so returning null here would say the encoder had not failed when it had,
     * and the screen would report a WAV upload as though it were a FLAC. A
     * constant also allocates nothing, which is the case this exists for.
     */
    private static String safeDescribe(Throwable failure) {
        try {
            return describe(failure);
        } catch (Throwable t) {
            return UNDESCRIBABLE;
        }
    }

    /**
     * A sentence for the screen.
     *
     * <p>An {@link Error} is named by its class: "Java heap space" on its own
     * does not tell anyone their take was too long for this phone.
     */
    static String describe(Throwable failure) {
        String message = failure.getMessage();
        boolean hasMessage = message != null && !message.isEmpty();
        if (failure instanceof Error) {
            return hasMessage
                    ? failure.getClass().getSimpleName() + ": " + message
                    : failure.getClass().getSimpleName();
        }
        return hasMessage ? message : failure.getClass().getSimpleName();
    }
}
