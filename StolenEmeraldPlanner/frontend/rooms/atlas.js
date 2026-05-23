window.renderAtlas = async function (view, ensureAtlas, setStatus) {
  mount(view, el('div', { cls: 'empty', text: 'Loading atlas…' }));
  let data;
  try {
    data = await ensureAtlas();
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not read the repo. ', e.message));
    return;
  }

  const mapNames = Object.keys(data.maps).sort();
  const pretty = (n) =>
    n.replace(/^MAP_/, '').replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
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
    mount(
      view,
      head,
      ...(blocks.length ? blocks : [el('div', { cls: 'empty', text: 'No wild encounters on this map.' })])
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
    const cards = shown.map((n) => {
      const count = Object.values(data.maps[n].methods).reduce((a, b) => a + b.mons.length, 0);
      return el(
        'div',
        { cls: 'map-card', onclick: () => showMap(n) },
        el('div', { text: pretty(n) }),
        el('div', { cls: 'mt', text: `${count} wild mon` })
      );
    });
    mount(view, head, el('div', { cls: 'map-list' }, ...cards));
    search.focus();
    search.setSelectionRange(filter.length, filter.length);
  }

  listMaps();
};
