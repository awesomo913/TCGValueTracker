// Sandbox: design "what you want" — place things on a route, rewrite NPC scripts,
// draft story beats. Saved as a PROPOSAL to the planner's own storage (never the
// repo) and exportable. Read-only toward StolenEmerald.
window.renderSandbox = async function (view, api, setStatus) {
  const TILE = 16;
  const SCALE = 3;
  const PX = TILE * SCALE;
  const PTYPES = ['npc', 'trainer', 'item', 'hidden', 'sign', 'warp', 'trigger'];

  mount(view, el('div', { cls: 'empty', text: 'Loading sandbox…' }));
  let sb, maps;
  try {
    sb = await api.sandbox();
    maps = (await api.maps()).maps;
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not load sandbox. ', e.message));
    return;
  }
  sb.layout = sb.layout || {};
  sb.scripts = sb.scripts || {};
  sb.story = sb.story || [];
  const folders = maps
    .filter((m) => m.renderable)
    .map((m) => m.name)
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));

  const st = { sub: 'layout', folder: folders[0] || '', placeType: 'npc' };

  // ---- save / export ----
  const saveBtn = el('button', { cls: 'back-btn', text: 'Save proposal' });
  async function save() {
    try {
      await api.saveSandbox(sb);
      setStatus('Sandbox saved (proposal only — repo untouched)');
      saveBtn.textContent = 'Saved ✓';
      setTimeout(() => (saveBtn.textContent = 'Save proposal'), 1500);
    } catch (e) {
      setStatus('Save failed: ' + e.message);
    }
  }
  saveBtn.addEventListener('click', save);
  const exportBtn = el('button', { cls: 'back-btn', text: 'Export JSON' });
  exportBtn.addEventListener('click', () => {
    const blob = new Blob([JSON.stringify(sb, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'stolenemerald_design_proposal.json';
    a.click();
    URL.revokeObjectURL(a.href);
  });

  const content = el('div', {});
  const subBtns = {};
  const subTab = (key, label) => {
    const b = el('button', {
      cls: 'subtab' + (st.sub === key ? ' on' : ''),
      text: label,
      onclick: () => {
        st.sub = key;
        Object.entries(subBtns).forEach(([k, btn]) => btn.classList.toggle('on', k === key));
        renderSub();
      },
    });
    subBtns[key] = b;
    return b;
  };

  // ---- map picker (shared by Layout + Scripts) ----
  function mapPicker(onPick) {
    const dl = el('datalist', { id: 'sbmaplist' }, ...folders.map((n) => el('option', { value: n })));
    const inp = el('input', { list: 'sbmaplist', value: st.folder, placeholder: 'Pick a route…', style: { minWidth: '260px' } });
    inp.addEventListener('focus', () => { inp.dataset.prev = inp.value; inp.value = ''; });
    inp.addEventListener('blur', () => { if (!folders.includes(inp.value)) inp.value = inp.dataset.prev || ''; });
    inp.addEventListener('change', () => { if (folders.includes(inp.value)) { st.folder = inp.value; inp.blur(); onPick(); } });
    return el('div', { cls: 'map-picker', style: { margin: '0 0 12px' } }, inp, dl);
  }

  // ================= LAYOUT =================
  async function renderLayout() {
    const wrap = el('div', { cls: 'empty', text: 'Loading map…' });
    mount(content, mapPicker(renderLayout), wrap);
    let detail;
    try {
      detail = await api.mapDetail(st.folder);
    } catch (e) {
      mount(content, mapPicker(renderLayout), el('div', { cls: 'empty', text: 'Map load failed.' }));
      return;
    }
    const placed = (sb.layout[st.folder] = sb.layout[st.folder] || []);

    const palette = el('div', { cls: 'sb-palette' },
      el('span', { cls: 's', text: 'Place:' }),
      ...PTYPES.map((t) =>
        el('button', {
          cls: 'pal-btn m-' + t + (st.placeType === t ? ' on' : ''),
          title: t,
          onclick: () => { st.placeType = t; palette.querySelectorAll('.pal-btn').forEach((b) => b.classList.remove('on')); palette.querySelector('.m-' + t).classList.add('on'); },
        })
      ),
      el('span', { cls: 's', style: { marginLeft: '10px' }, text: 'Click map to add · click a marker to remove' })
    );

    const stage = el('div', { cls: 'map-stage', style: { width: detail.width * PX + 'px', height: detail.height * PX + 'px' } });
    const img = el('img', { src: detail.render_url, alt: st.folder });
    img.style.width = detail.width * PX + 'px';
    img.style.height = detail.height * PX + 'px';
    stage.appendChild(img);

    function drawPlaced() {
      stage.querySelectorAll('.sb-mk').forEach((n) => n.remove());
      placed.forEach((p, i) => {
        const mk = el('div', { cls: 'marker sb-mk m-' + p.type, title: p.type + ' (click to remove)' });
        mk.style.left = p.x * PX + 'px';
        mk.style.top = p.y * PX + 'px';
        mk.style.width = PX + 'px';
        mk.style.height = PX + 'px';
        mk.addEventListener('click', (ev) => { ev.stopPropagation(); placed.splice(i, 1); drawPlaced(); });
        stage.appendChild(mk);
      });
    }
    stage.addEventListener('click', (ev) => {
      const r = stage.getBoundingClientRect();
      const x = Math.floor((ev.clientX - r.left) / PX);
      const y = Math.floor((ev.clientY - r.top) / PX);
      if (x < 0 || y < 0 || x >= detail.width || y >= detail.height) return;
      placed.push({ type: st.placeType, x, y });
      drawPlaced();
    });
    drawPlaced();

    mount(content, mapPicker(renderLayout), palette,
      el('div', { cls: 'map-stage-wrap', style: { maxHeight: 'calc(100vh - 250px)' } }, stage),
      el('div', { cls: 's', style: { marginTop: '8px' } }, `${placed.length} placed on ${st.folder}`));
  }

  // ================= SCRIPTS =================
  async function renderScripts() {
    mount(content, mapPicker(renderScripts), el('div', { cls: 'empty', text: 'Loading scripts…' }));
    let labels = {};
    try {
      labels = (await (await fetch('/api/scripts/' + encodeURIComponent(st.folder))).json()).labels || {};
    } catch (e) { /* leave empty */ }
    const names = Object.keys(labels);
    sb.scripts[st.folder] = sb.scripts[st.folder] || {};
    const ta = el('textarea', { cls: 'sb-script', spellcheck: 'false' });
    const sel = el('select', {});
    sel.append(...names.map((n) => el('option', { value: n, text: n })));
    function load() {
      const n = sel.value;
      ta.value = sb.scripts[st.folder][n] !== undefined ? sb.scripts[st.folder][n] : labels[n] || '';
    }
    sel.addEventListener('change', load);
    ta.addEventListener('input', () => { sb.scripts[st.folder][sel.value] = ta.value; });
    if (names.length) load();
    else ta.value = '(no scripts on this map)';
    mount(content, mapPicker(renderScripts),
      el('div', { cls: 'map-picker', style: { margin: '0 0 8px' } }, el('span', { cls: 's', text: 'Script:' }), sel),
      ta,
      el('div', { cls: 's', style: { marginTop: '6px' }, text: 'Edits are your proposed rewrite — saved to the sandbox, not the game.' }));
  }

  // ================= STORY =================
  function renderStory() {
    const list = el('div', {});
    function draw() {
      const rows = sb.story.map((beat, i) =>
        el('div', { cls: 'sb-beat' },
          field(beat, 'var', 'VAR_… / flag'),
          field(beat, 'value', 'value'),
          field(beat, 'map', 'where (map)'),
          field(beat, 'note', 'what happens', true),
          el('button', { cls: 'back-btn', text: '✕', title: 'remove', onclick: () => { sb.story.splice(i, 1); draw(); } })
        )
      );
      mount(list, ...(rows.length ? rows : [el('div', { cls: 's', text: 'No story beats yet — add one below.' })]));
    }
    function field(obj, key, ph, wide) {
      const inp = el('input', { value: obj[key] || '', placeholder: ph, style: wide ? { flex: '2' } : { flex: '1' } });
      inp.addEventListener('input', () => { obj[key] = inp.value; });
      return inp;
    }
    const addBtn = el('button', { cls: 'back-btn', text: '+ Add story beat', onclick: () => { sb.story.push({ var: '', value: '', map: '', note: '' }); draw(); } });
    draw();
    mount(content,
      el('div', { cls: 's', style: { marginBottom: '10px' }, text: 'Draft the story you want — beats fire when a flag/var reaches a value on a map.' }),
      list, el('div', { style: { marginTop: '10px' } }, addBtn));
  }

  function renderSub() {
    if (st.sub === 'layout') renderLayout();
    else if (st.sub === 'scripts') renderScripts();
    else renderStory();
  }

  const head = el('div', { cls: 'view-head' },
    el('h2', { text: 'Sandbox' }),
    el('span', { cls: 'sb-banner', text: 'Design mode — saves a proposal, never changes the game' }),
    saveBtn, exportBtn);
  const tabs = el('div', { cls: 'sb-tabs' }, subTab('layout', 'Route layout'), subTab('scripts', 'NPC scripts'), subTab('story', 'Story'));
  mount(view, head, tabs, content);
  renderSub();
};
