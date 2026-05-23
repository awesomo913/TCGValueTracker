const api = {
  async atlas() {
    const r = await fetch('/api/atlas');
    if (!r.ok) throw new Error('repo not found (' + r.status + ')');
    return r.json();
  },
  async rescan() {
    const r = await fetch('/api/rescan', { method: 'POST' });
    return r.ok;
  },
};

const state = { atlas: null };
const view = document.getElementById('view');
const statusEl = document.getElementById('status');

function setStatus(msg) {
  statusEl.textContent = msg || '';
}

async function ensureAtlas() {
  if (state.atlas) return state.atlas;
  setStatus('Reading repo…');
  state.atlas = await api.atlas();
  setStatus(Object.keys(state.atlas.maps).length + ' maps loaded');
  return state.atlas;
}

const rooms = { atlas: () => window.renderAtlas(view, ensureAtlas, setStatus) };

function activate(room) {
  document.querySelectorAll('.nav-item').forEach((b) =>
    b.classList.toggle('active', b.dataset.room === room)
  );
  if (rooms[room]) rooms[room]();
  else mount(view, el('div', { cls: 'empty', text: 'Coming soon.' }));
}

document.querySelectorAll('.nav-item').forEach((b) => {
  b.addEventListener('click', () => {
    if (!b.disabled) activate(b.dataset.room);
  });
});

document.getElementById('rescan').addEventListener('click', async () => {
  setStatus('Rescanning…');
  state.atlas = null;
  try {
    await api.rescan();
    await ensureAtlas();
    activate('atlas');
  } catch (e) {
    setStatus('Rescan failed: ' + e.message);
  }
});

activate('atlas');
