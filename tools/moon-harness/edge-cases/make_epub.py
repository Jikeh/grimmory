"""Builds ``backend/src/test/resources/moonsync/books/edge-cases.epub``: a synthetic EPUB (original content, free to
commit) holding the HTML and package shapes most likely to make jsoup and parse5 build different trees, or to trip
the OPF/NCX reading, so that every harness run covers them even without a real library mounted.

Run from the repo root: ``python tools/moon-harness/edge-cases/make_epub.py``. The output is deterministic (fixed
zip timestamps), so re-running it without changes leaves the committed file identical.
"""

from __future__ import annotations

import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parents[3] / "backend/src/test/resources/moonsync/books/edge-cases.epub"
STAMP = (2026, 1, 1, 0, 0, 0)

XHTML_HEAD = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">\n'
    '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">\n'
    "<head>\n  <title>{title}</title>\n</head>\n"
)


def page(title: str, body: str, *, crlf: bool = False, tail: str = "\n</html>\n") -> str:
    text = XHTML_HEAD.format(title=title) + "<body>\n" + body + "\n</body>" + tail
    return text.replace("\n", "\r\n") if crlf else text


# Each spine file isolates one family of constructs, so a parity difference names its cause.
FILES: dict[str, str] = {
    "Text/self-closing.xhtml": page("Self-closing tags", """
  <h1 id="top">Self-closing</h1>
  <p>Before an empty anchor<a id="anchor-a"/> and after it.</p>
  <div/>
  <p>A div written as <span/> self-closing, then text.</p>
  <p>Line one<br/>line two<br></br>line three</p>
  <a name="named"></a><a id="dup"></a><a id="dup"></a>
  <p>Image <img src="x.png" alt=""/> and <image src="y.png"/> tag.</p>
"""),
    "Text/implied.xhtml": page("Implied and misnested", """
  <p>Paragraph <div>with a div inside</div> after the div.</p>
  <p>First<p>second, unclosed</p>
  <b><p>bold across</b> a paragraph end</p>
  <i>italic <b>both</i> bold only</b> plain
  <h2>Heading <h3>nested heading</h3> after</h2>
  <ul><li>one<li>two</ul>
  <dl><dt>term<dd>definition<dt>term two</dl>
  <li>loose list item</li>
  <form><form>nested form</form></form>
  <ruby>漢<rb>字</rb><rt>kan</rt><rp>(</rp><rt>ji</rt><rp>)</rp></ruby>
  <my-element data-x="1">custom element</my-element>
  <UPPER>Upper-case tag</UPPER><P>Upper-case paragraph</P>
"""),
    "Text/tables.xhtml": page("Tables", """
  <table>
    <tr><td>no tbody</td>
      <td>cell</td></tr>
    text directly in the table
    <tr><th>head</th></tr>
  </table>
  <table><tbody>
    <tr>
      <td><p>paragraph in a cell</p></td>
    </tr>
  </tbody></table>
  <table><caption>Caption</caption><colgroup><col/></colgroup><thead><tr><td>h</td></tr></thead></table>
  <table><tr><td>outer<table><tr><td>inner</td></tr></table></td></tr></table>
"""),
    "Text/raw-text.xhtml": page("Raw text elements", """
  <p>Before the script.</p>
  <script type="text/javascript">var a = "<p>not a paragraph</p>";</script>
  <style>p { color: red; }</style>
  <noscript><p>noscript content</p></noscript>
  <textarea>  textarea <b>text</b>
  </textarea>
  <title>title in body</title>
  <pre>
leading newline dropped</pre>
  <listing>
listing too</listing>
  <xmp><b>xmp keeps tags</b></xmp>
  <template><p>template content</p></template>
  <select><option>one<option>two</select>
  <iframe>iframe text</iframe>
  <p>After the raw text.</p>
"""),
    "Text/foreign.xhtml": page("SVG and MathML", """
  <p>Inline SVG:</p>
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
    <clipPath id="clip"><rect width="5" height="5"/></clipPath>
    <foreignObject width="10" height="10"><p>HTML in SVG</p></foreignObject>
    <text>SVG text <tspan>span</tspan></text>
    <![CDATA[ cdata in svg ]]>
    <script>svg script text</script>
  </svg>
  <math xmlns="http://www.w3.org/1998/Math/MathML"><mi>x</mi><mo>=</mo><mn>1</mn><annotation-xml encoding="text/html"><p>html</p></annotation-xml></math>
  <p>CDATA in HTML: <![CDATA[ becomes a comment ]]> after.</p>
"""),
    "Text/entities.xhtml": page("Entities", """
  <p>nbsp&nbsp;amp&amp;lt&lt;gt&gt;quot&quot;apos&apos;</p>
  <p>numeric &#233; &#xE9; &#x1F600; &#0; &#128; &#xD800;</p>
  <p>legacy &copy without semicolon, &notanentity; and &amp</p>
  <p title="attribute &amp; entity">soft&shy;hyphen and zero&#8203;width</p>
  <p>Combining: é vs é; ligature ﬁ.</p>
"""),
    "Text/document-shape.xhtml": (
        "﻿" + '<?xml version="1.0" encoding="utf-8"?>\n<!-- comment before html -->\n'
        "<html><head><title>Shape</title></head>\n\n"
        "<body><p>A BOM, a comment before html, then the body.</p>\n"
        "<head><meta name='late' content='1'/></head>\n"
        "<body class='second'><p>A second body tag.</p></body>\n"
        "trailing text after body\n</html>\n<!-- after html -->\n\n"
    ),
    "Text/no-html.xhtml": "<p>No html, head or body tags at all.</p>\n<p>Second paragraph.</p>\n",
    "Text/crlf file.xhtml": page("CRLF and a space in the file name", """
  <h1 id="crlf">CRLF</h1>
  <p>Every line of this file
  ends in CR LF, and its name has a space.</p>
""", crlf=True),
    "Text/città.xhtml": page("Non-ASCII file name", """
  <h1 id="città">Città</h1>
  <p>A file whose name and anchor are not ASCII.</p>
"""),
    "Text/merged.xhtml": page("Merged chapters", "\n".join(
        [f'  <h2 id="part{n}">Part {n}</h2>\n  <p>{"Text of part %d. " % n * 20}</p>' for n in range(1, 4)]
    )),
    "Text/long.xhtml": page("A chapter Moon+ splits", "\n".join(
        [f'  <h2 id="sec{n}">Section {n}</h2>\n' + "\n".join(
            f"  <p>Paragraph {n}.{k} of a chapter long enough for Moon+ to cut it into chunks.</p>" for k in range(60))
         for n in range(1, 40)]
    )),
    "Text/unlisted.xhtml": page("A file no navPoint names", "  <p>Only reachable in reading order.</p>"),
    # Shapes found in a real library (O'Reilly DocBook and InDesign exports):
    "Text/open-anchor.xhtml": page("An anchor left open at the end", """
  <div><h1><a id="open-a"/>Title</h1><p>Body text.</p>
  <p>Index terms <a id="term-1" class="indexterm"/> <a id="term-2" class="indexterm"/></p></div>
"""),
    "Text/table-char-refs.xhtml": page("Character-reference whitespace in a table", """
  <figure>
<table id="t1">&#13;
<tbody>&#13;
<tr>&#13;
<td>&#13;
<p>cell</p>&#13;
</td>&#13;
</tr>
</tbody>
</table>
</figure>"""),
    "Text/title-runs-on.xhtml": (
        '<?xml version="1.0" encoding="UTF-8"?>' + chr(10)
        + '<html xmlns="http://www.w3.org/1999/xhtml"><head><title/>'
        + '<link rel="stylesheet" href="core.css" type="text/css"/></head><body><p>All of this is the title.</p>'
        + '</body></html>' + chr(10)
    ),
}

SPINE = list(FILES)

# navPoints: nested, a percent-encoded href, a missing file, fragments, a sparse/duplicate/missing playOrder.
NAV = [
    ("1", "Self-closing", "Text/self-closing.xhtml", []),
    ("1", "Implied", "Text/implied.xhtml#nowhere", []),
    ("7", "Tables", "Text/tables.xhtml", [
        (None, "Raw text", "Text/raw-text.xhtml", []),
        ("x", "Foreign", "Text/foreign.xhtml", []),
    ]),
    ("9", "Entities", "Text/entities.xhtml", []),
    ("10", "Shape", "Text/document-shape.xhtml", []),
    ("11", "No html", "Text/no-html.xhtml", []),
    ("12", "CRLF", "Text/crlf%20file.xhtml#crlf", []),
    ("13", "Città", "Text/citt%C3%A0.xhtml", []),
    ("14", "Part 1", "Text/merged.xhtml", [
        ("15", "Part 2", "Text/merged.xhtml#part2", []),
        ("16", "Part 3", "Text/merged.xhtml#part3", []),
    ]),
    ("17", "Missing file", "Text/missing.xhtml", []),
    ("18", "Long", "Text/long.xhtml", [("19", "Section 30", "Text/long.xhtml#sec30", [])]),
]


def nav_points(entries: list, depth: int = 2) -> str:
    out = []
    for i, (order, label, src, children) in enumerate(entries):
        pad = "  " * depth
        attr = "" if order is None else f' playOrder="{order}"'
        out.append(f'{pad}<navPoint id="np{depth}-{i}-{label[:3]}"{attr}>')
        out.append(f"{pad}  <navLabel><text>{label}</text></navLabel>")
        out.append(f'{pad}  <content src="{src}"/>')
        out.append(nav_points(children, depth + 1))
        out.append(f"{pad}</navPoint>")
    return "\n".join(o for o in out if o)


def manifest_id(name: str) -> str:
    return "f" + str(SPINE.index(name))


OPF = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Moon+ harness edge cases</dc:title><dc:identifier id="id">edge-cases</dc:identifier><dc:language>en</dc:language>
  </metadata>
  <manifest>
{chr(10).join(f'    <item id="{manifest_id(n)}" href="{n}" media-type="application/xhtml+xml"/>' for n in SPINE)}
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
{chr(10).join(f'    <itemref idref="{manifest_id(n)}"/>' for n in SPINE)}
    <itemref/>
  </spine>
</package>
"""

NCX = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="edge-cases"/></head>
  <docTitle><text>Moon+ harness edge cases</text></docTitle>
  <navMap>
{nav_points(NAV)}
  </navMap>
</ncx>
"""

CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""


def write(zf: zipfile.ZipFile, name: str, data: str, compress: int = zipfile.ZIP_DEFLATED) -> None:
    info = zipfile.ZipInfo(name, STAMP)
    info.compress_type = compress
    zf.writestr(info, data.encode("utf-8"))


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w") as zf:
        write(zf, "mimetype", "application/epub+zip", zipfile.ZIP_STORED)
        write(zf, "META-INF/container.xml", CONTAINER)
        write(zf, "OEBPS/content.opf", OPF)
        write(zf, "OEBPS/toc.ncx", NCX)
        for name, text in FILES.items():
            write(zf, f"OEBPS/{name}", text)
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
