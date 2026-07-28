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

    /**
     * Optional overrides for the stages that most often need correcting.
     *
     * @param tempoOverride       tempo in the meter's <em>counted</em> beats per
     *                            minute -- dotted quarters in 6/8, not quarters.
     *                            The same unit a metronome and this class's own
     *                            progress output use, and the same one
     *                            {@link TempoMap#constantPulse} takes. Reading it
     *                            as quarter notes puts the bar grid 1.5x out in
     *                            compound time. It corrects the rate but not the
     *                            phase; see #65.
     * @param timeSignature       the meter to assume, 4/4 when null
     * @param firstDownbeatSeconds currently ignored; see #67
     */
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
            // 120 counted beats a minute, not 120 quarter notes: the default has
            // to mean the same thing as a typed --tempo 120, or the fallback and
            // the override disagree in compound time.
            return Score.empty(TempoMap.constantPulse(120, meter), audio.durationSeconds());
        }

        List<Double> beatTimes = beats.beatTimes();
        // Named as beats per minute rather than as a tempo on purpose: the
        // tracker counts pulses, and a pulse is a quarter note only in simple
        // time, so this figure is 1.5x under the quarter-note tempo in 6/8.
        progress.accept(String.format("found %d beats at %.1f beats/min",
                beatTimes.size(), beats.beatsPerMinute()));

        // A tempo override replaces the tracked tempo but not the tracked beats:
        // the beats are measured evidence, whereas the tempo is a summary of
        // them, and a user correcting the tempo is usually correcting a
        // half-or-double reading rather than claiming the beats are misplaced.
        // The beats do survive, in the grid below -- but only their rate reaches
        // the map, because a constant map has no lead-in and so cannot carry
        // their phase. The map and the grid can therefore disagree about where
        // the beats are by up to half a pulse; see #65.
        //
        // The override is read as counted beats per minute, which is what the
        // user is looking at when they type it -- a metronome marking, or the
        // rate this very run just reported. Passing it to TempoMap.constant
        // instead would read it as quarter notes per minute, so in 6/8 the two
        // branches below would describe bars 1.5x apart and correcting the tempo
        // would silently move every bar line.
        TempoMap tempoMap = settings.tempoOverride() != null
                ? TempoMap.constantPulse(settings.tempoOverride(), meter)
                : TempoMap.fromBeatTimes(beatTimes, meter);

        // Pulses per bar, not the numerator: the tracker emits one pulse per
        // counted beat, and 6/8 counts two of them to a bar rather than six.
        BeatGrid grid = BeatTracker.toBeatGrid(beats, envelope, meter.beatsPerBar());

        progress.accept("estimating chords");
        Chroma chroma = Chroma.extract(audio).beatSynchronous(beatTimes);
        ChordProgression chords = ChordEstimator.estimate(chroma, beatTimes);
        progress.accept(String.format("found %d chord spans", chords.size()));

        return Score.empty(tempoMap, audio.durationSeconds())
                .withBeatGrid(grid)
                .withChords(chords);
    }
}
