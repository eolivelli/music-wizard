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
 * Where this package says what it is doing.
 *
 * <p>A fetch that fails on a phone reports one sentence, and the sentence is a
 * verdict rather than evidence — it cannot distinguish a media host refusing an
 * address from a build that can no longer read what YouTube serves. These lines
 * are the evidence, and they exist to be read by someone who is not holding the
 * phone.
 *
 * <p>So the rule for what goes in one: <strong>name the host, the format and
 * the status; never the URL.</strong> A media URL carries the phone's public
 * address and the session's signatures, and this text is written to be sent to
 * somebody. {@code ImportLog} scrubs what arrives as a backstop, but a line
 * that never held a secret cannot leak one through a gap in a pattern.
 */
public interface Trace {

    /** Says nothing, for every caller that is not diagnosing a phone. */
    Trace NONE = message -> { };

    void line(String message);
}
