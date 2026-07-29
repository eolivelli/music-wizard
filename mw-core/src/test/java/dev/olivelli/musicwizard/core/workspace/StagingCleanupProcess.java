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

package dev.olivelli.musicwizard.core.workspace;

import dev.olivelli.musicwizard.core.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A throwaway process that stages a cache artifact and then dies, so a test can
 * observe what the JVM's own shutdown actually does.
 *
 * <p>This exists because the interesting line -- {@code addShutdownHook} -- can
 * only be reached by a process that really exits. Calling the cleanup method
 * directly, which is all the in-process tests can do, leaves the registration
 * itself unexercised: it can be deleted outright and every in-process test still
 * passes.
 *
 * <p>Prints the staged path on stdout so the parent knows what to look for.
 */
final class StagingCleanupProcess {

    private StagingCleanupProcess() {
    }

    /**
     * @param args mode, then the workspace directory to create, then the
     *             recording to import
     */
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path root = Path.of(args[1]);
        Path source = Path.of(args[2]);
        Workspace workspace = Workspace.create(root, source, ConfigLoader.withoutGlobalConfig());
        StageCache.Key key = StageCache.Key.forStage("stems");

        switch (mode) {
            case "crash" -> {
                Path staged = workspace.cache().stagingPath(key, ".wav");
                Files.writeString(staged, "half a stem");
                report(staged);
                // An exception out of main is the ordinary way a stage dies, and
                // it still runs shutdown hooks.
                throw new IllegalStateException("simulated crash mid-separation");
            }
            case "stage-during-shutdown" -> {
                // An application whose own shutdown hook still writes to the
                // cache. Reserving a path there must not fail just because no
                // further hook can be armed.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Path staged = workspace.cache().stagingPath(key, ".wav");
                        Files.writeString(staged, "staged while going down");
                        report(staged);
                    } catch (Exception e) {
                        System.out.println("FAILED " + e);
                    }
                }));
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void report(Path staged) {
        System.out.println("STAGED " + staged.toAbsolutePath());
        System.out.flush();
    }
}
