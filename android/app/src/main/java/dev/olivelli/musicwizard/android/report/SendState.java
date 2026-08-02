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

import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * Sends that are queued or running: the name each was started under, to the
     * name its take goes by now.
     *
     * <p>Two names because the take can be renamed while its send runs, and the
     * worker reports under the one it captured. Keying by the started name is
     * what lets {@link #finishSend} find the send; the value is where the result
     * belongs. Both are needed: keying by the current name loses the send when
     * it is renamed, and keying only by the started name files the result under
     * a name that no longer means the same take.
     */
    private final Map<String, String> inFlight = new LinkedHashMap<>();

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

    /** By the name the take goes by now, not the one its send was started under. */
    public boolean isSending(String take) {
        return inFlight.containsValue(take);
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
        inFlight.put(take, take);
    }

    /**
     * Records how a send ended.
     *
     * @param detail the line for the screen — the comment's URL, or why there
     *               is none
     */
    /**
     * Records how a send ended.
     *
     * @param startedAs the name the take had when {@link #beginSend} was called,
     *                  which is the only name the worker knows; the result is
     *                  filed against whatever that take is called now
     * @param detail    the line for the screen — the comment's URL, or why there
     *                  is none
     */
    public void finishSend(String startedAs, boolean worked, String detail) {
        String take = inFlight.remove(startedAs);
        if (take == null) {
            // Deleted while the send ran. The comment is on GitHub either way;
            // what is dropped is bookkeeping about a take that is not there.
            return;
        }
        if (worked) {
            store.setDraft(take, "");
            store.setFiled(take, true);
        }
        lastTake = take;
        lastDetail = detail;
    }

    /**
     * Drops everything known about a take, because it no longer exists.
     *
     * <p>Called when a recording is deleted, and here rather than on the store
     * because the in-memory half has to go too: these are keyed by the take's
     * name and a name is reusable, so a later take renamed onto a deleted one's
     * would otherwise inherit its unsent comment — and, if a send were still
     * running, be marked filed by it.
     */
    public void forget(String take) {
        // By value: the send may have been started under an earlier name.
        inFlight.values().remove(take);
        store.setDraft(take, "");
        store.setFiled(take, false);
        if (take.equals(lastTake)) {
            lastTake = null;
            lastDetail = null;
        }
    }

    /**
     * Carries everything known about a take to its new name, a running send
     * included.
     *
     * <p>The send follows the take rather than the name: it stays keyed by the
     * name it was started under, so the worker can still find it, and its result
     * lands on the new name. Neither of the simpler answers works. Dropping the
     * send leaves the renamed take reading as never sent, with the text already
     * on GitHub still in the box and Send live — a duplicate one tap away.
     * Re-keying it by the new name loses it, because the worker will report
     * under the old one and nothing would ever clear the new.
     */
    public void moved(String from, String to) {
        if (from.equals(to)) {
            return;
        }
        String draft = store.draft(from);
        boolean wasFiled = store.isFiled(from);
        String detail = detailFor(from);

        // The destination is written before the source is cleared. These are
        // separate writes, so a process killed between them leaves either an
        // unused key under the old name — which the next rename or delete of
        // that name clears — or, the other way round, the typed sentence gone.
        // Only one of those is acceptable.
        forget(to);
        store.setDraft(to, draft);
        store.setFiled(to, wasFiled);
        if (detail != null) {
            lastTake = to;
            lastDetail = detail;
        }

        store.setDraft(from, "");
        store.setFiled(from, false);
        if (from.equals(lastTake)) {
            lastTake = null;
            lastDetail = null;
        }
        inFlight.replaceAll((startedAs, now) -> now.equals(from) ? to : now);
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
