// Render the beginner's Getting Started guide to PDF via headless Chrome.
//
// Unlike the brochure, this guide is NOT laid out as fixed-size pages: it flows
// naturally and lets Chrome paginate, with page-break-inside:avoid on the blocks
// that must not be split. That means there is nothing to clip, so this script
// only has to wait for webfonts before printing.
//
// Usage:  node docs/guide/build-guide-pdf.js

const fs   = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const HTML   = path.resolve(__dirname, 'arima-getting-started.html');
const PDF    = path.resolve(__dirname, 'Arima-Notebooks-Getting-Started.pdf');

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

    // Inter and JetBrains Mono come from Google Fonts; printing before they land
    // silently falls back to a system face and changes every measurement.
    await page.evaluate(() => document.fonts.ready);

    await page.pdf({
      path: PDF,
      format: 'A4',
      printBackground: true,
      preferCSSPageSize: true,
    });

    const kb = (fs.statSync(PDF).size / 1024).toFixed(0);
    console.log(`  wrote ${path.basename(PDF)}  (${kb} KB)`);
  } finally {
    await browser.close();
  }
})();
