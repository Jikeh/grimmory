package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.position.epub.MoonChapterMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Comparison {@code epub-chapter-map}: {@link MoonChapterMap} against readsync's {@code EpubNcxCache}; reference side
 * {@code reference/harness/epub_chapter_map.py}, which defines the queries (every chapter index, strict and relaxed;
 * reverse lookups at 0 and around each relaxed anchor).
 */
final class EpubChapterMapComparison implements HarnessComparison {

    @Override
    public String name() {
        return "epub-chapter-map";
    }

    @Override
    public JsonNode run(HarnessInput input) {
        return EpubBooks.withEpub(input, (epub, out) -> {
            MoonChapterMap map = new MoonChapterMap(epub);
            int count = Math.max(epub.navPoints().map(List::size).orElse(0), epub.spineLength()) + 2;
            ArrayNode chapters = out.putArray("chapters");
            List<MoonChapterMap.SpineAnchor> relaxed = new ArrayList<>();
            for (int c = 0; c < count; c++) {
                Optional<MoonChapterMap.SpineAnchor> strictAnchor = map.resolveChapter(c, false);
                Optional<MoonChapterMap.SpineAnchor> relaxedAnchor = map.resolveChapter(c, true);
                relaxedAnchor.ifPresent(relaxed::add);
                ObjectNode chapter = chapters.addObject();
                chapter.set("strict", strictAnchor.map(a -> pair(a.spineIndex(), a.anchorOffset())).orElse(NULL));
                chapter.set("relaxed", relaxedAnchor.map(a -> pair(a.spineIndex(), a.anchorOffset())).orElse(NULL));
            }
            ArrayNode reverse = out.putArray("reverse");
            TreeMap<Integer, TreeSet<Integer>> probes = probes(epub.spineLength(), relaxed);
            probes.forEach((spine, offsets) -> offsets.forEach(offset -> {
                ObjectNode probe = reverse.addObject().put("spine", spine).put("offset", offset);
                probe.set("result", map.resolveSpinePosition(spine, offset).map(a -> pair(a.chapter(), a.anchorOffset())).orElse(NULL));
            }));
        });
    }

    /** The reference's {@code probes}: 0 in every spine file, plus each relaxed anchor and its neighbours. */
    static TreeMap<Integer, TreeSet<Integer>> probes(int spineLength, List<MoonChapterMap.SpineAnchor> relaxed) {
        TreeMap<Integer, TreeSet<Integer>> probes = new TreeMap<>();
        for (int spine = 0; spine < spineLength; spine++) {
            probes.put(spine, new TreeSet<>(List.of(0)));
        }
        for (MoonChapterMap.SpineAnchor anchor : relaxed) {
            TreeSet<Integer> offsets = probes.get(anchor.spineIndex());
            if (offsets != null) {
                for (int offset = anchor.anchorOffset() - 1; offset <= anchor.anchorOffset() + 1; offset++) {
                    if (offset >= 0) {
                        offsets.add(offset);
                    }
                }
            }
        }
        return probes;
    }

    private static final JsonNode NULL = JsonNodeFactory.instance.nullNode();

    private static JsonNode pair(int first, int second) {
        return JsonNodeFactory.instance.arrayNode().add(first).add(second);
    }
}
