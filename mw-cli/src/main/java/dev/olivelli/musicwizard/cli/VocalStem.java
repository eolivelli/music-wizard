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

package dev.olivelli.musicwizard.cli;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.core.config.MusicWizardConfig;
import dev.olivelli.musicwizard.core.ml.MlProviders;
import dev.olivelli.musicwizard.core.ml.SeparationProvider;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One run's separated vocal, computed at most once.
 *
 * <p>Two stages read it — the melody (#559) and lyric transcription (#314) —
 * and separating a song costs minutes, so whichever asks first pays and the
 * other is handed the same buffer. Lazily, because a run may reach neither:
 * an analysis served from the cache separates nothing at all.
 *
 * <p>Here rather than in {@code mw-transcribe}, which must not depend on
 * {@code mw-ml} (#247): the CLI separates and hands the stem in.
 */
final class VocalStem {

    private final Path source;
    private final SeparationProvider provider;
    private AudioBuffer voice;

    private VocalStem(Path source, SeparationProvider provider) {
        this.source = source;
        this.provider = provider;
    }

    /**
     * The stem of this recording, or empty when nothing on this classpath can
     * separate it. Callers that skip separation must not ask.
     */
    static Optional<VocalStem> forRun(Path source, MusicWizardConfig config) {
        MusicWizardConfig.MlConfig ml = config.ml();
        return MlProviders.separation(ml == null ? null : ml.separationProvider())
                .map(provider -> new VocalStem(source, provider));
    }

    /** The id to name in a report, and in the transcription cache key. */
    String providerId() {
        return provider.id();
    }

    /**
     * The vocal, at the separator's own preferred rate — a decode below it
     * band-limits what the model reads and no later resample recovers that;
     * {@code SeparationProvider.preferredSampleRate} carries the why. Every
     * consumer resamples from here to what it needs.
     *
     * <p>Announced like any other stage, and only on the call that does the
     * work: this takes real time, and a command that reports each step must
     * not sit mute through it.
     */
    AudioBuffer voice(Consumer<String> report) {
        if (voice == null) {
            int preferred = provider.preferredSampleRate();
            AudioBuffer mix = preferred > 0
                    ? AudioDecoder.decode(source, preferred)
                    : AudioDecoder.decode(source);
            report.accept("separating the vocal with " + provider.id());
            voice = new AudioBuffer(
                    provider.separate(new float[][] {mix.samples()}, mix.sampleRate())
                            .vocals()[0],
                    mix.sampleRate());
        }
        return voice;
    }
}
