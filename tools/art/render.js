#!/usr/bin/env node
// Renders the SVG sources in this folder to the bitmaps the app ships in
// app/src/main/res/drawable-nodpi/. Needs Node with the `playwright` module and a
// Chromium it can launch (PLAYWRIGHT_BROWSERS_PATH or CHROME_PATH).
//
//   node tools/art/render.js
const fs = require('fs');
const path = require('path');

function loadPlaywright() {
  try { return require('playwright'); } catch (_) {}
  const { execSync } = require('child_process');
  const root = execSync('npm root -g').toString().trim();
  return require(path.join(root, 'playwright'));
}

const here = __dirname;
const out = path.resolve(here, '../../app/src/main/res/drawable-nodpi');
fs.mkdirSync(out, { recursive: true });

const jobs = [
  // Main menu landscape: WebP keeps the painterly gradients small.
  // The canvas is taller than the export so filter edges fall outside the crop.
  { svg: 'menu_backdrop.svg', w: 1600, h: 840, outputs: [{ name: 'menu_backdrop.webp', type: 'image/webp', quality: 0.82, crop: [0, 0, 1600, 800] }] },
  // Title crest, transparent.
  { svg: 'emblem.svg', w: 512, h: 512, outputs: [{ name: 'menu_emblem.png', type: 'image/png' }] },
  // Four 96px HUD resource icons in one strip, split into separate files.
  {
    svg: 'hud_icons.svg', w: 384, h: 96,
    outputs: ['hud_food', 'hud_wood', 'hud_gold', 'hud_stone'].map((n, i) => ({ name: n + '.png', type: 'image/png', crop: [i * 96, 0, 96, 96] })),
  },
];

(async () => {
  const pw = loadPlaywright();
  const launch = {};
  if (process.env.CHROME_PATH) launch.executablePath = process.env.CHROME_PATH;
  const browser = await pw.chromium.launch(launch);
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  let total = 0;
  for (const job of jobs) {
    await page.setViewportSize({ width: job.w, height: job.h });
    const svg = fs.readFileSync(path.join(here, job.svg), 'utf8');
    await page.setContent(`<!doctype html><html><body style="margin:0;background:transparent">${svg}</body></html>`);
    await page.waitForTimeout(100);
    const png = await page.screenshot({ omitBackground: true, type: 'png', clip: { x: 0, y: 0, width: job.w, height: job.h } });
    for (const o of job.outputs) {
      const crop = o.crop || [0, 0, job.w, job.h];
      // Re-encode (and crop) through a canvas so WebP and sub-images come out of the same source.
      const dataUrl = await page.evaluate(async ({ src, crop, type, quality }) => {
        const img = new Image();
        img.src = src;
        await img.decode();
        const c = document.createElement('canvas');
        c.width = crop[2]; c.height = crop[3];
        c.getContext('2d').drawImage(img, crop[0], crop[1], crop[2], crop[3], 0, 0, crop[2], crop[3]);
        return c.toDataURL(type, quality);
      }, { src: 'data:image/png;base64,' + png.toString('base64'), crop, type: o.type, quality: o.quality });
      const buf = Buffer.from(dataUrl.split(',')[1], 'base64');
      fs.writeFileSync(path.join(out, o.name), buf);
      total += buf.length;
      console.log(`${o.name}: ${buf.length} bytes`);
    }
  }
  await browser.close();
  console.log(`total: ${total} bytes`);
})().catch((e) => { console.error(e); process.exit(1); });
