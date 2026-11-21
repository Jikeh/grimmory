package org.booklore.service.moonsync.harness;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PoRoundTripComparisonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PoRoundTripComparison comparison = new PoRoundTripComparison();

    private static HarnessInput inline(String text, double... percents) {
        var spec = JSON.createObjectNode().put("text", text);
        var array = spec.putArray("percents");
        for (double p : percents) {
            array.add(p);
        }
        return new HarnessInput(spec, ref -> Path.of(ref));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static void assertSameJson(JsonNode actual, JsonNode expected) {
        assertThat(JsonDiff.diff(expected, actual, Set.of())).as("%s", actual).isEmpty();
    }

    @Test
    void epubOutputMatchesReadsyncsShape() {
        assertSameJson(comparison.run(inline("1*2@3#4:5.5%", 0.25, 100.0)), json("""
                {"format":"epub","deviceId":"1","percent":5.5,"chapter":2,"split":3,"offset":4,"page":null,"scroll":null,
                 "serialized":"1*2@3#4:5.5%","withPercent":["1*2@3#4:0.2%","1*2@3#4:100.0%"]}"""));
    }

    @Test
    void pdfOutputKeepsTheScrollSuffixAndReformatsThePercent() {
        assertSameJson(comparison.run(inline("1538849237101*22:100%|391")), json("""
                {"format":"pdf","deviceId":"1538849237101","percent":100.0,"chapter":null,"split":null,"offset":null,
                 "page":22,"scroll":"391","serialized":"1538849237101*22:100.0%|391","withPercent":[]}"""));
    }

    @Test
    void percentRewritesRoundLikeReadsync() {
        // Python's "%.1f" rounds the exact binary value half-to-even: 0.25 -> 0.2, 0.35 (just below) -> 0.3.
        JsonNode output = comparison.run(inline("1*0@0#0:1%", 0.05, 0.25, 0.35, 2.5, 12.345, 33.35, 99.95));
        assertThat(output.get("withPercent").valueStream().map(JsonNode::asString)).containsExactly(
                "1*0@0#0:0.1%", "1*0@0#0:0.2%", "1*0@0#0:0.3%", "1*0@0#0:2.5%", "1*0@0#0:12.3%", "1*0@0#0:33.4%",
                "1*0@0#0:100.0%");
    }

    @Test
    void unparseableInputIsAnError() {
        assertThat(comparison.run(inline("not a position")).has("error")).isTrue();
    }

    @Test
    void negativeScrollSuffixIsAnExemptSpecDeviationWhenReadsyncRejectsIt() {
        HarnessInput input = inline("1*2@3#4:5.5%|-7");
        JsonNode java = comparison.run(input);
        assertThat(comparison.exemption(input, json("{\"error\":\"PoParseError\"}"), java)).isPresent();
        assertThat(comparison.exemption(input, java, java)).isEmpty();
    }
}
