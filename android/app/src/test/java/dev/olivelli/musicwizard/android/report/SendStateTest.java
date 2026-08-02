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
 * <p>This is the state a screen used to keep for itself, and every way it went
 * wrong cost a duplicate asset and a duplicate comment on the same take. The
 * ways were all variations on one thing — a copy of a fact that had changed
 * since — so what is tested here is that the answers track the record rather
 * than a moment.
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
        state.beginSend(TAKE);

        // Nobody asks anything while it runs; this is the paused screen.
        state.finishSend(TAKE, true, "Sent.\nhttps://github.com/o/r/issues/7#c1");

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
        state.beginSend(TAKE);
        state.finishSend(TAKE, true, detail);

        assertEquals(detail, state.detailFor(TAKE));
        assertNull("another take's screen must not show this one's result",
                state.detailFor(OTHER));
    }

    /** A failed send reopens the take, keeps the draft, and says why. */
    @Test
    public void aFailedSendLeavesTheTakeSendableAndTheDraftIntact() {
        drafts.put(TAKE, "G C D");
        state.beginSend(TAKE);
        state.finishSend(TAKE, false, "Not sent.\nBad credentials");

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
        state.beginSend(TAKE);
        state.finishSend(TAKE, true, "Sent.\nhttps://…");

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
        state.beginSend(TAKE);
        state.finishSend(TAKE, true, "Sent.\nhttps://…");

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
        state.finishSend(TAKE, false, "first");
        state.finishSend(OTHER, false, "second");

        assertNull(state.detailFor(TAKE));
        assertEquals("second", state.detailFor(OTHER));
    }
}
