from app.logging_setup import DiagnosticLogger

def test_writes_state_and_crash_lines(tmp_path):
    log = DiagnosticLogger("blockboss", tmp_path)
    log.startup("1.0.0")
    log.state("init", "ready")
    try:
        raise ValueError("boom")
    except ValueError as e:
        log.crash(e)
    text = log.path.read_text(encoding="utf-8")
    assert "STARTUP blockboss v1.0.0" in text
    assert "STATE init->ready" in text
    assert "CRASH" in text and "boom" in text
