# Block Boss

A web dashboard that runs on a Raspberry Pi 5 and lets your kid start or stop a Minecraft Bedrock server with big, friendly buttons — while keeping the scary stuff (stop, restart, manage players, restore) safely behind a 4-digit parent PIN.

---

## What it is

Block Boss is a small web app you host on a Raspberry Pi 5 (8 GB). Open it in any browser on your home network and you get a simple control panel. Your child can hit the green **Start** button, watch who's online, and make a backup. Everything that could mess up the game — stopping the server, restarting it, adding or removing approved players, restoring an old backup — requires the parent PIN first. Players join from Nintendo Switch.

---

## Hardware you need

| Item | Notes |
|---|---|
| Raspberry Pi 5 (8 GB) | 4 GB may work but 8 GB is comfortable |
| Raspberry Pi OS 64-bit (Bookworm) | Must be the **64-bit** version |
| A microSD card or SSD (32 GB+) | The Bedrock server download alone is ~200 MB |
| Wired Ethernet (recommended) | Wi-Fi works but wired is more stable for a game server |

---

## Install

### Step 1 — Find the download URLs

You need two URLs before you run the installer. Look them up fresh because they change with each release:

- **Bedrock Dedicated Server (BDS):** Go to [minecraft.net/download/server/bedrock](https://www.minecraft.net/en-us/download/server/bedrock), choose the **Ubuntu/Debian** Linux build, and copy the `.zip` download link.
- **BedrockConnect:** Go to [github.com/Pugmatt/BedrockConnect/releases](https://github.com/Pugmatt/BedrockConnect/releases), find the latest `.jar` file, and copy its download link.

### Step 2 — Clone the repo on your Pi

```bash
git clone https://github.com/<your-fork>/block_boss ~/AI/block_boss
cd ~/AI/block_boss
```

### Step 3 — Set the two URLs and run the installer

```bash
export BDS_URL="<paste the Bedrock Server zip URL here>"
export BEDROCKCONNECT_URL="<paste the BedrockConnect jar URL here>"
bash deploy/install.sh
```

The installer will:
- Install Box64 (the tool that lets the x86-only Bedrock server run on your Pi's ARM chip)
- Download and unzip the Bedrock Dedicated Server
- Download the BedrockConnect jar
- Set up the Python environment

### Step 4 — Set your parent PIN

Open the dashboard (see Daily Use below), go to **Settings**, and set a 4-digit PIN. You must do this before the PIN-protected buttons will work.

### Step 5 — Install the background service

This makes Block Boss start automatically when the Pi boots:

```bash
sudo cp deploy/block-boss.service /etc/systemd/system/block-boss@.service
sudo systemctl enable --now block-boss@$USER
```

---

## Daily use

On any device on your home network, open a browser and go to:

```
http://<pi-ip>:8000
```

Replace `<pi-ip>` with your Pi's local IP address (e.g. `192.168.1.42`). You can find it by running `hostname -I` on the Pi.

**What your child sees:**
- A big green **Start** button
- A list of who's online right now
- A **Backup** button to save the world

**What needs the parent PIN:**
- Stop server
- Restart server
- Add or remove approved players
- Restore a backup

---

## Nintendo Switch setup (BedrockConnect)

The Switch only connects to "Featured Servers" from Mojang's list — it won't let you type in your own server address directly. BedrockConnect works around this by acting as a fake DNS server (a system that translates server names into IP addresses) so the Switch thinks your Pi *is* one of the official servers.

### One-time setup on each Switch

1. On the Switch, go to **System Settings → Internet → Internet Settings**.
2. Select your Wi-Fi network, then **Change Settings**.
3. Under **DNS Settings**, choose **Manual**.
4. Set **Primary DNS** to your Pi's IP address (e.g. `192.168.1.42`).
5. Leave Secondary DNS blank (or `8.8.8.8`).
6. Save and reconnect.
7. Open Minecraft, go to **Play → Servers**. One of the featured servers will now launch a custom server list — pick **Add Server** and enter `<pi-ip>` on port `19132`.

> **Note:** Nintendo system updates occasionally reset these DNS settings. If the Switch stops finding your server, check the DNS settings first.

---

## Backups

Backups are saved to `~/block_boss/backups/` on the Pi. Each backup is a folder named with a timestamp (e.g. `2026-05-21T14-30-00`). Block Boss keeps the 7 most recent backups by default (change `BLOCK_BOSS_BACKUP_KEEP` in the environment if you want more or fewer).

To restore a backup, open the dashboard, go to **Backups**, and hit **Restore** next to the one you want — you'll need the parent PIN.

---

## Parent PIN

The PIN is stored as a secure hash (scrambled one-way fingerprint — the real digits are never saved). It protects:

- Stopping the server
- Restarting the server
- Adding or removing players from the approved-player list
- Restoring a backup

To change the PIN, go to **Settings** in the dashboard. You'll need to enter the current PIN first.

---

## Troubleshooting

**Server won't start**
Check the logs on the Pi:
```bash
ls ~/block_boss/logs/
```
Look at the most recent log file for error messages. Common causes: Box64 not installed correctly, the `bedrock_server` binary not present or not executable, or not enough disk space.

**Switch can't see the server**
1. Open the Block Boss dashboard and check the **Switch (BedrockConnect)** status light — it should be green.
2. If it's red or yellow, BedrockConnect isn't running. Check the service: `sudo systemctl status block-boss@$USER`.
3. On the Switch, confirm the DNS is still set to the Pi's IP (Nintendo updates can reset this).

**Dashboard won't open**
Make sure the service is running:
```bash
sudo systemctl status block-boss@$USER
```
If it's stopped, start it with:
```bash
sudo systemctl start block-boss@$USER
```

---

## License

See `LICENSE` in the repository root.
