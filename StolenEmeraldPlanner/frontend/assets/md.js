// Minimal, safe Markdown -> DOM renderer. Returns an array of elements.
// Uses only el()/text nodes (from dom.js) — never innerHTML, so external doc
// content can't inject markup.
(function () {
  function inline(text) {
    // tokenize **bold**, *italic*, `code` into text nodes + spans
    const nodes = [];
    let last = 0;
    const re = /(\*\*([^*]+)\*\*|\*([^*]+)\*|`([^`]+)`)/g;
    for (const m of text.matchAll(re)) {
      if (m.index > last) nodes.push(document.createTextNode(text.slice(last, m.index)));
      if (m[2] !== undefined) nodes.push(el('strong', { text: m[2] }));
      else if (m[3] !== undefined) nodes.push(el('em', { text: m[3] }));
      else if (m[4] !== undefined) nodes.push(el('code', { cls: 'md-code', text: m[4] }));
      last = m.index + m[0].length;
    }
    if (last < text.length) nodes.push(document.createTextNode(text.slice(last)));
    return nodes;
  }

  function render(mdText) {
    const out = [];
    const lines = (mdText || '').split('\n');
    let i = 0;
    let listBuf = null;
    let listType = null;

    function flushList() {
      if (listBuf) {
        out.push(el(listType, { cls: 'md-list' }, ...listBuf));
        listBuf = null;
        listType = null;
      }
    }

    while (i < lines.length) {
      const line = lines[i];
      if (line.trim().startsWith('```')) {
        flushList();
        const code = [];
        i++;
        while (i < lines.length && !lines[i].trim().startsWith('```')) {
          code.push(lines[i]);
          i++;
        }
        i++; // skip closing fence
        out.push(el('pre', { cls: 'md-pre' }, el('code', { text: code.join('\n') })));
        continue;
      }
      const h = line.match(/^(#{1,6})\s+(.*)$/);
      if (h) {
        flushList();
        const lvl = Math.min(h[1].length, 4);
        out.push(el('h' + lvl, { cls: 'md-h md-h' + lvl }, ...inline(h[2])));
        i++;
        continue;
      }
      if (/^\s*([-*+])\s+/.test(line)) {
        if (listType !== 'ul') {
          flushList();
          listBuf = [];
          listType = 'ul';
        }
        listBuf.push(el('li', {}, ...inline(line.replace(/^\s*[-*+]\s+/, ''))));
        i++;
        continue;
      }
      if (/^\s*\d+\.\s+/.test(line)) {
        if (listType !== 'ol') {
          flushList();
          listBuf = [];
          listType = 'ol';
        }
        listBuf.push(el('li', {}, ...inline(line.replace(/^\s*\d+\.\s+/, ''))));
        i++;
        continue;
      }
      if (/^\s*([-*_]\s*){3,}$/.test(line)) {
        flushList();
        out.push(el('hr', { cls: 'md-hr' }));
        i++;
        continue;
      }
      if (line.trim() === '') {
        flushList();
        i++;
        continue;
      }
      flushList();
      const para = [line];
      i++;
      while (
        i < lines.length &&
        lines[i].trim() !== '' &&
        !/^(#{1,6})\s/.test(lines[i]) &&
        !/^\s*([-*+])\s+/.test(lines[i]) &&
        !/^\s*\d+\.\s+/.test(lines[i]) &&
        !lines[i].trim().startsWith('```')
      ) {
        para.push(lines[i]);
        i++;
      }
      out.push(el('p', { cls: 'md-p' }, ...inline(para.join(' '))));
    }
    flushList();
    return out;
  }

  window.renderMarkdown = render;
})();
