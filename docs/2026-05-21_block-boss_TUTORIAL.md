# Block Boss — TUTORIAL

**Date:** 2026-05-21
**Who this is for:** Parents setting up the server for the first time, and the child who will use it every day.

---

## Part 1: Parent Setup (First Time Only)

This section assumes you have a Raspberry Pi 5 with Raspberry Pi OS 64-bit already installed and connected to your home network.

---

### Step 1 — Find the two download links

You need two things before you can install. These links change every time Minecraft releases an update, so look them up fresh right now:

**Link A — The Minecraft server:**
Go to `https://www.minecraft.net/en-us/download/server/bedrock` in a browser. Find the Linux (Ubuntu/Debian) download. Right-click the download button and choose "Copy link address." Save that link.

**Link B — BedrockConnect:**
BedrockConnect is the helper that lets Nintendo Switch find your Pi. Go to `https://github.com/Pugmatt/BedrockConnect/releases`. Find the latest release. Right-click the `.jar` file download link and copy it. Save that link.

---

### Step 2 — Copy the Block Boss code to your Pi

On your Pi, open a terminal (the black text window) and type:

```bash
git clone https://github.com/<your-fork>/block_boss ~/AI/block_boss
cd ~/AI/block_boss
```

---

### Step 3 — Run the installer

Still in the terminal, paste these commands, replacing the placeholder text with the real links you copied in Step 1:

```bash
export BDS_URL="<paste Link A here>"
export BEDROCKCONNECT_URL="<paste Link B here>"
bash deploy/install.sh
```

The installer will take several minutes. It is downloading and building software. You will see lots of text scrolling by — that is normal.

When it finishes you will see:
```
>> Done. Install the service with:
   sudo cp deploy/block-boss.service /etc/systemd/system/block-boss@.service
   sudo systemctl enable --now block-boss@<your-username>
```

---

### Step 4 — Start Block Boss automatically on boot

Run the two commands the installer printed at the end. They set up Block Boss to start every time the Pi powers on.

> **What is a "service"?** It is a program that runs in the background, automatically, even when nobody is logged in. Like a security camera that runs 24/7.

---

### Step 5 — Open the dashboard

On any device on your home network (phone, tablet, laptop), open a browser and go to:

```
http://<pi-ip>:8000
```

You need to know your Pi's local IP address. You can find it on the Pi's terminal by typing `hostname -I`. It will look something like `192.168.1.42`.

You should see the Block Boss dashboard with big colored buttons.

---

### Step 6 — Set your parent PIN

On the dashboard, find **Settings** and set a 4-digit PIN. Write it down somewhere safe. This PIN is the only thing protecting the Stop, Restart, and Restore buttons from little fingers. If you forget it, you will need to delete the file `~/block_boss/pin.hash` on the Pi and set a new one.

---

### Step 7 — Set up each Nintendo Switch (one-time per Switch)

1. On the Switch, open **System Settings**.
2. Go to **Internet → Internet Settings**.
3. Select your home Wi-Fi network.
4. Tap **Change Settings**.
5. Under **DNS Settings**, choose **Manual**.
6. Set **Primary DNS** to your Pi's IP address.
7. Leave Secondary DNS empty (or set it to `8.8.8.8`, which is Google's public DNS — just a backup address lookup service).
8. Save and reconnect to Wi-Fi.
9. Open Minecraft on the Switch. Go to **Play → Servers**.
10. One of the "Featured Servers" entries will open a custom server list. Choose **Add Server**, enter your Pi's IP address, and use port `19132`. Give it a name like "Home Server."

> **Nintendo update warning:** Nintendo Switch system updates can reset the DNS setting. If the Switch stops finding the server one day, go back to Internet Settings and check that the Primary DNS is still your Pi's address.

---

### Step 8 — Add your child's Minecraft username to the approved list

In the dashboard, open **Approved Players** and add your child's gamertag (their Minecraft player name). You will need your PIN to do this.

Anyone not on the list will be turned away at the door when they try to join.

---

### Step 9 — Test it

Press the green **Start** button. Wait 20-30 seconds. On the Switch, open Minecraft, go to **Play → Servers**, find your server, and join. You should appear in the **Who's Playing** list on the dashboard.

---

## Part 2: For the Child — Every Day

Hi! Here is all you need to know.

---

### How to start the game

1. Make sure the TV or screen is on and your Switch is on.
2. On the family tablet (or any device), open the browser and go to the Block Boss page.
3. Press the big **green button** that says **Start**.
4. Wait about 20-30 seconds. The button will turn from "Starting..." to "Running" when it is ready.
5. Pick up the Switch, open Minecraft, go to **Play → Servers**, and pick "Home Server."

That's it! You're in.

---

### How to see who's playing

The **Who's Playing** section updates automatically. It shows everyone online right now.

---

### How to make a backup

If you built something really cool and want to save it, press the **Backup** button. It takes a snapshot of your whole world. You can always go back to a backup if something goes wrong.

---

### What you can't press

The red **Stop** button, the **Restart** button, the **Approved Players** section, and the **Restore** button all need Mum or Dad's PIN. If you press them by accident, nothing will happen — you'll just see a PIN box pop up. Just close it and carry on.

---

### If the server seems stuck

Tell a grown-up. They can check the Block Boss dashboard to see what the status light says.

---

## Part 3: Ongoing Parent Tasks

### Making backups regularly

The **Backup** button saves everything right now. It is a good habit to press it before a big building session and after one. Block Boss keeps the 7 most recent backups automatically (older ones are deleted to save disk space).

### Checking logs if something goes wrong

If the server refuses to start, log into the Pi and look at the log files:

```bash
ls ~/block_boss/logs/
```

Open the most recent file with:

```bash
cat ~/block_boss/logs/<filename>
```

The messages will tell you what went wrong.

### Keeping Box64 and the Bedrock server up to date

When Minecraft releases an update, you will need to:
1. Get the new BDS download link from minecraft.net.
2. Re-run the installer (it skips steps that are already done, but you can delete `~/block_boss/bedrock-server/bedrock_server` first to force a fresh download).

Box64 updates are less frequent. Check `https://github.com/ptitSeb/box64` occasionally.
