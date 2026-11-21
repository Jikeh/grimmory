package org.booklore.service.moonsync.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entry point of the Java side of the differential harness: runs only inside {@code just harness java} (or
 * {@code just harness run}), which mounts the harness folder and sets {@code MOON_HARNESS_DIR}.
 * {@code MOON_HARNESS_SAVE=1} also saves each difference as a regression case.
 */
@EnabledIfEnvironmentVariable(named = "MOON_HARNESS_DIR", matches = ".+")
class MoonDifferentialHarnessTest {

    static final Path REGRESSIONS = Path.of("src/test/resources/moonsync/harness/regressions");

    @Test
    void javaLibraryMatchesReadsync() {
        Path harnessDir = Path.of(System.getenv("MOON_HARNESS_DIR"));
        MoonDifferentialHarness.Run run = MoonDifferentialHarness.run(harnessDir, MoonDifferentialHarness.COMPARISONS);
        MoonDifferentialHarness.writeReport(run, harnessDir.resolve("out"));
        String summary = MoonDifferentialHarness.summary(run);
        System.out.print(summary);

        List<Path> saved = "1".equals(System.getenv("MOON_HARNESS_SAVE"))
                ? MoonDifferentialHarness.saveRegressions(run, harnessDir, REGRESSIONS)
                : List.of();
        saved.forEach(path -> System.out.println("saved regression case " + path));

        assertThat(run.missing())
                .as("comparisons with a Java side but no readsync output (register them in reference/adapter/__init__.py)")
                .isEmpty();
        assertThat(run.differences())
                .as("differences from readsync (see tools/moon-harness/out/report.md):%n%s", summary)
                .isEmpty();
    }
}
