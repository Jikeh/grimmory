package org.booklore.service.moonsync.harness;

import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

/**
 * The Java side of one differential comparison. The reference side is the Python module with the same
 * {@link #name()} in {@code tools/moon-harness/reference/harness/}; both turn the same input into JSON of the same
 * shape, and {@link HarnessJudge} compares them.
 *
 * <p>Known, documented differences from readsync (the spec's "Where this deliberately differs from readsync", and
 * readsync errors found on the device) are never "fixed" to match: a comparison declares them, with the reason,
 * through {@link #exemption} (the whole case) or {@link #ignoredFields} (single fields), and the report counts them.
 */
interface HarnessComparison {

    String name();

    /** The Java library's output for {@code input}. Failures are returned as {@code {"error": "..."}}, not thrown. */
    JsonNode run(HarnessInput input);

    /** Applied to both outputs before comparing; e.g. to reduce partial failures to a plain error. */
    default JsonNode comparable(JsonNode output) {
        return output;
    }

    /** A reason when this whole case is a known difference, so it is reported as exempt instead of compared. */
    default Optional<String> exemption(HarnessInput input, JsonNode reference, JsonNode java) {
        return Optional.empty();
    }

    /** Fields left out of the comparison for this case: JSON pointer → reason. */
    default Map<String, String> ignoredFields(HarnessInput input, JsonNode reference, JsonNode java) {
        return Map.of();
    }
}
