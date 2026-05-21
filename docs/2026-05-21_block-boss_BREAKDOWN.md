# Block Boss — BREAKDOWN

**Date:** 2026-05-21
**Project:** Block Boss (Minecraft Bedrock server manager for Raspberry Pi 5)
**Status:** All code and tests complete on development machine. On-Pi hardware steps remain.

---

## What it is, in one paragraph

Block Boss is a web dashboard for a Raspberry Pi 5. It lets a parent set up a Minecraft Bedrock server at home and gives a child a simple, safe interface to start the game and see who's playing — while hiding the dangerous controls (stop, wipe, restore) behind a 4-digit parent PIN. Players join from Nintendo Switch. Two technical hurdles make this interesting: the official Minecraft Bedrock server is compiled only for x86 chips (like a desktop PC), but the Pi uses an ARM chip, so an x86-to-ARM translator called Box64 bridges the gap. And the Switch won't let you type in a custom server address — it only knows how to find "Featured Servers" from Mojang's list — so a tool called BedrockConnect tricks it by acting as a fake DNS server (a phone book for the internet) that redirects the Switch's lookup to the Pi.

---

## Module Map

All Python lives under `app/`. Each file has one job.

| File | What it does |
|---|---|
| `config.py` | Loads all settings (paths, port number, PIN file location, how many backups to keep) from environment variables. Everything is immutable (frozen dataclass — an object whose fields can never change after creation). |
| `logging_setup.py` | Configures Python's built-in logging system once at startup. Writes log lines to `~/block_boss/logs/`. |
| `log_parser.py` | Reads lines coming out of the Bedrock server's stdout (standard output — the text stream a program prints to) and extracts useful events: who joined, who left, whether the world save finished. |
| `auth.py` | Hashes (scrambles one-way) and verifies the parent PIN. The real digits are never stored — only a PBKDF2 fingerprint (a one-way scramble made with Python's built-in hashlib). |
| `allowlist.py` | Reads and writes `allowlist.json`, the file the Bedrock server uses to decide which player names may connect. Can also send a live "reloadallowlist" command to a running server so changes take effect instantly. |
| `backups.py` | Makes a timestamped copy of the `worlds/` folder (the saved game data), prunes old backups down to the configured keep-count, and restores a backup by swapping the folders. |
| `server_supervisor.py` | Launches and babysits the Bedrock server process (via Box64). Tracks the server's state (stopped / starting / running / stopping), collects the online player list from log lines, and lets callers send console commands to the server's stdin (standard input — the text stream you type commands into). |
| `bedrockconnect.py` | Launches and babysits the BedrockConnect Java process. Exposes a simple `status()` call so the API can report whether it's up. |
| `main.py` | Builds the FastAPI (a Python web framework) application. Wires every HTTP endpoint (`/api/start`, `/api/stop`, etc.) and the WebSocket (a live two-way connection — like a phone call instead of a letter) that pushes server status to the browser every two seconds. |
| `run.py` | Entry point. Reads config, starts the supervisor and BedrockConnect, then hands the assembled app to the web server (uvicorn). |

### Frontend (`web/`)

| File | What it does |
|---|---|
| `index.html` | The single HTML page. Loads CSS and JS. Contains the button layout. |
| `style.css` | Big, friendly, kid-readable styling. Large touch targets. |
| `app.js` | All browser-side logic. Calls the REST API (HTTP request-response) and listens to the WebSocket for live updates. Builds every piece of visible HTML using `createElement` and `textContent` — never `innerHTML` — to prevent XSS (cross-site scripting — a web attack where malicious text is executed as code). |

### Deploy (`deploy/`)

| File | What it does |
|---|---|
| `install.sh` | One-shot Bash installer. Installs Box64, downloads the Bedrock server and BedrockConnect, sets up the Python virtual environment. |
| `block-boss.service` | A systemd service file. Systemd is the Linux process manager that starts services when the Pi boots. This file describes how to start Block Boss and restart it if it crashes. |

---

## Data Flow: "Kid presses Start"

1. Browser button click triggers `fetch("/api/start", { method: "POST" })` in `app.js`.
2. FastAPI routes the request to the `start()` function in `main.py`.
3. `main.py` calls `server.start()` on the `ServerSupervisor` object.
4. `ServerSupervisor` opens a child process: `box64 bedrock_server` (Box64 translates x86 Bedrock instructions to ARM on the fly).
5. A background thread in `ServerSupervisor` reads every line the Bedrock server prints and hands it to `log_parser.py`, which updates the player list and the `save_ready` flag.
6. `main.py` returns `{"ok": true, "status": "starting"}` to the browser.
7. Meanwhile the WebSocket loop in `main.py` fires every two seconds, pushing `{"status": "...", "players": [...]}` to all connected browsers.
8. `app.js` receives the WebSocket update and re-renders the status badge and player list.

### PIN-protected flow (e.g. Stop)

Same as above, except `app.js` pops up a PIN input dialog first. The PIN is sent as a JSON body field. `main.py` calls `auth.verify_pin()` before proceeding. A wrong PIN returns HTTP 403 (Forbidden) and nothing happens.

---

## Why Box64?

The Minecraft Bedrock Dedicated Server is compiled as an x86 binary — it speaks the instruction language of Intel/AMD desktop chips. The Raspberry Pi 5 uses an ARM chip, which speaks a different language. Box64 is a compatibility layer that reads x86 instructions and translates them to ARM in real time. It is not perfect (some games crash), but Bedrock runs well enough on Pi 5 with 8 GB of RAM.

## Why BedrockConnect?

Nintendo Switch's Minecraft only shows the official "Featured Servers" list. You cannot type in a custom IP address. BedrockConnect intercepts the DNS query (the Switch's request to look up a server's address) and returns the Pi's IP instead of the real featured server's IP. The Switch then connects to the Pi thinking it's connecting to a real Mojang server. This is a well-known community workaround and does not modify the Switch or the game.

---

## Security design choices

- Parent PIN stored as a salted PBKDF2-SHA256 hash (200,000 rounds) — the original digits are unrecoverable.
- The allowlist file is only writable through the API (PIN-gated).
- Backup restore stops the server first so no world data is partially written.
- Path traversal (an attack where a crafted filename like `../../etc/passwd` escapes its folder) is blocked by checking that the resolved backup path starts with the backups directory.
- All DOM updates in the frontend use `createElement`/`textContent`, never `innerHTML`, so no player name or server message can inject HTML or JavaScript.
