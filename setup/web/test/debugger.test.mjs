// Exercise the debugger page's event handlers with a small DOM and session,
// transport and fetch doubles. No real USB, phone command or network request.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { webcrypto } from 'node:crypto';
import { build } from 'esbuild';
import { frame, line, START } from './navee-fixtures.mjs';

const entry = fileURLToPath(new URL('../src/debugger.js', import.meta.url));
const html = await readFile(new URL('../debugger.html', import.meta.url), 'utf8');
const bundled = await build({ entryPoints: [entry], bundle: true, write: false, format: 'iife', plugins: [{
  name: 'ui-fixture', setup(build) {
    build.onResolve({ filter: /^\.\/(capture|transport|debug-capture)\.js$/ }, args => ({ path: args.path, namespace: 'fixture' }));
    build.onLoad({ filter: /./, namespace: 'fixture' }, args => ({ resolveDir: dirname(entry), contents:
      args.path.includes('debug-capture') ? "import { LogRecorder } from './navee-log.js'; window.fixture.LogRecorder = LogRecorder; export const RECORD_MS = 540000; export const DebugCaptureSession = window.fixture.Session;"
      : args.path.includes('capture') ? "export const readJournal = () => window.fixture.pending ? { version: 1, token: 'a'.repeat(64), serial: 'same-phone', original: 'WARN', stage: 'armed' } : null;"
      : "import { SetupError } from './protocol.js'; window.fixture.SetupError = SetupError; export const connectPhone = (...args) => window.fixture.connect(...args);" }));
  }
}] });
const LOGS = [line(`Send write command: ${frame(0x52, [1])}`, '1800000004.000'), line('getUserInfo {"nickname":"Rider"}', '1800000004.100'),
  line('Write success: [55 aa 00 63 06 01 01 01 02 03 04 6f fe fd]', '1800000004.200'), line('Connect success (C0FFEE123456)', '1800000005.000')].join('\n');

function node(id = '', tag = 'div') {
  return { id, tag, hidden: false, disabled: false, checked: false, open: false, value: '', textContent: '', className: '', type: '',
    children: [], attributes: {}, listeners: {}, scrollTop: 0, scrollHeight: 100, clientHeight: 100,
    classList: { toggle() {}, remove() {}, add() {} },
    addEventListener(name, fn) { this.listeners[name] = fn; },
    setAttribute(key, value) { this.attributes[key] = value; }, removeAttribute(key) { delete this.attributes[key]; },
    append(...nodes) { this.children.push(...nodes); }, replaceChildren(...nodes) { this.children = nodes; },
    focus() {}, click() { this.listeners.click?.(); } };
}
function fixture({ mode = 'success', hostname = 'volt.jjricks.com', reply = { status: 201, body: { id: '0a1b2c3d' } } } = {}) {
  const elements = new Map(), phones = [], sessions = [], events = [], fetches = [], blobs = [], windowEvents = {};
  for (const [, id] of html.matchAll(/id="([^"]+)"/g)) elements.set(id, node(id));
  const fixture = { mode, pending: false, nextSerial: 'same-phone', reply, duringRecording: null,
    async connect(_waiting, disconnect) {
      const phone = { serial: this.nextSerial, name: 'Fixture', disconnect, closed: false,
        async close(forget) { assert.equal(forget, true); this.closed = true; events.push('close'); this.disconnect(); }, async run() { throw new Error('unused'); } };
      phones.push(phone); return phone;
    },
    Session: class {
      constructor({ serial, onRecord, update }) { Object.assign(this, { serial, onRecord, update, safe: false, result: null, busy: false, stopped: false }); sessions.push(this); }
      async begin() {
        this.busy = true; this.update('preparing'); this.update('recording');
        this.recorder = new fixture.LogRecorder(START); this.recorder.ingest(LOGS); this.onRecord(this.recorder);
        await fixture.duringRecording?.(this);
        this.update('cleaning'); this.busy = false;
        this.safe = fixture.mode !== 'unsafe-failure'; fixture.pending = !this.safe;
        if (fixture.mode !== 'success') throw new fixture.SetupError('cleanup', 'Fixture capture failure');
        this.result = { recorder: this.recorder, facts: { naveeVersion: '2.1.9', android: '16', phone: 'Pixel 9' }, reason: 'stopped', startedAt: '2027-01-15T08:00:00.000Z', durationS: 75 };
        events.push('cleaned');
      }
      capture() { assert.equal(this.safe, true); assert(this.result); return this.result; }
      addNote(text) { return this.recorder.addNote('1800000004.500', text); }
      async recover() { this.safe = true; fixture.pending = false; }
      stop() { this.stopped = true; }
      cancel() {} forgetResult() { this.result = null; }
    }
  };
  const scope = { fixture, console, location: { hostname, pathname: '/debugger/' }, crypto: webcrypto, TextEncoder, isSecureContext: true,
    navigator: { usb: {} }, localStorage: { getItem() { return null; } }, performance: { now: () => 0 },
    Blob: class { constructor(parts) { blobs.push(parts.join('')); } },
    URL: { createObjectURL() { events.push('download'); return 'blob:fixture'; }, revokeObjectURL() {} },
    setTimeout: () => 0, setInterval: () => 1, clearInterval() {},
    fetch: async (url, options) => {
      fetches.push({ url, options });
      if (fixture.reply instanceof Error) throw fixture.reply;
      return { status: fixture.reply.status, json: async () => { if (typeof fixture.reply.body === 'string') throw new SyntaxError('not json'); return fixture.reply.body; } };
    },
    addEventListener: (name, fn) => { windowEvents[name] = fn; },
    document: { getElementById: id => elements.get(id), querySelectorAll: () => [], createElement: tag => node('', tag) }
  };
  scope.window = scope; runInNewContext(bundled.outputFiles[0].text, scope);
  const $ = id => elements.get(id);
  const click = id => $(id).listeners.click();
  const type = (id, value) => { $(id).value = value; $(id).listeners.input?.(); };
  async function toRecord() {
    type('model', 'UT5 Max'); type('description', 'RGB lights only change in NAVEE.'); type('contact', 'u/rider');
    click('problem-next'); click('ready-next');
    $('usb-consent').checked = true; $('usb-consent').listeners.change(); await click('connect');
  }
  async function record() { await toRecord(); $('record-consent').checked = true; await click('start'); }
  const visible = () => [...elements.values()].filter(e => e.id.endsWith('-panel') || e.id === 'recovery').filter(e => !e.hidden).map(e => e.id);
  const rowsOf = id => $(id).children.map(li => li.children[1].textContent);
  return { fixture, elements, phones, sessions, events, fetches, blobs, windowEvents, $, click, type, toRecord, record, visible, rowsOf };
}

test('the problem step needs a model and description; the model list is Volt’s', () => {
  const f = fixture();
  assert.deepEqual(f.$('model').children.map(o => o.value), ['', 'UT5 Max', 'N65i', 'ST3 Pro', 'GT5 Max', 'UT5 Ultra X', 'XT5 Pro', 'Other / not sure']);
  assert.deepEqual(f.visible(), ['problem-panel']);
  assert.equal(f.$('problem-next').disabled, true);
  f.type('description', 'Lights'); assert.equal(f.$('problem-next').disabled, true);
  f.type('model', 'Other / not sure'); assert.equal(f.$('problem-next').disabled, false);
  f.type('description', '   '); assert.equal(f.$('problem-next').disabled, true);
  f.type('description', 'x'.repeat(4001)); assert.equal(f.$('problem-next').disabled, true);
  f.type('description', 'ok'); f.click('problem-next'); assert.deepEqual(f.visible(), ['ready-panel']);
  f.click('ready-next'); assert.deepEqual(f.visible(), ['connect-panel']);
  assert.equal(f.$('connect').disabled, true);
});
test('verified recording closes USB, then sends exactly the reviewed lines', async () => {
  const f = fixture();
  let live;
  f.fixture.duringRecording = () => { live = f.rowsOf('live-rows'); };
  await f.record();
  // Only kept, redacted rows were shown live; PIN frame and account row hidden.
  assert.deepEqual(live, [`Send write command: ${frame(0x52, [1])}`, 'Connect success ([scooter])']);
  assert.equal(f.$('hidden-count').textContent, '2 other NAVEE log lines were hidden');
  assert.deepEqual(f.events, ['cleaned', 'close']);
  assert.deepEqual(f.visible(), ['review-panel']);
  assert.equal(f.$('sum-navee').textContent, '2.1.9'); assert.equal(f.$('sum-phone').textContent, 'Pixel 9');
  assert.equal(f.$('review-rows').children[0].children[2].textContent, '0x52 Cruise control = 1');
  assert.equal(f.$('send').disabled, true);
  await f.click('send'); assert.equal(f.fetches.length, 0);
  // Delete the second line; it must not be sent.
  f.$('review-rows').children[1].children.at(-1).click();
  assert.deepEqual(f.rowsOf('review-rows'), [`Send write command: ${frame(0x52, [1])}`]);
  f.$('send-consent').checked = true; f.$('send-consent').listeners.change();
  assert.equal(f.$('send').disabled, false);
  await f.click('send');
  assert.equal(f.fetches.length, 1);
  const { url, options } = f.fetches[0];
  assert.equal(url, 'https://volt.jjricks.com/debugger/submit');
  assert.deepEqual(JSON.parse(JSON.stringify({ ...options, body: null })), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: null, credentials: 'omit', cache: 'no-store', redirect: 'error' });
  assert.deepEqual(JSON.parse(options.body), { format: 'volt-navee-capture-v1', model: 'UT5 Max', description: 'RGB lights only change in NAVEE.', contact: 'u/rider',
    navee_version: '2.1.9', android: '16', phone: 'Pixel 9', started_at: '2027-01-15T08:00:00.000Z', duration_s: 75, hidden_rows: 2,
    rows: [`08:00:04.000 Send write command: ${frame(0x52, [1])}`] });
  assert.deepEqual(f.visible(), ['done-panel']);
  assert.equal(f.$('reference').textContent, '0a1b2c3d');
  await f.click('send'); assert.equal(f.fetches.length, 1);
  f.click('finish');
  assert.deepEqual(f.visible(), ['problem-panel']); assert.equal(f.$('description').value, '');
});
test('notes typed during recording join the capture', async () => {
  const f = fixture();
  f.fixture.duringRecording = () => {
    f.$('marker').value = 'now rainbow mode';
    f.$('marker-form').listeners.submit({ preventDefault() {} });
    f.$('marker').value = 'mail me@example.com';
    f.$('marker-form').listeners.submit({ preventDefault() {} });
    assert.match(f.$('error').textContent, /email/);
  };
  await f.record();
  assert(f.rowsOf('review-rows').includes('[note] now rainbow mode'));
  assert(!f.rowsOf('review-rows').some(row => row.includes('example.com')));
});
test('rate limits, server refusals and network failures keep the capture; raw errors never show', async () => {
  const f = fixture({ reply: { status: 429, body: { error: 'rate limited' } } });
  await f.record();
  f.$('send-consent').checked = true; f.$('send-consent').listeners.change();
  await f.click('send');
  assert.match(f.$('error').textContent, /Wait a few minutes/); assert.deepEqual(f.visible(), ['review-panel']);
  f.fixture.reply = { status: 400, body: { error: 'row 2 contains a MAC address' } };
  await f.click('send');
  assert.match(f.$('error').textContent, /didn’t accept.*row 2 contains a MAC address/);
  f.fixture.reply = { status: 400, body: { error: 'bad\u0000<script>' } };
  await f.click('send');
  assert.doesNotMatch(f.$('error').textContent, /script|bad/);
  f.fixture.reply = { status: 503, body: 'Gateway HTML' };
  await f.click('send');
  assert.match(f.$('error').textContent, /server had a problem/);
  f.fixture.reply = new TypeError('Failed to fetch secret-detail');
  await f.click('send');
  assert.match(f.$('error').textContent, /Couldn’t reach/); assert.doesNotMatch(f.$('error').textContent, /secret/);
  assert.equal(f.$('send').disabled, false);
  f.fixture.reply = { status: 201, body: { id: '0a1b2c3d' } };
  await f.click('send');
  assert.equal(f.fetches.length, 6); assert.deepEqual(f.visible(), ['done-panel']);
});
test('a local copy contains exactly the reviewed body', async () => {
  const f = fixture();
  await f.record();
  await f.click('download-copy');
  assert.deepEqual(f.events, ['cleaned', 'close', 'download']);
  const copy = JSON.parse(f.blobs[0]);
  assert.equal(copy.format, 'volt-navee-capture-v1'); assert.equal(copy.rows.length, 2);
  assert(!f.blobs[0].includes('C0FFEE123456') && !f.blobs[0].includes('Rider') && !f.blobs[0].includes('55 aa 00 63'));
});
test('unverified cleanup keeps USB for recovery; verified recovery closes it', async () => {
  const f = fixture({ mode: 'unsafe-failure' });
  await f.record();
  assert.deepEqual(f.visible(), ['recovery']); assert.equal(f.phones[0].closed, false);
  assert.equal(f.rowsOf('live-rows').length, 0);
  await f.click('recover');
  assert.equal(f.phones[0].closed, true); assert.equal(f.fixture.pending, false);
  assert.deepEqual(f.visible(), ['connect-panel']);
});
test('a verified failure closes USB and resets consent', async () => {
  const f = fixture({ mode: 'safe-failure' });
  await f.record();
  assert.equal(f.phones[0].closed, true); assert.deepEqual(f.visible(), ['connect-panel']);
  assert.equal(f.$('usb-consent').checked, false); assert.match(f.$('error').textContent, /Fixture capture failure/);
});
test('the page refuses to record away from volt.jjricks.com', async () => {
  const f = fixture({ hostname: 'pairscooter.jjricks.com' });
  assert.match(f.$('environment').textContent, /volt\.jjricks\.com\/debugger/);
  await f.toRecord();
  assert.equal(f.phones.length, 0); assert.equal(f.$('connect').disabled, true);
});
test('leaving with an unsent capture asks first; page exit discards it', async () => {
  const f = fixture();
  await f.record();
  const event = { preventDefault() { this.prevented = true; } };
  f.windowEvents.beforeunload(event); assert.equal(event.prevented, true);
  f.windowEvents.pagehide();
  const after = { preventDefault() { this.prevented = true; } };
  f.windowEvents.beforeunload(after); assert.equal(after.prevented, undefined);
});
