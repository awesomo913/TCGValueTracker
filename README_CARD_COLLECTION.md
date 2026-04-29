## Card Collection (Anime UI + FastAPI)

Monorepo layout on this machine (peer folders under `Desktop\AI`):

| Path | Contents |
|------|----------|
| `card_collection_backend/` | FastAPI, SQLAlchemy, Alembic, price scheduler |
| `card_collection_frontend/` | React (Create React App or similar layout) |

### Backend

```bash
cd card_collection_backend
python -m venv .venv
.venv\Scripts\activate          # Windows
pip install -r requirements.txt
uvicorn main:app --reload       # http://localhost:8000
python -m pytest test_api.py -q
```

**Environment (optional)**

| Variable | Purpose |
|-----------|---------|
| `DATABASE_URL` | Postgres/other SQLAlchemy URL; omit for SQLite `./card_collection.db` |
| `PRICE_SOURCES_ENABLED` | Comma list, default `TCGPlayer,eBay,CardMarket` |
| `TCGPLAYER_API_KEY` | Live TCGPlayer (placeholder URL in code — replace with real integration) |
| `EBAY_APP_ID` / `EBAY_OAUTH_TOKEN` | eBay Browse API |
| `CARDMARKET_API_KEY` | CardMarket |
| `PRICE_UPDATE_INTERVAL_HOURS` | Scheduler interval (default 24) |

**Migrations (Alembic)**

```bash
cd card_collection_backend
alembic upgrade head
```

If the DB was created with `create_all` on startup, Alembic “initial” migration may be a no-op or align schema.

### Frontend

```bash
cd card_collection_frontend
npm install
npm start
```

Build:

```bash
npm run build
```

API base URL is **http://localhost:8000** (see `src/services/api.js`).

### Publish to GitHub

This `Desktop\AI` checkout is a **partial git repo**: only the card collection trees, `.github/workflows/card-collection-ci.yml`, `README_CARD_COLLECTION.md`, and `CARD_COLLECTION_CLAUDIFY_HANDOFF.md` are in the first commit.

```bash
cd Desktop/AI
gh auth login               # or complete device flow: gh auth refresh
gh repo create card-collection-anime-stack --public --source=. --remote=origin --push
```

Pick another repo name if taken. Remove stale `origin` first if needed: `git remote remove origin`.

### Licence

Private / your project unless you attach a OSS licence separately.
