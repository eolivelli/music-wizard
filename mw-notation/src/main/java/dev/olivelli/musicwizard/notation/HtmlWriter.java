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

import java.util.Locale;

/**
 * Text accumulation for {@link AnalysisReport}, with the escaping it needs.
 *
 * <p>Everything that reaches a document goes through {@link #text} or
 * {@link #attribute}: a chord root, a lyric syllable and a file name are all
 * user data, and the same builder writes both HTML and the inline SVG.
 */
final class HtmlWriter {

    private final StringBuilder out = new StringBuilder();

    /** Appends already-escaped markup. */
    HtmlWriter raw(String markup) {
        out.append(markup);
        return this;
    }

    /** Appends markup, then a newline. */
    HtmlWriter line(String markup) {
        out.append(markup).append('\n');
        return this;
    }

    /** Appends escaped character data. */
    HtmlWriter text(String value) {
        out.append(escape(value));
        return this;
    }

    HtmlWriter open(String tag, String... attributes) {
        out.append('<').append(tag);
        appendAttributes(attributes);
        out.append('>');
        return this;
    }

    /**
     * Appends a tag with no children.
     *
     * <p>Written open-and-close rather than self-closed, which is the only form
     * that means the same thing in both halves of this document: an HTML parser
     * reads {@code <span/>} as an <em>opening</em> tag and makes everything
     * after it a child, so a bar chart written that way nests instead of
     * listing. SVG would accept either.
     */
    HtmlWriter empty(String tag, String... attributes) {
        out.append('<').append(tag);
        appendAttributes(attributes);
        out.append("></").append(tag).append('>');
        return this;
    }

    HtmlWriter close(String tag) {
        out.append("</").append(tag).append('>');
        return this;
    }

    /** A whole element whose only child is escaped text. */
    HtmlWriter element(String tag, String content, String... attributes) {
        open(tag, attributes);
        text(content);
        return close(tag);
    }

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
                    .append(attribute(attributes[i + 1])).append('"');
        }
    }

    @Override
    public String toString() {
        return out.toString();
    }

    /**
     * Character data, with the three delimiters that could end an element or
     * start an entity replaced.
     */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** The same, plus the quotes that could end an attribute. */
    static String attribute(String value) {
        return escape(value).replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * A number for a coordinate or a readout.
     *
     * <p>{@link Locale#ROOT}, because a decimal comma is neither valid SVG nor
     * the same number the golden files hold, and a whole number loses its
     * {@code .0} so the documents stay small.
     */
    static String number(double value, int decimals) {
        String formatted = String.format(Locale.ROOT, "%." + decimals + "f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        // A negative zero reads as a coordinate that is somewhere else.
        return "-0".equals(formatted) ? "0" : formatted;
    }
}
