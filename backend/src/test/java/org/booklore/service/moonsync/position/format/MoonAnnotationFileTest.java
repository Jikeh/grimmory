package org.booklore.service.moonsync.position.format;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports readsync's {@code tests/unit/test_an_parser.py} (EPUB cases) with its original inputs and expected
 * outputs, and adds the Moon+-shaped cases readsync never covered: bookmark records, deleted-notes records
 * with unescaped newlines, header-less deleted-only uploads, positional record reading, and verbatim rewrites.
 *
 * <p>readsync's PDF {@code .an} parsing cases are replaced by rejection cases on the same fixtures: PDF highlights
 * are out of scope for Moon+ sync (a listed deviation in the spec), so the parser recognizes the PDF sub-format
 * only to reject it clearly.
 *
 * <p>Note-slot content (escaping, multi-line notes on live records) is completed in ticket 02; the cases here
 * only use the empty note slot every readsync sample has, plus the raw deleted-notes shape.
 */
class MoonAnnotationFileTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = MoonAnnotationFileTest.class.getResourceAsStream("/moonsync/format/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static byte[] zlib(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out, new Deflater())) {
            deflater.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static MoonAnnotationRecord highlight(String id, int chapter, int split, long offset, String text) {
        return new MoonAnnotationRecord(id, "book.epub", "/sdcard/book.epub", "/sdcard/book.epub",
                chapter, split, offset, text.length(), 1996532479, 1700000000000L,
                "", "", text, "0", "0", "0");
    }

    @Nested
    class PortedFromReadsync {

        @Test
        void parsesTheSampleRecordLikeTheMoonMdTable() throws IOException {
            List<MoonAnnotationRecord> records = MoonAnnotationFile.parse(fixture("annotations_epub_sample.an")).records();

            assertThat(records).hasSize(1);
            MoonAnnotationRecord r = records.getFirst();
            assertThat(r.id()).isEqualTo("41");
            assertThat(r.filename()).isEqualTo("1984 - George Orwell_107.epub");
            assertThat(r.path()).startsWith("/storage/7BD8-3916/");
            assertThat(r.lowerPath()).isEqualTo(r.path().toLowerCase());
            assertThat(r.chapter()).isEqualTo(9);
            assertThat(r.split()).isZero();
            assertThat(r.offset()).isEqualTo(10202);
            assertThat(r.length()).isEqualTo(618);
            assertThat(r.color()).isEqualTo(1996532479);
            assertThat(r.time()).isEqualTo(1451525891330L);
            assertThat(r.text()).startsWith("Se si amava qualcuno");
            assertThat(List.of(r.underline(), r.strikethrough(), r.bak())).containsExactly("0", "0", "0");
        }

        @Test
        void doesNotLeakTheHeaderIntoTheFirstRecord() throws IOException {
            MoonAnnotationFile file = MoonAnnotationFile.parse(fixture("annotations_epub_sample.an"));

            assertThat(file.records().getFirst().id()).isNotEqualTo("415");
        }

        @Test
        void keepsTheBlankBookmarkAndNoteSlotsOutOfTheText() throws IOException {
            MoonAnnotationRecord r = MoonAnnotationFile.parse(fixture("annotations_epub_sample.an")).records().getFirst();

            assertThat(r.text()).doesNotStartWith("\n");
            assertThat(r.bookmark()).isEmpty();
            assertThat(r.note()).isEmpty();
        }

        @Test
        void keepsBrTagsInTheTextVerbatim() throws IOException {
            List<MoonAnnotationRecord> records = MoonAnnotationFile.parse(fixture("annotations_epub_br_tag.an")).records();

            assertThat(records).hasSize(1);
            assertThat(records.getFirst().text()).contains("<BR>");
            assertThat(records.getFirst().text().split("<BR>", -1)).hasSize(3);
        }

        @Test
        void parsesAFileWithOnlyAHeader() throws IOException {
            assertThat(MoonAnnotationFile.parse(fixture("annotations_empty.an")).records()).isEmpty();
        }

        @Test
        void rejectsAnInvalidZlibStreamWithAClearError() {
            assertThatThrownBy(() -> MoonAnnotationFile.parse(fixture("annotations_malformed.an")))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("zlib");
        }

        @Test
        void doesNotRequireADelimiterAfterTheLastRecord() throws IOException {
            assertThat(MoonAnnotationFile.parse(fixture("annotations_epub_sample.an")).records()).hasSize(1);
        }

        @Test
        void flagsADeletedRecordInsteadOfTreatingItAsAHighlight() {
            // The before/after sample in readsync's docs/connectors/moon.md ("Deletion / tombstone records").
            String header = "999\nindent:true\ntrim:true\n";
            String active = "#\n81\n1984\n/sdcard/eBooks/George Orwell/1984 - George Orwell_107.epub\n"
                    + "/sdcard/ebooks/george orwell/1984 - george orwell_107.epub\n10\n1\n55140\n50\n"
                    + "1996532479\n1784406703372\n\n\nGran parte, infatti, della letteratura del passato\n0\n0\n0\n";
            String tombstone = "#\n0\n0\n0\n0\n10\n1\n55140\n0\n0\n1784406703372\n\n0\n0\n0\n*DELETED*\n";

            List<MoonAnnotationRecord> records = MoonAnnotationFile.parse(zlib(header + active + tombstone)).records();

            assertThat(records).hasSize(2);
            MoonAnnotationRecord activeRecord = records.get(0);
            MoonAnnotationRecord deleted = records.get(1);
            assertThat(activeRecord.isDeleted()).isFalse();
            assertThat(activeRecord.text()).startsWith("Gran parte");
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.isHighlight()).isFalse();
            assertThat(deleted.chapter()).isEqualTo(10);
            assertThat(deleted.split()).isEqualTo(1);
            assertThat(deleted.offset()).isEqualTo(55140);
            assertThat(deleted.length()).isZero();
            assertThat(deleted.color()).isZero();
            assertThat(deleted.text()).isNotEqualTo("Gran parte, infatti, della letteratura del passato");
        }

        @Test
        void parsesTheHeaderLikeTheMoonMdTable() throws IOException {
            MoonAnnotationHeader header = MoonAnnotationFile.parse(fixture("annotations_epub_sample.an")).header().orElseThrow();

            assertThat(header.totalLength()).isEqualTo("415");
            assertThat(header.indent()).isTrue();
            assertThat(header.trim()).isTrue();
        }

        @Test
        void rejectsAFileTooShortForAHeader() {
            assertThatThrownBy(() -> MoonAnnotationFile.parse(zlib("only one line")))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("header");
        }

        @Test
        void serializesAndReparsesTheSampleRecords() throws IOException {
            MoonAnnotationHeader header = new MoonAnnotationHeader("999", true, true);
            List<MoonAnnotationRecord> records = MoonAnnotationFile.parse(fixture("annotations_epub_sample.an")).records();

            MoonAnnotationFile reparsed = MoonAnnotationFile.parse(MoonAnnotationFile.of(header, records).toBytes());

            assertThat(reparsed.header()).contains(header);
            assertThat(reparsed.records()).isEqualTo(records);
        }

        @Test
        void serializesAndReparsesMultipleRecordsIncludingADeletedOne() {
            MoonAnnotationHeader header = new MoonAnnotationHeader("42", false, false);
            MoonAnnotationRecord active = new MoonAnnotationRecord("1", "book.epub", "/sdcard/book.epub", "/sdcard/book.epub",
                    3, 0, 100, 20, 1996532479, 1700000000000L, "", "", "a highlighted passage", "0", "0", "0");
            MoonAnnotationRecord deleted = new MoonAnnotationRecord("0", "0", "0", "0",
                    3, 0, 50, 0, 0, 1700000000000L, "", "", "0", "0", "0", MoonAnnotationRecord.DELETED_MARKER);

            MoonAnnotationFile reparsed = MoonAnnotationFile.parse(MoonAnnotationFile.of(header, List.of(active, deleted)).toBytes());

            assertThat(reparsed.records()).containsExactly(active, deleted);
            assertThat(reparsed.records().get(1).isDeleted()).isTrue();
        }

        @Test
        void aTrailingNewlineDoesNotCorruptTheLastRecord() {
            // Real device exports end with "\n" after the last trailer line.
            String header = "999\nindent:true\ntrim:true\n";
            String one = "#\n1\nbook.epub\n/path\n/path\n0\n0\n100\n10\n123\n1000\n\n\nfirst highlight\n0\n0\n0\n";
            String two = "#\n2\nbook.epub\n/path\n/path\n0\n0\n200\n20\n123\n2000\n\n\nsecond highlight\n0\n0\n0\n";

            List<MoonAnnotationRecord> records = MoonAnnotationFile.parse(zlib(header + one + two)).records();

            assertThat(records).hasSize(2);
            assertThat(records.get(1).text()).isEqualTo("second highlight");
            assertThat(List.of(records.get(1).underline(), records.get(1).strikethrough(), records.get(1).bak()))
                    .containsExactly("0", "0", "0");
        }

        @ParameterizedTest
        @ValueSource(strings = {"annotations_pdf_sample.an", "annotations_pdf_tombstone.an"})
        void rejectsThePdfSubFormatClearly(String name) {
            // Deviation: readsync parses PDF .an files; Moon+ sync syncs EPUB highlights only.
            assertThatThrownBy(() -> MoonAnnotationFile.parse(fixture(name)))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("PDF");
        }
    }

    /**
     * Files laid out exactly as Moon+ writes them: {@code BookDb.exportNotesToText} (header, then one
     * {@code #}-led block per notes-table row with every column on its own line and newlines escaped as
     * {@code <BR>}) followed by {@code BookDb.deletedNotesToText} (the in-memory deleted-notes list, whose
     * bookmark and note text is written without escaping).
     */
    @Nested
    class MoonShapedExports {

        private static final String HEADER = "334344\nindent:true\ntrim:false\n";
        private static final String HIGHLIGHT = "#\n81\n1984 - George Orwell_107.epub\n/sdcard/Books/1984 - George Orwell_107.epub\n"
                + "/sdcard/books/1984 - george orwell_107.epub\n10\n1\n55140\n50\n1996532479\n1784406703372\n\n\n"
                + "Gran parte, infatti, della letteratura del passato\n0\n0\n0\n";
        private static final String BOOKMARK = "#\n82\n1984 - George Orwell_107.epub\n/sdcard/Books/1984 - George Orwell_107.epub\n"
                + "/sdcard/books/1984 - george orwell_107.epub\n4\n0\n1200\n-16776961\n1996532479\n1784406709999\n"
                + "Chapter 4: It was a bright cold day\n\n\n0\n0\n0\n";
        private static final String DELETED_WITH_MULTILINE_NOTE = "#\n0\n0\n0\n0\n12\n0\n300\n0\n0\n1784406712345\n\n"
                + "first line of the note\nsecond line of the note\n0\n0\n0\n*DELETED*\n";

        private static final String EXPORT = HEADER + HIGHLIGHT + BOOKMARK + DELETED_WITH_MULTILINE_NOTE;

        @Test
        void roundTripsTheDecompressedTextExactly() {
            MoonAnnotationFile file = MoonAnnotationFile.parse(zlib(EXPORT));

            assertThat(file.toText()).isEqualTo(EXPORT);
            assertThat(MoonAnnotationFile.parse(file.toBytes()).toText()).isEqualTo(EXPORT);
        }

        @Test
        void readsTheHeaderFlags() {
            MoonAnnotationHeader header = MoonAnnotationFile.parse(zlib(EXPORT)).header().orElseThrow();

            assertThat(header.totalLength()).isEqualTo("334344");
            assertThat(header.indent()).isTrue();
            assertThat(header.trim()).isFalse();
        }

        @Test
        void exposesABookmarkAsNotAHighlight() {
            List<MoonAnnotationRecord> records = MoonAnnotationFile.parse(zlib(EXPORT)).records();

            MoonAnnotationRecord bookmark = records.get(1);
            assertThat(bookmark.kind()).isEqualTo(MoonAnnotationRecord.Kind.BOOKMARK);
            assertThat(bookmark.isHighlight()).isFalse();
            assertThat(bookmark.bookmark()).isEqualTo("Chapter 4: It was a bright cold day");
            assertThat(records.get(0).kind()).isEqualTo(MoonAnnotationRecord.Kind.HIGHLIGHT);
            assertThat(MoonAnnotationFile.parse(zlib(EXPORT)).highlights()).containsExactly(records.get(0));
        }

        @Test
        void readsADeletedRecordWithUnescapedNewlinesInItsNote() {
            MoonAnnotationRecord deleted = MoonAnnotationFile.parse(zlib(EXPORT)).records().get(2);

            assertThat(deleted.kind()).isEqualTo(MoonAnnotationRecord.Kind.DELETED);
            assertThat(deleted.chapter()).isEqualTo(12);
            assertThat(deleted.split()).isZero();
            assertThat(deleted.offset()).isEqualTo(300);
            assertThat(deleted.time()).isEqualTo(1784406712345L);
            assertThat(deleted.bookmark()).isEmpty();
            assertThat(deleted.note()).isEqualTo("first line of the note\nsecond line of the note");
            assertThat(deleted.text()).isEqualTo("0");
        }

        @Test
        void aDeletedBookmarkIsStillRecognizedAsDeleted() {
            String deletedBookmark = "#\n0\n0\n0\n0\n4\n0\n1200\n0\n0\n1784406709999\nChapter 4\n\n0\n0\n0\n*DELETED*\n";

            MoonAnnotationRecord record = MoonAnnotationFile.parse(zlib(HEADER + deletedBookmark)).records().getFirst();

            assertThat(record.kind()).isEqualTo(MoonAnnotationRecord.Kind.DELETED);
            assertThat(record.bookmark()).isEqualTo("Chapter 4");
        }

        @Test
        void rewritingOneRecordKeepsTheOthersVerbatim() {
            MoonAnnotationFile file = MoonAnnotationFile.parse(zlib(EXPORT));
            List<MoonAnnotationRecord> records = new ArrayList<>(file.records());
            MoonAnnotationRecord recolored = records.getFirst().withColor(-256);
            records.set(0, recolored);

            String rewritten = file.withRecords(records).toText();

            assertThat(rewritten).isEqualTo(HEADER + HIGHLIGHT.replace("\n1996532479\n", "\n-256\n")
                    + BOOKMARK + DELETED_WITH_MULTILINE_NOTE);
        }

        @Test
        void keepsAnIrregularRecordVerbatimWhenRewritten() {
            // readsync's documented tombstone sample has 15 lines, one fewer than BookDb.deletedNotesToText writes.
            String fifteenLineTombstone = "#\n0\n0\n0\n0\n10\n1\n55140\n0\n0\n1784406703372\n\n0\n0\n0\n*DELETED*\n";
            MoonAnnotationFile file = MoonAnnotationFile.parse(zlib(HEADER + HIGHLIGHT + fifteenLineTombstone));
            List<MoonAnnotationRecord> records = new ArrayList<>(file.records());
            records.add(highlight("90", 11, 0, 7, "new"));

            String rewritten = file.withRecords(records).toText();

            assertThat(rewritten).startsWith(HEADER + HIGHLIGHT + fifteenLineTombstone);
            assertThat(MoonAnnotationFile.parse(zlib(rewritten)).records()).containsExactlyElementsOf(records);
        }

        @Test
        void keepsTheMissingTrailingNewlineOfAHandBuiltFile() throws IOException {
            byte[] sample = fixture("annotations_epub_sample.an");
            MoonAnnotationFile file = MoonAnnotationFile.parse(sample);

            assertThat(file.toText()).doesNotEndWith("\n");
            assertThat(MoonAnnotationFile.parse(file.toBytes()).toText()).isEqualTo(file.toText());
        }

        @Test
        void readsTheHeaderlessFileMoonUploadsAfterTheLastNoteIsDeleted() {
            // BookDb.exportNotesToText returns "" once no live rows are left; Sync.uploadCloudNoteFile still
            // uploads the deleted-notes list on its own.
            String deletedOnly = "#\n0\n0\n0\n0\n10\n1\n55140\n0\n0\n1784406703372\n\n\n0\n0\n0\n*DELETED*\n";

            MoonAnnotationFile file = MoonAnnotationFile.parse(zlib(deletedOnly));

            assertThat(file.header()).isEmpty();
            assertThat(file.records()).singleElement().satisfies(r -> {
                assertThat(r.isDeleted()).isTrue();
                assertThat(r.offset()).isEqualTo(55140);
            });
        }

        @Test
        void refusesToWriteAHeaderlessFileBecauseMoonCannotImportIt() {
            // BookDb.importNotesFromText reads the book size from line 0 with Integer.valueOf.
            String deletedOnly = "#\n0\n0\n0\n0\n10\n1\n55140\n0\n0\n1784406703372\n\n\n0\n0\n0\n*DELETED*\n";
            MoonAnnotationFile file = MoonAnnotationFile.parse(zlib(deletedOnly));
            List<MoonAnnotationRecord> records = new ArrayList<>(file.records());
            records.add(highlight("1", 2, 0, 5, "text"));

            assertThatThrownBy(file::toText).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> file.withRecords(records).toBytes()).isInstanceOf(IllegalStateException.class);
            assertThat(file.withRecords(records).withHeader(new MoonAnnotationHeader("1000", true, true)).toText())
                    .startsWith("1000\nindent:true\ntrim:true\n" + deletedOnly + "#\n1\n");
        }

        @Test
        void readsRecordsByPositionSoALoneHashColumnDoesNotSplitThem() {
            // BookDb.importNotesFromText reads the 16 lines after '#' by index; a highlight of just "#" is
            // exported as a lone '#' line.
            String hashHighlight = "#\n83\nbook.epub\n/p\n/p\n3\n0\n40\n1\n1996532479\n1784406700000\n\n\n#\n0\n0\n0\n";
            String text = HEADER + hashHighlight + HIGHLIGHT;

            MoonAnnotationFile file = MoonAnnotationFile.parse(zlib(text));

            assertThat(file.records()).hasSize(2);
            assertThat(file.records().getFirst().text()).isEqualTo("#");
            assertThat(file.toText()).isEqualTo(text);
            assertThat(MoonAnnotationFile.of(file.header().orElseThrow(), file.records()).toText()).isEqualTo(text);
        }

        @Test
        void endsWithANewlineWhenTheLastLineIsEmptyEvenIfTheSourceDidNot() throws IOException {
            // readsync and hand-built files have no final "\n"; an empty last field (here a caller-built record with
            // an empty bak; Moon+ itself always stores "0"/"1") would otherwise be read back, here and by Moon+'s
            // readLine, as that missing terminator.
            MoonAnnotationFile file = MoonAnnotationFile.parse(fixture("annotations_epub_sample.an"));
            MoonAnnotationRecord emptyBak = new MoonAnnotationRecord("2", "book.epub", "/p", "/p", 1, 0, 5, 4, 1, 1L,
                    "", "", "text", "0", "0", "");
            List<MoonAnnotationRecord> records = List.of(file.records().getFirst(), emptyBak);

            String text = file.withRecords(records).toText();

            assertThat(text).endsWith("\n0\n0\n\n");
            assertThat(MoonAnnotationFile.parseText(text).records()).containsExactlyElementsOf(records);
        }

        @Test
        void aNewFileIsWrittenTheWayMoonWritesIt() {
            String deletedHighlight = "#\n0\n0\n0\n0\n12\n0\n300\n0\n0\n1784406712345\n\na one-line note\n0\n0\n0\n*DELETED*\n";
            String export = HEADER + HIGHLIGHT + BOOKMARK + deletedHighlight;
            MoonAnnotationFile file = MoonAnnotationFile.of(new MoonAnnotationHeader("334344", true, false),
                    MoonAnnotationFile.parse(zlib(export)).records());

            assertThat(file.toText()).isEqualTo(export);
        }

        @Test
        void refusesToRewriteAChangedDeletedRecordWithAMultiLineNote() {
            // Moon+'s own multi-line deleted note is kept verbatim while unchanged, but a new or changed record
            // must be single-line: importNotesFromText would read the second note line as the text column.
            MoonAnnotationFile file = MoonAnnotationFile.parse(zlib(EXPORT));
            List<MoonAnnotationRecord> records = new ArrayList<>(file.records());
            records.set(2, records.get(2).withColor(5));

            assertThatThrownBy(() -> file.withRecords(records).toText())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("note");
        }
    }

    @Nested
    class Errors {

        private static final String HEADER = "999\nindent:true\ntrim:true\n";

        @Test
        void rejectsARecordWithTooFewLines() {
            assertThatThrownBy(() -> MoonAnnotationFile.parse(zlib(HEADER + "#\n1\nbook.epub\n/p\n/p\n0\n0\n")))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("record 1");
        }

        @Test
        void reportsTheDeletedRecordMinimumLength() {
            assertThatThrownBy(() -> MoonAnnotationFile.parse(zlib(HEADER + "#\n0\n0\n0\n0\n1\n0\n5\n0\n0\n1\n0\n*DELETED*\n")))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("at least 14");
        }

        @Test
        void refusesAMultiLineNoteOnANewDeletedRecord() {
            MoonAnnotationRecord deleted = new MoonAnnotationRecord("0", "0", "0", "0", 1, 0, 5, 0, 0, 1L,
                    "", "a\nb", "0", "0", "0", MoonAnnotationRecord.DELETED_MARKER);

            assertThatThrownBy(() -> MoonAnnotationFile.of(new MoonAnnotationHeader("1", true, true), List.of(deleted)).toText())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("note");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "false", "1.0"})
        void refusesNonIntUnderlineOrStrikethrough(String value) {
            MoonAnnotationRecord base = highlight("1", 0, 0, 0, "text");
            MoonAnnotationRecord badUnderline = new MoonAnnotationRecord(base.id(), base.filename(), base.path(), base.lowerPath(),
                    0, 0, 0, 4, 1, 1L, "", "", "text", value, "0", "0");
            MoonAnnotationRecord badStrikethrough = new MoonAnnotationRecord(base.id(), base.filename(), base.path(), base.lowerPath(),
                    0, 0, 0, 4, 1, 1L, "", "", "text", "0", value, "0");
            MoonAnnotationHeader header = new MoonAnnotationHeader("1", true, true);

            assertThatThrownBy(() -> MoonAnnotationFile.of(header, List.of(badUnderline)).toText())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("underline");
            assertThatThrownBy(() -> MoonAnnotationFile.of(header, List.of(badStrikethrough)).toText())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strikethrough");
        }

        @Test
        void readsAnEpubHighlightThatQuotesThePdfTag() {
            String record = "#\n1\nbook.epub\n/p\n/p\n0\n0\n100\n4\n123\n1000\n\n\nsee #A*# here\n0\n0\n0\n";

            assertThat(MoonAnnotationFile.parse(zlib(HEADER + record)).records().getFirst().text()).isEqualTo("see #A*# here");
        }

        @Test
        void refusesACarriageReturnBecauseMoonSplitsLinesOnIt() {
            MoonAnnotationRecord broken = highlight("1", 0, 0, 0, "one\rtwo");

            assertThatThrownBy(() -> MoonAnnotationFile.of(new MoonAnnotationHeader("1", true, true), List.of(broken)).toText())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("text");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "abc", "-5", "12.5", "99999999999"})
        void refusesToWriteATotalLengthMoonCannotRead(String totalLength) {
            MoonAnnotationFile file = MoonAnnotationFile.of(new MoonAnnotationHeader(totalLength, true, true), List.of());

            assertThatThrownBy(file::toText)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("total length");
        }

        @Test
        void stillParsesATotalLengthMoonCannotRead() {
            assertThat(MoonAnnotationFile.parse(zlib("abc\nindent:true\ntrim:true\n")).header().orElseThrow().totalLength())
                    .isEqualTo("abc");
        }

        @Test
        void rejectsANonNumericPositionField() {
            String record = "#\n1\nbook.epub\n/p\n/p\nchapter\n0\n100\n10\n123\n1000\n\n\ntext\n0\n0\n0\n";

            assertThatThrownBy(() -> MoonAnnotationFile.parse(zlib(HEADER + record)))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("chapter");
        }

        @Test
        void rejectsContentBeforeTheFirstRecordDelimiter() {
            assertThatThrownBy(() -> MoonAnnotationFile.parse(zlib(HEADER + "stray\n#\n")))
                    .isInstanceOf(MoonFormatException.class);
        }

        @Test
        void rejectsAMalformedHeader() {
            assertThatThrownBy(() -> MoonAnnotationFile.parse(zlib("999\nfoo:true\ntrim:true\n")))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("header");
        }

        @Test
        void rejectsContentThatIsNotUtf8() {
            byte[] invalidUtf8 = deflate(new byte[]{'9', '\n', (byte) 0xC3, (byte) 0x28});

            assertThatThrownBy(() -> MoonAnnotationFile.parse(invalidUtf8))
                    .isInstanceOf(MoonFormatException.class)
                    .hasMessageContaining("UTF-8");
        }

        @Test
        void refusesToWriteAFieldThatWouldBreakTheLineStructure() {
            MoonAnnotationRecord broken = highlight("1", 0, 0, 0, "text").withNote("two\nlines");

            assertThatThrownBy(() -> MoonAnnotationFile.of(new MoonAnnotationHeader("1", true, true), List.of(broken)).toText())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("note");
        }

        private static byte[] deflate(byte[] bytes) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
                deflater.write(bytes);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return out.toByteArray();
        }
    }
}
