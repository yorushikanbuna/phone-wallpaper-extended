# phone-wallpaper-extended

Extend image height with a seamless exponential-blend transition — designed for adapting wallpapers to taller phone screens **without cropping**.

## How it works

<div align="center">
  <pre>
┌──────────────────────┐
│   solid fill (606px) │  ← pure colour, sampled from blurred image top
├──────────────────────┤
│   solid zone (50px)  │  ← original image, overlaid with same pure colour
│  ── invisible seam ──│
│  exp-blend (200px)   │  ← e^(-5x) decay: blur-heavy at top, sharp at bottom
├──────────────────────┤
│   untouched original │  ← full quality preserved
└──────────────────────┘
  </pre>
</div>

### The algorithm

1. **Sample fill colour** — heavily blur the image's top region and extract the dominant colour
2. **Solid extension** — fill the required extra height with that pure colour
3. **Solid zone** — overlay the same pure colour on the original's first 50 px to make the seam invisible
4. **Exponential blend** — over the next 200 px, blend from pure colour → heavily blurred original → sharp original, following an e⁻⁵ˣ decay curve so texture releases extremely slowly at first
5. **Original untouched** — everything beyond 250 px is unmodified

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
node extend-wallpaper.js <input> [output] [options]
```

| Option | Default | Description |
|--------|---------|-------------|
| `--ratio N` | `0.4606` (1216/2640) | Target width/height ratio |
| `--solid N` | `50` | Pixels of solid-colour zone on original top |
| `--blend N` | `200` | Pixels of exponential-blend transition |
| `--fill-blur N` | `80` | Blur sigma for fill colour sampling |
| `--blend-blur N` | `50` | Blur sigma for transition zone |
| `--exp-k N` | `5` | Exponential decay steepness |

```bash
# Basic usage — auto-detects extension amount from image dimensions
node extend-wallpaper.js photo.png

# Custom output path
node extend-wallpaper.js photo.png wallpaper.png

# Custom aspect ratio (e.g. 9:19.5)
node extend-wallpaper.js photo.png --ratio 0.4615

# Batch processing (bash)
for f in *.png; do node extend-wallpaper.js "$f"; done
```

### API

```js
const { extendImage } = require('./extend-wallpaper.js');

await extendImage('photo.png', 'output.png', {
  ratio: 1216 / 2640,   // target aspect ratio
  solidZone: 50,        // px of pure fill on original top
  blendZone: 200,       // px of exponential transition
  fillBlur: 80,         // blur sigma for fill colour
  blendBlur: 50,        // blur sigma for transition
  expK: 5,              // exponential steepness
});
```

## Example

| Before (1440×2520) | After (1440×3126) |
|---------------------|--------------------|
| Original wallpaper, too short for phone screen | Extended 606px at top, zero visible seam, original content fully preserved |

## License

MIT
