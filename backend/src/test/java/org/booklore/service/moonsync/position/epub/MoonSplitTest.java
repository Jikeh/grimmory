package org.booklore.service.moonsync.position.epub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The raw chunk boundaries (readsync {@code moon_split.js}); the rendered side of the split map and the full port of
 * the bridge's split tests belong with the rendered-offset work.
 */
class MoonSplitTest {

    @Test
    void smallChapterIsOneChunk() {
        String html = "<body><p>Hello world.</p></body>";
        assertThat(MoonSplit.rawBoundaries(html)).containsExactly(0, html.length());
        assertThat(MoonSplit.chunkCount(html)).isEqualTo(1);
    }

    @Test
    void cutsAtTheFirstParagraphOrDivAfterTheTrigger() {
        String filler = "x".repeat(MoonSplit.SPLIT_TRIGGER);
        String html = "<body><p>" + filler + "</p><div>tail</div><p>end</p></body>";
        int cut = html.indexOf("<div>tail");
        assertThat(MoonSplit.rawBoundaries(html)).containsExactly(0, cut, html.length());
    }

    @Test
    void fallsBackToAfterTheNextTagWhenNoParagraphIsNear() {
        String html = "<body><p>" + "x".repeat(MoonSplit.SPLIT_TRIGGER) + "<i>italic</i>"
                + "y".repeat(MoonSplit.SPLIT_SEARCH_WINDOW + 10) + "<p>late</p></body>";
        int cut = html.indexOf("<i>") + "<i>".length();
        assertThat(MoonSplit.rawBoundaries(html).get(1)).isEqualTo(cut);
    }

    @Test
    void anUnclosedDivIsCarriedIntoTheNextChunk() {
        assertThat(MoonSplit.findCarriedDiv("<div class=\"a\"><p>x")).isEqualTo("<div class=\"a\">");
        assertThat(MoonSplit.findCarriedDiv("<div class=\"a\"><p>x</div>")).isEmpty();
        assertThat(MoonSplit.findCarriedDiv("<p>x")).isEmpty();
    }

    @Test
    void bodyRangeIsBetweenTheBodyTagAndTheLastBodyEnd() {
        String html = "<html><BODY class=x>a</body>b</Body></html>";
        int[] range = MoonSplit.bodyContentRange(html);
        assertThat(html.substring(range[0], range[1])).isEqualTo("a</body>b");
        assertThat(MoonSplit.bodyContentRange("<bodyx>a")).containsExactly(0, 8);
    }

    @Test
    void noClosingTagAfterTheTriggerIsAnError() {
        assertThatThrownBy(() -> MoonSplit.rawBoundaries("<body>" + "x".repeat(MoonSplit.SPLIT_TRIGGER * 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void real1984SplitBoundary() throws IOException {
        Path file = TestEpubs.readsyncFixture("index_split_008_1984.html").orElse(null);
        assumeThat(file).as("readsync's index_split_008_1984.html (set MOON_READSYNC_FIXTURES)").isNotNull();
        // The real chunk begins at the blank paragraph just before the visible "CAPITOLO I" heading.
        var boundaries = MoonSplit.rawBoundaries(Files.readString(file));
        assertThat(boundaries).hasSize(4);
        assertThat(boundaries.get(1)).isEqualTo(100639);
    }
}
