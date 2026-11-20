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
 *  8 length        highlighted length
 *  9 color         packed ARGB colour
 * 10 time          created, epoch millis
 * 11 bookmark      bookmark name; non-empty only on bookmark rows
 * 12 note          note text
 * 13 text          highlighted text ("original")
 * 14 underline     0/1
 * 15 strikethrough 0/1
 * 16 bak           "*DELETED*" on deleted-notes records
 * </pre>
 * Values are kept exactly as stored. Live rows escape newlines as {@code <BR>}; deleted-notes records
 * ({@code BookDb.deletedNotesToText}) write their bookmark and note unescaped, so on those records {@code note}
 * may hold real newlines. Decoding of note content is completed in ticket 02.
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

    public MoonAnnotationRecord withNote(String newNote) {
        return new MoonAnnotationRecord(id, filename, path, lowerPath, chapter, split, offset, length, color, time,
                bookmark, newNote, text, underline, strikethrough, bak);
    }

    public MoonAnnotationRecord withText(String newText) {
        return new MoonAnnotationRecord(id, filename, path, lowerPath, chapter, split, offset, length, color, time,
                bookmark, note, newText, underline, strikethrough, bak);
    }
}
