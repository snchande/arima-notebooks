// Render the Arima Notebooks brochure HTML to PDF via headless Chrome.
//
// The brochure is print-designed: @page is A4 with zero margin and every
// `.page` section is a fixed 210x297mm block with `overflow:hidden`. That means
// content which does not fit is silently CLIPPED rather than reflowed onto a new
// page, so this script measures every page before printing and fails loudly if
// one has overflowed. Mermaid diagrams are rendered from the CDN at load time,
// hence waitUntil:'networkidle0'.
//
// Usage:  node docs/brochure/build-brochure-pdf.js

const fs   = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const HTML   = path.resolve(__dirname, 'arima-brochure.html');
const PDF    = path.resolve(__dirname, 'arima-brochure.pdf');

// A4 height in px at 96dpi, plus a tolerance for sub-pixel layout rounding.
const A4_PX     = 297 / 25.4 * 96;
const TOLERANCE = 4;

(async () => {
  if (!fs.existsSync(CHROME)) {
    console.error(`Chrome not found at ${CHROME}`);
    process.exit(1);
  }

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
  });

  try {
    const page = await browser.newPage();
    await page.goto('file:///' + HTML.replace(/\\/g, '/'), { waitUntil: 'networkidle0' });

    // Give mermaid a moment to swap its <div class="mermaid"> blocks for SVG.
    await page.waitForFunction(
      () => document.querySelectorAll('.mermaid svg').length ===
            document.querySelectorAll('.mermaid').length,
      { timeout: 30000 }
    ).catch(() => console.warn('  ! mermaid did not finish rendering — diagrams may be missing'));

    // Measure each page for clipped content.
    const pages = await page.evaluate(() => Array.from(document.querySelectorAll('.page')).map((el, i) => ({
      index: i + 1,
      label: el.querySelector('.page-foot > div:last-child')?.textContent.trim() || '(no footer)',
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    })));

    console.log(`\nPages: ${pages.length}`);
    let overflowed = 0;
    for (const p of pages) {
      const over = p.scrollHeight - p.clientHeight;
      const flag = over > TOLERANCE ? `OVERFLOW +${over}px` : 'ok';
      if (over > TOLERANCE) overflowed++;
      console.log(`  ${String(p.index).padStart(2)}  ${p.label.padEnd(28)} ${flag}`);
    }

    await page.pdf({
      path: PDF,
      format: 'A4',
      printBackground: true,
      preferCSSPageSize: true,   // honour the brochure's own @page A4/margin:0
    });
    await page.close();

    const kb = Math.round(fs.statSync(PDF).size / 1024);
    console.log(`\nWrote ${path.basename(PDF)} (${kb} KB)`);

    if (overflowed > 0) {
      console.error(`\n${overflowed} page(s) overflowed and will be clipped in the PDF.`);
      process.exit(2);
    }
  } finally {
    await browser.close();
  }
})().catch(e => { console.error(e); process.exit(1); });
