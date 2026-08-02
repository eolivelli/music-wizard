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

import java.util.HashSet;
import java.util.Set;

/**
 * What is known about sending each take: in flight, filed, and how the last one
 * went.
 *
 * <p>None of it belongs to a screen. A send outlives the screen that started
 * it, so a screen that keeps its own copy of any of this is a screen that can
 * be wrong — and the way it goes wrong is that Send comes back enabled on a
 * take that has just been filed, which costs a duplicate asset and a duplicate
 * comment. So a screen asks this object every time it draws and remembers
 * nothing.
 *
 * <p>Outside the Activity for the second reason too: here it can be tested. The
 * durable half is behind {@link Store} so that the test does not need
 * {@code SharedPreferences} and this class does not need a {@code Context}.
 *
 * <p>Main thread only, like {@code AnalysisJobs}: the worker reaches it by
 * posting. Nothing here is synchronized because there is no second thread.
 */
public final class SendState {

    /**
     * The half that has to survive the process.
     *
     * <p>Whether a take's comment is already filed, and the comment someone
     * typed and has not sent. Both are keyed by the take's name.
     */
    public interface Store {

        String draft(String take);

        void setDraft(String take, String text);

        boolean isFiled(String take);

        void setFiled(String take, boolean filed);
    }

    private final Store store;
    private final Set<String> inFlight = new HashSet<>();

    /**
     * How the most recent send ended, and which take it was.
     *
     * <p>One, not a map: the screen that wants it is always the one for the take
     * just sent, and the line holds a URL, so keeping every send ever made would
     * be a leak with a friendlier name. Same reasoning as {@code AnalysisJobs}.
     */
    private String lastTake;
    private String lastDetail;

    public SendState(Store store) {
        this.store = store;
    }

    public boolean isSending(String take) {
        return inFlight.contains(take);
    }

    /**
     * Whether what is in the box has already been sent.
     *
     * <p>Read through to the store on every call rather than cached. A cached
     * copy is stale the moment a send lands while the screen is paused — behind
     * the home screen, or on the token screen — and the screen coming back then
     * offers to send the same comment again.
     */
    public boolean isFiled(String take) {
        return store.isFiled(take);
    }

    /** Whether Send should be live: nothing running, and something new to say. */
    public boolean canSend(String take) {
        return !isSending(take) && !isFiled(take);
    }

    /** What the last send of this take had to say, or null if there is nothing. */
    public String detailFor(String take) {
        return take.equals(lastTake) ? lastDetail : null;
    }

    public String draft(String take) {
        return store.draft(take);
    }

    /** Keeps an unsent comment; a filed one is not a draft and is not kept. */
    public void keepDraft(String take, String text) {
        if (!isFiled(take)) {
            store.setDraft(take, text);
        }
    }

    /**
     * Marks a send as started.
     *
     * <p>Call this last, immediately before handing the work to the executor:
     * anything that throws between here and there would leave the take marked
     * for the life of the process, with Send disabled on every future visit.
     */
    public void beginSend(String take) {
        inFlight.add(take);
    }

    /**
     * Records how a send ended.
     *
     * @param detail the line for the screen — the comment's URL, or why there
     *               is none
     */
    public void finishSend(String take, boolean worked, String detail) {
        // First, and whether or not a screen is left to tell: this is what lets
        // the take be sent again.
        inFlight.remove(take);
        if (worked) {
            store.setDraft(take, "");
            store.setFiled(take, true);
        }
        lastTake = take;
        lastDetail = detail;
    }

    /**
     * The comment was edited, so what was last sent is not what is in the box.
     *
     * @return true when something changed and the screen should redraw; false
     *         on the ordinary keystroke, which must not write to the store
     */
    public boolean edited(String take) {
        boolean changed = false;
        if (store.isFiled(take)) {
            store.setFiled(take, false);
            changed = true;
        }
        if (take.equals(lastTake)) {
            lastTake = null;
            lastDetail = null;
            changed = true;
        }
        return changed;
    }
}
