const $ = (id) => document.getElementById(id);

function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }

function li(text, cls) {
  const e = document.createElement("li");
  e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

function askPin() {
  return new Promise((resolve) => {
    const dlg = $("pinDialog");
    $("pinInput").value = "";
    dlg.showModal();
    dlg.addEventListener("close", function handler() {
      dlg.removeEventListener("close", handler);
      resolve(dlg.returnValue === "ok" ? $("pinInput").value : null);
    });
  });
}

async function post(path, body) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : null,
  });
  if (res.status === 403) { alert("Wrong PIN"); return null; }
  return res.json();
}

async function postWithPin(path, extra = {}) {
  const pin = await askPin();
  if (pin === null) return null;
  return post(path, { ...extra, pin });
}

function renderStatus(s) {
  const map = { stopped: "grey", starting: "yellow", running: "lightgreen", crashed: "lightred" };
  $("light").className = "light " + (map[s.status] || "grey");
  $("statusText").textContent = {
    stopped: "Stopped", starting: "Starting...", running: "Running!", crashed: "Crashed",
  }[s.status] || s.status;
  const ul = $("players");
  clear(ul);
  if (s.players && s.players.length) {
    s.players.forEach((p) => ul.appendChild(li(p)));
  } else {
    ul.appendChild(li("nobody yet", "muted"));
  }
}

async function refreshAllowlist() {
  const data = await (await fetch("/api/allowlist")).json();
  const ul = $("allowlist");
  clear(ul);
  if (!data.players.length) { ul.appendChild(li("no one yet", "muted")); return; }
  data.players.forEach((p) => {
    const row = document.createElement("li");
    row.appendChild(document.createTextNode(p + " "));
    const btn = document.createElement("button");
    btn.className = "rm";
    btn.textContent = "remove";
    btn.addEventListener("click", async () => {
      await postWithPin("/api/allowlist/remove", { name: p });
      refreshAllowlist();
    });
    row.appendChild(btn);
    ul.appendChild(row);
  });
}

async function refreshSwitch() {
  const data = await (await fetch("/api/switch")).json();
  $("switchLight").className = "light small " + (data.running ? "lightgreen" : "lightred");
  $("dnsIp").textContent = data.dns_ip;
}

$("startBtn").onclick = () => post("/api/start");
$("stopBtn").onclick = () => postWithPin("/api/stop");
$("restartBtn").onclick = () => postWithPin("/api/restart");
$("backupBtn").onclick = async () => {
  $("backupMsg").textContent = "Saving...";
  const r = await post("/api/backup");
  $("backupMsg").textContent = r ? `Saved: ${r.backup}` : "Backup failed";
};
$("addPlayerBtn").onclick = async () => {
  const name = $("newPlayer").value.trim();
  if (!name) return;
  await postWithPin("/api/allowlist/add", { name });
  $("newPlayer").value = "";
  refreshAllowlist();
};

function connectWs() {
  const ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onmessage = (e) => renderStatus(JSON.parse(e.data));
  ws.onclose = () => setTimeout(connectWs, 2000);
}

(async function init() {
  renderStatus(await (await fetch("/api/status")).json());
  await refreshAllowlist();
  await refreshSwitch();
  setInterval(refreshSwitch, 10000);
  connectWs();
})();
