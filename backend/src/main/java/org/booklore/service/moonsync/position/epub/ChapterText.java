package org.booklore.service.moonsync.position.epub;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The "plain" text of a spine file: every text node under {@code <body>} in document order, except text directly
 * inside {@code <script>} or {@code <style>}, concatenated. Offsets in this space are what Grimmory's CFIs address
 * and what readsync's NCX anchors are measured in; Moon+'s own (rendered) offsets are converted to and from it.
 * A port of readsync's {@code cfi_bridge/lib/walk.js}.
 */
public final class ChapterText {

    /** One non-empty text node and where its text starts in the concatenation. Lengths are UTF-16 units. */
    public record Segment(TextNode node, int start, int length) {

        public int end() {
            return start + length;
        }
    }

    private final Document document;
    private final List<Segment> segments;

    private ChapterText(Document document, List<Segment> segments) {
        this.document = document;
        this.segments = segments;
    }

    public static ChapterText of(String html) {
        return of(MoonHtml.parse(html));
    }

    public static ChapterText of(Document document) {
        List<Segment> segments = new ArrayList<>();
        Element body = document.body();
        int[] cursor = {0};
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode text && counted(text)) {
                int length = text.getWholeText().length();
                if (length > 0) {
                    segments.add(new Segment(text, cursor[0], length));
                    cursor[0] += length;
                }
            }
        }, body);
        return new ChapterText(document, List.copyOf(segments));
    }

    /** jsdom's walk rejects text whose parent element is an HTML {@code <script>} or {@code <style>}. */
    static boolean counted(TextNode text) {
        return !(text.parentNode() instanceof Element element && isScriptOrStyle(element));
    }

    /** An HTML-namespace {@code <script>} or {@code <style>} (an SVG {@code <script>}'s text counts, as in jsdom). */
    static boolean isScriptOrStyle(Element element) {
        return (element.normalName().equals("script") || element.normalName().equals("style"))
                && Parser.NamespaceHtml.equals(element.tag().namespace());
    }

    public Document document() {
        return document;
    }

    public List<Segment> segments() {
        return segments;
    }

    public int length() {
        return segments.isEmpty() ? 0 : segments.getLast().end();
    }

    /**
     * Where the element with {@code id} starts in the text: the start of the first text segment at or after it
     * (inside it or later in the document), the end of the text when none follows, 0 when there is no text at all.
     * Empty when no element has that id. This is an NCX navPoint's anchor for its {@code #fragment}.
     */
    public Optional<Integer> anchorOffset(String id) {
        Element target = document.getElementById(id);
        if (target == null) {
            return Optional.empty();
        }
        Map<Node, Integer> order = new IdentityHashMap<>();
        int[] next = {0};
        NodeTraversor.traverse((node, depth) -> order.put(node, next[0]++), document);
        int targetOrder = order.get(target);
        for (Segment segment : segments) {
            if (order.get(segment.node()) > targetOrder) {
                return Optional.of(segment.start());
            }
        }
        return Optional.of(length());
    }
}
