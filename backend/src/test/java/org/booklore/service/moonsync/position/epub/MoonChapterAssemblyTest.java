package org.booklore.service.moonsync.position.epub;

import org.booklore.service.moonsync.position.epub.MoonChapterAssembly.ChapterHtml;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Moon+'s chapter assembly: synthetic books for each rule of {@code Epub.getChaptersFromOpf} /
 * {@code getChapterTextWithIDTag} (decompiled 10.8), and the device fixtures (Moon+ on a real phone) for what Moon+ actually
 * showed: every probe's highlighted text must be in its chapter's split chunk, at about its captured offset.
 */
class MoonChapterAssemblyTest {

    /**
     * How far a plain tag-strip's offset may be from Moon+'s: at least 40 characters, or an eighth of the offset. The
     * strip counts paragraph breaks, images and {@code &nbsp;} lines differently from Moon+'s renderer (exact counting
     * belongs to the Moon+ renderer port); the worst case is Ulysses XV's table-set play script, where it runs ~11% short by the end of a
     * chunk. This checks the chapter and chunk assembly, not the counting.
     */
    private static int tolerance(int position) {
        return Math.max(40, position / 8);
    }

    @TempDir
    Path dir;

    private static String body(String content) {
        return "<html><head><title>t</title></head><body>" + content + "</body></html>";
    }

    private MoonChapterAssembly book(String ncxNavPoints, Map<String, String> files) {
        String opf = TestEpubs.opf(ncxNavPoints != null, files.keySet().toArray(String[]::new));
        return MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir, opf,
                ncxNavPoints == null ? null : TestEpubs.ncx(ncxNavPoints), files)));
    }

    private static String html(MoonChapterAssembly assembly, int chapter) {
        return assembly.chapter(chapter).orElseThrow(() -> new AssertionError("no chapter " + chapter)).html();
    }

    /** Spine files in the given order: name, content, name, content… */
    private static Map<String, String> files(String... nameThenContent) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < nameThenContent.length; i += 2) {
            files.put(nameThenContent[i], nameThenContent[i + 1]);
        }
        return files;
    }

    private static String np(String order, String src) {
        return TestEpubs.navPoint(order, "Chapter " + order, src);
    }

    @Test
    void withoutATrustedNcxEachSpineFileIsAChapter() {
        MoonChapterAssembly assembly = book(null, files("a.xhtml", body("<p>A</p>"), "b.xhtml", body("<p>B</p>")));
        assertThat(html(assembly, 0)).isEqualTo("<p>A</p>");
        assertThat(html(assembly, 1)).isEqualTo("<p>B</p>");
        assertThat(assembly.chapter(2)).isEmpty();
    }

    @Test
    void theLastChapterRunsToTheSpineEndRepeatingEveryFileWhenItPointsBack() {
        // Pinocchio's shape: the last navPoint ("Indice") targets a file early in the spine.
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : List.of("cover", "front", "index", "pre", "s1", "s2", "s3", "diz")) {
            files.put(name + ".xhtml", body("<p>" + name + "</p>"));
        }
        String ncx = np("1", "cover.xhtml") + np("2", "front.xhtml") + np("3", "s1.xhtml") + np("4", "s2.xhtml")
                + np("5", "s3.xhtml") + np("6", "diz.xhtml") + np("7", "index.xhtml");
        MoonChapterAssembly assembly = book(ncx, files);
        assertThat(assembly.unsupportedReason()).isEmpty();
        // "front" stops before index.xhtml: a later chapter's file comes sooner in the spine than the next chapter's.
        assertThat(html(assembly, 1)).isEqualTo("<p>front</p>");
        assertThat(html(assembly, 6)).isEqualTo("<p>index</p>"
                + "<a name=pre.xhtml><hr2><p>pre</p><a name=s1.xhtml><hr2><p>s1</p><a name=s2.xhtml><hr2><p>s2</p>"
                + "<a name=s3.xhtml><hr2><p>s3</p><a name=diz.xhtml><hr2><p>diz</p>");
    }

    @Test
    void anUnclaimedFileAfterAChapterIsAppendedToIt() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.xhtml", body("<p>a</p>"));
        files.put("notes.xhtml", body("<p>notes</p>"));
        files.put("b.xhtml", body("<p>b</p>"));
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "b.xhtml"), files);
        assertThat(html(assembly, 0)).isEqualTo("<p>a</p><a name=notes.xhtml><hr2><p>notes</p>");
        assertThat(html(assembly, 1)).isEqualTo("<p>b</p>");
    }

    @Test
    void frontMatterBeforeTheFirstAnchorIsChapterZero() {
        // Pride and Prejudice's shape: title page, then a file with a table of contents before chapter 1's anchor.
        String toc = "<h1>Table of Contents</h1>" + "<p><a href=\"#c\">Chapter n</a></p>".repeat(30);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("title.xhtml", body("<div><img src=\"cover.jpg\"/></div>"));
        files.put("split0.xhtml", body(toc + "<p><a id=\"ch1\"></a></p><h2>Chapter 1</h2><p>It is a truth</p>"));
        files.put("split1.xhtml", body("<h2>Chapter 2</h2>"));
        MoonChapterAssembly assembly = book(np("1", "split0.xhtml#ch1") + np("2", "split1.xhtml"), files);
        assertThat(assembly.unsupportedReason()).isEmpty();
        assertThat(html(assembly, 0)).isEqualTo("<a name=title.xhtml><div><img src=\"cover.jpg\"/></div><hr2>" + toc + "<p>");
        // Moon+ starts at the id's tag and steps back over the opening tags right before it: the <p> is in both.
        assertThat(html(assembly, 1)).isEqualTo("<p><a id=\"ch1\"></a></p><h2>Chapter 1</h2><p>It is a truth</p>");
        assertThat(html(assembly, 2)).isEqualTo("<h2>Chapter 2</h2>");
    }

    @Test
    void aLongHeadBeforeTheNextChaptersAnchorGoesToThePreviousChapter() {
        String head = "<p>" + "h".repeat(600) + "</p>";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.xhtml", body("<p>a</p>"));
        files.put("b.xhtml", body(head + "<h2 id=\"x\">X</h2>"));
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "b.xhtml#x"), files);
        assertThat(html(assembly, 0)).isEqualTo("<p>a</p><hr2>" + head);
        assertThat(html(assembly, 1)).isEqualTo("<h2 id=\"x\">X</h2>");
    }

    @Test
    void anAnchorNearTheTopOfANewFileStartsTheChapterAtTheFileStart() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.xhtml", body("<p>a</p>"));
        files.put("b.xhtml", body("<p>short head</p><h2 id=\"x\">X</h2>"));
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "b.xhtml#x"), files);
        assertThat(html(assembly, 0)).isEqualTo("<p>a</p>");
        assertThat(html(assembly, 1)).isEqualTo("<p>short head</p><h2 id=\"x\">X</h2>");
    }

    @Test
    void chaptersInOneFileEndWhereTheNextOneStarts() {
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "a.xhtml#p2") + np("3", "a.xhtml#p3"),
                files("a.xhtml", body("<p>one</p><div><h2 id=\"p2\">two</h2></div><h2 id=\"p3\">three</h2>")));
        // A chapter ends at the next id's own tag, the next starts back over the opening tags before it.
        assertThat(html(assembly, 0)).isEqualTo("<p>one</p><div>");
        assertThat(html(assembly, 1)).isEqualTo("<div><h2 id=\"p2\">two</h2></div>");
        assertThat(html(assembly, 2)).isEqualTo("<h2 id=\"p3\">three</h2>");
    }

    @Test
    void commentsAreRemovedAndAnEmptyIdChapterBecomesItsTitle() {
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "a.xhtml#p2") + np("3", "b.xhtml"),
                files("a.xhtml", body("<p>one<!-- c --></p><span id=\"p2\"></span>"), "b.xhtml", body("<p>b</p>")));
        assertThat(html(assembly, 0)).isEqualTo("<p>one</p>");
        assertThat(html(assembly, 1)).isEqualTo("<center><h2>Chapter 2</h2></center>");
    }

    @Test
    void piecesTraceEveryCharacterBackToItsSpineFile() {
        String head = "<p>" + "h".repeat(600) + "</p>";
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "b.xhtml#x"), files(
                "a.xhtml", body("<p>a<!-- gone --> text</p>"),
                "notes.xhtml", body("<p>notes</p>"),
                "b.xhtml", body(head + "<h2 id=\"x\">x</h2>")));
        EpubPackage epub = EpubPackage.read(dir.resolve("book.epub"));
        ChapterHtml first = assembly.chapter(0).orElseThrow();
        assertThat(first.html()).isEqualTo("<p>a text</p><a name=notes.xhtml><hr2><p>notes</p><hr2>" + head);
        for (int chapter = 0; chapter < 2; chapter++) {
            ChapterHtml html = assembly.chapter(chapter).orElseThrow();
            int covered = 0;
            for (MappedHtml.Piece piece : html.pieces()) {
                assertThat(piece.at()).isEqualTo(covered);
                covered = piece.end();
                if (!piece.synthetic()) {
                    String source = epub.spineContent(piece.spineIndex());
                    assertThat(html.html().substring(piece.at(), piece.end()))
                            .isEqualTo(source.substring(piece.sourceStart(), piece.sourceStart() + piece.length()));
                }
            }
            assertThat(covered).isEqualTo(html.html().length());
        }
    }

    @Test
    void numberingConflictsWithReadsyncAreUnsupportedNotGuessed() {
        // A first anchor right at the top: readsync counts uncovered content as chapter 0, Moon+ makes no chapter of it.
        MoonChapterAssembly assembly = book(np("1", "a.xhtml#top") + np("2", "b.xhtml"),
                files("a.xhtml", body("<h1 id=\"top\">A</h1>"), "b.xhtml", body("<p>b</p>")));
        assertThat(assembly.unsupportedReason()).hasValueSatisfying(r -> assertThat(r).contains("chapter 0"));
        assertThat(assembly.chapter(1)).isEmpty();
    }

    @Test
    void chaptersAfterANavPointMoonDropsAreNotGuessed() {
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "missing.xhtml") + np("3", "b.xhtml"),
                files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>")));
        assertThat(assembly.chapter(0)).isPresent();
        assertThat(assembly.chapter(2)).isEmpty(); // Moon+ calls it chapter 1
    }

    @Test
    void aNavPointIntoAFileOutsideTheSpineKeepsItsNumberAndStopsTheChapterBefore() {
        // Moon+ finds x.xhtml in the zip and keeps the navPoint; its spine index is -1, so the chapter before it gets no
        // following files (checkChapterAdditionalText returns early).
        Map<String, String> files = files("a.xhtml", body("<p>a</p>"), "notes.xhtml", body("<p>notes</p>"),
                "b.xhtml", body("<p>b</p>"));
        String opf = TestEpubs.opf(true, "a.xhtml", "notes.xhtml", "b.xhtml");
        Map<String, String> zip = new LinkedHashMap<>(files);
        zip.put("x.xhtml", body("<p>x</p>"));
        MoonChapterAssembly assembly = MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir, opf,
                TestEpubs.ncx(np("1", "a.xhtml") + np("2", "x.xhtml") + np("3", "b.xhtml")), zip)));
        assertThat(html(assembly, 0)).isEqualTo("<p>a</p>");
        assertThat(assembly.chapter(1)).isEmpty();
        assertThat(html(assembly, 2)).isEqualTo("<p>b</p>");
    }

    @Test
    void anIdChapterGivesTheSimilarIdItCutsAtToTheNextChapterInItsFile() {
        // getChapterTextWithIDTag: the next chapter is in the same file without an id, so Moon+ cuts at the next
        // "id=\"p…" and from then on starts the next chapter there.
        String head = "<p>" + "h".repeat(600) + "</p>";
        MoonChapterAssembly assembly = book(np("1", "b.xhtml") + np("2", "a.xhtml#p1") + np("3", "a.xhtml"), files(
                "b.xhtml", body("<p>b</p>"),
                "a.xhtml", body(head + "<h2 id=\"p1\">one</h2><p>x</p><h2 id=\"p2\">two</h2><p>y</p>")));
        assertThat(html(assembly, 1)).isEqualTo("<h2 id=\"p1\">one</h2><p>x</p>");
        assertThat(html(assembly, 2)).isEqualTo("<h2 id=\"p2\">two</h2><p>y</p>");
    }

    @Test
    void chaptersMoonDropsAsBrokenMakeTheBookUnsupported() {
        // deleteBrokenChapters: more than two ID chapters whose id isn't found, after a chapter in the same file, are
        // removed in a small book, which renumbers everything after them.
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "a.xhtml#m1") + np("3", "a.xhtml#m2")
                + np("4", "a.xhtml#m3") + np("5", "b.xhtml"), files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>")));
        assertThat(assembly.unsupportedReason()).hasValueSatisfying(r -> assertThat(r).contains("deleteBrokenChapters"));
    }

    @Test
    void anIdIsLookedUpPercentDecodedAsMoonDecodesTheWholeSrc() {
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "a.xhtml#a%20b"),
                files("a.xhtml", body("<p>one</p><h2 id=\"a b\">two</h2>")));
        assertThat(html(assembly, 0)).isEqualTo("<p>one</p>");
        assertThat(html(assembly, 1)).isEqualTo("<h2 id=\"a b\">two</h2>");
    }

    @Test
    void anNcxWithOnlyOneResolvingNavPointIsUnsupported() {
        // Moon+ then numbers chapters by the spine (getChaptersFromOpf: fewer than 2 NCX chapters).
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + np("2", "gone.xhtml") + np("3", "lost.xhtml"),
                files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>"), "c.xhtml", body("<p>c</p>")));
        assertThat(assembly.unsupportedReason()).isPresent();
    }

    @Test
    void aNavPointEndingInABareHashIsUnsupported() {
        MoonChapterAssembly assembly = book(np("1", "a.xhtml#") + np("2", "b.xhtml"),
                files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>")));
        assertThat(assembly.unsupportedReason()).hasValueSatisfying(r -> assertThat(r).contains("empty"));
    }

    @Test
    void aNavPointWithoutContentDoesNotCountAsABareHash() {
        String noContent = "<navPoint id=\"x\" playOrder=\"9\"><navLabel><text>x</text></navLabel><content/></navPoint>";
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + noContent + np("2", "b.xhtml"),
                files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>")));
        assertThat(assembly.unsupportedReason()).isEmpty();
        assertThat(html(assembly, 1)).isEqualTo("<p>b</p>");
    }

    @Test
    void aNavPointWhoseLabelHasNoTextIsUnsupported() {
        // getCName answers null for <text/>, and getChapter then skips the navPoint, renumbering the rest.
        String empty = "<navPoint id=\"e\" playOrder=\"2\"><navLabel><text/></navLabel><content src=\"b.xhtml\"/></navPoint>";
        MoonChapterAssembly assembly = book(np("1", "a.xhtml") + empty + np("3", "c.xhtml"),
                files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>"), "c.xhtml", body("<p>c</p>")));
        assertThat(assembly.unsupportedReason()).hasValueSatisfying(r -> assertThat(r).contains("label"));
    }

    @Test
    void aBookWhereMoonPicksAnotherNcxByNameIsUnsupported() {
        // The OPF names toc.ncx, but the zip also holds a second toc.ncx: Moon+'s by-name pick is ambiguous.
        Map<String, String> zip = files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>"),
                "old/toc.ncx", TestEpubs.ncx(np("1", "b.xhtml")));
        MoonChapterAssembly assembly = MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir,
                TestEpubs.opf(true, "a.xhtml", "b.xhtml"), TestEpubs.ncx(np("1", "a.xhtml") + np("2", "b.xhtml")), zip)));
        assertThat(assembly.unsupportedReason()).hasValueSatisfying(r -> assertThat(r).contains("NCX"));
    }

    @Test
    void anNcxTheOpfDoesNotNameStillNumbersTheBookForMoon() {
        // No NCX in the OPF (readsync: spine order), but a toc.ncx with chapters in the zip: Moon+ reads that one.
        Map<String, String> zip = files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>"), "c.xhtml", body("<p>c</p>"),
                "toc.ncx", TestEpubs.ncx(np("1", "b.xhtml") + np("2", "c.xhtml")));
        MoonChapterAssembly assembly = MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir,
                TestEpubs.opf(false, "a.xhtml", "b.xhtml", "c.xhtml"), null, zip)));
        assertThat(assembly.unsupportedReason()).isPresent();
    }

    @Test
    void anNcxWithoutItsNamespaceIsReadByMoonButNotByReadsync() {
        String ncx = TestEpubs.ncx(np("1", "a.xhtml") + np("2", "b.xhtml")).replace(" xmlns=\"http://www.daisy.org/z3986/2005/ncx/\"", "");
        MoonChapterAssembly assembly = MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir,
                TestEpubs.opf(true, "a.xhtml", "b.xhtml", "c.xhtml"), ncx,
                files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>"), "c.xhtml", body("<p>c</p>")))));
        assertThat(assembly.unsupportedReason()).hasValueSatisfying(r -> assertThat(r).contains("can't read"));
    }

    @Test
    void anUpperCaseNcxNameIsTheOneMoonPicks() {
        Map<String, String> zip = files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>"),
                "TOC.NCX", TestEpubs.ncx(np("1", "a.xhtml") + np("2", "b.xhtml")));
        MoonChapterAssembly assembly = MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir,
                TestEpubs.opf(true, "a.xhtml", "b.xhtml").replace("href=\"toc.ncx\"", "href=\"TOC.NCX\""), null, zip)));
        assertThat(assembly.unsupportedReason()).isEmpty();
        assertThat(html(assembly, 1)).isEqualTo("<p>b</p>");
    }

    @Test
    void anNcxMoonReadsDifferentlyIsUnsupported() {
        Map<String, String> files = files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<p>b</p>"));
        for (String odd : List.of(
                "<pageList>" + np("9", "a.xhtml") + "</pageList>",           // navPoint outside navMap/navPoint
                "<navPoint playOrder=\"9\"><content href=\"a.xhtml\"/></navPoint>", // content without src
                np("9", "a%23x.xhtml"),                                        // %23 Moon+ decodes into an id
                "<navPoint playOrder=\"9\"><content src=\"a.xhtml\"/><content src=\"b.xhtml\"/></navPoint>")) { // several contents
            MoonChapterAssembly assembly = book(np("1", "a.xhtml") + odd + np("2", "b.xhtml"), files);
            assertThat(assembly.unsupportedReason()).as(odd).hasValueSatisfying(r -> assertThat(r).contains("differently"));
        }
        assertThat(book(np("1", "a.xhtml") + np("2", "b%20c.xhtml#x"),
                files("a.xhtml", body("<p>a</p>"), "b c.xhtml", body("<h2 id=\"x\">x</h2>"))).unsupportedReason())
                .as("an encoded space is not a #").isEmpty();
    }

    private MoonChapterAssembly bookWithNav(String navItems) {
        String opf = TestEpubs.opf(true, "a.xhtml", "b.xhtml").replace("</manifest>",
                "  <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest>");
        String nav = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">"
                + "<body><nav epub:type=\"toc\"><ol>" + navItems + "</ol></nav></body></html>";
        return MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir, opf, TestEpubs.ncx(np("1", "a.xhtml") + np("2", "b.xhtml")),
                files("a.xhtml", body("<p>a</p>"), "b.xhtml", body("<h2 id=\"x\">b</h2>"), "nav.xhtml", nav))));
    }

    @Test
    void aTrustedNcxNextToANavThatDiffersIsUnsupported() {
        // getChapters: Moon+ re-reads the chapters from the nav and keeps them unless there are fewer.
        assertThat(bookWithNav("<li><a href=\"a.xhtml\">A</a></li><li><a href=\"b.xhtml\">B</a></li>"
                + "<li><a href=\"b.xhtml#x\">X</a></li>").unsupportedReason())
                .hasValueSatisfying(r -> assertThat(r).contains("nav"));
        assertThat(bookWithNav("<li><a href=\"a.xhtml\">A</a><ol><li><a href=\"b.xhtml\">B</a></li></ol></li>").unsupportedReason())
                .as("the same entries, nested").isEmpty();
        assertThat(bookWithNav("<li><a href=\"a.xhtml\">A</a></li>").unsupportedReason())
                .as("a shorter nav: Moon+ keeps the NCX").isEmpty();
        assertThat(bookWithNav("<li><a href=\"a.xhtml\">Pride & Prejudice</a></li><li><a href=\"b.xhtml\">B</a></li>")
                .unsupportedReason()).as("a bare & Moon+ repairs (getDocument)").hasValueSatisfying(r -> assertThat(r).contains("&"));
    }

    @Test
    void whenMoonTakesTheNavItTakesItsLabels() {
        // Same entries as the NCX, so Moon+'s chapters come from the nav: an empty chapter shows the nav's label.
        String opf = TestEpubs.opf(true, "a.xhtml", "b.xhtml").replace("</manifest>",
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest>");
        String nav = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><nav><ol><li><a href=\"a.xhtml\">One</a></li>"
                + "<li><a href=\"a.xhtml#p2\">Nav two</a></li><li><a href=\"b.xhtml\">B</a></li></ol></nav></body></html>";
        MoonChapterAssembly assembly = MoonChapterAssembly.of(EpubPackage.read(TestEpubs.epub(dir, opf,
                TestEpubs.ncx(np("1", "a.xhtml") + np("2", "a.xhtml#p2") + np("3", "b.xhtml")),
                files("a.xhtml", body("<p>one</p><span id=\"p2\"></span>"), "b.xhtml", body("<p>b</p>"), "nav.xhtml", nav))));
        assertThat(html(assembly, 1)).isEqualTo("<center><h2>Nav two</h2></center>");
    }

    @Test
    void anEpub3NavBookIsUnsupported() {
        Path book = TestEpubs.epub(dir, TestEpubs.opf(false, "a.xhtml")
                .replace("media-type=\"application/xhtml+xml\"/>", "media-type=\"application/xhtml+xml\" properties=\"nav\"/>"),
                null, files("a.xhtml", body("<p>a</p>")));
        assertThat(MoonChapterAssembly.of(EpubPackage.read(book)).unsupportedReason()).isPresent();
    }

    // --- Device fixtures (Moon+ 10.8 on a real phone) ---

    @ParameterizedTest
    @ValueSource(strings = {"pride-and-prejudice", "ulysses", "pinocchio"})
    void everyProbeIsInItsChapterAndChunkAtAboutItsOffset(String fixtureName) {
        JsonNode fixture = TestEpubs.deviceFixture(fixtureName);
        Path book = TestEpubs.deviceBook(fixture).orElse(null);
        assumeThat(book).as("%s (not committed; set MOON_LOCAL_BOOKS to a folder holding it)",
                fixture.at("/book/moonFileName").asString()).isNotNull();
        EpubPackage epub = EpubPackage.read(book);
        MoonChapterAssembly assembly = MoonChapterAssembly.of(epub);
        assertThat(assembly.unsupportedReason()).isEmpty();
        List<String> failures = new ArrayList<>();
        int probes = 0;
        for (JsonNode capture : fixture.get("captures")) {
            String kind = capture.get("kind").asString();
            if (!kind.equals("probe") && !kind.equals("po")) {
                continue;
            }
            JsonNode parsed = capture.get("parsed");
            int chapter = parsed.get("chapter").asInt();
            int split = parsed.get("split").asInt();
            ChapterHtml html = assembly.chapter(chapter).orElse(null);
            if (html == null) {
                failures.add(capture.get("id").asString() + ": no chapter " + chapter);
                continue;
            }
            List<Integer> chunks = MoonSplit.rawBoundaries(html.html());
            if (split >= chunks.size() - 1) {
                failures.add(capture.get("id").asString() + ": chapter " + chapter + " has " + (chunks.size() - 1) + " chunks");
                continue;
            }
            if (!kind.equals("probe")) {
                continue;
            }
            probes++;
            String chunkText = normalize(Jsoup.parseBodyFragment(html.html().substring(chunks.get(split), chunks.get(split + 1))).text());
            String probe = normalize(capture.get("text").asString().replace("<BR>", " "));
            int position = parsed.get("position").asInt();
            int found = closest(chunkText, probe, position);
            if (found < 0) {
                failures.add(capture.get("id").asString() + ": '" + probe + "' not in chapter " + chapter + " chunk " + split);
            } else if (Math.abs(found - position) > tolerance(position)) {
                failures.add(capture.get("id").asString() + ": '" + probe + "' at " + found + ", Moon+ " + position);
            }
        }
        assertThat(failures).as("%d probes", probes).isEmpty();
        assertThat(probes).isPositive();
    }

    @Test
    void pinocchiosLastChapterRepeatsTheBookAndSplitsIntoThreeChunks() {
        JsonNode fixture = TestEpubs.deviceFixture("pinocchio");
        Path book = TestEpubs.deviceBook(fixture).orElse(null);
        assumeThat(book).as("Pinocchio (not committed; set MOON_LOCAL_BOOKS)").isNotNull();
        ChapterHtml indice = MoonChapterAssembly.of(EpubPackage.read(book)).chapter(40).orElseThrow();
        // indice.xhtml, then every spine file after it: pre, the 36 chapters, the glossary and the series list.
        assertThat(indice.pieces().stream().filter(p -> !p.synthetic()).map(MappedHtml.Piece::spineIndex).toList())
                .isEqualTo(IntStream.range(3, 43).boxed().toList());
        assertThat(MoonSplit.chunkCount(indice.html())).isEqualTo(3); // end of book: 40@2#65889
    }

    private static String normalize(String text) {
        return text.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    private static int closest(String text, String needle, int near) {
        int best = -1;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            if (best < 0 || Math.abs(i - near) < Math.abs(best - near)) {
                best = i;
            }
        }
        return best;
    }
}
