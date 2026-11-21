package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.position.format.MoonAnnotationFile;
import org.booklore.service.moonsync.position.format.MoonAnnotationRecord;
import org.booklore.service.moonsync.position.format.MoonFormatException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.InflaterInputStream;

/**
 * Comparison {@code an-roundtrip}: {@link MoonAnnotationFile} against readsync's EPUB {@code an_parser}
 * ({@code parse_an_epub_header}, {@code parse_an_epub}, {@code serialize_an_epub}); reference side
 * {@code reference/harness/an_roundtrip.py}. Compared on decompressed text: header, every record's fields, and the
 * records read back after each side's writer lays the file out afresh ({@code reparsed}: readsync's
 * {@code serialize_an_epub}; Java's {@link MoonAnnotationFile#of}, Moon+'s 16-line layout for every record). Java's
 * verbatim rewrite of unchanged records is a Grimmory-only guarantee, covered by {@code MoonAnnotationFileTest}.
 *
 * <p>Known differences (spec "Where this deliberately differs from readsync"; ticket 02):
 * <ul>
 *   <li>Note text. readsync has no bookmark or note field: {@code parse_an_epub} folds the bookmark, note and text
 *       slots into {@code text} (leading empty slots dropped, joined with newlines, trailing newlines stripped). Java's
 *       {@code bookmark}/{@code note} are reported but not compared, and {@code text} is compared in that folded form
 *       ({@link #normalized}), so the highlighted text is still checked on records with a note.</li>
 *   <li>PDF {@code .an} files (Grimmory rejects them; readsync also takes {@code #A*#} anywhere as PDF, Grimmory only
 *       on line 0), header-less files and lone {@code #} columns (Grimmory parses them; readsync can't): the whole
 *       case is exempt, each only when the input really has that shape.</li>
 * </ul>
 */
final class AnRoundTripComparison implements HarnessComparison {

    static final String KIND_EPUB = "epub";
    static final String KIND_PDF = "pdf";
    private static final String PDF_TAG = "#A*#";

    /** Start of {@link MoonAnnotationFile}'s rejection message for the PDF sub-format. */
    private static final String PDF_REJECTION = "PDF .an files are not supported";

    @Override
    public String name() {
        return "an-roundtrip";
    }

    @Override
    public JsonNode run(HarnessInput input) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        MoonAnnotationFile file;
        try {
            file = input.isFile() ? MoonAnnotationFile.parse(input.bytes()) : MoonAnnotationFile.parseText(input.text());
        } catch (MoonFormatException e) {
            if (e.getMessage().startsWith(PDF_REJECTION)) {
                return out.put("kind", KIND_PDF);
            }
            return out.put(JsonDiff.ERROR, e.getMessage());
        }
        out.put("kind", KIND_EPUB);
        file.header().ifPresentOrElse(
                header -> out.putObject("header").put("totalLength", header.totalLength())
                        .put("indent", header.indent()).put("trim", header.trim()),
                () -> out.putNull("header"));
        out.set("records", records(file.records()));
        if (file.header().isPresent()) {
            try {
                MoonAnnotationFile laidOut = MoonAnnotationFile.of(file.header().get(), file.records());
                out.set("reparsed", records(MoonAnnotationFile.parseText(laidOut.toText()).records()));
            } catch (RuntimeException e) {
                out.putObject("reparsed").put(JsonDiff.ERROR, e.getMessage());
            }
        }
        return out;
    }

    /**
     * A file one side rejects in part (header or records) counts as rejected: the sides fail at different steps.
     * Java records (the side with a {@code note} field) get {@code text} folded the way readsync reads it.
     */
    @Override
    public JsonNode normalized(JsonNode output) {
        if (JsonDiff.isError(output.path("header")) || JsonDiff.isError(output.path("records"))) {
            return JsonNodeFactory.instance.objectNode().put(JsonDiff.ERROR, "rejected");
        }
        ObjectNode copy = (ObjectNode) output.deepCopy();
        for (String list : List.of("records", "reparsed")) {
            for (JsonNode node : copy.path(list)) {
                if (node instanceof ObjectNode record && record.has("note")) {
                    record.put("text", readsyncText(record.path("bookmark").asString(""), record.path("note").asString(""),
                            record.path("text").asString("")));
                }
            }
        }
        return copy;
    }

    /** {@code parse_an_epub}'s text: the bookmark, note and text lines, leading empty lines dropped, joined, trailing newlines stripped. */
    static String readsyncText(String bookmark, String note, String text) {
        List<String> lines = new ArrayList<>();
        for (String slot : List.of(bookmark, note, text)) {
            lines.addAll(List.of(slot.split("\n", -1)));
        }
        while (!lines.isEmpty() && lines.getFirst().isEmpty()) {
            lines.removeFirst();
        }
        String joined = String.join("\n", lines);
        int end = joined.length();
        while (end > 0 && joined.charAt(end - 1) == '\n') {
            end--;
        }
        return joined.substring(0, end);
    }

    @Override
    public Optional<String> exemption(HarnessInput input, JsonNode reference, JsonNode java) {
        String javaKind = java.path("kind").asString("");
        if (reference.path("kind").asString("").equals(KIND_PDF)) {
            if (javaKind.equals(KIND_PDF)) {
                return Optional.of("PDF .an file: Grimmory rejects it, readsync parses it (spec deviation)");
            }
            // Only when the tag really is off line 0: a real PDF file that Java read as EPUB must stay a difference.
            if (javaKind.equals(KIND_EPUB) && !firstLine(input).contains(PDF_TAG)) {
                return Optional.of("readsync takes \"#A*#\" anywhere as PDF; Grimmory checks line 0 only (spec deviation)");
            }
            return Optional.empty();
        }
        boolean readsyncRejected = JsonDiff.isError(normalized(reference));
        // Only when readsync failed too: a header Java dropped from a normal file must stay a difference.
        if (javaKind.equals(KIND_EPUB) && java.path("header").isNull() && readsyncRejected) {
            return Optional.of("header-less file (deleted-notes records only): Grimmory parses it, readsync can't (spec deviation)");
        }
        if (readsyncRejected && !JsonDiff.isError(java) && hasLoneHashColumn(java)) {
            return Optional.of("lone '#' column: Grimmory reads records by position, readsync splits on it (spec deviation)");
        }
        return Optional.empty();
    }

    @Override
    public Map<String, String> ignoredFields(HarnessInput input, JsonNode reference, JsonNode java) {
        Map<String, String> ignored = new LinkedHashMap<>();
        if (JsonDiff.isError(java.path("reparsed")) && hasMultiLineDeletedNote(java)) {
            // Moon+'s own unescaped multi-line deleted note doesn't fit the fixed slots; Grimmory only ever copies it
            // verbatim (MoonAnnotationFile class comment), so there is no Java layout to compare.
            ignored.put("/reparsed", "Moon+'s multi-line deleted note: written verbatim only, never laid out");
        }
        for (String list : List.of("records", "reparsed")) {
            JsonNode records = java.path(list);
            if (!records.isArray()) {
                continue;
            }
            for (int i = 0; i < records.size(); i++) {
                String path = "/" + list + "/" + i;
                ignored.put(path + "/bookmark", "readsync has no bookmark field (compared folded into text)");
                ignored.put(path + "/note", "readsync has no note field (compared folded into text; note text: spec deviation)");
            }
        }
        return ignored;
    }

    private static String firstLine(HarnessInput input) {
        String text;
        if (input.isFile()) {
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(input.bytes()))) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        } else {
            text = input.text();
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private static boolean hasMultiLineDeletedNote(JsonNode java) {
        return java.path("records").valueStream()
                .anyMatch(record -> record.path("deleted").asBoolean() && record.path("note").asString("").contains("\n"));
    }

    private static boolean hasLoneHashColumn(JsonNode java) {
        return java.path("records").valueStream()
                .flatMap(record -> record.properties().stream().map(Map.Entry::getValue))
                .anyMatch(value -> value.isString() && value.asString().equals("#"));
    }

    private static ArrayNode records(List<MoonAnnotationRecord> records) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (MoonAnnotationRecord r : records) {
            array.addObject()
                    .put("id", r.id()).put("filename", r.filename()).put("path", r.path()).put("lowerPath", r.lowerPath())
                    .put("chapter", r.chapter()).put("split", r.split()).put("offset", r.offset()).put("length", r.length())
                    .put("color", r.color()).put("time", r.time())
                    .put("bookmark", r.bookmark()).put("note", r.note()).put("text", r.text())
                    .put("underline", r.underline()).put("strikethrough", r.strikethrough()).put("bak", r.bak())
                    .put("deleted", r.isDeleted());
        }
        return array;
    }
}
