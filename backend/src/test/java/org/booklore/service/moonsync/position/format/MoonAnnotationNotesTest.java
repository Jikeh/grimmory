package org.booklore.service.moonsync.position.format;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Note slots of {@code .an} records (ticket 02), against real uploads from Moon+ 10.8 on the phone.
 *
 * <p>Fixtures in {@code /moonsync/format/notes/} are the {@code .an} files downloaded from Drive after each step
 * of a manual session (see the README there), plus the {@code notes} table rows exported from Moon+'s backup
 * after step 11, which is the authority for what each note really holds.
 *
 * <p>readsync reads every note as empty; this is a listed deviation, so these cases have no readsync counterpart.
 */
class MoonAnnotationNotesTest {

    private static final String DIR = "/moonsync/format/notes/";
    private static final List<String> STEPS = List.of(
            "step01-no-note.an",
            "step02-one-line-note.an",
            "step03-multi-line-note.an",
            "step04-special-chars-note.an",
            "step05-edited-note.an",
            "step06-bookmark.an",
            "step07-multi-paragraph-note.an",
            "step08-squiggly-note.an",
            "step09-deleted-without-record.an",
            "step10-second-book.an",
            "step11-after-force-stop.an",
            "step12-deleted-trash-bin.an",
            "step13-deleted-note-popup.an");

    static List<String> steps() {
        return STEPS;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = MoonAnnotationNotesTest.class.getResourceAsStream(DIR + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String decompressed(String name) throws IOException {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(fixture(name)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MoonAnnotationFile step(String name) throws IOException {
        return MoonAnnotationFile.parse(fixture(name));
    }

    private static MoonAnnotationRecord byOffset(MoonAnnotationFile file, long offset) {
        return file.records().stream()
                .filter(r -> !r.isDeleted() && r.offset() == offset)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no live record at offset " + offset));
    }

    private static MoonAnnotationRecord highlight(String note) {
        return new MoonAnnotationRecord("1", "book.epub", "/sdcard/book.epub", "/sdcard/book.epub",
                3, 0, 120, 4, 1996532479, 1700000000000L, "", note, "text", "0", "0", "0");
    }

    private static final MoonAnnotationHeader HEADER = new MoonAnnotationHeader("1000", true, true);

    @Nested
    class ReadingDeviceUploads {

        @Test
        void aHighlightWithoutANoteHasAnEmptyNote() throws IOException {
            MoonAnnotationRecord r = byOffset(step("step01-no-note.an"), 439);

            assertThat(r.noteText()).isEmpty();
            assertThat(r.text()).isEqualTo("There were impressive photos of the good professor looking serious and imposing.");
        }

        @Test
        void readsAOneLineNoteVerbatimIncludingItsTrailingSpace() throws IOException {
            MoonAnnotationRecord r = byOffset(step("step02-one-line-note.an"), 815);

            assertThat(r.noteText()).isEqualTo("Single line note sample ");
            assertThat(r.text()).isEqualTo("Oh, and that major market crash that’s coming?");
            assertThat(r.isHighlight()).isTrue();
        }

        @Test
        void decodesTheBrTagsOfAMultiLineNote() throws IOException {
            MoonAnnotationRecord r = byOffset(step("step03-multi-line-note.an"), 1096);

            assertThat(r.note()).isEqualTo("Multi line note<BR>Sample text ");
            assertThat(r.noteText()).isEqualTo("Multi line note\nSample text ");
            assertThat(r.text()).isEqualTo("Perhaps they simply didn’t place the emphasis correctly");
        }

        @Test
        void readsSpecialCharactersVerbatim() throws IOException {
            MoonAnnotationRecord r = byOffset(step("step04-special-chars-note.an"), 1629);

            assertThat(r.noteText())
                    .isEqualTo("Special chars note ăéïõù \"asd\" #$_-) _) *+*! */\":\" :*-$ 🤔 ");
            assertThat(r.text()).isEqualTo("True enough and so far so good.");
        }

        @Test
        void anEditedNoteReplacesTheOldOneInTheSameRecordPosition() throws IOException {
            MoonAnnotationFile file = step("step05-edited-note.an");

            assertThat(byOffset(file, 815).noteText()).isEqualTo("Single line note edited ");
            assertThat(file.records()).noneMatch(r -> r.noteText().contains("sample"));
        }

        @Test
        void aBookmarkKeepsItsNameInTheBookmarkSlotAndHasNoNote() throws IOException {
            MoonAnnotationRecord r = byOffset(step("step06-bookmark.an"), 0);

            assertThat(r.kind()).isEqualTo(MoonAnnotationRecord.Kind.BOOKMARK);
            assertThat(r.bookmark()).isEqualTo("Bookmark test ");
            assertThat(r.noteText()).isEmpty();
            assertThat(r.text()).isEmpty();
        }

        @Test
        void aMultiParagraphHighlightKeepsItsTextApartFromItsNote() throws IOException {
            MoonAnnotationRecord r = byOffset(step("step07-multi-paragraph-note.an"), 1919);

            assertThat(r.noteText()).isEqualTo("Multi parapgarph highlight ");
            assertThat(r.text()).startsWith("The professor responds: ").contains("<BR>Hold the bloody phone!");
            assertThat(r.highlightedText().split("\n", -1)).hasSize(3);
            assertThat(r.highlightedText()).doesNotContain("<BR>");
        }

        @Test
        void aSquigglyHighlightCarriesItsNote() throws IOException {
            MoonAnnotationRecord r = byOffset(step("step08-squiggly-note.an"), 2663);

            assertThat(r.noteText()).isEqualTo("Different stile note ");
            assertThat(r.bak()).isEqualTo("1");
            assertThat(r.color()).isEqualTo(-28160);
        }

        @Test
        void aDeletionCanArriveAsAPlainDisappearance() throws IOException {
            MoonAnnotationFile file = step("step09-deleted-without-record.an");

            assertThat(file.records()).noneMatch(MoonAnnotationRecord::isDeleted);
            assertThat(file.records()).noneMatch(r -> r.offset() == 1096);
        }

        @Test
        void aTrashBinDeleteWritesADeletedRecordKeepingTheNote() throws IOException {
            List<MoonAnnotationRecord> deleted = step("step12-deleted-trash-bin.an").records().stream()
                    .filter(MoonAnnotationRecord::isDeleted).toList();

            assertThat(deleted).singleElement().satisfies(r -> {
                assertThat(r.chapter()).isEqualTo(19);
                assertThat(r.split()).isZero();
                assertThat(r.offset()).isEqualTo(815);
                assertThat(r.bookmark()).isEmpty();
                assertThat(r.noteText()).isEqualTo("Single line note edited ");
            });
        }

        @Test
        void aNotePopupDeleteWritesOnlyItsOwnRecordAfterAReopen() throws IOException {
            List<MoonAnnotationRecord> deleted = step("step13-deleted-note-popup.an").records().stream()
                    .filter(MoonAnnotationRecord::isDeleted).toList();

            assertThat(deleted).singleElement().satisfies(r -> {
                assertThat(r.offset()).isEqualTo(2663);
                assertThat(r.noteText()).isEqualTo("Different stile note ");
            });
        }

        @Test
        void everyDecodedColumnMatchesMoonsOwnNotesTable() throws IOException {
            Map<String, List<String>> table = notesTable();
            List<MoonAnnotationRecord> records = new ArrayList<>(step("step11-after-force-stop.an").records());
            records.addAll(step("step10-second-book.an").records());

            assertThat(records).extracting(MoonAnnotationRecord::id).containsExactlyInAnyOrderElementsOf(table.keySet());
            for (MoonAnnotationRecord r : records) {
                List<String> row = table.get(r.id());
                assertThat(List.of(r.bookmark(), r.noteText(), r.highlightedText(), r.bak()))
                        .as("record %s", r.id())
                        .containsExactly(row.get(10), row.get(11), row.get(12), row.get(15));
            }
        }
    }

    @Nested
    class RoundTrips {

        @ParameterizedTest
        @MethodSource("org.booklore.service.moonsync.position.format.MoonAnnotationNotesTest#steps")
        void rewritingEveryNoteFromItsDecodedTextReproducesTheUploadExactly(String name) throws IOException {
            MoonAnnotationFile file = step(name);
            List<MoonAnnotationRecord> rewritten = file.records().stream()
                    .map(r -> r.withNoteText(r.noteText()))
                    .toList();

            assertThat(rewritten).isEqualTo(file.records());
            assertThat(file.withRecords(rewritten).toText()).isEqualTo(decompressed(name));
        }

        @ParameterizedTest
        @MethodSource("org.booklore.service.moonsync.position.format.MoonAnnotationNotesTest#steps")
        void layingOutTheRecordsAfreshMatchesMoonsOwnExport(String name) throws IOException {
            MoonAnnotationFile parsed = step(name);
            MoonAnnotationFile fresh = MoonAnnotationFile.of(parsed.header().orElseThrow(), parsed.records());

            assertThat(fresh.toText()).isEqualTo(decompressed(name));
        }
    }

    @Nested
    class WritingNotes {

        @Test
        void encodesLineBreaksAsBrTags() {
            MoonAnnotationRecord r = highlight("").withNoteText("first\nsecond\n\nfourth");

            assertThat(r.note()).isEqualTo("first<BR>second<BR><BR>fourth");
            assertThat(r.noteText()).isEqualTo("first\nsecond\n\nfourth");
        }

        @Test
        void writesTheNoteInItsSlotAndReadsItBack() {
            MoonAnnotationRecord r = highlight("").withNoteText("Multi line note\nSample text ");

            String text = MoonAnnotationFile.of(HEADER, List.of(r)).toText();

            assertThat(text.split("\n", -1)).containsSubsequence("", "Multi line note<BR>Sample text ", "text");
            assertThat(MoonAnnotationFile.parseText(text).records()).singleElement()
                    .satisfies(back -> assertThat(back.noteText()).isEqualTo("Multi line note\nSample text "));
        }

        @Test
        void keepsLeadingAndTrailingWhitespace() {
            assertThat(highlight("").withNoteText("  padded \n ").note()).isEqualTo("  padded <BR> ");
        }

        @Test
        void treatsCarriageReturnsAsTheLineBreaksMoonsLineReaderWouldSee() {
            assertThat(highlight("").withNoteText("a\r\nb\rc").noteText()).isEqualTo("a\nb\nc");
        }

        @Test
        void aLiteralBrTagInANoteComesBackAsALineBreakAsInMoon() {
            MoonAnnotationRecord r = highlight("").withNoteText("x <BR> y");

            assertThat(r.noteText()).isEqualTo("x \n y");
        }

        @Test
        void decodesBrTagsCaseSensitivelyLikeMoon() {
            assertThat(highlight("a<br>b<Br>c").noteText()).isEqualTo("a<br>b<Br>c");
        }

        @Test
        void aChangedDeletedRecordWithAMultiLineNoteStaysInMoonsFixedSlots() throws IOException {
            MoonAnnotationFile file = step("step13-deleted-note-popup.an");
            List<MoonAnnotationRecord> records = file.records().stream()
                    .map(r -> r.isDeleted() ? r.withNoteText("two\nlines") : r)
                    .toList();

            String text = file.withRecords(records).toText();
            MoonAnnotationRecord back = MoonAnnotationFile.parseText(text).records().getLast();

            assertThat(text).endsWith("\n\ntwo<BR>lines\n0\n0\n0\n*DELETED*\n");
            assertThat(back.isDeleted()).isTrue();
            assertThat(back.noteText()).isEqualTo("two\nlines");
        }

        @Test
        void aDeletedRecordsUnescapedMultiLineNoteDecodesToTheSameText() {
            String deleted = "#\n0\n0\n0\n0\n12\n0\n300\n0\n0\n1784406712345\n\nfirst<BR>second\nthird\n0\n0\n0\n*DELETED*\n";

            MoonAnnotationRecord r = MoonAnnotationFile.parseText("1000\nindent:true\ntrim:true\n" + deleted)
                    .records().getFirst();

            assertThat(r.noteText()).isEqualTo("first\nsecond\nthird");
        }

        @Test
        void rejectsANullNote() {
            assertThatThrownBy(() -> highlight("").withNoteText(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /** The notes table rows by {@code _id}, columns in {@code exportNotesToText} order. */
    private static Map<String, List<String>> notesTable() throws IOException {
        List<List<String>> rows = parseCsv(new String(fixture("notes-table-after-step11.csv"), StandardCharsets.UTF_8));
        assertThat(rows.getFirst()).containsExactly("_id", "book", "filename", "lowerFilename", "lastChapter",
                "lastSplitIndex", "lastPosition", "highlightLength", "highlightColor", "time", "bookmark", "note",
                "original", "underline", "strikethrough", "bak");
        Map<String, List<String>> byId = new HashMap<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            byId.put(row.getFirst(), row);
        }
        return byId;
    }

    /** RFC 4180: quoted fields, doubled quotes, line breaks inside quotes. */
    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
