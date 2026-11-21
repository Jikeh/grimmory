package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.position.epub.MoonChapterMap;
import org.booklore.service.moonsync.position.epub.MoonHtml;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** The Java side of {@code epub-structure}, {@code epub-parser-parity} and {@code epub-chapter-map}, on edge-cases.epub. */
class EpubComparisonsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static HarnessInput edgeCases() {
        Path book;
        try {
            book = Path.of(Objects.requireNonNull(EpubComparisonsTest.class.getClassLoader()
                    .getResource("moonsync/books/edge-cases.epub")).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return new HarnessInput(JSON.createObjectNode().put("file", "corpus/edge-cases.epub"), ref -> book);
    }

    private static HarnessInput notABook() {
        return new HarnessInput(JSON.createObjectNode().put("file", "corpus/missing.epub"), ref -> Path.of("/nonexistent.epub"));
    }

    @Test
    void structureListsSpineNavPointsAndTheirSpineIndexes() {
        JsonNode out = new EpubStructureComparison().run(edgeCases());
        assertThat(out.get("spineHrefs")).hasSize(16);
        assertThat(out.get("spinePaths").get(0).asString()).isEqualTo("OEBPS/Text/self-closing.xhtml");
        assertThat(out.get("ncxTrusted").asBoolean()).isTrue();
        JsonNode navPoints = out.get("navPoints");
        assertThat(navPoints.get(1).get("fragment").asString()).isEqualTo("nowhere");
        assertThat(navPoints.get(3).get("playOrder").isNull()).isTrue(); // playOrder="x"
        // "crlf%20file.xhtml" and "citt%C3%A0.xhtml" match their unencoded manifest hrefs; the missing file doesn't.
        assertThat(out.get("navPointSpineIndex").valueStream().map(n -> n.isNull() ? null : n.asInt()).toList())
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10, null, 11, 11);
    }

    @Test
    void anUnreadableBookIsAnErrorOnEveryComparison() {
        assertThat(new EpubStructureComparison().run(notABook()).has(JsonDiff.ERROR)).isTrue();
        assertThat(new EpubParserParityComparison().run(notABook()).has(JsonDiff.ERROR)).isTrue();
        assertThat(new EpubChapterMapComparison().run(notABook()).has(JsonDiff.ERROR)).isTrue();
    }

    @Test
    void paritySignatureCoversEverySpineFile() {
        JsonNode files = new EpubParserParityComparison().run(edgeCases()).get("files");
        assertThat(files).hasSize(16);
        assertThat(files.get(0).get("digest").asString()).hasSize(64);
    }

    @Test
    void paritySignatureSeesStructureAndTextButNotComments() {
        String a = EpubParserParityComparison.signature(MoonHtml.parse("<body><p>x</p></body>")).toString();
        assertThat(EpubParserParityComparison.signature(MoonHtml.parse("<body><p>x</p><!-- c --></body>")).toString()).isEqualTo(a);
        assertThat(EpubParserParityComparison.signature(MoonHtml.parse("<body><div>x</div></body>")).toString()).isNotEqualTo(a);
        assertThat(EpubParserParityComparison.signature(MoonHtml.parse("<body><p>y</p></body>")).toString()).isNotEqualTo(a);
        assertThat(EpubParserParityComparison.events(MoonHtml.parse("<body><p id=\"i\">x</p></body>")))
                .containsExactly("<html", "<head", ">", "<body", "<p#i", "\"x", ">", ">", ">");
    }

    /** Shapes found in the real library; the expected events are jsdom's (tree_signature.js, TREE_EVENTS=1). */
    @Test
    void parserMatchesJsdomOnRealLibraryShapes() {
        // An <a/> left open: text after </body> gets a reconstructed copy of it.
        assertThat(EpubParserParityComparison.events(MoonHtml.parse("<body><div><p>x <a id=\"a1\"/> y</p></div>\n</body>\n</html>\n")))
                .containsExactly("<html", "<head", ">", "<body", "<div", "<p", "\"x ", "<a#a1", "\" y", ">", ">", ">",
                        "<a#a1", "\"\n\n\n", ">", ">", ">");
        // Character-reference whitespace in a table is foster-parented out of it.
        assertThat(EpubParserParityComparison.events(MoonHtml.parse(
                "<body><figure>\n<table>&#13;\n<tbody>&#13;\n<tr>&#13;\n<td>c</td>&#13;\n</tr></tbody></table></figure></body>")))
                .containsExactly("<html", "<head", ">", "<body", "<figure", "\"\n\r\n\r\n\r\n\r\n", "<table", "<tbody", "<tr",
                        "<td", "\"c", ">", ">", ">", ">", ">", ">", ">");
        // A <title/> runs to the end of the file; its "</html>" is text, not a place to put text after the body.
        assertThat(EpubParserParityComparison.events(MoonHtml.parse("<html><head><title/><link/></head><body><p>t</p></body></html>\n")))
                .containsExactly("<html", "<head", "<title", "\"<link/></head><body><p>t</p></body></html>\n", ">", ">",
                        "<body", ">", ">");
    }

    @Test
    void chapterMapProbesAreZeroAndEachRelaxedAnchorWithItsNeighbours() {
        var probes = EpubChapterMapComparison.probes(3, List.of(new MoonChapterMap.SpineAnchor(1, 10),
                new MoonChapterMap.SpineAnchor(1, 0), new MoonChapterMap.SpineAnchor(7, 5)));
        assertThat(probes.get(0)).containsExactly(0);
        assertThat(probes.get(1)).containsExactly(0, 1, 9, 10, 11);
        assertThat(probes.get(2)).containsExactly(0);
    }

    @Test
    void chapterMapCoversEveryChapterIndexAndReverseProbe() {
        JsonNode out = new EpubChapterMapComparison().run(edgeCases());
        assertThat(out.get("chapters")).hasSize(18); // max(16 navPoints, 13 spine items) + 2
        assertThat(out.get("chapters").get(0).get("strict").toString()).isEqualTo("[0,0]");
        assertThat(out.get("chapters").get(1).get("relaxed").isNull()).isTrue(); // #nowhere doesn't resolve
        assertThat(out.get("reverse").get(0).get("result").toString()).isEqualTo("[0,0]");
    }
}
