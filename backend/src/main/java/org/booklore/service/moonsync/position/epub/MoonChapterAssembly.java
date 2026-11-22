package org.booklore.service.moonsync.position.epub;

import org.jsoup.Jsoup;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The HTML of each Moon+ chapter, assembled the way Moon+ assembles it, so that Moon+'s chapter-relative offsets can
 * be traced to spine files. The chapter <em>numbers</em> and starts are {@link MoonChapterMap}'s (readsync's,
 * harness-compared); what a chapter <em>contains</em> follows Moon+'s {@code com.flyersoft.books.Epub} (decompiled
 * 10.8), which readsync doesn't model (it clamps an offset to the end of the chapter's first file):
 * <ul>
 *   <li><b>Own file</b> ({@code getChapterTextWithIDTag}): a chapter with a {@code #fragment} starts at the tag holding
 *       that id (string search for {@code id="…"}, {@code name="…"} and bare quotes, then back over directly preceding
 *       opening tags) and ends where the next chapter starts when that one is in the same file. A fragment within the
 *       first 500 characters of the body (or 100 and a quarter of it) of a file the previous chapter isn't in is
 *       ignored: the chapter starts at the body's start.</li>
 *   <li><b>Following files</b> ({@code getChaptersFromOpf}, {@code checkChapterAdditionalText}): spine files between a
 *       chapter's file and the next chapter's (or a later chapter's, when that one comes sooner in the spine) are
 *       appended to it, each after {@code <a name=FILE>} and the page break {@code <hr2>}. With at most two files in
 *       between, only files no chapter has claimed are appended; with more, Moon+ appends every one of them, so the
 *       last chapter of Pinocchio (an NCX entry pointing back at spine item 3) repeats the whole book to the end of
 *       the spine [device: {@code pinocchio.json}].</li>
 *   <li><b>Front matter</b>: unclaimed spine files before the first chapter's file form chapter 0, and so does the
 *       body before the first chapter's anchor when longer than 100 characters ({@code recheckChapters},
 *       {@code getTextBeforeTag}); Pride and Prejudice's chapter 0 is its title page plus the table of contents at the
 *       head of the next file [device: {@code pride-and-prejudice.json}]. Likewise, the head of a file before a later
 *       chapter's anchor is appended to the chapter before it ({@code getMoreContentFromNextID_Tag}).</li>
 *   <li>A chapter with a sub-chapter starting in the same file is empty; one whose id isn't found, after a chapter in
 *       the same file, is just its title; an ID-addressed chapter has its HTML comments removed and, when that leaves
 *       no text, becomes its title ({@code ifContentEmpty}).</li>
 *   <li>Untrusted NCX ({@link EpubPackage#ncxTrusted()} false): chapter <i>n</i> is spine file <i>n</i>.</li>
 * </ul>
 * Each spine file contributes its {@code <body>} content ({@code T.getHtmlBody}).
 *
 * <p>Not ported, so reported as unsupported rather than guessed: books whose NCX Moon+ picks by file name
 * differently from the OPF (or reads where readsync can't, e.g. without its namespace), or reads differently
 * ({@link EpubPackage#hasIrregularNcx()}), books Moon+ reads through an
 * EPUB 3 nav document instead of the NCX (no NCX, or an untrusted one, next to a declared nav), a one-navPoint NCX
 * over at most two spine files (Moon+ keeps it as its only chapter), and any chapter whose Moon+ number would differ
 * from readsync's: front matter one side counts and the other doesn't, a navPoint whose file isn't in the zip or
 * whose label has no text, a bare {@code #}, fewer than two resolving navPoints, or chapters
 * {@code deleteBrokenChapters} would drop. Chapters whose file is in the zip but not in the spine keep their numbers
 * but have no text here. When Moon+ takes the EPUB 3 nav over the NCX (a nav at least as long), its labels and
 * nesting are used; a nav whose entries differ from the NCX's, or that only Moon+ can repair and read, is reported
 * unsupported. Also not ported: finding files by name only, its tours and single-chapter splitting
 * ({@code trySplitChapters}).
 */
public final class MoonChapterAssembly {

    /** One chapter: its assembled HTML (body content, Moon+'s markers included) and where each piece came from. */
    public record ChapterHtml(int chapter, MappedHtml content) {

        public String html() {
            return content.html();
        }

        public List<MappedHtml.Piece> pieces() {
            return content.pieces();
        }
    }

    static final String PAGE_BREAK = "<hr2>";
    private static final int OUTSIDE_SPINE = -1;
    /** {@code Epub.getMaxHtmlSize} for EPUB: below it, Moon+ preloads ID chapters and drops broken ones. */
    private static final long PRELOAD_MAX_BOOK_SIZE = 1_000_000;
    private static final int PRELOAD_MAX_CHAPTERS = 60;
    /** An anchor this far into a new file's body leaves the head before it to the previous chapter. */
    private static final int FAR_INTO_BODY = 500;
    private static final int FAR_INTO_SHORT_BODY = 100;
    /** {@code getTextBeforeTag}'s minimum text for a head handed to the previous chapter, and for chapter 0. */
    private static final int HEAD_MIN_TEXT = 20;
    private static final int FRONT_HEAD_MIN_TEXT = 40;
    /** A following file this short and without text gets no page break before it. */
    private static final int NEAR_EMPTY_FILE = 100;
    /** {@code ifContentEmpty}: only a chapter shorter than this can be replaced by its title. */
    private static final int EMPTY_CHAPTER_MAX = 200;
    private static final int TEXT_BEFORE_TAG_RAW = 80;

    private final EpubPackage epub;
    private final Map<Integer, Optional<FileText>> files = new HashMap<>();
    /** Moon+'s chapter list, in Moon+'s order. */
    private final List<Chapter> chapters;
    private final @Nullable String unsupported;
    private final Map<Integer, Optional<ChapterHtml>> built = new HashMap<>();

    private MoonChapterAssembly(EpubPackage epub) {
        this.epub = epub;
        List<Chapter> list = new ArrayList<>();
        this.chapters = list;
        this.unsupported = buildChapters(list);
    }

    public static MoonChapterAssembly of(EpubPackage epub) {
        return new MoonChapterAssembly(epub);
    }

    /** Why this book's chapters can't be assembled at all, if so. */
    public Optional<String> unsupportedReason() {
        return Optional.ofNullable(unsupported);
    }

    /**
     * Chapter {@code number} (readsync's numbering), or empty when the book is unsupported, there is no such chapter,
     * its spine files can't be read, or Moon+ would number it differently.
     */
    public Optional<ChapterHtml> chapter(int number) {
        if (unsupported != null) {
            return Optional.empty();
        }
        return built.computeIfAbsent(number, this::build);
    }

    private Optional<ChapterHtml> build(int number) {
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            if (chapter.number == number) {
                if (i != number || chapter.spineIndex == OUTSIDE_SPINE) {
                    return Optional.empty(); // Moon+ would call it chapter i, or its text isn't in the spine
                }
                try {
                    return Optional.of(new ChapterHtml(number, text(i)));
                } catch (MissingFileException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    // ---- Moon+'s chapter list (getChaptersFromToc, getChaptersFromOpf, recheckChapters) ----

    /** Spine indexes {@code from} to {@code to}, both exclusive. */
    private record SpineRange(int from, int to) {
    }

    /**
     * A Moon+ chapter. {@code number} is readsync's; its position in {@link #chapters} is Moon+'s. {@code spineIndex}
     * is {@value #OUTSIDE_SPINE} for a navPoint whose file is in the zip but not in the spine: Moon+ keeps it as a
     * chapter (and readsync's numbering counts it), but no spine file holds its text.
     */
    private static final class Chapter {
        final int number;
        final int spineIndex;
        @Nullable String fragment;
        int depth;
        String label;
        boolean hasSubChapter;
        final Set<Integer> usedFiles = new HashSet<>();
        /** Files appended eagerly ({@code additionalText}), in order. */
        final List<Integer> additional = new ArrayList<>();
        /** {@code #MORE#_from|to}: every spine file strictly between, appended when the chapter is read. */
        @Nullable SpineRange lazy;
        /** Front matter Moon+ inserts as chapter 0, already assembled. */
        @Nullable MappedHtml direct;
        /** The navPoint as {@link EpubPackage#tocEntry} writes it, to compare with the EPUB 3 nav. */
        String tocEntry = "";

        Chapter(int number, int spineIndex, @Nullable String fragment, int depth, String label) {
            this.number = number;
            this.spineIndex = spineIndex;
            this.fragment = fragment;
            this.depth = depth;
            this.label = label;
            usedFiles.add(spineIndex);
        }
    }

    private @Nullable String buildChapters(List<Chapter> list) {
        String unsupported = bookLevelDifference();
        if (unsupported != null) {
            return unsupported;
        }
        if (!epub.ncxTrusted()) {
            for (int i = 0; i < epub.spineLength(); i++) {
                list.add(new Chapter(i, i, null, 0, ""));
            }
            return null;
        }
        List<NavPoint> navPoints = epub.navPoints().orElseThrow();
        int leading = MoonChapterMap.leadingOffset(epub, navPoints.getFirst());
        boolean treeToc = false;
        boolean hasIdStr = false;
        for (int position = 0; position < navPoints.size(); position++) {
            NavPoint navPoint = navPoints.get(position);
            treeToc |= navPoint.depth() > 0;
            // getChapter sets has_id_str for a named navPoint whose src has a '#', before it looks for the file.
            hasIdStr |= (navPoint.fragment() != null || navPoint.bareHash()) && !navPoint.labelless();
            OptionalInt spineIndex = epub.ncxSpineIndex(navPoint.href());
            if (spineIndex.isPresent() || epub.containsFile(navPoint.href())) {
                if (navPoint.bareHash()) {
                    return "a navPoint ends in an empty '#': Moon+ reads it as an empty id, readsync as no fragment";
                }
                if (navPoint.labelless()) {
                    return "a navPoint's label has no text: Moon+ skips it, readsync numbers it";
                }
                // Moon+ decodes the whole content/@src (Uri.decode) before splitting off the id.
                String fragment = navPoint.fragment() == null ? null : EpubPackage.percentDecode(navPoint.fragment());
                Chapter chapter = new Chapter(position + leading, spineIndex.orElse(OUTSIDE_SPINE), fragment,
                        navPoint.depth(), navPoint.label());
                chapter.tocEntry = EpubPackage.tocEntry(navPoint.href(), navPoint.bareHash() ? "" : fragment);
                list.add(chapter);
            }
        }
        if (list.stream().allMatch(c -> c.spineIndex == OUTSIDE_SPINE)) {
            return "no navPoint resolves to a spine file";
        }
        // getChapters: Moon+ re-reads the chapter list from the nav and keeps it unless it is shorter. Only the same
        // entries are supported; then the chapters take the nav's labels and nesting.
        List<EpubPackage.NavTocEntry> nav = epub.navTocEntries();
        if (nav.size() >= list.size()) {
            List<String> navTargets = nav.stream().map(EpubPackage.NavTocEntry::target).toList();
            List<String> ncxTargets = list.stream().map(c -> c.tocEntry).toList();
            if (!navTargets.equals(ncxTargets)) {
                return "Moon+ numbers this book by its EPUB 3 nav, whose entries differ from the NCX's";
            }
            for (int i = 0; i < list.size(); i++) {
                list.get(i).depth = nav.get(i).depth();
                list.get(i).label = nav.get(i).label();
                treeToc |= nav.get(i).depth() > 0; // addEpub3NCXChapters sets treeTOC; the NCX's stays set too
            }
        }
        if (list.size() < 2) {
            // getChaptersFromOpf: with fewer than 2 NCX chapters found, Moon+ reads the spine (or keeps the one chapter).
            return "only one navPoint resolves: Moon+ numbers this book's chapters by the spine, readsync by the NCX";
        }
        if (treeToc) {
            for (int i = 0; i < list.size() - 1; i++) {
                list.get(i).hasSubChapter = list.get(i).depth < list.get(i + 1).depth;
            }
        }
        List<Integer> contentBefore = assignAdditionalFiles(list);
        boolean inserted = false;
        if (!contentBefore.isEmpty()) {
            list.addFirst(frontMatter(contentBefore));
            inserted = true;
        }
        int firstChapterPos = inserted ? 1 : 0;
        int headChapter = 0; // 1 once recheckChapters prepends the head before the first anchor
        if (hasIdStr && list.size() > firstChapterPos) {
            MappedHtml head = contentBeforeFirstTag(list.get(firstChapterPos));
            if (head != null) {
                if (firstChapterPos == 0) {
                    Chapter front = new Chapter(0, list.getFirst().spineIndex, null, 0, "");
                    front.direct = head;
                    list.addFirst(front);
                    inserted = true;
                    headChapter = 1;
                } else {
                    list.getFirst().direct.appendSynthetic(PAGE_BREAK).append(head);
                }
            }
        }
        if (inserted != (leading == 1)) {
            return inserted
                    ? "Moon+ makes the front matter chapter 0, readsync doesn't: every chapter number differs by one"
                    : "readsync counts front matter as chapter 0, Moon+ doesn't: every chapter number differs by one";
        }
        try {
            assignFoundIds(list);
            // recheckChapters preloads and drops broken chapters before it prepends the head chapter.
            List<Chapter> preloaded = list.subList(headChapter, list.size());
            if (hasIdStr && preloaded.size() < PRELOAD_MAX_CHAPTERS && epub.fileSize() < PRELOAD_MAX_BOOK_SIZE) {
                long broken = IntStream.range(0, preloaded.size()).filter(i -> isBroken(preloaded, i)).count();
                if (broken > 2 && broken < preloaded.size()) {
                    return "Moon+ drops " + broken + " chapters whose anchors it can't find (deleteBrokenChapters), "
                            + "renumbering the rest";
                }
            }
        } catch (MissingFileException e) {
            return e.getMessage();
        }
        return null;
    }

    /** Why Moon+ would read this book's chapter list from somewhere readsync doesn't, if it would. */
    private @Nullable String bookLevelDifference() {
        if (epub.moonReadsAnotherNcx()) {
            return "Moon+ picks its NCX by file name and would read another one than the OPF names";
        }
        if (epub.navRepairedByMoon()) {
            return "the EPUB 3 nav has a bare '&': Moon+ repairs and may read it, this port can't tell";
        }
        if (epub.hasIrregularNcx()) {
            return "the NCX has a shape Moon+ reads differently (a navPoint outside navMap/navPoint, several contents, "
                    + "a content with other attributes but no src, or a %23 in a src)";
        }
        if (epub.ncxTrusted()) {
            return null;
        }
        if (epub.moonNcxNavPoints() > 1) {
            return "Moon+ numbers this book by its NCX, which readsync can't read (no namespace, or not named in the OPF)";
        }
        if (epub.hasMoonNavDocument()) {
            return "Moon+ reads this book's chapters from its EPUB 3 nav document (not ported)";
        }
        if (epub.navPoints().map(List::size).orElse(0) == 1 && epub.spineLength() <= 2) {
            return "a one-navPoint NCX over at most two spine files: Moon+ keeps the navPoint as its only chapter";
        }
        return null;
    }

    /**
     * {@code getChapterTextWithIDTag}: when an ID chapter is followed by a chapter in the same file without an id,
     * Moon+ cuts at the next similar id ({@link #similarIdPosition}) and gives that id to the next chapter, which from
     * then on starts there. Moon+ does it as it reads the chapters in order (all of them up front when it preloads);
     * here it is done once, in order.
     */
    private void assignFoundIds(List<Chapter> list) {
        for (int i = 0; i < list.size() - 1; i++) {
            Chapter chapter = list.get(i);
            Chapter next = list.get(i + 1);
            if (chapter.direct != null || chapter.fragment == null || next.fragment != null
                    || !sameFile(list, i, i + 1) || chapter.spineIndex == OUTSIDE_SPINE) {
                continue;
            }
            FileText file = file(chapter.spineIndex);
            int idPos = startPosition(list, i, file);
            if (idPos == -1) {
                continue;
            }
            SimilarId similar = similarIdPosition(file.bodyText(), chapter.fragment, idPos);
            if (similar.position() == -1 || similar.quote() == 0) {
                continue;
            }
            String body = file.bodyText();
            int open = body.indexOf(similar.quote(), similar.position());
            int close = open == -1 ? -1 : body.indexOf(similar.quote(), open + 2);
            if (open != -1 && close != -1) {
                next.fragment = body.substring(open + 1, close);
            }
        }
    }

    /** {@code getChapterTextWithIDTag}'s broken chapter: id not found, after a chapter in the same file, nothing appended. */
    private boolean isBroken(List<Chapter> list, int i) {
        Chapter chapter = list.get(i);
        if (chapter.direct != null || chapter.fragment == null || chapter.spineIndex == OUTSIDE_SPINE || i == 0) {
            return false;
        }
        Chapter previous = list.get(i - 1);
        return !previous.hasSubChapter && sameFile(list, i - 1, i)
                && idPosition(file(chapter.spineIndex), chapter.fragment) == -1
                && chapter.additional.isEmpty() && chapter.lazy == null;
    }

    /**
     * {@code getChaptersFromOpf}'s second half: which spine files each chapter gets after its own, and which unclaimed
     * files come before the first chapter (returned).
     */
    private List<Integer> assignAdditionalFiles(List<Chapter> list) {
        int size = list.size();
        List<Integer> contentBefore = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Chapter chapter = list.get(i);
            boolean last = i == size - 1;
            int from = chapter.spineIndex;
            int to = last ? epub.spineLength() : list.get(i + 1).spineIndex;
            if (!last) {
                for (int k = i + 2; k < size; k++) {
                    int later = list.get(k).spineIndex;
                    if (to > later && later > from && later > 1) {
                        to = later;
                    }
                }
            }
            if (from != OUTSIDE_SPINE && to != OUTSIDE_SPINE && to - from > 1) {
                boolean lazy = to - from > 3;
                for (int file = from + 1; file < to && file < epub.spineLength(); file++) {
                    if (claimed(list, file)) {
                        continue;
                    }
                    chapter.usedFiles.add(file);
                    if (!lazy) {
                        chapter.additional.add(file);
                    } else if (chapter.lazy == null) {
                        chapter.lazy = new SpineRange(from, to);
                    }
                }
            }
            if (i == 0) {
                int before = Math.min(to, from);
                for (int file = 0; file < before; file++) {
                    if (!claimed(list, file)) {
                        contentBefore.add(file);
                    }
                }
            }
        }
        return contentBefore;
    }

    private static boolean claimed(List<Chapter> list, int file) {
        return list.stream().anyMatch(c -> c.usedFiles.contains(file));
    }

    private Chapter frontMatter(List<Integer> files) {
        Chapter front = new Chapter(0, files.getFirst(), null, 0, "");
        MappedHtml html = new MappedHtml();
        for (int file : files) {
            appendFile(html, file);
        }
        front.direct = html;
        return front;
    }

    /** {@code recheckChapters}: the body before the first chapter's anchor, when it is long enough to be a page. */
    private @Nullable MappedHtml contentBeforeFirstTag(Chapter first) {
        if (first.fragment == null) {
            return null;
        }
        FileText file = file(first.spineIndex);
        int idPos = idPosition(file, first.fragment);
        if (idPos <= FAR_INTO_SHORT_BODY) {
            return null;
        }
        return textBeforeTag(file, idPos, FRONT_HEAD_MIN_TEXT);
    }

    // ---- One chapter's text (getChapterText, getChapterTextWithIDTag, getMoreContentFromNextID_Tag) ----

    private MappedHtml text(int i) {
        Chapter chapter = chapters.get(i);
        if (chapter.direct != null) {
            return chapter.direct;
        }
        if (chapter.fragment == null && !nextHasIdTag(i)) {
            if (chapter.hasSubChapter && sameFile(chapters, i, i + 1)) {
                return new MappedHtml();
            }
            FileText file = file(chapter.spineIndex);
            return new MappedHtml().appendSource(chapter.spineIndex, file.full, file.body.start(), file.body.end())
                    .append(additional(chapter));
        }
        MappedHtml text = textWithIdTag(i);
        if (nextHasIdTag(i) && !sameFile(chapters, i, i + 1)) {
            MappedHtml head = headBeforeAnchor(i + 1);
            if (head != null) {
                text.appendSynthetic(PAGE_BREAK).append(head);
            }
        }
        return contentEmptyCheck(chapter, text);
    }

    private boolean nextHasIdTag(int i) {
        return i < chapters.size() - 1 && chapters.get(i + 1).fragment != null;
    }

    private MappedHtml textWithIdTag(int i) {
        Chapter chapter = chapters.get(i);
        FileText file = file(chapter.spineIndex);
        String text = file.bodyText();
        String id = chapter.fragment;
        String nextId = i < chapters.size() - 1 ? chapters.get(i + 1).fragment : null;
        int idPos = startPosition(chapters, i, file);
        if (idPos == -1) {
            if (i > 0 && !chapters.get(i - 1).hasSubChapter && sameFile(chapters, i - 1, i)) {
                return new MappedHtml().appendSynthetic("<h2>" + chapter.label + "</h2>").append(additional(chapter));
            }
            int end = text.length();
            if (i == 0 && nextId != null) {
                int nextPos = idPosInHtml(text, nextId, 0);
                int lt = nextPos > 0 ? text.lastIndexOf('<', nextPos) : -1;
                if (lt > 0) {
                    end = lt;
                }
            }
            return slice(chapter.spineIndex, file, 0, end).append(additional(chapter));
        }
        int end = -1;
        if (sameFile(chapters, i, i + 1)) {
            int nextPos = nextId != null ? idPosInHtml(text, nextId, idPos) : -1;
            if (nextPos == -1) {
                nextPos = similarIdPosition(text, id, idPos).position();
            }
            if (nextPos != -1) {
                end = tagStartBefore(text, nextPos);
            }
        }
        if (end == -1) {
            end = text.length();
        }
        int start = backOverOpeningTags(text, idPos);
        return slice(chapter.spineIndex, file, start, end).append(additional(chapter));
    }

    /**
     * When the next chapter's id isn't found after this one, Moon+ cuts at the next element whose id shares this one's
     * prefix up to its first digit ({@code getIdPTagInHtml}), else at the next {@code  id=}.
     */
    private static SimilarId similarIdPosition(String text, @Nullable String id, int from) {
        String tag = idPTagInHtml(text, String.valueOf(id));
        int found = -1;
        if (tag != null) {
            for (int k = 1; k < tag.length(); k++) {
                char c = tag.charAt(k);
                if (c >= '0' && c <= '9') {
                    tag = tag.substring(0, k);
                    break;
                }
            }
            if (tag.length() > 1) {
                found = text.indexOf(" " + tag, from);
            }
        }
        if (found == -1) {
            found = text.indexOf(" id=", from);
        }
        char quote = tag == null ? 0 : tag.contains("\"") ? '"' : tag.contains("'") ? '\'' : 0;
        return new SimilarId(found, quote);
    }

    /** Where the next similar id was found ({@code -1}: nowhere), and the quote its tag uses (0 when neither). */
    private record SimilarId(int position, char quote) {
    }

    /**
     * Where a chapter with a {@code #fragment} starts in its file's body (0 without one, -1 when the id isn't found):
     * an anchor within the first 500 characters (or 100 and a quarter of the body) of a file the previous chapter
     * isn't in counts as the body's start, and so does one whose head is too short to hand to the previous chapter.
     */
    private int startPosition(List<Chapter> list, int i, FileText file) {
        Chapter chapter = list.get(i);
        int idPos = chapter.fragment == null ? 0 : idPosition(file, chapter.fragment);
        if (idPos > 0 && i > 0 && !sameFile(list, i - 1, i)) {
            if (!farIntoBody(idPos, file.bodyText().length()) || textBeforeTag(file, idPos, HEAD_MIN_TEXT) == null) {
                idPos = 0;
            }
        }
        return idPos;
    }

    /** {@code Epub.getIdPTagInHtml}, including its {@code != 1} comparisons (so it nearly always answers {@code id="…"}). */
    private static @Nullable String idPTagInHtml(String text, String id) {
        String[] candidates = {"id=\"" + id + "\"", "id='" + id + "'", "name=\"" + id + "\"", "name='" + id + "'",
                "\"" + id + "\"", "'" + id + "'"};
        for (int k = 0; k < candidates.length - 1; k++) {
            if (text.indexOf(candidates[k]) != 1) {
                return candidates[k];
            }
        }
        return text.indexOf(candidates[candidates.length - 1]) == 1 ? null : candidates[candidates.length - 1];
    }

    /** From the {@code <} of the tag holding the id, back over opening tags that directly precede it. */
    static int backOverOpeningTags(String text, int idPos) {
        int lt = text.lastIndexOf('<', idPos);
        if (lt == -1) {
            int lineStart = text.lastIndexOf('\n', idPos) + 2;
            return lineStart > 5 ? lineStart : idPos;
        }
        int pos;
        do {
            pos = lt;
            if (pos <= 0 || text.charAt(pos - 1) != '>') {
                break;
            }
            lt = text.lastIndexOf('<', pos - 1);
            if (lt == -1) {
                break;
            }
        } while (text.charAt(lt + 1) != '/');
        return pos;
    }

    /** {@code getChapterTextWithIDTag(i, true)}: the head of chapter {@code j}'s file before its anchor, if Moon+ moves it. */
    private @Nullable MappedHtml headBeforeAnchor(int j) {
        Chapter chapter = chapters.get(j);
        FileText file = file(chapter.spineIndex);
        int idPos = idPosition(file, chapter.fragment);
        String text = file.bodyText();
        if (idPos > 0 && j > 0 && !sameFile(chapters, j - 1, j) && farIntoBody(idPos, text.length())) {
            return textBeforeTag(file, idPos, HEAD_MIN_TEXT);
        }
        return null;
    }

    /**
     * {@code getIdPosInHtml} on the body; when the id is only found elsewhere in the file (e.g. on {@code <body>}),
     * the body's start.
     */
    private static int idPosition(FileText file, String id) {
        int pos = idPosInHtml(file.bodyText(), id, 0);
        return pos == -1 && idPosInHtml(file.full, id, 0) >= 0 ? 0 : pos;
    }

    static int idPosInHtml(String text, String id, int from) {
        int pos = text.indexOf("id=\"" + id + "\"", from);
        if (pos == -1) {
            pos = text.indexOf("id='" + id + "'", from);
        }
        if (pos == -1) {
            pos = text.indexOf("name=\"" + id + "\"", from);
        }
        if (pos == -1) {
            pos = text.indexOf("name='" + id + "'", from);
        }
        if (pos == -1 && id.length() >= 3) {
            pos = text.indexOf("\"" + id + "\"", from);
        }
        if (pos != -1 || id.length() < 3) {
            return pos;
        }
        return text.indexOf("'" + id + "'", from);
    }

    /** {@code getTextBeforeTag}: the body up to the tag before {@code pos}, if at least 80 characters or enough text. */
    private @Nullable MappedHtml textBeforeTag(FileText file, int pos, int minText) {
        String text = file.bodyText();
        int start = text.indexOf("<body");
        start = start == -1 ? 0 : text.indexOf('>', start) + 1;
        int end = tagStartBefore(text, pos);
        if (end <= start) {
            return null;
        }
        String head = text.substring(start, end);
        if (head.length() >= TEXT_BEFORE_TAG_RAW || plainText(head).length() >= minText) {
            return slice(file.spineIndex, file, start, end);
        }
        return null;
    }

    private MappedHtml additional(Chapter chapter) {
        MappedHtml html = new MappedHtml();
        if (chapter.lazy != null) {
            for (int file = chapter.lazy.from() + 1; file < chapter.lazy.to() && file < epub.spineLength(); file++) {
                appendFile(html, file);
            }
        } else {
            for (int file : chapter.additional) {
                appendFile(html, file);
            }
        }
        return html;
    }

    /** {@code <a name=FILE>}, a page break unless the file is a near-empty first one, then the file's body. */
    private void appendFile(MappedHtml html, int spineIndex) {
        FileText file = file(spineIndex);
        html.appendSynthetic("<a name=" + file.name + ">");
        String body = file.bodyText();
        boolean blank = body.length() <= NEAR_EMPTY_FILE && plainText(body).isBlank();
        if (spineIndex > 0 && !blank) {
            html.appendSynthetic(PAGE_BREAK);
        }
        html.appendSource(spineIndex, file.full, file.body.start(), file.body.end());
    }

    /** {@code ifContentEmpty}: comments removed; a chapter left without text, images or its title becomes its title. */
    private static MappedHtml contentEmptyCheck(Chapter chapter, MappedHtml text) {
        MappedHtml cleaned = text;
        int from = 0;
        while (true) {
            String html = cleaned.html();
            int open = html.indexOf("<!--", from);
            int close = open == -1 ? -1 : html.indexOf("-->", open);
            if (close == -1) {
                break;
            }
            cleaned = cleaned.without(open, close + 3);
            from = open;
        }
        String html = cleaned.html();
        if (html.length() < EMPTY_CHAPTER_MAX && !html.contains("<im") && !html.contains(chapter.label) && plainText(html).isBlank()) {
            return new MappedHtml().appendSynthetic("<center><h2>" + chapter.label + "</h2></center>");
        }
        return cleaned;
    }

    private MappedHtml slice(int spineIndex, FileText file, int from, int to) {
        return new MappedHtml().appendSource(spineIndex, file.full, file.body.start() + from, file.body.start() + to);
    }

    /** Whether chapters {@code i} and {@code j} (when both exist) come from the same spine file. */
    private static boolean sameFile(List<Chapter> list, int i, int j) {
        return i >= 0 && j < list.size() && list.get(i).spineIndex == list.get(j).spineIndex;
    }

    /** The {@code <} before {@code pos}; without one, two past the line start (as Moon+ computes it). */
    private static int tagStartBefore(String text, int pos) {
        int lt = text.lastIndexOf('<', pos);
        return lt != -1 ? lt : text.lastIndexOf('\n', pos) + 2;
    }

    private static boolean farIntoBody(int idPos, int bodyLength) {
        return idPos > FAR_INTO_BODY || (idPos > FAR_INTO_SHORT_BODY && idPos > bodyLength / 4);
    }

    /** Approximates Moon+'s {@code T.html2Text} / {@code Html.fromHtml} text, used only for its emptiness thresholds. */
    private static String plainText(String html) {
        return Jsoup.parseBodyFragment(html).text();
    }

    // ---- Files ----

    /** {@code [start, end)} in a file's text. */
    record BodyRange(int start, int end) {
    }

    /** A spine file's whole text and where Moon+'s {@code T.getHtmlBody} finds its body. */
    private record FileText(int spineIndex, String name, String full, BodyRange body) {

        String bodyText() {
            return full.substring(body.start(), body.end());
        }
    }

    private FileText file(int spineIndex) {
        return files.computeIfAbsent(spineIndex, this::readFile).orElseThrow(() -> new MissingFileException(spineIndex));
    }

    private Optional<FileText> readFile(int spineIndex) {
        String full;
        try {
            full = epub.spineContent(spineIndex);
        } catch (EpubContentException e) {
            return Optional.empty();
        }
        String path = EpubPackage.percentDecode(epub.spinePath(spineIndex));
        String name = EpubPackage.baseName(path);
        BodyRange body = htmlBody(full);
        return Optional.of(new FileText(spineIndex, name, full, body));
    }

    /** {@code T.getHtmlBody}: between {@code <body…>} and the next {@code </body>}, else inside {@code <html…>}, else all. */
    static BodyRange htmlBody(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.length() != html.length()) {
            lower = html;
        }
        BodyRange body = between(html, lower, "<body", "</body>");
        if (body == null) {
            body = between(html, lower, "<html", "</html>");
        }
        return body != null ? body : new BodyRange(0, html.length());
    }

    /** After the first {@code open…>} up to the next {@code close} (or the end); null without {@code open}. */
    private static @Nullable BodyRange between(String html, String lower, String open, String close) {
        int tag = lower.indexOf(open);
        if (tag == -1) {
            return null;
        }
        int gt = lower.indexOf('>', tag);
        int end = lower.indexOf(close, gt);
        return new BodyRange(gt + 1, end != -1 ? end : html.length());
    }

    private static final class MissingFileException extends EpubContentException {
        MissingFileException(int spineIndex) {
            super("spine file " + spineIndex + " can't be read");
        }
    }
}
