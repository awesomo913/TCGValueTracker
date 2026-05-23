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
