from __future__ import annotations
import shlex
from .config import Config
from .logging_setup import DiagnosticLogger
from .server_supervisor import ServerSupervisor
from .bedrockconnect import BedrockConnectSupervisor
from .main import create_app
from . import __version__

cfg = Config.load()
log = DiagnosticLogger("blockboss", cfg.logs_dir)
log.startup(__version__)

# Bedrock ships its own .so libs and only finds them with LD_LIBRARY_PATH=.
# Wrap in bash so the env var + working dir are set before box64 execs the binary.
_bds_launch = (
    f"cd {shlex.quote(str(cfg.bds_dir))} && "
    f"LD_LIBRARY_PATH=. exec {shlex.quote(cfg.box64_bin)} ./bedrock_server"
)
server = ServerSupervisor(
    ["bash", "-lc", _bds_launch],
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
