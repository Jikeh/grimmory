package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.position.epub.ChapterText;
import org.booklore.service.moonsync.position.epub.EpubContentException;
import org.booklore.service.moonsync.position.epub.EpubPackage;
import org.booklore.service.moonsync.position.epub.MoonHtml;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Comparison {@code epub-parser-parity}: the tree {@link MoonHtml} (jsoup) builds for every spine file against the
 * one readsync's bridge builds with jsdom (parse5); reference side {@code reference/harness/tree_signature.js}, which
 * defines the signature. Offsets and CFIs computed on a different tree can't be trusted, so this has to show zero
 * differences across the corpus before the offset and CFI comparisons mean anything.
 */
final class EpubParserParityComparison implements HarnessComparison {

    private static final Map<String, String> NAMESPACES = Map.of(
            Parser.NamespaceHtml, "",
            Parser.NamespaceSvg, "svg:",
            Parser.NamespaceMathml, "math:");

    @Override
    public String name() {
        return "epub-parser-parity";
    }

    @Override
    public JsonNode run(HarnessInput input) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        EpubPackage epub;
        try {
            epub = EpubPackage.read(input.path());
        } catch (EpubContentException e) {
            return out.put(JsonDiff.ERROR, e.getMessage());
        }
        ArrayNode files = out.putArray("files");
        for (int i = 0; i < epub.spineLength(); i++) {
            String html;
            try {
                html = epub.spineContent(i);
            } catch (EpubContentException e) {
                files.addNull();
                continue;
            }
            files.add(signature(MoonHtml.parse(html)));
        }
        return out;
    }

    static ObjectNode signature(Document document) {
        MessageDigest digest = sha256();
        int elements = 0;
        int texts = 0;
        for (String event : events(document)) {
            update(digest, event);
            if (event.startsWith("<")) {
                elements++;
            } else if (event.startsWith("\"")) {
                texts++;
            }
        }
        ChapterText chapterText = ChapterText.of(document);
        MessageDigest text = sha256();
        for (ChapterText.Segment segment : chapterText.segments()) {
            utf16le(text, segment.node().getWholeText());
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("digest", HexFormat.of().formatHex(digest.digest()));
        out.put("elements", elements);
        out.put("texts", texts);
        out.put("textLength", chapterText.length());
        out.put("textDigest", HexFormat.of().formatHex(text.digest()));
        return out;
    }

    /** The signature's events, in order: {@code <name[#id]}, {@code >} closing it, {@code "text}. */
    static List<String> events(Document document) {
        List<String> events = new ArrayList<>();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(document.firstElementChild());
        while (!stack.isEmpty()) {
            Object next = stack.pop();
            if (next instanceof Closing) {
                events.add(">");
                continue;
            }
            Node node = (Node) next;
            if (node instanceof Element element) {
                String namespace = element.tag().namespace();
                String prefix = NAMESPACES.getOrDefault(namespace, "{" + namespace + "}");
                events.add("<" + prefix + element.tag().localName() + (element.hasAttr("id") ? "#" + element.attr("id") : ""));
                stack.push(Closing.INSTANCE);
                for (int c = element.childNodeSize() - 1; c >= 0; c--) {
                    stack.push(element.childNode(c));
                }
            } else if (node instanceof TextNode text) {
                events.add("\"" + text.getWholeText());
            } else if (node instanceof DataNode data) {
                events.add("\"" + data.getWholeData());
            }
        }
        return events;
    }

    private enum Closing { INSTANCE }

    private static void update(MessageDigest digest, String event) {
        digest.update((event.length() + ":").getBytes(StandardCharsets.ISO_8859_1));
        utf16le(digest, event);
    }

    /** UTF-16LE code units as they are (a lone surrogate included), like Node's {@code 'utf16le'} encoding. */
    private static void utf16le(MessageDigest digest, String value) {
        byte[] bytes = new byte[value.length() * 2];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            bytes[2 * i] = (byte) c;
            bytes[2 * i + 1] = (byte) (c >> 8);
        }
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
