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

/**
 * A provider exists but the model it runs cannot be had.
 *
 * <p>Thrown for the reasons a download-on-first-use design has: the model is
 * absent and the configuration says offline, the download failed, or what
 * arrived does not match its checksum. The message says which, names the file,
 * and — when offline is the reason — says what turning offline off would fetch,
 * because "could not load model" with none of that is the error message this
 * project's LilyPond discovery was written to avoid.
 *
 * <p>Callers treat it the way they treat an absent LilyPond binary: report and
 * continue without the stage, never fail the run. A checked exception would say
 * that at the signature, but every caller sits behind an orchestration layer
 * that already catches {@link RuntimeException} per stage, and a checked type
 * on an SPI forces every future provider method to redeclare it.
 */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
