const sharp = require('sharp');

async function makeLR(origPath, extPath, outPath) {
  const W = 400; // each side 400px → 802px total
  const gap = 2;

  const orig = await sharp(origPath).metadata();
  const ext = await sharp(extPath).metadata();
  const oH = Math.round(orig.height * W / orig.width);
  const eH = Math.round(ext.height * W / ext.width);
  const maxH = Math.max(oH, eH);

  const origScaled = await sharp(origPath).resize(W, oH).png().toBuffer();
  const extScaled = await sharp(extPath).resize(W, eH).png().toBuffer();

  const totalW = W + gap + W;
  const bg = { r: 18, g: 18, b: 24 };

  await sharp({
    create: { width: totalW, height: maxH, channels: 3, background: bg }
  })
    .composite([
      { input: origScaled, top: Math.floor((maxH - oH) / 2), left: 0 },
      { input: extScaled,  top: Math.floor((maxH - eH) / 2), left: W + gap },
    ])
    .png()
    .toFile(outPath);

  console.log(outPath, totalW + 'x' + maxH);
}

async function main() {
  const pairs = [
    ['瑞希.png', '瑞希_extended.png', 'images/瑞希-compare.png'],
    ['绘名.png', '绘名_extended.png', 'images/绘名-compare.png'],
    ['奏.png',   '奏_extended.png',   'images/奏-compare.png'],
    ['真东.png', '真东_extended.png', 'images/真东-compare.png'],
  ];
  for (const [o, e, out] of pairs) {
    await makeLR(o, e, out);
  }
  console.log('Done!');
}

main().catch(e => { console.error(e); process.exit(1); });
