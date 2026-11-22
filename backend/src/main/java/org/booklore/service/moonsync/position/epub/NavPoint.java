package org.booklore.service.moonsync.position.epub;

import org.jspecify.annotations.Nullable;

import java.math.BigInteger;

/**
 * One {@code <navPoint>} of a book's NCX, in the depth-flattened document order that Moon+'s chapter index counts
 * (readsync {@code _NavPointEntry}, plus the depth and label Moon+'s chapter assembly needs).
 *
 * @param playOrder the {@code playOrder} attribute, or {@code null} when absent or not an integer. Kept for
 *                  diagnostics only: chapters are numbered by position in the list, not by this attribute, which
 *                  real books number sparsely and with duplicates (readsync {@code docs/connectors/moon-epub-ncx.md})
 * @param href      {@code content/@src} before the first {@code #}, as written (relative to the OPF's folder, possibly
 *                  percent-encoded)
 * @param fragment  what follows the first {@code #}, or {@code null} when there is none (the navPoint targets the
 *                  file's start)
 * @param depth     how many navPoints it is nested in (0 at the top of the {@code navMap})
 * @param label     its {@code navLabel/text}, line breaks removed and trimmed as Moon+ does; {@code ""} without one
 * @param bareHash  {@code content/@src} ends in a {@code #} with nothing after it: no fragment for readsync (and
 *                  {@link #fragment()}), an empty id for Moon+
 * @param labelless it has a {@code navLabel} whose {@code text} is missing or holds no text node first: Moon+'s
 *                  {@code getCName} answers null and {@code getChapter} then skips the navPoint (one without any
 *                  {@code navLabel} is kept, with label {@code ""})
 */
public record NavPoint(@Nullable BigInteger playOrder, String href, @Nullable String fragment, int depth, String label,
                       boolean bareHash, boolean labelless) {
}
