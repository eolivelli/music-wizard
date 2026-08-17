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

import dev.olivelli.musicwizard.core.ml.ModelUnavailableException;
import dev.olivelli.musicwizard.core.ml.SeparationProvider;

/**
 * A separator present on the classpath whose model cannot be had — the
 * ordinary state of a fresh machine, and the one where a run promises a stem
 * and then cannot produce one.
 */
public final class FailingSeparationProvider implements SeparationProvider {

    /** What the failure says, so a test can find it in the report. */
    static final String REASON = "the fake separation model was never downloaded";

    @Override
    public String id() {
        return "fake-cli-unavailable-separation";
    }

    @Override
    public Separation separate(float[][] channels, int sampleRate) {
        throw new ModelUnavailableException(REASON);
    }
}
