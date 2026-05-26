"""Local Windows launcher — mock Pi-only dependencies so the dashboard UI works."""
from __future__ import annotations
from unittest.mock import MagicMock
from app.config import Config
from app.main import create_app
from app.server_supervisor import Status

cfg = Config.load()
# Mock supervisors so dashboard UI works without box64/Java on Windows
server = MagicMock()
server.status = Status.STOPPED
server.players = []
server.save_ready = False
server.start = MagicMock()
server.stop = MagicMock()
server.restart = MagicMock()
server.send_command = MagicMock()

bc = MagicMock()
bc.running = False
bc.status.return_value = {"running": False, "dns_ip": "127.0.0.1"}

app = create_app(cfg, server, bc)

if __name__ == "__main__":
    import uvicorn
    import os
    port = int(os.environ.get("BLOCK_BOSS_PORT", "8001"))
    uvicorn.run(app, host="0.0.0.0", port=port)
