# Menu and HUD art

The few bitmaps the app ships are generated from the SVG files in this folder:

| Source | Output (`app/src/main/res/drawable-nodpi/`) | Used by |
|---|---|---|
| `menu_backdrop.svg` | `menu_backdrop.webp` (1600x800) | `BackdropDrawable`, behind every menu screen |
| `emblem.svg` | `menu_emblem.png` (512x512) | `EmblemView`, the crest above the main menu title |
| `hud_icons.svg` | `hud_food.png`, `hud_wood.png`, `hud_gold.png`, `hud_stone.png` (96x96) | `HudSkin.resIcon`, the top bar and market buttons |

Everything else in the menus and HUD (button frames, panels, ribbons, the launcher icon) is
drawn in code or as vector drawables.

## Regenerating

Needs Node with the `playwright` module installed (globally is fine) and a Chromium it can
launch:

```sh
node tools/art/render.js
# or, pointing at a specific browser install:
PLAYWRIGHT_BROWSERS_PATH=/path/to/browsers node tools/art/render.js
CHROME_PATH=/path/to/chrome node tools/art/render.js
```

The script renders each SVG in headless Chromium, then re-encodes (and crops) through a
canvas so the backdrop comes out as WebP and the icon strip is split into separate files.
Edit the SVGs and re-run; the outputs are committed so a normal build never needs Node.
