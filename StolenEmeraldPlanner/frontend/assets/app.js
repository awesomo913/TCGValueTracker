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
  async maps() {
    const r = await fetch('/api/maps');
    if (!r.ok) throw new Error('repo not found (' + r.status + ')');
    return r.json();
  },
  async mapDetail(folder) {
    const r = await fetch('/api/map/' + encodeURIComponent(folder));
    if (!r.ok) throw new Error('map load failed (' + r.status + ')');
    return r.json();
  },
  async scriptTrainer(folder, label) {
    const r = await fetch('/api/script_trainer/' + encodeURIComponent(folder) + '/' + encodeURIComponent(label));
    if (!r.ok) throw new Error('script load failed (' + r.status + ')');
    return r.json();
  },
  async history() {
    const r = await fetch('/api/history');
    if (!r.ok) throw new Error('repo not found (' + r.status + ')');
    return r.json();
  },
  async historyDoc(rel) {
    const r = await fetch('/api/history/doc?rel=' + encodeURIComponent(rel));
    if (!r.ok) throw new Error('doc load failed (' + r.status + ')');
    return r.json();
  },
  async historySearch(q) {
    const r = await fetch('/api/history/search?q=' + encodeURIComponent(q));
    if (!r.ok) throw new Error('search failed (' + r.status + ')');
    return r.json();
  },
  async timeline() {
    const r = await fetch('/api/timeline');
    if (!r.ok) throw new Error('repo not found (' + r.status + ')');
    return r.json();
  },
  async status() {
    const r = await fetch('/api/status');
    if (!r.ok) throw new Error('status failed (' + r.status + ')');
    return r.json();
  },
  async roadmap() {
    const r = await fetch('/api/roadmap');
    if (!r.ok) throw new Error('roadmap failed (' + r.status + ')');
    return r.json();
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

const rooms = {
  atlas: () => window.renderAtlas(view, ensureAtlas, setStatus),
  mapview: () => window.renderMapView(view, api, setStatus),
  cast: () => window.renderCast(view, api, setStatus),
  command: () => window.renderCommand(view, api, setStatus),
  timeline: () => window.renderTimeline(view, api, setStatus),
  roadmap: () => window.renderRoadmap(view, api, setStatus),
};

let currentRoom = 'atlas';

function activate(room) {
  currentRoom = room;
  document.querySelectorAll('.nav-item').forEach((b) =>
    b.classList.toggle('active', b.dataset.room === room)
  );
  if (rooms[room]) rooms[room]();
  else mount(view, el('div', { cls: 'empty', text: 'Coming soon.' }));
  // re-trigger the seamless fade-in
  view.classList.remove('anim');
  void view.offsetWidth; // force reflow so the animation restarts
  view.classList.add('anim');
}

// --- live update detection: poll the repo signature; refresh on change ---
const liveEl = document.getElementById('live');
let lastSig = null;
function setLive(text, cls) {
  if (!liveEl) return;
  liveEl.textContent = text;
  liveEl.className = 'live ' + (cls || '');
}
async function pollStatus() {
  try {
    const s = await api.status();
    if (!s.repo_found) {
      setLive('● repo not found', 'bad');
      return;
    }
    if (lastSig === null) {
      lastSig = s.signature;
    } else if (s.signature !== lastSig) {
      lastSig = s.signature;
      setStatus('Repo changed — refreshing…');
      state.atlas = null; // bust client cache so the next fetch rebuilds
      try {
        activate(currentRoom); // re-render current room with fresh data
      } catch (err) {
        setStatus('Refresh error: ' + err.message); // don't let it kill the poll loop
      }
    }
    setLive('● live', 'ok');
  } catch (e) {
    setLive('● offline', 'bad');
  }
}
setInterval(pollStatus, 5000);
pollStatus();

document.querySelectorAll('.nav-item').forEach((b) => {
  b.addEventListener('click', () => {
    if (!b.disabled) activate(b.dataset.room);
  });
});

document.getElementById('rescan').addEventListener('click', async () => {
  setStatus('Rescanning…');
  state.atlas = null;
  try {
    if (!(await api.rescan())) {
      setStatus('Rescan failed (server error)');
      return;
    }
    await ensureAtlas();
    activate('atlas');
  } catch (e) {
    setStatus('Rescan failed: ' + e.message);
  }
});

activate('atlas');
