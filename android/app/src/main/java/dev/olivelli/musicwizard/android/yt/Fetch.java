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
import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Shared text in, one audio file out.
 *
 * <p>The whole of what the app calls. Everything below this is testable without
 * a network and everything above it is Android, so this is the seam the two
 * halves meet at.
 */
public final class Fetch {

    /**
     * The longest video this will fetch.
     *
     * <p>Not a network limit but a memory one, downstream: the analysis holds the
     * whole take as a {@code float[]} at the file's own rate and an STFT
     * magnitude matrix over it at the same time. At this length that is already
     * hundreds of megabytes for the first alone. A two-hour set would fail
     * later, inside the analysis, where the cause is much harder to read than
     * it is here.
     */
    public static final long MAX_SECONDS = 20 * 60;

    private final InnerTube tube;
    private final StreamDownload download;
    private final Trace trace;

    public Fetch(Http http) {
        this(http, Trace.NONE);
    }

    public Fetch(Http http, Trace trace) {
        this(new InnerTube(http, trace), new StreamDownload(http, trace), trace);
    }

    Fetch(InnerTube tube, StreamDownload download) {
        this(tube, download, Trace.NONE);
    }

    Fetch(InnerTube tube, StreamDownload download, Trace trace) {
        this.tube = tube;
        this.download = download;
        this.trace = trace;
    }

    /** What was fetched, and what it is. */
    public static final class Fetched {

        private final File file;
        private final String videoId;
        private final String title;
        private final String author;
        private final long lengthSeconds;

        /** Public so the Android side can build one for a test without a network. */
        public Fetched(File file, String videoId, String title, String author,
                long lengthSeconds) {
            this.file = file;
            this.videoId = videoId;
            this.title = title;
            this.author = author;
            this.lengthSeconds = lengthSeconds;
        }

        /** The downloaded container, a whole MP4 or WebM a decoder can open. */
        public File file() {
            return file;
        }

        public String videoId() {
            return videoId;
        }

        /** Never blank: the video id stands in when YouTube gave no title. */
        public String title() {
            return title;
        }

        public String author() {
            return author;
        }

        public long lengthSeconds() {
            return lengthSeconds;
        }

        /** The canonical link, which is what gets recorded as the take's provenance. */
        public String url() {
            return VideoLink.watchUrl(videoId);
        }
    }

    /**
     * Fetches the audio of the first video linked in {@code shareText}.
     *
     * <p>Writes into {@code directory} and nowhere else, and leaves nothing
     * behind on any path but success. Cancellation surfaces as an
     * {@code InterruptedIOException}, which is an {@code IOException} the caller
     * reports as cancelled rather than failed.
     */
    public Fetched run(String shareText, File directory, StreamDownload.Progress progress,
            BooleanSupplier cancelled) throws ExtractionException, IOException {
        String videoId = VideoLink.videoId(shareText);
        if (videoId == null) {
            throw new ExtractionException(ExtractionException.Reason.NO_VIDEO,
                    describe(VideoLink.problem(shareText)));
        }

        PlayerInfo info = tube.resolve(videoId);
        if (info.lengthSeconds() > MAX_SECONDS) {
            throw new ExtractionException(ExtractionException.Reason.TOO_LONG,
                    "That video is longer than " + MAX_SECONDS / 60
                            + " minutes, which is as much as the app will fetch.");
        }

        AudioStream stream = AudioStream.choose(info.audio());
        trace.line("chose " + stream);
        File part = new File(directory, videoId + ".part");
        try {
            download.to(part, stream, progress, cancelled);
        } catch (StreamDownload.ExpiredException expired) {
            trace.line("the link was refused; resolving again");
            // URLs last about six hours, so this is a confirmation screen that was
            // left open. Resolving again is the cure, and it is worth exactly one
            // try: fresh URLs that are refused too are not stale ones.
            // No null check on the re-chosen stream: InnerTube.resolve throws
            // SABR_ONLY when nothing is fetchable, so it cannot return a
            // PlayerInfo whose formats have no URL. One place owns that.
            info = tube.resolve(videoId);
            stream = AudioStream.choose(info.audio());
            trace.line("chose " + stream + " on the second resolve");
            try {
                download.to(part, stream, progress, cancelled);
            } catch (StreamDownload.ExpiredException again) {
                // Freshly resolved URLs, refused anyway, after the download had
                // already retried each chunk. That is the media host rate-limiting
                // this address, not a link that went stale, and it clears by
                // itself — so say so rather than blaming the link.
                throw new ExtractionException(ExtractionException.Reason.RATE_LIMITED,
                        "YouTube is refusing downloads just now."
                                + " Wait a minute and try again.", again);
            }
        }

        // Naming the finished file can fail too, and the megabytes are already
        // on disk by then: without this the .part outlives every such failure,
        // one orphan per attempt, in a cache directory nobody looks at.
        File media = new File(directory, videoId + extensionFor(stream.mimeType()));
        boolean named = false;
        try {
            if (media.exists() && !media.delete()) {
                throw new IOException("could not replace " + media.getName());
            }
            if (!part.renameTo(media)) {
                throw new IOException("could not name the downloaded audio "
                        + media.getName());
            }
            named = true;
        } finally {
            if (!named && part.exists() && !part.delete()) {
                part.deleteOnExit();
            }
        }

        trace.line("fetched " + media.length() + " bytes as " + media.getName());
        String title = info.title() == null || info.title().isBlank() ? videoId : info.title();
        return new Fetched(media, videoId, title, info.author(), info.lengthSeconds());
    }

    /** What to tell the user when the share held no video. */
    private static String describe(VideoLink.Problem problem) {
        switch (problem) {
            case PLAYLIST:
                return "That is a playlist. Share one video.";
            case CHANNEL:
                return "That is a channel. Share one video.";
            case NO_VIDEO_ID:
                return "That YouTube link does not name a video.";
            case NO_LINK:
            default:
                return "That does not hold a YouTube link.";
        }
    }

    /**
     * The extension a decoder will recognise the container by.
     *
     * <p>{@code MediaExtractor} sniffs content rather than names, but a file
     * called {@code .part} is also what a half-finished download is called, and
     * the two must not be confusable in a cache directory that is swept.
     */
    private static String extensionFor(String mimeType) {
        return mimeType.startsWith("audio/webm") ? ".webm" : ".m4a";
    }
}
