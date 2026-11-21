package org.booklore.service.moonsync.position.epub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test books: small EPUBs built on the fly (the shapes readsync's own tests build with {@code zipfile}), the committed
 * test books, and books that can't be committed, found locally.
 *
 * <p>Books that aren't committed (readsync's {@code dorian_gray.epub} and {@code 1984.epub}, and the device-fixture
 * books whose fixture has {@code "resource": null}) are looked up in two folders, and tests needing them are skipped
 * (reported as skipped, never passed) when they are absent:
 * <ul>
 *   <li>{@code MOON_READSYNC_FIXTURES}: readsync's {@code tests/fixtures/grimmory};</li>
 *   <li>{@code MOON_LOCAL_BOOKS}: a folder holding the device test books under any name, matched by SHA-256.</li>
 * </ul>
 */
final class TestEpubs {

    static final String CONTAINER_XML = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestEpubs() {
    }

    /** Writes a zip of {@code entries} (name → UTF-8 text, in order) to {@code dir/name}. */
    static Path zip(Path dir, String name, Map<String, String> entries) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        entries.forEach((entry, text) -> bytes.put(entry, text.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(dir, name, bytes);
    }

    static Path zipBytes(Path dir, String name, Map<String, byte[]> entries) {
        Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    static Path write(Path file, byte[] bytes) {
        try {
            return Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An EPUB with the standard container, {@code OEBPS/content.opf}, optionally {@code OEBPS/toc.ncx}, and files. */
    static Path epub(Path dir, String opf, String ncx, Map<String, String> files) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", CONTAINER_XML);
        entries.put("OEBPS/content.opf", opf);
        if (ncx != null) {
            entries.put("OEBPS/toc.ncx", ncx);
        }
        files.forEach((name, text) -> entries.put("OEBPS/" + name, text));
        return zip(dir, "book.epub", entries);
    }

    /** A package document listing {@code hrefs} (ids {@code f0}, {@code f1}, …) in the spine, with an NCX item when asked. */
    static String opf(boolean withNcx, String... hrefs) {
        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        for (int i = 0; i < hrefs.length; i++) {
            manifest.append("    <item id=\"f").append(i).append("\" href=\"").append(hrefs[i])
                    .append("\" media-type=\"application/xhtml+xml\"/>\n");
            spine.append("    <itemref idref=\"f").append(i).append("\"/>\n");
        }
        if (withNcx) {
            manifest.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        }
        return """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata/>
                  <manifest>
                %s  </manifest>
                  <spine%s>
                %s  </spine>
                </package>
                """.formatted(manifest, withNcx ? " toc=\"ncx\"" : "", spine);
    }

    /** An NCX whose navMap is {@code navPoints} (already-written {@code <navPoint>} elements). */
    static String ncx(String navPoints) {
        return """
                <?xml version="1.0"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                %s
                  </navMap>
                </ncx>
                """.formatted(navPoints);
    }

    static String navPoint(String playOrder, String label, String src) {
        return "<navPoint id=\"np" + playOrder + "\" playOrder=\"" + playOrder + "\"><navLabel><text>" + label
                + "</text></navLabel><content src=\"" + src + "\"/></navPoint>";
    }

    static Path resource(String name) {
        URL url = TestEpubs.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalArgumentException("no test resource " + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A device fixture ({@code moonsync/device/<name>.json}). */
    static JsonNode deviceFixture(String name) {
        try {
            return JSON.readTree(Files.readString(resource("moonsync/device/" + name + ".json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The book of a device fixture: its committed resource, else a file in {@code MOON_LOCAL_BOOKS} with its SHA-256. */
    static Optional<Path> deviceBook(JsonNode fixture) {
        JsonNode book = fixture.get("book");
        if (book.hasNonNull("resource")) {
            return Optional.of(resource(book.get("resource").asString()));
        }
        return localFile("MOON_LOCAL_BOOKS", book.get("sha256").asString());
    }

    /** A file from readsync's {@code tests/fixtures/grimmory} ({@code MOON_READSYNC_FIXTURES}). */
    static Optional<Path> readsyncFixture(String name) {
        String dir = System.getenv("MOON_READSYNC_FIXTURES");
        if (dir == null || dir.isBlank()) {
            return Optional.empty();
        }
        Path file = Path.of(dir).resolve(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    private static Optional<Path> localFile(String variable, String sha256) {
        String dir = System.getenv(variable);
        if (dir == null || dir.isBlank() || !Files.isDirectory(Path.of(dir))) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            List<Path> candidates = files.filter(Files::isRegularFile).toList();
            for (Path candidate : candidates) {
                if (sha256(candidate).equals(sha256)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
