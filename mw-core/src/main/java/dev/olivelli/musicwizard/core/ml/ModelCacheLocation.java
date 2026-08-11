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

package dev.olivelli.musicwizard.core.ml;

import java.nio.file.Path;

/**
 * Where downloaded models live when configuration does not say.
 *
 * <p>Here rather than in {@code mw-ml} because two modules need the answer and
 * only one of them compiles against {@code mw-ml}: the cache itself resolves
 * models here, and {@code doctor} reports the directory whether or not any
 * provider is on the classpath. Two statements of a default is the two-readers
 * defect waiting to happen.
 */
public final class ModelCacheLocation {

    private ModelCacheLocation() {
    }

    /**
     * The directory a configuration names, or the default when it names none.
     *
     * <p>Null and blank are both "unset": {@code Path.of("")} is the working
     * directory, which no one has ever meant by an empty config value. One
     * statement, because {@code doctor} reports this directory and the cache
     * resolves models in it, and the two must never disagree.
     */
    public static Path directoryFor(String configured) {
        return configured == null || configured.isBlank()
                ? defaultDirectory()
                : Path.of(configured.strip());
    }

    /**
     * {@code $XDG_CACHE_HOME/music-wizard/models} where that is set, else
     * {@code ~/.cache/music-wizard/models}.
     *
     * <p>Under the user cache rather than beside any workspace, because a model
     * belongs to no recording and re-downloading hundreds of megabytes per song
     * would make the design absurd.
     */
    public static Path defaultDirectory() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("music-wizard").resolve("models");
    }
}
