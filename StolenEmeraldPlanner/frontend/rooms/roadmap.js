window.renderRoadmap = async function (view, api, setStatus) {
  mount(view, el('div', { cls: 'empty', text: 'Loading roadmap…' }));
  let data;
  try {
    data = await api.roadmap();
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not load roadmap. ', e.message));
    return;
  }

  // renderable route folders so each roadmap item fades a real route behind it
  // (skips imported routes without a layout, which would 404)
  let routes = [];
  try {
    routes = (await api.maps()).maps
      .filter((m) => m.renderable && /^Route\d{3}$/.test(m.name)) // Hoenn main routes (101-134)
      .map((m) => m.name)
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  } catch (e) {
    routes = [];
  }

  const c = data.counts || { done: 0, wip: 0, planned: 0 };
  const total = c.done + c.wip + c.planned || 1;
  const pct = Math.round((c.done / total) * 100);

  const summary = el(
    'div',
    { cls: 'rm-summary' },
    el('div', { cls: 'rm-chip done' }, el('b', { text: String(c.done) }), 'Shipped'),
    el('div', { cls: 'rm-chip wip' }, el('b', { text: String(c.wip) }), 'In progress'),
    el('div', { cls: 'rm-chip planned' }, el('b', { text: String(c.planned) }), 'Planned'),
    el('div', { cls: 'rm-chip' }, el('b', { text: pct + '%' }), 'Done')
  );
  const progress = el('div', { cls: 'rm-progress' }, el('i', { style: { width: pct + '%' } }));

  const mark = { done: '✓', wip: '~', planned: '' };
  let itemIndex = 0; // assign each item a route so clicking shows a different one
  const makeItem = (it) => {
    const route = routes.length ? routes[itemIndex % routes.length] : null;
    itemIndex++;
    const row = el(
      'div',
      { cls: 'rm-item ' + it.status + (route ? ' clickable' : ''), title: route ? 'Click to see ' + route : '' },
      el('div', { cls: 'box', text: mark[it.status] || '' }),
      el('div', { cls: 'txt', text: it.text })
    );
    if (route) {
      row.addEventListener('click', () => {
        view.querySelectorAll('.rm-item').forEach((r) => r.classList.remove('sel'));
        row.classList.add('sel');
        window.setScene(route); // fade this route's map in behind the roadmap
      });
    }
    return row;
  };
  const sections = data.sections.map((sec) =>
    el('div', { cls: 'rm-section' }, el('h3', { text: sec.title }), ...sec.items.map(makeItem))
  );

  mount(
    view,
    el('div', { cls: 'view-head' }, el('h2', { text: 'Roadmap & Future Plans' })),
    summary,
    progress,
    ...(sections.length ? sections : [el('div', { cls: 'empty', text: 'No roadmap items found.' })]),
    el('div', { cls: 's', style: { color: 'var(--muted)', marginTop: '14px', fontSize: '12px' } },
      'Click any item to fade a route map behind it. Edit frontend/roadmap.md to update this list — the view reads it live.')
  );
};
