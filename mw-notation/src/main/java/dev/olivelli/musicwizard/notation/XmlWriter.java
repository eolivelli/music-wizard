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

package dev.olivelli.musicwizard.notation;

/**
 * Indented XML text, one element per line.
 *
 * <p>Text rather than a DOM handed to a serializer, because the JDK's and
 * Android's serializers indent differently and a golden file has to describe
 * the bytes the phone writes too. Escaping is {@link HtmlWriter}'s: the two
 * documents this project writes have the same five characters to fear.
 */
final class XmlWriter {

    private static final String INDENT = "    ";

    private final StringBuilder out = new StringBuilder();
    private int depth;

    XmlWriter() {
        this(0);
    }

    private XmlWriter(int depth) {
        this.depth = depth;
    }

    /** A writer for content nested one level below this one's current depth. */
    XmlWriter child() {
        return new XmlWriter(depth + 1);
    }

    /** Appends everything a child wrote, at the depth it wrote it. */
    XmlWriter append(XmlWriter child) {
        out.append(child.out);
        return this;
    }

    /** Appends a line as it is, at no indentation: the declaration and DOCTYPE. */
    XmlWriter prolog(String line) {
        out.append(line).append('\n');
        return this;
    }

    XmlWriter open(String tag, String... attributes) {
        indent().append('<').append(tag);
        appendAttributes(attributes);
        out.append(">\n");
        depth++;
        return this;
    }

    XmlWriter close(String tag) {
        depth--;
        indent().append("</").append(tag).append(">\n");
        return this;
    }

    /** A whole element whose only child is escaped text. */
    XmlWriter element(String tag, String text, String... attributes) {
        indent().append('<').append(tag);
        appendAttributes(attributes);
        out.append('>').append(HtmlWriter.escape(text)).append("</").append(tag).append(">\n");
        return this;
    }

    /** A self-closed element, which XML reads as empty where HTML would not. */
    XmlWriter empty(String tag, String... attributes) {
        indent().append('<').append(tag);
        appendAttributes(attributes);
        out.append("/>\n");
        return this;
    }

    private StringBuilder indent() {
        for (int i = 0; i < depth; i++) {
            out.append(INDENT);
        }
        return out;
    }

    /** Name and value pairs; a null value leaves its attribute out. */
    private void appendAttributes(String... attributes) {
        if (attributes.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "attributes come in name and value pairs, got " + attributes.length);
        }
        for (int i = 0; i < attributes.length; i += 2) {
            if (attributes[i + 1] == null) {
                continue;
            }
            out.append(' ').append(attributes[i]).append("=\"")
                    .append(HtmlWriter.attribute(attributes[i + 1])).append('"');
        }
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
