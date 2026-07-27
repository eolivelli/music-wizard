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

package dev.olivelli.musicwizard.transcribe;

import dev.olivelli.musicwizard.audio.AudioBuffer;
import dev.olivelli.musicwizard.audio.AudioDecoder;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.dsp.BeatTracker;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.ChordEstimator;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs the analysis stages over a recording and assembles a {@link Score}.
 *
 * <p>The order is fixed by what each stage needs: beats come first because
 * chords are estimated over beat-synchronous chroma, and chroma is taken from
 * the full mix rather than from any separated stem — separation artifacts
 * destroy the partial structure chroma estimation depends on.
 */
public final class AudioTranscriber {

    /** Optional overrides for the stages that most often need correcting. */
    public record Options(
            Double tempoOverride,
            TimeSignature timeSignature,
            Double firstDownbeatSeconds) {

        public static Options defaults() {
            return new Options(null, TimeSignature.FOUR_FOUR, null);
        }

        public TimeSignature timeSignatureOrDefault() {
            return timeSignature != null ? timeSignature : TimeSignature.FOUR_FOUR;
        }
    }

    private final Consumer<String> progress;

    public AudioTranscriber(Consumer<String> progress) {
        this.progress = progress != null ? progress : message -> { };
    }

    public AudioTranscriber() {
        this(null);
    }

    /** Decodes and analyses a recording. */
    public Score transcribe(Path file, Options options) {
        Objects.requireNonNull(file, "file");
        Options settings = options != null ? options : Options.defaults();

        progress.accept("decoding " + file.getFileName());
        AudioBuffer audio = AudioDecoder.decode(file);
        if (audio.isEffectivelySilent()) {
            throw new IllegalArgumentException(
                    "the recording is silent, so there is nothing to transcribe: " + file);
        }
        progress.accept(String.format("decoded %.1fs at %d Hz",
                audio.durationSeconds(), audio.sampleRate()));

        return transcribe(audio, settings);
    }

    /** Analyses already-decoded audio. */
    public Score transcribe(AudioBuffer audio, Options options) {
        Objects.requireNonNull(audio, "audio");
        Options settings = options != null ? options : Options.defaults();
        TimeSignature meter = settings.timeSignatureOrDefault();

        progress.accept("detecting onsets");
        OnsetEnvelope envelope = OnsetEnvelope.fromAudio(audio);

        progress.accept("tracking beats");
        BeatTracker.Result beats = BeatTracker.track(envelope);
        if (beats.isEmpty()) {
            progress.accept("no beats found; returning an empty score");
            return Score.empty(TempoMap.constant(120, meter), audio.durationSeconds());
        }

        List<Double> beatTimes = beats.beatTimes();
        progress.accept(String.format("found %d beats at %.1f BPM",
                beatTimes.size(), beats.beatsPerMinute()));

        // A tempo override replaces the tracked tempo but not the tracked beats:
        // the beats are measured evidence, whereas the tempo is a summary of
        // them, and a user correcting the tempo is usually correcting a
        // half-or-double reading rather than claiming the beats are misplaced.
        TempoMap tempoMap = settings.tempoOverride() != null
                ? TempoMap.constant(settings.tempoOverride(), meter)
                : TempoMap.fromBeatTimes(beatTimes, meter);

        BeatGrid grid = BeatTracker.toBeatGrid(beats, envelope, meter.numerator());

        progress.accept("estimating chords");
        Chroma chroma = Chroma.extract(audio).beatSynchronous(beatTimes);
        ChordProgression chords = ChordEstimator.estimate(chroma, beatTimes);
        progress.accept(String.format("found %d chord spans", chords.size()));

        return Score.empty(tempoMap, audio.durationSeconds())
                .withBeatGrid(grid)
                .withChords(chords);
    }
}
