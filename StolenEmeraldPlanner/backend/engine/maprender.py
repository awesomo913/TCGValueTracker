"""Render a decomp map layout into an accurate PNG, the way Porymap does.

GBA tileset model (pokeemerald):
- tiles.png        : indexed (4bpp) image, 8x8 tiles in a 16-wide grid; pixel
                     value = palette index 0-15 (0 = transparent).
- metatiles.bin    : 16 bytes per metatile = 8 tile entries (4 bottom layer,
                     4 top layer), each entry 2 bytes:
                       bits 0-9  tile index, bit 10 xflip, bit 11 yflip,
                       bits 12-15 palette number.
- map.bin          : width*height entries, 2 bytes each; metatile id = value & 0x3FF.
- palettes/NN.pal  : JASC palettes; primary owns slots 0-6, secondary 7-15.

Metatiles 0..511 live in the primary tileset; >=512 in the secondary
(index-512). Tile indices <512 use the primary tiles.png, >=512 the secondary.

Read-only: opens repo files, returns an in-memory PNG. Never writes to the repo.
"""
import struct
from pathlib import Path

from PIL import Image

TILE = 8
METATILE = 16
TILES_PER_METATILE = 8
METATILES_IN_PRIMARY = 512
TILES_IN_PRIMARY = 512


def _tileset_dir(repo: Path, gname: str) -> Path:
    """gTileset_General -> data/tilesets/primary/general (search primary then secondary)."""
    stem = gname.replace("gTileset_", "")
    # CamelCase -> snake_case
    snake = "".join(("_" + c.lower()) if c.isupper() else c for c in stem).lstrip("_")
    for kind in ("primary", "secondary"):
        d = repo / "data" / "tilesets" / kind / snake
        if d.is_dir():
            return d
    raise FileNotFoundError(f"tileset folder not found for {gname} ({snake})")


def _load_palettes(tdir: Path) -> dict:
    pals = {}
    pdir = tdir / "palettes"
    if not pdir.is_dir():
        return pals
    for f in pdir.glob("*.pal"):
        try:
            slot = int(f.stem)
        except ValueError:
            continue
        lines = f.read_text(encoding="utf-8", errors="ignore").splitlines()
        colors = []
        for ln in lines[3:]:  # skip JASC-PAL / version / count
            parts = ln.split()
            if len(parts) >= 3:
                colors.append((int(parts[0]), int(parts[1]), int(parts[2])))
        if colors:
            pals[slot] = colors
    return pals


def _read_metatiles(tdir: Path):
    data = (tdir / "metatiles.bin").read_bytes()
    n = len(data) // (TILES_PER_METATILE * 2)
    out = []
    for m in range(n):
        tiles = []
        for t in range(TILES_PER_METATILE):
            (v,) = struct.unpack_from("<H", data, (m * TILES_PER_METATILE + t) * 2)
            tiles.append(
                {
                    "tile": v & 0x3FF,
                    "xflip": bool(v & 0x400),
                    "yflip": bool(v & 0x800),
                    "pal": (v >> 12) & 0xF,
                }
            )
        out.append(tiles)
    return out


def _tile_index_image(tiles_p: Image.Image, tile_idx: int, xflip: bool, yflip: bool):
    per_row = tiles_p.width // TILE
    if per_row == 0:
        return None
    tx = (tile_idx % per_row) * TILE
    ty = (tile_idx // per_row) * TILE
    if ty + TILE > tiles_p.height:
        return None
    region = tiles_p.crop((tx, ty, tx + TILE, ty + TILE))
    if xflip:
        region = region.transpose(Image.FLIP_LEFT_RIGHT)
    if yflip:
        region = region.transpose(Image.FLIP_TOP_BOTTOM)
    return region


def render_layout(repo: Path, layout: dict) -> Image.Image:
    w, h = layout["width"], layout["height"]
    prim = _tileset_dir(repo, layout["primary_tileset"])
    sec = _tileset_dir(repo, layout["secondary_tileset"])

    palettes = {}
    palettes.update(_load_palettes(prim))
    palettes.update(_load_palettes(sec))  # secondary owns its own slots

    tiles_prim = Image.open(prim / "tiles.png").convert("P")
    tiles_sec = Image.open(sec / "tiles.png").convert("P")

    meta_prim = _read_metatiles(prim)
    meta_sec = _read_metatiles(sec)

    blocks = (repo / layout["blockdata_filepath"]).read_bytes()

    img = Image.new("RGBA", (w * METATILE, h * METATILE), (0, 0, 0, 255))
    tile_cache = {}

    def composed_tile(entry):
        key = (entry["tile"], entry["xflip"], entry["yflip"], entry["pal"])
        if key in tile_cache:
            return tile_cache[key]
        ti = entry["tile"]
        if ti < TILES_IN_PRIMARY:
            src = tiles_prim
            idx = ti
        else:
            src = tiles_sec
            idx = ti - TILES_IN_PRIMARY
        region = _tile_index_image(src, idx, entry["xflip"], entry["yflip"])
        pal = palettes.get(entry["pal"])
        if region is None or pal is None:
            tile_cache[key] = None
            return None
        px = region.load()
        out = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
        op = out.load()
        for yy in range(TILE):
            for xx in range(TILE):
                i = px[xx, yy]
                if i == 0:  # palette index 0 = transparent
                    continue
                if i < len(pal):
                    r, g, b = pal[i]
                    op[xx, yy] = (r, g, b, 255)
        tile_cache[key] = out
        return out

    sub = [(0, 0), (TILE, 0), (0, TILE), (TILE, TILE)]  # 2x2 layout of 4 tiles
    for by in range(h):
        for bx in range(w):
            off = (by * w + bx) * 2
            if off + 1 >= len(blocks):
                continue
            (val,) = struct.unpack_from("<H", blocks, off)
            mid = val & 0x3FF
            if mid < METATILES_IN_PRIMARY:
                mt = meta_prim[mid] if mid < len(meta_prim) else None
            else:
                si = mid - METATILES_IN_PRIMARY
                mt = meta_sec[si] if si < len(meta_sec) else None
            if mt is None:
                continue
            ox, oy = bx * METATILE, by * METATILE
            for layer in range(2):  # 0 = bottom, 1 = top (drawn over)
                for sidx in range(4):
                    entry = mt[layer * 4 + sidx]
                    ti = composed_tile(entry)
                    if ti is None:
                        continue
                    dx, dy = sub[sidx]
                    img.alpha_composite(ti, (ox + dx, oy + dy))
    return img
