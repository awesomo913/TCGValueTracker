from app.throttle import PinThrottle


class FakeClock:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t

    def advance(self, secs: float) -> None:
        self.t += secs


def _throttle(clock: FakeClock) -> PinThrottle:
    return PinThrottle(max_attempts=3, window_s=60.0, lockout_s=30.0, _clock=clock)


def test_not_locked_initially():
    t = _throttle(FakeClock())
    assert not t.locked()


def test_locks_after_max_attempts():
    clock = FakeClock()
    t = _throttle(clock)
    t.record_failure()
    t.record_failure()
    assert not t.locked()
    t.record_failure()  # 3rd failure trips the lockout
    assert t.locked()


def test_lockout_expires():
    clock = FakeClock()
    t = _throttle(clock)
    for _ in range(3):
        t.record_failure()
    assert t.locked()
    clock.advance(31.0)
    assert not t.locked()


def test_success_resets_counter():
    clock = FakeClock()
    t = _throttle(clock)
    t.record_failure()
    t.record_failure()
    t.record_success()
    t.record_failure()  # counter was reset, so this is failure #1, not #3
    assert not t.locked()


def test_old_failures_fall_out_of_window():
    clock = FakeClock()
    t = _throttle(clock)
    t.record_failure()
    t.record_failure()
    clock.advance(61.0)  # first two failures age out of the 60s window
    t.record_failure()
    assert not t.locked()
