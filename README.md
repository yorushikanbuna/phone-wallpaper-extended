# phone-wallpaper-extended

Extend image height with a seamless exponential-blend transition — adapt wallpapers to taller phone screens **without cropping**. Works with any image resolution and any phone aspect ratio.

## How it works

```
┌──────────────────────────┐
│   solid fill (adaptive)  │  ← pure colour from blurred image top
├──────────────────────────┤
│   solid zone (8% of ext) │  ← overlay same colour → invisible seam
│  ──── seam invisible ────│
│   exp-blend (35% of ext) │  ← e⁻⁵ˣ decay: texture releases slowly
├──────────────────────────┤
│   untouched original     │  ← full quality preserved
└──────────────────────────┘
```

Zones scale automatically with the extension amount. A 600 px extension gets ~50 px solid / ~200 px blend. A 200 px extension gets ~30 px solid / ~80 px blend.

## Install

```bash
git clone https://github.com/yorushikanbuna/phone-wallpaper-extended.git
cd phone-wallpaper-extended
npm install
```

Requires **Node.js ≥ 18**.

## Usage

### CLI

```bash
node extend-wallpaper.js <input> [output] --target <WxH>
```

| Option | Default | Description |
|--------|---------|-------------|
| `--target WxH` | *(required)* | Phone resolution, e.g. `1216x2640` |
| `--ratio N` | — | Alt: aspect ratio, e.g. `0.4606` |
| `--solid N` | adaptive | Solid-zone px on original top |
| `--blend N` | adaptive | Blend transition px |
| `--fill-blur N` | `80` | Blur sigma for fill colour |
| `--blend-blur N` | `50` | Blur sigma for transition |
| `--exp-k N` | `5` | Exponential decay steepness |

### Examples

```bash
# Your phone is 1216×2640, image is 1440×2520
node extend-wallpaper.js photo.png --target 1216x2640

# Custom output path
node extend-wallpaper.js photo.png out.png --target 1080x2400

# Fine-tune the blend
node extend-wallpaper.js photo.png --target 1216x2640 --solid 40 --blend 300

# Batch process a folder
for f in *.png; do node extend-wallpaper.js "$f" --target 1216x2640; done
```

### API

```js
const { extendImage } = require('./extend-wallpaper.js');

// Specify phone resolution
await extendImage('in.png', 'out.png', { target: '1216x2640' });

// Or aspect ratio
await extendImage('in.png', 'out.png', { ratio: 1216 / 2640 });

// With custom zones
await extendImage('in.png', 'out.png', {
  target:    '1216x2640',
  solidZone: 60,     // px of pure fill on original top
  blendZone: 250,    // px of transition
  fillBlur:  100,    // sigma for fill colour
  blendBlur: 60,     // sigma for transition blur
  expK:      6,      // steeper decay
});
```

## Tuning guide

| Symptom | Fix |
|---------|-----|
| Seam is visible | Increase `--solid` (e.g. 60) |
| Transition too abrupt | Increase `--blend` (e.g. 300) |
| Fill colour looks wrong | Increase `--fill-blur` (e.g. 120) |
| Extension too blurry | Decrease `--blend-blur` (e.g. 30) |
| Texture releases too fast | Increase `--exp-k` (e.g. 6–7) |

## License

MIT
