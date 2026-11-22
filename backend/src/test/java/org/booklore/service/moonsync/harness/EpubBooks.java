package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.position.epub.EpubContentException;
import org.booklore.service.moonsync.position.epub.EpubPackage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.function.BiConsumer;

/** What the EPUB comparisons share: a corpus book read as an {@link EpubPackage}, or the failure as the output. */
final class EpubBooks {

    private EpubBooks() {
    }

    /** {@code fill}'s output for the input's book, or {@code {"error": ...}} when the book can't be read. */
    static JsonNode withEpub(HarnessInput input, BiConsumer<EpubPackage, ObjectNode> fill) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        EpubPackage epub;
        try {
            epub = EpubPackage.read(input.path());
        } catch (EpubContentException e) {
            return out.put(JsonDiff.ERROR, e.getMessage());
        }
        fill.accept(epub, out);
        return out;
    }
}
