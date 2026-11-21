package org.booklore.service.moonsync.harness;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Runs the Java side of one case and compares it with readsync's output. Shared by the harness run and saved regression cases. */
final class HarnessJudge {

    enum Status { SAME, EXEMPT, DIFFERENT }

    /**
     * @param exemption why the case is exempt (status {@link Status#EXEMPT}), else {@code null}
     * @param ignored   fields left out of the comparison, pointer → reason
     */
    record CaseResult(String comparison, String id, JsonNode input, JsonNode reference, JsonNode java, Status status,
                      @Nullable String exemption, Map<String, String> ignored, List<JsonDiff.Difference> differences) {
    }

    private HarnessJudge() {
    }

    static CaseResult judge(HarnessComparison comparison, String id, HarnessInput input, JsonNode reference) {
        JsonNode java;
        try {
            java = comparison.run(input);
        } catch (RuntimeException e) {
            // run() should report failures itself; anything escaping is a harness bug, shown as a Java-side error.
            java = JsonNodeFactory.instance.objectNode().put(JsonDiff.ERROR, "harness: " + e);
        }
        Optional<String> exemption = comparison.exemption(input, reference, java);
        if (exemption.isPresent()) {
            return new CaseResult(comparison.name(), id, input.spec(), reference, java, Status.EXEMPT, exemption.get(), Map.of(), List.of());
        }
        Map<String, String> ignored = new TreeMap<>(comparison.ignoredFields(input, reference, java));
        List<JsonDiff.Difference> differences = JsonDiff.diff(comparison.normalized(reference), comparison.normalized(java), ignored.keySet());
        Status status = differences.isEmpty() ? Status.SAME : Status.DIFFERENT;
        return new CaseResult(comparison.name(), id, input.spec(), reference, java, status, null, ignored, differences);
    }
}
