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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds providers on the classpath.
 *
 * <p>{@link ServiceLoader} discovery, looked up fresh per call: a provider
 * arrives by being on the classpath with a {@code META-INF/services} entry,
 * which is how {@code mw-ml}'s implementations reach a CLI that only depends
 * on it at runtime scope. No caching here — providers are looked up a handful
 * of times per run, and a cache would pin the first classpath seen, which is
 * wrong in exactly the environments (tests, the Android app) where the
 * classpath is the interesting variable.
 *
 * <p>An absent provider is an empty {@link Optional}, never a throw: the
 * pipeline treats a missing provider the way it treats a missing LilyPond,
 * reporting what is absent and what would supply it, then continuing. The
 * {@code ids} methods exist so that report can name what <em>is</em> present.
 */
public final class MlProviders {

    private MlProviders() {
    }

    /** The separation provider with this id, if the classpath has it. */
    public static Optional<SeparationProvider> separation(String id) {
        return byId(SeparationProvider.class, SeparationProvider::id, id);
    }

    /** The ASR provider with this id, if the classpath has it. */
    public static Optional<AsrProvider> asr(String id) {
        return byId(AsrProvider.class, AsrProvider::id, id);
    }

    /** Ids of every separation provider present, for saying what is available. */
    public static List<String> separationIds() {
        return idsOf(SeparationProvider.class, SeparationProvider::id);
    }

    /** Ids of every ASR provider present, for saying what is available. */
    public static List<String> asrIds() {
        return idsOf(AsrProvider.class, AsrProvider::id);
    }

    private static <P> Optional<P> byId(Class<P> type,
                                        java.util.function.Function<P, String> id,
                                        String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return Optional.empty();
        }
        for (P provider : instantiable(type)) {
            if (wanted.equals(id.apply(provider))) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    private static <P> List<String> idsOf(Class<P> type,
                                          java.util.function.Function<P, String> id) {
        List<String> ids = new ArrayList<>();
        for (P provider : instantiable(type)) {
            ids.add(id.apply(provider));
        }
        return List.copyOf(ids);
    }

    /**
     * Every provider that can be constructed, skipping any that cannot.
     *
     * <p>{@link ServiceLoader}'s iterator throws {@link ServiceConfigurationError}
     * out of {@code next()} when a provider's class fails to initialise — which
     * is exactly what an ONNX-backed provider does on a machine without the
     * natives, the machine the degrade-like-absent-LilyPond design exists for.
     * Iterated plainly, one broken provider hides every working one and takes
     * down {@code doctor}, the command whose job is reporting what is broken.
     * The stream API exposes each provider individually, so a failure skips
     * that provider and only that provider.
     */
    private static <P> List<P> instantiable(Class<P> type) {
        List<P> providers = new ArrayList<>();
        ServiceLoader.load(type).stream().forEach(candidate -> {
            try {
                providers.add(candidate.get());
            } catch (ServiceConfigurationError e) {
                // This provider cannot run here; the others still can.
            }
        });
        return providers;
    }
}
