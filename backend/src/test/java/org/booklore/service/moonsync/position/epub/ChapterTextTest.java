package org.booklore.service.moonsync.position.epub;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The anchor cases ported from readsync {@code cfi_bridge/test/cfi.test.js} and {@code index.test.js}, plus the walk. */
class ChapterTextTest {

    @Test
    void anchorAfterOtherTextIsWhereItsOwnTextStarts() {
        // The Dorian Gray shape: <h2 id="heading_id_5"> after unrelated paragraphs; an earlier segment must not win.
        ChapterText text = ChapterText.of("<body><p>intro text</p><h2 id=\"heading_id_5\">Capitolo primo</h2><p>more</p></body>");
        assertThat(text.anchorOffset("heading_id_5")).hasValue("intro text".length());
    }

    @Test
    void missingIdIsEmpty() {
        assertThat(ChapterText.of("<body><p>intro text</p></body>").anchorOffset("nonexistent")).isEmpty();
    }

    @Test
    void anchorOnTheFirstTextIsZero() {
        assertThat(ChapterText.of("<body><h2 id=\"top\">Title</h2><p>more</p></body>").anchorOffset("top")).hasValue(0);
    }

    @Test
    void emptyAnchorElementResolvesToTheTextAfterIt() {
        ChapterText text = ChapterText.of("<body><p>before</p><h2><a id=\"anchor\"></a>Capitolo primo</h2></body>");
        assertThat(text.anchorOffset("anchor")).hasValue("before".length());
    }

    @Test
    void anchorWithNoTextAfterItIsTheEnd() {
        assertThat(ChapterText.of("<body><p>only text</p><div id=\"tail\"></div></body>").anchorOffset("tail"))
                .hasValue("only text".length());
    }

    @Test
    void anchorInAFileWithoutTextIsZero() {
        assertThat(ChapterText.of("<body><div id=\"x\"></div></body>").anchorOffset("x")).hasValue(0);
    }

    @Test
    void walkCountsBodyTextExceptHtmlScriptAndStyle() {
        ChapterText text = ChapterText.of("<html><head><title>t</title></head><body><p>ab</p><script>var x;</script>"
                + "<style>p{}</style><noscript>cd</noscript><svg><script>ef</script></svg></body></html>");
        assertThat(text.segments()).extracting(s -> s.node().getWholeText()).containsExactly("ab", "cd", "ef");
        assertThat(text.segments()).extracting(ChapterText.Segment::start).containsExactly(0, 2, 4);
        assertThat(text.length()).isEqualTo(6);
    }

    @Test
    void lengthsAreUtf16Units() {
        assertThat(ChapterText.of("<body><p>😀é</p></body>").length()).isEqualTo(3);
    }
}
