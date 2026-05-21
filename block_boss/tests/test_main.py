from fastapi.testclient import TestClient
from app import auth
from app.config import Config
from app.main import create_app
from app.server_supervisor import Status

class FakeServer:
    def __init__(self):
        self.status = Status.STOPPED
        self.players = []
        self.save_ready = True
        self.commands = []
    def start(self): self.status = Status.RUNNING
    def stop(self): self.status = Status.STOPPED
    def restart(self): self.status = Status.RUNNING
    def send_command(self, c): self.commands.append(c)

class FakeBC:
    def status(self): return {"running": True, "dns_ip": "192.168.1.50"}

def _client(tmp_path):
    cfg = Config.load(home=tmp_path)
    cfg.allowlist_path.parent.mkdir(parents=True, exist_ok=True)
    return cfg, TestClient(create_app(cfg, FakeServer(), FakeBC()))

def test_status_starts_stopped(tmp_path):
    _, client = _client(tmp_path)
    body = client.get("/api/status").json()
    assert body["status"] == "stopped"
    assert body["pin_set"] is False

def test_start_is_open_no_pin(tmp_path):
    _, client = _client(tmp_path)
    r = client.post("/api/start")
    assert r.status_code == 200
    assert r.json()["status"] == "running"

def test_stop_requires_pin(tmp_path):
    cfg, client = _client(tmp_path)
    assert client.post("/api/stop", json={"pin": ""}).status_code == 403
    auth.set_pin("1234", cfg.pin_file)
    assert client.post("/api/stop", json={"pin": "0000"}).status_code == 403
    assert client.post("/api/stop", json={"pin": "1234"}).status_code == 200

def test_switch_status(tmp_path):
    _, client = _client(tmp_path)
    body = client.get("/api/switch").json()
    assert body["running"] is True
    assert body["dns_ip"] == "192.168.1.50"

def test_allowlist_add_requires_pin_then_lists(tmp_path):
    cfg, client = _client(tmp_path)
    auth.set_pin("1234", cfg.pin_file)
    assert client.post("/api/allowlist/add", json={"name": "Alex", "pin": "x"}).status_code == 403
    r = client.post("/api/allowlist/add", json={"name": "Alex", "pin": "1234"})
    assert r.status_code == 200
    assert client.get("/api/allowlist").json()["players"] == ["Alex"]
