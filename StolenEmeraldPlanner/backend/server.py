import io
import threading
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles
from PIL import Image

from backend import cache, config, diagnostics
from backend.engine import (
    atlas,
    history,
    mapdetail,
    maprender,
    maps,
    roadmap,
    scripts,
    species,
    timeline,
    trainers,
)

app = FastAPI(title="StolenEmerald Planner")
FRONTEND = Path(__file__).resolve().parent.parent / "frontend"
_atlas_lock = threading.Lock()  # serialize cache check+rebuild to avoid a TOCTOU race


@app.get("/api/health")
def health():
    return {"ok": True, "repo": str(config.repo_path()), "repo_found": config.repo_exists()}


@app.get("/api/status")
def status():
    """Cheap poll target: lets the UI notice repo changes and auto-refresh.
    `signature` changes whenever any tracked repo file's mtime/size changes."""
    found = config.repo_exists()
    sig = cache.repo_signature() if found else ""
    stale = found and (cache.is_stale("atlas", sig) or cache.is_stale("timeline_v2", sig))
    return {"repo_found": found, "signature": sig, "stale": stale}


@app.get("/api/roadmap")
def get_roadmap():
    return roadmap.parse(FRONTEND / "roadmap.md")


@app.get("/api/atlas")
def get_atlas():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="StolenEmerald repo not found")
    sig = cache.repo_signature()
    with _atlas_lock:  # one request rebuilds; others wait and read the fresh value
        if cache.is_stale("atlas", sig):
            diagnostics.log("DECISION", "atlas cache miss -> rebuild")
            payload = atlas.build(config.repo_path())
            cache.set_payload("atlas", payload, sig)
        else:
            payload = cache.get_payload("atlas")
    if payload is None:
        # cache row missing/corrupt after a hit — self-heal rather than return null
        diagnostics.log("CRASH", "atlas cache returned None; forcing rebuild")
        with _atlas_lock:
            payload = atlas.build(config.repo_path())
            cache.set_payload("atlas", payload, sig)
    return payload


@app.post("/api/rescan")
def rescan():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    repo = config.repo_path()
    sig = cache.repo_signature()
    cache.set_payload("atlas", atlas.build(repo), sig)
    cache.set_payload("timeline_v2", timeline.build(repo), sig)  # keep timeline current too
    diagnostics.log("STATE", "manual rescan complete (atlas + timeline)")
    return {"ok": True}


@app.get("/api/maps")
def get_maps():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    repo = config.repo_path()
    return {"maps": maps.list_maps(repo), "parse_errors": maps.parse_errors(repo)}


def _first_frame_png(path: Path) -> bytes:
    """anim_front.png is a vertical/horizontal sprite sheet of square frames.
    Crop the first frame (side = min dimension) so the portrait isn't squished.
    Pure in-memory read of the repo file — never writes back."""
    with Image.open(path) as im:
        im = im.convert("RGBA")
        side = min(im.width, im.height)
        frame = im.crop((0, 0, side, side))
        buf = io.BytesIO()
        frame.save(buf, format="PNG")
        return buf.getvalue()


@app.get("/api/map/{folder}")
def get_map_detail(folder: str):
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    detail = mapdetail.build(config.repo_path(), folder)
    if detail is None:
        raise HTTPException(status_code=404, detail="map not found")
    return detail


@app.get("/api/map_render/{folder}.png")
def get_map_render(folder: str):
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    repo = config.repo_path()
    m = maps.get_map(repo, folder)
    layout = maps.layout_index(repo).get(m.get("layout", "")) if m else None
    if layout is None:
        raise HTTPException(status_code=404, detail="layout not found")

    cache_dir = config.app_data_dir() / "renders"
    cache_dir.mkdir(parents=True, exist_ok=True)
    out = cache_dir / f"{folder}.png"
    block = repo / layout["blockdata_filepath"]
    try:
        fresh = out.is_file() and block.is_file() and out.stat().st_mtime >= block.stat().st_mtime
    except OSError:
        fresh = False
    if not fresh:
        try:
            img = maprender.render_layout(repo, layout)
            img.save(out)
        except (OSError, ValueError, KeyError) as e:
            diagnostics.log("CRASH", f"map_render failed folder={folder} err={e}")
            raise HTTPException(status_code=500, detail=f"render failed: {e}")
    return FileResponse(out)


@app.get("/api/sprite/{species_const}")
def get_sprite(species_const: str, kind: str = "icon"):
    d = species.sprite_dir(config.repo_path(), species_const)
    fname = {"icon": "icon.png", "front": "anim_front.png"}.get(kind, "icon.png")
    if d is None or not (d / fname).is_file():
        diagnostics.log("DECISION", f"missing_sprite={species_const} kind={kind}")
        raise HTTPException(status_code=404, detail="sprite not found")
    if kind == "front":
        try:
            return Response(content=_first_frame_png(d / fname), media_type="image/png")
        except OSError as e:
            diagnostics.log("DECISION", f"front_crop_failed={species_const} err={e}; serving raw")
            return FileResponse(d / fname)  # fall back to the raw sheet, still visible
    return FileResponse(d / fname)


@app.get("/api/scripts/{folder}")
def get_scripts(folder: str):
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    return {"labels": scripts.load_map_scripts(config.repo_path(), folder)}


@app.get("/api/trainer/{trainer_const}")
def get_trainer(trainer_const: str):
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    text = trainers.get(config.repo_path(), trainer_const)
    if text is None:
        raise HTTPException(status_code=404, detail="trainer not found")
    return {"trainer": trainer_const, "text": text}


@app.get("/api/script_trainer/{folder}/{label}")
def get_script_trainer(folder: str, label: str):
    """The script body for a label, plus its trainer party if it references one."""
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    repo = config.repo_path()
    body = scripts.load_map_scripts(repo, folder).get(label, "")
    found = trainers.find_in_text(repo, body)
    return {
        "label": label,
        "body": body,
        "trainer": found[0] if found else "",
        "party": found[1] if found else "",
    }


@app.get("/api/history")
def get_history():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    return {"docs": history.list_docs(config.repo_path())}


@app.get("/api/history/doc")
def get_history_doc(rel: str):
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    text = history.read_doc(config.repo_path(), rel)
    if text is None:
        raise HTTPException(status_code=404, detail="doc not found")
    return {"rel": rel, "text": text}


@app.get("/api/history/search")
def get_history_search(q: str):
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    return {"hits": history.search(config.repo_path(), q)}


@app.get("/api/timeline")
def get_timeline():
    if not config.repo_exists():
        raise HTTPException(status_code=503, detail="repo not found")
    sig = cache.repo_signature()
    with _atlas_lock:
        if cache.is_stale("timeline_v2", sig):
            diagnostics.log("DECISION", "timeline cache miss -> rebuild")
            payload = timeline.build(config.repo_path())
            cache.set_payload("timeline_v2", payload, sig)
        else:
            payload = cache.get_payload("timeline_v2")
    if payload is None:
        payload = timeline.build(config.repo_path())
        cache.set_payload("timeline_v2", payload, sig)
    return payload


# static frontend mounted last so /api/* wins
if FRONTEND.is_dir():
    app.mount("/", StaticFiles(directory=str(FRONTEND), html=True), name="frontend")
