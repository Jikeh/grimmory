package org.booklore.service.moonsync.position.epub;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML assembled from pieces of spine files plus markup Moon+ inserts, remembering where every character came from.
 * Moon+'s offsets count characters of a chapter Moon+ assembled itself; this lets them be traced back to a spine file.
 */
public final class MappedHtml {

    /**
     * {@code length} characters at {@code at} in the assembled HTML, copied from spine file {@code spineIndex}
     * starting at {@code sourceStart} (an offset into that file's whole text), or inserted by Moon+ when
     * {@code spineIndex} is {@value #SYNTHETIC}.
     */
    public record Piece(int at, int length, int spineIndex, int sourceStart) {

        public boolean synthetic() {
            return spineIndex == SYNTHETIC;
        }

        public int end() {
            return at + length;
        }
    }

    public static final int SYNTHETIC = -1;

    private final StringBuilder html = new StringBuilder();
    private final List<Piece> pieces = new ArrayList<>();

    MappedHtml appendSource(int spineIndex, String fileText, int from, int to) {
        if (to > from) {
            pieces.add(new Piece(html.length(), to - from, spineIndex, from));
            html.append(fileText, from, to);
        }
        return this;
    }

    MappedHtml appendSynthetic(String markup) {
        if (!markup.isEmpty()) {
            pieces.add(new Piece(html.length(), markup.length(), SYNTHETIC, 0));
            html.append(markup);
        }
        return this;
    }

    MappedHtml append(MappedHtml other) {
        int shift = html.length();
        for (Piece piece : other.pieces) {
            pieces.add(new Piece(piece.at() + shift, piece.length(), piece.spineIndex(), piece.sourceStart()));
        }
        html.append(other.html);
        return this;
    }

    /** A copy without {@code [from, to)}. */
    MappedHtml without(int from, int to) {
        MappedHtml out = new MappedHtml();
        copyRange(out, 0, from);
        copyRange(out, to, html.length());
        return out;
    }

    private void copyRange(MappedHtml out, int from, int to) {
        for (Piece piece : pieces) {
            int start = Math.max(from, piece.at());
            int end = Math.min(to, piece.end());
            if (start >= end) {
                continue;
            }
            out.pieces.add(new Piece(out.html.length(), end - start, piece.spineIndex(),
                    piece.synthetic() ? 0 : piece.sourceStart() + start - piece.at()));
            out.html.append(html, start, end);
        }
    }

    public String html() {
        return html.toString();
    }

    public List<Piece> pieces() {
        return List.copyOf(pieces);
    }

    boolean isEmpty() {
        return html.isEmpty();
    }

    int length() {
        return html.length();
    }
}
