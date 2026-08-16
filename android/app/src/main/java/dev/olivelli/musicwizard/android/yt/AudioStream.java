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

import java.util.List;

/**
 * One audio-only adaptive format, and the choice between several.
 *
 * <p>A plain class rather than a record on purpose: D8 rewrites records to
 * extend {@code RecordTag}, which is what stops Jackson serializing them on
 * device — see {@code MwAnalysis.writeCache}. Nothing here is databound, but a
 * record would invite someone to try.
 */
public final class AudioStream {

    /** AAC-LC in MP4, the format the chooser wants. See {@link #choose}. */
    public static final int ITAG_M4A_128 = 140;

    /** Opus in WebM at a comparable bitrate, the fallback. */
    public static final int ITAG_OPUS_160 = 251;

    private final int itag;
    private final String mimeType;
    private final String url;
    private final int bitrate;
    private final long contentLength;
    private final int sampleRate;
    private final int channels;
    private final long durationMillis;

    public AudioStream(int itag, String mimeType, String url, int bitrate, long contentLength,
            int sampleRate, int channels, long durationMillis) {
        this.itag = itag;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.url = url;
        this.bitrate = bitrate;
        this.contentLength = contentLength;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.durationMillis = durationMillis;
    }

    public int itag() {
        return itag;
    }

    public String mimeType() {
        return mimeType;
    }

    /** The direct media URL, or null when YouTube served this format SABR-only. */
    public String url() {
        return url;
    }

    public int bitrate() {
        return bitrate;
    }

    /**
     * The length YouTube declares, which is what the download is verified against.
     *
     * <p>Not the {@code Content-Length} of any one reply: a truncated container
     * decodes to a truncated take, and MW would analyse the short version without
     * complaint.
     */
    public long contentLength() {
        return contentLength;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public long durationMillis() {
        return durationMillis;
    }

    /** Whether this one can actually be fetched. */
    public boolean isFetchable() {
        return url != null && !url.isEmpty() && contentLength > 0;
    }

    /**
     * The format to fetch, or null when none can be.
     *
     * <p>itag 140 is preferred, and not because AAC is better than Opus — at
     * these bitrates it is not. It arrives at 44100 Hz, and
     * {@code Resampler}'s javadoc records that an integer decimation to the
     * analysis rate is bit-identical while a fractional one is not — choosing
     * 140 puts a fetched track on the same exact-decimation path a phone
     * recording takes, so the two are analysed by the same arithmetic rather
     * than by similar arithmetic.
     *
     * <p>After that, the highest bitrate that can be fetched. The 50 kbps
     * formats (139, 249) are last by that ordering rather than by name: they are
     * band-limited around 10 kHz, which takes energy away from exactly the
     * partials chord estimation reads.
     */
    public static AudioStream choose(List<AudioStream> formats) {
        AudioStream best = null;
        for (AudioStream format : formats) {
            if (!format.isFetchable()) {
                continue;
            }
            if (format.itag == ITAG_M4A_128) {
                return format;
            }
            if (best == null
                    || rank(format) > rank(best)
                    || (rank(format) == rank(best) && format.bitrate > best.bitrate)) {
                best = format;
            }
        }
        return best;
    }

    /** Opus above everything left once 140 is out of the running. */
    private static int rank(AudioStream format) {
        return format.itag == ITAG_OPUS_160 ? 1 : 0;
    }

    @Override
    public String toString() {
        return "itag " + itag + " " + mimeType + " " + bitrate / 1000 + "k "
                + sampleRate + "Hz " + contentLength + " bytes";
    }
}
