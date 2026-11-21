package org.booklore.service.moonsync.harness;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JsonDiffTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static Set<String> paths(String reference, String java, String... ignored) {
        Set<String> paths = new java.util.TreeSet<>();
        JsonDiff.diff(json(reference), json(java), Set.of(ignored)).forEach(d -> paths.add(d.path()));
        return paths;
    }

    @Test
    void numbersCompareByValue() {
        assertThat(paths("{\"a\":5,\"b\":1.50}", "{\"a\":5.0,\"b\":1.5}")).isEmpty();
        assertThat(paths("{\"a\":5}", "{\"a\":6}")).containsExactly("/a");
    }

    @Test
    void missingAndExtraKeysAndElementsAreDifferences() {
        assertThat(paths("{\"a\":1,\"b\":[1,2]}", "{\"c\":1,\"b\":[1]}")).containsExactly("/a", "/b/1", "/c");
    }

    @Test
    void nullIsNotMissing() {
        assertThat(paths("{\"a\":null}", "{}")).containsExactly("/a");
    }

    @Test
    void twoErrorsAreEqualWhateverTheMessage() {
        assertThat(paths("{\"x\":{\"error\":\"PoParseError: bad\"}}", "{\"x\":{\"error\":\"Unrecognized\"}}")).isEmpty();
        assertThat(paths("{\"x\":{\"error\":\"bad\"}}", "{\"x\":{\"v\":1}}")).containsExactly("/x/error", "/x/v");
    }

    @Test
    void ignoredPathsSkipTheirSubtree() {
        assertThat(paths("{\"r\":[{\"t\":\"a\",\"n\":1}]}", "{\"r\":[{\"t\":\"b\",\"n\":2,\"note\":\"x\"}]}", "/r/0/t", "/r/0/note"))
                .containsExactly("/r/0/n");
    }

    @Test
    void typesMustMatch() {
        assertThat(paths("{\"a\":\"1\"}", "{\"a\":1}")).containsExactly("/a");
    }

    @Test
    void keysArePointerEscaped() {
        assertThat(paths("{\"a/b\":1}", "{\"a/b\":2}")).containsExactly("/a~1b");
    }
}
