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

package dev.olivelli.musicwizard.android.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * When Send may be pressed, and what the screen is told.
 *
 * <p>What is checked is that the answers track the record rather than a
 * moment: see the class's own javadoc for why that is the whole point of it.
 */
public class SendStateTest {

    private static final String TAKE = "2026-08-02_18-00-00";
    private static final String OTHER = "another-take";

    /** The durable half, in a map. */
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, Boolean> filed = new HashMap<>();

    private SendState state;

    @Before
    public void setUp() {
        state = new SendState(new SendState.Store() {
            @Override
            public String draft(String take) {
                return drafts.getOrDefault(take, "");
            }

            @Override
            public void setDraft(String take, String text) {
                if (text == null || text.trim().isEmpty()) {
                    drafts.remove(take);
                } else {
                    drafts.put(take, text);
                }
            }

            @Override
            public boolean isFiled(String take) {
                return filed.getOrDefault(take, false);
            }

            @Override
            public void setFiled(String take, boolean value) {
                if (value) {
                    filed.put(take, true);
                } else {
                    filed.remove(take);
                }
            }
        });
    }

    /** Nothing sent, nothing filed: Send is live and there is nothing to report. */
    @Test
    public void aFreshTakeCanBeSent() {
        assertTrue(state.canSend(TAKE));
        assertFalse(state.isSending(TAKE));
        assertFalse(state.isFiled(TAKE));
        assertNull(state.detailFor(TAKE));
    }

    /** While a send runs, that take is closed and every other one is not. */
    @Test
    public void aSendInFlightClosesOnlyItsOwnTake() {
        state.beginSend(TAKE);

        assertTrue(state.isSending(TAKE));
        assertFalse(state.canSend(TAKE));
        assertTrue(state.canSend(OTHER));
    }

    /**
     * A send that lands while no screen is looking still closes the take.
     *
     * <p>The defect this is written for: the screen cached "filed" when it was
     * created and never asked again, so a send finishing behind the home screen
     * — or behind this screen's own token screen — left Send enabled when it
     * came back, and one tap posted the same take a second time.
     */
    @Test
    public void aSendThatLandsWhileNoScreenIsLookingStillClosesTheTake() {
        drafts.put(TAKE, "G C D");
        // The screen drew once before the send — the read the old code kept.
        assertTrue(state.canSend(TAKE));
        SendState.Send send = state.beginSend(TAKE);

        // Nobody asks anything while it runs; this is the paused screen.
        state.finishSend(send, true, "Sent.\nhttps://github.com/o/r/issues/7#c1");

        assertFalse("Send must not come back live on a take just filed",
                state.canSend(TAKE));
        assertTrue(state.isFiled(TAKE));
        assertFalse(state.isSending(TAKE));
        assertNull("a filed comment is not a draft", drafts.get(TAKE));
    }

    /**
     * The comment's URL outlives the moment it arrives.
     *
     * <p>It is the only proof the take reached the repository, and a screen
     * timeout must not be what loses it.
     */
    @Test
    public void whereItLandedIsStillThereOnTheNextLook() {
        String detail = "Sent.\nhttps://github.com/o/r/issues/7#c1";
        SendState.Send send = state.beginSend(TAKE);
        state.finishSend(send, true, detail);

        assertEquals(detail, state.detailFor(TAKE));
        assertNull("another take's screen must not show this one's result",
                state.detailFor(OTHER));
    }

    /** A failed send reopens the take, keeps the draft, and says why. */
    @Test
    public void aFailedSendLeavesTheTakeSendableAndTheDraftIntact() {
        drafts.put(TAKE, "G C D");
        SendState.Send send = state.beginSend(TAKE);
        state.finishSend(send, false, "Not sent.\nBad credentials");

        assertTrue(state.canSend(TAKE));
        assertFalse(state.isFiled(TAKE));
        assertEquals("G C D", drafts.get(TAKE));
        assertEquals("Not sent.\nBad credentials", state.detailFor(TAKE));
    }

    /**
     * Editing reopens the take, and an ordinary keystroke writes nothing.
     *
     * <p>The return value is what the screen redraws on. If it were true on
     * every keystroke the store would be written on every keystroke.
     */
    @Test
    public void editingReopensTheTakeAndOnlyThenReportsAChange() {
        SendState.Send send = state.beginSend(TAKE);
        state.finishSend(send, true, "Sent.\nhttps://…");

        assertTrue("the first edit after a send changes something",
                state.edited(TAKE));
        assertTrue(state.canSend(TAKE));
        assertFalse(state.isFiled(TAKE));
        assertNull("the old result does not describe the new comment",
                state.detailFor(TAKE));

        assertFalse("an ordinary keystroke must not touch the store",
                state.edited(TAKE));
    }

    /** A filed comment is not a draft, so leaving the screen must not re-save it. */
    @Test
    public void aFiledCommentIsNotKeptAsADraft() {
        SendState.Send send = state.beginSend(TAKE);
        state.finishSend(send, true, "Sent.\nhttps://…");

        // What onPause does with whatever is still in the field.
        state.keepDraft(TAKE, "G C D");
        assertNull(drafts.get(TAKE));

        // Once it is edited it is a draft again.
        state.edited(TAKE);
        state.keepDraft(TAKE, "G C D, and a bridge");
        assertEquals("G C D, and a bridge", drafts.get(TAKE));
    }

    /** One result is kept, and it is the most recent one. */
    @Test
    public void onlyTheLastResultIsKept() {
        state.finishSend(state.beginSend(TAKE), false, "first");
        state.finishSend(state.beginSend(OTHER), false, "second");

        assertNull(state.detailFor(TAKE));
        assertEquals("second", state.detailFor(OTHER));
    }

    /** A deleted take takes its draft, its mark and its result with it. */
    @Test
    public void forgettingATakeLeavesNothingForTheNextTakeOfThatName() {
        drafts.put(TAKE, "G C D");
        SendState.Send send = state.beginSend(TAKE);
        state.finishSend(send, true, "Sent.\nhttps://…");

        state.forget(TAKE);

        assertFalse(state.isFiled(TAKE));
        assertNull(drafts.get(TAKE));
        assertNull(state.detailFor(TAKE));
        assertTrue("a later take renamed onto this name starts clean",
                state.canSend(TAKE));
    }

    /** A rename carries the unsent comment, the mark and the result across. */
    @Test
    public void renamingCarriesEverythingToTheNewName() {
        drafts.put(TAKE, "G C D");
        SendState.Send send = state.beginSend(TAKE);
        state.finishSend(send, false, "Not sent.\nBad credentials");

        state.moved(TAKE, OTHER);

        assertEquals("G C D", drafts.get(OTHER));
        assertEquals("Not sent.\nBad credentials", state.detailFor(OTHER));
        assertNull(drafts.get(TAKE));
        assertNull(state.detailFor(TAKE));
    }

    /** And the destination's own leftovers go, since that name is being taken over. */
    @Test
    public void renamingOverwritesWhateverWasUnderTheNewName() {
        drafts.put(OTHER, "an older take's comment");
        filed.put(OTHER, true);

        state.moved(TAKE, OTHER);

        assertNull(drafts.get(OTHER));
        assertFalse(state.isFiled(OTHER));
    }

    /**
     * A send follows its take through a rename, and lands on the new name.
     *
     * <p>The worker only ever knows the name the send was started under. If
     * that is all this tracked, the renamed take would read as never sent —
     * with the text already on GitHub still in its box and Send live, which is
     * a duplicate one tap away.
     */
    @Test
    public void aSendFollowsItsTakeThroughARename() {
        drafts.put(TAKE, "G C D");
        SendState.Send send = state.beginSend(TAKE);
        state.moved(TAKE, OTHER);

        assertTrue("the take is still sending, under its new name",
                state.isSending(OTHER));
        assertFalse(state.canSend(OTHER));
        assertFalse("and not under the old one", state.isSending(TAKE));

        state.finishSend(send, true, "Sent.\nhttps://…");

        assertTrue("the result belongs to the take, which is now called this",
                state.isFiled(OTHER));
        assertEquals("Sent.\nhttps://…", state.detailFor(OTHER));
        assertFalse("Send must not come back live on what was just sent",
                state.canSend(OTHER));
        assertNull("and the sent text is not left in the box", drafts.get(OTHER));
        assertFalse(state.isFiled(TAKE));
        assertFalse("nothing is left reading as in flight", state.isSending(OTHER));
    }

    /** Through two renames as well: each one moves the same send along. */
    @Test
    public void aSendFollowsItsTakeThroughASecondRename() {
        SendState.Send send = state.beginSend(TAKE);
        state.moved(TAKE, OTHER);
        state.moved(OTHER, "third-name");

        assertTrue(state.isSending("third-name"));

        state.finishSend(send, true, "Sent.\nhttps://…");

        assertTrue(state.isFiled("third-name"));
        assertFalse(state.isSending("third-name"));
    }

    /**
     * Reusing the freed name for a second send does not lose the first.
     *
     * <p>A rename frees the old name, and the library refuses only names that
     * are *taken*, so a different take can legally be renamed onto it and sent.
     * While a send was identified by the name it started under, that second
     * send replaced the first: one take ended up on GitHub reading as never
     * sent, and another marked filed by someone else's upload.
     */
    @Test
    public void reusingAFreedNameDoesNotLoseTheSendStillRunningUnderIt() {
        SendState.Send first = state.beginSend(TAKE);
        state.moved(TAKE, OTHER);

        // TAKE is free again; a different recording is renamed onto it and sent.
        SendState.Send second = state.beginSend(TAKE);
        assertTrue("the first send is still running, as take-B",
                state.isSending(OTHER));
        assertTrue(state.isSending(TAKE));

        assertEquals(OTHER, state.finishSend(first, true, "first"));
        assertTrue("the renamed take is filed by its own send", state.isFiled(OTHER));
        assertFalse("and not by the other one", state.isFiled(TAKE));
        assertTrue("whose send is still running", state.isSending(TAKE));

        assertEquals(TAKE, state.finishSend(second, true, "second"));
        assertTrue(state.isFiled(TAKE));
        assertFalse(state.isSending(TAKE));
    }

    /**
     * The name to draw a result on is the take's name now, not the started one.
     *
     * <p>The screen matches what comes back against the take it is showing. Given
     * the started name it would refuse to redraw the renamed take — leaving it on
     * "Compressing and uploading…" with no way back, since the disabled field
     * cannot fire the watcher — or, worse, match a different take that has since
     * been renamed onto the freed name and wipe its unsent comment.
     */
    @Test
    public void finishSendAnswersWithTheNameToDrawOn() {
        SendState.Send send = state.beginSend(TAKE);
        state.moved(TAKE, OTHER);

        assertEquals(OTHER, state.finishSend(send, true, "Sent.\nhttps://…"));
    }

    /** A deleted take has no name to draw on, and says so. */
    @Test
    public void finishSendAnswersWithNothingWhenTheTakeIsGone() {
        SendState.Send send = state.beginSend(TAKE);
        state.forget(TAKE);

        assertNull(state.finishSend(send, true, "Sent.\nhttps://…"));
    }

    /** Deleting a take that was renamed mid-send still drops its send. */
    @Test
    public void deletingATakeRenamedMidSendStillDropsIt() {
        SendState.Send send = state.beginSend(TAKE);
        state.moved(TAKE, OTHER);
        state.forget(OTHER);

        assertNull(state.finishSend(send, true, "Sent.\nhttps://…"));
        assertFalse(state.isFiled(OTHER));
        assertFalse(state.isFiled(TAKE));
    }

    /** The same, for a take deleted mid-send. */
    @Test
    public void aSendWhoseTakeWasDeletedUnderItFilesNothing() {
        SendState.Send send = state.beginSend(TAKE);
        state.forget(TAKE);

        state.finishSend(send, true, "Sent.\nhttps://…");

        assertFalse(state.isFiled(TAKE));
        assertNull(state.detailFor(TAKE));
        assertTrue(state.canSend(TAKE));
    }
}
