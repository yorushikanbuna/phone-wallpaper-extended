#!/usr/bin/env node
const sharp = require('sharp');

// ── Defaults ──────────────────────────────────────────────
const FILL_BLUR = 80;   // sigma for fill-colour blur
const EXP_K     = 3;    // exponential S-curve steepness

// ── Core ───────────────────────────────────────────────────

/**
 * Extend an image with a solid-colour background and the original
 * fading in via an exponential transparency gradient.
 *
 * @param {string}  inputPath
 * @param {string}  outputPath
 * @param {object}  [opts]
 * @param {string}  [opts.target]      target resolution "WxH" (e.g. "1216x2640")
 * @param {number}  [opts.ratio]       target width / height (e.g. 1216/2640)
 * @param {number}  [opts.modifyZone]  px of transparency gradient on original top
 * @param {number}  [opts.fillBlur]    blur sigma for fill-colour sampling
 * @param {number}  [opts.expK]        exponential steepness (default 3)
 */
async function extendImage(inputPath, outputPath, opts = {}) {
  const meta = await sharp(inputPath).metadata();
  const { width, height } = meta;

  // Resolve target ratio
  let ratio = opts.ratio;
  if (!ratio && opts.target) {
    const [tw, th] = opts.target.split('x').map(Number);
    if (!tw || !th) throw new Error(`Invalid --target "${opts.target}". Use WxH, e.g. 1216x2640`);
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
    await sharp(inputPath).toFile(outputPath);
    return { width, height, targetH, extendPx: 0, fillColor: null };
  }

  const modifyZone = opts.modifyZone ?? Math.round(height * 0.1);
  const position   = opts.position   ?? 'top';
  const fillBlur   = opts.fillBlur   ?? FILL_BLUR;
  const expK       = (opts.expK != null && opts.expK > 0) ? opts.expK : EXP_K;

  if (modifyZone <= 0) throw new Error('modifyZone must be > 0');

  // ── 1. Sample fill from image top (matches seam colour for feathering) ──
  const fillSampleTop = 0;
  const FILL_SAMPLE_H = Math.min(30, height);
  const fillRaw = await sharp(inputPath)
    .extract({ left: 0, top: fillSampleTop, width, height: FILL_SAMPLE_H })
    .removeAlpha().raw().toBuffer();

  const fillPNG = await sharp(fillRaw, {
    raw: { width, height: FILL_SAMPLE_H, channels: 3 },
  }).png().toBuffer();

  const fillBlurred = await sharp(fillPNG)
    .blur(Math.min(fillBlur, FILL_SAMPLE_H * 2))
    .removeAlpha().raw().toBuffer();

  // Median of all pixels in the blurred strip — robust fill colour
  const allR = [], allG = [], allB = [];
  for (let i = 0; i < fillBlurred.length; i += 3) {
    allR.push(fillBlurred[i]);
    allG.push(fillBlurred[i + 1]);
    allB.push(fillBlurred[i + 2]);
  }
  allR.sort((a, b) => a - b); allG.sort((a, b) => a - b); allB.sort((a, b) => a - b);
  const mid = Math.floor(allR.length / 2);
  let fR = allR[mid], fG = allG[mid], fB = allB[mid];

  // ── Second fill colour for center/bottom modes ──
  let fR2 = fR, fG2 = fG, fB2 = fB;
  if (position === 'center') {
    const halfZone = Math.floor(modifyZone / 2);
    const bTop = Math.max(0, height - halfZone - 10);
    const bH = Math.min(20, height - bTop);
    if (bH > 0) {
      const botRaw = await sharp(inputPath)
        .extract({left:0,top:bTop,width,height:bH}).removeAlpha().raw().toBuffer();
      const botPNG = await sharp(botRaw,{raw:{width,height:bH,channels:3}}).png().toBuffer();
      const botBlur = await sharp(botPNG).blur(Math.min(fillBlur, bH*2)).removeAlpha().raw().toBuffer();
      const all2=[],mid2=Math.floor(botBlur.length/6);
      for(let i=0;i<botBlur.length;i+=3)all2.push(botBlur[i]);all2.sort((a,b)=>a-b);fR2=all2[mid2];
      all2.length=0;for(let i=0;i<botBlur.length;i+=3)all2.push(botBlur[i+1]);all2.sort((a,b)=>a-b);fG2=all2[mid2];
      all2.length=0;for(let i=0;i<botBlur.length;i+=3)all2.push(botBlur[i+2]);all2.sort((a,b)=>a-b);fB2=all2[mid2];
    }
  }

  // ── 2. Fill background (with two-colour support for center) ──
  const fillTop = await sharp({
    create: { width, height: extendPx, channels: 4,
      background: { r: fR, g: fG, b: fB, alpha: 1 } },
  }).png().toBuffer();
  const fillBot = await sharp({
    create: { width, height: extendPx, channels: 4,
      background: { r: fR2, g: fG2, b: fB2, alpha: 1 } },
  }).png().toBuffer();

  const topOffset = position === 'bottom' ? extendPx
    : position === 'center' ? Math.floor(extendPx / 2) : extendPx;
  const halfExt = Math.floor(extendPx / 2);

  let fillBg;
  if (position === 'center') {
    fillBg = await sharp({
      create: { width, height: targetH, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 1 } },
    })
      .composite([
        { input: fillTop, top: 0, left: 0 },
        { input: fillBot, top: halfExt + height, left: 0 },
      ])
      .png().toBuffer();
  } else {
    fillBg = await sharp({
      create: { width, height: targetH, channels: 4,
        background: { r: fR, g: fG, b: fB, alpha: 1 } },
    }).png().toBuffer();
  }

  // ── 3. Alpha gradient (pure exponential, per-position) ──
  const origWithAlphaBuf = Buffer.alloc(height * width * 4);
  const origRaw = await sharp(inputPath).removeAlpha().raw().toBuffer();
  const denom = 1 - Math.exp(-expK);
  const halfZone = Math.floor(modifyZone / 2);
  const alphaAt = (dist, range) => {
    const t = Math.min(dist / range, 1);
    const curve = (Math.exp(-expK * (1 - t)) - Math.exp(-expK)) / denom;
    return Math.round(255 * curve);
  };

  for (let y = 0; y < height; y++) {
    let alpha;
    if (position === 'bottom') {
      alpha = y >= height - modifyZone ? alphaAt(height - y, modifyZone) : 255;
    } else if (position === 'center') {
      if (y < halfZone) alpha = alphaAt(y, halfZone);
      else if (y >= height - halfZone) alpha = alphaAt(height - y, halfZone);
      else alpha = 255;
    } else {
      alpha = y < modifyZone ? alphaAt(y, modifyZone) : 255;
    }
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 3;
      const di = (y * width + x) * 4;
      origWithAlphaBuf[di]     = origRaw[si];
      origWithAlphaBuf[di + 1] = origRaw[si + 1];
      origWithAlphaBuf[di + 2] = origRaw[si + 2];
      origWithAlphaBuf[di + 3] = alpha;
    }
  }

  const origWithAlpha = await sharp(origWithAlphaBuf, {
    raw: { width, height, channels: 4 },
  }).png().toBuffer();

  await sharp(fillBg)
    .composite([{ input: origWithAlpha, top: topOffset, left: 0, blend: 'over' }])
    .png()
    .toFile(outputPath);

  return { width, height, targetH, extendPx, fillColor: { r: fR, g: fG, b: fB } };
}

// ── CLI ────────────────────────────────────────────────────

function printHelp() {
  console.log([
    '',
    '  extend-wallpaper — seamless phone wallpaper extension',
    '',
    '  Usage:',
    '    node extend-wallpaper.js <input> [output] --target <WxH>',
    '',
    '  Options:',
    '    --target WxH      target phone resolution (e.g. 1216x2640)',
    '    --ratio N         alt: aspect ratio (e.g. 0.4606)',
    '    --modify-zone N   px of transparency gradient (default: 10% of image height)',
    '    --fill-blur N     blur sigma for fill colour (default: 80)',
    '    --exp-k N         exponential steepness (default: 3)',
    '',
    '  Examples:',
    '    node extend-wallpaper.js photo.png --target 1216x2640',
    '    node extend-wallpaper.js photo.png out.png --target 1080x2400',
    '    for f in *.png; do node extend-wallpaper.js "$f" --target 1216x2640; done',
    '',
  ].join('\n'));
}

function parseArgv(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h')  { args.help = true; }
    else if (a === '--target')         { args.target     = argv[++i]; }
    else if (a === '--ratio')          { args.ratio      = parseFloat(argv[++i]); }
    else if (a === '--modify-zone')    { args.modifyZone = parseInt(argv[++i], 10); }
    else if (a === '--fill-blur')      { args.fillBlur   = parseFloat(argv[++i]); }
    else if (a === '--exp-k')          { args.expK       = parseFloat(argv[++i]); }
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
  if (args.modifyZone != null) opts.modifyZone = args.modifyZone;
  if (args.fillBlur != null)   opts.fillBlur   = args.fillBlur;
  if (args.expK != null)       opts.expK       = args.expK;

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
