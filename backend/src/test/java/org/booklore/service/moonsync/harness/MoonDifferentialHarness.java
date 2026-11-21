package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.harness.HarnessJudge.CaseResult;
import org.booklore.service.moonsync.harness.HarnessJudge.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The Java side of the Moon+ differential harness ({@code tools/moon-harness/README.md}).
 *
 * <p>Reads readsync's outputs from {@code <harness>/out/reference/<comparison>.json} (written by the reference
 * image), runs every case through the Java comparison of the same name, and writes {@code out/report.md} and
 * {@code out/report.json}. Inputs resolve under {@code <harness>/samples} and {@code <harness>/corpus}.
 */
final class MoonDifferentialHarness {

    static final int FORMAT_VERSION = 1;
    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** Every comparison, by name. Adding one: implement {@link HarnessComparison} and list it here (README). */
    static final Map<String, HarnessComparison> COMPARISONS = registry(new PoRoundTripComparison(), new AnRoundTripComparison(),
            new EpubStructureComparison(), new EpubParserParityComparison(), new EpubChapterMapComparison());

    /** Written by the reference side beside its outputs when only some comparisons were asked for. */
    static final String SELECTION_FILE = "selection.txt";
    private static final String MISSING = "missing reference output (registered on the Java side only?)";
    private static final String SKIPPED = "not run (left out with --only)";
    private static final String EMPTY = "no cases (no matching inputs in the samples or corpus folder?)";

    /**
     * @param missing   comparisons without reference output that should have had one: a full reference run (no
     *                  {@value #SELECTION_FILE}) covers every comparison, so a missing one means the Java and Python
     *                  registries disagree. Fails the run like a difference.
     * @param skipped   comparisons left out on purpose ({@code reference --only})
     * @param empty     comparisons with reference output but no cases (e.g. a samples folder override holding no
     *                  matching files): a green run with nothing compared would be misleading, so this fails it
     * @param revisions readsync revision per comparison, as the reference image recorded it
     */
    record Run(List<CaseResult> results, List<String> missing, List<String> skipped, List<String> empty,
               Map<String, String> revisions) {

        List<CaseResult> differences() {
            return results.stream().filter(r -> r.status() == Status.DIFFERENT).toList();
        }
    }

    /** Case counts of one comparison. */
    record Counts(int same, int exempt, int different) {

        static final Counts NONE = new Counts(0, 0, 0);

        int total() {
            return same + exempt + different;
        }

        Counts plus(Status status) {
            return switch (status) {
                case SAME -> new Counts(same + 1, exempt, different);
                case EXEMPT -> new Counts(same, exempt + 1, different);
                case DIFFERENT -> new Counts(same, exempt, different + 1);
            };
        }
    }

    private MoonDifferentialHarness() {
    }

    private static Map<String, HarnessComparison> registry(HarnessComparison... comparisons) {
        Map<String, HarnessComparison> byName = new LinkedHashMap<>();
        for (HarnessComparison comparison : comparisons) {
            if (byName.put(comparison.name(), comparison) != null) {
                throw new IllegalStateException("duplicate comparison " + comparison.name());
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    static Run run(Path harnessDir, Map<String, HarnessComparison> comparisons) {
        Path referenceDir = harnessDir.resolve("out/reference");
        if (!Files.isDirectory(referenceDir)) {
            throw new IllegalStateException("no reference output in " + referenceDir + "; run `just harness reference` first");
        }
        Function<String, Path> files = HarnessInput.mountedRoots(harnessDir);
        List<CaseResult> results = new ArrayList<>();
        Map<String, String> revisions = new TreeMap<>();
        List<String> missing = new ArrayList<>(new TreeMap<>(comparisons).keySet());
        List<String> empty = new ArrayList<>();
        for (Path file : jsonFiles(referenceDir)) {
            JsonNode document = read(file);
            String name = document.path("comparison").asString("");
            if (document.path("formatVersion").asInt() != FORMAT_VERSION) {
                throw new IllegalStateException(file + ": reference format version " + document.path("formatVersion") + ", expected " + FORMAT_VERSION);
            }
            HarnessComparison comparison = comparisons.get(name);
            if (comparison == null) {
                throw new IllegalStateException(file + ": readsync output for comparison \"" + name + "\", which has no Java side; known: " + comparisons.keySet());
            }
            missing.remove(name);
            revisions.put(name, document.path("readsyncRevision").asString("unknown"));
            if (document.path("cases").isEmpty()) {
                empty.add(name);
            }
            for (JsonNode c : document.path("cases")) {
                results.add(HarnessJudge.judge(comparison, c.get("id").asString(), new HarnessInput(c.get("input"), files), c.get("output")));
            }
        }
        List<String> skipped = new ArrayList<>();
        Path selection = referenceDir.resolve(SELECTION_FILE);
        if (Files.isRegularFile(selection)) {
            try {
                List<String> selected = Files.readAllLines(selection).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
                skipped.addAll(missing.stream().filter(name -> !selected.contains(name)).toList());
                missing.removeAll(skipped);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Run(results, missing, skipped, empty, revisions);
    }

    // --- report ---

    static void writeReport(Run run, Path outDir) {
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("report.md"), markdown(run), StandardCharsets.UTF_8);
            JSON.writeValue(outDir.resolve("report.json").toFile(), reportJson(run));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String summary(Run run) {
        StringBuilder out = new StringBuilder();
        counts(run).forEach((name, c) -> out.append(String.format("%s: %d cases, %d same, %d exempt, %d different%n",
                name, c.total(), c.same(), c.exempt(), c.different())));
        run.missing().forEach(name -> out.append(name).append(": ").append(MISSING).append('\n'));
        run.skipped().forEach(name -> out.append(name).append(": ").append(SKIPPED).append('\n'));
        run.empty().forEach(name -> out.append(name).append(": ").append(EMPTY).append('\n'));
        return out.toString();
    }

    private static Map<String, Counts> counts(Run run) {
        Map<String, Counts> counts = new TreeMap<>();
        for (CaseResult r : run.results()) {
            counts.put(r.comparison(), counts.getOrDefault(r.comparison(), Counts.NONE).plus(r.status()));
        }
        return counts;
    }

    private static String markdown(Run run) {
        StringBuilder md = new StringBuilder("# Moon+ differential harness report\n\n");
        md.append(run.differences().isEmpty() ? "**No differences.**\n\n" : "**" + run.differences().size() + " differing case(s).**\n\n");
        if (!run.empty().isEmpty()) {
            md.append("**No cases for: ").append(String.join(", ", run.empty())).append(".**\n\n");
        }
        if (!run.missing().isEmpty()) {
            md.append("**Missing reference output for: ").append(String.join(", ", run.missing())).append(".**\n\n");
        }
        md.append("| Comparison | readsync | Cases | Same | Exempt | Different |\n|---|---|---|---|---|---|\n");
        counts(run).forEach((name, c) -> md.append(String.format("| %s | %s | %d | %d | %d | %d |%n", name,
                run.revisions().getOrDefault(name, "?"), c.total(), c.same(), c.exempt(), c.different())));
        run.missing().forEach(name -> md.append("| ").append(name).append(" | – | **").append(MISSING).append("** | | | |\n"));
        run.skipped().forEach(name -> md.append("| ").append(name).append(" | – | ").append(SKIPPED).append(" | | | |\n"));

        Map<String, Integer> exemptions = new TreeMap<>();
        Map<String, Integer> ignored = new TreeMap<>();
        for (CaseResult r : run.results()) {
            if (r.exemption() != null) {
                exemptions.merge(r.comparison() + ": " + r.exemption(), 1, Integer::sum);
            }
            r.ignored().values().stream().distinct().forEach(reason -> ignored.merge(r.comparison() + ": " + reason, 1, Integer::sum));
        }
        if (!exemptions.isEmpty() || !ignored.isEmpty()) {
            md.append("\n## Known differences (not compared)\n\n");
            exemptions.forEach((reason, n) -> md.append("- whole case, ").append(n).append("×: ").append(reason).append('\n'));
            ignored.forEach((reason, n) -> md.append("- fields, in ").append(n).append(" case(s): ").append(reason).append('\n'));
        }
        if (!run.differences().isEmpty()) {
            md.append("\n## Differences\n");
            for (CaseResult r : run.differences()) {
                md.append("\n### ").append(r.comparison()).append(" — `").append(r.id()).append("`\n\n");
                md.append("| Field | readsync | Java |\n|---|---|---|\n");
                for (JsonDiff.Difference d : r.differences()) {
                    md.append("| `").append(d.path().isEmpty() ? "(whole output)" : d.path()).append("` | ")
                            .append(cell(d.reference())).append(" | ").append(cell(d.java())).append(" |\n");
                }
                md.append("\n<details><summary>Input and both outputs</summary>\n\n```json\n")
                        .append(JSON.writeValueAsString(caseJson(r))).append("\n```\n\n</details>\n");
            }
        }
        return md.toString();
    }

    private static String cell(JsonNode value) {
        String text = value == null ? "(missing)" : "`" + JSON.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value) + "`";
        return text.replace("|", "\\|").replace("\n", " ");
    }

    private static ObjectNode reportJson(Run run) {
        ObjectNode report = JSON.createObjectNode();
        report.put("differences", run.differences().size());
        report.set("summary", JSON.valueToTree(counts(run)));
        report.set("missing", JSON.valueToTree(run.missing()));
        report.set("skipped", JSON.valueToTree(run.skipped()));
        report.set("empty", JSON.valueToTree(run.empty()));
        ArrayNode cases = report.putArray("cases");
        run.results().stream().filter(r -> r.status() != Status.SAME).forEach(r -> cases.add(caseJson(r)));
        return report;
    }

    private static ObjectNode caseJson(CaseResult r) {
        ObjectNode node = JSON.createObjectNode().put("comparison", r.comparison()).put("id", r.id()).put("status", r.status().name());
        if (r.exemption() != null) {
            node.put("exemption", r.exemption());
        }
        node.set("input", r.input());
        node.set("readsync", r.reference());
        node.set("java", r.java());
        if (!r.differences().isEmpty()) {
            ArrayNode diffs = node.putArray("differences");
            for (JsonDiff.Difference d : r.differences()) {
                ObjectNode diff = diffs.addObject().put("path", d.path());
                diff.set("readsync", d.reference());
                diff.set("java", d.java());
            }
        }
        return node;
    }

    // --- regression cases ---

    /**
     * Writes each differing case as a regression case under {@code regressionsDir/<comparison>/}: the input file
     * copied beside a {@code <slug>.json} holding the input and readsync's output as the expected value.
     * {@link MoonHarnessRegressionTest} runs them. Returns the written case files.
     */
    static List<Path> saveRegressions(Run run, Path harnessDir, Path regressionsDir) {
        Function<String, Path> files = HarnessInput.mountedRoots(harnessDir);
        List<Path> written = new ArrayList<>();
        for (CaseResult r : run.differences()) {
            try {
                Path dir = Files.createDirectories(regressionsDir.resolve(r.comparison()));
                String slug = slug(r.id());
                ObjectNode input = (ObjectNode) r.input().deepCopy();
                if (input.has("file")) {
                    String ref = input.get("file").asString();
                    String copy = slug + extension(ref);
                    Files.copy(files.apply(ref), dir.resolve(copy), StandardCopyOption.REPLACE_EXISTING);
                    input.put("file", copy);
                }
                ObjectNode regression = JSON.createObjectNode()
                        .put("comparison", r.comparison())
                        .put("id", r.id())
                        .put("readsyncRevision", run.revisions().getOrDefault(r.comparison(), "unknown"))
                        .put("note", "Saved by the differential harness; expected is readsync's output. Fix the Java side, "
                                + "or, if this is a deliberate deviation, declare it in the comparison and delete this case.");
                regression.set("input", input);
                regression.set("expected", r.reference());
                Path caseFile = dir.resolve(slug + ".json");
                JSON.writeValue(caseFile.toFile(), regression);
                written.add(caseFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return written;
    }

    /** A file-name-safe, stable name for a case id: readable prefix plus a hash of the full id. */
    static String slug(String id) {
        String readable = id.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (readable.length() > 60) {
            readable = readable.substring(readable.length() - 60);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return readable.replaceAll("^[._-]+", "") + "-" + HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extension(String ref) {
        String name = ref.substring(ref.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    static List<Path> jsonFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode read(Path file) {
        return JSON.readTree(file.toFile());
    }
}
