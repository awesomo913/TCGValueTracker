# PROOF — GBA Sprite Editor Plain-Language Record

A dated record any reader can understand — customer, investor, lawyer,
judge, jury, or anyone curious. No engineering background needed.

## What this thing is

The GBA Sprite Editor is a desktop program for drawing and editing the
tiny pictures (called "sprites") that show up in old-style Game Boy
Advance games. Think of every Pokemon trainer, every monster, every
building or rock or chair you see on screen in those games — somebody
had to draw each one as a small grid of colored dots. This program
lets you do that, save it in the format the game expects, and put it
back into the game.

It is built for a specific game project: a hand-modified version of
Pokemon Emerald. The program already knows where all the existing
art lives (~7,900 pictures) so the user can pick any one, change it,
and put it back without hunting around in folders.

## What it does for you

- Lets you pick from every picture in the game and edit it.
- Reads every step of the editor aloud if you want it to. Built-in
  voice. No internet needed.
- Walks new users through a 10-step illustrated tutorial.
- Saves your changes in the four file formats the game needs (the
  picture itself, its color list, a compressed version, and a tile
  layout) — all from one click.
- Has an Animation Editor so you can string several frames together
  and watch your character walk or attack.

## What changed on May 18, 2026 (version 2.8.0)

Three things shipped today that were on the to-do list from the last
session:

1. **Bottom strip now shows the frames you're working on.** When you
   open the Animation Editor and load a sheet of frames, those frames
   show up in a row at the bottom of the main window. Click any one to
   jump to it. Add, delete, or reorder frames in the editor and the
   bottom row updates immediately.

2. **A new "Send to Main" button in the Animation Editor.** Pushes
   the current frame onto the big main canvas so you can polish it
   with the full toolset (pencil, fill, color picker, selection) and
   then send it back.

3. **A motion-trail toggle.** A slider in the Animation Editor lets
   you see up to four previous frames overlaid behind the current
   one, each fading more than the last. Animators call this
   "onion-skinning." It makes it much easier to see how a character
   moves from one frame to the next so you can keep the motion smooth.

## How it was made

The user designed it. AI helped build it. It is a program written in
the Python language using two free libraries (CustomTkinter for the
on-screen buttons and Pillow for the picture editing).

The program is now version 2.8.0. The Windows shortcut on the desktop
launches the latest version every time. The whole thing fits in one
161-megabyte file that doesn't need anything else installed.

## Verification

- All 51 automated tests still pass after the changes.
- A smoke test confirmed the bottom strip updates correctly when the
  Animation Editor adds, removes, reorders, paints, or scrubs frames.
- A smoke test confirmed the "Send to Main" button correctly pushes
  the active frame back to the main canvas.
- A smoke test confirmed the motion-trail toggle from 0 (off) up to
  4 (four trailing ghosts) renders without errors.
- The standalone Windows program at
  `C:\Users\computer\Desktop\My Apps\SpriteEditor.exe` was rebuilt on
  May 18, 2026, and now reports version 2.8.0.

## Changelog

- **May 18, 2026 — v2.10.0.**  The chat AI now knows what sprite
  you're working on.  Every question you send to the AI also carries
  (in the background) a short list of facts about your editor — the
  filename, the size, how many colors are in it, the five most-used
  colors, which drawing tool is active, whether GBA mode is on, and
  how deep the undo history is.  That means you can ask "why is my
  palette index 0 magenta?" or "why does this look so flat?" and the
  AI answers with reference to *your* sprite, not a generic one.
  Also added an AI Settings tab inside Help Hub so you can change
  the daily cost cap, switch where the API key is read from, and
  open the DeepSeek key-rotation page with one click.
- **May 18, 2026 — v2.9.1.**  Two small UX fixes after live testing:
  double-clicking the desktop shortcut while the editor is already
  running now brings the existing window forward instead of opening
  a duplicate, and there's a small floating "? Help (F1)" button at
  the top-right of the editor so Help is always reachable even if
  you resize the window narrow.
- **May 18, 2026 — v2.9.0.** Added an "Ask AI" tab in the Help Hub.
  The editor can now talk to DeepSeek (a chat AI service) over the
  internet to answer free-form questions a sprite artist might have.
  Replies stream in word by word, can be read aloud through the
  same voice the editor already uses, and the running daily cost
  is shown in the chat header so you always know what you've spent.
  The system was split in two: the editor reads its instructions
  and rules from a separate folder on disk (Quasar) — meaning you
  can tweak the AI's tone, switch models, or add new abilities just
  by editing files in `C:\Quasar\sprite_editor_ai\`, then clicking
  "Reload AI Config" inside the chat.  No AI keys are ever stored
  inside the program file; the editor reads them from a Windows
  environment variable so the same key never accidentally ships
  with the program.  When the AI service fails for any reason, the
  editor drops a small diagnostic file in Quasar's inbox and waits
  up to 30 seconds for a suggested fix to appear before showing a
  generic help message.  This is Phase 1 of three; future updates
  will give the AI awareness of the sprite you're currently editing
  (Phase 2) and the ability to perform editor actions on your
  behalf with confirmation prompts (Phase 3).
- **May 18, 2026 — v2.8.1.** Sharpened the six tool icons on high-
  resolution monitors and added a searchable Help Hub FAQ tab with
  22 plain-English answers (undo, transparency, sprite sizes, voice,
  BGR555, and more).  Confirmed by inspection that the selection
  tool's copy/cut/paste/delete already create exactly one undo
  entry each — no fix needed.
- **May 18, 2026 — v2.8.0.** Added bottom-strip frame slots wired to
  the Animation Editor, a "Send to Main" button, and a 0–4 motion-
  trail slider (onion-skin).
- **May 18, 2026 — v2.7.0.** Hover tooltips on every template,
  cross-category render audit (2,903 templates, zero failures), and
  four real in-game GBA color examples.
- **May 18, 2026 — v2.6.0.** Pencil-stroke undo fix, three-way color
  picker, zoom +/-, Diagnose dialog, sandbox editor, Help Hub buttons.
