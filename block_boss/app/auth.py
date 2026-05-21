from __future__ import annotations
import hashlib
import hmac
import os
from pathlib import Path

_ITERATIONS = 200_000

def _hash(pin: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, _ITERATIONS)

def set_pin(pin: str, pin_file: Path) -> None:
    if not (pin.isdigit() and len(pin) == 4):
        raise ValueError("PIN must be exactly 4 digits")
    salt = os.urandom(16)
    digest = _hash(pin, salt)
    pin_file = Path(pin_file)
    pin_file.parent.mkdir(parents=True, exist_ok=True)
    pin_file.write_text(f"{salt.hex()}${digest.hex()}", encoding="utf-8")

def verify_pin(pin: str, pin_file: Path) -> bool:
    pin_file = Path(pin_file)
    if not pin_file.exists():
        return False
    raw = pin_file.read_text(encoding="utf-8").strip()
    try:
        salt_hex, digest_hex = raw.split("$", 1)
    except ValueError:
        return False
    expected = bytes.fromhex(digest_hex)
    actual = _hash(pin, bytes.fromhex(salt_hex))
    return hmac.compare_digest(expected, actual)

def pin_is_set(pin_file: Path) -> bool:
    return Path(pin_file).exists()
