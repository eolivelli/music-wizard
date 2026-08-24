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

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.Confidence;
import dev.olivelli.musicwizard.core.model.Key;
import dev.olivelli.musicwizard.core.model.Note;
import dev.olivelli.musicwizard.core.model.PartRole;
import dev.olivelli.musicwizard.core.model.Score;
import dev.olivelli.musicwizard.core.workspace.BeatTrace;
import dev.olivelli.musicwizard.core.workspace.ChordTrace;
import dev.olivelli.musicwizard.core.workspace.ChromaTrace;
import dev.olivelli.musicwizard.core.workspace.KeyTrace;
import dev.olivelli.musicwizard.core.workspace.MelodyTrace;
import dev.olivelli.musicwizard.core.workspace.RunManifest;
import dev.olivelli.musicwizard.core.workspace.RunManifest.Outcome;
import dev.olivelli.musicwizard.core.workspace.Workspace;
import dev.olivelli.musicwizard.testkit.MidiFixtures;
import dev.olivelli.musicwizard.testkit.SignalFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code analyze} records about its own run (#674).
 *
 * <p>The fixture is the one {@code MelodyFromTheStemTest} uses — clicks with a
 * bass note under them — because the pipeline has to reach every stage for the
 * record to name them: a signal with no trackable pulse returns an empty score
 * and stops at the beat tracker.
 */
@DisplayName("the run manifest an analysis writes")
class RunManifestCliTest {

    @TempDir
    Path directory;

    private Path workspaceDirectory;

    @BeforeEach
    void importFixture() throws IOException {
        int rate = SignalFactory.DEFAULT_SAMPLE_RATE;
        float[] samples = SignalFactory.clickTrack(120, 8.0, rate);
        float[] bass = SignalFactory.sine(SignalFactory.midiToHz(45), 8.0, rate);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f * samples[i] + 0.5f * bass[i];
        }
        Path source = directory.resolve("band.wav");
        SignalFactory.writeWav(source, samples, rate);
        workspaceDirectory = directory.resolve("band.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w",
                workspaceDirectory.toString()).exitCode()).isZero();
    }

    /** Points the workspace at one separation provider by id. */
    private void configureSeparation(String id) throws IOException {
        configureMl("separationProvider: " + id);
    }

    /** Writes one ml block into the workspace's own config layer. */
    private void configureMl(String... keys) throws IOException {
        Path descriptor = workspaceDirectory.resolve("workspace.yaml");
        StringBuilder block = new StringBuilder("\nconfig:\n  ml:\n");
        for (String key : keys) {
            block.append("    ").append(key).append('\n');
        }
        Files.writeString(descriptor, Files.readString(descriptor) + block);
    }

    private RunManifest manifest() {
        return Workspace.open(workspaceDirectory).readRunManifest().orElseThrow();
    }

    private RunManifest.StageRun stage(String name) {
        return manifest().stage(name).orElseThrow(() ->
                new AssertionError("the run recorded no stage named " + name
                        + "; it named " + manifest().stages()));
    }

    private Score score() {
        return Workspace.open(workspaceDirectory).readScore().orElseThrow();
    }

    private ChromaTrace chromaTrace() {
        return Workspace.open(workspaceDirectory).readRunTraces().orElseThrow()
                .trace(ChromaTrace.STAGE, ChromaTrace.class)
                .orElseThrow(() -> new AssertionError("this run recorded no chroma trace"));
    }

    private ChordTrace chordTrace() {
        return Workspace.open(workspaceDirectory).readRunTraces().orElseThrow()
                .trace(ChordTrace.STAGE, ChordTrace.class)
                .orElseThrow(() -> new AssertionError("this run recorded no chord trace"));
    }

    private KeyTrace keyTrace() {
        return Workspace.open(workspaceDirectory).readRunTraces().orElseThrow()
                .trace(KeyTrace.STAGE, KeyTrace.class)
                .orElseThrow(() -> new AssertionError("this run recorded no key trace"));
    }

    private MelodyTrace melodyTrace() {
        return Workspace.open(workspaceDirectory).readRunTraces().orElseThrow()
                .trace(MelodyTrace.STAGE, MelodyTrace.class)
                .orElseThrow(() -> new AssertionError("this run recorded no melody trace"));
    }

    private BeatTrace beatTrace() {
        return Workspace.open(workspaceDirectory).readRunTraces().orElseThrow()
                .trace(BeatTrace.STAGE, BeatTrace.class)
                .orElseThrow(() -> new AssertionError("this run recorded no beat trace"));
    }

    /** Strips one kind of sidecar out of the cache, as an older build's would be. */
    private void deleteCacheEntries(String extension) throws IOException {
        try (var entries = Files.walk(workspaceDirectory.resolve("cache"))) {
            for (Path path : entries.filter(path ->
                    path.getFileName().toString().endsWith(extension)).toList()) {
                Files.delete(path);
            }
        }
    }

    private void analyze(String... options) {
        String[] arguments = new String[options.length + 2];
        arguments[0] = "analyze";
        arguments[1] = workspaceDirectory.toString();
        System.arraycopy(options, 0, arguments, 2, options.length);
        CliRunner.Result result = CliRunner.run(arguments);
        assertThat(result.exitCode()).as(result.all()).isZero();
    }

    @Test
    @DisplayName("names every stage of the run, and what the recording decoded to")
    void namesEveryStage() {
        analyze();

        RunManifest manifest = manifest();
        assertThat(manifest.schemaVersion()).isEqualTo(RunManifest.CURRENT_SCHEMA_VERSION);
        assertThat(manifest.musicWizardVersion()).isNotBlank();
        assertThat(manifest.startedAt()).isNotBlank();
        assertThat(manifest.finishedAt()).isNotBlank();
        assertThat(manifest.stages()).extracting(RunManifest.StageRun::stage)
                .contains("decode", "chroma", "beats", "chords", "key", "melody",
                        "separation", "lyrics", "lyric-alignment");
        assertThat(stage("decode").outcome()).isEqualTo(Outcome.COMPUTED);
        assertThat(stage("decode").facts())
                .containsEntry("read as", "mono at 22050 Hz")
                .containsKeys("format", "sample rate as stored", "channels as stored",
                        "duration as decoded");
        assertThat(stage("chords").outcome()).isEqualTo(Outcome.COMPUTED);
        // Off is a decision the page has to be able to show, not an omission.
        assertThat(stage("melody").outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(manifest.settings())
                .containsEntry("source", "audio")
                .containsEntry("melody", "not read");
    }

    @Test
    @DisplayName("says which options steered the run, and only those it acted on")
    void namesTheSettingsItActedOn() {
        analyze("--tempo", "90", "--time-signature", "3/4");

        assertThat(manifest().settings())
                .containsEntry("tempo forced to", "90.0 counted beats a minute")
                .containsEntry("meter forced to", "3/4")
                .doesNotContainKey("first downbeat forced to")
                .doesNotContainKey("lyrics language");
    }

    @Test
    @DisplayName("a run served the cached analysis reports the cache, not a fresh run")
    void aCachedRunSaysSo() {
        analyze();
        assertThat(stage("decode").outcome()).isEqualTo(Outcome.COMPUTED);

        analyze();

        // The stages under the key are replayed with the answer they produced,
        // so a cached run is as descriptive as the run that computed it -- and
        // says which it was.
        assertThat(stage("decode").outcome()).isEqualTo(Outcome.CACHED);
        assertThat(stage("decode").facts()).containsKey("format");
        assertThat(stage("beats").outcome()).isEqualTo(Outcome.CACHED);
        // A stage that did not run has no answer the cache could have held.
        assertThat(stage("melody").outcome()).isEqualTo(Outcome.SKIPPED);
        // Separation is not under the cache key: a cached run separates nothing
        // and must not claim the previous run's answer.
        assertThat(stage("separation").outcome()).isEqualTo(Outcome.SKIPPED);
    }

    @Test
    @DisplayName("a cached analysis from before the record says that rather than nothing")
    void aCacheEntryWithNoRecordIsNamed() throws IOException {
        analyze();
        // What every workspace analysed by an older build holds: the score
        // under the key, and nothing about the stages that made it.
        deleteCacheEntries(".stages.json");

        analyze();

        assertThat(stage("analysis").outcome()).isEqualTo(Outcome.CACHED);
        assertThat(stage("analysis").reason()).contains("no record of what its stages did");
        assertThat(manifest().stage("decode")).isEmpty();
    }

    @Test
    @DisplayName("the beat tracker writes down what it chose between")
    void theBeatTraceIsWritten() {
        analyze();

        BeatTrace trace = beatTrace();
        assertThat(trace.windows()).isNotEmpty();
        // Every window weighed a rate and one of them is the seed it took.
        assertThat(trace.windows()).allSatisfy(window -> {
            assertThat(window.candidates()).isNotEmpty();
            assertThat(window.candidates()).filteredOn(BeatTrace.Candidate::chosen)
                    .singleElement()
                    .extracting(BeatTrace.Candidate::beatsPerMinute)
                    .isEqualTo(window.seedPulse());
        });
        assertThat(trace.agreedPulse()).isPositive();
        assertThat(stage("beats").facts()).containsKey("pulse the windows agreed on");
    }

    @Test
    @DisplayName("the chroma front end writes down its tuning, its fit and its spans")
    void theChromaTraceIsWritten() {
        analyze();

        ChromaTrace trace = chromaTrace();
        assertThat(trace.fit()).isNotNull();
        assertThat(trace.fit().frames()).isPositive();
        assertThat(trace.fit().lowestNoteMidi()).isLessThan(trace.fit().highestNoteMidi());
        // One entry per chord span, each covering at least one beat-synchronous
        // span and each reading one per pitch class.
        assertThat(trace.spans()).hasSize(score().chords().size());
        assertThat(trace.spans()).allSatisfy(span -> {
            assertThat(span.toBeat()).isGreaterThan(span.fromBeat());
            assertThat(span.combined()).hasSize(12);
            assertThat(span.treble()).hasSize(12);
            assertThat(span.bass()).hasSize(12);
            // The residual is measured on this path, so an empty one would mean
            // the ablation never reached the record.
            assertThat(span.significance()).hasSize(12);
        });
        assertThat(stage("chroma").facts()).containsKey("tuning");
    }

    @Test
    @DisplayName("the chroma line the run keeps is the one with its spans on it")
    void theChromaTraceIsTheLastOneRecorded() {
        // The stage records once before the beats and again once there are
        // spans to summarise, and a run that kept the first would hold a trace
        // that describes a recording nothing was folded over.
        analyze();

        assertThat(chromaTrace().spans()).isNotEmpty();
        assertThat(manifest().stages().stream()
                .filter(entry -> entry.stage().equals("chroma")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the decoder writes down why each span carries its label")
    void theChordTraceIsWritten() {
        analyze();

        ChordTrace trace = chordTrace();
        assertThat(trace.spans()).hasSize(score().chords().size());
        assertThat(trace.spans()).allSatisfy(span -> {
            assertThat(span.toBeat()).isGreaterThan(span.fromBeat());
            assertThat(span.decoded()).isNotNull();
            assertThat(span.settledBy()).isNotBlank();
            // The residual is measured on this path and this fixture's roots
            // are in the fit, so a span on a root with no gate reading would
            // mean the ablation never reached the record.
            assertThat(span.gates()).hasSize(span.chord().equals("N.C.") ? 0 : 6);
        });
        assertThat(trace.roots()).isNotEmpty();
    }

    @Test
    @DisplayName("the key estimator writes down what each of its two decisions weighed")
    void theKeyTraceIsWritten() {
        analyze();

        KeyTrace trace = keyTrace();
        assertThat(trace.source()).isEqualTo(KeyTrace.FROM_CHORDS);
        assertThat(trace.candidates()).hasSize(24);
        assertThat(trace.tonic().winner())
                .isEqualTo(score().primaryKey().orElseThrow().displayName());
        assertThat(trace.signature().runnerUp()).isNotEqualTo(trace.tonic().winner());
        assertThat(trace.soundingSeconds()).isPositive();
    }

    @Test
    @DisplayName("what the stages weighed travels with the cached answer")
    void theTraceIsServedWithTheCachedAnalysis() {
        analyze();
        BeatTrace computed = beatTrace();
        ChromaTrace weighed = chromaTrace();
        ChordTrace decided = chordTrace();
        KeyTrace named = keyTrace();

        // A trace is a function of the cache key exactly as the score is, so a
        // run served the score has to be served the trace: without that, the
        // second analysis of an unchanged file renders a blank picture.
        analyze();

        assertThat(stage("beats").outcome()).isEqualTo(Outcome.CACHED);
        assertThat(beatTrace()).isEqualTo(computed);
        assertThat(chromaTrace()).isEqualTo(weighed);
        assertThat(chordTrace()).isEqualTo(decided);
        assertThat(keyTrace()).isEqualTo(named);
    }

    @Test
    @DisplayName("a cached analysis from before the trace leaves the page to say so")
    void aCacheEntryWithNoTraceIsNotInvented() throws IOException {
        analyze();
        deleteCacheEntries(".traces.json");

        analyze();

        assertThat(stage("beats").outcome()).isEqualTo(Outcome.CACHED);
        assertThat(Workspace.open(workspaceDirectory).readRunTraces().orElseThrow().traces())
                .doesNotContainKey("beats");
    }

    @Test
    @DisplayName("the melody stage writes down what it cut out of the signal it read")
    void theMelodyTraceIsWritten() throws IOException {
        configureSeparation("fake-cli-voice");

        analyze("--melody");

        MelodyTrace trace = melodyTrace();
        assertThat(trace.signal()).isEqualTo(MelodyTrace.SEPARATED_VOCAL);
        assertThat(trace.track().frames()).isPositive();
        assertThat(trace.tuning()).isNotNull();
        // The record describes the notes the score kept, one entry each, and
        // the runs account for every one of them.
        List<Note> notes = score().track(PartRole.LEAD_VOCAL).orElseThrow().notes();
        assertThat(trace.notes()).hasSameSizeAs(notes);
        assertThat(trace.notes()).extracting(MelodyTrace.Note::midiPitch)
                .isEqualTo(notes.stream().map(Note::midiPitch).toList());
        assertThat(trace.runs().stream().mapToInt(MelodyTrace.Run::notes).sum())
                .isEqualTo(trace.notes().size());
        assertThat(stage("melody").facts()).containsKey("voiced");
    }

    @Test
    @DisplayName("a run that was never asked for a melody records no melody trace")
    void aMelodyNeverAskedForRecordsNoTrace() {
        analyze();

        assertThat(stage("melody").outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(Workspace.open(workspaceDirectory).readRunTraces().orElseThrow()
                .trace(MelodyTrace.STAGE, MelodyTrace.class)).isEmpty();
    }

    @Test
    @DisplayName("names the separator that ran and the signal the melody was read from")
    void namesTheSeparatorAndTheSignal() throws IOException {
        configureSeparation("fake-cli-voice");

        analyze("--melody");

        assertThat(stage("separation").outcome()).isEqualTo(Outcome.COMPUTED);
        assertThat(stage("separation").facts()).containsEntry("provider", "fake-cli-voice");
        assertThat(stage("melody").facts())
                .containsEntry("read from", "the separated vocal");
    }

    @Test
    @DisplayName("names a separator that could not run, and that the melody heard the mix")
    void namesASeparatorThatFailed() throws IOException {
        configureSeparation("fake-cli-unavailable-separation");

        analyze("--melody");

        assertThat(stage("separation").outcome()).isEqualTo(Outcome.FAILED);
        assertThat(stage("separation").reason())
                .contains(FailingSeparationProvider.REASON);
        assertThat(stage("melody").facts()).containsEntry("read from", "the full mix");
    }

    @Test
    @DisplayName("says a separator was skipped when it was asked to skip one")
    void namesASkippedSeparation() throws IOException {
        configureSeparation("fake-cli-voice");

        analyze("--melody", "--skip-separation");

        assertThat(stage("separation").outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(stage("separation").reason()).isEqualTo("--skip-separation");
        assertThat(stage("melody").facts()).containsEntry("read from", "the full mix");
    }

    @Test
    @DisplayName("words kept from a previous analysis are still named as their own source")
    void keptWordsKeepTheirProvenance() throws IOException {
        // --force re-transcribes over carried words, so both the line saying
        // where the score's words came from and the line saying what the
        // recognizer did are written in one run. The recognizer's failure is
        // not a statement about words it never replaced.
        Path lrc = directory.resolve("band.lrc");
        Files.writeString(lrc, "[00:01.00]hello there\n[00:03.00]my old friend\n");
        analyze("--lyrics", lrc.toString(), "--lyrics-language", "en");
        assertThat(stage("lyrics").facts()).containsEntry("words from", "the file band.lrc");
        configureMl("asrProvider: no-such-asr");

        analyze("--force", "--lyrics-language", "en");

        assertThat(stage("lyrics").outcome()).isEqualTo(Outcome.COMPUTED);
        assertThat(stage("lyrics").facts())
                .containsEntry("words from", "the previous analysis of this workspace")
                .containsEntry("lines", "2");
        assertThat(stage("lyric-transcription").outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(stage("lyric-transcription").reason()).contains("no-such-asr");
    }

    @Test
    @DisplayName("a MIDI workspace records the path it was read by, and no decode")
    void aMidiWorkspaceRecordsItsOwnPath() {
        Path source = MidiFixtures.write(
                MidiFixtures.fourChordSong(), directory.resolve("four.mid"));
        workspaceDirectory = directory.resolve("four.mwz");
        assertThat(CliRunner.run("init", source.toString(), "-w",
                workspaceDirectory.toString()).exitCode()).isZero();

        analyze();

        assertThat(stage("read-midi").outcome()).isEqualTo(Outcome.COMPUTED);
        assertThat(stage("read-midi").facts()).containsKeys("tracks", "ticks per quarter");
        assertThat(manifest().stage("decode"))
                .as("a MIDI file is read symbolically and decodes nothing")
                .isEmpty();
        assertThat(manifest().settings()).containsEntry("source", "Standard MIDI File");
        // The file states the accidentals and the mode, so both halves of the
        // key are read rather than weighed (#554), and the trace says which of
        // the two paths named the key rather than leaving the page the same
        // absence a workspace analysed before #678 leaves.
        assertThat(keyTrace().source()).isEqualTo(KeyTrace.DECLARED);
        assertThat(keyTrace().candidates()).isEmpty();
        Key key = score().primaryKey().orElseThrow();
        assertThat(key.signatureConfidence()).contains(Confidence.CERTAIN);
        assertThat(key.tonicConfidence()).contains(Confidence.CERTAIN);
    }
}
