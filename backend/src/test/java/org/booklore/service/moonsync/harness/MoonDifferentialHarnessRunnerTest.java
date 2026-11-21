package org.booklore.service.moonsync.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoonDifferentialHarnessRunnerTest {

    @TempDir
    Path harness;

    private void reference(String comparison, String cases) throws IOException {
        Files.createDirectories(harness.resolve("out/reference"));
        Files.writeString(harness.resolve("out/reference/" + comparison + ".json"), """
                {"formatVersion":1,"comparison":"%s","readsyncRevision":"abc123","cases":%s}""".formatted(comparison, cases));
    }

    private void sample(String name, String content) throws IOException {
        Path file = harness.resolve("samples").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static final String SAME_OUTPUT = """
            {"format":"epub","deviceId":"1","percent":5.5,"chapter":2,"split":3,"offset":4,"page":null,"scroll":null,
             "serialized":"1*2@3#4:5.5%","withPercent":[]}""";

    @Test
    void reportsEveryDifferingCaseWithBothOutputsAndSavesThemAsRegressions() throws IOException {
        sample("format/a.po", "1*2@3#4:5.5%\n");
        reference("po-roundtrip", """
                [{"id":"samples/format/a.po","input":{"file":"samples/format/a.po","percents":[]},"output":%s},
                 {"id":"inline-same","input":{"text":"1*2@3#4:5.5%%","percents":[]},"output":%s}]"""
                .formatted(SAME_OUTPUT.replace("\"offset\":4", "\"offset\":5"), SAME_OUTPUT));

        MoonDifferentialHarness.Run run = MoonDifferentialHarness.run(harness, MoonDifferentialHarness.COMPARISONS);

        assertThat(run.results()).hasSize(2);
        assertThat(run.differences()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("samples/format/a.po");
            assertThat(r.differences()).extracting(JsonDiff.Difference::path).containsExactly("/offset");
        });
        assertThat(run.missing()).containsExactly("an-roundtrip");
        assertThat(MoonDifferentialHarness.summary(run)).contains("po-roundtrip: 2 cases, 1 same, 0 exempt, 1 different")
                .contains("an-roundtrip: not run");

        MoonDifferentialHarness.writeReport(run, harness.resolve("out"));
        String report = Files.readString(harness.resolve("out/report.md"));
        assertThat(report).contains("1 differing case(s)", "samples/format/a.po", "| `/offset` | `5` | `4` |", "abc123");
        JsonNode reportJson = MoonDifferentialHarness.read(harness.resolve("out/report.json"));
        assertThat(reportJson.get("differences").asInt()).isEqualTo(1);
        assertThat(reportJson.at("/cases/0/java/offset").asInt()).isEqualTo(4);

        Path regressions = harness.resolve("regressions");
        List<Path> saved = MoonDifferentialHarness.saveRegressions(run, harness, regressions);
        assertThat(saved).singleElement().satisfies(caseFile -> {
            JsonNode regression = MoonDifferentialHarness.read(caseFile);
            assertThat(regression.at("/expected/offset").asInt()).isEqualTo(5);
            String copy = regression.at("/input/file").asString();
            assertThat(caseFile.resolveSibling(copy)).hasContent("1*2@3#4:5.5%");
            HarnessInput input = new HarnessInput(regression.get("input"), ref -> caseFile.resolveSibling(ref));
            assertThat(HarnessJudge.judge(new PoRoundTripComparison(), "x", input, regression.get("expected")).status())
                    .isEqualTo(HarnessJudge.Status.DIFFERENT);
        });
    }

    @Test
    void noDifferencesWhenBothSidesAgree() throws IOException {
        reference("po-roundtrip", "[{\"id\":\"i\",\"input\":{\"text\":\"1*2@3#4:5.5%%\",\"percents\":[]},\"output\":%s}]".formatted(SAME_OUTPUT));
        MoonDifferentialHarness.Run run = MoonDifferentialHarness.run(harness, MoonDifferentialHarness.COMPARISONS);
        MoonDifferentialHarness.writeReport(run, harness.resolve("out"));
        assertThat(run.differences()).isEmpty();
        assertThat(Files.readString(harness.resolve("out/report.md"))).contains("No differences.");
    }

    @Test
    void readsyncOutputWithoutAJavaSideFails() throws IOException {
        reference("mystery", "[]");
        assertThatThrownBy(() -> MoonDifferentialHarness.run(harness, MoonDifferentialHarness.COMPARISONS))
                .hasMessageContaining("no Java side");
    }

    @Test
    void missingReferenceOutputFailsWithAHint() {
        assertThatThrownBy(() -> MoonDifferentialHarness.run(harness, MoonDifferentialHarness.COMPARISONS))
                .hasMessageContaining("just harness reference");
    }

    @Test
    void inputsCannotEscapeTheMountedRoots() {
        var files = HarnessInput.mountedRoots(harness);
        assertThat(files.apply("corpus/a/b.epub")).isEqualTo(harness.resolve("corpus/a/b.epub"));
        assertThatThrownBy(() -> files.apply("samples/../secrets")).hasMessageContaining("escapes");
        assertThatThrownBy(() -> files.apply("elsewhere/x")).hasMessageContaining("samples/<path>");
    }

    @Test
    void slugsAreFileSafeAndDistinct() {
        String a = MoonDifferentialHarness.slug("samples/device/ulysses.json#U1");
        String b = MoonDifferentialHarness.slug("samples/device/ulysses.json#U2");
        assertThat(a).matches("[A-Za-z0-9._-]+").isNotEqualTo(b).startsWith("samples_device_ulysses.json_U1-");
    }
}
