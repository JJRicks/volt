// Optional QA for dist/debugger/ using an already-installed Playwright/Chrome.
// No browser download, USB device, phone or real network request.
// PLAYWRIGHT_MODULE=/abs/playwright/index.mjs CHROME_PATH=/.../Chrome QA_DIR=/abs/out node test/debugger-browser-check.mjs
import { readFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
import { COMMANDS } from '../src/navee-commands.js';
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
  const rows = [['08:00:04.000', 'Send write command: [55 aa 00 6d 03 04 02 01 da fe fd] (ok)', ['known', 'Known', 'Line lights color → Red'], false],
    ['08:00:04.500', '[note] now switching to rainbow mode', null, true], ['08:00:05.000', 'Send write command: [55 aa 00 a4 01 01 a5 fe fd] (ok)', ['new', 'New to Volt', '0xa4 = 1'], false],
    ['08:00:05.000', '[you said] 0xa4 = 1: rainbow mode', null, 'note answer'], ['08:00:05.500', 'Send write command: [55 aa 00 53 01 2d 80 fe fd] (ok)', ['new-value', 'New value', 'Energy recovery = 45'], false],
    ['08:00:05.800', 'Send read command: [55 aa 00 70 6f fe fd] (ok)', ['read', 'Read', 'Read vehicle status'], false], ['08:00:06.000', 'Connect success ([scooter])', null, false],
    ['08:00:06.000', `[Exo] ${'x'.repeat(500)}`, null, false]];
  for (const id of ['live-rows', 'review-rows']) {
    const list = document.getElementById(id);
    list.replaceChildren(...rows.map(([time, text, reading, note]) => {
      const li = document.createElement('li'); if (note) li.className = note === true ? 'note' : note;
      const t = document.createElement('time'); t.textContent = time; const c = document.createElement('code'); c.textContent = text; li.append(t, c);
      if (reading) {
        const r = document.createElement('span'), tag = document.createElement('span'), words = document.createElement('span');
        r.className = 'reading'; tag.className = `tag ${reading[0]}`; tag.textContent = reading[1]; r.append(tag, words); li.append(r);
        words.append(...reading[2].split(/(0x[0-9a-f:]+)/).map((part, i) => { if (!(i % 2)) return part; const code = document.createElement('code'); code.textContent = part; return code; }));
      }
      if (id === 'review-rows') { const b = document.createElement('button'); b.className = 'remove'; b.textContent = 'Delete'; li.append(b); }
      return li;
    }));
  }
  document.getElementById('live-empty').hidden = true;
  // Prompt card, review summary, unanswered list and "What Volt knows" highlights, as the page renders them.
  document.getElementById('ask').hidden = false;
  document.getElementById('ask-tag').className = 'tag new'; document.getElementById('ask-tag').textContent = 'New to Volt';
  document.getElementById('ask-reading').textContent = '0x6d:04:07 = 1'; document.getElementById('ask-time').textContent = 'at 08:00:07.000';
  document.getElementById('ask-queue').textContent = '1 more waiting.';
  document.getElementById('known-summary').append(...'18 known commands · 2 new: 0xa4 (sent 3 times), 0x6d:04:07 (sent once) · 1 new value: Energy recovery = 45 · 4 routine reads'.split(/(0x[0-9a-f:]+)/)
    .map((part, i) => { if (!(i % 2)) return part; const code = document.createElement('code'); code.textContent = part; return code; }));
  document.getElementById('summary-sent').textContent = 'New to Volt: 0xa4 x3 [you said: rainbow mode], 0x6d:04:07 x1; New values: 0x53 Energy recovery=45 x1; Known: 18 commands, 4 reads';
  document.getElementById('summary-line').hidden = false; document.getElementById('unknowns').hidden = false;
  document.getElementById('unknown-list').replaceChildren(...[['new-value', 'New value', 'Energy recovery = 45', 'at 08:00:05.500, sent once'], ['new', 'New to Volt', '0x6d:04:07 = 1', 'at 08:00:07.000, sent once']].map(([status, label, reading, when], i) => {
    const li = document.createElement('li'), form = document.createElement('form'); form.className = 'unknown';
    const what = document.createElement('p'); what.className = 'ask-what';
    const tag = document.createElement('span'); tag.className = `tag ${status}`; tag.textContent = label;
    const code = document.createElement('code'); code.textContent = reading; const at = document.createElement('span'); at.textContent = when;
    what.append(tag, ' ', code, ' ', at);
    const row = document.createElement('div'); row.className = 'marker-row';
    const input = document.createElement('input'); input.id = `unknown-${i}`; input.placeholder = 'What you changed';
    const save = document.createElement('button'); save.className = 'secondary'; save.textContent = 'Save'; row.append(input, save);
    form.append(what, row); li.append(form); return li;
  }));
  for (const panel of ['knows-live', 'knows-review']) {
    for (const item of document.querySelectorAll(`#${panel} li`)) {
      const n = { 'Line lights color': 1, 'Energy recovery': 1, 'Vehicle status': 4, 'Cruise control': 3 }[item.children[0].textContent];
      if (n) { item.classList.add('seen'); item.lastChild.hidden = false; item.lastChild.textContent = n === 1 ? 'Seen once' : `Seen ${n} times`; }
    }
    document.getElementById(`${panel}-seen`).textContent = '· 4 seen in this capture';
  }
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
      if (['recording-panel', 'review-panel'].includes(id) && [1280, 390].includes(width)) {
        await page.waitForTimeout(300); // the prompt card's fade-in restarts when its panel is shown
        await page.screenshot({ path: join(process.env.QA_DIR, `${id}-closed-${width}.png`), fullPage: true });
        const knows = id === 'recording-panel' ? '#knows-live' : '#knows-review';
        assert.equal(await page.locator(knows).evaluate(element => element.open), false, 'What Volt knows starts collapsed');
        assert.equal(await page.locator(`${knows} li`).count(), Object.keys(COMMANDS).length);
      }
      for (const detail of await page.locator(`#${id} details`).all()) {
        await detail.locator('summary').focus(); await page.keyboard.press('Enter');
        assert.equal(await detail.evaluate(element => element.open), true);
      }
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${id} expanded width ${width}`);
      if ([1280, 390, 320].includes(width)) await page.screenshot({ path: join(process.env.QA_DIR, `${id}-${width}.png`), fullPage: true });
      if (id === 'review-panel' && [1280, 390].includes(width)) await page.locator('#knows-review').screenshot({ path: join(process.env.QA_DIR, `knows-open-${width}.png`) });
    }
    // The prompt card is reachable by keyboard: Tab from its input reaches Save, then Skip.
    await page.evaluate(() => { for (const panel of document.querySelectorAll('.panel')) panel.hidden = panel.id !== 'recording-panel'; });
    await page.locator('#ask-text').focus(); await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(() => document.activeElement.id), 'ask-save');
    await page.keyboard.press('Tab'); assert.equal(await page.evaluate(() => document.activeElement.id), 'ask-skip');
    // Rendered text/background contrast for small and muted copy.
    assert.equal(await page.evaluate(() => {
      for (const panel of document.querySelectorAll('.panel')) panel.hidden = !['review-panel'].includes(panel.id);
      const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(Number).map(n => n / 255)
        .map(n => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4).reduce((sum, n, i) => sum + n * [.2126, .7152, .0722][i], 0);
      return ['#send', '#review-panel .fine', '.summary dt', '.log time', '.log .reading', '.log code', '.counts', '.consent', '.steps li',
        '.tag.known', '.tag.new', '.tag.new-value', '.tag.read', '.ask-title', '.ask-what', '.known-summary', '.unknowns .fine', '.knows-values', '.knows-list code', '.seen-count', '.knows h3'].every(selector => {
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
    assert.equal(await page.locator('#ask').evaluate(element => getComputedStyle(element).animationName), 'none');
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
    usbConsentAndCancel: true, promptCardKeyboard: true, knowsPanelCollapsed: true, onlySubmitReachable: true, unreviewedScriptBlocked: true, otherHostRefused: true, serviceWorkerBlocked: true, pageErrors, screenshots: process.env.QA_DIR }, null, 2));
} finally { await browser.close(); }
