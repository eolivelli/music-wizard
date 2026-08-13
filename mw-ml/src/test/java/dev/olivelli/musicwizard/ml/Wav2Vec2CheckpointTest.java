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

package dev.olivelli.musicwizard.ml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.olivelli.musicwizard.core.model.LyricWord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-language half of the provider: which alphabet a word is spelled
 * against, and which languages a machine can actually align.
 */
@DisplayName("alignment checkpoints")
class Wav2Vec2CheckpointTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("each alphabet names its own blank and separator")
    void indicesComeFromTheAlphabet() {
        // Not shared constants: the Italian export puts the separator at 4
        // like the English one but spells two more punctuation entries before
        // its letters, so a hardcoded letter index would silently mis-spell
        // every word.
        assertThat(Wav2Vec2Models.ENGLISH.blank()).isZero();
        assertThat(Wav2Vec2Models.ITALIAN.blank()).isZero();
        assertThat(Wav2Vec2Models.ENGLISH.separator())
                .isEqualTo(Wav2Vec2Models.ITALIAN.separator());
        assertThat(Wav2Vec2Models.ENGLISH.vocabulary()[7]).isNotEqualTo(
                Wav2Vec2Models.ITALIAN.vocabulary()[7]);
    }

    @Test
    @DisplayName("the alphabet decides the case and whether accents survive")
    void normalisationFollowsTheAlphabet() {
        assertThat(Wav2Vec2Models.ENGLISH.isLowerCase()).isFalse();
        assertThat(Wav2Vec2Models.ENGLISH.spellsAccents()).isFalse();
        assertThat(Wav2Vec2Models.ITALIAN.isLowerCase()).isTrue();
        assertThat(Wav2Vec2Models.ITALIAN.spellsAccents()).isTrue();
    }

    /** Frames that make one vocabulary index near-certain, in order. */
    private static float[][] plan(String[] vocabulary, int[]... runs) {
        int frames = 0;
        for (int[] run : runs) {
            frames += run[1];
        }
        float[][] logProbs = new float[frames][vocabulary.length];
        float low = (float) Math.log(1e-4);
        float high = (float) Math.log(0.999);
        int f = 0;
        for (int[] run : runs) {
            for (int i = 0; i < run[1]; i++) {
                java.util.Arrays.fill(logProbs[f], low);
                logProbs[f][run[0]] = high;
                f++;
            }
        }
        return logProbs;
    }

    private static int indexOf(Wav2Vec2Models.Checkpoint checkpoint, char c) {
        return checkpoint.indexOf(String.valueOf(c));
    }

    @Test
    @DisplayName("an accented word is spelled with its accent where the model has one")
    void italianKeepsItsAccents() {
        // The whole point of the second alphabet: folded to "e", the vowel the
        // aligner is listening for is not the one being sung, and the word's
        // onset lands wherever the trellis can least badly put it.
        var checkpoint = Wav2Vec2Models.ITALIAN;
        int accented = indexOf(checkpoint, 'è');
        float[][] logProbs = plan(checkpoint.vocabulary(),
                new int[] {checkpoint.blank(), 5},
                new int[] {indexOf(checkpoint, 'c'), 5},
                new int[] {indexOf(checkpoint, '\''), 3},
                new int[] {accented, 10},
                new int[] {checkpoint.blank(), 5});
        var provider = new Wav2Vec2AlignmentProvider(
                null, checkpoint, null, (path, samples) -> logProbs);

        List<LyricWord> placed = provider.align(new float[16_000], 16_000, "it",
                List.of("c'è"));

        assertThat(placed).hasSize(1);
        // The accented run is the evidence for the last letter, so it is the
        // word's END that moves: spelled with its accent the word runs
        // through those frames, and folded to "e" it stops before them at a
        // confidence the trellis is not entitled to. The start is the same
        // either way, which is why asserting it would prove nothing.
        assertThat(placed.get(0).endSeconds()).isGreaterThan(0.4);
        assertThat(placed.get(0).confidence().value()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("a character the alphabet lacks folds rather than vanishing")
    void unknownCharactersFoldPerCharacter() {
        // The fold is per character, not a property of the alphabet: Italian
        // spells è and does not spell ô, and dropping the ô outright deletes
        // the nucleus of the syllable being placed. The typographic
        // apostrophe is the same case -- lyric files are full of it and no
        // vocabulary has it.
        var it = Wav2Vec2Models.ITALIAN;
        assertThat(Wav2Vec2AlignmentProvider.spelling("hôtel", it)).isEqualTo("hotel");
        assertThat(Wav2Vec2AlignmentProvider.spelling("sî", it)).isEqualTo("si");
        assertThat(Wav2Vec2AlignmentProvider.spelling("città", it)).isEqualTo("città");
        assertThat(Wav2Vec2AlignmentProvider.spelling("un\u2019altra", it))
                .isEqualTo("un'altra");
        // English spells no accents at all, so everything folds, as before.
        assertThat(Wav2Vec2AlignmentProvider.spelling("café", Wav2Vec2Models.ENGLISH))
                .isEqualTo("CAFE");
    }

    @Test
    @DisplayName("the transcribed Italian alphabet is pinned to the export's own")
    void italianAlphabetIsPinned() {
        // The javadoc makes this table the authority over the export's
        // vocab.json, so a stray edit re-indexes every letter silently. These
        // are read from the export: 44 entries, è at 35, the two punctuation
        // entries the English alphabet does not have at 5 and 6.
        var it = Wav2Vec2Models.ITALIAN;
        assertThat(it.vocabulary()).hasSize(44);
        assertThat(it.indexOf("è")).isEqualTo(35);
        assertThat(it.indexOf("'")).isEqualTo(5);
        assertThat(it.indexOf("-")).isEqualTo(6);
        assertThat(it.indexOf("a")).isEqualTo(7);
        assertThat(it.indexOf("z")).isEqualTo(32);
    }

    @Test
    @DisplayName("a language is offered only when this machine has its model")
    void languagesFollowWhatIsPresent() throws java.io.IOException {
        // English has a published export, so it is always offered. Italian has
        // none (#388) and appears exactly when the user's directory holds it,
        // because a language listed but unrunnable makes analyze fail a line
        // it could have kept.
        var withoutModels = new Wav2Vec2AlignmentProvider(null, null, null);
        assertThat(withoutModels.languages()).containsExactly("en");

        Path italian = directory.resolve("it");
        Files.createDirectories(italian);
        Files.createFile(italian.resolve("model.onnx"));
        var withItalian = new Wav2Vec2AlignmentProvider(null, null, directory.toString());
        assertThat(withItalian.languages()).containsExactly("en", "it");

        // A directory holding some other language does not add this one.
        var elsewhere = new Wav2Vec2AlignmentProvider(
                null, null, directory.resolve("nowhere").toString());
        assertThat(elsewhere.languages()).containsExactly("en");
    }
}
