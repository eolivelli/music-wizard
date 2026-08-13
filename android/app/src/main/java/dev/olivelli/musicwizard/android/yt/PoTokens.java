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

package dev.olivelli.musicwizard.android.yt;

/**
 * Where a proof-of-origin token would come from, if one were needed.
 *
 * <p>Nothing implements this and nothing calls it, which is the point. Measured
 * on real videos, the client {@link InnerTube} uses returns media URLs with no
 * {@code pot} parameter and YouTube serves them without one — so the app needs
 * neither a WebView running Google's BotGuard nor the assets and threading that
 * come with it.
 *
 * <p>That will not hold forever: yt-dlp already records selective enforcement
 * observed on this client. This interface exists so that when it arrives, the
 * change is one implementation and two call sites rather than a refactor of
 * everything that touches a URL — and so that whoever meets
 * {@link ExtractionException.Reason#SABR_ONLY} for the first time finds this
 * note instead of starting from nothing.
 *
 * <p>The obvious implementation is a WebView loading Google's own BotGuard
 * script, which NewPipe ships and which is proven on Android. Read the
 * <em>approach</em> only: NewPipe is GPL-3.0 and its code cannot be borrowed
 * into this repository. Two things to know before starting — a web token is
 * bound to the video id, so it is one run per fetch and not one per session; and
 * an outdated system WebView has been observed producing an invalid token rather
 * than an error, which at {@code minSdk 26} is a real device to expect.
 */
public interface PoTokens {

    /** Never asked for one today. */
    PoTokens NONE = videoId -> null;

    /** A token bound to this video, or null when none can be had. */
    String forVideo(String videoId);
}
