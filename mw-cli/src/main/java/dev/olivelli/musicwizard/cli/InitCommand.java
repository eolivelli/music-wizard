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
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Creates a workspace around a recording. */
@Command(name = "init", description = "Create a workspace for a recording.")
final class InitCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE",
            description = "The recording to import (.mp3, .wav, .flac or .mid).")
    Path sourceFile;

    @Option(names = {"-w", "--workspace"}, paramLabel = "DIR",
            description = "Workspace directory to create. Defaults to FILE with a .mwz suffix.")
    Path workspaceDirectory;

    @Option(names = "--title", description = "Song title, used on the printed score.")
    String title;

    @Option(names = "--artist", description = "Artist name, used on the printed score.")
    String artist;

    @Override
    public Integer call() {
        Path target = workspaceDirectory != null
                ? workspaceDirectory
                : defaultWorkspacePath(sourceFile);

        Workspace workspace = Workspace.create(target, sourceFile);
        if (title != null || artist != null) {
            workspace.updateMetadata(title, artist);
        }

        System.out.println("Created workspace " + workspace.root());
        System.out.println("Imported          " + workspace.sourceFile().getFileName());
        System.out.println();
        System.out.println("Next: mw analyze " + workspace.root().getFileName());
        return 0;
    }

    private static Path defaultWorkspacePath(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Path parent = source.toAbsolutePath().getParent();
        return parent != null ? parent.resolve(stem + ".mwz") : Path.of(stem + ".mwz");
    }
}
