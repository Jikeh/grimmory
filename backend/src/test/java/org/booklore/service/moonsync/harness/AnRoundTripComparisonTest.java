package org.booklore.service.moonsync.harness;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnRoundTripComparisonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RECORD = "#\n1\nBook\n/p\n/p\n3\n0\n0\n5\n-1\n2\n\n\nHello\n0\n0\n0\n";
    private final AnRoundTripComparison comparison = new AnRoundTripComparison();

    private static HarnessInput inline(String text) {
        return new HarnessInput(JSON.createObjectNode().put("text", text), Path::of);
    }

    private static HarnessInput resource(String name) {
        try {
            Path dir = Path.of(Objects.requireNonNull(AnRoundTripComparisonTest.class.getResource("/moonsync/format")).toURI());
            return new HarnessInput(JSON.createObjectNode().put("file", name), dir::resolve);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What readsync outputs for a Java output: no bookmark/note fields, both slots folded into text. */
    private static JsonNode asReadsync(JsonNode java) {
        ObjectNode reference = (ObjectNode) java.deepCopy();
        for (String list : new String[]{"records", "reparsed"}) {
            for (JsonNode node : reference.path(list)) {
                ObjectNode record = (ObjectNode) node;
                // parse_an_epub drops the leading empty lines of [bookmark, note, text] and joins the rest.
                List<String> slots = new ArrayList<>(List.of(record.get("bookmark").asString(), record.get("note").asString(), record.get("text").asString()));
                while (slots.size() > 1 && slots.getFirst().isEmpty()) {
                    slots.removeFirst();
                }
                String folded = String.join("\n", slots);
                record.remove("bookmark");
                record.remove("note");
                record.put("text", folded);
            }
        }
        return reference;
    }

    @Test
    void readsJavaRecordsWithReadsyncsFieldNames() {
        JsonNode output = comparison.run(inline("1000\nindent:true\ntrim:false\n" + RECORD));
        assertThat(output.get("kind").asString()).isEqualTo("epub");
        assertThat(output.get("header")).isEqualTo(JSON.readTree("{\"totalLength\":\"1000\",\"indent\":true,\"trim\":false}"));
        assertThat(JsonDiff.diff(output.get("records").get(0), JSON.readTree("""
                {"id":"1","filename":"Book","path":"/p","lowerPath":"/p","chapter":3,"split":0,"offset":0,"length":5,
                 "color":-1,"time":2,"bookmark":"","note":"","text":"Hello","underline":"0","strikethrough":"0",
                 "bak":"0","deleted":false}"""), Set.of())).isEmpty();
        assertThat(output.get("reparsed")).isEqualTo(output.get("records"));
    }

    @Test
    void noteTextIsLeftOutWhereReadsyncCannotHaveIt() {
        HarnessInput input = resource("notes/step02-one-line-note.an");
        JsonNode java = comparison.run(input);
        int noted = 0;
        while (noted < java.get("records").size() && java.at("/records/" + noted + "/note").asString("").isEmpty()) {
            noted++;
        }
        assertThat(noted).as("a record with a note").isLessThan(java.get("records").size());

        HarnessJudge.CaseResult result = HarnessJudge.judge(comparison, "step02", input, asReadsync(java));

        assertThat(result.status()).isEqualTo(HarnessJudge.Status.SAME);
        assertThat(result.ignored()).containsKey("/records/" + noted + "/text").containsKey("/reparsed/0/note");
    }

    @Test
    void aChangedFieldIsADifference() {
        HarnessInput input = resource("notes/step01-no-note.an");
        ObjectNode reference = (ObjectNode) asReadsync(comparison.run(input));
        ((ObjectNode) reference.at("/records/0")).put("offset", 1);

        HarnessJudge.CaseResult result = HarnessJudge.judge(comparison, "step01", input, reference);

        assertThat(result.status()).isEqualTo(HarnessJudge.Status.DIFFERENT);
        assertThat(result.differences()).extracting(JsonDiff.Difference::path).containsExactly("/records/0/offset");
    }

    @Test
    void pdfFilesAreExempt() {
        HarnessInput input = resource("annotations_pdf_sample.an");
        JsonNode java = comparison.run(input);
        assertThat(java).isEqualTo(JSON.readTree("{\"kind\":\"pdf\"}"));
        assertThat(comparison.exemption(input, java, java)).isPresent();
    }

    @Test
    void headerLessFilesAreExempt() {
        HarnessInput input = inline("#\n0\n\n\n\n3\n0\n0\n0\n0\n2\n\nnote\n0\n0\n0\n*DELETED*\n");
        JsonNode java = comparison.run(input);
        assertThat(java.get("header").isNull()).isTrue();
        assertThat(comparison.exemption(input, JSON.readTree("{\"kind\":\"epub\",\"header\":{\"error\":\"x\"},\"records\":{\"error\":\"x\"}}"), java))
                .isPresent();
    }

    @Test
    void aLoneHashColumnIsExemptWhenReadsyncRejectsTheFile() {
        HarnessInput input = inline("1000\nindent:true\ntrim:true\n" + RECORD.replace("\nHello\n", "\n#\n"));
        JsonNode java = comparison.run(input);
        JsonNode rejected = JSON.readTree("{\"kind\":\"epub\",\"header\":{\"totalLength\":\"1000\",\"indent\":true,\"trim\":true},\"records\":{\"error\":\"x\"}}");
        assertThat(comparison.exemption(input, rejected, java)).isPresent();
        assertThat(comparison.exemption(input, asReadsync(java), java)).isEmpty();
    }

    @Test
    void aPartialRejectionComparesAsARejection() {
        HarnessInput input = resource("annotations_malformed.an");
        JsonNode java = comparison.run(input);
        JsonNode reference = JSON.readTree("{\"error\":\"zlib\"}");
        assertThat(HarnessJudge.judge(comparison, "malformed", input, reference).status()).isEqualTo(HarnessJudge.Status.SAME);
        assertThat(java.has("error")).isTrue();
    }
}
