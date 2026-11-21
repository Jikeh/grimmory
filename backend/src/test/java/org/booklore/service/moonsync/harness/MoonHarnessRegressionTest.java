package org.booklore.service.moonsync.harness;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differences the harness found, saved as regression cases ({@code just harness run save=1}) in
 * {@code src/test/resources/moonsync/harness/regressions/<comparison>/}: each must now match readsync's recorded
 * output, or be a declared exemption. Runs in the normal test suite; needs neither readsync nor Docker.
 */
class MoonHarnessRegressionTest {

    @TestFactory
    Stream<DynamicTest> savedDifferencesNowMatchReadsync() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(getClass().getResource("/moonsync/harness/regressions"),
                "moonsync/harness/regressions test resource folder").toURI());
        return MoonDifferentialHarness.jsonFiles(root).stream().map(file -> DynamicTest.dynamicTest(
                root.relativize(file).toString(), () -> {
                    JsonNode regression = MoonDifferentialHarness.read(file);
                    String name = regression.get("comparison").asString();
                    HarnessComparison comparison = MoonDifferentialHarness.COMPARISONS.get(name);
                    assertThat(comparison).as("comparison %s", name).isNotNull();
                    HarnessInput input = new HarnessInput(regression.get("input"), ref -> file.resolveSibling(ref).normalize());
                    HarnessJudge.CaseResult result = HarnessJudge.judge(comparison, regression.get("id").asString(), input, regression.get("expected"));
                    assertThat(result.status())
                            .as("%s differs from readsync: %s", regression.get("id").asString(), result.differences())
                            .isNotEqualTo(HarnessJudge.Status.DIFFERENT);
                }));
    }
}
