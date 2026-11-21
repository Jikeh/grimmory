package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.position.epub.EpubContentException;
import org.booklore.service.moonsync.position.epub.EpubPackage;
import org.booklore.service.moonsync.position.epub.NavPoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.OptionalInt;

/**
 * Comparison {@code epub-structure}: {@link EpubPackage} against readsync's {@code EpubContentCache}; reference side
 * {@code reference/harness/epub_structure.py}. Per corpus EPUB: spine hrefs and paths, which spine items are
 * readable, the NCX navPoints, the trust gate and each navPoint's spine index.
 */
final class EpubStructureComparison implements HarnessComparison {

    @Override
    public String name() {
        return "epub-structure";
    }

    @Override
    public JsonNode run(HarnessInput input) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        EpubPackage epub;
        try {
            epub = EpubPackage.read(input.path());
        } catch (EpubContentException e) {
            return out.put(JsonDiff.ERROR, e.getMessage());
        }
        ArrayNode hrefs = out.putArray("spineHrefs");
        ArrayNode paths = out.putArray("spinePaths");
        ArrayNode readable = out.putArray("spineReadable");
        for (int i = 0; i < epub.spineLength(); i++) {
            hrefs.add(epub.spineHref(i));
            paths.add(epub.spinePath(i));
            readable.add(isReadable(epub, i));
        }
        List<NavPoint> navPoints = epub.navPoints().orElse(null);
        if (navPoints == null) {
            out.putNull("navPoints");
        } else {
            ArrayNode array = out.putArray("navPoints");
            for (NavPoint navPoint : navPoints) {
                ObjectNode node = array.addObject();
                if (navPoint.playOrder() == null) {
                    node.putNull("playOrder");
                } else {
                    node.put("playOrder", navPoint.playOrder());
                }
                node.put("href", navPoint.href()).put("fragment", navPoint.fragment());
            }
        }
        out.put("ncxTrusted", epub.ncxTrusted());
        if (navPoints == null) {
            out.putNull("navPointSpineIndex");
        } else {
            ArrayNode indexes = out.putArray("navPointSpineIndex");
            for (NavPoint navPoint : navPoints) {
                OptionalInt index = epub.ncxSpineIndex(navPoint.href());
                if (index.isPresent()) {
                    indexes.add(index.getAsInt());
                } else {
                    indexes.addNull();
                }
            }
        }
        return out;
    }

    private static boolean isReadable(EpubPackage epub, int spineIndex) {
        try {
            epub.spineContent(spineIndex);
            return true;
        } catch (EpubContentException e) {
            return false;
        }
    }
}
