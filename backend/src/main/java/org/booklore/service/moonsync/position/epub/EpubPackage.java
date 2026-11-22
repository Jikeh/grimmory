package org.booklore.service.moonsync.position.epub;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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

    private EpubPackage(Path file, String opfDir, List<String> spineHrefs, @Nullable List<NavPoint> navPoints,
                        boolean epub3Nav, List<String> entryNames, @Nullable String ncxEntry) {
        this.file = file;
        this.opfDir = opfDir;
        this.spineHrefs = spineHrefs;
        this.navPoints = navPoints;
        this.epub3Nav = epub3Nav;
        this.entryNames = entryNames;
        this.ncxEntry = ncxEntry;
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
            Document opf = parseXml(readEntry(zip, opfPath));
            List<String> spine = parseSpineHrefs(opf);
            List<NavPoint> navPoints = parseNavPoints(zip, opf, opfDir);
            List<String> names = Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList();
            String ncx = findNcxHref(opf);
            String ncxEntry = ncx == null ? null : entryName(zip, resolveRelative(opfDir, ncx));
            return new EpubPackage(file, opfDir, spine, navPoints, declaresNavDocument(opf), names, ncxEntry);
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
     * Whether the package declares an EPUB 3 navigation document ({@code properties="nav"}). Moon+ reads its chapter
     * list from that document when it has no NCX, or when the nav lists at least as many entries as the NCX
     * ({@code Epub.getChapters}); readsync, and so this port, reads the NCX only.
     */
    public boolean declaresNavDocument() {
        return epub3Nav;
    }

    /**
     * Whether Moon+ would read a different NCX than this package does ({@link #navPoints()} follows the OPF, as
     * readsync does). Moon+ goes by file name ({@code Epub.getTocFilename}): the zip's only {@code toc.ncx}, else its
     * only {@code .ncx}; a zip with several and no unique {@code toc.ncx} counts as different (Moon+'s pick then
     * depends on its sorted file list).
     */
    public boolean moonReadsAnotherNcx() {
        List<String> tocs = entryNames.stream().filter(n -> baseName(n).equals("toc.ncx")).toList();
        List<String> ncxs = entryNames.stream().filter(n -> n.endsWith(".ncx")).toList();
        String moon = tocs.size() == 1 ? tocs.getFirst() : tocs.isEmpty() && ncxs.size() == 1 ? ncxs.getFirst() : null;
        return ncxs.isEmpty() ? ncxEntry != null : moon == null || !moon.equals(ncxEntry);
    }

    private static String baseName(String path) {
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
        String name = decoded.substring(decoded.lastIndexOf('/') + 1);
        return !name.isEmpty() && entryNames.stream().anyMatch(e -> e.equals(name) || e.endsWith("/" + name));
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

    private static boolean declaresNavDocument(Document opf) {
        Element manifest = firstChild(opf.getDocumentElement(), OPF_NS, "manifest");
        if (manifest == null) {
            return false;
        }
        for (Element item : children(manifest, OPF_NS, "item")) {
            String properties = attribute(item, "properties");
            if (properties != null && List.of(properties.strip().split("\\s+")).contains("nav")) {
                return true;
            }
        }
        return false;
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

    private static @Nullable List<NavPoint> parseNavPoints(ZipFile zip, Document opf, String opfDir) throws IOException {
        String ncxHref = findNcxHref(opf);
        if (ncxHref == null) {
            return null;
        }
        Document ncx;
        try {
            ncx = parseXml(readEntry(zip, resolveRelative(opfDir, ncxHref)));
        } catch (MissingEntryException | SAXException e) {
            return null;
        }
        Element navMap = firstChild(ncx.getDocumentElement(), NCX_NS, "navMap");
        if (navMap == null) {
            return null;
        }
        List<NavPoint> entries = new ArrayList<>();
        for (Element navPoint : descendants(navMap, NCX_NS, "navPoint")) {
            Element content = firstChild(navPoint, NCX_NS, "content");
            String src = content == null ? null : attribute(content, "src");
            if (src == null || src.isEmpty()) {
                continue;
            }
            int hash = src.indexOf('#');
            String href = hash < 0 ? src : src.substring(0, hash);
            String fragment = hash < 0 || hash == src.length() - 1 ? null : src.substring(hash + 1);
            entries.add(new NavPoint(pythonInt(attribute(navPoint, "playOrder")), href, fragment,
                    depth(navPoint, navMap), label(navPoint), hash == src.length() - 1, labelless(navPoint)));
        }
        return entries.isEmpty() ? null : List.copyOf(entries);
    }

    /** Moon+'s {@code getCName} returning null: a {@code navLabel} without a {@code text} that starts with text. */
    private static boolean labelless(Element navPoint) {
        Element navLabel = firstChild(navPoint, NCX_NS, "navLabel");
        if (navLabel == null) {
            return false;
        }
        Element text = firstChild(navLabel, NCX_NS, "text");
        Node first = text == null ? null : text.getFirstChild();
        return first == null || first.getNodeValue() == null;
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

    /** Moon+'s {@code Epub.getCName}: the first {@code navLabel}'s {@code text} child's first text. */
    private static String label(Element navPoint) {
        Element navLabel = firstChild(navPoint, NCX_NS, "navLabel");
        Element text = navLabel == null ? null : firstChild(navLabel, NCX_NS, "text");
        Node first = text == null ? null : text.getFirstChild();
        String value = first == null || first.getNodeValue() == null ? "" : first.getNodeValue();
        return value.replace("\n", "").replace("\r", "").trim();
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

    /** The name of the entry {@link #readEntry} would read for {@code path}, or null when there is none. */
    private static @Nullable String entryName(ZipFile zip, String path) {
        if (zip.getEntry(path) != null) {
            return path;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return Collections.list(zip.entries()).stream().map(ZipEntry::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).equals(lower)).findFirst().orElse(null);
    }

    /** Exact name first, then the first entry (in central-directory order) whose name matches ignoring case. */
    private static byte[] readEntry(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            String lower = path.toLowerCase(Locale.ROOT);
            entry = Collections.list(zip.entries()).stream()
                    .filter(e -> e.getName().toLowerCase(Locale.ROOT).equals(lower))
                    .findFirst()
                    .orElseThrow(() -> new MissingEntryException(path));
        }
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
