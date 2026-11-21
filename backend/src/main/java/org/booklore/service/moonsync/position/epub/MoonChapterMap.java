package org.booklore.service.moonsync.position.epub;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Moon+'s EPUB chapter index ↔ spine file and start position. A port of readsync's {@code EpubNcxCache}
 * ({@code connectors/grimmory/epub_ncx.py}), compared against it by the differential harness
 * ({@code epub-chapter-map}); the facts it rests on are in {@code docs/moon-sync/moon-format-reference.md}
 * ("{@code chapter}") and readsync's {@code docs/connectors/moon-epub-ncx.md}.
 *
 * <ul>
 *   <li><b>Trusted NCX</b> ({@link EpubPackage#ncxTrusted()}): chapter <i>n</i> is the <i>n</i>-th navPoint in
 *       document order, counting from 0, shifted by one when the book opens with content no navPoint covers (the
 *       first navPoint has a {@code #fragment}, or targets a later spine file): that content is chapter 0. A
 *       navPoint whose href or anchor doesn't resolve leaves its number unused rather than renumbering the rest.
 *       The start is the navPoint's spine file and the {@link ChapterText} offset of its anchor (0 without one).</li>
 *   <li><b>Otherwise</b> (no NCX, or a one-navPoint generator stub): chapter <i>n</i> is spine item <i>n</i>, from
 *       its start. A trusted book never falls back to this reading for a single chapter.</li>
 * </ul>
 *
 * <p>Each chapter records whether Moon+ splits its spine file into several chunks. The single-chunk guard is
 * readsync's: by default a chapter in a split file doesn't resolve, because readsync's reverse direction can't
 * compose offsets in one; {@code allowSplitSpineFile} lifts it for Moon+ → Grimmory progress.
 *
 * <p>Not thread-safe: the chapter map and parsed files are built lazily and cached.
 */
public final class MoonChapterMap {

    /** Where a chapter starts: a spine index and a {@link ChapterText} offset into that file. */
    public record SpineAnchor(int spineIndex, int anchorOffset) {
    }

    /** The chapter a spine position falls in, and that chapter's start in the same file. */
    public record ChapterAnchor(int chapter, int anchorOffset) {
    }

    /** One chapter of a trusted NCX. */
    public record Chapter(int spineIndex, int anchorOffset, boolean spineFileIsSingleChunk) {
    }

    private final EpubPackage epub;
    private final Map<Integer, ChapterText> texts = new HashMap<>();
    private final Map<Integer, Boolean> singleChunk = new HashMap<>();
    private @Nullable Map<Integer, Chapter> chapters;
    private boolean built;

    public MoonChapterMap(EpubPackage epub) {
        this.epub = epub;
    }

    public EpubPackage epub() {
        return epub;
    }

    /** Chapter index → spine anchor, keeping readsync's single-chunk guard. */
    public Optional<SpineAnchor> resolveChapter(int chapter) {
        return resolveChapter(chapter, false);
    }

    /**
     * Chapter index → spine anchor. Empty when the chapter doesn't resolve: a number past the book, a navPoint whose
     * target couldn't be found, or (unless {@code allowSplitSpineFile}) a chapter whose spine file Moon+ splits.
     */
    public Optional<SpineAnchor> resolveChapter(int chapter, boolean allowSplitSpineFile) {
        if (epub.ncxTrusted()) {
            Chapter found = chapters().get(chapter);
            if (found == null || !found.spineFileIsSingleChunk() && !allowSplitSpineFile) {
                return Optional.empty();
            }
            return Optional.of(new SpineAnchor(found.spineIndex(), found.anchorOffset()));
        }
        return chapter >= 0 && chapter < epub.spineLength() ? Optional.of(new SpineAnchor(chapter, 0)) : Optional.empty();
    }

    /**
     * Spine position → chapter: among the chapters starting in {@code spineIndex} at or before {@code offset} (a
     * {@link ChapterText} offset), the one starting last. Chapters in a split spine file never match (readsync has no
     * reverse composition for them). Empty when nothing matches, e.g. text before the file's first chapter start.
     */
    public Optional<ChapterAnchor> resolveSpinePosition(int spineIndex, int offset) {
        if (epub.ncxTrusted()) {
            ChapterAnchor best = null;
            for (Map.Entry<Integer, Chapter> entry : chapters().entrySet()) {
                Chapter chapter = entry.getValue();
                if (!chapter.spineFileIsSingleChunk() || chapter.spineIndex() != spineIndex || chapter.anchorOffset() > offset) {
                    continue;
                }
                if (best == null || chapter.anchorOffset() > best.anchorOffset()) {
                    best = new ChapterAnchor(entry.getKey(), chapter.anchorOffset());
                }
            }
            return Optional.ofNullable(best);
        }
        return spineIndex >= 0 && spineIndex < epub.spineLength() ? Optional.of(new ChapterAnchor(spineIndex, 0)) : Optional.empty();
    }

    /**
     * The trusted NCX's chapters by index, in the order readsync builds them (chapter 0 first when the book opens with
     * uncovered content, then by navPoint). Empty for an untrusted book.
     */
    public Map<Integer, Chapter> chapters() {
        if (!built) {
            chapters = build();
            built = true;
        }
        return chapters == null ? Map.of() : chapters;
    }

    /** Parsed text of a spine file, cached; empty when its content can't be read. */
    Optional<ChapterText> text(int spineIndex) {
        if (!texts.containsKey(spineIndex)) {
            ChapterText text;
            try {
                text = ChapterText.of(epub.spineContent(spineIndex));
            } catch (EpubContentException e) {
                text = null;
            }
            texts.put(spineIndex, text);
        }
        return Optional.ofNullable(texts.get(spineIndex));
    }

    private @Nullable Map<Integer, Chapter> build() {
        if (!epub.ncxTrusted()) {
            return null;
        }
        List<NavPoint> navPoints = epub.navPoints().orElse(List.of());
        if (navPoints.isEmpty()) {
            return null;
        }
        Map<Integer, Chapter> map = new LinkedHashMap<>();
        int leading = leadingOffset(navPoints.getFirst());
        if (leading > 0) {
            map.put(0, new Chapter(0, 0, isSingleChunk(0)));
        }
        for (int position = 0; position < navPoints.size(); position++) {
            NavPoint navPoint = navPoints.get(position);
            OptionalInt spineIndex = epub.ncxSpineIndex(navPoint.href());
            if (spineIndex.isEmpty()) {
                continue;
            }
            boolean single = isSingleChunk(spineIndex.getAsInt());
            int anchor = 0;
            if (navPoint.fragment() != null) {
                Optional<Integer> resolved = text(spineIndex.getAsInt()).flatMap(t -> t.anchorOffset(navPoint.fragment()));
                if (resolved.isEmpty()) {
                    continue;
                }
                anchor = resolved.get();
            }
            map.put(position + leading, new Chapter(spineIndex.getAsInt(), anchor, single));
        }
        return map.isEmpty() ? null : Collections.unmodifiableMap(map);
    }

    /**
     * 1 when the book opens with content the NCX doesn't cover (that content is chapter 0 and every navPoint shifts by
     * one), else 0. readsync's {@code _leading_offset}: Dorian Gray's uncovered cover page makes its 12th navPoint
     * chapter 12; Cloud Atlas's navPoint on its first file makes its 63rd navPoint chapter 62.
     */
    private int leadingOffset(NavPoint first) {
        if (first.fragment() != null) {
            return 1;
        }
        OptionalInt spineIndex = epub.ncxSpineIndex(first.href());
        return spineIndex.isPresent() && spineIndex.getAsInt() > 0 ? 1 : 0;
    }

    /** Whether Moon+ shows this spine file as one chunk; a file that can't be measured counts as split. */
    private boolean isSingleChunk(int spineIndex) {
        return singleChunk.computeIfAbsent(spineIndex, index -> {
            try {
                return MoonSplit.chunkCount(epub.spineContent(index)) == 1;
            } catch (EpubContentException | IllegalArgumentException e) {
                return false;
            }
        });
    }
}
