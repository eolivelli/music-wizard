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
import dev.olivelli.musicwizard.audio.Resampler;
import dev.olivelli.musicwizard.audio.Spectrogram;
import dev.olivelli.musicwizard.core.model.BeatGrid;
import dev.olivelli.musicwizard.core.model.ChordProgression;
import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.NoteTrack;
import dev.olivelli.musicwizard.core.model.Provenance;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.model.TempoMap;
import dev.olivelli.musicwizard.core.model.TimeSignature;
import dev.olivelli.musicwizard.core.workspace.RunLog;
import dev.olivelli.musicwizard.dsp.BeatTracker;
import dev.olivelli.musicwizard.dsp.Chroma;
import dev.olivelli.musicwizard.dsp.ChordEstimator;
import dev.olivelli.musicwizard.dsp.DownbeatEstimator;
import dev.olivelli.musicwizard.dsp.KeyEstimator;
import dev.olivelli.musicwizard.dsp.MelodyEstimator;
import dev.olivelli.musicwizard.dsp.HarmonicRhythm;
import dev.olivelli.musicwizard.dsp.NnlsAblation;
import dev.olivelli.musicwizard.dsp.NnlsChroma;
import dev.olivelli.musicwizard.dsp.OnsetEnvelope;
import dev.olivelli.musicwizard.dsp.PitchTracker;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
     * The tempo assumed when nothing could be tracked and nothing was supplied.
     *
     * <p>In counted beats per minute, so that it means the same thing as a typed
     * {@code --tempo 120} in every meter.
     */
    private static final double DEFAULT_PULSES_PER_MINUTE = 120;

    /**
     * How far a requested downbeat may sit from a tracked beat in silence —
     * wide enough that a time read off a waveform and rounded passes without
     * comment, narrow enough that landing between two beats does not. Beyond
     * it the beats themselves are wrong and the run needs {@code --tempo} too.
     */
    private static final double DOWNBEAT_SNAP_TOLERANCE_SECONDS = 0.05;

    /**
     * Confidence in a forced phase that fell exactly between two tracked
     * pulses. Counted rather than chosen: which pulse comes out is
     * {@link #nearestBeatIndex}'s tie-break, so the phase is right half the
     * time. The bottom of the ambiguous band only — a request naming no
     * tracked pulse at all is answered separately and lower by
     * {@link #snappedPhaseConfidence}.
     */
    private static final double SNAPPED_PHASE_FLOOR = 0.5;

    /**
     * Optional overrides for the stages that most often need correcting.
     *
     * @param tempoOverride       tempo in the meter's <em>counted</em> beats per
     *                            minute -- dotted quarters in 6/8, not quarters.
     *                            The same unit a metronome and this class's own
     *                            progress output use, and the same one
     *                            {@link TempoMap#constantPulse} takes. Reading it
     *                            as quarter notes puts the bar grid 1.5x out in
     *                            compound time. It corrects the rate; the phase
     *                            comes from the tracked pulses either way.
     * @param timeSignature       the meter to assume, 4/4 when null
     * @param firstDownbeatSeconds when a bar starts, in seconds. Chooses the
     *                            phase of the bar grid outright rather than
     *                            contributing to it: the estimator is not run at
     *                            all, because a human who counted the bars is
     *                            better evidence than harmonic novelty and
     *                            averaging the two would let a confident wrong
     *                            estimate outvote them.
     * @param trackMelody         whether to read a melody out of the audio.
     *                            Off by default, and that is not timidity: the
     *                            tracker is monophonic, so on a full mix it
     *                            returns a confident line that is whatever was
     *                            loudest and most periodic — usually the bass —
     *                            rather than failing. Writing that into every
     *                            score by default would put a wrong melody in
     *                            front of every user who wanted a chord chart.
     *                            What it reads is the mix unless the caller
     *                            hands in a vocal stem — see
     *                            {@link #transcribe(Path, Options, Supplier)},
     *                            which is what {@code analyze --melody} does
     *                            wherever a separation provider can be had.
     */
    public record Options(
            Double tempoOverride,
            TimeSignature timeSignature,
            Double firstDownbeatSeconds,
            boolean trackMelody) {

        public Options {
            // Checked here rather than where they are used, so that a mistyped
            // command line is rejected before a minute of decoding rather than
            // after it -- and named as the option the user typed. A negative
            // downbeat otherwise reached nearestBeatIndex, which has no opinion
            // about it and silently answers "the first beat".
            if (firstDownbeatSeconds != null
                    && (!Double.isFinite(firstDownbeatSeconds) || firstDownbeatSeconds < 0)) {
                throw new IllegalArgumentException(
                        "the first downbeat must be a finite, non-negative number of seconds,"
                                + " got: " + firstDownbeatSeconds);
            }
            if (tempoOverride != null && (!Double.isFinite(tempoOverride) || tempoOverride <= 0)) {
                throw new IllegalArgumentException(
                        "the tempo must be finite and positive, got: " + tempoOverride);
            }
        }

        /**
         * The three corrections, with the melody stage left off.
         *
         * <p>Off is what every caller that predates the stage meant, and the
         * only thing a caller can mean without having chosen a signal the stage
         * can read — so it is a default rather than an omission.
         */
        public Options(Double tempoOverride, TimeSignature timeSignature,
                       Double firstDownbeatSeconds) {
            this(tempoOverride, timeSignature, firstDownbeatSeconds, false);
        }

        public static Options defaults() {
            return new Options(null, TimeSignature.FOUR_FOUR, null, false);
        }

        public TimeSignature timeSignatureOrDefault() {
            return timeSignature != null ? timeSignature : TimeSignature.FOUR_FOUR;
        }
    }

    private final Consumer<String> progress;
    private final RunLog runLog;

    /**
     * @param progress where the running commentary goes, or null for nowhere
     * @param runLog   where each stage records what it did, or null for
     *                 nowhere. A per-run sink like the commentary, and for the
     *                 same reason: what a stage did is not part of what it
     *                 computed.
     */
    public AudioTranscriber(Consumer<String> progress, RunLog runLog) {
        this.progress = progress != null ? progress : message -> { };
        this.runLog = runLog != null ? runLog : new RunLog();
    }

    public AudioTranscriber(Consumer<String> progress) {
        this(progress, null);
    }

    public AudioTranscriber() {
        this(null, null);
    }

    /** Decodes and analyses a recording, reading the melody from the mix. */
    public Score transcribe(Path file, Options options) {
        return transcribe(file, options, null);
    }

    /**
     * The same, with the melody stage pointed at a separated vocal stem.
     *
     * <p>Everything else still reads the mix, chroma above all: separation
     * artifacts destroy the partial structure chord estimation depends on.
     * The melody is the opposite case — {@link PitchTracker} is monophonic and
     * on a mix returns the loudest periodic line, so on a band recording it
     * reads the bass or the guitar and calls it the melody (#559).
     *
     * <p>Supplied as a {@link Supplier} rather than a buffer because
     * separating costs minutes and this stage is off by default: nothing is
     * separated unless {@link Options#trackMelody()} is set and the pipeline
     * reaches the melody stage. It may return {@code null}, which means the
     * mix — that is how a caller whose separator failed degrades to the
     * behaviour of the overload above rather than to no melody at all. The
     * separation itself lives in the caller because {@code mw-transcribe}
     * does not depend on {@code mw-ml} (#247).
     */
    public Score transcribe(Path file, Options options, Supplier<AudioBuffer> vocalStem) {
        Objects.requireNonNull(file, "file");
        Options settings = options != null ? options : Options.defaults();

        progress.accept("decoding " + file.getFileName());
        AudioBuffer audio = AudioDecoder.decode(file);
        if (audio.isEffectivelySilent()) {
            throw new IllegalArgumentException(
                    "the recording is silent, so there is nothing to transcribe: " + file);
        }
        progress.accept(String.format(Locale.ROOT, "decoded %.1fs at %d Hz",
                audio.durationSeconds(), audio.sampleRate()));
        recordDecode(file, audio);

        return transcribe(audio, settings, vocalStem);
    }

    /** Analyses already-decoded audio, reading the melody from it. */
    public Score transcribe(AudioBuffer audio, Options options) {
        return transcribe(audio, options, null);
    }

    /** Analyses already-decoded audio, with {@link #transcribe(Path, Options, Supplier)}'s stem. */
    public Score transcribe(AudioBuffer audio, Options options,
                            Supplier<AudioBuffer> vocalStem) {
        Objects.requireNonNull(audio, "audio");
        Options settings = options != null ? options : Options.defaults();
        TimeSignature meter = settings.timeSignatureOrDefault();

        progress.accept("detecting onsets");
        // One transform, two readings of it: the summed envelope everything
        // downstream works from, and the bass register the beat tracker asks
        // whether its pulse is stated or is a subdivision of the stated one.
        OnsetEnvelope.Both onsets = OnsetEnvelope.bothFromAudio(audio);
        OnsetEnvelope envelope = onsets.envelope();

        // Chroma is extracted before the beats, not after, and the order is
        // load-bearing since #231: the beat tracker weighs its tempo candidates
        // by whether the harmony can be barred by them, and harmonic rhythm is
        // read from frame-level chroma, which needs no beats. Beat-synchronous
        // chroma still needs them and is derived further down.
        progress.accept("extracting chroma");
        // The transform once, for both views of the same fit: the chroma folds
        // the activations, the ablation refits without one pitch class at a
        // time. Handed different transforms they would describe different fits.
        Spectrogram transform = NnlsChroma.transform(audio);
        double tuning = Chroma.estimateTuning(transform);
        NnlsChroma registers = NnlsChroma.extract(transform, tuning);
        Chroma combinedFrames = registers.combined();
        HarmonicRhythm harmonicRhythm = HarmonicRhythm.of(combinedFrames);
        runLog.stage("chroma").computed();

        progress.accept("tracking beats");
        BeatTracker.Result beats = BeatTracker.track(envelope, harmonicRhythm, onsets.pulseRegister());
        if (beats.isEmpty()) {
            progress.accept("no beats found; returning an empty score");
            runLog.stage("beats").computed("no pulse was found");
            for (String unreached : List.of("chords", "key", "melody")) {
                runLog.stage(unreached).skipped("no beats were tracked, so the run stopped here");
            }
            if (settings.firstDownbeatSeconds() != null) {
                // Said rather than passed over. There is genuinely no pulse to
                // mark as a downbeat, but an override that vanishes without
                // comment is the shape of the bug this branch's sibling had.
                progress.accept("no beats were tracked, so the requested downbeat"
                        + " has nothing to mark; ignoring it");
            }
            // A tempo the user supplied still applies when the tracker found
            // nothing: they are the caller most likely to have typed one, since
            // an unreadable track is exactly what a correction is for, and
            // answering them with a hard-coded 120 discards an instruction.
            //
            // Counted beats a minute, not quarter notes: the default has to mean
            // the same thing as a typed --tempo 120, or the fallback and the
            // override disagree in compound time.
            FallbackTempo fallback = FallbackTempo.forOptions(settings);
            return Score.empty(
                    TempoMap.constantPulse(
                            fallback.pulsesPerMinute(), meter, fallback.provenance()),
                    audio.durationSeconds());
        }

        List<Double> beatTimes = beats.beatTimes();
        // "beats/min", not a tempo: the tracker counts pulses, which are the
        // quarter note only in simple time. The grid's own steady rate rather
        // than the tracker's median, because the user is told they may type
        // this figure back via --tempo and it must match what the chart is
        // headed with (#200). The lone-pulse arm cannot hold that property —
        // no interval, no rate — and falls back to the tracker's seed, which
        // on a short clip is a function of the clip's length (#240).
        progress.accept(String.format(Locale.ROOT, "found %d beats at %.1f beats/min",
                beatTimes.size(),
                beatTimes.size() >= 2
                        ? BeatGrid.steadyPulseRate(beatTimes)
                        : beats.beatsPerMinute()));

        // How the tracked pulses bar the music where the correction says they
        // are not the counted beat. Nothing measures this from audio — the
        // tracker lands on a sub-multiple without knowing it (#353) — so a
        // typed tempo against kept pulses is the only producer: their ratio is
        // the pulse. Read before the map, whose lead-in is measured in tracked
        // pulses too.
        OptionalInt correctedPulsesPerBar =
                trackedPulsesPerBar(settings.tempoOverride(), beatTimes, meter);
        int pulsesPerBar = correctedPulsesPerBar.orElse(meter.beatsPerBar());
        OptionalDouble pulseQuarters = correctedPulsesPerBar.isPresent()
                ? OptionalDouble.of(meter.quarterBeatsPerBar() / pulsesPerBar)
                : OptionalDouble.empty();
        if (correctedPulsesPerBar.isPresent()) {
            progress.accept(String.format(Locale.ROOT,
                    "the supplied tempo puts %d tracked beat%s in a bar, not %d",
                    pulsesPerBar, pulsesPerBar == 1 ? "" : "s", meter.beatsPerBar()));
        }

        // Beat-synchronised chroma before the beat grid, and the grid before
        // the tempo map: the downbeat phase is chosen from harmonic change and
        // the map anchors its lead-in on that phase (#84) — the other way
        // round, the map's bar lines and the grid's downbeats disagree (#501).
        // NNLS rather than the plain fold because the plain fold does not work
        // on records (#185); both registers, not the treble alone, because the
        // two fail on different chords. combined() before beatSynchronous(),
        // and the order is not cosmetic — beat-synchronising first normalises
        // each register separately, making every beat half treble and half
        // bass whatever they contained; see NnlsChroma.combined.
        Chroma chroma = combinedFrames.beatSynchronous(beatTimes);
        // The treble alone, for the quality half of chord recognition only --
        // ChordEstimator.estimate(Chroma, Chroma, List) has the measurement. Not
        // combined(), so this one is beat-synchronised on its own: the question
        // it answers is what share of the *chordal* register each pitch class
        // holds, and normalising the sum would put the bass back into it.
        Chroma treble = registers.treble().beatSynchronous(beatTimes);
        // And the bass alone, for the root half: which of a chord's own notes is
        // its root is the one thing the fold above cannot say, and the register
        // where the root is played is the one that can. Beat-synchronised on its
        // own for the same reason the treble is.
        Chroma bass = registers.bass().beatSynchronous(beatTimes);
        // And the fit's own residual over those same spans, which is what says
        // whether a third the chroma reports is a third the music holds (#537).
        NnlsAblation ablation =
                NnlsAblation.extract(transform, tuning).beatSynchronous(beatTimes);

        // Pulses per bar, not the numerator: DownbeatEstimator asks for "the
        // assumed bar length in beats", and the beats it means are the tracked
        // ones it is phasing. 6/8 counts two of them to a bar rather than six,
        // and a corrected pulse counts its own.
        DownbeatEstimator.Estimate downbeat = settings.firstDownbeatSeconds() != null
                ? forcedDownbeat(beatTimes, settings.firstDownbeatSeconds(), pulsesPerBar)
                : DownbeatEstimator.estimate(beatTimes, chroma, envelope, pulsesPerBar);
        BeatGrid tracked = BeatTracker.toBeatGrid(beats, downbeat);
        // Recorded on the grid rather than left to each reader, because the grid
        // is what a reader has: BeatGrid.steadyTempo and medianTempo convert a
        // pulse rate to quarter notes, and before this the only figure they could
        // convert through was the meter's.
        BeatGrid grid = pulseQuarters.isPresent()
                ? tracked.withPulseQuarters(pulseQuarters.getAsDouble())
                : tracked;

        // A tempo override replaces the tracked tempo but not the tracked
        // beats: the user is usually correcting a half-or-double reading, not
        // claiming the beats are misplaced, so the override supplies the rate
        // and the pulses the phase (constantPulseFrom). Read as counted beats
        // per minute — what a metronome and this run's own report show — or
        // correcting the tempo in 6/8 would silently move every bar line. A
        // lone tracked pulse carries no interval and falls back, keeping the
        // clip transcribable.
        TempoMap tempoMap;
        if (beatTimes.size() >= 2 && settings.tempoOverride() == null) {
            tempoMap = TempoMap.fromBeatTimes(beatTimes, meter,
                    meter.beatUnitQuarters(), downbeat.phase(), pulsesPerBar);
        } else {
            // A typed tempo, or a clip with no interval to infer one from —
            // both take the figure and its origin from one place, so a tempo
            // cannot arrive wearing the other branch's label. Only an untyped
            // lone pulse announces the assumption.
            FallbackTempo fallback = FallbackTempo.forOptions(settings);
            if (settings.tempoOverride() == null) {
                progress.accept("only one beat was tracked, which carries no tempo; assuming "
                        + (int) fallback.pulsesPerMinute() + " beats/min");
            }
            tempoMap = constantPulseFrom(fallback.pulsesPerMinute(), meter,
                    beatTimes.get(0), fallback.provenance(),
                    pulseQuarters.orElse(meter.beatUnitQuarters()),
                    downbeat.phase(), pulsesPerBar);
        }

        runLog.stage("beats").computed();

        progress.accept("estimating chords");
        ChordProgression chords =
                ChordEstimator.estimate(chroma, treble, bass, ablation, beatTimes);
        progress.accept(String.format(Locale.ROOT, "found %d chord spans", chords.size()));
        runLog.stage("chords").computed();

        // Over the whole recording rather than over the chords' own extent: a key
        // is what the listener hears the piece as being in, and it does not stop
        // at the last chord the estimator was able to name. Score.keyAt would
        // otherwise answer nothing for the lead-in and the tail.
        Optional<KeyEstimator.Estimate> key =
                KeyEstimator.estimate(chords, 0, audio.durationSeconds());
        key.ifPresentOrElse(
                // Worded by the key itself, so this line, the summary and the
                // chart cannot describe one key three ways.
                estimate -> {
                    progress.accept("key " + estimate.key().displayNameWithConfidence());
                    runLog.stage("key").computed();
                },
                () -> {
                    progress.accept("no chord sounds, so no key was estimated");
                    runLog.stage("key").computed("no chord sounds, so no key was estimated");
                });

        Score score = Score.empty(tempoMap, audio.durationSeconds())
                .withBeatGrid(grid)
                .withChords(chords)
                .withKeys(key.map(estimate -> List.of(estimate.key())).orElse(List.of()));

        // Last, and from whatever the caller says the melody is in: there is no
        // separation in this module, so the signal is chosen by handing it in.
        // The stage is off unless asked for -- see Options.trackMelody.
        if (!settings.trackMelody()) {
            runLog.stage("melody").skipped("not asked for; analyze --melody reads one");
        } else {
            AudioBuffer melodyAudio = melodySignal(audio, vocalStem);
            boolean separated = melodyAudio != audio;
            progress.accept(separated
                    ? "tracking the melody in the vocal stem"
                    : "tracking the melody in the full mix");
            RunLog.Stage stage = runLog.stage("melody")
                    .fact("read from", separated ? "the separated vocal" : "the full mix");
            // The envelope of the signal being tracked, not of the mix. It
            // decides where a note is struck again at the same pitch, and
            // MelodyEstimator measures a peak in standard deviations of the
            // envelope it is given -- so a mix envelope would rate the band's
            // drums against the band's own spread and cut vocal notes on them.
            // The other half of that decision, the voicedness dip, is read from
            // this same signal's pitch track (#495, #497).
            OnsetEnvelope melodyEnvelope =
                    separated ? OnsetEnvelope.fromAudio(melodyAudio) : envelope;
            // The mix's tuning, not the stem's, and the same figure the chroma
            // was built at: a lead sheet whose chords and whose melody were
            // rounded on different grids can name the same sounding pitch two
            // ways. The band is also the better reference of the two, having
            // more of the recording in it than one voice does (#566).
            NoteTrack melody = MelodyEstimator.estimate(
                    PitchTracker.track(melodyAudio), melodyEnvelope, tuning);
            if (melody.isEmpty()) {
                progress.accept("no melody was found");
                stage.computed("no melody was found in that signal");
            } else {
                progress.accept(String.format(Locale.ROOT, "found %d melody notes over %s",
                        melody.size(),
                        melody.pitchRange().map(Object::toString).orElse("no range")));
                score = score.withTrack(melody);
                stage.computed();
            }
        }
        return score;
    }

    /** What the file was and what it became, for the run's record. */
    private void recordDecode(Path file, AudioBuffer audio) {
        RunLog.Stage stage = runLog.stage("decode");
        AudioDecoder.describe(file).ifPresent(format -> {
            stage.fact("format", format.type().equals(format.encoding())
                    ? format.type() : format.type() + ", " + format.encoding());
            if (format.sampleRate() > 0) {
                stage.fact("sample rate as stored", format.sampleRate() + " Hz");
            }
            if (format.channels() > 0) {
                stage.fact("channels as stored", format.channels());
            }
        });
        stage.fact("read as", "mono at " + audio.sampleRate() + " Hz")
                .fact("duration as decoded",
                        String.format(Locale.ROOT, "%.2f s", audio.durationSeconds()))
                .computed();
    }

    /**
     * What the melody stage listens to: the caller's stem, or the mix when
     * there is no stem to be had. The mix arm returns the very buffer it was
     * given, which is what lets the caller keep the mix's own envelope.
     *
     * <p>Resampled here rather than by the caller because the two rates belong
     * to different modules: a separator states its own
     * ({@code SeparationProvider.preferredSampleRate}) and {@link PitchTracker}
     * insists on the analysis rate, which it throws rather than assumes. The
     * clock is untouched — a resample changes how many samples carry a second,
     * not how many seconds there are — so the notes stay on the recording's
     * timeline and align with the beats and chords read from the mix.
     */
    private static AudioBuffer melodySignal(AudioBuffer audio, Supplier<AudioBuffer> vocalStem) {
        AudioBuffer stem = vocalStem == null ? null : vocalStem.get();
        if (stem == null) {
            return audio;
        }
        if (stem.sampleRate() == audio.sampleRate()) {
            return stem;
        }
        return new AudioBuffer(
                Resampler.resample(stem.samples(), stem.sampleRate(), audio.sampleRate()),
                audio.sampleRate());
    }

    /**
     * The tempo to use when there is nothing to infer one from, together with
     * where it came from — one value carrying both, so the figure and its
     * label cannot drift apart when a third source arrives.
     */
    private record FallbackTempo(double pulsesPerMinute, Provenance provenance) {

        static FallbackTempo forOptions(Options settings) {
            return settings.tempoOverride() != null
                    ? new FallbackTempo(settings.tempoOverride(), Provenance.SUPPLIED)
                    : new FallbackTempo(DEFAULT_PULSES_PER_MINUTE, Provenance.ASSUMED);
        }
    }

    /**
     * The relations a tracked pulse and a counted beat may stand in.
     *
     * <p>The relations a beat is actually divided or multiplied by, for the
     * reason {@link BeatTracker}'s own table gives: a rate that is no whole
     * subdivision of the beat is not a rate the music has. Those entries plus
     * {@code 1}, which is the answer that records nothing, and its tolerance and
     * relative-distance rule unchanged. A second copy of a musical fact; #371.
     */
    private static final double[] PULSE_RELATIONS =
            {1.0 / 4, 1.0 / 3, 1.0 / 2, 2.0 / 3, 1.0, 3.0 / 2, 2.0, 3.0, 4.0};

    /** How far a ratio may sit from a relation and still be read as one. */
    private static final double RELATION_TOLERANCE = 0.05;

    /**
     * How many tracked pulses fill a bar, where a supplied tempo says the
     * pulse is not the meter's counted beat — the only measurement there is,
     * since the tracker cannot know it landed on a sub-multiple (#353); the
     * ratio of corrected rate to kept pulses is the pulse (#139). Empty
     * unless the ratio is a musical relation (a nudge from 105 to 106 is not
     * an octave error) <em>and</em> a whole number of such pulses fills a
     * bar. A bar count rather than a pulse, so no rounding step sits between
     * two figures that must agree exactly. Package-private for the ratios a
     * recording cannot easily be made to produce.
     */
    static OptionalInt trackedPulsesPerBar(
            Double tempoOverride, List<Double> beatTimes, TimeSignature meter) {
        if (tempoOverride == null || beatTimes.size() < 2) {
            return OptionalInt.empty();
        }
        double observed = tempoOverride / BeatGrid.steadyPulseRate(beatTimes);
        for (double relation : PULSE_RELATIONS) {
            // Relative, so that 1/3 and 3 are held to the same standard.
            if (Math.abs(observed - relation) / relation >= RELATION_TOLERANCE) {
                continue;
            }
            if (relation == 1.0) {
                // The counted beat, which is what every reader already assumes.
                // Recording it would state an assumption as a measurement.
                return OptionalInt.empty();
            }
            double perBar = meter.quarterBeatsPerBar() / (relation * meter.beatUnitQuarters());
            return perBar >= 1 && Math.abs(perBar - Math.rint(perBar)) < 1e-9
                    ? OptionalInt.of((int) Math.rint(perBar))
                    : OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    /**
     * A constant-tempo map whose pulses start where the tracked ones do.
     * {@link TempoMap#constantPulse} anchors at the origin with no lead-in, so
     * a corrected rate would throw the phase away and put bar lines half a
     * beat from the grid stored beside them. Anchored the way
     * {@link TempoMap#fromBeatTimes} anchors: the audio before the first
     * tracked pulse is a whole number of <em>pulses</em> — not quarters, which
     * are not whole dotted quarters — stretched to land exactly on it. The
     * pulse is a parameter because the correction can say it is not the
     * counted beat (#139); this form leaves the bar phase unknown, which the
     * fullest overload carries (#84). The lead-in is
     * {@link Provenance#DERIVED}: its rate is whatever reached the first
     * pulse, not a tempo anyone supplied, and {@link Score#estimatedTempo()}
     * reads that label. Package-private for anchors unreachable through a
     * recording.
     *
     * @param pulsesPerMinute  the counted tempo to hold throughout
     * @param firstBeatSeconds when the first tracked pulse falls
     * @param provenance       where {@code pulsesPerMinute} came from
     */
    static TempoMap constantPulseFrom(double pulsesPerMinute, TimeSignature meter,
                                      double firstBeatSeconds, Provenance provenance) {
        // Bypasses TempoMap.requireBarPhase: the pulsesPerBar of 1 here is
        // "phase unknown", not a claim that a bar holds one pulse, and the
        // tiling check would read it as the claim.
        return buildConstantPulseFrom(pulsesPerMinute, meter, firstBeatSeconds, provenance,
                meter.beatUnitQuarters(), 0, 1);
    }

    /**
     * The same map, with the tracker's downbeat phase honoured: the lead-in is
     * chosen so the first downbeat lands on a bar line, exactly as {@link
     * TempoMap#fromBeatTimes(List, TimeSignature, double, int, int)} chooses
     * its own — both anchor with {@link TempoMap#leadInPulses} and both
     * validate with {@link TempoMap#requireBarPhase}, so a supplied tempo and
     * a tracked one can neither bar the same grid differently nor take a
     * miscounted bar silently (#84). This form receives the <em>derived</em>
     * pair — a #139-corrected pulse and its count — which is exactly the pair
     * that can be wrong, so it is the one place the check must not be skipped.
     *
     * @param firstDownbeatPulse index of a tracked pulse that begins a bar
     * @param pulsesPerBar       tracked pulses in one bar
     */
    static TempoMap constantPulseFrom(double pulsesPerMinute, TimeSignature meter,
                                      double firstBeatSeconds, Provenance provenance,
                                      double pulseQuarters, int firstDownbeatPulse,
                                      int pulsesPerBar) {
        TempoMap.requireBarPhase(meter, pulseQuarters, firstDownbeatPulse, pulsesPerBar);
        return buildConstantPulseFrom(pulsesPerMinute, meter, firstBeatSeconds, provenance,
                pulseQuarters, firstDownbeatPulse, pulsesPerBar);
    }

    private static TempoMap buildConstantPulseFrom(
            double pulsesPerMinute, TimeSignature meter, double firstBeatSeconds,
            Provenance provenance, double pulseQuarters, int firstDownbeatPulse,
            int pulsesPerBar) {
        // Built first so that a bad tempo is rejected in the units it was typed
        // in, before any of the arithmetic below can turn it into something else.
        TempoMap constant = TempoMap.constantPulse(pulsesPerMinute, meter, provenance);
        if (!(firstBeatSeconds > 0)) {
            // The first pulse is already the origin, so there is no lead-in and
            // nothing to anchor: the constant map carries the phase as it is.
            return constant;
        }
        double quarterBpm = constant.initialTempo();
        double pulseSeconds = 60.0 * pulseQuarters / quarterBpm;

        int leadInPulses = TempoMap.leadInPulses(
                firstBeatSeconds, pulseSeconds, firstDownbeatPulse, pulsesPerBar);

        double leadInTempo = 60.0 * leadInPulses * pulseQuarters / firstBeatSeconds;
        if (!Double.isFinite(leadInTempo) || leadInTempo <= 0) {
            // Only reachable from an absurdly small first beat, where cramming a
            // whole pulse into it overflows. The phase is then unrepresentable,
            // and the rate the user asked for is worth more than a thrown map.
            return constant;
        }
        return new TempoMap(
                List.of(new TempoMap.TempoSegment(0, 0.0, leadInTempo, Provenance.DERIVED),
                        new TempoMap.TempoSegment(leadInPulses * pulseQuarters,
                                firstBeatSeconds, quarterBpm, provenance)),
                List.of(new TempoMap.MeterChange(0, meter)));
    }

    /**
     * The bar phase a user asked for, in place of the estimated one.
     *
     * <p>Snapped to the nearest tracked pulse, because the grid can only mark a
     * pulse as a downbeat and a typed time is not going to land on one exactly.
     * That is also the honest reading of the instruction: the user is saying
     * which beat begins the bar, not proposing a beat the tracker missed.
     *
     * <p>Reported as {@link Confidence#CERTAIN} when the request landed on a
     * tracked pulse: a human counted the bars, and nothing this pipeline measures
     * outranks that. It is not a claim about the grid, only about the phase --
     * {@link BeatTracker#toBeatGrid} multiplies it by the confidence in the beats
     * being phased, so a hand-picked downbeat over shaky beats still reads as
     * shaky, which is what it is.
     *
     * <p>It falls away with the distance the snap had to move, because at that
     * point two different things are being reported as one. A request halfway
     * between two pulses names neither of them, and the phase that comes out is
     * this method's guess at which neighbour was meant, not the user's
     * instruction -- yet it would otherwise be recorded as more certain than any
     * phase harmony ever backs.
     */
    private DownbeatEstimator.Estimate forcedDownbeat(
            List<Double> beatTimes, double downbeatSeconds, int beatsPerBar) {
        int index = nearestBeatIndex(beatTimes, downbeatSeconds);
        double distance = Math.abs(beatTimes.get(index) - downbeatSeconds);
        if (distance > DOWNBEAT_SNAP_TOLERANCE_SECONDS) {
            progress.accept(String.format(Locale.ROOT,
                    "the requested downbeat at %.3fs is %.3fs from the nearest tracked beat"
                            + " at %.3fs; using that beat",
                    downbeatSeconds, distance, beatTimes.get(index)));
        }
        return new DownbeatEstimator.Estimate(
                Math.floorMod(index, beatsPerBar), beatsPerBar,
                beatsPerBar == 1
                        // Phase 0 is the only phase there is; reporting less
                        // than certain would rank a claim that cannot be wrong
                        // below one that can.
                        ? Confidence.CERTAIN
                        : snappedPhaseConfidence(
                                beatTimes, index, downbeatSeconds, beatsPerBar));
    }

    /**
     * How far to trust a phase the user chose but the grid had to move.
     *
     * <p>Inside the tracked range: linear falloff from certainty at a named
     * pulse to {@link #SNAPPED_PHASE_FLOOR} at the midpoint between two, where
     * which pulse comes out is a tie-break rather than an instruction. The
     * scale is the <em>smaller</em> of the rival gap and the median interval —
     * the local gap alone over-reports where the tracker dropped a beat, the
     * median alone over-reports where a passage speeds up.
     *
     * <p>Outside the range and past half a pulse there is no second candidate
     * and the request names nothing tracked: the phase is what clamping
     * produced, worth one guess in {@code beatsPerBar} — sharing the tie-break
     * floor once put a downbeat typed far past the end of a recording above
     * the estimator's own unsupported phase. Keyed on being outside the range
     * rather than on distance, so the ordinary midpoint case cannot flip on
     * jitter.
     *
     * <p>Deliberately not ordered against {@link DownbeatEstimator}'s flat
     * floor, which the two-beat bar inverts — the count here is the accurate
     * one of the pair, and making them commensurate is #88
     * ({@code theBlindPhaseOnlyOutranksNothingInWiderBars} fails when it is
     * done). With fewer than two pulses the one that exists is the one the
     * user named. Package-private so the grids that make the two scales
     * disagree can be driven directly.
     */
    static Confidence snappedPhaseConfidence(
            List<Double> beatTimes, int index, double downbeatSeconds, int beatsPerBar) {
        if (beatTimes.size() < 2) {
            return Confidence.CERTAIN;
        }
        double chosen = beatTimes.get(index);
        // The pulse the snap would have picked had the request fallen a little
        // further the same way -- which is the one it is being confused with.
        // Absent exactly when the request is outside the tracked range, since
        // nothing outside it lies between two pulses.
        int rival = downbeatSeconds >= chosen ? index + 1 : index - 1;
        double rivalGap = rival >= 0 && rival < beatTimes.size()
                ? Math.abs(beatTimes.get(rival) - chosen)
                : Double.POSITIVE_INFINITY;
        double halfPulse = Math.min(medianInterval(beatTimes), rivalGap) / 2;
        double missed = halfPulse > 0
                ? Math.abs(chosen - downbeatSeconds) / halfPulse
                // Also the NaN path: a grid whose median is zero cannot say how
                // far out anything is.
                : Double.POSITIVE_INFINITY;
        if (Double.isInfinite(rivalGap) && !(missed <= 1)) {
            return Confidence.clamped(1.0 / beatsPerBar);
        }
        return Confidence.clamped(
                1.0 - (1.0 - SNAPPED_PHASE_FLOOR) * Math.clamp(missed, 0, 1));
    }

    /** The median gap between tracked pulses, as a robust idea of pulse length. */
    private static double medianInterval(List<Double> beatTimes) {
        double[] intervals = new double[beatTimes.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = beatTimes.get(i + 1) - beatTimes.get(i);
        }
        Arrays.sort(intervals);
        int middle = intervals.length / 2;
        return intervals.length % 2 == 1
                ? intervals[middle]
                : (intervals[middle - 1] + intervals[middle]) / 2.0;
    }

    /**
     * Index of the tracked pulse nearest a time.
     *
     * <p>{@link BeatGrid#nearestBeatIndex} would do, but the grid does not exist
     * yet: its bar positions are what this is being asked for. A linear scan that
     * stops once the distance grows, over a list that is sorted by construction.
     */
    private static int nearestBeatIndex(List<Double> beatTimes, double seconds) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < beatTimes.size(); i++) {
            double distance = Math.abs(beatTimes.get(i) - seconds);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            } else {
                break;
            }
        }
        return best;
    }
}
