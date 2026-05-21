from __future__ import annotations
import re
from dataclasses import dataclass
from typing import Optional

CONNECT_RE = re.compile(r"Player connected:\s*(?P<name>.+?),\s*xuid:\s*(?P<xuid>\d+)")
DISCONNECT_RE = re.compile(r"Player disconnected:\s*(?P<name>.+?),\s*xuid:\s*(?P<xuid>\d+)")
READY_RE = re.compile(r"Server started\.")

@dataclass(frozen=True)
class LogEvent:
    kind: str  # "connect" | "disconnect" | "ready"
    player: Optional[str] = None

def parse_line(line: str) -> Optional[LogEvent]:
    m = CONNECT_RE.search(line)
    if m:
        return LogEvent("connect", m.group("name").strip())
    m = DISCONNECT_RE.search(line)
    if m:
        return LogEvent("disconnect", m.group("name").strip())
    if READY_RE.search(line):
        return LogEvent("ready")
    return None

class PlayerTracker:
    def __init__(self) -> None:
        self._players: set[str] = set()
        self.ready = False

    def apply(self, event: Optional[LogEvent]) -> None:
        if event is None:
            return
        if event.kind == "connect" and event.player:
            self._players.add(event.player)
        elif event.kind == "disconnect" and event.player:
            self._players.discard(event.player)
        elif event.kind == "ready":
            self.ready = True

    @property
    def players(self) -> list[str]:
        return sorted(self._players)
