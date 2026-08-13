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

import java.util.Collections;
import java.util.List;

/**
 * What the player call said about one video.
 *
 * <p>A plain class rather than a record, for the reason {@link AudioStream}
 * gives.
 */
public final class PlayerInfo {

    private final String videoId;
    private final String title;
    private final String author;
    private final long lengthSeconds;
    private final List<AudioStream> audio;

    public PlayerInfo(String videoId, String title, String author, long lengthSeconds,
            List<AudioStream> audio) {
        this.videoId = videoId;
        this.title = title;
        this.author = author;
        this.lengthSeconds = lengthSeconds;
        this.audio = audio == null ? List.of() : Collections.unmodifiableList(audio);
    }

    public String videoId() {
        return videoId;
    }

    /**
     * The video's own title, which is authoritative.
     *
     * <p>The share text carries a title too, sometimes, in either of two extras
     * depending on the YouTube version. This one is what the take is named after
     * once the call has been made.
     */
    public String title() {
        return title;
    }

    public String author() {
        return author;
    }

    public long lengthSeconds() {
        return lengthSeconds;
    }

    /** Every audio-only format offered, fetchable or not. */
    public List<AudioStream> audio() {
        return audio;
    }
}
