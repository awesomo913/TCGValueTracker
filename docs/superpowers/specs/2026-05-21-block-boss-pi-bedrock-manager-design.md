# Block Boss — Pi 5 Bedrock Server + Kid-Friendly Manager

**Date:** 2026-05-21
**Status:** Design approved, ready for implementation plan
**Target hardware:** Raspberry Pi 5, 8GB RAM, Raspberry Pi OS 64-bit (Debian, ARM64)

---

## Plain-English summary

Block Boss is a single web page hosted on the Raspberry Pi. A 7-year-old opens it
on a tablet or PC and taps big buttons to run a Minecraft (Bedrock) server. A parent
manages the same page from their PC. Behind the page, Python runs the real Bedrock
game server and a helper that lets a Nintendo Switch connect.

The dashboard is reached at `http://<pi-ip>:8000` on the home WiFi. The PC terminal
(SSH) is only needed for the one-time install.

---

## Why these choices

### Engine: Box64 + official Bedrock Dedicated Server
Mojang ships the Bedrock Dedicated Server (BDS) only for x86_64. The Pi 5 is ARM64,
so BDS cannot run natively. **Box64** translates the x86_64 binary at runtime so it
runs on ARM. A Pi 5 8GB has the headroom for a small server (target: up to ~5–10
players). This gives true vanilla Bedrock behavior — the closest to what the kids
expect on console. (Rejected: PocketMine-MP — native but non-vanilla world gen;
PaperMC + Geyser — solid but a Java world with parity quirks and an extra moving part.)

### Why Bedrock at all
Players are on Nintendo Switch, which runs Bedrock Edition only. Switch cannot join
arbitrary servers — it only lists Nintendo "Featured Servers." The standard
workaround is **BedrockConnect**: a small DNS + menu service. Point the Switch's DNS
at the Pi, open any featured server, and a menu appears where you enter the Pi's
address. Nintendo updates can occasionally break this; the dashboard surfaces the
helper's live status so the parent knows.

### Interface: web dashboard, not a terminal menu
A 7-year-old taps big on-screen buttons. The page works on a tablet or PC browser.
The parent uses the same page. No typing required for day-to-day use.

---

## Architecture

One Python process (FastAPI) on the Pi:
- Serves the static dashboard (`web/`), no JavaScript build step (no npm).
- Exposes a small REST API for button actions.
- Pushes live updates (status, player list, log lines) to open pages over a WebSocket.
- Supervises two child processes: the Bedrock server (under Box64) and BedrockConnect.

Runs as a systemd service so it auto-starts on boot and restarts on crash.

```
Browser (tablet/PC)
   │  HTTP + WebSocket
   ▼
FastAPI app (main)  ──► server_supervisor ──► Box64 ──► Bedrock Dedicated Server
   │                       │  stdin commands / stdout capture
   │                       ▼
   │                    log_parser (player join/leave, ready state)
   ├─► backups  ──► worlds/ copy (save hold/query/resume)
   ├─► allowlist ──► allowlist.json + "allowlist reload"
   ├─► auth (parent PIN, hashed)
   └─► bedrockconnect ──► BedrockConnect process (DNS helper)
```

---

## Components (small, single-purpose modules)

Code lives in `block_boss/` under the workspace; deployed to the Pi.

| Module | Purpose | Depends on |
|---|---|---|
| `app/main.py` | FastAPI app: routes, WebSocket, serve `web/` | all modules below |
| `app/config.py` | Paths, ports, settings; reads a config file | — |
| `app/server_supervisor.py` | Start/stop/restart BDS under Box64; send stdin commands; capture stdout | config, log_parser |
| `app/log_parser.py` | Parse BDS log lines → player join/leave, "Server started" ready signal | — |
| `app/backups.py` | Safe world backup (save hold → save query ready → copy `worlds/` → save resume); list/restore; nightly scheduler | config, server_supervisor |
| `app/allowlist.py` | Read/write `allowlist.json`; trigger `allowlist reload` | config, server_supervisor |
| `app/auth.py` | Parent PIN (hashed at rest); gate risky endpoints | config |
| `app/bedrockconnect.py` | Supervise BedrockConnect process; report status + Pi DNS IP | config |
| `app/logging_setup.py` | Diagnostic logger: STARTUP / STATE / DECISION / PERF / CRASH lines to `logs/` | — |
| `web/index.html`, `web/style.css`, `web/app.js` | Dashboard UI | — |
| `deploy/install.sh` | Install Box64, BDS, Java, BedrockConnect, Python deps (uv); configure | — |
| `deploy/block-boss.service` | systemd unit | — |

### Default external choices (confirm current install steps at implementation time)
- **Box64** — installed from its standard ARM64 build for Raspberry Pi OS.
- **BedrockConnect** — Pugmatt's BedrockConnect (most maintained); requires a Java
  runtime on the Pi. Binds DNS on port 53 (needs a capability grant, set by `install.sh`).
- **Bedrock Dedicated Server** — downloaded from Mojang's official URL during install.

---

## Feature behavior

### Start / Stop / Restart (core)
- Big status light: grey=stopped, yellow=starting, green=running, red=crashed.
- **Start** (no PIN): launches Box64 + BDS; light yellow→green when log_parser sees the
  ready line.
- **Stop / Restart** (PIN): sends `stop` to BDS stdin, waits for clean exit.

### Player count + who's online (no PIN)
- log_parser watches for connect/disconnect lines → in-memory player list → pushed live
  to the page over WebSocket.

### One-click world backup (no PIN)
- `save hold` → poll `save query` until the world is ready to copy → copy `worlds/` to
  `backups/world-YYYYMMDD-HHMMSS/` → `save resume` (always, in a `finally` block).
- Optional nightly auto-backup (scheduler thread), retention: keep last N (default 7).
- Page shows "last backup" time. Restore picks a backup folder (PIN-gated).

### Approved-players list / whitelist (edit = PIN)
- Requires `allow-list=true` in `server.properties`.
- Editor adds/removes gamertags in `allowlist.json`; sends `allowlist reload`.
- Viewing the list: no PIN. Editing: PIN.

### Parent PIN
- 4-digit PIN, hashed at rest (e.g., a salted hash), never stored in plain text.
- Open to kid: Start, view players, run Backup.
- PIN required: Stop, Restart, edit allowlist, restore backup.
- Wrong PIN: rejected + logged, no lockout brick.

### Switch setup panel
- Shows the Pi's LAN IP to enter as the Switch's primary DNS, with step text.
- Green/red light for "BedrockConnect running." If it stops responding, the panel says
  the trick may be temporarily broken (e.g., after a Nintendo update).

---

## Error handling

- BDS subprocess exits unexpectedly → supervisor catches it, logs CRASH, status=red,
  honors an auto-restart toggle (default on, with a backoff cap to avoid crash loops).
- Box64/BDS not installed → dashboard banner "Server not installed — run install.sh,"
  no stack trace to the user.
- Backup copy failure → `save resume` still runs (finally); error toast on the page;
  failure logged with detail.
- BedrockConnect can't bind port 53 → dashboard shows the exact fix (capability grant).
- All errors: friendly message on the page, full detail in `logs/`.

---

## Testing strategy (TDD)

**Unit (pytest, test-first):**
- `log_parser`: feed recorded BDS log lines → assert player connect/disconnect events
  and ready detection.
- `backups`: mock filesystem + a fake `save query` response → assert freeze/copy/resume
  ordering and that resume runs even when copy raises.
- `allowlist`: round-trip read/write of `allowlist.json`; assert reload command issued.
- `auth`: PIN hashing/verification; risky endpoints reject without a valid PIN.

**Integration:**
- `server_supervisor` driven against a fake "server" script that emits BDS-like stdout
  and accepts stdin → assert start/stop/restart and command delivery without real BDS.

**Manual (on the Pi):**
- Full install via `install.sh`.
- Connect from a phone (Bedrock) first — simplest path to prove the server works.
- Then connect a Switch via BedrockConnect DNS.
- Exercise each button incl. PIN gating and a backup/restore cycle.

---

## Out of scope (v1, YAGNI)

- Mods/plugins, multiple worlds, multiple simultaneous servers.
- Remote access from outside the home network (no port forwarding / tunneling).
- Accounts beyond the single parent PIN.
- Mobile app (the web page is mobile-friendly already).

---

## Notes on workspace conventions

- This is a **Linux (Pi) web service**, not a Windows GUI — the exe-packaging rule does
  not apply; there is no `.exe`. Deployment artifact is the systemd service.
- Python 3.11; dependencies installed with `uv`, pinned in `requirements.txt`.
- Docs quartet (BREAKDOWN / HANDOFF / TUTORIAL / PROOF) to be produced per the
  project-docs rule once the build lands.
