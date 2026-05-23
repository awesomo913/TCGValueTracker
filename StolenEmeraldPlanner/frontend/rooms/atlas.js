window.renderAtlas = async function (view, ensureAtlas, setStatus) {
  mount(view, el('div', { cls: 'empty', text: 'Loading atlas…' }));
  let data;
  try {
    data = await ensureAtlas();
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not read the repo. ', e.message));
    return;
  }

  const pretty = (n) =>
    n
      .replace(/^MAP_/, '')
      .replace(/_/g, ' ')
      .replace(/([A-Za-z])(\d)/g, '$1 $2') // "Route101" -> "Route 101"
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  const byName = (a, b) => pretty(a).localeCompare(pretty(b), undefined, { numeric: true });
  const mapNames = Object.keys(data.maps).sort(byName);
  const monName = (s) => s.replace(/^SPECIES_/, '').replace(/_/g, ' ').toLowerCase();
  const spriteUrl = (s, kind) =>
    '/api/sprite/' + encodeURIComponent(s) + (kind ? '?kind=' + kind : '');

  function spriteEl(species, big) {
    const folder = data.sprite_folders[species];
    const ph = () =>
      el('div', {
        cls: 'placeholder-img',
        style: big ? { width: '128px', height: '128px' } : {},
        text: 'no sprite',
      });
    if (!folder) return ph();
    const img = el('img', {
      src: spriteUrl(species, big ? 'front' : null),
      alt: monName(species),
      loading: 'lazy',
    });
    if (big) {
      img.style.width = '128px';
      img.style.height = '128px';
      img.style.imageRendering = 'pixelated';
    }
    img.addEventListener('error', () => img.replaceWith(ph()));
    return img;
  }

  function monCard(mon) {
    return el(
      'div',
      { cls: 'mon-card', onclick: () => showMon(mon.species) },
      spriteEl(mon.species, false),
      el('div', { cls: 'name', text: monName(mon.species) }),
      el('div', { cls: 'lv', text: `Lv ${mon.min_level}-${mon.max_level} · ${mon.rarity_pct}%` }),
      el('div', { cls: 'rarity' }, el('i', { style: { width: Math.min(100, mon.rarity_pct) + '%' } }))
    );
  }

  function showMap(name) {
    const m = data.maps[name];
    const head = el(
      'div',
      { cls: 'view-head' },
      el('button', { cls: 'back-btn', text: '← Maps', onclick: listMaps }),
      el('h2', { text: pretty(name) })
    );
    const blocks = Object.entries(m.methods).map(([meth, blk]) =>
      el(
        'div',
        {},
        el('div', {
          cls: 'method-tag',
          text: `${meth.replace('_mons', '').replace('_', ' ')} · rate ${blk.encounter_rate}`,
        }),
        el('div', { cls: 'mon-grid' }, ...blk.mons.map(monCard))
      )
    );
    const legend = el(
      'div',
      { cls: 'legend' },
      el('span', { text: 'Rarity:' }),
      el('span', { cls: 'lg' }, el('span', { cls: 'bar' }), 'longer = more common'),
      el('span', { cls: 'lg', text: 'Method shown per group (land / water / rock smash / fishing)' })
    );
    mount(
      view,
      head,
      ...(blocks.length ? [legend, ...blocks] : [el('div', { cls: 'empty', text: 'No wild encounters on this map.' })])
    );
  }

  function showMon(species) {
    const where = data.species_index[species] || [];
    const head = el(
      'div',
      { cls: 'view-head' },
      el('button', { cls: 'back-btn', text: '←', onclick: listMaps }),
      el('h2', { style: { textTransform: 'capitalize' }, text: monName(species) })
    );
    const list = el(
      'div',
      { cls: 'map-list' },
      ...where.map((n) => el('div', { cls: 'map-card', onclick: () => showMap(n) }, el('div', { text: pretty(n) })))
    );
    const body = el(
      'div',
      { style: { display: 'flex', gap: '24px', flexWrap: 'wrap' } },
      el('div', {}, spriteEl(species, true)),
      el('div', {}, el('h3', { text: `Appears on ${where.length} map(s)` }), list)
    );
    mount(view, head, body);
  }

  function listMaps(filter) {
    filter = typeof filter === 'string' ? filter : '';
    const shown = mapNames.filter((n) => pretty(n).toLowerCase().includes(filter.toLowerCase()));
    const search = el('input', {
      id: 'mapsearch',
      placeholder: `Search ${mapNames.length} maps…`,
      value: filter,
    });
    search.addEventListener('input', () => listMaps(search.value));
    const head = el(
      'div',
      { cls: 'view-head' },
      el('h2', { text: 'World Atlas' }),
      search,
      el('span', { cls: 'status', text: `${shown.length} shown` })
    );
    const ORDER = ['Routes', 'Towns & Cities', 'Caves', 'Mountains', 'Forests', 'Sea & Islands', 'Other Areas'];
    const category = (n) => {
      const s = n.toUpperCase();
      if (s.includes('ROUTE')) return 'Routes';
      if (s.includes('CITY') || s.includes('TOWN')) return 'Towns & Cities';
      if (s.includes('CAVE') || s.includes('TUNNEL') || s.includes('GROTTO') || s.includes('CHAMBER')) return 'Caves';
      if (s.includes('MT_') || s.includes('MOUNTAIN') || s.includes('ASCENT') || s.includes('PEAK')) return 'Mountains';
      if (s.includes('FOREST') || s.includes('WOODS')) return 'Forests';
      if (s.includes('SEA') || s.includes('OCEAN') || s.includes('UNDERWATER') || s.includes('ISLAND') || s.includes('WATER')) return 'Sea & Islands';
      return 'Other Areas';
    };
    const mapCard = (n) => {
      const count = Object.values(data.maps[n].methods).reduce((a, b) => a + b.mons.length, 0);
      return el(
        'div',
        { cls: 'map-card', onclick: () => showMap(n) },
        el('div', { text: pretty(n) }),
        el('div', { cls: 'mt', text: `${count} wild mon` })
      );
    };
    const groups = {};
    for (const n of shown) (groups[category(n)] = groups[category(n)] || []).push(n);
    const children = [];
    for (const cat of ORDER) {
      const list = groups[cat];
      if (!list || !list.length) continue;
      list.sort(byName);
      children.push(el('div', { cls: 'region-h' }, cat, el('span', { cls: 'rc', text: list.length + ' maps' })));
      for (const n of list) children.push(mapCard(n));
    }
    mount(view, head, el('div', { cls: 'map-list' }, ...children));
    search.focus();
    search.setSelectionRange(filter.length, filter.length);
  }

  listMaps();
};
