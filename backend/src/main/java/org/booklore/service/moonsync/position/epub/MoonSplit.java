package org.booklore.service.moonsync.position.epub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where Moon+ cuts an oversized chapter into chunks (the {@code split} of a {@code .po}): the raw-HTML boundaries of
 * readsync's {@code cfi_bridge/lib/moon_split.js} ({@code splitRawHtmlBoundaries}), ported as is so the chunk count
 * the chapter map relies on matches readsync. Rendered chunk lengths, and the checks against Moon+'s own
 * {@code A.createSplitHtmls}, belong to the rendered-offset work.
 *
 * <p>The rule: once the {@code <body>} content, plus the {@code <div …>} tag carried over from the previous chunk, is
 * longer than {@value #SPLIT_TRIGGER} characters, cut at the first {@code <p} or {@code <div} at or after
 * {@value #SPLIT_TRIGGER} if it is no more than {@value #SPLIT_SEARCH_WINDOW} further on, else right after the next
 * {@code >}. A chunk ending inside an unclosed {@code <div …>} carries that opening tag into the next one.
 */
public final class MoonSplit {

    static final int SPLIT_TRIGGER = 100_000;
    static final int SPLIT_SEARCH_WINDOW = 20_000;

    private MoonSplit() {
    }

    /**
     * Chunk boundaries as offsets into {@code html}: {@code 0}, each cut, then {@code html.length()}. One chunk when
     * the chapter isn't split.
     *
     * @throws IllegalArgumentException when the {@code >} fallback finds no {@code >} (readsync throws too)
     */
    public static List<Integer> rawBoundaries(String html) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        int[] range = bodyContentRange(html);
        String content = html.substring(range[0], range[1]);
        int consumed = 0;
        String carry = "";
        while (consumed < content.length()) {
            String working = carry + content.substring(consumed);
            if (working.length() <= SPLIT_TRIGGER) {
                break;
            }
            int splitAt = findSplitPoint(working);
            String carried = findCarriedDiv(working.substring(0, splitAt));
            int originalChunkLength = splitAt - carry.length();
            if (originalChunkLength <= 0) {
                throw new IllegalArgumentException("moon split produced a non-positive chunk length");
            }
            consumed += originalChunkLength;
            boundaries.add(range[0] + consumed);
            carry = carried;
        }
        boundaries.add(html.length());
        return List.copyOf(boundaries);
    }

    public static int chunkCount(String html) {
        return rawBoundaries(html).size() - 1;
    }

    /** {@code [start, end)} of the {@code <body>} content, or the whole text without a usable {@code <body>} tag. */
    static int[] bodyContentRange(String html) {
        int bodyOpen = findBodyOpen(html);
        if (bodyOpen < 0) {
            return new int[]{0, html.length()};
        }
        int bodyTagEnd = html.indexOf('>', bodyOpen);
        if (bodyTagEnd < 0) {
            return new int[]{0, html.length()};
        }
        // Like the JavaScript: the index found in the lower-cased copy is used on the original.
        int bodyClose = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (bodyClose < 0 || bodyClose < bodyTagEnd) {
            return new int[]{bodyTagEnd + 1, html.length()};
        }
        return new int[]{bodyTagEnd + 1, bodyClose};
    }

    /** {@code html.search(/<body\b/i)}. */
    private static int findBodyOpen(String html) {
        for (int i = html.indexOf('<'); i >= 0 && i + 5 <= html.length(); i = html.indexOf('<', i + 1)) {
            if (html.regionMatches(true, i + 1, "body", 0, 4)
                    && (i + 5 == html.length() || !isWordChar(html.charAt(i + 5)))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    static int findSplitPoint(String html) {
        int searchEnd = Math.min(html.length(), SPLIT_TRIGGER + SPLIT_SEARCH_WINDOW);
        int preferred = findPreferredBoundary(html, SPLIT_TRIGGER, searchEnd);
        if (preferred >= 0) {
            return preferred;
        }
        int nextGt = html.indexOf('>', SPLIT_TRIGGER);
        if (nextGt < 0) {
            throw new IllegalArgumentException("moon split fallback could not find a closing '>' after the split trigger");
        }
        return nextGt + 1;
    }

    static int findPreferredBoundary(String html, int start, int end) {
        int best = -1;
        for (String needle : new String[]{"<p", "<div"}) {
            int idx = html.indexOf(needle, start);
            if (idx >= 0 && idx <= end && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    static String findCarriedDiv(String chunk) {
        int open = chunk.lastIndexOf("<div");
        if (open < 0 || chunk.lastIndexOf("</div>") > open) {
            return "";
        }
        int tagEnd = chunk.indexOf('>', open);
        return tagEnd < 0 ? "" : chunk.substring(open, tagEnd + 1);
    }
}
