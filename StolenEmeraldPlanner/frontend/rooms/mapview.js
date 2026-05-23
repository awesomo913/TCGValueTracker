window.renderMapView = async function (view, api, setStatus) {
  const TILE = 16;
  const LAYERS = [
    { key: 'terrain', label: 'Encounter zones', swatch: '#4fd07a' },
    { key: 'npcs', label: 'NPCs', cls: 'm-npc', swatch: '#4aa3ff' },
    { key: 'trainers', label: 'Trainers', cls: 'm-trainer', swatch: '#ff5b5b' },
    { key: 'item_balls', label: 'Item balls', cls: 'm-item', swatch: '#e8c66b' },
    { key: 'hidden_items', label: 'Hidden items', cls: 'm-hidden', swatch: '#ff9a3c' },
    { key: 'signs', label: 'Signs', cls: 'm-sign', swatch: '#9fb0aa' },
    { key: 'warps', label: 'Warps', cls: 'm-warp', swatch: '#b07cff' },
    { key: 'triggers', label: 'Events / triggers', cls: 'm-trigger', swatch: '#4fe0ad' },
  ];
  const st = { detail: null, scale: 2, on: {}, list: [] };
  LAYERS.forEach((l) => (st.on[l.key] = true));

  const monName = (s) => s.replace(/^SPECIES_/, '').replace(/_/g, ' ').toLowerCase();
  const itemName = (s) => s.replace(/^ITEM_/, '').replace(/_/g, ' ').toLowerCase();

  // --- side panel pieces (rebuilt on demand) ---
  const detailBox = el('div', { cls: 'detail-box' }, el('div', { cls: 'row', text: 'Click a marker to inspect it.' }));
  const terrainPanel = el('div', {});

  function setDetail(title, rows) {
    const kids = [el('h4', { text: title })];
    for (const [k, v] of rows) kids.push(el('div', { cls: 'row' }, el('b', { text: k + ': ' }), String(v)));
    mount(detailBox, ...kids);
  }

  function toggleEl(layer, count) {
    const t = el(
      'div',
      {
        cls: 'layer-toggle' + (st.on[layer.key] ? '' : ' off'),
        onclick: () => {
          st.on[layer.key] = !st.on[layer.key];
          drawStage();
          buildSide();
        },
      },
      el('span', { cls: 'swatch', style: { background: layer.swatch } }),
      el('span', { text: layer.label }),
      el('span', { cls: 'cnt', text: String(count) })
    );
    return t;
  }

  const METHOD_LABELS = {
    land_mons: 'Grass / land',
    water_mons: 'Surfing / water',
    rock_smash_mons: 'Rock smash',
    fishing_mons: 'Fishing',
  };

  function encMonCard(m) {
    return el(
      'div',
      { cls: 'enc-card' },
      el('img', { src: '/api/sprite/' + encodeURIComponent(m.species), alt: monName(m.species), loading: 'lazy' }),
      el(
        'div',
        { style: { flex: '1' } },
        el('div', { cls: 'nm', text: monName(m.species) }),
        el('div', { cls: 'meta', text: `Lv ${m.min_level}-${m.max_level} · ${m.rarity_pct}%` }),
        el('div', { cls: 'rb' }, el('i', { style: { width: Math.min(100, m.rarity_pct) + '%' } }))
      )
    );
  }

  function encounterPanel() {
    const d = st.detail;
    const methods = (d && d.encounters) || {};
    const order = ['land_mons', 'water_mons', 'rock_smash_mons', 'fishing_mons'];
    const sections = [];
    for (const key of order) {
      const blk = methods[key];
      if (!blk || !blk.mons || !blk.mons.length) continue;
      const seen = new Set();
      const cards = [];
      for (const m of blk.mons) {
        if (seen.has(m.species)) continue;
        seen.add(m.species);
        cards.push(encMonCard(m));
      }
      sections.push(el('div', { cls: 'enc-head', text: `${METHOD_LABELS[key]} (${seen.size})` }));
      sections.push(...cards);
    }
    if (!sections.length) {
      return el('div', { cls: 'enc-panel' }, el('h4', { text: 'Wild Pokémon' }), el('div', { cls: 'meta', text: 'No wild encounters on this map.' }));
    }
    return el('div', { cls: 'enc-panel' }, el('h4', { text: 'Wild Pokémon here' }), ...sections);
  }

  function buildSide() {
    const d = st.detail;
    const zoom = el(
      'div',
      { cls: 'zoombar' },
      el('button', { text: '−', onclick: () => { st.scale = Math.max(1, st.scale - 1); drawStage(); } }),
      el('span', { cls: 'cnt', text: st.scale + '×' }),
      el('button', { text: '+', onclick: () => { st.scale = Math.min(6, st.scale + 1); drawStage(); } })
    );
    const toggles = LAYERS.map((l) => toggleEl(l, l.key === 'terrain' ? cellCount() : (d[l.key] || []).length));
    mount(terrainPanel, encounterPanel());
    mount(side, terrainPanel, zoom, ...toggles, detailBox);
  }

  function cellCount() {
    const d = st.detail;
    if (!d || !d.terrain) return 0;
    let n = 0;
    for (const row of d.terrain) for (const c of row) if (c) n++;
    return n;
  }

  // --- map stage (image + terrain canvas + markers) ---
  const stage = el('div', { cls: 'map-stage' });
  const stageWrap = el('div', { cls: 'map-stage-wrap' }, stage);
  const side = el('div', { cls: 'map-side' });

  function addSpawnCluster(cls, mons, PX) {
    const d = st.detail;
    if (!mons.length || !d.terrain) return;
    // centroid of cells of this terrain class
    let sx = 0, sy = 0, n = 0;
    for (let y = 0; y < d.terrain.length; y++) {
      for (let x = 0; x < d.terrain[y].length; x++) {
        if (d.terrain[y][x] === cls) { sx += x; sy += y; n++; }
      }
    }
    if (!n) return;
    const cx = (sx / n + 0.5) * PX;
    const cy = (sy / n + 0.5) * PX;
    const seen = [];
    for (const m of mons) {
      if (!seen.includes(m.species)) seen.push(m.species);
      if (seen.length >= 6) break;
    }
    const uniqueTotal = new Set(mons.map((m) => m.species)).size;
    const cluster = el('div', { cls: 'spawn-cluster' + (cls === 'water' ? ' water' : '') });
    cluster.style.left = cx + 'px';
    cluster.style.top = cy + 'px';
    for (const sp of seen) {
      cluster.appendChild(el('img', { src: '/api/sprite/' + encodeURIComponent(sp), alt: monName(sp), title: monName(sp) }));
    }
    if (uniqueTotal > seen.length) {
      cluster.appendChild(el('span', { cls: 'more', text: '+' + (uniqueTotal - seen.length) }));
    }
    stage.appendChild(cluster);
  }

  function drawStage() {
    const d = st.detail;
    if (!d) return;
    const PX = TILE * st.scale;
    const w = d.width, h = d.height;
    stage.style.width = w * PX + 'px';
    stage.style.height = h * PX + 'px';
    clear(stage);

    const img = el('img', { src: d.render_url, alt: d.name });
    img.style.width = w * PX + 'px';
    img.style.height = h * PX + 'px';
    stage.appendChild(img);

    if (st.on.terrain && d.terrain && d.terrain.length) {
      const cv = el('canvas');
      cv.width = w * TILE;
      cv.height = h * TILE;
      cv.style.width = w * PX + 'px';
      cv.style.height = h * PX + 'px';
      const ctx = cv.getContext('2d');
      for (let y = 0; y < d.terrain.length; y++) {
        for (let x = 0; x < d.terrain[y].length; x++) {
          const c = d.terrain[y][x];
          if (c === 'grass') ctx.fillStyle = 'rgba(80,220,120,.40)';
          else if (c === 'water') ctx.fillStyle = 'rgba(80,160,240,.40)';
          else continue;
          ctx.fillRect(x * TILE, y * TILE, TILE, TILE);
        }
      }
      stage.appendChild(cv);
      addSpawnCluster('grass', (d.encounters.land_mons || {}).mons || [], PX);
      addSpawnCluster('water', (d.encounters.water_mons || {}).mons || [], PX);
    }

    for (const layer of LAYERS) {
      if (layer.key === 'terrain' || !st.on[layer.key]) continue;
      for (const item of d[layer.key] || []) {
        if (item.x == null || item.y == null) continue;
        const mk = el('div', { cls: 'marker ' + layer.cls, title: layer.label });
        mk.style.left = item.x * PX + 'px';
        mk.style.top = item.y * PX + 'px';
        mk.style.width = PX + 'px';
        mk.style.height = PX + 'px';
        mk.addEventListener('click', () => inspect(layer, item));
        stage.appendChild(mk);
      }
    }
    buildSideCounts();
  }

  function buildSideCounts() {
    side.querySelectorAll('.layer-toggle').forEach((tg, i) => {
      tg.classList.toggle('off', !st.on[LAYERS[i].key]);
    });
  }

  function inspect(layer, item) {
    const rows = [['tile', `(${item.x}, ${item.y})`]];
    if (layer.key === 'npcs' || layer.key === 'signs') {
      if (item.graphics_id) rows.push(['graphics', item.graphics_id]);
      if (item.script) rows.push(['script', item.script]);
    } else if (layer.key === 'trainers') {
      rows.push(['sight range', item.sight]);
      rows.push(['script', item.script]);
      if (item.flag && item.flag !== '0') rows.push(['flag', item.flag]);
    } else if (layer.key === 'item_balls') {
      rows.push(['script', item.script]);
    } else if (layer.key === 'hidden_items') {
      rows.push(['item', itemName(item.item)]);
      rows.push(['flag', item.flag]);
      if (item.quantity) rows.push(['quantity', item.quantity]);
    } else if (layer.key === 'warps') {
      rows.push(['leads to', item.dest_map.replace(/^MAP_/, '')]);
    } else if (layer.key === 'triggers') {
      rows.push(['when', `${item.var} == ${item.var_value}`]);
      rows.push(['runs', item.script]);
    }
    setDetail(layer.label, rows);
    // seamless cross-room jump: open this marker's script in Cast & Scripts
    if (item.script && st.detail) {
      const jump = el('button', {
        cls: 'back-btn',
        style: { marginTop: '10px', borderColor: 'var(--emerald-bright)' },
        text: 'Open script in Cast →',
        onclick: () => window.openInCast(st.detail.folder, item.script),
      });
      detailBox.appendChild(jump);
    }
  }

  async function loadMap(folder) {
    window.setScene(folder); // immersive faded backdrop of the same map
    setStatus('Loading ' + folder + '…');
    try {
      st.detail = await api.mapDetail(folder);
    } catch (e) {
      setStatus('Map load failed: ' + e.message);
      return;
    }
    setStatus(folder + ' loaded');
    drawStage();
    buildSide();
  }

  // --- top picker ---
  mount(view, el('div', { cls: 'empty', text: 'Loading map list…' }));
  let maps;
  try {
    maps = (await api.maps()).maps;
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not read the repo. ', e.message));
    return;
  }
  if (!Array.isArray(maps)) {
    mount(view, el('div', { cls: 'empty', text: 'Unexpected map list shape from server.' }));
    return;
  }
  st.list = maps.map((m) => m.name).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));

  const dl = el('datalist', { id: 'maplist' }, ...st.list.map((n) => el('option', { value: n })));
  const input = el('input', {
    list: 'maplist',
    placeholder: `Pick from ${st.list.length} maps (try Route101)…`,
    style: { minWidth: '320px' },
  });
  // clear on focus so the FULL list shows again (else the dropdown stays
  // filtered to the current map and you can't browse back to other routes)
  input.addEventListener('focus', () => {
    input.dataset.prev = input.value;
    input.value = '';
  });
  input.addEventListener('blur', () => {
    if (!st.list.includes(input.value)) input.value = input.dataset.prev || '';
  });
  input.addEventListener('change', () => {
    if (st.list.includes(input.value)) {
      input.dataset.prev = input.value;
      loadMap(input.value);
      input.blur();
    }
  });
  const picker = el('div', { cls: 'map-picker' }, el('h2', { text: 'Map View' }), input, dl);

  mount(view, picker, el('div', { cls: 'mapview' }, stageWrap, side));

  // default to Route101 if present, else first map
  const start = st.list.includes('Route101') ? 'Route101' : st.list[0];
  if (start) {
    input.value = start;
    loadMap(start);
  }
};
