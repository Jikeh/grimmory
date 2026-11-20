package org.booklore.service.moonsync.position.format;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * A Moon+ EPUB {@code .an} file: zlib-compressed UTF-8 text holding a {@link MoonAnnotationHeader} and one
 * {@link MoonAnnotationRecord} per highlight, bookmark or deleted-notes record.
 *
 * <p>Layout, as Moon+ uploads it ({@code Sync.uploadCloudNoteFile}): {@code BookDb.exportNotesToText} (header
 * plus every live row) followed by {@code BookDb.deletedNotesToText} (the in-memory deleted-notes list), every
 * line terminated by {@code \n}. {@code exportNotesToText} writes nothing at all, header included, once a book has
 * no live rows left, so the file uploaded after deleting a book's last highlight or bookmark is only deleted-notes
 * records with no header. Such files parse with an empty {@link #header()}. Moon+ itself can't import them
 * ({@code importNotesFromText} reads the book size from line 0), so writing requires a header
 * ({@link #withHeader}).
 *
 * <p>Records are read the way {@code BookDb.importNotesFromText} reads them: the 16 lines after a {@code #} by
 * position, so a column that is itself a lone {@code #} (a highlight of just "#") doesn't split the record.
 * Records that aren't 16 lines followed by a {@code #} or the end of the file are read up to the next {@code #}:
 * deleted-notes records with multi-line notes, and the lenient shapes readsync accepts.
 *
 * <p>Rewrites are verbatim: a record that comes back unchanged through {@link #withRecords} is written with the
 * exact lines it was read from, so bookmarks and records this parser reads leniently (e.g. readsync's
 * 15-line tombstone sample) survive untouched. Only new or changed records are written in Moon+'s 16-line layout,
 * and every one of their fields must be a single line, deleted-notes notes included: Moon+ reads by fixed index,
 * so a multi-line field would shift its columns. Encoding line breaks as {@code <BR>} is the caller's job
 * (ticket 02).
 * Round-trips are defined on the decompressed text; zlib bytes may differ from Moon+'s.
 *
 * <p>The PDF {@code .an} sub-format (tagged {@code #A*#} fields) is recognized only to be rejected: Moon+ sync
 * handles EPUB highlights only.
 */
public final class MoonAnnotationFile {

    private static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
    private static final String RECORD_DELIMITER = "#";
    private static final int RECORD_LINES = 16;
    private static final int FIXED_HEAD_LINES = 10;
    private static final int FIXED_TAIL_LINES = 3;
    private static final String PDF_TAG = "#A*#";

    private final @Nullable MoonAnnotationHeader header;
    private final List<MoonAnnotationRecord> records;
    private final Map<MoonAnnotationRecord, List<String>> sourceLines;
    private final boolean trailingNewline;

    private MoonAnnotationFile(@Nullable MoonAnnotationHeader header, List<MoonAnnotationRecord> records,
                               Map<MoonAnnotationRecord, List<String>> sourceLines, boolean trailingNewline) {
        this.header = header;
        this.records = List.copyOf(records);
        this.sourceLines = sourceLines;
        this.trailingNewline = trailingNewline;
    }

    /** A new file written entirely in Moon+'s layout. */
    public static MoonAnnotationFile of(MoonAnnotationHeader header, List<MoonAnnotationRecord> records) {
        return new MoonAnnotationFile(Objects.requireNonNull(header, "header"), records, Map.of(), true);
    }

    /** Decompresses and parses an {@code .an} file. */
    public static MoonAnnotationFile parse(byte[] compressed) {
        return parseText(decompress(Objects.requireNonNull(compressed, "compressed")));
    }

    /** Parses the decompressed text of an {@code .an} file. */
    public static MoonAnnotationFile parseText(String text) {
        Objects.requireNonNull(text, "text");
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        // A PDF file starts "{size}#A*#..."; checking only line 0 keeps an EPUB highlight quoting "#A*#" readable.
        if (lines.getFirst().contains(PDF_TAG)) {
            throw new MoonFormatException("PDF .an files are not supported; only EPUB highlights are synced");
        }
        boolean trailingNewline = lines.size() > 1 && lines.getLast().isEmpty();
        if (trailingNewline) {
            lines.removeLast();
        }
        MoonAnnotationHeader header = null;
        List<String> body = lines;
        if (!lines.getFirst().equals(RECORD_DELIMITER)) {
            if (lines.size() < 3) {
                throw new MoonFormatException(".an header must have 3 lines, found " + lines.size());
            }
            header = parseHeader(lines.get(0), lines.get(1), lines.get(2));
            body = lines.subList(3, lines.size());
        }
        if (!body.isEmpty() && !body.getFirst().equals(RECORD_DELIMITER)) {
            throw new MoonFormatException(".an body must start with a '#' record delimiter, found \"" + body.getFirst() + "\"");
        }
        List<MoonAnnotationRecord> records = new ArrayList<>();
        Map<MoonAnnotationRecord, List<String>> sourceLines = new HashMap<>();
        int start = 0;
        while (start < body.size()) {
            int end = start + 1 + RECORD_LINES;
            if (end > body.size() || (end < body.size() && !body.get(end).equals(RECORD_DELIMITER))) {
                end = start + 1;
                while (end < body.size() && !body.get(end).equals(RECORD_DELIMITER)) {
                    end++;
                }
            }
            List<String> recordLines = List.copyOf(body.subList(start + 1, end));
            MoonAnnotationRecord record = parseRecord(records.size() + 1, recordLines);
            records.add(record);
            sourceLines.putIfAbsent(record, recordLines);
            start = end;
        }
        return new MoonAnnotationFile(header, records, Map.copyOf(sourceLines), trailingNewline);
    }

    /** The header, or empty for a file holding only deleted-notes records (see the class comment). */
    public Optional<MoonAnnotationHeader> header() {
        return Optional.ofNullable(header);
    }

    /** Every record in file order: highlights, bookmarks and deleted-notes records. */
    public List<MoonAnnotationRecord> records() {
        return records;
    }

    /** The live highlights only; bookmarks and deleted-notes records are excluded. */
    public List<MoonAnnotationRecord> highlights() {
        return records.stream().filter(MoonAnnotationRecord::isHighlight).toList();
    }

    /**
     * This file with its records replaced. Records equal to ones read from the original file keep their source
     * lines; the rest are written in Moon+'s layout.
     */
    public MoonAnnotationFile withRecords(List<MoonAnnotationRecord> newRecords) {
        return new MoonAnnotationFile(header, newRecords, sourceLines, trailingNewline);
    }

    public MoonAnnotationFile withHeader(MoonAnnotationHeader newHeader) {
        return new MoonAnnotationFile(newHeader, records, sourceLines, trailingNewline);
    }

    /**
     * The decompressed file content.
     *
     * @throws IllegalStateException when the file has no header, or one Moon+ can't import (see
     *                               {@link MoonAnnotationHeader#requireImportable()})
     */
    public String toText() {
        if (header == null) {
            throw new IllegalStateException("A Moon+ .an file needs a header to be written; Moon+'s import reads the book size from line 0");
        }
        header.requireImportable();
        List<String> lines = new ArrayList<>(header.lines());
        for (MoonAnnotationRecord record : records) {
            lines.add(RECORD_DELIMITER);
            List<String> source = sourceLines.get(record);
            lines.addAll(source != null ? source : layout(record));
        }
        String text = String.join("\n", lines);
        return trailingNewline ? text + "\n" : text;
    }

    /** The zlib-compressed file content, ready to upload. */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(toText().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("zlib compression failed", e);
        }
        return out.toByteArray();
    }

    private static String decompress(byte[] compressed) {
        byte[] bytes;
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            bytes = in.readNBytes(MAX_DECOMPRESSED_BYTES + 1);
        } catch (ZipException e) {
            throw new MoonFormatException(".an file is not a valid zlib stream: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MoonFormatException(".an file could not be decompressed as zlib: " + e.getMessage(), e);
        }
        if (bytes.length > MAX_DECOMPRESSED_BYTES) {
            throw new MoonFormatException(".an file decompresses to more than " + MAX_DECOMPRESSED_BYTES + " bytes");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new MoonFormatException("Decompressed .an content is not valid UTF-8", e);
        }
    }

    private static MoonAnnotationHeader parseHeader(String totalLength, String indentLine, String trimLine) {
        return new MoonAnnotationHeader(totalLength, headerFlag(indentLine, "indent:"), headerFlag(trimLine, "trim:"));
    }

    private static boolean headerFlag(String line, String prefix) {
        return switch (line.startsWith(prefix) ? line.substring(prefix.length()) : "") {
            case "true" -> true;
            case "false" -> false;
            default -> throw new MoonFormatException(".an header line must be \"" + prefix + "true\" or \"" + prefix + "false\", found \"" + line + "\"");
        };
    }

    /**
     * Reads one record. The 10 leading and 3 trailing columns are fixed; what lies between holds bookmark, note
     * and text. Live records have exactly those three lines (Moon+ escapes newlines; a multi-line text is still
     * accepted, as readsync does). Deleted-notes records end their middle with the {@code "0"} text column and
     * may spread an unescaped note over several lines.
     */
    private static MoonAnnotationRecord parseRecord(int number, List<String> lines) {
        int n = lines.size();
        boolean deleted = n > 0 && lines.getLast().equals(MoonAnnotationRecord.DELETED_MARKER);
        int minimum = deleted ? FIXED_HEAD_LINES + 1 + FIXED_TAIL_LINES : RECORD_LINES;
        if (n < minimum) {
            throw new MoonFormatException(".an record " + number + " has " + n + " lines, expected " + (deleted ? "at least " + minimum : RECORD_LINES));
        }
        List<String> middle = lines.subList(FIXED_HEAD_LINES, n - FIXED_TAIL_LINES);
        String bookmark;
        String note;
        String text;
        if (deleted) {
            text = middle.getLast();
            List<String> bookmarkAndNote = middle.subList(0, middle.size() - 1);
            bookmark = bookmarkAndNote.isEmpty() ? "" : bookmarkAndNote.getFirst();
            note = bookmarkAndNote.size() < 2 ? "" : String.join("\n", bookmarkAndNote.subList(1, bookmarkAndNote.size()));
        } else {
            bookmark = middle.get(0);
            note = middle.get(1);
            text = String.join("\n", middle.subList(2, middle.size()));
        }
        return new MoonAnnotationRecord(
                lines.get(0), lines.get(1), lines.get(2), lines.get(3),
                intField(number, lines, 4, "chapter"),
                intField(number, lines, 5, "split"),
                longField(number, lines, 6, "offset"),
                intField(number, lines, 7, "length"),
                intField(number, lines, 8, "color"),
                longField(number, lines, 9, "time"),
                bookmark, note, text,
                lines.get(n - 3), lines.get(n - 2), lines.get(n - 1));
    }

    private static int intField(int number, List<String> lines, int index, String name) {
        try {
            return Integer.parseInt(lines.get(index));
        } catch (NumberFormatException e) {
            throw nonNumeric(number, name, lines.get(index), e);
        }
    }

    private static long longField(int number, List<String> lines, int index, String name) {
        try {
            return Long.parseLong(lines.get(index));
        } catch (NumberFormatException e) {
            throw nonNumeric(number, name, lines.get(index), e);
        }
    }

    private static MoonFormatException nonNumeric(int number, String name, String value, NumberFormatException cause) {
        return new MoonFormatException(".an record " + number + " has a non-numeric " + name + " field: \"" + value + "\"", cause);
    }

    /** Moon+'s 16-line layout for one record. */
    private static List<String> layout(MoonAnnotationRecord r) {
        List<String> lines = new ArrayList<>(RECORD_LINES);
        addSingleLine(lines, "id", r.id());
        addSingleLine(lines, "filename", r.filename());
        addSingleLine(lines, "path", r.path());
        addSingleLine(lines, "lowerPath", r.lowerPath());
        lines.add(Integer.toString(r.chapter()));
        lines.add(Integer.toString(r.split()));
        lines.add(Long.toString(r.offset()));
        lines.add(Integer.toString(r.length()));
        lines.add(Integer.toString(r.color()));
        lines.add(Long.toString(r.time()));
        addSingleLine(lines, "bookmark", r.bookmark());
        // Single line even on deleted-notes records: BookDb.deletedNotesToText writes notes unescaped, but
        // importNotesFromText reads by fixed index, so an extra line shifts the *DELETED* marker out of slot 16.
        addSingleLine(lines, "note", r.note());
        addSingleLine(lines, "text", r.text());
        addIntLine(lines, "underline", r.underline());
        addIntLine(lines, "strikethrough", r.strikethrough());
        addSingleLine(lines, "bak", r.bak());
        return lines;
    }

    /** importNotesFromText reads these columns with {@code Integer.valueOf}; anything else aborts Moon+'s import. */
    private static void addIntLine(List<String> lines, String field, String value) {
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Moon+ .an " + field + " field must be an int, found \"" + value + "\"", e);
        }
        lines.add(value);
    }

    /** Moon+ splits the file with {@code BufferedReader.readLine}, which breaks on {@code \r} as well as {@code \n}. */
    private static void addSingleLine(List<String> lines, String field, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Moon+ .an " + field + " field must not contain a line break");
        }
        lines.add(value);
    }
}
