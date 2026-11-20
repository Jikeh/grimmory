package org.booklore.service.moonsync.position.format;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports readsync's {@code tests/unit/test_po_parser.py} with its original inputs and expected outputs,
 * plus verbatim round-trip cases.
 */
class MoonPositionTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = MoonPositionTest.class.getResourceAsStream("/moonsync/format/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    class PortedFromReadsync {

        @Test
        void parsesConfirmedEpubExample() {
            MoonPosition.Epub position = (MoonPosition.Epub) MoonPosition.parse("1738611943423*3@0#4281:1.5%");

            assertThat(position.deviceId()).isEqualTo("1738611943423");
            assertThat(position.chapter()).isEqualTo(3);
            assertThat(position.split()).isZero();
            assertThat(position.offset()).isEqualTo(4281);
            assertThat(position.percent()).isEqualTo(1.5);
        }

        @Test
        void parsesConfirmedPdfExample() {
            MoonPosition.Pdf position = (MoonPosition.Pdf) MoonPosition.parse("1738611943423*14:3.6%");

            assertThat(position.deviceId()).isEqualTo("1738611943423");
            assertThat(position.page()).isEqualTo(14);
            assertThat(position.percent()).isEqualTo(3.6);
        }

        @Test
        void parsesIntegerPercent() {
            assertThat(MoonPosition.parse("1538849237101*10@1#56325:100%").percent()).isEqualTo(100.0);
        }

        @Test
        void parsesZeroPercent() {
            assertThat(MoonPosition.parse("1538849237101*0@0#0:0%").percent()).isZero();
        }

        @Test
        void toleratesSurroundingWhitespace() {
            MoonPosition.Pdf position = (MoonPosition.Pdf) MoonPosition.parse("  1738611943423*14:3.6%  \n");

            assertThat(position.page()).isEqualTo(14);
        }

        @Test
        void rejectsMalformedInput() {
            assertThatThrownBy(() -> MoonPosition.parse("not a po line"))
                    .isInstanceOf(MoonFormatException.class);
        }

        @Test
        void doesNotGuessAnUnsampledFormat() {
            // CBX/comics are unsampled: a line matching neither confirmed shape must fail, not be guessed.
            assertThatThrownBy(() -> MoonPosition.parse("1738611943423#14:3.6%"))
                    .isInstanceOf(MoonFormatException.class);
        }

        @Test
        void serializesEpubAndRoundTrips() {
            MoonPosition position = MoonPosition.epub("123", 2, 1, 999, 50.0, null);

            assertThat(position.serialize()).isEqualTo("123*2@1#999:50.0%");
            assertThat(MoonPosition.parse(position.serialize())).isEqualTo(position);
        }

        @Test
        void serializesPdfAndRoundTrips() {
            MoonPosition position = MoonPosition.pdf("123", 5, 10.0, null);

            assertThat(position.serialize()).isEqualTo("123*5:10.0%");
            assertThat(MoonPosition.parse(position.serialize())).isEqualTo(position);
        }

        @Test
        void withPercentKeepsPositionFields() {
            MoonPosition.Epub updated = (MoonPosition.Epub) MoonPosition.epub("123", 3, 0, 4281, 1.5, null).withPercent(42.0);

            assertThat(updated.percent()).isEqualTo(42.0);
            assertThat(updated.chapter()).isEqualTo(3);
            assertThat(updated.split()).isZero();
            assertThat(updated.offset()).isEqualTo(4281);
        }

        @Test
        void parsesTheWebViewScrollOffsetFromARealCapture() {
            // Real device capture that readsync's first, $-anchored regex rejected outright.
            MoonPosition.Epub position = (MoonPosition.Epub) MoonPosition.parse("1738611943423*9@0#80217:63.1%|391");

            assertThat(position.chapter()).isEqualTo(9);
            assertThat(position.split()).isZero();
            assertThat(position.offset()).isEqualTo(80217);
            assertThat(position.percent()).isEqualTo(63.1);
            assertThat(position.webViewScrollOffset()).isEqualTo("391");
        }

        @Test
        void serializesTheWebViewScrollOffset() {
            MoonPosition position = MoonPosition.epub("123", 2, 1, 999, 50.0, "391");

            assertThat(position.serialize()).isEqualTo("123*2@1#999:50.0%|391");
            assertThat(MoonPosition.parse(position.serialize())).isEqualTo(position);
        }

        @Test
        void withPercentKeepsTheWebViewScrollOffset() {
            MoonPosition updated = MoonPosition.epub("123", 3, 0, 4281, 1.5, "391").withPercent(42.0);

            assertThat(updated.webViewScrollOffset()).isEqualTo("391");
        }

        @Test
        void toleratesTheScrollOffsetOnPdfPositions() {
            MoonPosition.Pdf position = (MoonPosition.Pdf) MoonPosition.parse("1738611943423*14:3.6%|7");

            assertThat(position.page()).isEqualTo(14);
            assertThat(position.webViewScrollOffset()).isEqualTo("7");
        }

        @Test
        void hasNoScrollOffsetWhenAbsent() {
            assertThat(MoonPosition.parse("1738611943423*3@0#4281:1.5%").webViewScrollOffset()).isNull();
        }

        @Test
        void parsesTheFixtureFiles() throws IOException {
            assertThat(MoonPosition.parse(fixture("progress_sample_epub.po"))).isInstanceOf(MoonPosition.Epub.class);
            assertThat(MoonPosition.parse(fixture("progress_sample_pdf.po"))).isInstanceOf(MoonPosition.Pdf.class);
            assertThat(MoonPosition.parse(fixture("progress_edge_cases.po")).percent()).isEqualTo(100.0);
        }
    }

    @Nested
    class VerbatimRoundTrip {

        @ParameterizedTest
        @ValueSource(strings = {
                "1738611943423*3@0#4281:1.5%",
                "1538849237101*10@1#56325:100%",
                "1538849237101*0@0#0:0%",
                "1738611943423*9@0#80217:63.1%|391",
                "1738611943423*14:3.6%",
                "1738611943423*14:3.6%|7",
                "1738611943423*2@0#15:12.345%",
        })
        void writesBackExactlyWhatWasRead(String text) {
            assertThat(MoonPosition.parse(text).serialize()).isEqualTo(text);
        }

        @Test
        void withPercentFormatsLikeReadsync() {
            assertThat(MoonPosition.parse("1538849237101*10@1#56325:100%").withPercent(7).serialize())
                    .isEqualTo("1538849237101*10@1#56325:7.0%");
        }
    }

    @Nested
    class ParseErrors {

        @ParameterizedTest
        @ValueSource(strings = {
                "",
                "   ",
                "1738611943423*3@0#4281:1.5",
                "1738611943423*3@0#4281:%",
                "1738611943423*3@0#4281:1.5%|",
                "1738611943423*3@0#4281:1.5%|abc",
                "*3@0#4281:1.5%",
                "1738611943423*3@0#-4281:1.5%",
                "1738611943423*3@0:1.5%",
                "1738611943423*99999999999@0#1:1.5%",
        })
        void rejectsWithAClearMessage(String text) {
            assertThatThrownBy(() -> MoonPosition.parse(text))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining(".po");
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> MoonPosition.parse(null)).isInstanceOf(MoonFormatException.class);
        }
    }
}
