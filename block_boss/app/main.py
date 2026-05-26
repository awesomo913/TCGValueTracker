from __future__ import annotations
from pathlib import Path
from typing import Optional
from fastapi import FastAPI, HTTPException, Body, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from . import auth, allowlist, backups
from .config import Config
from .server_supervisor import Status
from .throttle import PinThrottle

WEB_DIR = Path(__file__).resolve().parent.parent / "web"


def _require_pin(cfg: Config, pin: Optional[str], throttle: PinThrottle) -> None:
    if throttle.locked():
        raise HTTPException(
            status_code=429,
            detail=f"Too many wrong PINs. Try again in "
                   f"{int(throttle.seconds_remaining()) + 1}s.",
        )
    if not auth.verify_pin(pin or "", cfg.pin_file):
        throttle.record_failure()
        raise HTTPException(status_code=403, detail="Bad or missing parent PIN")
    throttle.record_success()


def create_app(cfg: Config, server, bc) -> FastAPI:
    app = FastAPI(title="Block Boss")
    # Per-app so each uvicorn process (and each test) gets its own counter.
    pin_throttle = PinThrottle()

    @app.get("/api/status")
    def status():
        return {"status": server.status.value, "players": server.players,
                "pin_set": auth.pin_is_set(cfg.pin_file)}

    @app.post("/api/start")
    def start():
        server.start()
        return {"ok": True, "status": server.status.value}

    @app.post("/api/stop")
    def stop(pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin, pin_throttle)
        server.stop()
        return {"ok": True, "status": server.status.value}

    @app.post("/api/restart")
    def restart(pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin, pin_throttle)
        server.restart()
        return {"ok": True, "status": server.status.value}

    @app.get("/api/players")
    def players():
        return {"players": server.players}

    @app.post("/api/backup")
    def backup():
        result = backups.make_backup(
            cfg.worlds_dir, cfg.backups_dir,
            send_command=server.send_command,
            is_save_ready=lambda: server.save_ready,
            server_running=server.status == Status.RUNNING,
        )
        backups.prune_backups(cfg.backups_dir, cfg.backup_keep)
        return {"ok": True, "backup": result.path.name, "when": result.when.isoformat()}

    @app.get("/api/backups")
    def list_backups_route():
        return {"backups": [p.name for p in backups.list_backups(cfg.backups_dir)]}

    @app.post("/api/restore")
    def restore(name: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin, pin_throttle)
        src = (cfg.backups_dir / name).resolve()
        backups_root = cfg.backups_dir.resolve()
        if backups_root not in src.parents or not src.is_dir():
            raise HTTPException(status_code=404, detail="backup not found")
        if server.status == Status.RUNNING:
            server.stop()
        backups.restore_backup(src, cfg.worlds_dir, server_running=False)
        return {"ok": True}

    @app.get("/api/allowlist")
    def get_allowlist():
        return {"players": allowlist.list_players(cfg.allowlist_path)}

    @app.post("/api/allowlist/add")
    def allowlist_add(name: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin, pin_throttle)
        allowlist.add_player(cfg.allowlist_path, name, send_command=server.send_command)
        return {"ok": True, "players": allowlist.list_players(cfg.allowlist_path)}

    @app.post("/api/allowlist/remove")
    def allowlist_remove(name: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        _require_pin(cfg, pin, pin_throttle)
        allowlist.remove_player(cfg.allowlist_path, name, send_command=server.send_command)
        return {"ok": True, "players": allowlist.list_players(cfg.allowlist_path)}

    @app.get("/api/switch")
    def switch():
        return bc.status()

    @app.post("/api/pin")
    def set_pin(new_pin: str = Body(..., embed=True), pin: str = Body(default="", embed=True)):
        if auth.pin_is_set(cfg.pin_file):
            _require_pin(cfg, pin, pin_throttle)
        auth.set_pin(new_pin, cfg.pin_file)
        return {"ok": True}

    @app.websocket("/ws")
    async def ws(websocket: WebSocket):
        import asyncio
        await websocket.accept()
        try:
            while True:
                await websocket.send_json(
                    {"status": server.status.value, "players": server.players})
                await asyncio.sleep(2)
        except WebSocketDisconnect:
            return
        except Exception:
            # client vanished without a close frame (e.g. device slept) -> stop pushing
            return

    if WEB_DIR.exists():
        @app.get("/")
        def index():
            return FileResponse(str(WEB_DIR / "index.html"))
        app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")

    return app
