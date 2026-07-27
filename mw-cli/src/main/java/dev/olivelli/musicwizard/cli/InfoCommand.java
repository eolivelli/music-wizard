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

import dev.olivelli.musicwizard.core.workspace.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Reports what a workspace contains and which stages have run. */
@Command(name = "info", description = "Show the state of a workspace.")
final class InfoCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "WORKSPACE", description = "The workspace directory.")
    Path workspaceDirectory;

    @Override
    public Integer call() {
        Workspace workspace = Workspace.open(workspaceDirectory);
        Workspace.Descriptor descriptor = workspace.readDescriptor();

        System.out.println("Workspace   " + workspace.root());
        System.out.println("Source      " + descriptor.sourceFileName());
        System.out.println("Created     " + descriptor.createdAt());
        System.out.println("Title       " + orDash(descriptor.title()));
        System.out.println("Artist      " + orDash(descriptor.artist()));
        System.out.println("Source hash " + (workspace.sourceMatchesDigest() ? "matches" : "CHANGED"));
        System.out.println();
        System.out.println("Score       " + (Files.isRegularFile(workspace.scoreFile())
                ? workspace.scoreFile().getFileName().toString()
                : "not yet produced"));
        System.out.println("Cache       " + describeDirectory(workspace.cacheDirectory()));
        System.out.println("Outputs     " + describeDirectory(workspace.outputDirectory()));
        return 0;
    }

    private static String orDash(String value) {
        return value != null && !value.isBlank() ? value : "-";
    }

    private static String describeDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return "absent";
        }
        try (var entries = Files.walk(directory)) {
            long files = entries.filter(Files::isRegularFile).count();
            return files == 0 ? "empty" : files + " file" + (files == 1 ? "" : "s");
        } catch (java.io.IOException e) {
            return "unreadable";
        }
    }
}
