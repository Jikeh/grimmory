package org.booklore.service.moonsync.harness;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * One case's input, as the reference adapter wrote it ({@code tools/moon-harness/reference/harness/inputs.py}):
 * {@code {"file": "samples/<path>"}} / {@code {"file": "corpus/<path>"}} for a mounted file, or
 * {@code {"text": "..."}} for inline content, plus comparison-specific keys.
 *
 * @param spec  the input object
 * @param files resolves a {@code "file"} value to a local path (the mounted roots in a harness run, the case's own
 *              folder for a saved regression case)
 */
record HarnessInput(JsonNode spec, Function<String, Path> files) {

    boolean isFile() {
        return spec.has("file");
    }

    byte[] bytes() {
        if (isFile()) {
            try {
                return Files.readAllBytes(files.apply(spec.get("file").asString()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return spec.get("text").asString().getBytes(StandardCharsets.UTF_8);
    }

    String text() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    /** Resolves {@code samples/…} and {@code corpus/…} under a harness folder, refusing anything outside them. */
    static Function<String, Path> mountedRoots(Path harnessDir) {
        return ref -> {
            int slash = ref.indexOf('/');
            String root = slash < 0 ? ref : ref.substring(0, slash);
            if (slash < 0 || !(root.equals("samples") || root.equals("corpus"))) {
                throw new IllegalArgumentException("input file must be samples/<path> or corpus/<path>, got " + ref);
            }
            Path base = harnessDir.resolve(root).normalize();
            Path path = base.resolve(ref.substring(slash + 1)).normalize();
            if (!path.startsWith(base)) {
                throw new IllegalArgumentException("input file escapes its root: " + ref);
            }
            return path;
        };
    }
}
