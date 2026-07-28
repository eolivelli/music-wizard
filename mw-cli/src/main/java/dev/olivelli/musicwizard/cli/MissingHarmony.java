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
 * Where the score came from is not knowable here and is not guessed at; see
 * #120.
 *
 * <p>That has a cost, and it is stated rather than glossed. The branch for a
 * score <em>with</em> note tracks names a cause, and the cause is right only
 * because every such score is a MIDI import today. When #8 lands, an audio
 * analysis with separated stems will take that branch, and the reason it has no
 * chords will be that the estimator ran over the mix and found none -- not that
 * symbolic harmony is unimplemented. An earlier draft of this paragraph claimed
 * the opposite, that both branches hold "including an audio analysis that
 * separated stems and estimated no chords", which is exactly the case that
 * falsifies it. Tracked as #125, due with #8, and answerable properly only by
 * #120.
 */
final class MissingHarmony {

    private MissingHarmony() {
    }

    /**
     * The explanation, as a clause a caller prefixes with a comma.
     *
     * <p>A score with note tracks is one named stage short of a chart, and the
     * stage is named. A score without them gets a statement of that fact and
     * nothing more, and the restraint is the point -- this sentence has now been
     * wrong twice by reaching past what it can know:
     *
     * <ul>
     *   <li>"so there is nothing to engrave" was a claim about the whole score
     *       from two of its ten fields, and #9 falsifies it: a chords-and-lyrics
     *       sheet is this project's strongest output and needs neither a chord
     *       progression nor a note track to be worth printing.
     *   <li>"so there is nothing for harmony to be derived from" was a claim
     *       about mechanism, true on the MIDI path and false on the audio one,
     *       where chords are estimated from the mix and never from note tracks.
     *       On a recording the estimator had genuinely run and found nothing,
     *       and this told the user it had never had anything to run on -- while
     *       {@code analyze} three lines earlier reported the spans it looked for.
     *       It also pointed an audio user at a note-transcription issue that has
     *       no bearing on why the chord estimator returned nothing.
     * </ul>
     *
     * <p>Both were introduced by fixes for the sentence being too narrow, which
     * is the standing lesson: what keeps this line safe on two paths that share
     * nothing but a {@link Score} is describing the score rather than explaining
     * it.
     *
     * <p>The {@code parts > 0} branch does still explain, and is therefore the
     * one that will need this applied to it -- see the note on the class. It is
     * not fixed here because the correct answer for that branch is to know what
     * produced the score (#120) rather than to find a fourth wording that guesses
     * better.
     */
    static String explain(Score score) {
        Objects.requireNonNull(score, "score");
        int parts = score.tracks().size();
        return parts == 0
                ? "and no parts either"
                : "though it holds " + parts + " part(s); naming the harmony a set of"
                        + " notes spells is not implemented yet (#115)";
    }
}
