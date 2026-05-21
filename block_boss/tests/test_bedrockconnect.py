import sys
import time
from app.bedrockconnect import BedrockConnectSupervisor, lan_ip

def test_lan_ip_returns_string():
    ip = lan_ip()
    assert isinstance(ip, str)
    assert ip.count(".") == 3

def test_supervisor_lifecycle_and_status(tmp_path):
    cmd = [sys.executable, "-c", "import time; time.sleep(5)"]
    bc = BedrockConnectSupervisor(cmd, cwd=tmp_path)
    assert bc.running is False
    bc.start()
    time.sleep(0.3)
    assert bc.running is True
    status = bc.status()
    assert status["running"] is True
    assert "dns_ip" in status
    bc.stop()
    time.sleep(0.3)
    assert bc.running is False
