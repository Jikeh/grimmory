package org.booklore.service.moonsync.position.epub;

import org.booklore.service.moonsync.position.epub.MoonChapterMap.ChapterAnchor;
import org.booklore.service.moonsync.position.epub.MoonChapterMap.SpineAnchor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Ported from readsync {@code tests/unit/test_epub_ncx.py}, same inputs and expectations: the real Dorian Gray
 * (trusted NCX) and 1984 (stub NCX) books, whose coordinates come from readsync's
 * {@code dorian_gray_coordinates.json} and {@code 1984_epub_ncx_coordinates.json}, and the synthetic books for the
 * single-chunk guard and document-order keying.
 */
class MoonChapterMapTest {

    private static final String SMALL_CHAPTER =
            "<html><body><p>Part one text.</p><h2 id=\"part2\">Part two</h2><p>Part two text.</p></body></html>";
    private static final String LONG_CHAPTER =
            "<html><body><p>" + "x".repeat(150_000) + "</p><h2 id=\"part2\">Part two</h2><p>tail</p></body></html>";

    @TempDir
    Path dir;

    private static MoonChapterMap dorianGray() {
        Path book = TestEpubs.readsyncFixture("dorian_gray.epub").orElse(null);
        assumeThat(book).as("readsync's dorian_gray.epub (set MOON_READSYNC_FIXTURES)").isNotNull();
        return new MoonChapterMap(EpubPackage.read(book));
    }

    private static MoonChapterMap book1984() {
        Path book = TestEpubs.readsyncFixture("1984.epub").orElse(null);
        assumeThat(book).as("readsync's 1984.epub (set MOON_READSYNC_FIXTURES)").isNotNull();
        return new MoonChapterMap(EpubPackage.read(book));
    }

    /** One spine file, two navPoints, the second at {@code #part2} (readsync {@code _merged_chapter_epub}). */
    private MoonChapterMap mergedChapter(String body, String secondPlayOrder) {
        String ncx = TestEpubs.ncx(TestEpubs.navPoint("1", "Part one", "chapter1.xhtml")
                + TestEpubs.navPoint(secondPlayOrder, "Part two", "chapter1.xhtml#part2"));
        return new MoonChapterMap(EpubPackage.read(TestEpubs.epub(dir, TestEpubs.opf(true, "chapter1.xhtml"), ncx,
                Map.of("chapter1.xhtml", body))));
    }

    // --- Real fixture: Dorian Gray ---

    @Test
    void realDorianGrayChapter22ResolvesToTheContent0005Anchor() {
        assertThat(dorianGray().resolveChapter(22)).hasValue(new SpineAnchor(3, 72940));
    }

    @Test
    void realDorianGrayReverseLookupRecoversChapter22() {
        assertThat(dorianGray().resolveSpinePosition(3, 72945)).hasValue(new ChapterAnchor(22, 72940));
    }

    @Test
    void realDorianGrayChaptersInSplitFilesDegradeToNothingNotAGuess() {
        // content0001.xhtml (chapters 1-10) is split into 3 Moon+ chunks, and the NCX is trusted, so these never fall
        // back to a literal spine index (the Cloud Atlas bug: ~77% through the book instead of ~20%).
        MoonChapterMap map = dorianGray();
        assertThat(map.resolveChapter(1)).isEmpty();
        assertThat(map.resolveChapter(2)).isEmpty();
        assertThat(map.resolveChapter(3)).isEmpty();
        assertThat(map.resolveChapter(11)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 0", "2, 1, 64", "3, 1, 10302", "11, 2, 0", "17, 2, 177922", "18, 3, 0", "22, 3, 72940"})
    void realDorianGrayRelaxedResolutionRecoversSplitFileChapters(int chapter, int spine, int anchor) {
        assertThat(dorianGray().resolveChapter(chapter, true)).hasValue(new SpineAnchor(spine, anchor));
    }

    @Test
    void relaxingTheGuardNeverLeaksIntoTheReverseDirection() {
        assertThat(dorianGray().resolveSpinePosition(1, 10_400)).isEmpty();
    }

    // --- Real fixture: 1984 (stub NCX, the no-regression proof) ---

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void real1984EveryRealisticChapterIsItsSpineIndex(int chapter) {
        assertThat(book1984().resolveChapter(chapter)).hasValue(new SpineAnchor(chapter, 0));
    }

    @Test
    void real1984NcxIsNeverTrusted() {
        MoonChapterMap map = book1984();
        map.resolveChapter(0);
        assertThat(map.epub().ncxTrusted()).isFalse();
        assertThat(map.chapters()).isEmpty();
    }

    // --- Synthetic ---

    @Test
    void chapterIndexKeysOnDocumentPositionNotThePlayOrderAttribute() {
        // Book 262's real NCX numbers its navPoints 1, 1, 2, 3, 4, 7, 8, 22, 46, …; position is the key.
        MoonChapterMap map = mergedChapter(SMALL_CHAPTER, "46");
        assertThat(map.resolveChapter(0)).hasValue(new SpineAnchor(0, 0));
        assertThat(map.resolveChapter(1)).hasValue(new SpineAnchor(0, "Part one text.".length()));
        assertThat(map.resolveChapter(46)).isEmpty();
    }

    @Test
    void singleChunkMergedChapterResolvesThroughTheNcx() {
        MoonChapterMap map = mergedChapter(SMALL_CHAPTER, "2");
        assertThat(map.resolveChapter(0)).hasValue(new SpineAnchor(0, 0));
        assertThat(map.resolveChapter(1)).hasValue(new SpineAnchor(0, "Part one text.".length()));
    }

    @Test
    void reverseLookupRecoversTheChapterFromAPosition() {
        MoonChapterMap map = mergedChapter(SMALL_CHAPTER, "2");
        int anchor = "Part one text.".length();
        assertThat(map.resolveSpinePosition(0, anchor + 3)).hasValue(new ChapterAnchor(1, anchor));
        assertThat(map.resolveSpinePosition(0, 2)).hasValue(new ChapterAnchor(0, 0));
    }

    @Test
    void aSplitSpineFileIsNeverTrustedForNcxResolution() {
        MoonChapterMap map = mergedChapter(LONG_CHAPTER, "2");
        assertThat(map.resolveChapter(2)).isEmpty();
        assertThat(map.resolveChapter(1)).isEmpty();
        assertThat(map.resolveChapter(0)).isEmpty();
        assertThat(map.resolveChapter(1, true)).isPresent();
    }

    @Test
    void aGuardedChapterNeverCollidesWithADifferentValidSpineIndex() {
        // The Cloud Atlas shape: chapter 1's real target is the split chapter2.xhtml (spine 2), but 1 is also a valid,
        // unrelated spine index. 4 navPoints over 3 files: trusted.
        String ncx = TestEpubs.ncx(TestEpubs.navPoint("1", "split", "chapter2.xhtml")
                + TestEpubs.navPoint("2", "same split file", "chapter2.xhtml#part2")
                + TestEpubs.navPoint("3", "ordinary", "chapter0.xhtml")
                + TestEpubs.navPoint("4", "ordinary too", "chapter1.xhtml"));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("chapter0.xhtml", "<html><body><p>an ordinary chapter</p></body></html>");
        files.put("chapter1.xhtml", "<html><body><p>another ordinary chapter</p></body></html>");
        files.put("chapter2.xhtml", LONG_CHAPTER);
        MoonChapterMap map = new MoonChapterMap(EpubPackage.read(TestEpubs.epub(dir,
                TestEpubs.opf(true, "chapter0.xhtml", "chapter1.xhtml", "chapter2.xhtml"), ncx, files)));
        assertThat(map.resolveChapter(1)).isEmpty();
        assertThat(map.resolveChapter(2)).isEmpty();
        assertThat(map.resolveChapter(3)).hasValue(new SpineAnchor(0, 0));
        assertThat(map.resolveChapter(4)).hasValue(new SpineAnchor(1, 0));
    }

    @Test
    void aNavPointWhoseTargetIsMissingKeepsItsNumber() {
        String ncx = TestEpubs.ncx(TestEpubs.navPoint("1", "one", "a.xhtml") + TestEpubs.navPoint("2", "gone", "missing.xhtml")
                + TestEpubs.navPoint("3", "no anchor", "a.xhtml#nowhere") + TestEpubs.navPoint("4", "two", "b.xhtml"));
        MoonChapterMap map = new MoonChapterMap(EpubPackage.read(TestEpubs.epub(dir, TestEpubs.opf(true, "a.xhtml", "b.xhtml"), ncx,
                Map.of("a.xhtml", "<p>a</p>", "b.xhtml", "<p>b</p>"))));
        assertThat(map.resolveChapter(1)).isEmpty();
        assertThat(map.resolveChapter(2)).isEmpty();
        assertThat(map.resolveChapter(3)).hasValue(new SpineAnchor(1, 0));
    }

    @Test
    void untrustedBookChapterIsTheSpineIndex() {
        MoonChapterMap map = new MoonChapterMap(EpubPackage.read(TestEpubs.epub(dir, TestEpubs.opf(false, "a.xhtml", "b.xhtml"), null,
                Map.of("a.xhtml", "<p>a</p>", "b.xhtml", "<p>b</p>"))));
        assertThat(map.resolveChapter(1)).hasValue(new SpineAnchor(1, 0));
        assertThat(map.resolveChapter(2)).isEmpty();
        assertThat(map.resolveSpinePosition(1, 5)).hasValue(new ChapterAnchor(1, 0));
    }
}
