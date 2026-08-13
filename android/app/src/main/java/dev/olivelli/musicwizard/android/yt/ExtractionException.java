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

/**
 * A fetch that will not work, and why.
 *
 * <p>The {@link Reason} matters more than the message: "YouTube would not serve
 * this" and "this build can no longer read what YouTube serves" are the same
 * sentence to a user and completely different facts to whoever has to fix it,
 * and only one of them is worth reinstalling the app for. Tests assert the
 * reason; the screen shows the message.
 */
public class ExtractionException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What went wrong, in the terms the caller can act on. */
    public enum Reason {

        /** The shared text held no YouTube video link. */
        NO_VIDEO,

        /**
         * The bot check refused the session even after a bootstrapped visitor id.
         *
         * <p>Usually transient and worth retrying, sometimes on another network:
         * a bare call is refused most of the time and the retry normally passes,
         * so both failing is unusual rather than expected.
         */
        BOT_CHECK,

        /**
         * YouTube answered, but no audio format carried a URL.
         *
         * <p>This is the one that means the app is out of date rather than
         * unlucky. It is what SABR-only enforcement looks like when it reaches a
         * client, and no retry will help — the client constants in
         * {@link InnerTube} have to change. Note that a {@code
         * serverAbrStreamingUrl} beside perfectly good URLs is normal and is not
         * this; the signal is every {@code url} being absent.
         */
        SABR_ONLY,

        /**
         * A live stream, or the recording of one.
         *
         * <p>Refused before anything is downloaded: there is no fixed length to
         * fetch, and a chart of an endless broadcast means nothing.
         */
        LIVE,

        /**
         * YouTube declined to serve this video to this client, permanently.
         *
         * <p>Carries YouTube's own sentence, because the causes are several and
         * not distinguishable from the outside — a video marked as made for
         * children answers "This video is not available" here, and so do some
         * region-restricted ones. Retrying does not help.
         */
        REFUSED,

        /** No such video, or it has been removed. */
        UNAVAILABLE,

        /**
         * The media host is refusing downloads for the moment.
         *
         * <p>Distinct from {@link #BOT_CHECK}, which is the player endpoint
         * refusing to say anything at all; this is the media host answering 403
         * to a fetch it was happily serving a moment earlier. It survives both
         * the per-chunk retries and resolving the video again, and it clears on
         * its own — measured while fetching the same track repeatedly from one
         * address, which is what provokes it. Worth telling the user to wait,
         * and worth not calling a dead link.
         */
        RATE_LIMITED,

        /** Longer than the app will fetch and analyse. */
        TOO_LONG,

        /** Playable, but with no audio track worth taking. */
        NO_AUDIO,

        /**
         * The reply did not parse.
         *
         * <p>Separate from the rest because it is the shape a Jackson-on-Android
         * problem takes, which this app has met before — see {@code
         * MwAnalysis.writeCache} for the record-desugaring one.
         */
        MALFORMED
    }

    private final Reason reason;

    public ExtractionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ExtractionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
