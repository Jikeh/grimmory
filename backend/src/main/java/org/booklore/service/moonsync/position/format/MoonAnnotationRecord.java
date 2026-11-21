package org.booklore.service.moonsync.position.format;

import java.util.List;
import java.util.Objects;

/**
 * One record of an EPUB {@code .an} file: one row of Moon+'s {@code notes} table.
 *
 * <p>Moon+ writes every row as a {@code #} line followed by its 16 columns, one per line
 * ({@code BookDb.exportNotesToText}, column order as read back by {@code BookDb.importNotesFromText}):
 * <pre>
 *  1 id            Moon+'s row id (changes on every export; not an identity)
 *  2 filename      book file name
 *  3 path          book path on the phone
 *  4 lowerPath     the same, lower-cased
 *  5 chapter       NCX-derived chapter index
 *  6 split         split chunk within the chapter
 *  7 offset        start offset into the chunk's rendered text
 *  8 length        highlighted length; on bookmark rows, the bookmark's colour
 *  9 color         packed ARGB highlight colour
 * 10 time          created, epoch millis
 * 11 bookmark      bookmark name; non-empty only on bookmark rows
 * 12 note          note text
 * 13 text          highlighted text ("original")
 * 14 underline     0/1
 * 15 strikethrough 0/1
 * 16 bak           squiggly-underline flag ("1"/"0") on live rows; "*DELETED*" on deleted-notes records
 * </pre>
 * Values are kept exactly as stored. Live rows escape newlines as {@code <BR>} in every column; deleted-notes
 * records ({@code BookDb.deletedNotesToText}) write their bookmark and note unescaped, so on those records
 * {@code note} may hold real newlines. {@link #noteText()} and {@link #highlightedText()} give the decoded text, and
 * {@link #withNoteText} encodes a note for writing.
 *
 * <p>Note slot, confirmed on Moon+ 10.8 uploads (fixtures in {@code moonsync/format/notes/}): the note is always
 * column 12, between the bookmark (11) and the highlighted text (13), whether empty or not; a bookmark row has its
 * name in column 11 and an empty note and text. Parse by position, never by skipping blank lines.
 *
 * <p>Field names follow Moon+'s columns; readsync calls them annotation_id, filename, path, path_lower,
 * chapter_index, sub_index, start_offset, span_length, color_argb, created_at_ms, text and trailer.
 */
public record MoonAnnotationRecord(
        String id,
        String filename,
        String path,
        String lowerPath,
        int chapter,
        int split,
        long offset,
        int length,
        int color,
        long time,
        String bookmark,
        String note,
        String text,
        String underline,
        String strikethrough,
        String bak) {

    /** The {@code bak} value {@code BookDb.deletedNotesToText} writes on deleted-notes records. */
    public static final String DELETED_MARKER = "*DELETED*";

    /** What {@code BookDb.exportNotesToText} writes for each line break in a column, and import reads back as one. */
    private static final String LINE_BREAK_ESCAPE = "<BR>";

    /** What a record stands for. Only {@link #HIGHLIGHT} records are ever synced as highlights. */
    public enum Kind {
        /** A live highlight (with or without a note). */
        HIGHLIGHT,
        /** A Moon+ bookmark: recognized, never imported, and preserved verbatim on rewrite. */
        BOOKMARK,
        /** A deleted-notes record: a highlight or bookmark the user deleted in Moon+. */
        DELETED
    }

    public MoonAnnotationRecord {
        for (String value : List.of(id, filename, path, lowerPath, bookmark, note, text, underline, strikethrough, bak)) {
            Objects.requireNonNull(value, "Moon+ .an record fields must not be null");
        }
    }

    public Kind kind() {
        if (DELETED_MARKER.equals(bak)) {
            return Kind.DELETED;
        }
        return bookmark.isEmpty() ? Kind.HIGHLIGHT : Kind.BOOKMARK;
    }

    public boolean isHighlight() {
        return kind() == Kind.HIGHLIGHT;
    }

    public boolean isBookmark() {
        return kind() == Kind.BOOKMARK;
    }

    public boolean isDeleted() {
        return kind() == Kind.DELETED;
    }

    public MoonAnnotationRecord withColor(int newColor) {
        return new MoonAnnotationRecord(id, filename, path, lowerPath, chapter, split, offset, length, newColor, time,
                bookmark, note, text, underline, strikethrough, bak);
    }

    /**
     * The note as the user wrote it: {@link #note()} with Moon+'s {@code <BR>} line-break escape decoded, the way
     * {@code BookDb.importNotesFromText} reads every column back. Never trimmed: Moon+ stores the text box's content
     * exactly, so trailing spaces (typically from the phone keyboard) are part of the note. Empty when there is none.
     */
    public String noteText() {
        return decodeLineBreaks(note);
    }

    /**
     * The highlighted passage with {@code <BR>} decoded; a paragraph break inside the selection is one {@code \n}.
     * Read-only for now: there is no encoding counterpart yet, so a caller building a multi-paragraph highlight for
     * Moon+ must store {@code <BR>} in {@link #text()} itself (the writer refuses raw line breaks).
     */
    public String highlightedText() {
        return decodeLineBreaks(text);
    }

    /** This record with its raw note slot set as stored, without encoding (see {@link #withNoteText}). */
    public MoonAnnotationRecord withNote(String newNote) {
        return new MoonAnnotationRecord(id, filename, path, lowerPath, chapter, split, offset, length, color, time,
                bookmark, newNote, text, underline, strikethrough, bak);
    }

    /**
     * This record with the given note text, encoded the way {@code BookDb.exportNotesToText} stores it: every line
     * break becomes {@code <BR>}, so the note stays in its single fixed slot. Also used on deleted-notes records, whose
     * notes Moon+ itself writes unescaped: there the encoding only keeps the record at Moon+'s fixed 16 lines (an
     * unescaped extra line would shift {@code *DELETED*} out of its column); Moon+'s import deletes by chapter, split
     * and position and never reads the note of a deleted record.
     *
     * <p>{@code \r\n} and a lone {@code \r} count as line breaks too, as they do for the {@code readLine} Moon+
     * splits the file with. Nothing else is changed. Moon+ has no escape for a literal {@code <BR>}, so a note typed
     * with one reads back with a line break in its place, in Moon+ and in {@link #noteText()} alike.
     *
     * <p>Call it only when the note text actually changed. On one of Moon+'s own deleted-notes records with a
     * multi-line (unescaped) note, {@code withNoteText(noteText())} gives a record that reads the same but no longer
     * equals the parsed one, so a rewrite lays it out afresh instead of copying Moon+'s lines verbatim.
     */
    public MoonAnnotationRecord withNoteText(String newNoteText) {
        return withNote(encodeLineBreaks(Objects.requireNonNull(newNoteText, "newNoteText")));
    }

    private static String decodeLineBreaks(String stored) {
        return stored.replace(LINE_BREAK_ESCAPE, "\n");
    }

    private static String encodeLineBreaks(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", LINE_BREAK_ESCAPE);
    }

    public MoonAnnotationRecord withText(String newText) {
        return new MoonAnnotationRecord(id, filename, path, lowerPath, chapter, split, offset, length, color, time,
                bookmark, note, newText, underline, strikethrough, bak);
    }
}
