package org.booklore.service.moonsync.position.epub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.booklore.service.moonsync.position.epub.TestEpubs.CONTAINER_XML;

/**
 * Ported from readsync {@code tests/unit/test_epub_content.py} (same inputs and expectations), plus the
 * percent-decoding and zip-name rules readsync gets from Python's {@code urllib} and {@code zipfile}.
 */
class EpubPackageTest {

    private static final Map<String, String> THREE_FILES = Map.of(
            "titlepage.xhtml", "<html><body><p>Cover</p></body></html>",
            "text/chapter1.xhtml", "<html><body><p>Chapter one text.</p></body></html>",
            "text/chapter2.xhtml", "<html><body><p>Chapter two text.</p></body></html>");

    private static final String TOC_NCX = TestEpubs.ncx("""
                <navPoint id="np1" playOrder="1">
                  <navLabel><text>Chapter one</text></navLabel>
                  <content src="text/chapter1.xhtml"/>
                  <navPoint id="np2" playOrder="2">
                    <navLabel><text>Chapter one, part two</text></navLabel>
                    <content src="text/chapter1.xhtml#heading_2"/>
                  </navPoint>
                </navPoint>
                <navPoint id="np3" playOrder="3">
                  <navLabel><text>Chapter two</text></navLabel>
                  <content src="text/chapter2.xhtml"/>
                </navPoint>""");

    private static final String STUB_NCX = TestEpubs.ncx(TestEpubs.navPoint("1", "Start", "titlepage.xhtml"));

    @TempDir
    Path dir;

    private Path plainBook() {
        return TestEpubs.epub(dir, TestEpubs.opf(false, "titlepage.xhtml", "text/chapter1.xhtml", "text/chapter2.xhtml"),
                null, THREE_FILES);
    }

    private Path bookWithNcx(String ncx) {
        return TestEpubs.epub(dir, TestEpubs.opf(true, "titlepage.xhtml", "text/chapter1.xhtml", "text/chapter2.xhtml"),
                ncx, THREE_FILES);
    }

    @Test
    void spineHrefsAreManifestHrefsInSpineOrder() {
        EpubPackage epub = EpubPackage.read(plainBook());
        assertThat(epub.spineHref(0)).isEqualTo("titlepage.xhtml");
        assertThat(epub.spineHref(1)).isEqualTo("text/chapter1.xhtml");
        assertThat(epub.spineHref(2)).isEqualTo("text/chapter2.xhtml");
        assertThat(epub.spinePath(1)).isEqualTo("OEBPS/text/chapter1.xhtml");
    }

    @Test
    void spineContentIsTheDecodedFile() {
        assertThat(EpubPackage.read(plainBook()).spineContent(1)).contains("Chapter one text.");
    }

    @Test
    void outOfRangeSpineIndexIsAClearError() {
        assertThatThrownBy(() -> EpubPackage.read(plainBook()).spineHref(99))
                .isInstanceOf(EpubContentException.class).hasMessageContaining("out of range");
    }

    @Test
    void containerPathIsMatchedIgnoringCase() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("meta-inf/container.xml", CONTAINER_XML);
        entries.put("OEBPS/content.opf", TestEpubs.opf(false, "titlepage.xhtml", "text/chapter1.xhtml"));
        Path book = TestEpubs.zip(dir, "book.epub", entries);
        assertThat(EpubPackage.read(book).spineHref(1)).isEqualTo("text/chapter1.xhtml");
    }

    @Test
    void malformedZipIsAnError() {
        Path book = dir.resolve("book.epub");
        TestEpubs.write(book, "not a zip file at all".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> EpubPackage.read(book)).isInstanceOf(EpubContentException.class);
    }

    @Test
    void missingContainerIsAnError() {
        Path book = TestEpubs.zip(dir, "book.epub", Map.of("some_other_file.txt", "hello"));
        assertThatThrownBy(() -> EpubPackage.read(book)).isInstanceOf(EpubContentException.class);
    }

    @Test
    void spineItemrefWithoutManifestEntryIsAnError() {
        String opf = TestEpubs.opf(false, "titlepage.xhtml", "text/chapter1.xhtml").replace("idref=\"f1\"", "idref=\"nonexistent\"");
        Path book = TestEpubs.epub(dir, opf, null, Map.of());
        assertThatThrownBy(() -> EpubPackage.read(book))
                .isInstanceOf(EpubContentException.class).hasMessageContaining("no matching manifest item");
    }

    @Test
    void spineContentThatIsNotUtf8IsAnError() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", CONTAINER_XML.getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/content.opf", TestEpubs.opf(false, "a.xhtml").getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/a.xhtml", new byte[]{'<', 'p', '>', (byte) 0xE9, '<'});
        Path book = TestEpubs.zipBytes(dir, "book.epub", entries);
        assertThatThrownBy(() -> EpubPackage.read(book).spineContent(0))
                .isInstanceOf(EpubContentException.class).hasMessageContaining("not valid UTF-8");
    }

    // --- NCX ---

    @Test
    void navPointsAreFlattenedInDocumentOrderWhateverTheirDepth() {
        List<NavPoint> navPoints = EpubPackage.read(bookWithNcx(TOC_NCX)).navPoints().orElseThrow();
        assertThat(navPoints).extracting(NavPoint::playOrder, NavPoint::href, NavPoint::fragment, NavPoint::depth)
                .containsExactly(
                        tuple(BigInteger.ONE, "text/chapter1.xhtml", null, 0),
                        tuple(BigInteger.TWO, "text/chapter1.xhtml", "heading_2", 1),
                        tuple(BigInteger.valueOf(3), "text/chapter2.xhtml", null, 0));
        assertThat(navPoints).extracting(NavPoint::label).containsExactly("Chapter one", "Chapter one, part two", "Chapter two");
    }

    @Test
    void aBareHashIsNoFragmentButRecorded() {
        NavPoint navPoint = EpubPackage.read(bookWithNcx(TestEpubs.ncx(TestEpubs.navPoint("1", "a", "text/chapter1.xhtml#"))))
                .navPoints().orElseThrow().getFirst();
        assertThat(navPoint.fragment()).isNull();
        assertThat(navPoint.bareHash()).isTrue();
    }

    @Test
    void ncxIsFoundThroughTheSpineTocAttribute() {
        assertThat(EpubPackage.read(bookWithNcx(TOC_NCX)).navPoints()).hasValueSatisfying(n -> assertThat(n).hasSize(3));
    }

    @Test
    void ncxIsFoundByMediaTypeWhenTheSpineHasNoTocAttribute() {
        String opf = TestEpubs.opf(true, "titlepage.xhtml", "text/chapter1.xhtml", "text/chapter2.xhtml").replace(" toc=\"ncx\"", "");
        Path book = TestEpubs.epub(dir, opf, TOC_NCX, THREE_FILES);
        assertThat(EpubPackage.read(book).navPoints()).hasValueSatisfying(n -> assertThat(n).hasSize(3));
    }

    @Test
    void aRealTocTrustedEvenWhenItDoesNotOutnumberTheSpine() {
        // 3 navPoints, 3 spine items: trusted. readsync's gate was once "more navPoints than spine items", which left
        // five real books resolving chapter indexes as spine indexes, one of them 54 points out.
        assertThat(EpubPackage.read(bookWithNcx(TOC_NCX)).ncxTrusted()).isTrue();
    }

    @Test
    void aSingleNavPointStubIsNotTrusted() {
        EpubPackage epub = EpubPackage.read(bookWithNcx(STUB_NCX));
        assertThat(epub.navPoints()).hasValueSatisfying(n -> assertThat(n).hasSize(1));
        assertThat(epub.ncxTrusted()).isFalse();
    }

    @Test
    void noNcxAtAllIsEmptyAndUntrusted() {
        EpubPackage epub = EpubPackage.read(plainBook());
        assertThat(epub.navPoints()).isEmpty();
        assertThat(epub.ncxTrusted()).isFalse();
    }

    @Test
    void aNavPointWithoutPlayOrderIsKeptSoPositionsDontShift() {
        List<NavPoint> navPoints = EpubPackage.read(bookWithNcx(TOC_NCX.replace(" playOrder=\"2\"", ""))).navPoints().orElseThrow();
        assertThat(navPoints).extracting(NavPoint::playOrder).containsExactly(BigInteger.ONE, null, BigInteger.valueOf(3));
        assertThat(navPoints).extracting(NavPoint::href)
                .containsExactly("text/chapter1.xhtml", "text/chapter1.xhtml", "text/chapter2.xhtml");
    }

    @Test
    void playOrderIsReadLikePythonsInt() {
        String ncx = TestEpubs.ncx(TestEpubs.navPoint(" 7 ", "a", "titlepage.xhtml") + TestEpubs.navPoint("1_0", "b", "titlepage.xhtml")
                + TestEpubs.navPoint("1.0", "c", "titlepage.xhtml") + TestEpubs.navPoint("+3", "d", "titlepage.xhtml"));
        assertThat(EpubPackage.read(bookWithNcx(ncx)).navPoints().orElseThrow()).extracting(NavPoint::playOrder)
                .containsExactly(BigInteger.valueOf(7), BigInteger.TEN, null, BigInteger.valueOf(3));
    }

    @Test
    void aNavPointWithoutContentSrcIsSkippedAndAnUnreadableNcxIsNoNcx() {
        String ncx = TOC_NCX.replace("<content src=\"text/chapter2.xhtml\"/>", "<content/>");
        assertThat(EpubPackage.read(bookWithNcx(ncx)).navPoints().orElseThrow()).hasSize(2);
        assertThat(EpubPackage.read(bookWithNcx("<ncx><navMap>")).navPoints()).isEmpty();
        assertThat(EpubPackage.read(bookWithNcx(TestEpubs.ncx(""))).navPoints()).isEmpty();
    }

    @Test
    void ncxSpineIndexIsEmptyForAnUnmatchedHref() {
        EpubPackage epub = EpubPackage.read(bookWithNcx(TOC_NCX));
        assertThat(epub.ncxSpineIndex("text/chapter1.xhtml")).hasValue(1);
        assertThat(epub.ncxSpineIndex("text/does_not_exist.xhtml")).isEmpty();
    }

    @Test
    void ncxSpineIndexMatchesAPercentEncodedHref() {
        // Real example (book "Neuromante"): the NCX percent-encodes hrefs the manifest writes with literal spaces.
        Path book = TestEpubs.epub(dir, TestEpubs.opf(true, "split 002.htm"), TOC_NCX,
                Map.of("split 002.htm", "<html><body><p>Chapter text.</p></body></html>"));
        assertThat(EpubPackage.read(book).ncxSpineIndex("split%20002.htm")).hasValue(0);
    }

    @Test
    void percentDecodingIsUrllibUnquote() {
        assertThat(EpubPackage.percentDecode("a%20b+c")).isEqualTo("a b+c");
        assertThat(EpubPackage.percentDecode("citt%C3%A0")).isEqualTo("città");
        assertThat(EpubPackage.percentDecode("bad%zz%4")).isEqualTo("bad%zz%4");
        assertThat(EpubPackage.percentDecode("%FF")).isEqualTo("�");
        assertThat(EpubPackage.percentDecode("%C3é%A9")).isEqualTo("�é�");
    }

    @Test
    void anEpub3NavDocumentIsReported() {
        String opf = TestEpubs.opf(false, "nav.xhtml").replace("media-type=\"application/xhtml+xml\"/>",
                "media-type=\"application/xhtml+xml\" properties=\"scripted nav\"/>");
        assertThat(EpubPackage.read(TestEpubs.epub(dir, opf, null, Map.of())).declaresNavDocument()).isTrue();
        assertThat(EpubPackage.read(plainBook()).declaresNavDocument()).isFalse();
    }

    // --- Real fixtures (readsync's, not committed here) ---

    @Test
    void realDorianGrayHasATrustedNcxRicherThanItsSpine() {
        Path book = TestEpubs.readsyncFixture("dorian_gray.epub").orElse(null);
        assumeThat(book).as("readsync's dorian_gray.epub (set MOON_READSYNC_FIXTURES)").isNotNull();
        EpubPackage epub = EpubPackage.read(book);
        assertThat(epub.navPoints().orElseThrow()).hasSize(22);
        assertThat(epub.spineLength()).isEqualTo(4);
        assertThat(epub.ncxTrusted()).isTrue();
    }

    @Test
    void real1984HasAnUntrustedStubNcx() {
        Path book = TestEpubs.readsyncFixture("1984.epub").orElse(null);
        assumeThat(book).as("readsync's 1984.epub (set MOON_READSYNC_FIXTURES)").isNotNull();
        EpubPackage epub = EpubPackage.read(book);
        assertThat(epub.navPoints().orElseThrow()).hasSize(1);
        assertThat(epub.spineLength()).isEqualTo(11);
        assertThat(epub.ncxTrusted()).isFalse();
    }
}
