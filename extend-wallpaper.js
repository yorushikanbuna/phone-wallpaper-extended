#!/usr/bin/env node
const sharp = require('sharp');

// ── Configuration ──────────────────────────────────────────
const TARGET_RATIO = 1216 / 2640; // phone aspect ratio
const ORIG_MODIFY  = 250;         // px into original to modify (50 solid + 200 blend)
const SOLID_ZONE   = 50;          // px of pure fill at top of original
const FILL_BLUR    = 80;          // sigma for fill-color blur
const BLEND_BLUR   = 50;          // sigma for transition-zone blur
const EXP_K        = 5;           // exponential decay steepness

// ── Core ───────────────────────────────────────────────────

/**
 * Extend an image by adding solid-color padding at the top,
 * with a seamless exponential-blend transition into the original.
 *
 * @param {string}  inputPath   - path to the source image
 * @param {string}  outputPath  - path for the output image
 * @param {object}  [opts]      - optional overrides
 * @param {number}  [opts.ratio]       - target width/height ratio
 * @param {number}  [opts.solidZone]   - px of pure fill on original top
 * @param {number}  [opts.blendZone]   - px of transition blend
 * @param {number}  [opts.fillBlur]    - blur sigma for fill colour
 * @param {number}  [opts.blendBlur]   - blur sigma for transition
 * @param {number}  [opts.expK]        - exponential decay steepness
 */
async function extendImage(inputPath, outputPath, opts = {}) {
  const ratio     = opts.ratio      ?? TARGET_RATIO;
  const solidZone = opts.solidZone  ?? SOLID_ZONE;
  const blendZone = opts.blendZone  ?? (ORIG_MODIFY - SOLID_ZONE);
  const fillBlur  = opts.fillBlur   ?? FILL_BLUR;
  const blendBlur = opts.blendBlur  ?? BLEND_BLUR;
  const expK      = opts.expK       ?? EXP_K;

  const meta = await sharp(inputPath).metadata();
  const { width, height } = meta;
  const targetH = Math.round(width / ratio);
  const extendPx = targetH - height;

  if (extendPx <= 0) {
    // Image is already tall enough — copy unchanged
    await sharp(inputPath).toFile(outputPath);
    return { width, height, targetH, extendPx: 0, fillColor: null };
  }

  const modifyZone = solidZone + blendZone;

  // ── 1. Sample fill colour from heavily-blurred image top ──
  const topRaw = await sharp(inputPath)
    .extract({ left: 0, top: 0, width, height: Math.min(modifyZone, height) })
    .removeAlpha()
    .raw()
    .toBuffer();

  const topPNG = await sharp(topRaw, {
    raw: { width, height: Math.min(modifyZone, height), channels: 3 },
  }).png().toBuffer();

  const topBlurred = await sharp(topPNG)
    .blur(fillBlur)
    .removeAlpha()
    .raw()
    .toBuffer();

  const fR = topBlurred[0], fG = topBlurred[1], fB = topBlurred[2];

  // ── 2. Solid extension ──────────────────────────────────
  const extSolid = await sharp({
    create: { width, height: extendPx, channels: 4,
      background: { r: fR, g: fG, b: fB, alpha: 1 } },
  }).png().toBuffer();

  // ── 3. Blend overlay for original's top N rows ──────────
  const topHeavyBlur = await sharp(topPNG)
    .blur(blendBlur)
    .removeAlpha()
    .raw()
    .toBuffer();

  const overlayBuf = Buffer.alloc(modifyZone * width * 4);
  for (let y = 0; y < modifyZone; y++) {
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 3;
      const di = (y * width + x) * 4;

      if (y < solidZone) {
        // Pure fill colour — invisible seam
        overlayBuf[di]     = fR;
        overlayBuf[di + 1] = fG;
        overlayBuf[di + 2] = fB;
        overlayBuf[di + 3] = 255;
        continue;
      }

      // Exponential-decay blend: solid → blurred → original
      const t = (y - solidZone) / blendZone;
      const sW = Math.exp(-expK * t);         // solid: steep drop
      const oW = t * t;                        // original: quadratic rise
      const bW = Math.max(0, 1 - sW - oW);     // blur: fills the gap
      const total = sW + bW + oW;

      const bR = topHeavyBlur[si], bG = topHeavyBlur[si + 1], bB = topHeavyBlur[si + 2];
      const oR = topRaw[si],       oG = topRaw[si + 1],       oB = topRaw[si + 2];

      overlayBuf[di]     = clamp(Math.round((fR * sW + bR * bW + oR * oW) / total));
      overlayBuf[di + 1] = clamp(Math.round((fG * sW + bG * bW + oG * oW) / total));
      overlayBuf[di + 2] = clamp(Math.round((fB * sW + bB * bW + oB * oW) / total));
      overlayBuf[di + 3] = 255;
    }
  }

  const overlay = await sharp(overlayBuf, {
    raw: { width, height: modifyZone, channels: 4 },
  }).png().toBuffer();

  // ── 4. Rebuild original with modified top ───────────────
  const origRest = await sharp(inputPath)
    .extract({ left: 0, top: modifyZone, width, height: height - modifyZone })
    .ensureAlpha()
    .png()
    .toBuffer();

  const origModified = await sharp({
    create: { width, height, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 1 } },
  })
    .composite([
      { input: overlay,  top: 0,           left: 0 },
      { input: origRest, top: modifyZone,   left: 0 },
    ])
    .png()
    .toBuffer();

  // ── 5. Final assembly ───────────────────────────────────
  await sharp({
    create: { width, height: targetH, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 1 } },
  })
    .composite([
      { input: extSolid,     top: 0,        left: 0 },
      { input: origModified, top: extendPx, left: 0 },
    ])
    .png()
    .toFile(outputPath);

  return { width, height, targetH, extendPx, fillColor: { r: fR, g: fG, b: fB } };
}

// ── Helpers ────────────────────────────────────────────────

function clamp(v) {
  return v < 0 ? 0 : v > 255 ? 255 : v;
}

function printUsage() {
  console.log([
    '',
    'Usage: node extend-wallpaper.js <input> [output] [options]',
    '',
    '  <input>        path to source image (1440×2520 PNG recommended)',
    '  [output]       path for output image (default: <input>_extended.png)',
    '',
    'Options:',
    '  --ratio N      target aspect ratio (default: 1216/2640 ≈ 0.4606)',
    '  --solid N      px of solid-colour zone on original top (default: 50)',
    '  --blend N      px of exponential-blend transition (default: 200)',
    '  --fill-blur N  blur sigma for fill colour (default: 80)',
    '  --blend-blur N blur sigma for transition (default: 50)',
    '  --exp-k N      exponential decay steepness (default: 5)',
    '',
    'Example:',
    '  node extend-wallpaper.js photo.png',
    '  node extend-wallpaper.js photo.png out.png --ratio 9/19.5',
    '',
  ].join('\n'));
}

// ── CLI ────────────────────────────────────────────────────

function parseArgv(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h') { args.help = true; }
    else if (a === '--ratio')      { args.ratio      = parseFloat(argv[++i]); }
    else if (a === '--solid')      { args.solidZone  = parseInt(argv[++i], 10); }
    else if (a === '--blend')      { args.blendZone  = parseInt(argv[++i], 10); }
    else if (a === '--fill-blur')  { args.fillBlur   = parseFloat(argv[++i]); }
    else if (a === '--blend-blur') { args.blendBlur  = parseFloat(argv[++i]); }
    else if (a === '--exp-k')      { args.expK       = parseFloat(argv[++i]); }
    else { args._.push(a); }
  }
  return args;
}

async function main() {
  // ── exported function, no CLI behaviour ──
  if (require.main !== module) return;

  const args = parseArgv(process.argv.slice(2));

  if (args.help || args._.length === 0) {
    printUsage();
    process.exit(args.help ? 0 : 1);
  }

  const inputPath  = args._[0];
  const outputPath = args._[1] || inputPath.replace(/\.(png|jpe?g|webp|tiff?)$/i, '_extended.png');

  const opts = {};
  if (args.ratio)      opts.ratio      = args.ratio;
  if (args.solidZone)  opts.solidZone  = args.solidZone;
  if (args.blendZone)  opts.blendZone  = args.blendZone;
  if (args.fillBlur)   opts.fillBlur   = args.fillBlur;
  if (args.blendBlur)  opts.blendBlur  = args.blendBlur;
  if (args.expK)       opts.expK       = args.expK;

  const result = await extendImage(inputPath, outputPath, opts);
  console.log(`  → ${outputPath}  (extended ${result.extendPx}px, fill #${hex(result.fillColor)})`);
}

function hex(c) {
  if (!c) return 'none';
  return [c.r, c.g, c.b].map(v => v.toString(16).padStart(2, '0')).join('');
}

main().catch(e => { console.error(e.message); process.exit(1); });

// ── Exports ────────────────────────────────────────────────
module.exports = { extendImage };
