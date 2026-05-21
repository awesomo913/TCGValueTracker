from __future__ import annotations
from .config import Config
from .logging_setup import DiagnosticLogger
from .server_supervisor import ServerSupervisor
from .bedrockconnect import BedrockConnectSupervisor
from .main import create_app
from . import __version__

cfg = Config.load()
log = DiagnosticLogger("blockboss", cfg.logs_dir)
log.startup(__version__)

server = ServerSupervisor(
    [cfg.box64_bin, str(cfg.bds_executable)],
    cwd=cfg.bds_dir,
    on_line=lambda line: log.decision(f"bds: {line}") if "ERROR" in line else None,
)
bc = BedrockConnectSupervisor(
    ["java", "-jar", str(cfg.home / "bedrockconnect" / "BedrockConnect.jar"),
     "nodns=false"],
    cwd=cfg.home / "bedrockconnect",
)

# Start the Switch DNS helper at boot so consoles can reach the server.
# If Java or the jar is missing, keep the dashboard up; the Switch panel will
# simply show BedrockConnect as offline.
try:
    bc.start()
    log.state("bedrockconnect", "started")
except Exception as exc:  # noqa: BLE001 - dashboard must boot regardless
    log.crash(exc)
    log.decision("bedrockconnect failed to start; dashboard shows it offline")

app = create_app(cfg, server, bc)
