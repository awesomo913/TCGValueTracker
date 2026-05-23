# StolenEmerald Planner — TUTORIAL

A friendly walkthrough for using the app. No coding needed.

## 1. Open it

**Easiest:** double-click `Desktop\My Apps\StolenEmeraldPlanner\StolenEmeraldPlanner.exe`.
A window titled "StolenEmerald Planner" opens.

**From source instead:**
```bash
cd StolenEmeraldPlanner
.venv/Scripts/python app.py
```

The first time it opens, it reads your project once (takes a couple seconds) and
remembers it. After that it's instant.

## 2. The World Atlas (the main screen)

You land on the **World Atlas**. You'll see a grid of cards — one per map that
has wild Pokémon (240 of them). Each card shows the map name and how many wild
Pokémon live there.

- **Search:** type in the box at the top (e.g. "route 1", "cerulean") to filter
  the list instantly.

## 3. Look at a map's Pokémon

Click any map card. You'll see every wild Pokémon on that map:

- Its **sprite** (the actual game artwork).
- Its **level range** (e.g. "Lv 49-49").
- Its **rarity** as a percent, with a little bar — full bar = common, tiny bar =
  rare.
- Grouped by **how you find it**: Land, Water, Rock Smash, Fishing.

Click **← Maps** (top-left) to go back to the list.

## 4. Look at one Pokémon everywhere

Click any Pokémon card. You'll see:

- A **big picture** of it.
- A list of **every map it appears on**.

Click any of those maps to jump straight to that map's encounters.

## 5. When you change your project

If you edit your StolenEmerald project (add a Pokémon to a route, etc.), click
**Rescan repo** (bottom-left). The app re-reads everything and updates.

## 6. Good to know

- The app **never changes your project** — it only looks.
- If a Pokémon has no artwork, you'll see a "no sprite" placeholder instead of a
  broken image.
- Three more sections (Cast & Scripts, Command Center, Story Timeline) are
  greyed out for now — they're coming in later updates.
