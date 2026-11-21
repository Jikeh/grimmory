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
 * <p>Not ported, so reported as unsupported rather than guessed: books Moon+ reads through an EPUB 3 nav document
 * instead of the NCX (no NCX, or an untrusted one, next to a declared nav), a one-navPoint NCX over at most two
 * spine files (Moon+ keeps it as its only chapter), and any chapter whose Moon+ number would differ from readsync's
 * (front matter one side counts and the other doesn't, or a navPoint Moon+ drops). Also not ported: Moon+ choosing
 * the nav over the NCX when both exist (the two normally agree), finding the NCX by file name, its tours and
 * single-chapter splitting ({@code trySplitChapters}), and dropping broken chapters ({@code deleteBrokenChapters}).
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
                if (i != number) {
                    return Optional.empty(); // Moon+ would call it chapter i
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

    /** A Moon+ chapter. {@code number} is readsync's; its position in {@link #chapters} is Moon+'s. */
    private static final class Chapter {
        final int number;
        final int spineIndex;
        @Nullable String fragment;
        final int depth;
        final String label;
        boolean hasSubChapter;
        final Set<Integer> usedFiles = new HashSet<>();
        /** Files appended eagerly ({@code additionalText}), in order. */
        final List<Integer> additional = new ArrayList<>();
        /** {@code #MORE#_from|to}: every spine file strictly between, appended when the chapter is read. */
        int lazyFrom = -1;
        int lazyTo = -1;
        /** Front matter Moon+ inserts as chapter 0, already assembled. */
        @Nullable MappedHtml direct;

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
        if (!epub.ncxTrusted()) {
            int navPoints = epub.navPoints().map(List::size).orElse(0);
            if (epub.declaresNavDocument()) {
                return "Moon+ reads this book's chapters from its EPUB 3 nav document (not ported)";
            }
            if (navPoints == 1 && epub.spineLength() <= 2) {
                return "a one-navPoint NCX over at most two spine files: Moon+ keeps the navPoint as its only chapter";
            }
            for (int i = 0; i < epub.spineLength(); i++) {
                list.add(new Chapter(i, i, null, 0, ""));
            }
            return null;
        }
        List<NavPoint> navPoints = epub.navPoints().orElseThrow();
        int leading = readsyncLeadingOffset(navPoints.getFirst());
        boolean treeToc = false;
        boolean hasIdStr = false;
        for (int position = 0; position < navPoints.size(); position++) {
            NavPoint navPoint = navPoints.get(position);
            treeToc |= navPoint.depth() > 0;
            hasIdStr |= navPoint.fragment() != null;
            OptionalInt spineIndex = epub.ncxSpineIndex(navPoint.href());
            if (spineIndex.isPresent()) {
                list.add(new Chapter(position + leading, spineIndex.getAsInt(), navPoint.fragment(), navPoint.depth(), navPoint.label()));
            }
        }
        if (list.isEmpty()) {
            return "no navPoint resolves to a spine file";
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
        if (hasIdStr && list.size() > firstChapterPos) {
            MappedHtml head = contentBeforeFirstTag(list.get(firstChapterPos));
            if (head != null) {
                if (firstChapterPos == 0) {
                    Chapter front = new Chapter(0, list.getFirst().spineIndex, null, 0, "");
                    front.direct = head;
                    list.addFirst(front);
                    inserted = true;
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
        return null;
    }

    /** readsync's {@code _leading_offset}: the numbering {@link MoonChapterMap} uses. */
    private int readsyncLeadingOffset(NavPoint first) {
        if (first.fragment() != null) {
            return 1;
        }
        OptionalInt spineIndex = epub.ncxSpineIndex(first.href());
        return spineIndex.isPresent() && spineIndex.getAsInt() > 0 ? 1 : 0;
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
            if (to - from > 1) {
                boolean lazy = to - from > 3;
                for (int file = from + 1; file < to && file < epub.spineLength(); file++) {
                    if (claimed(list, file)) {
                        continue;
                    }
                    chapter.usedFiles.add(file);
                    if (!lazy) {
                        chapter.additional.add(file);
                    } else if (chapter.lazyFrom < 0) {
                        chapter.lazyFrom = from;
                        chapter.lazyTo = to;
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
        if (idPos <= 100) {
            return null;
        }
        return textBeforeTag(file, idPos, 40);
    }

    // ---- One chapter's text (getChapterText, getChapterTextWithIDTag, getMoreContentFromNextID_Tag) ----

    private MappedHtml text(int i) {
        Chapter chapter = chapters.get(i);
        if (chapter.direct != null) {
            return chapter.direct;
        }
        if (chapter.fragment == null && !nextHasIdTag(i)) {
            if (chapter.hasSubChapter && i < chapters.size() - 1 && chapters.get(i + 1).spineIndex == chapter.spineIndex) {
                return new MappedHtml();
            }
            FileText file = file(chapter.spineIndex);
            return new MappedHtml().appendSource(chapter.spineIndex, file.full, file.bodyStart, file.bodyEnd)
                    .append(additional(chapter));
        }
        MappedHtml text = textWithIdTag(i);
        if (nextHasIdTag(i) && chapters.get(i + 1).spineIndex != chapter.spineIndex) {
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
        String text = file.body();
        String id = chapter.fragment;
        String nextId = i < chapters.size() - 1 ? chapters.get(i + 1).fragment : null;
        int idPos = id == null ? 0 : idPosition(file, id);
        if (idPos > 0 && i > 0 && chapters.get(i - 1).spineIndex != chapter.spineIndex) {
            if (idPos > 500 || (idPos > 100 && idPos > text.length() / 4)) {
                if (textBeforeTag(file, idPos, 20) == null) {
                    idPos = 0;
                }
            } else {
                idPos = 0;
            }
        }
        if (idPos == -1) {
            if (i > 0 && !chapters.get(i - 1).hasSubChapter && chapters.get(i - 1).spineIndex == chapter.spineIndex) {
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
        if (i < chapters.size() - 1 && chapters.get(i + 1).spineIndex == chapter.spineIndex) {
            int nextPos = nextId != null ? idPosInHtml(text, nextId, idPos) : -1;
            if (nextPos == -1) {
                nextPos = similarIdPosition(text, id, idPos);
            }
            if (nextPos != -1) {
                end = text.lastIndexOf('<', nextPos);
                if (end == -1) {
                    end = text.lastIndexOf('\n', nextPos) + 2;
                }
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
    private static int similarIdPosition(String text, @Nullable String id, int from) {
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
        return found == -1 ? text.indexOf(" id=", from) : found;
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
        String text = file.body();
        if (idPos > 0 && j > 0 && chapters.get(j - 1).spineIndex != chapter.spineIndex
                && (idPos > 500 || (idPos > 100 && idPos > text.length() / 4))) {
            return textBeforeTag(file, idPos, 20);
        }
        return null;
    }

    /**
     * {@code getIdPosInHtml} on the body; when the id is only found elsewhere in the file (e.g. on {@code <body>}),
     * the body's start.
     */
    private static int idPosition(FileText file, String id) {
        int pos = idPosInHtml(file.body(), id, 0);
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
        String text = file.body();
        int start = text.indexOf("<body");
        start = start == -1 ? 0 : text.indexOf('>', start) + 1;
        int end = text.lastIndexOf('<', pos);
        if (end == -1) {
            end = text.lastIndexOf('\n', pos) + 2;
        }
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
        if (chapter.lazyFrom >= 0) {
            for (int file = chapter.lazyFrom + 1; file < chapter.lazyTo && file < epub.spineLength(); file++) {
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
        String body = file.body();
        boolean blank = body.length() <= 100 && plainText(body).isBlank();
        if (spineIndex > 0 && !blank) {
            html.appendSynthetic(PAGE_BREAK);
        }
        html.appendSource(spineIndex, file.full, file.bodyStart, file.bodyEnd);
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
        if (html.length() < 200 && !html.contains("<im") && !html.contains(chapter.label) && plainText(html).isBlank()) {
            return new MappedHtml().appendSynthetic("<center><h2>" + chapter.label + "</h2></center>");
        }
        return cleaned;
    }

    private MappedHtml slice(int spineIndex, FileText file, int from, int to) {
        return new MappedHtml().appendSource(spineIndex, file.full, file.bodyStart + from, file.bodyStart + to);
    }

    /** Approximates Moon+'s {@code T.html2Text} / {@code Html.fromHtml} text, used only for its emptiness thresholds. */
    private static String plainText(String html) {
        return Jsoup.parseBodyFragment(html).text().strip();
    }

    // ---- Files ----

    /** A spine file's whole text and where Moon+'s {@code T.getHtmlBody} finds its body. */
    private record FileText(int spineIndex, String name, String full, int bodyStart, int bodyEnd) {

        String body() {
            return full.substring(bodyStart, bodyEnd);
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
        String name = path.substring(path.lastIndexOf('/') + 1);
        int[] body = htmlBody(full);
        return Optional.of(new FileText(spineIndex, name, full, body[0], body[1]));
    }

    /** {@code T.getHtmlBody}: between {@code <body…>} and the next {@code </body>}, else inside {@code <html…>}, else all. */
    static int[] htmlBody(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.length() != html.length()) {
            lower = html;
        }
        int body = lower.indexOf("<body");
        if (body != -1) {
            int gt = lower.indexOf('>', body);
            int close = lower.indexOf("</body>", gt);
            return close != -1 ? new int[]{gt + 1, close} : new int[]{gt + 1, html.length()};
        }
        int root = lower.indexOf("<html");
        if (root != -1) {
            int gt = lower.indexOf('>', root);
            int close = lower.indexOf("</html>", gt);
            return close != -1 ? new int[]{gt + 1, close} : new int[]{gt + 1, html.length()};
        }
        return new int[]{0, html.length()};
    }

    private static final class MissingFileException extends RuntimeException {
        MissingFileException(int spineIndex) {
            super("spine file " + spineIndex + " can't be read");
        }
    }
}
