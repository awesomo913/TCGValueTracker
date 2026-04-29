# Claudify — Card Collection (verified from disk 2026-04-29)

Single handoff block for Claude (paste everything inside the dashed box into Claude Desktop/Web/Mobile).

---

**Title:** Continue Card Collection app — multi-source pricing, snapshots, anime UI, prod DB/tests, GitHub push

**Environment:** Windows paths; backend `Python` + FastAPI + SQLAlchemy; frontend React (`card_collection_frontend`). Root folders on this machine:

- Backend: `C:\Users\computer\Desktop\AI\card_collection_backend\`
- Frontend: `C:\Users\computer\Desktop\AI\card_collection_frontend\`

Parent folder `Desktop\AI` also contains unrelated nested repos (e.g. `AI2`, `AIStudio`); isolate git work for **only** `card_collection_backend` + `card_collection_frontend` + any handoff README you add — or use submodule/subtree sparingly.

**Symptom / goal:** User wants the project finished to “prod-ish” standard: Visual (React/CSS) stays **frontend-only**; API/business logic stays in **FastAPI/backend**. Publish to a **public GitHub repo** with a normal PR workflow. Earlier threads mixed aspirational wording with repo state — **this document lists only behaviors confirmed in source files.**

**Verified backend state (read from repo):**

| Area | Facts |
|------|--------|
| DB | `database.py`: `DATABASE_URL` env → engine; else sqlite `sqlite:///./card_collection.db`. |
| Schema | `models.py`: `Card`, `SealedProduct` include `price_sources` (JSON), `last_price_update`; `PriceHistory` (`item_type`, `item_id`, `source`, `price`, `timestamp`) with index `idx_price_history_item`. |
| Startup | `main.py` line ~10: `models.Base.metadata.create_all(bind=engine)`. Startup event calls `start_scheduler()`. |
| CORS | `localhost:3000` and `127.0.0.1:3000`. |
| HTTP | CRUD `/cards/`, `/sealed/`; `GET /collection/value`; `POST /prices/update`; `GET /snapshot`; `GET /price-history/{item_type}/{item_id}`. |
| Pricing | `price_service.py`: `fetch_card_prices_all_sources` / `fetch_sealed_prices_all_sources` — env `PRICE_SOURCES_ENABLED`, keys `TCGPLAYER_API_KEY`, `EBAY_APP_ID`, `EBAY_OAUTH_TOKEN`, `CARDMARKET_API_KEY`. **Live calls are heuristic/placeholder** (URLs and JSON shapes may not match real vendor APIs). Without keys, **mock multi-source dict** is used. |
| Batch update | `update_all_prices()` loads all cards/sealed, per provider logs `log_price_history`, sets `price_sources`, sets `current_price` to **average of sources**, `last_price_update`. |
| Scheduler | `scheduler.py`: background thread; interval from `PRICE_UPDATE_INTERVAL_HOURS` or default **24h**. First run starts soon after app boot. |
| Snapshot | `crud.price_snapshot`: sums `by_source` from each item’s `price_sources` × quantity; builds `history` from `PriceHistory` last 24h grouped by timestamp+source. **Review whether `total_value` should be sum across all sources or single estimated value** — current code adds `price * qty` per source (can overcount vs one “fair” price). |
| Manual trigger | `POST /prices/update` calls `update_all_prices()` **without DB session injection** — function opens its own `SessionLocal()`. |

**Verified frontend state:**

| Area | Facts |
|------|--------|
| Routes | `App.js`: `/`, `/cards`, `/cards/add`, `/cards/edit/:id`, `/sealed`, `/sealed/add`, `/sealed/edit/:id`, `/snapshot`. Root wrapper class `anime-app`. |
| Nav | Header links **only** Dashboard, My Cards, Sealed — **no nav link to `/snapshot`** (route exists; add link in header if desired). |
| Tiles | `components/TileCard.js`: `anime-tile` layout; shows `current_price` and per-source bars from `price_sources`. |
| Snapshot page | `pages/PriceSnapshotPage.js` exists (verify it calls `getSnapshot` from `services/api.js`). |
| Styling | `App.css` — anime/neon styling (confirm contrast/a11y). |

**Tests (verified file `card_collection_backend/test_api.py`):**

- Uses `from main import app` — run pytest with cwd = backend or `PYTHONPATH` set.
- Covers: `/`, `POST /cards/`, `GET /snapshot`, `GET /price-history/card/1` and `.../sealed/1`, `GET /cards/`, snapshot `history` key, `POST /prices/update`.

**Repro (local):**

1. Backend: `cd card_collection_backend` → venv → `pip install -r requirements.txt` → `uvicorn main:app --reload` (port 8000 per `__main__`).
2. Frontend: `cd card_collection_frontend` → `npm install` → `npm start` (port 3000).
3. `pytest` from backend directory.

**Expected vs actual gaps (honest):**

- **Live pricing:** Implementation is **scaffolding + mocks**; production needs vendor-correct auth, rate limits, and response parsing per official docs.
- **Migrations:** Uses `create_all` only — **no Alembic** in verified tree; Postgres prod should add migrations before destructive changes.
- **total_value semantics:** Confirm product intent vs current sum-across-sources behavior.
- **GitHub:** Not pushed from this handoff writer; user asked for **public** repo — initialize/clean `.gitignore`, exclude `venv`, `node_modules`, sqlite DB artifacts, `.env`.

**Constraints:**

- Frontend **must not embed** secrets; backend reads env vars only (`***` placeholders in docs).
- Keep separation: React → HTTP → FastAPI **only** (no leaking Python into JSX).
- Do not commit real API keys.

**Definition of done (suggested checklist):**

- [ ] `pytest -q` green from `card_collection_backend`.
- [ ] `npm test` or `npm run build` succeeds for frontend (add CI).
- [ ] Document required env vars in backend README (`DATABASE_URL`, `PRICE_SOURCES_ENABLED`, provider keys).
- [ ] Decide and fix **collection total_value** semantics if current behavior is wrong.
- [ ] Replace placeholder vendor APIs OR document clearly as “demo mode only”.
- [ ] Alembic (or equivalent) for `PriceHistory` and JSON columns when moving to Postgres.
- [ ] Public GitHub: one repo containing both subprojects (or mono-repo folder layout), Actions CI: pytest + frontend build.
- [ ] Nav UX: optional link to `/snapshot`.

**Optional: files to read first inside repo**

`main.py`, `crud.py` (`price_snapshot`, `log_price_history`), `price_service.py`, `models.py`, `database.py`, `scheduler.py`, `test_api.py`, `card_collection_frontend/src/App.js`, `TileCard.js`, `PriceSnapshotPage.js`, `api.js`.

## Fixes applied 2026-04-29 (this session)

- **Frontend `api.js`:** Aligned Axios paths with FastAPI routes (`/snapshot`, `/collection/value`, `/prices/update` had erroneous trailing slashes; resource IDs no longer use a trailing slash). Verified against `main.py`.
- **Backend imports:** Replaced relative package imports (`from . import …`) with **flat sibling imports** so `uvicorn main:app` and `pytest` work with cwd = `card_collection_backend/`. Used deferred `from crud import log_price_history` inside `update_all_prices()` to avoid circular imports.
- **Tests:** Ran `python -m pytest test_api.py -q` — **8 passed** (warnings: SQLAlchemy `declarative_base`, Pydantic v2, FastAPI `on_event` deprecation).

---

**COPY-PASTE BLOCK ENDS** — above dashed section is Claude-ready handoff plus post-fix notes.

