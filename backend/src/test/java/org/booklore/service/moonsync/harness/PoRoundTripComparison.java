package org.booklore.service.moonsync.harness;

import org.booklore.service.moonsync.position.format.MoonFormatException;
import org.booklore.service.moonsync.position.format.MoonPosition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * Comparison {@code po-roundtrip}: {@link MoonPosition} against readsync's {@code po_parser}
 * ({@code parse_po}, {@code serialize_po}, {@code with_percent}); reference side
 * {@code reference/harness/po_roundtrip.py}.
 *
 * <p>{@code serialized} is readsync's serialization of the parsed fields, which re-formats the percent with one
 * decimal; the Java equivalent is {@code withPercent(percent())}, not {@link MoonPosition#serialize()} (that one
 * keeps the percent as written, a Grimmory-only guarantee covered by {@code MoonPositionTest}).
 */
final class PoRoundTripComparison implements HarnessComparison {

    @Override
    public String name() {
        return "po-roundtrip";
    }

    @Override
    public JsonNode run(HarnessInput input) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        MoonPosition position;
        try {
            position = MoonPosition.parse(input.text());
        } catch (MoonFormatException e) {
            return out.put(JsonDiff.ERROR, e.getMessage());
        }
        switch (position) {
            case MoonPosition.Epub epub -> out.put("format", "epub").put("deviceId", epub.deviceId())
                    .put("percent", epub.percent()).put("chapter", epub.chapter()).put("split", epub.split())
                    .put("offset", epub.offset()).putNull("page");
            case MoonPosition.Pdf pdf -> out.put("format", "pdf").put("deviceId", pdf.deviceId())
                    .put("percent", pdf.percent()).putNull("chapter").putNull("split").putNull("offset")
                    .put("page", pdf.page());
        }
        out.put("scroll", position.webViewScrollOffset());
        out.put("serialized", position.withPercent(position.percent()).serialize());
        ArrayNode rewrites = out.putArray("withPercent");
        JsonNode percents = input.spec().path("percents");
        for (JsonNode percent : percents) {
            rewrites.add(position.withPercent(percent.asDouble()).serialize());
        }
        return out;
    }

    @Override
    public Optional<String> exemption(HarnessInput input, JsonNode reference, JsonNode java) {
        if (JsonDiff.isError(reference) && java.path("scroll").asString("").startsWith("-")) {
            return Optional.of("negative WebView scroll suffix: Grimmory accepts it, readsync takes digits only (spec deviation)");
        }
        return Optional.empty();
    }
}
