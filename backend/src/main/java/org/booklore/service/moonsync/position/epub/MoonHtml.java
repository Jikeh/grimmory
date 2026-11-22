package org.booklore.service.moonsync.position.epub;

import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.Range;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place spine (X)HTML is parsed for Moon+ position math. readsync parses with jsdom (parse5, the WHATWG
 * HTML algorithm, scripting off); this uses jsoup's HTML tree builder, and the differential harness
 * ({@code epub-parser-parity}) checks that both build the same element and text-node sequence for every spine file
 * of the corpus (including the synthetic {@code edge-cases.epub}) before any offset computed on top of it is
 * trusted.
 *
 * <p>Where jsoup departs from the WHATWG algorithm, the input or the tree is normalized here:
 * <ul>
 *   <li>Newlines: the input stream turns {@code \r\n} and a lone {@code \r} into {@code \n} before tokenizing; jsoup
 *       keeps them, which made every text node of a CRLF file longer.</li>
 *   <li>Whitespace between {@code <html>} and {@code <head>}: ignored in the "before head" insertion mode; jsoup
 *       keeps it as a text node of {@code <html>}.</li>
 *   <li>Text after {@code </body>} and {@code </html>}: the "after body" modes insert it "in body" (the body is still
 *       open), at the current node and after reconstructing the active formatting elements (so an unclosed
 *       {@code <a/>}, which HTML doesn't self-close, gets a copy around it); jsoup puts text after {@code </body>}
 *       into {@code <html>} and drops whitespace after {@code </html>}. See {@link #withTextAfterBody}.</li>
 *   <li>Adjacent text: inserting a character appends to an adjacent text node, so parse5 never leaves two text
 *       nodes side by side; jsoup does (after misnested formatting tags, around text foster-parented out of a table,
 *       and for {@code <![CDATA[…]]>} in SVG/MathML, which jsoup keeps as a CDATA node and parse5 reads as text).
 *       Adjacent text nodes are merged into one plain text node.</li>
 *   <li>{@code <template>}: its children belong to the template's content fragment, outside the document tree;
 *       jsoup makes them ordinary children. They are removed.</li>
 *   <li>Raw text: jsoup keeps the content of {@code <xmp>}, {@code <iframe>}, {@code <noscript>} and the like as
 *       data nodes, parse5 as text; only an HTML {@code <script>} or {@code <style>} keeps a data node here.</li>
 *   <li>Whitespace from character references in a table outside its cells is foster-parented before the table by
 *       parse5 (it comes as ordinary character tokens); jsoup keeps it in the table.</li>
 *   <li>Character references to a surrogate ({@code &#xD800;}): U+FFFD; jsoup inserts the lone surrogate.</li>
 * </ul>
 */
public final class MoonHtml {

    /** The end of a document: the last {@code </html>} end tag, then only comments and whitespace. */
    private static final Pattern DOCUMENT_TAIL = Pattern.compile(
            "</html[\\t\\n\\f ]*>((?:[\\t\\n\\f ]|<!--.*?-->)*)\\z", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private MoonHtml() {
    }

    /** Stands in for the text after {@code </body>} while jsoup parses; private-use characters, never in a book. */
    private static final String SENTINEL = "\uE000moon-after-body\uE000";

    public static Document parse(String html) {
        String normalized = normalizeNewlines(html);
        Document document = withTextAfterBody(parseRaw(normalized), normalized);
        Element root = document.firstElementChild();
        if (root != null && document.head() != null) {
            dropWhitespaceBeforeHead(root, document.head());
        }
        fosterParentCharacterReferencesInTables(document, normalized);
        for (Element element : document.getAllElements()) {
            if (element.normalName().equals("template") && Parser.NamespaceHtml.equals(element.tag().namespace())) {
                element.empty();
            }
        }
        for (Element element : document.getAllElements()) {
            if (!ChapterText.isScriptOrStyle(element)) {
                for (Node child : new ArrayList<>(element.childNodes())) {
                    if (child instanceof DataNode data) {
                        data.replaceWith(new TextNode(data.getWholeData()));
                    }
                }
            }
            mergeAdjacentText(element);
        }
        return document;
    }

    private static Document parseRaw(String html) {
        return Parser.htmlParser().setTrackPosition(true).parseInput(html, "");
    }

    /** The WHATWG input stream's newline normalization. */
    static String normalizeNewlines(String html) {
        if (html.indexOf('\r') < 0) {
            return html;
        }
        return html.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void dropWhitespaceBeforeHead(Element root, Element head) {
        List<Node> blank = new ArrayList<>();
        for (Node child : root.childNodes()) {
            if (child == head) {
                break;
            }
            if (child instanceof TextNode text && text.isBlank()) {
                blank.add(child);
            }
        }
        blank.forEach(Node::remove);
    }

    /**
     * Text after {@code </body>} (and whitespace after {@code </html>}), where the "after body" modes put it: inserted
     * "in body" at the current node, after reconstructing the active formatting elements. jsoup puts the whitespace
     * elsewhere, so the source is parsed again with a sentinel right before the end tag: jsoup inserts the sentinel by
     * the in-body rules, reconstruction included, and the sentinel is then replaced by that text.
     */
    private static Document withTextAfterBody(Document document, String source) {
        Element body = document.body();
        int end = body != null && closedInSource(body) ? body.endSourceRange().startPos()
                : source.toLowerCase(Locale.ROOT).lastIndexOf("</html");
        if (end < 0 || insideText(document, end)) {
            return document; // no end tag, or one inside raw text (a <title/> runs to the end of the file)
        }
        String after = textAfter(source, end);
        if (after == null) {
            // Markup after </body>: take the text from jsoup's nodes there, then what jsoup dropped after </html>.
            StringBuilder text = new StringBuilder();
            textFrom(document, end).forEach(t -> text.append(t.getWholeText()));
            after = text.append(trailingWhitespace(source)).toString();
        }
        if (after.isEmpty()) {
            return document;
        }
        Document again = parseRaw(source.substring(0, end) + SENTINEL + source.substring(end));
        textFrom(again, end + SENTINEL.length()).forEach(Node::remove);
        for (TextNode text : textFrom(again, 0)) {
            int at = text.getWholeText().indexOf(SENTINEL);
            if (at >= 0) {
                text.text(text.getWholeText().substring(0, at) + after);
                break;
            }
        }
        return again;
    }

    /**
     * The text after the end tag at {@code end} when nothing but text, comments and {@code </body>}/{@code </html>} end
     * tags follow it (character references decoded), or {@code null} when other markup does.
     */
    private static String textAfter(String source, int end) {
        int gt = source.indexOf('>', end);
        if (gt < 0) {
            return null;
        }
        String rest = END_TAGS.matcher(COMMENT.matcher(source.substring(gt + 1)).replaceAll("")).replaceAll("");
        return rest.indexOf('<') >= 0 ? null : Parser.unescapeEntities(rest, false);
    }

    private static final Pattern END_TAGS = Pattern.compile("</(?:body|html)[\\t\\n\\f ]*>", Pattern.CASE_INSENSITIVE);

    private static boolean insideText(Document document, int position) {
        boolean[] inside = {false};
        NodeTraversor.traverse((node, depth) -> {
            if ((node instanceof TextNode || node instanceof DataNode) && node.sourceRange().isTracked()
                    && node.sourceRange().startPos() <= position && position < node.sourceRange().endPos()) {
                inside[0] = true;
            }
        }, document);
        return inside[0];
    }

    /** Text nodes starting at or after {@code position} in the source, in source order. */
    private static List<TextNode> textFrom(Document document, int position) {
        List<TextNode> found = new ArrayList<>();
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode text && text.sourceRange().isTracked() && text.sourceRange().startPos() >= position) {
                found.add(text);
            }
        }, document);
        found.sort(Comparator.comparingInt(t -> t.sourceRange().startPos()));
        return found;
    }

    /**
     * Text in a table outside its cells: parse5 keeps a run of literal whitespace in place but foster-parents a run
     * holding a character reference ({@code &#13;} decodes to whitespace, yet comes as an ordinary character token)
     * to just before the table, as it does with any non-whitespace text. jsoup keeps such whitespace in the table.
     */
    // Known, not handled (in no corpus book): for a table directly in <body>, jsdom leaves such a run after the table,
    // split into separate text nodes, rather than before it.
    private static void fosterParentCharacterReferencesInTables(Document document, String source) {
        for (Element element : document.getAllElements()) {
            if (!TABLE_CONTEXT.contains(element.normalName()) || !Parser.NamespaceHtml.equals(element.tag().namespace())) {
                continue;
            }
            Element table = element.normalName().equals("table") ? element : element.closest("table");
            if (table == null || table.parent() == null) {
                continue;
            }
            for (Node child : new ArrayList<>(element.childNodes())) {
                if (child instanceof TextNode text && text.sourceRange().isTracked()
                        && source.substring(text.sourceRange().startPos(), text.sourceRange().endPos()).contains("&")) {
                    text.remove();
                    table.before(text);
                }
            }
        }
    }

    private static final Set<String> TABLE_CONTEXT = Set.of("table", "tbody", "thead", "tfoot", "tr");

    private static boolean closedInSource(Element element) {
        Range end = element.endSourceRange();
        return end.isTracked() && !end.isImplicit();
    }

    /** Whitespace after the closing {@code </html>} (comments there go to the document, not the text). */
    private static String trailingWhitespace(String html) {
        int close = html.toLowerCase(Locale.ROOT).lastIndexOf("</html");
        if (close < 0) {
            return "";
        }
        Matcher tail = DOCUMENT_TAIL.matcher(html).region(close, html.length());
        return tail.lookingAt() ? COMMENT.matcher(tail.group(1)).replaceAll("") : "";
    }

    private static void mergeAdjacentText(Element element) {
        List<Node> children = new ArrayList<>(element.childNodes());
        int i = 0;
        while (i < children.size()) {
            if (!(children.get(i) instanceof TextNode)) {
                i++;
                continue;
            }
            int end = i;
            StringBuilder text = new StringBuilder();
            while (end < children.size() && children.get(end) instanceof TextNode run) {
                text.append(run.getWholeText());
                end++;
            }
            TextNode first = (TextNode) children.get(i);
            String merged = surrogatesReplaced(text.toString());
            if (end - i > 1 || first.getClass() != TextNode.class || !merged.equals(first.getWholeText())) {
                TextNode replacement = new TextNode(merged);
                first.replaceWith(replacement);
                for (int k = i + 1; k < end; k++) {
                    children.get(k).remove();
                }
            }
            i = end;
        }
    }

    private static String surrogatesReplaced(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean paired = Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
            if (paired) {
                if (out != null) {
                    out.append(c).append(text.charAt(i + 1));
                }
                i++;
            } else if (Character.isSurrogate(c)) {
                if (out == null) {
                    out = new StringBuilder(text.substring(0, i));
                }
                out.append('\uFFFD');
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }
}
