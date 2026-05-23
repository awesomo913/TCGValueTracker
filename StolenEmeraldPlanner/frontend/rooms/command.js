window.renderCommand = async function (view, api, setStatus) {
  const st = { docs: [], sel: null };
  const listCol = el('div', { cls: 'col-list' });
  const docView = el('div', { cls: 'docview', text: 'Pick a document to read it. Use search to find anything across all of them.' });
  const main = el('div', { cls: 'col-main' }, docView);

  function openDoc(rel, gotoLine) {
    mount(docView, document.createTextNode('Loading ' + rel + '…'));
    api
      .historyDoc(rel)
      .then((r) => {
        mount(docView, el('div', { cls: 'section-h', text: rel }), document.createTextNode(r.text));
        if (gotoLine) {
          // best-effort: scroll near the line by ratio
          const lines = r.text.split('\n').length || 1;
          docView.scrollTop = (gotoLine / lines) * docView.scrollHeight;
        }
      })
      .catch((e) => mount(docView, document.createTextNode('Failed: ' + e.message)));
  }

  function docRow(d) {
    const when = new Date(d.mtime * 1000).toISOString().slice(0, 10);
    const row = el(
      'div',
      { cls: 'row-item' },
      el('div', { cls: 't', text: d.name }),
      el('div', { cls: 's', text: `${when} · ${(d.size / 1024).toFixed(1)} KB · ${d.rel}` })
    );
    row.addEventListener('click', () => {
      listCol.querySelectorAll('.row-item').forEach((r) => r.classList.remove('sel'));
      row.classList.add('sel');
      openDoc(d.rel);
    });
    return row;
  }

  function showDocs() {
    const rows = st.docs.map(docRow);
    mount(listCol, el('div', { cls: 'section-h', text: `Documents (${st.docs.length})` }), ...rows);
  }

  function runSearch(q) {
    if (!q.trim()) {
      showDocs();
      return;
    }
    api
      .historySearch(q)
      .then((r) => {
        const hits = r.hits || [];
        const items = hits.map((h) =>
          el(
            'div',
            { cls: 'hit', onclick: () => openDoc(h.rel, h.line) },
            el('span', { cls: 'ln', text: h.rel + ':' + h.line }),
            h.text
          )
        );
        mount(
          listCol,
          el('div', { cls: 'section-h', text: `${hits.length} matches for "${q}"` }),
          ...(items.length ? items : [el('div', { cls: 's', text: 'No matches.' })])
        );
      })
      .catch((e) => mount(listCol, el('div', { cls: 's', text: 'Search failed: ' + e.message })));
  }

  mount(view, el('div', { cls: 'empty', text: 'Loading history…' }));
  let docs;
  try {
    docs = (await api.history()).docs;
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not read the repo. ', e.message));
    return;
  }
  st.docs = docs;

  const search = el('input', { placeholder: 'Search all docs (e.g. battle frontier)…', style: { minWidth: '300px' } });
  let timer;
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => runSearch(search.value), 250);
  });
  const picker = el('div', { cls: 'map-picker' }, el('h2', { text: 'Command Center' }), search);
  mount(view, picker, el('div', { cls: 'cols' }, listCol, main));
  showDocs();
  if (st.docs.length) openDoc(st.docs[0].rel);
};
