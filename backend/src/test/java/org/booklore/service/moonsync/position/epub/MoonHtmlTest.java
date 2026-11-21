package org.booklore.service.moonsync.position.epub;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each normalization that makes jsoup's tree the one parse5 (jsdom) builds. The expected trees are parse5's, as the
 * harness ({@code epub-parser-parity}) observed them on {@code edge-cases.epub} and the corpus.
 */
class MoonHtmlTest {

    private static List<String> texts(Node parent) {
        return parent.childNodes().stream()
                .map(n -> n instanceof TextNode t ? "\"" + t.getWholeText() + "\"" : "<" + ((Element) n).normalName() + ">")
                .toList();
    }

    @Test
    void crlfAndLoneCrBecomeLf() {
        Document document = MoonHtml.parse("<html><body><p>a\r\nb\rc</p></body></html>");
        assertThat(document.selectFirst("p").wholeText()).isEqualTo("a\nb\nc");
    }

    @Test
    void whitespaceBeforeHeadIsDropped() {
        Document document = MoonHtml.parse("<html>\n  <head><title>t</title></head>\n<body></body></html>");
        assertThat(texts(document.firstElementChild())).containsExactly("<head>", "\"\n\"", "<body>");
    }

    @Test
    void textAfterBodyAndHtmlEndsInBody() {
        Document document = MoonHtml.parse("<html><head></head><body><p>x</p>\n</body>\n</html>\n<!-- c -->\n\n");
        assertThat(texts(document.body())).containsExactly("<p>", "\"\n\n\n\n\n\"");
        assertThat(texts(document.firstElementChild())).containsExactly("<head>", "<body>");
    }

    @Test
    void textAfterBodyGoesToTheElementStillOpenAndKeepsSourceOrder() {
        Document document = MoonHtml.parse("<html><body><p>x</p><div>open\n</body>\nafter body\n</html>\n");
        assertThat(texts(document.selectFirst("div"))).containsExactly("\"open\n\nafter body\n\n\"");
    }

    @Test
    void adjacentTextNodesAreMerged() {
        assertThat(texts(MoonHtml.parse("<body><i>a <b>b</i> c</b> d</body>").selectFirst("b")))
                .containsExactly("\"b\"");
        Document table = MoonHtml.parse("<body>\n<table>\n<tr><td>x</td></tr>\nfoster\n</table></body>");
        assertThat(texts(table.body())).first().isEqualTo("\"\n\nfoster\n\"");
        Document svg = MoonHtml.parse("<body><svg>a<![CDATA[ b ]]>c</svg></body>");
        assertThat(texts(svg.selectFirst("svg"))).containsExactly("\"a b c\"");
        assertThat(svg.selectFirst("svg").childNode(0).getClass()).isEqualTo(TextNode.class);
    }

    @Test
    void templateContentIsNotInTheTree() {
        assertThat(MoonHtml.parse("<body><template><p>t</p></template></body>").selectFirst("template").childNodeSize()).isZero();
    }

    @Test
    void rawTextOutsideScriptAndStyleIsText() {
        Document document = MoonHtml.parse("<body><xmp><b>x</b></xmp><script>s</script></body>");
        assertThat(document.selectFirst("xmp").childNode(0)).isInstanceOf(TextNode.class);
        assertThat(document.selectFirst("script").childNode(0)).isNotInstanceOf(TextNode.class);
    }

    @Test
    void surrogateCharacterReferencesBecomeReplacementCharacters() {
        assertThat(MoonHtml.parse("<body><p>&#xD800;&#x1F600;</p></body>").selectFirst("p").wholeText())
                .isEqualTo("�😀");
    }
}
