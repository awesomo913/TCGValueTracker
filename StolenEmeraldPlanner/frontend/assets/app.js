// Response cache so re-opening a tab or re-visiting a map/doc is instant.
// Busted whenever the repo changes (see api.bust + pollStatus).
const _cache = {};
async function getJSON(key, url, errLabel) {
  if (_cache[key] !== undefined) return _cache[key];
  const r = await fetch(url);
  if (!r.ok) throw new Error((errLabel || 'load') + ' failed (' + r.status + ')');
  const data = await r.json();
  _cache[key] = data;
  return data;
}

const api = {
  bust() {
    for (const k in _cache) delete _cache[k];
  },
  atlas() {
    return getJSON('atlas', '/api/atlas', 'repo not found');
  },
  async rescan() {
    const r = await fetch('/api/rescan', { method: 'POST' });
    return r.ok;
  },
  maps() {
    return getJSON('maps', '/api/maps', 'repo not found');
  },
  mapDetail(folder) {
    return getJSON('map:' + folder, '/api/map/' + encodeURIComponent(folder), 'map load');
  },
  scriptTrainer(folder, label) {
    return getJSON(
      'st:' + folder + ':' + label,
      '/api/script_trainer/' + encodeURIComponent(folder) + '/' + encodeURIComponent(label),
      'script load'
    );
  },
  history() {
    return getJSON('history', '/api/history', 'repo not found');
  },
  historyDoc(rel) {
    return getJSON('doc:' + rel, '/api/history/doc?rel=' + encodeURIComponent(rel), 'doc load');
  },
  async historySearch(q) {
    // search is dynamic — never cached
    const r = await fetch('/api/history/search?q=' + encodeURIComponent(q));
    if (!r.ok) throw new Error('search failed (' + r.status + ')');
    return r.json();
  },
  timeline() {
    return getJSON('timeline', '/api/timeline', 'repo not found');
  },
  async status() {
    const r = await fetch('/api/status'); // status is live — never cached
    if (!r.ok) throw new Error('status failed (' + r.status + ')');
    return r.json();
  },
  roadmap() {
    return getJSON('roadmap', '/api/roadmap', 'roadmap');
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
  atlas: () => window.renderAtlas(view, ensureAtlas, setStatus, api),
  mapview: () => window.renderMapView(view, api, setStatus),
  cast: () => window.renderCast(view, api, setStatus),
  command: () => window.renderCommand(view, api, setStatus),
  timeline: () => window.renderTimeline(view, api, setStatus),
  roadmap: () => window.renderRoadmap(view, api, setStatus),
};

let currentRoom = 'atlas';

function activate(room) {
  currentRoom = room;
  window.clearScene && window.clearScene(); // reset bg; the room re-sets it if relevant
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

// faded route background — preload then crossfade so there's no broken-image flash
const _sceneEl = document.getElementById('scene');
let _sceneKey = null;
// preload any image URL, then crossfade it in as the faded backdrop
window.setSceneUrl = (url, key) => {
  if (!url) return window.clearScene();
  key = key || url;
  if (key === _sceneKey) return; // already showing this scene
  _sceneKey = key;
  const img = new Image();
  img.onload = () => {
    if (_sceneKey !== key) return; // a newer scene was requested meanwhile
    _sceneEl.style.backgroundImage = `url("${url}")`;
    _sceneEl.classList.add('on');
  };
  img.onerror = () => {
    if (_sceneKey === key) window.clearScene();
  };
  img.src = url;
};
window.setScene = (folder) => {
  if (!folder) return window.clearScene();
  window.setSceneUrl('/api/map_render/' + encodeURIComponent(folder) + '.png', folder);
};
window.clearScene = () => {
  _sceneKey = null;
  if (_sceneEl) _sceneEl.classList.remove('on');
};

// cross-room deep link: open a map's script in Cast & Scripts from anywhere
window.openInCast = (folder, label) => {
  window.pendingCast = { folder, label };
  activate('cast');
};

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
      api.bust(); // clear all cached responses so the next fetch rebuilds
      state.atlas = null;
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
  api.bust();
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
