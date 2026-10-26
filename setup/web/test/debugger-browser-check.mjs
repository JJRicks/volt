// Optional QA for dist/debugger/ using an already-installed Playwright/Chrome.
// No browser download, USB device, phone or real network request.
// PLAYWRIGHT_MODULE=/abs/playwright/index.mjs CHROME_PATH=/.../Chrome QA_DIR=/abs/out node test/debugger-browser-check.mjs
import { readFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
const root = resolve('dist/debugger'), headers = JSON.parse(await readFile(join(root, 'headers.json'), 'utf8'));
const files = ['index.html', 'app.js', 'styles.css', 'saira_regular.ttf', 'saira_semibold.ttf', 'saira_bold.ttf'];
const types = { html: 'text/html', css: 'text/css', js: 'text/javascript', ttf: 'font/ttf' };
const widths = [1280, 1000, 768, 390, 344, 320];
const panels = ['problem-panel', 'ready-panel', 'connect-panel', 'record-panel', 'recording-panel', 'review-panel', 'done-panel', 'recovery'];
const pageErrors = [], submitted = [], blocked = [];
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH, headless: true });
async function open(width, hostname = 'volt.jjricks.com') {
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  await context.addInitScript(() => { navigator.usb.requestDevice = async () => { throw new DOMException('No device selected', 'NotFoundError'); }; });
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.href === 'https://volt.jjricks.com/debugger/submit') { submitted.push(route.request().method()); await route.fulfill({ status: 201, contentType: 'application/json', body: '{"id":"0a1b2c3d"}' }); return; }
    const name = url.pathname === '/debugger/' ? 'index.html' : url.pathname.slice('/debugger/'.length);
    if (url.hostname !== hostname || !files.includes(name)) { blocked.push(url.href); await route.abort(); return; }
    await route.fulfill({ status: 200, headers: { ...headers, 'Content-Type': types[name.split('.').pop()] }, body: await readFile(join(root, name)) });
  });
  const page = await context.newPage(); page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(`https://${hostname}/debugger/`); await page.waitForFunction(() => document.querySelector('#model').options.length > 1);
  return { context, page };
}
// Representative kept rows, including the longest unbroken line the filter allows.
const sample = () => {
  const rows = [['08:00:04.000', 'Send write command: [55 aa 00 6d 03 04 02 01 da fe fd]', '0x6d Line lights color = 1', false],
    ['08:00:04.500', '[note] now switching to rainbow mode', '', true], ['08:00:05.000', 'Connect success ([scooter])', '', false],
    ['08:00:06.000', `[Exo] ${'x'.repeat(500)}`, '', false]];
  for (const id of ['live-rows', 'review-rows']) {
    const list = document.getElementById(id);
    list.replaceChildren(...rows.map(([time, text, reading, note]) => {
      const li = document.createElement('li'); if (note) li.className = 'note';
      const t = document.createElement('time'); t.textContent = time; const c = document.createElement('code'); c.textContent = text; li.append(t, c);
      if (reading) { const r = document.createElement('span'); r.className = 'reading'; r.textContent = reading; li.append(r); }
      if (id === 'review-rows') { const b = document.createElement('button'); b.className = 'remove'; b.textContent = 'Delete'; li.append(b); }
      return li;
    }));
  }
  document.getElementById('live-empty').hidden = true;
  for (const [id, text] of [['sum-model', 'UT5 Max'], ['sum-description', 'My RGB lights can only be changed in the NAVEE app — I’ll cycle through every light mode and color.'],
    ['sum-contact', 'u/rider'], ['sum-navee', '2.1.9'], ['sum-android', '16'], ['sum-phone', 'Pixel 9'], ['sum-recording', '1 min 15 s, started 2027-01-15 08:00:00 UTC'], ['reference', '0a1b2c3d']])
    document.getElementById(id).textContent = text;
};
try {
  await mkdir(process.env.QA_DIR, { recursive: true });
  for (const width of widths) {
    const { context, page } = await open(width);
    await page.evaluate(() => document.fonts.ready);
    assert.equal(await page.evaluate(() => [...document.fonts].every(font => font.status === 'loaded')), true, `Bundled Saira at ${width}`);
    await page.keyboard.press('Tab');
    assert.equal(await page.locator('.skip').evaluate(element => element.matches(':focus-visible') && element.getBoundingClientRect().top >= 0), true);
    assert.equal(await page.locator('#environment').isVisible(), false);
    assert.equal(await page.locator('#problem-next').isDisabled(), true);
    await page.locator('#model').selectOption('UT5 Max');
    await page.locator('#description').fill('My RGB lights can only be changed in the NAVEE app.');
    assert.equal(await page.locator('#problem-next').isEnabled(), true);
    await page.locator('#description').focus();
    assert.equal(await page.locator('#description').evaluate(element => getComputedStyle(element).outlineWidth), '3px');
    if ([1280, 390, 320].includes(width)) await page.screenshot({ path: join(process.env.QA_DIR, `problem-${width}.png`), fullPage: true });
    await page.locator('#problem-next').click(); await page.locator('#ready-next').click();
    assert.equal(await page.locator('#connect').isDisabled(), true);
    await page.locator('#usb-consent').check(); assert.equal(await page.locator('#connect').isEnabled(), true);
    await page.locator('#connect').click();
    await page.waitForFunction(() => !document.querySelector('#error').hidden);
    assert.match(await page.locator('#error').innerText(), /No phone selected/);
    assert.equal(await page.locator('#record-panel').isVisible(), false);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `connect width ${width}`);
    await page.evaluate(sample);
    for (const id of panels) {
      await page.evaluate(id => {
        for (const panel of document.querySelectorAll('.panel')) panel.hidden = panel.id !== id;
        document.querySelector('#error').hidden = true;
      }, id);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${id} width ${width}`);
      for (const detail of await page.locator(`#${id} details`).all()) {
        await detail.locator('summary').focus(); await page.keyboard.press('Enter');
        assert.equal(await detail.evaluate(element => element.open), true);
      }
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${id} expanded width ${width}`);
      if ([1280, 390, 320].includes(width)) await page.screenshot({ path: join(process.env.QA_DIR, `${id}-${width}.png`), fullPage: true });
    }
    // Rendered text/background contrast for small and muted copy.
    assert.equal(await page.evaluate(() => {
      for (const panel of document.querySelectorAll('.panel')) panel.hidden = !['review-panel'].includes(panel.id);
      const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(Number).map(n => n / 255)
        .map(n => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4).reduce((sum, n, i) => sum + n * [.2126, .7152, .0722][i], 0);
      return ['#send', '#review-panel .fine', '.summary dt', '.log time', '.log .reading', '.log code', '.counts', '.consent', '.steps li'].every(selector => {
        const element = document.querySelector(selector), style = getComputedStyle(element);
        let background = element;
        while (background && getComputedStyle(background).backgroundColor === 'rgba(0, 0, 0, 0)') background = background.parentElement;
        const a = luminance(style.color), b = luminance(getComputedStyle(background).backgroundColor);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5;
      });
    }), true, `Text contrast at ${width}`);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.evaluate(() => { for (const panel of document.querySelectorAll('.panel')) panel.hidden = panel.id !== 'recording-panel'; });
    assert.equal(await page.locator('.live-dot').evaluate(element => getComputedStyle(element).animationName), 'none');
    if (width === 1280) {
      // CSP: only the submit URL is reachable; no other script or connection runs.
      const result = await page.evaluate(async () => {
        const script = document.createElement('script'); script.src = './unreviewed.js'; document.head.append(script);
        const attempt = async url => { try { await fetch(url, { method: 'POST', body: '{}' }); return 'sent'; } catch { return 'blocked'; } };
        return [await attempt('https://volt.jjricks.com/debugger/other'), await attempt('https://example.com/x'), await attempt('https://volt.jjricks.com/debugger/submit')];
      });
      await page.waitForTimeout(100);
      assert.deepEqual(result, ['blocked', 'blocked', 'sent']);
      assert.deepEqual(submitted, ['POST']);
      assert(!blocked.some(url => /unreviewed|other|example/.test(url)), 'CSP blocked requests before the network');
    }
    await context.close();
  }
  const away = await open(1000, 'pairscooter.jjricks.com');
  assert.match(await away.page.locator('#environment').innerText(), /volt\.jjricks\.com\/debugger/);
  await away.context.close();
  const swContext = await browser.newContext();
  await swContext.addInitScript(() => Object.defineProperty(navigator.serviceWorker, 'controller', { get: () => ({}) }));
  await swContext.route('**/*', async route => {
    const url = new URL(route.request().url()), name = url.pathname === '/debugger/' ? 'index.html' : url.pathname.slice('/debugger/'.length);
    if (!files.includes(name)) { await route.abort(); return; }
    await route.fulfill({ status: 200, headers: { ...headers, 'Content-Type': types[name.split('.').pop()] }, body: await readFile(join(root, name)) });
  });
  const swPage = await swContext.newPage(); await swPage.goto('https://volt.jjricks.com/debugger/');
  assert.match(await swPage.locator('#environment').innerText(), /service worker/);
  await swContext.close();
  assert.deepEqual(pageErrors, []);
  console.log(JSON.stringify({ widths, allPanelsFit: true, bundledFontsLoaded: true, textContrast: true, keyboardFocusVisible: true, reducedMotionRespected: true,
    usbConsentAndCancel: true, onlySubmitReachable: true, unreviewedScriptBlocked: true, otherHostRefused: true, serviceWorkerBlocked: true, pageErrors, screenshots: process.env.QA_DIR }, null, 2));
} finally { await browser.close(); }
