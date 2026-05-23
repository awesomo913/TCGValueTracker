import socket
import threading
import time

import uvicorn
import webview

from backend import diagnostics
from backend.server import app as fastapi_app


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def _serve(port: int):
    # Pass the app OBJECT, not an import string: uvicorn's string form re-imports
    # by module name, which fails inside a frozen PyInstaller bundle.
    uvicorn.run(fastapi_app, host="127.0.0.1", port=port, log_level="warning")


def main():
    diagnostics.bootstrap("StolenEmeraldPlanner")
    port = _free_port()
    threading.Thread(target=_serve, args=(port,), daemon=True).start()
    for _ in range(50):  # wait for the server to accept connections
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                break
        except OSError:
            time.sleep(0.1)
    else:  # loop exhausted without connecting — server failed to start
        diagnostics.log("CRASH", f"backend server never came up on port {port}")
        raise RuntimeError(f"backend server failed to start on port {port}")
    diagnostics.log("STATE", f"init->ready port={port}")
    webview.create_window(
        "StolenEmerald Planner",
        f"http://127.0.0.1:{port}",
        width=1400,
        height=900,
        min_size=(1100, 700),
    )
    webview.start()
    diagnostics.log("STATE", "ready->shutdown")


if __name__ == "__main__":
    main()
