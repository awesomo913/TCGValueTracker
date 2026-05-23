from fastapi.testclient import TestClient

from backend.server import app

client = TestClient(app)


def test_health():
    assert client.get("/api/health").json()["ok"] is True


def test_atlas_endpoint_has_route101():
    r = client.get("/api/atlas")
    if r.status_code == 503:
        return  # repo absent on this machine
    assert "MAP_ROUTE101" in r.json()["maps"]
