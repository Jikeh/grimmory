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
        if (totalLength.contains("\n")) {
            throw new IllegalArgumentException("Moon+ .an total length must be a single line");
        }
    }

    List<String> lines() {
        return List.of(totalLength, "indent:" + indent, "trim:" + trim);
    }
}
