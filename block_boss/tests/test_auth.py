import pytest
from app import auth

def test_set_and_verify_pin(tmp_path):
    pin_file = tmp_path / "pin.hash"
    assert auth.pin_is_set(pin_file) is False
    auth.set_pin("1234", pin_file)
    assert auth.pin_is_set(pin_file) is True
    assert auth.verify_pin("1234", pin_file) is True
    assert auth.verify_pin("0000", pin_file) is False

def test_pin_must_be_four_digits(tmp_path):
    pin_file = tmp_path / "pin.hash"
    with pytest.raises(ValueError):
        auth.set_pin("12", pin_file)
    with pytest.raises(ValueError):
        auth.set_pin("abcd", pin_file)

def test_verify_missing_file_is_false(tmp_path):
    assert auth.verify_pin("1234", tmp_path / "nope.hash") is False

def test_stored_pin_is_not_plaintext(tmp_path):
    pin_file = tmp_path / "pin.hash"
    auth.set_pin("4321", pin_file)
    assert "4321" not in pin_file.read_text(encoding="utf-8")
