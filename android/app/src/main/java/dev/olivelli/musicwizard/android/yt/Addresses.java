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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Resolving an address the media host hands back, without ever repeating it.
 *
 * <p>One place for the rule both roads need: a redirect target or a playlist
 * entry carries the same signatures a media URL does, so a failure names the
 * host at most (#857). {@code URI.resolve} throws an unchecked exception on a
 * malformed reference, which is why the catch below is wider than it looks.
 */
final class Addresses {

    private Addresses() {
    }

    /** {@code reference} against {@code base}, required to be https. */
    static String resolveHttps(String base, String reference) throws IOException {
        URI resolved;
        try {
            resolved = new URI(base).resolve(reference);
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            throw new IOException("the server named an unreadable address");
        }
        if (!"https".equalsIgnoreCase(resolved.getScheme())) {
            throw new IOException("refusing an address that is not https, at "
                    + hostOf(resolved.toString()));
        }
        return resolved.toString();
    }

    /** Just the host, which is the part worth reporting and the part that is safe. */
    static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "?" : host;
        } catch (URISyntaxException | IllegalArgumentException unreadable) {
            return "?";
        }
    }
}
