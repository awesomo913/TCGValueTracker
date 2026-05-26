# Block Boss — Kid-Safety Audit

**Date:** 2026-05-23
**Scope:** Parent PIN gate, web dashboard input validation, Box64/BedrockConnect lifecycle, log hygiene, and the command/path surface a kid or non-admin on the home LAN can reach.
**Tests:** Full suite green (exit 0) including the new throttle tests.

---

## P0

None.

**Checked and cleared:** `/api/start` is open (no PIN) — this is **intended and tested** (`tests/test_main.py::test_start_is_open_no_pin`, README: *"lets your kid start or stop… while keeping the scary stuff behind a PIN"*). The kid presses Start; only stop/restart/manage-players/restore are gated. Not a finding.

---

## P1 (fixed in this pass)

### 1. No brute-force protection on the parent PIN
- **File:** `app/auth.py` (`_ITERATIONS = 200_000`) + `app/main.py:_require_pin` (pre-fix: verify-only, no lockout).
- **Observed:** A 4-digit PIN is 10,000 combinations. PBKDF2 at 200k iterations costs ~tens of ms per guess on a Pi 5, so the entire keyspace is walkable in roughly **8 minutes** of sustained requests against any PIN-gated endpoint (`/api/stop`, `/api/restart`, etc.). Nothing rate-limited or locked out repeated wrong PINs — a kid (or a script) on the home network could grind it.
- **Fix applied:** New `app/throttle.py` (`PinThrottle`) — after 10 failures inside a 60s window it locks PIN checks for 30s; a correct PIN resets the counter. Wired into `_require_pin`, which now returns **429** while locked. Per-app instance (each uvicorn process gets its own). This turns an ~8-minute brute force into ~hours while never tripping on a parent's occasional typo. Policy constants are tunable at the top of `throttle.py`. Covered by `tests/test_throttle.py` (fake clock, no real sleeping).
- **Tradeoff (documented):** the throttle is global per process, so a wrong-guessing kid can briefly (≤30s) lock the parent out of the gated buttons. The running server is unaffected (Start is open), and the cooldown is short. If you'd rather isolate per-device, key the throttle on `request.client.host` — noted as a future option.

---

## P2

### 1. Read-only endpoints are unauthenticated — LAN info disclosure
- **File:** `app/main.py` — `/api/status` (`:22`), `/api/players` (`:44`), `/api/allowlist` (`:75`), `/api/backups` (`:59`), `/api/switch` (`:91`), `/ws` (`:102`).
- **Observed:** Anyone who can reach the dashboard URL on the home network can read player gamertags, backup folder names, and switch/server status without the PIN. Low severity for kid-safety (no control, just disclosure), but gamertags are mild PII.
- **Proposed fix:** If the LAN isn't fully trusted, gate the listing endpoints behind the PIN too, or bind the server to a single trusted interface. Otherwise accept as a home-LAN tool and note it in the README.

### 2. Allowlist player name is unvalidated
- **File:** `app/allowlist.py:add_player` (`:23`).
- **Observed:** `name` is only `.strip()`ed and checked non-empty before being written to `allowlist.json`. No length cap or charset restriction. **Not** a command-injection risk — `send_command("allowlist reload")` is a fixed string and the name never reaches the BDS console — but an over-long or odd name lands in the JSON the server reads.
- **Proposed fix:** Validate against the Bedrock gamertag charset (letters, digits, spaces, a few symbols) and cap length (~16). PIN-gated, so low urgency.

### 3. `set_pin` accepts trivial PINs
- **File:** `app/auth.py:set_pin` (`:12`).
- **Observed:** Only checks `isdigit()` and `len == 4`. `0000`, `1234`, `1111` are all accepted — and those are exactly what a kid guesses first.
- **Proposed fix:** Reject an obvious denylist (`0000`, `1234`, `1111`, sequential/repeated). Cheap, meaningful for this threat model.

---

## Notes (verified correct — recorded so they aren't re-flagged)

- **PIN storage is done right.** PBKDF2-HMAC-SHA256, 200k iterations, 16-byte random salt, `hmac.compare_digest` for constant-time compare (`app/auth.py`). The README's "secure hash, real digits never saved" claim is accurate.
- **Restore is path-traversal-safe.** `/api/restore` resolves the target and verifies `backups_root in src.parents` before touching it (`main.py:66-69`), and it's tested with `../../etc` (`test_main.py:63`).
- **No shell execution anywhere.** Both `subprocess.Popen` sites (`bedrockconnect.py:26`, `server_supervisor.py:48`) use list args with no `shell=True`. The BDS command channel only ever receives fixed strings.
- **PIN is never logged.** No PIN value reaches `logging_setup.py` / `log_parser.py` or any print. `log_parser` only parses BDS server output.
- **WebSocket handles client disappearance** (`main.py:111-115`) without leaking a pushing loop.
