import sys

print("[INFO] Server started.", flush=True)
for line in sys.stdin:
    cmd = line.strip()
    if cmd == "spawn":
        print("[INFO] Player connected: Steve, xuid: 100", flush=True)
    elif cmd == "despawn":
        print("[INFO] Player disconnected: Steve, xuid: 100", flush=True)
    elif cmd == "save hold":
        print("[INFO] Saving...", flush=True)
    elif cmd == "save query":
        print("[INFO] Data saved. Files are now ready to be copied.", flush=True)
    elif cmd == "stop":
        break
sys.exit(0)
