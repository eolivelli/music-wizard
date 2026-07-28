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

import dev.olivelli.musicwizard.core.model.Score;
import java.util.Objects;

/**
 * Why a score has no chord progression, worded once for every command that has
 * to say so.
 *
 * <p>This class exists because the same explanation was written three times and
 * was wrong in a different way each time. {@code render} first decided it by
 * sniffing the workspace's source file, which broke when the file was deleted
 * and lied when it was replaced. Its replacement read the score's track list as
 * a stand-in for provenance, which was wrong for a MIDI file holding only a
 * conductor track. And {@code analyze} carried a third wording of its own,
 * unconditionally naming #115 -- so on that same conductor-track file the two
 * commands gave different reasons for the same fact three lines apart, and the
 * {@code analyze} one was false: a file with no notes has no harmony for #115 to
 * name.
 *
 * <p>Two fixes at the layer the symptom was noticed is this project's most
 * frequent failure. A third edit in a third place was not the answer; removing
 * the choice was. Every command that explains an absent chord progression calls
 * this, and there is nowhere left for a fourth wording to appear.
 *
 * <p>What the answer may depend on is the <em>score</em>, and only the score.
 * Where the score came from is not knowable here and is not guessed at -- see
 * #120 -- and the two branches below hold whatever produced it, including an
 * audio analysis that separated stems and estimated no chords.
 */
final class MissingHarmony {

    private MissingHarmony() {
    }

    /**
     * The explanation, as a clause a caller supplies the subject for.
     *
     * <p>A score with notes is one named stage short of a chart, and the stage is
     * named. A score without them is not short of a stage: there is nothing in it
     * that harmony could be derived from.
     *
     * <p>Deliberately not "so there is nothing to engrave", which an earlier
     * draft said. That is a claim about the whole score from two of its ten
     * fields, and it stops being true the moment lyrics arrive under #9 -- a
     * chords-and-lyrics sheet is the project's strongest output and needs neither
     * a chord progression nor a note track to be worth printing.
     */
    static String explain(Score score) {
        Objects.requireNonNull(score, "score");
        int parts = score.tracks().size();
        return parts == 0
                ? "there are no parts in it either, so there is nothing for harmony"
                        + " to be derived from"
                : "it holds " + parts + " part(s), and naming the harmony a set of notes"
                        + " spells is not implemented yet (#115)";
    }
}
