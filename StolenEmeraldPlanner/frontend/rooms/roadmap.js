window.renderRoadmap = async function (view, api, setStatus) {
  mount(view, el('div', { cls: 'empty', text: 'Loading roadmap…' }));
  let data;
  try {
    data = await api.roadmap();
  } catch (e) {
    mount(view, el('div', { cls: 'empty' }, 'Could not load roadmap. ', e.message));
    return;
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
  const sections = data.sections.map((sec) =>
    el(
      'div',
      { cls: 'rm-section' },
      el('h3', { text: sec.title }),
      ...sec.items.map((it) =>
        el(
          'div',
          { cls: 'rm-item ' + it.status },
          el('div', { cls: 'box', text: mark[it.status] || '' }),
          el('div', { cls: 'txt', text: it.text })
        )
      )
    )
  );

  mount(
    view,
    el('div', { cls: 'view-head' }, el('h2', { text: 'Roadmap & Future Plans' })),
    summary,
    progress,
    ...(sections.length ? sections : [el('div', { cls: 'empty', text: 'No roadmap items found.' })]),
    el('div', { cls: 's', style: { color: 'var(--muted)', marginTop: '14px', fontSize: '12px' } },
      'Edit frontend/roadmap.md to update this list — the view reads it live.')
  );
};
