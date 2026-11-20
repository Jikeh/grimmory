package org.booklore.service.moonsync.position.format;

import java.util.List;
import java.util.Objects;

/**
 * The three header lines of an EPUB {@code .an} file, as {@code BookDb.exportNotesToText} writes them:
 * the book file's size in bytes, {@code indent:<bool>} and {@code trim:<bool>}.
 *
 * <p>{@code totalLength} is kept as text: Moon+'s import ({@code BookDb.importNotesFromText}) compares it with
 * its local copy of the book and rejects the file when they differ by more than 100,000 bytes. The two flags are
 * Moon+'s paragraph-indent and blank-space-trim settings at export time; readsync documents that they don't
 * reliably describe how the exported offsets were rendered, so callers must not rely on them for offset math.
 */
public record MoonAnnotationHeader(String totalLength, boolean indent, boolean trim) {

    public MoonAnnotationHeader {
        Objects.requireNonNull(totalLength, "totalLength");
        if (totalLength.indexOf('\n') >= 0 || totalLength.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Moon+ .an total length must be a single line");
        }
    }

    /**
     * Fails unless Moon+ can import a file with this header: {@code importNotesFromText} reads
     * {@code totalLength} with {@code Integer.valueOf}, so it must be a non-negative int. Not checked on parse, so
     * any file Moon+ uploaded stays readable.
     *
     * @throws IllegalStateException when {@code totalLength} isn't a non-negative int
     */
    public void requireImportable() {
        boolean importable;
        try {
            importable = !totalLength.isEmpty() && totalLength.chars().allMatch(c -> c >= '0' && c <= '9') && Integer.parseInt(totalLength) >= 0;
        } catch (NumberFormatException e) {
            importable = false;
        }
        if (!importable) {
            throw new IllegalStateException("Moon+ .an total length must be a non-negative int to be importable, found \"" + totalLength + "\"");
        }
    }

    List<String> lines() {
        return List.of(totalLength, "indent:" + indent, "trim:" + trim);
    }
}
