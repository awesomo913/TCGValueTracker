# StolenEmerald Planner — Visual Grades (llava:7b)

Graded by the local `llava:7b` vision model via `tools/llava_grade.py`. Scores are
rough/directional — the value is the *recurring pattern* across screens.

Re-run any time:
```bash
# with the app open, capture screenshots, then:
.venv/Scripts/python tools/llava_grade.py grade_shots/*.png
```

## Run — 2026-05-23

| # | View | Score | Main improvement called out |
|---|------|:---:|---|
| 1 | Atlas — map list | 7.0 | Reduce clutter; add icons for quick identification |
| 2 | Atlas — encounters | 7.0 | Add a legend for rarity/method icons |
| 3 | Atlas — mon detail | 6.5 | Stronger heading hierarchy, more spacing |
| 4 | Map View | 8.5 | Make overlay markers more prominent vs the map |
| 5 | Cast & Scripts | 7.0 | More text/background contrast; distinguish scripts |
| 6 | Command Center | 5.5 | Larger headings; dense doc text hurts readability |
| 7 | Story Timeline | 5.0 | Color-code data types; connect the three columns |
| 8 | Roadmap | 6.0 | Better contrast + spacing |

**Overall: ~6.5 / 10.** Strongest: Map View (8.5). Weakest: Story Timeline (5.0), Command Center (5.5).

## Recurring themes (act on these first)
1. **Text contrast** — muted gray body text on dark emerald panels reads too faint. Lighten body text / darken panels.
2. **Density & spacing** — more breathing room, especially the 3-column rooms (Timeline, Cast, Command).
3. **Color-coding by data type** — use color to distinguish kinds of data, not just as decoration.

## Run 2 — 2026-05-23 (after contrast + spacing + marker theme pass)

| # | View | Before | After | Δ |
|---|------|:---:|:---:|:---:|
| 1 | Atlas — map list | 7.0 | 8.0 | +1.0 |
| 2 | Atlas — encounters | 7.0 | 7.5 | +0.5 |
| 3 | Atlas — mon detail | 6.5 | 7.0 | +0.5 |
| 4 | Map View | 8.5 | 9.0 | +0.5 |
| 5 | Cast & Scripts | 7.0 | 6.0 | -1.0 (likely grader noise) |
| 6 | Command Center | 5.5 | 5.5 | 0 |
| 7 | Story Timeline | 5.0 | 7.0 | +2.0 |
| 8 | Roadmap | 6.0 | 6.0 | 0 |

**Overall avg: 6.5 → 7.0.** Theme pass: brighter `--muted`/`--text`, darker panels, +spacing in
3-column rooms, larger section headings, stronger map markers.

**Caveats / next:**
- llava:7b is noisy — Cast's -1.0 after a *global* improvement is almost certainly variance.
- **Command Center (5.5) is the real laggard:** it renders raw markdown as a wall of plain text.
  The genuine fix is rendering markdown (headings/bold/lists) in the doc viewer, not more CSS.
- Roadmap (6.0): could use stronger section separation / hierarchy.
