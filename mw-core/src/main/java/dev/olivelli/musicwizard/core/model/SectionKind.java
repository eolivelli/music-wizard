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

package dev.olivelli.musicwizard.core.model;

/**
 * A structural section of a song.
 *
 * <p>Boundaries between sections are found by signal analysis, but choosing
 * which cluster is the chorus rather than the verse needs world knowledge, so
 * the labels here are usually assigned by the Claude advisor layer. When no
 * advisor runs, sections stay {@link #UNKNOWN} and are numbered instead.
 */
public enum SectionKind {
    INTRO,
    VERSE,
    PRE_CHORUS,
    CHORUS,
    BRIDGE,
    SOLO,
    BREAKDOWN,
    OUTRO,
    UNKNOWN
}
