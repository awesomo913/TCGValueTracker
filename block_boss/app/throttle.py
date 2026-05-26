"""In-process brute-force throttle for the parent PIN.

A 4-digit PIN is only 10,000 combinations. PBKDF2 (200k iterations) slows each
guess to tens of ms, but with no lockout a script on the home LAN can still walk
the whole keyspace in minutes. This adds a simple failure counter + cooldown.

Single-process by design — Block Boss runs as one uvicorn process on the Pi, so
a module-level counter is sufficient. It is *global* (not per-IP): one family,
one PIN. Worst case a wrong-guessing kid briefly locks the parent out of the
PIN-gated buttons (stop/restart), but the cooldown is short and the running
server is unaffected (Start is open by design).

Policy (tunable): after MAX_ATTEMPTS failures inside WINDOW_S, lock for
LOCKOUT_S. A correct PIN resets the counter. A parent's occasional typo never
trips it; a 10k-guess brute force becomes ~hours instead of ~minutes.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field

MAX_ATTEMPTS = 10
WINDOW_S = 60.0
LOCKOUT_S = 30.0


@dataclass
class PinThrottle:
    max_attempts: int = MAX_ATTEMPTS
    window_s: float = WINDOW_S
    lockout_s: float = LOCKOUT_S
    _fails: list[float] = field(default_factory=list)
    _locked_until: float = 0.0
    _clock: "callable" = time.monotonic

    def locked(self) -> bool:
        """True while a cooldown is active."""
        return self._clock() < self._locked_until

    def seconds_remaining(self) -> float:
        return max(0.0, self._locked_until - self._clock())

    def record_failure(self) -> None:
        """Register one bad PIN attempt; arm the lockout if over threshold."""
        now = self._clock()
        # Drop failures older than the rolling window.
        self._fails = [t for t in self._fails if now - t < self.window_s]
        self._fails.append(now)
        if len(self._fails) >= self.max_attempts:
            self._locked_until = now + self.lockout_s
            self._fails.clear()

    def record_success(self) -> None:
        """A correct PIN clears the counter and any pending lockout."""
        self._fails.clear()
        self._locked_until = 0.0
