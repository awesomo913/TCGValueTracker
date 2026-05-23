window.renderCast = async function (view, api, setStatus) {
  const pretty = (n) => n.replace(/^MAP_/, '').replace(/_/g, ' ').replace(/([A-Za-z])(\d)/g, '$1 $2').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
  const st = { folder: null, detail: null, list: [] };

  const listCol = el('div', { cls: 'col-list' });
  const codeView = el('div', { cls: 'codeview', text: 'Pick a map, then click a person, trainer, item ball, or sign to see its script.' });
  const main = el('div', { cls: 'col-main' }, codeView);

  function entityRow(kind, tagCls, label, sub, scriptLabel) {
    const row = el(
      'div',
      { cls: 'row-item', 'data-script': scriptLabel || '' },
      el('div', { cls: 't' }, el('span', { cls: 'tag ' + tagCls, text: kind }), label),
      el('div', { cls: 's', text: sub })
    );
    row.addEventListener('click', () => {
      listCol.querySelectorAll('.row-item').forEach((r) => r.classList.remove('sel'));
      row.classList.add('sel');
      openScript(scriptLabel);
    });
    return row;
  }

  function scriptBody(body) {
    if (!body) return el('div', { cls: 's', text: '(script body not found in scripts.inc)' });
    const wrap = el('div', {});
    for (const line of body.split('\n')) {
      const t = line.trim();
      let cls = '';
      if (/^\w+::?$/.test(t)) cls = 'sl-label';
      else if (t.startsWith('@')) cls = 'sl-comment';
      const ln = el('div', cls ? { cls } : {});
      ln.textContent = line || ' ';
      wrap.appendChild(ln);
    }
    return wrap;
  }

  async function openScript(label) {
    if (!label) {
      mount(codeView, document.createTextNode('(no script attached)'));
      return;
    }
    mount(codeView, document.createTextNode('Loading ' + label + '…'));
    let r;
    try {
      r = await api.scriptTrainer(st.folder, label);
    } catch (e) {
      mount(codeView, document.createTextNode('Failed: ' + e.message));
      return;
    }
    const parts = [el('div', { cls: 'section-h', text: label })];
    parts.push(scriptBody(r.body));
    if (r.trainer) {
      parts.push(el('div', { cls: 'party-box' }, el('div', { cls: 'section-h', text: r.trainer }), r.party));
    }
    mount(codeView, ...parts);
  }

  function showMap(folder, openLabel) {
    st.folder = folder;
    mount(listCol, el('div', { cls: 's', text: 'Loading…' }));
    api
      .mapDetail(folder)
      .then((d) => {
        st.detail = d;
        const rows = [];
        const add = (arr, kind, tag, labelFn, subFn) => {
          if (!arr.length) return;
          rows.push(el('div', { cls: 'section-h', text: `${kind} (${arr.length})` }));
          arr.forEach((o) => rows.push(entityRow(tag, tag, labelFn(o), subFn(o), o.script)));
        };
        add(d.trainers, 'trainer', 'trainer', (o) => o.graphics_id.replace('OBJ_EVENT_GFX_', ''), (o) => `(${o.x},${o.y}) · sight ${o.sight} · ${o.script}`);
        add(d.npcs, 'npc', 'npc', (o) => o.graphics_id.replace('OBJ_EVENT_GFX_', ''), (o) => `(${o.x},${o.y}) · ${o.script || 'no script'}`);
        add(d.item_balls, 'item', 'item', () => 'Item ball', (o) => `(${o.x},${o.y}) · ${o.script}`);
        add(d.signs, 'sign', 'sign', () => 'Sign', (o) => `(${o.x},${o.y}) · ${o.script}`);
        if (!rows.length) rows.push(el('div', { cls: 's', text: 'No people, trainers, items, or signs on this map.' }));
        mount(listCol, ...rows);
        if (openLabel) {
          // deep-link from another room: select + open that script
          const row = listCol.querySelector('.row-item[data-script="' + openLabel + '"]');
          if (row) {
            row.classList.add('sel');
            row.scrollIntoView({ block: 'center' });
          }
          openScript(openLabel);
        } else {
          mount(codeView, document.createTextNode('Click an entry to see its script.'));
        }
      })
      .catch((e) => mount(listCol, el('div', { cls: 's', text: 'Failed: ' + e.message })));
  }

  // top picker
  mount(view, el('div', { cls: 'empty', text: 'Loading map list…' }));
  let maps;
  try {
    maps = (await api.maps()).maps;
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not read the repo. ', e.message));
    return;
  }
  st.list = maps.map((m) => m.name).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  const dl = el('datalist', { id: 'castmaplist' }, ...st.list.map((n) => el('option', { value: n })));
  const input = el('input', { list: 'castmaplist', placeholder: `Pick from ${st.list.length} maps…`, style: { minWidth: '320px' } });
  input.addEventListener('change', () => {
    if (st.list.includes(input.value)) showMap(input.value);
  });
  const picker = el('div', { cls: 'map-picker' }, el('h2', { text: 'Cast & Scripts' }), input, dl);
  mount(view, picker, el('div', { cls: 'cols' }, listCol, main));

  // honor a cross-room deep link (e.g. from a Map View marker), else default
  const pending = window.pendingCast;
  window.pendingCast = null;
  if (pending && st.list.includes(pending.folder)) {
    input.value = pending.folder;
    showMap(pending.folder, pending.label);
  } else {
    const start = st.list.includes('Route101') ? 'Route101' : st.list[0];
    if (start) {
      input.value = start;
      showMap(start);
    }
  }
};
