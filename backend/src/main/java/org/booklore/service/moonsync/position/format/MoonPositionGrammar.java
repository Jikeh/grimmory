package org.booklore.service.moonsync.position.format;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/** {@code .po} grammar shared by {@link MoonPosition#parse} and the record constructors, so anything constructible also parses. */
final class MoonPositionGrammar {

    private static final String DEVICE = "(?<device>\\d+)";
    private static final String PERCENT = ":(?<percent>\\d+(?:\\.\\d+)?)%";
    private static final String SCROLL = "(?:\\|(?<scroll>\\d+))?";
    static final Pattern EPUB = Pattern.compile(DEVICE + "\\*(?<chapter>\\d+)@(?<split>\\d+)#(?<offset>\\d+)" + PERCENT + SCROLL);
    static final Pattern PDF = Pattern.compile(DEVICE + "\\*(?<page>\\d+)" + PERCENT + SCROLL);
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern DECIMAL = Pattern.compile("\\d+(?:\\.\\d+)?");

    private MoonPositionGrammar() {
    }

    static void requireValid(String deviceId, String percentText, @Nullable String webViewScrollOffset) {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(percentText, "percentText");
        if (!DIGITS.matcher(deviceId).matches()) {
            throw new IllegalArgumentException("Moon+ device id must be digits: " + deviceId);
        }
        if (!DECIMAL.matcher(percentText).matches()) {
            throw new IllegalArgumentException("Moon+ percent must be a plain decimal: " + percentText);
        }
        if (webViewScrollOffset != null && !DIGITS.matcher(webViewScrollOffset).matches()) {
            throw new IllegalArgumentException("Moon+ WebView scroll offset must be digits: " + webViewScrollOffset);
        }
    }

    static String suffix(@Nullable String webViewScrollOffset) {
        return webViewScrollOffset == null ? "" : "|" + webViewScrollOffset;
    }
}
