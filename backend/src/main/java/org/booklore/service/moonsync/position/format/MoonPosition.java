package org.booklore.service.moonsync.position.format;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;

/**
 * A Moon+ reading position: the content of a {@code .po} file in Moon+'s cloud sync folder.
 *
 * <p>Two sub-formats, chosen by the book's format (readsync {@code docs/connectors/moon.md}):
 * <ul>
 *   <li>EPUB (and the other reflowable formats): {@code {device}*{chapter}@{split}#{offset}:{percent}%}</li>
 *   <li>PDF: {@code {device}*{page}:{percent}%}</li>
 * </ul>
 * Either may end in {@code |{webViewScrollOffset}}: Moon+'s saved WebView scroll offset, written only in its
 * WebView reading mode ({@code Sync.uploadCloudPositionFile} appends it; {@code Sync.notifyNewCloudPosition}
 * splits it off). It is carried verbatim and never interpreted.
 *
 * <p>The percent is kept as written so a parsed position serializes back to exactly the text it came from.
 * Positions built here format the percent with one decimal, as readsync does.
 */
public sealed interface MoonPosition permits MoonPosition.Epub, MoonPosition.Pdf {

    /** Moon+'s per-install random device id ({@code A.deviceRandomID}). */
    String deviceId();

    /** The percent exactly as written, without the {@code %} sign (e.g. {@code "1.5"}, {@code "100"}). */
    String percentText();

    /** Moon+'s WebView scroll offset suffix, or {@code null} when absent. */
    @Nullable String webViewScrollOffset();

    default double percent() {
        return Double.parseDouble(percentText());
    }

    /** The {@code .po} file content for this position. */
    String serialize();

    /** A copy with only the percent replaced; every position field Moon+ wrote is kept. */
    MoonPosition withPercent(double percent);

    static Epub epub(String deviceId, int chapter, int split, long offset, double percent, @Nullable String webViewScrollOffset) {
        return new Epub(deviceId, chapter, split, offset, formatPercent(percent), webViewScrollOffset);
    }

    static Pdf pdf(String deviceId, int page, double percent, @Nullable String webViewScrollOffset) {
        return new Pdf(deviceId, page, formatPercent(percent), webViewScrollOffset);
    }

    /**
     * Parses {@code .po} content. Surrounding whitespace is ignored.
     *
     * @throws MoonFormatException when the text matches neither sub-format (CBX/comics are unsampled and
     *                             deliberately not guessed)
     */
    static MoonPosition parse(@Nullable String text) {
        if (text == null) {
            throw new MoonFormatException("Unrecognized .po content: null");
        }
        String stripped = text.strip();
        try {
            Matcher epub = MoonPositionGrammar.EPUB.matcher(stripped);
            if (epub.matches()) {
                return new Epub(epub.group("device"), Integer.parseInt(epub.group("chapter")), Integer.parseInt(epub.group("split")),
                        Long.parseLong(epub.group("offset")), epub.group("percent"), epub.group("scroll"));
            }
            Matcher pdf = MoonPositionGrammar.PDF.matcher(stripped);
            if (pdf.matches()) {
                return new Pdf(pdf.group("device"), Integer.parseInt(pdf.group("page")), pdf.group("percent"), pdf.group("scroll"));
            }
        } catch (IllegalArgumentException e) {
            throw new MoonFormatException("Unrecognized .po content (number out of range): " + quote(text), e);
        }
        throw new MoonFormatException("Unrecognized .po content: " + quote(text));
    }

    private static String formatPercent(double percent) {
        if (!Double.isFinite(percent) || percent < 0) {
            throw new IllegalArgumentException("Moon+ percent must be a finite, non-negative number: " + percent);
        }
        return String.format(Locale.ROOT, "%.1f", percent);
    }

    private static String quote(String text) {
        return '"' + (text.length() > 200 ? text.substring(0, 200) + "…" : text) + '"';
    }

    /** An EPUB-style position: chapter (Moon+'s NCX-derived chapter index), split chunk and rendered-text offset. */
    record Epub(String deviceId, int chapter, int split, long offset, String percentText,
                @Nullable String webViewScrollOffset) implements MoonPosition {

        public Epub {
            MoonPositionGrammar.requireValid(deviceId, percentText, webViewScrollOffset);
            if (chapter < 0 || split < 0 || offset < 0) {
                throw new IllegalArgumentException("Moon+ chapter, split and offset must not be negative");
            }
        }

        @Override
        public String serialize() {
            return deviceId + '*' + chapter + '@' + split + '#' + offset + ':' + percentText + '%' + MoonPositionGrammar.suffix(webViewScrollOffset);
        }

        @Override
        public Epub withPercent(double percent) {
            return new Epub(deviceId, chapter, split, offset, formatPercent(percent), webViewScrollOffset);
        }
    }

    /** A PDF position: the page number. */
    record Pdf(String deviceId, int page, String percentText, @Nullable String webViewScrollOffset) implements MoonPosition {

        public Pdf {
            MoonPositionGrammar.requireValid(deviceId, percentText, webViewScrollOffset);
            if (page < 0) {
                throw new IllegalArgumentException("Moon+ page must not be negative");
            }
        }

        @Override
        public String serialize() {
            return deviceId + '*' + page + ':' + percentText + '%' + MoonPositionGrammar.suffix(webViewScrollOffset);
        }

        @Override
        public Pdf withPercent(double percent) {
            return new Pdf(deviceId, page, formatPercent(percent), webViewScrollOffset);
        }
    }
}
