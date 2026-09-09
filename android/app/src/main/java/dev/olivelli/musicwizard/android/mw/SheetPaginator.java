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
package dev.olivelli.musicwizard.android.mw;

import java.util.ArrayList;
import java.util.List;

/** Splits a column of drawn chunks into pages: greedy, in order, never splitting a chunk. */
public final class SheetPaginator {

    private SheetPaginator() {
    }

    /**
     * @param heights    each chunk's height, in the order they stack
     * @param pageHeight what a page can hold
     * @return each page's chunk indexes, in order; a chunk taller than a page
     *         gets a page of its own and is cut by the page
     */
    public static List<List<Integer>> paginate(double[] heights, double pageHeight) {
        if (!(pageHeight > 0)) {
            throw new IllegalArgumentException("a page must have a height");
        }
        List<List<Integer>> pages = new ArrayList<>();
        List<Integer> page = new ArrayList<>();
        double used = 0;
        for (int i = 0; i < heights.length; i++) {
            if (!page.isEmpty() && used + heights[i] > pageHeight) {
                pages.add(page);
                page = new ArrayList<>();
                used = 0;
            }
            page.add(i);
            used += heights[i];
        }
        if (!page.isEmpty()) {
            pages.add(page);
        }
        return pages;
    }
}
