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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The video id inside whatever the share sheet handed over.
 *
 * <p>What arrives is not reliably a URL. Different YouTube versions have put the
 * link in {@code EXTRA_TEXT} alone, put the title on a line above it, and put
 * the title in {@code EXTRA_SUBJECT} instead — so this scans for the first
 * YouTube link <em>anywhere</em> in the text rather than parsing the whole
 * string as one.
 *
 * <p>There is deliberately no list of playlist parameters to strip, and someone
 * looking for one should know why: what is extracted here is the eleven-character
 * id and nothing else, and {@link InnerTube} sends that id rather than a URL. So
 * {@code list}, {@code start_radio}, {@code index}, {@code si} and {@code
 * feature} are discarded by construction, which is stronger than stripping a
 * list that has to be kept up to date. {@code t} goes the same way on purpose: a
 * seek offset inherited from a share would silently truncate a take.
 */
public final class VideoLink {

    /** Ids are eleven characters of an unpadded base64url alphabet. */
    private static final String ID = "[A-Za-z0-9_-]{11}";

    /**
     * A link, with or without a scheme, on any of the hosts YouTube shares from.
     *
     * <p>The trailing class excludes whitespace and the quotes and brackets a
     * link picks up when it is pasted into prose.
     */
    private static final Pattern LINK = Pattern.compile(
            "(?i)(?:https?://)?(?:[a-z0-9-]+\\.)*"
                    + "(youtube\\.com|youtube-nocookie\\.com|youtu\\.be)"
                    + "(/[^\\s<>\"'\\]\\[)(]*)?");

    /** {@code /watch?v=ID}, wherever in the query the parameter sits. */
    private static final Pattern WATCH_PARAM =
            Pattern.compile("(?i)[?&]v=(" + ID + ")(?![A-Za-z0-9_-])");

    /** The path forms that carry the id directly. */
    private static final Pattern PATH_ID = Pattern.compile(
            "(?i)^/(?:shorts|live|embed|v|e)/(" + ID + ")(?![A-Za-z0-9_-])");

    /** {@code youtu.be/ID}, where the whole path is the id. */
    private static final Pattern SHORT_ID =
            Pattern.compile("^/(" + ID + ")(?![A-Za-z0-9_-])");

    /** Why no video was found, when none was. */
    public enum Problem {

        /** The text held no YouTube link at all. */
        NO_LINK,

        /** A playlist, with no one video named. */
        PLAYLIST,

        /** A channel, a user or a handle. */
        CHANNEL,

        /** A YouTube link whose shape carries no id this understands. */
        NO_VIDEO_ID
    }

    private VideoLink() {
    }

    /**
     * The id of the first video linked in {@code shareText}, or null.
     *
     * <p>Null is not an error to report on its own — {@link #problem} says what
     * to tell the user, and "that is a playlist" and "that is not a YouTube
     * link" are different sentences.
     */
    public static String videoId(String shareText) {
        if (shareText == null || shareText.isEmpty()) {
            return null;
        }
        Matcher links = LINK.matcher(shareText);
        while (links.find()) {
            String id = idIn(links.group(1), path(links.group(2)));
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /**
     * Why {@link #videoId} found nothing.
     *
     * <p>Reports on the first YouTube link in the text, since that is the one the
     * user meant. Undefined when a video <em>was</em> found.
     */
    public static Problem problem(String shareText) {
        if (shareText == null || shareText.isEmpty()) {
            return Problem.NO_LINK;
        }
        Matcher links = LINK.matcher(shareText);
        while (links.find()) {
            String path = path(links.group(2));
            if (idIn(links.group(1), path) != null) {
                return Problem.NO_VIDEO_ID;
            }
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("/playlist")) {
                return Problem.PLAYLIST;
            }
            if (lower.startsWith("/@") || lower.startsWith("/channel/")
                    || lower.startsWith("/c/") || lower.startsWith("/user/")) {
                return Problem.CHANNEL;
            }
            return Problem.NO_VIDEO_ID;
        }
        return Problem.NO_LINK;
    }

    /** The canonical watch URL for an id, which is what the user is shown and what is recorded. */
    public static String watchUrl(String videoId) {
        if (videoId == null || !videoId.matches(ID)) {
            throw new IllegalArgumentException("not a video id: " + videoId);
        }
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    private static String path(String matched) {
        return matched == null ? "" : matched;
    }

    private static String idIn(String host, String path) {
        if (host.toLowerCase(java.util.Locale.ROOT).endsWith("youtu.be")) {
            Matcher shortForm = SHORT_ID.matcher(path);
            if (shortForm.find()) {
                return shortForm.group(1);
            }
            // A youtu.be link can still carry ?v=, and nothing is lost by looking.
            return firstGroup(WATCH_PARAM.matcher(path));
        }
        Matcher inPath = PATH_ID.matcher(path);
        if (inPath.find()) {
            return inPath.group(1);
        }
        return firstGroup(WATCH_PARAM.matcher(path));
    }

    private static String firstGroup(Matcher matcher) {
        return matcher.find() ? matcher.group(1) : null;
    }
}
