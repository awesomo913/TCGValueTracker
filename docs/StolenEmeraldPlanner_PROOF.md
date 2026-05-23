# StolenEmerald Planner — PROOF (plain-language record)

This file is written so anyone — a customer, an investor, a lawyer, a judge, a
juror, or someone with no coding background — can understand what was built,
when, and why. No jargon.

## What it is, in one breath

It's a program that opens a nice-looking window on your computer and shows you,
at a glance, everything about a big fan-made Pokémon game project: which wild
Pokémon live on which maps, what their levels are, how rare they are, and what
each one looks like (using the game's real artwork). You click around like a
website, but it runs entirely on your own machine.

## Why it was built

The project's information was scattered across hundreds of files in many
different formats — encounter tables, map files, artwork folders, planning
notes. There was no single place to *see* it. This tool gathers it and makes it
visual, so the person building the game can plan the artistic and story side
while seeing the technical reality at the same time.

## The single most important promise

**The tool only reads the project. It never changes it.** It cannot edit, move,
or delete anything in the `C:\StolenEmerald` folder. It makes its own private
copy of what it needs (a small cache) somewhere else. So there is zero risk of
it damaging the actual game project.

## What it does today

- Shows **240 maps** that contain wild Pokémon.
- For each map, shows every wild Pokémon with its **real sprite (artwork)**, the
  **levels** it appears at, and **how rare** it is.
- Lets you click any Pokémon to see a **big picture** of it and **every map** it
  can be found on.
- Has a **Rescan** button so if you change the game project, the tool can re-read
  it and show the latest.

## Proof it actually works

On 2026-05-23, the tool was run and checked in a real web browser. Screenshots
captured: (1) the map list showing all 240 maps, (2) a map's encounter screen
showing real Pokémon sprites with levels and rarity bars, (3) a Pokémon detail
screen showing its picture and the five maps it appears on. Fifteen automated
tests pass against the real project data.

## Changelog

- **2026-05-23 — Phase 1 built.** Created the read-only viewer with the World
  Atlas: every wild Pokémon on every map, shown with real sprites, level ranges,
  and rarity. Verified working in a browser and packaged into a double-click
  Windows app. Three more sections (Cast & Scripts, Command Center, Story
  Timeline) are planned next.
- **2026-05-23 — Phase 2 built: the visual Map View.** Added a screen that draws
  each map exactly as it looks in the game (built from the game's own tile
  artwork) and lays useful information on top: it shades the grass and water where
  wild Pokémon appear, and marks every item ball, hidden item, person (NPC),
  trainer, sign, door (warp), and story event. Click any marker to see its
  details — for example, a story event shows the exact condition that triggers it.
  Verified in a browser on Route 101 (the rendered map, the green encounter zones,
  and the "Birch rescue" story trigger all displayed correctly) and rebuilt into
  the Windows app.
- **2026-05-23 — Phases 3-5 built: the last three rooms.** Added: **Cast &
  Scripts** (pick a map, see every person/trainer/item/sign and click to read the
  exact game script behind it, plus a trainer's team); **Command Center** (read
  and search all the project's notes and history documents in one place); and
  **Story Timeline** (a list of the 65 real story-progress switches, where each
  one advances the game and on which map, shown next to the written story plan).
  Also made wild-Pokémon spawn info much easier to see on the Map View (a clear
  card list plus the Pokémon's pictures placed right on the grass/water). All
  verified in a browser and rebuilt into the Windows app. The tool now has all
  four planned sections plus the visual Map View — five views total.
