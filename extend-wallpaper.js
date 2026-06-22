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
  const fillBlur   = opts.fillBlur   ?? FILL_BLUR;
  const expK       = opts.expK       ?? EXP_K;

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

  // ── Brightness match: adjust fill L to match gradient boundary ──
  const boundaryY = Math.min(modifyZone, height - 1);
  const boundaryRaw = await sharp(inputPath)
    .extract({ left: 0, top: Math.max(0, boundaryY - 10), width, height: Math.min(20, height - boundaryY + 10) })
    .removeAlpha().raw().toBuffer();
  let bSumR = 0, bSumG = 0, bSumB = 0;
  for (let i = 0; i < boundaryRaw.length; i += 3) {
    bSumR += boundaryRaw[i]; bSumG += boundaryRaw[i + 1]; bSumB += boundaryRaw[i + 2];
  }
  const bCount = boundaryRaw.length / 3;
  const bR = Math.round(bSumR / bCount), bG = Math.round(bSumG / bCount), bB = Math.round(bSumB / bCount);

  // RGB → HSL, match L, HSL → RGB
  const rgbToHsl = (r,g,b) => {
    r/=255;g/=255;b/=255; const mx=Math.max(r,g,b),mn=Math.min(r,g,b),d=mx-mn;
    let h=0,s=0,l=(mx+mn)/2;
    if(d!==0){s=l>.5?d/(2-mx-mn):d/(mx+mn);
      h=mx===r?((g-b)/d+(g<b?6:0))/6:mx===g?((b-r)/d+2)/6:((r-g)/d+4)/6;}
    return[h,s,l];
  };
  const hslToRgb = (h,s,l) => {
    const hue2rgb=(p,q,t)=>{if(t<0)t+=1;if(t>1)t-=1;if(t<1/6)return p+(q-p)*6*t;if(t<.5)return q;if(t<2/3)return p+(q-p)*(2/3-t)*6;return p;};
    if(s===0)return[Math.round(l*255),Math.round(l*255),Math.round(l*255)];
    const q=l<.5?l*(1+s):l+s-l*s, p=2*l-q;
    return[Math.round(hue2rgb(p,q,h+1/3)*255),Math.round(hue2rgb(p,q,h)*255),Math.round(hue2rgb(p,q,h-1/3)*255)];
  };

  const [fh,fs] = rgbToHsl(fR,fG,fB);
  const [,,bl] = rgbToHsl(bR,bG,bB);
  [fR,fG,fB] = hslToRgb(fh, fs, bl); // fill hue/sat + boundary lightness

  // ── 2. Pure solid fill background ──
  const fillBg = await sharp({
    create: { width, height: targetH, channels: 4,
      background: { r: fR, g: fG, b: fB, alpha: 1 } },
  }).png().toBuffer();

  // ── 3. Alpha gradient (pure exponential) ──
  const origWithAlphaBuf = Buffer.alloc(height * width * 4);
  const origRaw = await sharp(inputPath).removeAlpha().raw().toBuffer();
  const denom = 1 - Math.exp(-expK);

  for (let y = 0; y < height; y++) {
    const t = Math.min(y / modifyZone, 1);
    const curve = (Math.exp(-expK * (1 - t)) - Math.exp(-expK)) / denom;
    const alpha = Math.round(255 * curve);

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
    .composite([{ input: origWithAlpha, top: extendPx, left: 0, blend: 'over' }])
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
  if (args.modifyZone) opts.modifyZone = args.modifyZone;
  if (args.fillBlur)   opts.fillBlur   = args.fillBlur;
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
