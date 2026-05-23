window.renderTimeline = async function (view, api, setStatus) {
  const st = { data: null, idToFolder: {} };
  try {
    (await api.maps()).maps.forEach((m) => (st.idToFolder[m.id] = m.name));
  } catch (e) {
    st.idToFolder = {};
  }
  const pretty = (n) => n.replace(/^MAP_/, '').replace(/_/g, ' ').replace(/([A-Za-z])(\d)/g, '$1 $2').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
  const varName = (v) => v.replace(/^VAR_/, '').replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

  const listCol = el('div', { cls: 'col-list' });
  const detail = el('div', { cls: 'codeview', text: 'Pick a progression flag to see where it advances the story.' });
  const narrative = el('div', { cls: 'docview' });
  const main = el('div', { cls: 'col-main' },
    el('div', { cls: 'section-h', text: 'Trigger points' }), detail);

  function showVar(v) {
    const points = st.data.vars[v] || [];
    // order by the value the var must reach (story order)
    points.sort((a, b) => String(a.var_value).localeCompare(String(b.var_value), undefined, { numeric: true }));
    if (points[0] && st.idToFolder[points[0].map]) window.setScene(st.idToFolder[points[0].map]);
    const rows = points.map((p) =>
      el(
        'div',
        { style: { marginBottom: '10px', borderLeft: '3px solid #2c5e54', paddingLeft: '10px' } },
        el(
          'div',
          {},
          el('span', { cls: 'sl-label', text: v }),
          el('span', { text: ' reaches ' }),
          el('span', { cls: 'var-count', text: p.var_value })
        ),
        el('div', { cls: 's', text: `on ${pretty(p.map)} → ${p.script}` })
      )
    );
    mount(detail, el('div', { cls: 'section-h', text: `${varName(v)} — ${points.length} trigger point(s)` }), ...rows);
  }

  function varRow(v, count) {
    const row = el(
      'div',
      { cls: 'row-item', style: { display: 'flex', alignItems: 'center' } },
      el('span', { cls: 't', text: varName(v) }),
      el('span', { cls: 'var-count', text: count })
    );
    row.addEventListener('click', () => {
      listCol.querySelectorAll('.row-item').forEach((r) => r.classList.remove('sel'));
      row.classList.add('sel');
      showVar(v);
    });
    return row;
  }

  function showVars(filter) {
    const entries = Object.entries(st.data.vars).filter(([v]) =>
      !filter || varName(v).toLowerCase().includes(filter.toLowerCase())
    );
    mount(
      listCol,
      el('div', { cls: 'section-h', text: `Progression flags (${entries.length})` }),
      ...entries.map(([v, pts]) => varRow(v, String(pts.length)))
    );
  }

  mount(view, el('div', { cls: 'empty', text: 'Building timeline…' }));
  try {
    st.data = await api.timeline();
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not read the repo. ', e.message));
    return;
  }

  const narrBody = st.data.narrative
    ? (window.renderMarkdown ? window.renderMarkdown(st.data.narrative) : [document.createTextNode(st.data.narrative)])
    : [document.createTextNode('(no narrative doc found)')];
  mount(narrative,
    el('div', { cls: 'section-h', text: st.data.narrative_name || 'Narrative' }),
    ...narrBody);

  const search = el('input', { placeholder: 'Filter flags…', style: { minWidth: '220px' } });
  search.addEventListener('input', () => showVars(search.value));
  const picker = el('div', { cls: 'map-picker' }, el('h2', { text: 'Story Timeline' }), search);

  // three columns: flags | trigger points | narrative
  const narrativeCol = el('div', { cls: 'col-list', style: { width: '380px', flex: '0 0 380px' } }, narrative);
  mount(view, picker, el('div', { cls: 'cols' }, listCol, main, narrativeCol));
  showVars('');
};
