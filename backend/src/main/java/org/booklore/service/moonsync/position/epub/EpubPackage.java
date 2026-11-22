package org.booklore.service.moonsync.position.epub;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * An EPUB's package structure as Moon+ sync needs it: the spine (in reading order, as manifest hrefs), the NCX
 * navPoints, and each spine item's content. A port of readsync's {@code EpubContentCache}
 * ({@code connectors/grimmory/epub_content.py}), which the differential harness compares it against
 * ({@code epub-structure}); its rules are kept as they are, including where they are lenient:
 * <ul>
 *   <li>{@code META-INF/container.xml}'s first {@code rootfile} names the package document; paths in the zip are
 *       matched exactly, then case-insensitively.</li>
 *   <li>The spine is the {@code itemref}s whose {@code idref} names a manifest item; an {@code itemref} without
 *       {@code idref} is skipped, one naming no manifest item fails the book.</li>
 *   <li>The NCX is the manifest item {@code <spine toc>} names, else the first item with the NCX media type. It is
 *       read leniently: anything wrong with it means "no NCX", never a failed book. Navpoints are flattened in
 *       document order; one without {@code content/@src} is skipped, one without a usable {@code playOrder} kept.</li>
 *   <li>The NCX is <em>trusted</em> (Moon+'s chapter index is an ordinal into it, not a spine index) when it has more
 *       than one navPoint: every single-navPoint NCX in readsync's 26-book corpus was a generator stub.</li>
 *   <li>Hrefs resolve by joining them to the package document's folder (no {@code ../} handling, like readsync);
 *       an NCX href matches a spine href when both are equal after percent-decoding.</li>
 * </ul>
 * Zip entry names without the UTF-8 flag are read as CP437, as Python's {@code zipfile} does. Spine content must be
 * UTF-8.
 */
public final class EpubPackage {

    private static final String CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container";
    private static final String OPF_NS = "http://www.idpf.org/2007/opf";
    private static final String NCX_NS = "http://www.daisy.org/z3986/2005/ncx/";
    private static final String NCX_MEDIA_TYPE = "application/x-dtbncx+xml";
    private static final Charset ZIP_NAME_FALLBACK = Charset.forName("IBM437");
    /** A {@code navPoint} start tag, with or without a prefix, in lower-cased text. */
    private static final Pattern NAV_POINT_TAG = Pattern.compile("<(?:[a-z0-9_.-]+:)?navpoint[\\s>/]");
    /** Python's {@code int()}: optional surrounding whitespace and sign, decimal digits, single underscores between. */
    private static final Pattern PYTHON_INT = Pattern.compile("([+-]?)(\\p{Nd}+(?:_\\p{Nd}+)*)");
    private static final DocumentBuilderFactory XML = xmlFactory();

    private final Path file;
    private final String opfDir;
    private final List<String> spineHrefs;
    private final @Nullable List<NavPoint> navPoints;
    private final boolean epub3Nav;
    private final List<String> entryNames;
    private final @Nullable String ncxEntry;
    private final @Nullable String moonNcx;
    private final int moonNavPoints;
    private final boolean irregularNcx;
    private final List<NavTocEntry> navTocEntries;

    private EpubPackage(Path file, String opfDir, List<String> spineHrefs, @Nullable List<NavPoint> navPoints,
                        boolean epub3Nav, List<String> entryNames, @Nullable String ncxEntry,
                        @Nullable String moonNcx, int moonNavPoints, boolean irregularNcx,
                        List<NavTocEntry> navTocEntries) {
        this.file = file;
        this.opfDir = opfDir;
        this.spineHrefs = spineHrefs;
        this.navPoints = navPoints;
        this.epub3Nav = epub3Nav;
        this.entryNames = entryNames;
        this.ncxEntry = ncxEntry;
        this.moonNcx = moonNcx;
        this.moonNavPoints = moonNavPoints;
        this.irregularNcx = irregularNcx;
        this.navTocEntries = navTocEntries;
    }

    /**
     * Reads the package structure of the EPUB at {@code file}; spine content is read from the file on demand.
     *
     * @throws EpubContentException when the zip, the container or the package document can't be read
     */
    public static EpubPackage read(Path file) {
        try (ZipFile zip = open(file)) {
            String opfPath = findOpfPath(zip);
            String opfDir = dirname(opfPath);
            byte[] opfBytes = readEntry(zip, opfPath);
            Document opf = parseXml(opfBytes);
            List<String> spine = parseSpineHrefs(opf);
            String ncxHref = findNcxHref(opf);
            ZipEntry ncx = ncxHref == null ? null : findEntry(zip, resolveRelative(opfDir, ncxHref));
            NcxRead read = ncx == null ? new NcxRead(null, false) : readNcx(zip, ncx);
            List<NavPoint> navPoints = read.navPoints();
            List<String> names = Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList();
            ZipEntry navEntry = moonNavEntry(zip, new String(opfBytes, StandardCharsets.UTF_8), opfDir, names);
            String moonNcx = moonNcxEntry(names);
            int moonNavPoints = moonNcx == null ? 0 : countNavPointTags(zip, moonNcx);
            return new EpubPackage(file, opfDir, spine, navPoints, navEntry != null, names,
                    ncx == null ? null : ncx.getName(), moonNcx, moonNavPoints, read.irregular(),
                    navEntry == null ? List.of() : navTocEntries(zip, navEntry, names));
        } catch (IOException | SAXException e) {
            throw new EpubContentException("failed to parse EPUB structure of " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The package document's folder in the zip ({@code ""} when it sits at the root). */
    public String opfDir() {
        return opfDir;
    }

    /** Spine items in reading order, as manifest hrefs (relative to {@link #opfDir()}). */
    public List<String> spineHrefs() {
        return spineHrefs;
    }

    public int spineLength() {
        return spineHrefs.size();
    }

    /**
     * @throws EpubContentException when {@code spineIndex} is out of range
     */
    public String spineHref(int spineIndex) {
        if (spineIndex < 0 || spineIndex >= spineHrefs.size()) {
            throw new EpubContentException("spine index " + spineIndex + " out of range (spine has " + spineHrefs.size() + " items)");
        }
        return spineHrefs.get(spineIndex);
    }

    /**
     * The spine item's path from the zip root, the form Grimmory stores in its EPUB progress ({@code OEBPS/Text/021.html}
     * where the manifest says {@code Text/021.html}).
     */
    public String spinePath(int spineIndex) {
        return resolveRelative(opfDir, spineHref(spineIndex));
    }

    /**
     * The spine item's (X)HTML, decoded as UTF-8 (a byte-order mark stays in the text, as in readsync).
     *
     * @throws EpubContentException when the index is out of range, the entry is missing or it isn't UTF-8
     */
    public String spineContent(int spineIndex) {
        String path = spinePath(spineIndex);
        byte[] bytes;
        try (ZipFile zip = open(file)) {
            bytes = readEntry(zip, path);
        } catch (IOException e) {
            throw new EpubContentException("failed to read spine content " + path + ": " + e.getMessage(), e);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new EpubContentException("spine content " + path + " is not valid UTF-8", e);
        }
    }

    /** The NCX's navPoints in document order, or empty when the book has no usable NCX. */
    public Optional<List<NavPoint>> navPoints() {
        return Optional.ofNullable(navPoints);
    }

    /** Whether Moon+'s chapter index is an ordinal into the NCX (more than one navPoint) rather than a spine index. */
    public boolean ncxTrusted() {
        return navPoints != null && navPoints.size() > 1;
    }

    /**
     * Whether Moon+ finds an EPUB 3 navigation document ({@code Epub.getTocFilenameEpub3}: the literal text
     * {@code properties="nav"} in the package document, that tag's {@code href}, a file in the zip). Moon+ reads its
     * chapter list from it when there is no NCX, or when the nav lists at least as many entries as the NCX
     * ({@code Epub.getChapters}); readsync, and so {@link #navPoints()}, reads the NCX only.
     */
    public boolean declaresNavDocument() {
        return epub3Nav;
    }

    /**
     * Whether Moon+ would read a different NCX than this package does ({@link #navPoints()} follows the OPF, as
     * readsync does), or none can be told: a zip with {@code .ncx} files but no single one Moon+ would pick.
     */
    public boolean moonReadsAnotherNcx() {
        boolean anyNcx = entryNames.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).endsWith(".ncx"));
        return anyNcx ? !Objects.equals(moonNcx, ncxEntry) : ncxEntry != null;
    }

    /**
     * How many {@code navPoint} start tags the NCX Moon+ picks holds, counted by name as Moon+'s {@code addNCXChapters}
     * matches them (any prefix, any case, no namespace check). More than one there while {@link #ncxTrusted()} is
     * false means Moon+ numbers by an NCX that readsync doesn't read.
     */
    public int moonNcxNavPoints() {
        return moonNavPoints;
    }

    /**
     * Whether the NCX has a shape Moon+'s {@code addNCXChapters} reads differently from readsync's walk: a navPoint
     * nested in some other element (Moon+ only follows {@code navMap → navPoint → navPoint}), a navPoint with several
     * {@code content}s or whose {@code content} has other attributes but no {@code src} (Moon+ falls back to them),
     * or a {@code src} whose {@code %23} Moon+ decodes into a {@code #} before splitting off the id.
     */
    public boolean hasIrregularNcx() {
        return irregularNcx;
    }

    /**
     * One table-of-contents entry of the EPUB 3 nav as Moon+ reads it.
     *
     * @param target {@link #tocEntry}'s {@code file#fragment}
     * @param depth  how many {@code ol}s it is nested in below the top one
     * @param label  the first child of its {@code a} (or {@code ""}), line breaks removed and trimmed
     */
    public record NavTocEntry(String target, int depth, String label) {
    }

    /**
     * The EPUB 3 nav document's table of contents as Moon+ reads it ({@code Epub.getChaptersFromToc} for a nav,
     * {@code addEpub3NCXChapters}): the {@code nav} whose {@code epub:type} has {@code toc} (the first when there is
     * only one, or when a {@code nav} before it has no {@code epub:type}), its {@code ol}/{@code li}/{@code a} tree in
     * document order, entries whose file is in the zip and whose {@code a} has no children or starts with text. Empty
     * without a nav document Moon+ finds and can parse.
     */
    public List<NavTocEntry> navTocEntries() {
        return navTocEntries;
    }

    /** An NCX or nav target as {@code file#fragment}: the file's base name and the fragment, both percent-decoded. */
    public static String tocEntry(String href, @Nullable String fragment) {
        return target(baseName(percentDecode(href)), fragment == null ? "" : percentDecode(fragment));
    }

    private static String target(String file, String fragment) {
        return file + "#" + fragment;
    }

    /** {@code Epub.getTocFilenameEpub3}: the entry named by the tag holding the literal {@code properties="nav"}. */
    private static @Nullable ZipEntry moonNavEntry(ZipFile zip, String opf, String opfDir, List<String> names) {
        int at = opf.indexOf("properties=\"nav\"");
        int tagStart = at < 0 ? -1 : opf.lastIndexOf('<', at);
        int tagEnd = at < 0 ? -1 : opf.indexOf('>', at);
        if (tagStart < 0 || tagEnd < 0) {
            return null;
        }
        String tag = opf.substring(tagStart, tagEnd);
        int hrefAt = tag.indexOf("href=\"");
        int hrefEnd = hrefAt < 0 ? -1 : tag.indexOf('"', hrefAt + 7);
        if (hrefEnd < 0) {
            return null;
        }
        String href = tag.substring(hrefAt + 6, hrefEnd);
        ZipEntry entry = findEntry(zip, resolveRelative(opfDir, href));
        if (entry == null) {
            entry = names.stream().filter(n -> n.endsWith(href)).findFirst().map(zip::getEntry).orElse(null);
        }
        return entry;
    }
    private static List<NavTocEntry> navTocEntries(ZipFile zip, ZipEntry entry, List<String> names) throws IOException {
        Document nav;
        try {
            nav = parseXml(read(zip, entry));
        } catch (SAXException e) {
            return List.of(); // Moon+'s parse fails too, and it keeps the NCX
        }
        NodeList navs = nav.getElementsByTagName("nav");
        if (navs.getLength() == 0) {
            return List.of();
        }
        Element toc = (Element) navs.item(0);
        if (navs.getLength() > 1) {
            for (int i = 0; i < navs.getLength(); i++) {
                Element candidate = (Element) navs.item(i);
                if (!candidate.hasAttribute("epub:type")) {
                    break; // Moon+'s getNamedItem(...).getNodeValue() throws, and it falls back to the first nav
                }
                if (candidate.getAttribute("epub:type").contains("toc")) {
                    toc = candidate;
                    break;
                }
            }
        }
        List<NavTocEntry> entries = new ArrayList<>();
        for (Node ol = toc.getFirstChild(); ol != null; ol = ol.getNextSibling()) {
            if ("ol".equals(ol.getNodeName())) {
                navListItems(ol, 0, names, entries);
            }
        }
        return List.copyOf(entries);
    }

    private static void navListItems(Node ol, int depth, List<String> zipNames, List<NavTocEntry> entries) {
        for (Node li = ol.getFirstChild(); li != null; li = li.getNextSibling()) {
            if (!"li".equals(li.getNodeName())) {
                continue;
            }
            for (Node child = li.getFirstChild(); child != null; child = child.getNextSibling()) {
                if ("a".equals(child.getNodeName()) && child instanceof Element a) {
                    String src = percentDecode(a.getAttribute("href"));
                    String label = a.getFirstChild() == null ? "" : a.getFirstChild().getNodeValue();
                    int hash = src.indexOf('#');
                    String file = baseName(hash < 0 ? src : src.substring(0, hash));
                    if (label != null && hasFileNamed(zipNames, file)) {
                        entries.add(new NavTocEntry(target(file, hash < 0 ? "" : src.substring(hash + 1)), depth,
                                normalizedLabel(label)));
                    }
                } else if ("ol".equals(child.getNodeName())) {
                    navListItems(child, depth + 1, zipNames, entries);
                }
            }
        }
    }

    /**
     * The NCX Moon+ reads ({@code Epub.getTocFilename}), by file name: the zip's only entry named {@code toc.ncx};
     * else its only entry ending in {@code toc.ncx}, ignoring case; else its only {@code .ncx}. Null when there is none,
     * or several tie (Moon+'s pick then depends on its sorted file list).
     */
    private static @Nullable String moonNcxEntry(List<String> names) {
        List<String> exact = names.stream().filter(n -> baseName(n).equals("toc.ncx")).toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        if (!exact.isEmpty()) {
            return null;
        }
        List<String> toc = names.stream().filter(n -> n.toLowerCase(Locale.ROOT).endsWith("toc.ncx")).toList();
        if (!toc.isEmpty()) {
            return toc.size() == 1 ? toc.getFirst() : null;
        }
        List<String> ncx = names.stream().filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".ncx")).toList();
        return ncx.size() == 1 ? ncx.getFirst() : null;
    }

    private static int countNavPointTags(ZipFile zip, String name) throws IOException {
        String text = new String(readEntry(zip, name), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return (int) NAV_POINT_TAG.matcher(text).results().count();
    }

    /** Whether some zip entry has this base name, in any folder. */
    private static boolean hasFileNamed(List<String> names, String baseName) {
        return !baseName.isEmpty() && names.stream().anyMatch(n -> n.equals(baseName) || n.endsWith("/" + baseName));
    }

    /** The part after the last {@code /} ({@code T.getFilename}). */
    static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** The EPUB file's size in bytes (Moon+ decides some chapter handling by it). */
    public long fileSize() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new EpubContentException("can't read the size of " + file.getFileName(), e);
        }
    }

    /**
     * Whether the zip holds a file with this href's file name, in any folder: how Moon+ finds a navPoint's file
     * ({@code Epub.getChapter}, by file name first), so it keeps navPoints into files outside the spine.
     */
    public boolean containsFile(String href) {
        String decoded = percentDecode(href);
        String name = baseName(decoded);
        return hasFileNamed(entryNames, name);
    }

    /** The spine index of an NCX href (fragment already removed), comparing both sides percent-decoded. */
    public OptionalInt ncxSpineIndex(String href) {
        String decoded = percentDecode(href);
        for (int i = 0; i < spineHrefs.size(); i++) {
            if (percentDecode(spineHrefs.get(i)).equals(decoded)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static ZipFile open(Path file) throws IOException {
        try {
            return new ZipFile(file.toFile(), ZIP_NAME_FALLBACK);
        } catch (ZipException e) {
            throw new EpubContentException("not a zip file: " + file.getFileName(), e);
        }
    }

    private static String findOpfPath(ZipFile zip) throws IOException, SAXException {
        Document container = parseXml(readEntry(zip, "META-INF/container.xml"));
        Element rootfile = firstDescendant(container.getDocumentElement(), CONTAINER_NS, "rootfile");
        if (rootfile == null) {
            throw new EpubContentException("META-INF/container.xml has no <rootfile> element");
        }
        String fullPath = rootfile.getAttribute("full-path");
        if (fullPath.isEmpty()) {
            throw new EpubContentException("META-INF/container.xml's <rootfile> is missing full-path");
        }
        return fullPath;
    }

    private static List<String> parseSpineHrefs(Document opf) {
        Element root = opf.getDocumentElement();
        Element manifest = firstChild(root, OPF_NS, "manifest");
        Element spine = firstChild(root, OPF_NS, "spine");
        if (manifest == null || spine == null) {
            throw new EpubContentException("OPF package document is missing <manifest> or <spine>");
        }
        Map<String, String> idToHref = new LinkedHashMap<>();
        for (Element item : children(manifest, OPF_NS, "item")) {
            String id = attribute(item, "id");
            String href = attribute(item, "href");
            if (id != null && !id.isEmpty() && href != null && !href.isEmpty()) {
                idToHref.put(id, href);
            }
        }
        List<String> hrefs = new ArrayList<>();
        for (Element itemref : children(spine, OPF_NS, "itemref")) {
            String idref = attribute(itemref, "idref");
            if (idref == null || idref.isEmpty()) {
                continue;
            }
            String href = idToHref.get(idref);
            if (href == null) {
                throw new EpubContentException("spine itemref idref='" + idref + "' has no matching manifest item");
            }
            hrefs.add(href);
        }
        return List.copyOf(hrefs);
    }

    private static @Nullable String findNcxHref(Document opf) {
        Element root = opf.getDocumentElement();
        Element manifest = firstChild(root, OPF_NS, "manifest");
        if (manifest == null) {
            return null;
        }
        // Like readsync's dict comprehension: keyed by id (absent id is a key too), the last item wins, the first
        // occurrence keeps its place.
        Map<@Nullable String, Element> idToItem = new LinkedHashMap<>();
        for (Element item : children(manifest, OPF_NS, "item")) {
            idToItem.put(attribute(item, "id"), item);
        }
        Element spine = firstChild(root, OPF_NS, "spine");
        if (spine != null) {
            String tocId = attribute(spine, "toc");
            Element item = tocId == null || tocId.isEmpty() ? null : idToItem.get(tocId);
            String href = item == null ? null : attribute(item, "href");
            if (href != null && !href.isEmpty()) {
                return href;
            }
        }
        for (Element item : idToItem.values()) {
            String href = attribute(item, "href");
            if (NCX_MEDIA_TYPE.equals(attribute(item, "media-type")) && href != null && !href.isEmpty()) {
                return href;
            }
        }
        return null;
    }

    /** The navPoints (null without a usable NCX) and whether Moon+ would read the NCX differently. */
    private record NcxRead(@Nullable List<NavPoint> navPoints, boolean irregular) {
    }

    private static NcxRead readNcx(ZipFile zip, ZipEntry entry) throws IOException {
        Document ncx;
        try {
            ncx = parseXml(read(zip, entry));
        } catch (SAXException e) {
            return new NcxRead(null, false);
        }
        Element navMap = firstChild(ncx.getDocumentElement(), NCX_NS, "navMap");
        if (navMap == null) {
            return new NcxRead(null, false);
        }
        List<NavPoint> entries = new ArrayList<>();
        boolean irregular = false;
        for (Element navPoint : descendants(navMap, NCX_NS, "navPoint")) {
            Node parent = navPoint.getParentNode();
            irregular |= parent != navMap && !is(parent, NCX_NS, "navPoint");
            Element content = firstChild(navPoint, NCX_NS, "content");
            String src = content == null ? null : attribute(content, "src");
            // getCFilename falls back to the last other attribute, and addNCXChapters to a later content element.
            irregular |= children(navPoint, NCX_NS, "content").size() > 1;
            if (src == null || src.isEmpty()) {
                irregular |= content != null && content.getAttributes().getLength() > (src == null ? 0 : 1);
                continue;
            }
            irregular |= src.toLowerCase(Locale.ROOT).contains("%23");
            int hash = src.indexOf('#');
            String href = hash < 0 ? src : src.substring(0, hash);
            String fragment = hash < 0 || hash == src.length() - 1 ? null : src.substring(hash + 1);
            String label = moonLabel(navPoint);
            entries.add(new NavPoint(pythonInt(attribute(navPoint, "playOrder")), href, fragment,
                    depth(navPoint, navMap), normalizedLabel(label), hash == src.length() - 1, label == null));
        }
        return new NcxRead(entries.isEmpty() ? null : List.copyOf(entries), irregular);
    }

    /** {@code Epub.getChapter}'s label: line breaks removed, trimmed; {@code ""} for none. */
    private static String normalizedLabel(@Nullable String label) {
        return label == null ? "" : label.replace("\n", "").replace("\r", "").trim();
    }

    /**
     * Moon+'s label for a navPoint ({@code addNCXChapters}, {@code getCName}): {@code ""} without a {@code navLabel};
     * else, from the first {@code navLabel} that gives one, the value of the first child of its first {@code text}
     * that has a child. Null when every {@code navLabel} gives none: Moon+ then skips the navPoint.
     */
    private static @Nullable String moonLabel(Element navPoint) {
        String name = "";
        for (Element navLabel : children(navPoint, NCX_NS, "navLabel")) {
            if (name != null && !name.isEmpty()) {
                break;
            }
            name = null;
            for (Element text : children(navLabel, NCX_NS, "text")) {
                if (text.getFirstChild() != null) {
                    name = text.getFirstChild().getNodeValue();
                    break;
                }
            }
        }
        return name;
    }

    private static int depth(Element navPoint, Element navMap) {
        int depth = 0;
        for (Node n = navPoint.getParentNode(); n != null && n != navMap; n = n.getParentNode()) {
            if (is(n, NCX_NS, "navPoint")) {
                depth++;
            }
        }
        return depth;
    }

    private static @Nullable BigInteger pythonInt(@Nullable String value) {
        if (value == null) {
            return null;
        }
        Matcher m = PYTHON_INT.matcher(value.strip());
        if (!m.matches()) {
            return null;
        }
        StringBuilder digits = new StringBuilder(m.group(1));
        m.group(2).codePoints().filter(cp -> cp != '_').forEach(cp -> digits.append(Character.digit(cp, 10)));
        return new BigInteger(digits.toString());
    }

    /**
     * {@code urllib.parse.unquote}: {@code %XX} sequences decode as UTF-8 (invalid bytes become U+FFFD), each run of
     * ASCII characters on its own; everything else, {@code +} included, is kept.
     */
    static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) > 0x7f) {
                out.append(value.charAt(i++));
                continue;
            }
            ByteArrayOutputStream run = new ByteArrayOutputStream();
            while (i < value.length() && value.charAt(i) <= 0x7f) {
                char c = value.charAt(i);
                if (c == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                    run.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 3;
                } else {
                    run.write(c);
                    i++;
                }
            }
            out.append(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(run.toByteArray())));
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String dirname(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    private static String resolveRelative(String baseDir, String href) {
        return baseDir.isEmpty() ? href : baseDir + "/" + href;
    }

    /** Exact name first, then the first entry (in central-directory order) whose name matches ignoring case. */
    private static @Nullable ZipEntry findEntry(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry != null) {
            return entry;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return Collections.list(zip.entries()).stream()
                .filter(e -> e.getName().toLowerCase(Locale.ROOT).equals(lower))
                .findFirst()
                .orElse(null);
    }

    private static byte[] readEntry(ZipFile zip, String path) throws IOException {
        ZipEntry entry = findEntry(zip, path);
        if (entry == null) {
            throw new MissingEntryException(path);
        }
        return read(zip, entry);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static Document parseXml(byte[] xml) throws IOException, SAXException {
        try {
            return XML.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentBuilderFactory xmlFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser lacks a required security feature", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static @Nullable String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static boolean is(Node node, String ns, String localName) {
        return node instanceof Element && ns.equals(node.getNamespaceURI()) && localName.equals(node.getLocalName());
    }

    /** ElementTree's {@code find("{ns}name")}: the first direct child. */
    private static @Nullable Element firstChild(Element parent, String ns, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (is(n, ns, localName)) {
                return (Element) n;
            }
        }
        return null;
    }

    /** ElementTree's {@code findall("{ns}name")}: direct children. */
    private static List<Element> children(Element parent, String ns, String localName) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (is(n, ns, localName)) {
                found.add((Element) n);
            }
        }
        return found;
    }

    /** ElementTree's {@code findall(".//{ns}name")}: every descendant, in document order. */
    private static List<Element> descendants(Element root, String ns, String localName) {
        List<Element> found = new ArrayList<>();
        collect(root, ns, localName, found);
        return found;
    }

    private static void collect(Element parent, String ns, String localName, List<Element> found) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                if (is(child, ns, localName)) {
                    found.add(child);
                }
                collect(child, ns, localName, found);
            }
        }
    }

    private static @Nullable Element firstDescendant(Element root, String ns, String localName) {
        List<Element> all = descendants(root, ns, localName);
        return all.isEmpty() ? null : all.getFirst();
    }

    /** A zip entry that doesn't exist (readsync's {@code KeyError}). */
    private static final class MissingEntryException extends EpubContentException {
        MissingEntryException(String path) {
            super("no zip entry " + path);
        }
    }
}
