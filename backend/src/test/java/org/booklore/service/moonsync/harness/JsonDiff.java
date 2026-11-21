package org.booklore.service.moonsync.harness;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structural comparison of a readsync output with the Java one.
 *
 * <p>Rules: objects must have the same keys; arrays the same length and order; numbers compare by value
 * ({@code 5} equals {@code 5.0}); everything else by equality. Two objects that both hold an {@code "error"} key
 * are equal whatever their messages say: the two sides word failures differently, only the fact of failing is
 * compared. Paths listed as ignored (JSON pointers; a path also ignores everything below it) are skipped.
 */
final class JsonDiff {

    /** The key marking a failed side: {@code {"error": "<message>"}}. */
    static final String ERROR = "error";

    /** One difference; a {@code null} side means the key or element is missing there. */
    record Difference(String path, @Nullable JsonNode reference, @Nullable JsonNode java) {
    }

    private JsonDiff() {
    }

    static boolean isError(@Nullable JsonNode output) {
        return output != null && output.isObject() && output.has(ERROR);
    }

    static List<Difference> diff(JsonNode reference, JsonNode java, Set<String> ignored) {
        List<Difference> differences = new ArrayList<>();
        walk("", reference, java, ignored, differences);
        return differences;
    }

    private static void walk(String path, @Nullable JsonNode reference, @Nullable JsonNode java, Set<String> ignored,
                             List<Difference> out) {
        if (isIgnored(path, ignored)) {
            return;
        }
        if (reference == null || java == null) {
            if (reference != java) {
                out.add(new Difference(path, reference, java));
            }
            return;
        }
        if (reference.isObject() && java.isObject()) {
            if (isError(reference) && isError(java)) {
                return;
            }
            Set<String> keys = new TreeSet<>(reference.propertyNames());
            keys.addAll(java.propertyNames());
            for (String key : keys) {
                walk(path + "/" + escape(key), reference.get(key), java.get(key), ignored, out);
            }
            return;
        }
        if (reference.isArray() && java.isArray()) {
            for (int i = 0; i < Math.max(reference.size(), java.size()); i++) {
                walk(path + "/" + i, reference.get(i), java.get(i), ignored, out);
            }
            return;
        }
        if (reference.isNumber() && java.isNumber()) {
            if (reference.decimalValue().compareTo(java.decimalValue()) != 0) {
                out.add(new Difference(path, reference, java));
            }
            return;
        }
        if (!reference.equals(java)) {
            out.add(new Difference(path, reference, java));
        }
    }

    private static boolean isIgnored(String path, Set<String> ignored) {
        for (String prefix : ignored) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** JSON pointer escaping (RFC 6901). */
    static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }
}
