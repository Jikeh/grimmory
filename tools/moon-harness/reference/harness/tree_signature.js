'use strict';

// Parser parity (comparison `epub-parser-parity`): the tree jsdom builds for each spine file, the parser readsync's
// CFI bridge uses (`new JSDOM(html)`), reduced to a signature the Java side computes the same way from jsoup
// (EpubParserParityComparison). Reads a JSON array of HTML strings on stdin, writes a JSON array of signatures.
//
// Signature of a document: walking from <html> in document order, an event per element ("<" + name, plus "#" + id
// when it has an id attribute, then its children, then ">") and per text or CDATA node ('"' + its data); comments,
// processing instructions and the doctype are left out. Names are local names, prefixed "svg:" / "math:" / "{uri}"
// outside the HTML namespace. `digest` is SHA-256 over every event as "<length>:" (ASCII) plus its UTF-16LE code
// units; `textLength` / `textDigest` are readsync's own walk (walk.js) over <body>. With TREE_EVENTS set, each
// document's event list is written instead, for diffing against EpubParserParityComparison.events.

const crypto = require('crypto');
const path = require('path');

const bridge = process.env.READSYNC_CFI_BRIDGE;
const { JSDOM } = require(path.join(bridge, 'node_modules', 'jsdom'));
const { walkChapterText } = require(path.join(bridge, 'lib', 'walk'));

const NAMESPACES = {
  'http://www.w3.org/1999/xhtml': '',
  'http://www.w3.org/2000/svg': 'svg:',
  'http://www.w3.org/1998/Math/MathML': 'math:',
};

function update(hash, event) {
  hash.update(`${event.length}:`, 'latin1');
  hash.update(event, 'utf16le');
}

function events(document) {
  const out = [];
  // Iterative walk: [node, closing] pairs; a closing entry emits the element's end event.
  const stack = [[document.documentElement, false]];
  while (stack.length > 0) {
    const [node, closing] = stack.pop();
    if (closing) {
      out.push('>');
      continue;
    }
    if (node.nodeType === 1) {
      const ns = node.namespaceURI;
      const prefix = ns in NAMESPACES ? NAMESPACES[ns] : `{${ns}}`;
      out.push(`<${prefix}${node.localName}${node.hasAttribute('id') ? `#${node.getAttribute('id')}` : ''}`);
      stack.push([node, true]);
      for (let i = node.childNodes.length - 1; i >= 0; i--) {
        stack.push([node.childNodes[i], false]);
      }
    } else if (node.nodeType === 3 || node.nodeType === 4) {
      out.push(`"${node.data}`);
    }
  }
  return out;
}

function signature(html) {
  const document = new JSDOM(html).window.document;
  const all = events(document);
  if (process.env.TREE_EVENTS) {
    return all;
  }
  const hash = crypto.createHash('sha256');
  for (const event of all) {
    update(hash, event);
  }
  const segments = walkChapterText(document.body);
  const text = crypto.createHash('sha256');
  for (const segment of segments) {
    text.update(segment.node.data, 'utf16le');
  }
  const last = segments[segments.length - 1];
  return {
    digest: hash.digest('hex'),
    elements: all.filter((e) => e.startsWith('<')).length,
    texts: all.filter((e) => e.startsWith('"')).length,
    textLength: last ? last.chapterStartOffset + last.length : 0,
    textDigest: text.digest('hex'),
  };
}

let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => { input += chunk; });
process.stdin.on('end', () => {
  const htmls = JSON.parse(input);
  process.stdout.write(JSON.stringify(htmls.map((html) => (html === null ? null : signature(html)))));
});
