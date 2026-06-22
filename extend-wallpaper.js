#!/usr/bin/env node
const sharp = require('sharp');

// ── Defaults (adaptive — scale with extension amount) ──────
const FILL_BLUR  = 80;   // sigma for fill-colour blur
const BLEND_BLUR = 50;   // sigma for transition-zone blur
const EXP_K      = 5;    // exponential decay steepness

// ── Core ───────────────────────────────────────────────────

/**
 * Extend an image by adding solid-colour padding at the top,
 * with a seamless exponential-blend transition into the original.
 *
 * @param {string}  inputPath
 * @param {string}  outputPath
 * @param {object}  [opts]
 * @param {number}  [opts.ratio]        target width / height (e.g. 1216/2640)
 * @param {string}  [opts.target]       target resolution "WxH" (e.g. "1216x2640")
 * @param {number}  [opts.solidZone]    px of pure fill on original top
 * @param {number}  [opts.blendZone]    px of exponential-blend transition
 * @param {number}  [opts.fillBlur]     blur sigma for fill colour
 * @param {number}  [opts.blendBlur]    blur sigma for transition
 * @param {number}  [opts.expK]         exponential decay steepness
 */
async function extendImage(inputPath, outputPath, opts = {}) {
  const meta = await sharp(inputPath).metadata();
  const { width, height } = meta;

  // Resolve target ratio — prefer explicit ratio, then --target, then ask user
  let ratio = opts.ratio;
  if (!ratio && opts.target) {
    const [tw, th] = opts.target.split('x').map(Number);
    if (!tw || !th) throw new Error(`Invalid --target "${opts.target}". Use WxH format, e.g. 1216x2640`);
    ratio = tw / th;
  }
  if (!ratio) throw new Error(
    'Please specify --target WxH or --ratio N.\n' +
    '  Examples: --target 1216x2640  (phone resolution)\n' +
    '            --ratio 0.4606      (width ÷ height)'
  );

  const targetH = Math.round(width / ratio);
  const extendPx = targetH - height;

  if (extendPx <= 0) {
    // Image is already tall enough — copy unchanged
    await sharp(inputPath).toFile(outputPath);
    return { width, height, targetH, extendPx: 0, fillColor: null };
  }

  // Adaptive zones: scale with extension amount so short/long extensions look natural
  const solidZone = opts.solidZone ?? Math.max(30, Math.round(extendPx * 0.08));
  const blendZone = opts.blendZone ?? Math.max(80, Math.round(extendPx * 0.35));
  const fillBlur  = opts.fillBlur  ?? FILL_BLUR;
  const blendBlur = opts.blendBlur ?? BLEND_BLUR;
  const expK      = opts.expK      ?? EXP_K;

  const modifyZone = solidZone + blendZone;
  const sampleH = Math.min(modifyZone, height);

  // ── 1. Sample fill colour from heavily-blurred image top ──
  const topRaw = await sharp(inputPath)
    .extract({ left: 0, top: 0, width, height: sampleH })
    .removeAlpha()
    .raw()
    .toBuffer();

  const topPNG = await sharp(topRaw, {
    raw: { width, height: sampleH, channels: 3 },
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
      const sW = Math.exp(-expK * t);         // solid: steep exponential drop
      const oW = t * t;                        // original: quadratic rise at tail
      const bW = Math.max(0, 1 - sW - oW);     // blur: fills the mid-range
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
      { input: overlay,  top: 0,          left: 0 },
      { input: origRest, top: modifyZone,  left: 0 },
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

function printHelp() {
  console.log([
    '',
    '  extend-wallpaper — seamless phone wallpaper extension',
    '',
    '  Usage:',
    '    node extend-wallpaper.js <input> [output] [options]',
    '',
    '  Required (pick one):',
    '    --target WxH      e.g. --target 1216x2640   (your phone resolution)',
    '    --ratio N         e.g. --ratio 0.4606       (width ÷ height)',
    '',
    '  Optional:',
    '    --solid N         solid-zone px   (default: adaptive, ~8% of extension)',
    '    --blend N         blend-zone px   (default: adaptive, ~35% of extension)',
    '    --fill-blur N     fill-blur sigma (default: 80)',
    '    --blend-blur N    blend-blur sigma (default: 50)',
    '    --exp-k N         exp decay steepness (default: 5)',
    '',
    '  Examples:',
    '    # iPhone-style display (1216×2640)',
    '    node extend-wallpaper.js art.png --target 1216x2640',
    '',
    '    # Custom aspect ratio',
    '    node extend-wallpaper.js art.png out.png --target 1080x2400',
    '',
    '    # Batch process all PNGs in a folder',
    '    for f in *.png; do node extend-wallpaper.js "$f" --target 1080x2400; done',
    '',
  ].join('\n'));
}

// ── CLI ────────────────────────────────────────────────────

function parseArgv(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h')   { args.help = true; }
    else if (a === '--target')          { args.target     = argv[++i]; }
    else if (a === '--ratio')           { args.ratio      = parseFloat(argv[++i]); }
    else if (a === '--solid')           { args.solidZone  = parseInt(argv[++i], 10); }
    else if (a === '--blend')           { args.blendZone  = parseInt(argv[++i], 10); }
    else if (a === '--fill-blur')       { args.fillBlur   = parseFloat(argv[++i]); }
    else if (a === '--blend-blur')      { args.blendBlur  = parseFloat(argv[++i]); }
    else if (a === '--exp-k')           { args.expK       = parseFloat(argv[++i]); }
    else { args._.push(a); }
  }
  return args;
}

async function main() {
  if (require.main !== module) return;

  const args = parseArgv(process.argv.slice(2));

  if (args.help) { printHelp(); process.exit(0); }
  if (args._.length === 0) { printHelp(); process.exit(1); }

  const inputPath  = args._[0];
  const outputPath = args._[1] || inputPath.replace(/\.(png|jpe?g|webp|tiff?)$/i, '_extended.png');

  const opts = {};
  if (args.target)     opts.target     = args.target;
  if (args.ratio)      opts.ratio      = args.ratio;
  if (args.solidZone)  opts.solidZone  = args.solidZone;
  if (args.blendZone)  opts.blendZone  = args.blendZone;
  if (args.fillBlur)   opts.fillBlur   = args.fillBlur;
  if (args.blendBlur)  opts.blendBlur  = args.blendBlur;
  if (args.expK)       opts.expK       = args.expK;

  try {
    const { extendPx, fillColor, width, height, targetH } =
      await extendImage(inputPath, outputPath, opts);
    if (extendPx === 0) {
      console.log(`${inputPath}: already tall enough (${width}x${height}), copied unchanged`);
    } else {
      console.log(`${inputPath}: ${width}x${height} → ${width}x${targetH}  (+${extendPx}px, #${hex(fillColor)})`);
    }
  } catch (e) {
    console.error('Error:', e.message);
    process.exit(1);
  }
}

function hex(c) {
  if (!c) return 'none';
  return [c.r, c.g, c.b].map(v => v.toString(16).padStart(2, '0')).join('');
}

main();

// ── Exports ────────────────────────────────────────────────
module.exports = { extendImage };
