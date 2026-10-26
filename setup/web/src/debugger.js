import { readJournal } from './capture.js';
import { DebugCaptureSession, RECORD_MS } from './debug-capture.js';
import { LIMITS, MODELS, OTHER_MODEL, submission } from './navee-log.js';
import { PROPERTY, PACKAGE, SetupError, quote, randomToken } from './protocol.js';
import { connectPhone } from './transport.js';

// The only network destination; CSP connect-src allows exactly this URL.
const SUBMIT_URL = 'https://volt.jjricks.com/debugger/submit';
// Runs only on its own host (or local development), never beside the pairing
// helper, whose origin shares the same recovery journal key and USB grants.
const HOSTS = ['volt.jjricks.com', 'localhost', '127.0.0.1', '[::1]'];
const $ = id => document.getElementById(id);
// capture: the verified recording, held only in page memory until Done/Discard.
let phone = null, session = null, busy = false, closing = false, capture = null, sentId = null, ticker = 0, rendered = [];
const storage = {
  getItem: key => localStorage.getItem(key),
  setItem: (key, value) => localStorage.setItem(key, value),
  removeItem: key => localStorage.removeItem(key)
};
const PANELS = ['problem-panel', 'ready-panel', 'connect-panel', 'record-panel', 'recording-panel', 'review-panel', 'done-panel', 'recovery'];
const plural = (n, one, many) => `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
function message(id, text) { $(id).textContent = text; $(id).hidden = !text; }
function error(error) {
  message('notice', '');
  // USB, shell and library exceptions can contain phone data. Never render,
  // log, persist or transmit an arbitrary exception's message/stack.
  message('error', error instanceof SetupError ? error.message : 'This step could not finish. Check your USB connection and try again.');
  if (error instanceof SetupError && ['usb-busy', 'usb-access'].includes(error.code)) $('connection-help').open = true;
}
function panel(name, step, focus = true) {
  for (const id of PANELS) $(id).hidden = id !== name;
  for (const item of document.querySelectorAll('[data-step]')) {
    const n = Number(item.dataset.step);
    item.classList.toggle('complete', n < step);
    if (n === step) item.setAttribute('aria-current', 'step'); else item.removeAttribute('aria-current');
  }
  // Move keyboard and screen-reader focus to the new step's heading.
  const heading = focus && $(name).querySelector?.('h2');
  if (heading) { heading.setAttribute('tabindex', '-1'); heading.focus?.(); }
}
function pendingRecovery() {
  try { return !!readJournal(storage); }
  catch (e) { error(e); return true; }
}
function validateEnvironment() {
  const problem = !HOSTS.includes(location.hostname)
    ? 'The NAVEE debugger runs only at volt.jjricks.com/debugger/. Open it there to record.'
    : navigator.serviceWorker?.controller
    ? 'A service worker controls this page. For your privacy, recording is disabled. Use a separate regular browser profile (not Incognito or Guest); this page will not remove another app’s service worker.'
    : !window.isSecureContext || !navigator.usb
      ? 'Open this page in desktop Chrome or Edge over HTTPS. The USB connection this needs is unavailable in this browser.' : '';
  message('environment', problem);
  $('connect').disabled = !!problem || !$('usb-consent').checked || busy;
  $('recover').disabled = !!problem || busy;
  return !problem;
}
function details() {
  const description = $('description').value;
  $('description-count').textContent = `${description.length} / ${LIMITS.description}`;
  $('problem-next').disabled = !$('model').value || !description.trim() || description.length > LIMITS.description || $('contact').value.length > LIMITS.contact;
}
async function connect({ recovery = false } = {}) {
  if (phone) return phone;
  if (!validateEnvironment()) throw new SetupError('environment', 'This browser environment cannot safely run a recording.');
  let connected;
  connected = await connectPhone(name => message('notice', `Unlock ${name} and approve the USB debugging prompt. Leave “Always allow” unchecked.`), () => {
    // A deliberate close detaches this phone first.
    if (phone !== connected) return;
    phone = null;
    if (!closing) {
      session?.cancel(); session?.forgetResult(); clearLive();
      message('notice', '');
      error(new SetupError('disconnect', 'USB disconnected. If recording was running, reconnect the same phone to verify cleanup. The recording was discarded.'));
      if (!busy) panel(pendingRecovery() ? 'recovery' : 'connect-panel', pendingRecovery() ? 0 : 2);
    }
  });
  phone = connected;
  session = new DebugCaptureSession({ run: phone.run, serial: phone.serial, storage, onRecord: renderLive, update: state => {
    const recording = state === 'recording';
    $('recording-title').textContent = recording ? 'Recording. Use NAVEE now.' : state === 'cleaning' ? 'Restoring your phone’s settings.' : 'Getting the phone ready.';
    $('progress').textContent = recording ? 'Recording. Do the thing in NAVEE, then press Stop.' : state === 'cleaning' ? 'Closing NAVEE and restoring its log setting…' : 'Restarting NAVEE with temporary logging…';
    $('stop').disabled = !recording; $('marker').disabled = !recording; $('add-marker').disabled = !recording;
    if (recording) startTicker(); else if (state === 'cleaning') stopTicker();
  } });
  $('device-name').textContent = phone.name;
  message('notice', '');
  return phone;
}
async function action(work) {
  if (busy) return;
  busy = true; validateEnvironment(); message('error', ''); message('notice', '');
  try { await work(); } catch (e) { error(e); }
  finally { busy = false; validateEnvironment(); }
}
async function releasePhone() {
  closing = true;
  const connected = phone;
  phone = null;
  session?.forgetResult(); session = null;
  try { await connected?.close(true); }
  catch { message('notice', 'Disconnected. Remove this site’s USB permission in Chrome’s site settings.'); }
  finally { closing = false; }
}
async function closePhone() {
  await releasePhone();
  $('usb-consent').checked = false; $('record-consent').checked = false; $('start').disabled = true;
  panel(pendingRecovery() ? 'recovery' : 'connect-panel', pendingRecovery() ? 0 : 2);
}
// Countdown to the automatic stop, from the session's own deadline.
function startTicker() {
  stopTicker();
  const tick = () => {
    const left = session?.deadline ? Math.max(0, Math.ceil((session.deadline - performance.now()) / 1000)) : RECORD_MS / 1000;
    $('countdown').textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
    $('countdown').classList.toggle('low', left <= 60);
  };
  tick(); ticker = setInterval(tick, 500);
}
function stopTicker() { clearInterval(ticker); ticker = 0; }
function rowItem(row, removable) {
  const item = document.createElement('li');
  if (row.note) item.className = 'note';
  const time = document.createElement('time'); time.textContent = row.time;
  const text = document.createElement('code'); text.textContent = row.text;
  item.append(time, text);
  if (row.annotation) { const reading = document.createElement('span'); reading.className = 'reading'; reading.textContent = row.annotation; item.append(reading); }
  if (removable) {
    const remove = document.createElement('button');
    remove.className = 'remove'; remove.type = 'button'; remove.textContent = 'Delete';
    remove.setAttribute('aria-label', `Delete the line at ${row.time}`);
    remove.addEventListener('click', () => { capture?.recorder.remove(row.id); renderReview(); $('review-rows').focus?.(); });
    item.append(remove);
  }
  return item;
}
function counts(recorder) {
  $('kept-count').textContent = plural(recorder.rows.length, 'line kept', 'lines kept');
  $('hidden-count').textContent = `${plural(recorder.hidden, 'other NAVEE log line was', 'other NAVEE log lines were')} hidden` +
    (recorder.folded ? ` · ${plural(recorder.folded, 'routine read or confirmation', 'routine reads and confirmations')} folded` : '');
}
// Live list: kept, redacted rows only, newest at the bottom.
function renderLive(recorder) {
  const live = $('live'), list = $('live-rows'), ids = recorder.rows.map(row => row.id);
  const atBottom = live.scrollHeight - live.scrollTop - live.clientHeight < 48;
  if (rendered.length <= ids.length && rendered.every((id, i) => ids[i] === id)) {
    for (const row of recorder.rows.slice(rendered.length)) list.append(rowItem(row, false));
  } else list.replaceChildren(...recorder.rows.map(row => rowItem(row, false)));
  rendered = ids;
  $('live-empty').hidden = ids.length > 0;
  counts(recorder);
  if (atBottom) live.scrollTop = live.scrollHeight;
}
function clearLive() {
  rendered = []; $('live-rows').replaceChildren(); $('live-empty').hidden = false;
  $('kept-count').textContent = '0 lines kept'; $('hidden-count').textContent = '0 other NAVEE log lines hidden';
  $('countdown').textContent = `${RECORD_MS / 60_000}:00`; $('countdown').classList.remove('low');
}
const modelName = () => $('model').value || OTHER_MODEL;
function body() {
  const { recorder, facts, startedAt, durationS } = capture;
  return submission({ model: modelName(), description: $('description').value, contact: $('contact').value,
    naveeVersion: facts.naveeVersion, android: facts.android, phone: facts.phone, startedAt, durationS,
    hidden: Math.min(recorder.hidden + recorder.folded, 1_000_000), rows: recorder.rows });
}
function renderReview() {
  const { recorder, facts, startedAt, durationS } = capture;
  $('sum-model').textContent = modelName();
  $('sum-description').textContent = $('description').value.trim();
  $('sum-contact').textContent = $('contact').value.trim() || 'None given (the developer can’t reply)';
  $('sum-navee').textContent = facts.naveeVersion; $('sum-android').textContent = facts.android; $('sum-phone').textContent = facts.phone;
  $('sum-recording').textContent = `${Math.floor(durationS / 60)} min ${durationS % 60} s, started ${startedAt.slice(0, 19).replace('T', ' ')} UTC`;
  $('review-rows').replaceChildren(...recorder.rows.map(row => rowItem(row, true)));
  const navee = recorder.rows.filter(row => !row.note).length;
  $('review-counts').textContent = `${plural(recorder.rows.length, 'line', 'lines')} will be sent (times are UTC). “(ok)” means NAVEE confirmed the command was written. ${plural(recorder.hidden, 'other NAVEE log line was', 'other NAVEE log lines were')} hidden${recorder.folded ? ` and ${plural(recorder.folded, 'repeat', 'repeats')} folded` : ''}; only those counts are sent.`;
  if (!navee) message('notice', 'No NAVEE Bluetooth lines were kept, so there’s nothing to send. Check that NAVEE connects to your scooter while recording, then try again.');
  $('send').disabled = !navee || !$('send-consent').checked || !!sentId;
}
function showReview() {
  $('send-consent').checked = false; sentId = null;
  $('problem-next-label').textContent = 'Back to review';
  renderReview();
  panel('review-panel', 4);
}
function forgetCapture() {
  capture = null; sentId = null; clearLive();
  $('review-rows').replaceChildren(); $('send-consent').checked = false;
  $('problem-next-label').textContent = 'Next';
}
function downloadText(data, name, type) {
  const url = URL.createObjectURL(new Blob([data], { type }));
  const link = document.createElement('a'); link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
for (const id of ['model', 'description', 'contact']) $(id).addEventListener('input', details);
$('model').addEventListener('change', details);
$('problem-next').addEventListener('click', () => {
  details(); if ($('problem-next').disabled) return;
  if (capture) { message('notice', ''); showReview(); } else panel('ready-panel', 1);
});
$('ready-back').addEventListener('click', () => panel('problem-panel', 0));
$('ready-next').addEventListener('click', () => { message('error', ''); panel(pendingRecovery() ? 'recovery' : phone ? 'record-panel' : 'connect-panel', pendingRecovery() ? 0 : phone ? 3 : 2); });
$('connect-back').addEventListener('click', () => panel('ready-panel', 1));
$('usb-consent').addEventListener('change', validateEnvironment);
$('record-consent').addEventListener('change', () => { $('start').disabled = !$('record-consent').checked || busy; });
$('connect').addEventListener('click', () => action(async () => {
  if (!$('usb-consent').checked || pendingRecovery()) return;
  await connect(); panel('record-panel', 3);
}));
$('disconnect').addEventListener('click', () => action(closePhone));
$('start').addEventListener('click', () => action(async () => {
  if (!$('record-consent').checked || !session || !phone || !validateEnvironment()) return;
  $('record-consent').checked = false; $('start').disabled = true;
  forgetCapture(); $('stop').disabled = true; $('marker').disabled = true; $('add-marker').disabled = true;
  panel('recording-panel', 3);
  try {
    await session.begin();
    capture = session.capture();
    stopTicker();
    // Verified cleanup: USB is no longer needed. Remove the permission now.
    await releasePhone();
    showReview();
    const why = { limit: 'Recording reached its nine-minute limit and stopped. ', full: `The capture is full (${LIMITS.rows.toLocaleString('en-US')} lines or its size limit), so recording stopped. ` }[capture.reason] || '';
    if (why) message('notice', why + 'Cleanup is verified.');
  } catch (e) {
    stopTicker(); clearLive(); capture = null;
    if (session?.safe && !pendingRecovery()) await closePhone();
    else panel(pendingRecovery() ? 'recovery' : phone ? 'record-panel' : 'connect-panel', pendingRecovery() ? 0 : phone ? 3 : 2);
    throw e;
  }
}));
$('stop').addEventListener('click', () => {
  session?.stop(); $('stop').disabled = true; $('marker').disabled = true; $('add-marker').disabled = true;
  $('progress').textContent = 'Stopping: reading the last lines, then restoring settings…';
});
$('marker-form').addEventListener('submit', event => {
  event.preventDefault();
  message('error', '');
  try { session?.addNote($('marker').value); $('marker').value = ''; }
  catch (e) { error(e instanceof SetupError ? e : new SetupError('note', 'That note couldn’t be added.')); }
});
$('send-consent').addEventListener('change', () => { if (capture) renderReview(); });
$('edit-details').addEventListener('click', () => { message('notice', ''); panel('problem-panel', 0); });
$('send').addEventListener('click', () => action(async () => {
  if (!capture || sentId || !$('send-consent').checked) return;
  const data = body();
  $('send').disabled = true; $('send').textContent = 'Sending…';
  let response, reply = null;
  try {
    response = await fetch(SUBMIT_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: data,
      credentials: 'omit', cache: 'no-store', redirect: 'error' });
    try { reply = await response.json(); } catch { /* A non-JSON reply is handled by status. */ }
  } catch {
    throw new SetupError('network', 'Couldn’t reach Volt’s server. Check your internet connection and try again, or download a copy.');
  } finally { $('send').textContent = 'Send to Volt’s developer'; if (capture) renderReview(); }
  if (response.status === 201) {
    sentId = typeof reply?.id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(reply.id) ? reply.id : 'not provided';
    $('reference').textContent = sentId;
    capture = null; $('review-rows').replaceChildren();
    panel('done-panel', 5);
    return;
  }
  if (response.status === 429) throw new SetupError('rate', 'Too many captures were sent from here recently. Wait a few minutes, then try again. Your capture is still here.');
  // Our own server's short reason may help; anything else is never shown.
  const said = typeof reply?.error === 'string' && /^[\x20-\x7e]{1,200}$/.test(reply.error) ? ` It said: “${reply.error}”` : '';
  throw new SetupError('send', response.status >= 500
    ? 'Volt’s server had a problem, so nothing was sent. Try again in a few minutes, or download a copy.'
    : `Volt’s server didn’t accept this capture.${said} Your capture is still here.`);
}));
$('download-copy').addEventListener('click', () => action(async () => {
  if (!capture) return;
  downloadText(JSON.stringify(JSON.parse(body()), null, 2) + '\n', `volt-navee-capture-${capture.startedAt.replace(/[-:]/g, '').slice(0, 15)}-${randomToken().slice(0, 8)}.json`, 'application/json');
  message('notice', 'Download requested. The copy contains exactly the lines and details shown here.');
}));
$('discard').addEventListener('click', () => { forgetCapture(); message('notice', 'The recording was discarded.'); panel(phone ? 'record-panel' : 'connect-panel', phone ? 3 : 2); });
$('finish').addEventListener('click', () => {
  forgetCapture();
  $('description').value = ''; $('contact').value = ''; $('model').value = ''; details();
  $('usb-consent').checked = false; validateEnvironment();
  panel('problem-panel', 0);
});
$('recover').addEventListener('click', () => action(async () => {
  await connect({ recovery: true });
  try { await session.recover(); }
  catch (e) {
    if (e instanceof SetupError && e.code === 'device') await releasePhone();
    throw e;
  }
  await closePhone();
  message('notice', 'Cleanup verified. NAVEE is closed and the original log setting is restored. USB access is closed.');
}));
$('recovery-details').addEventListener('click', () => action(async () => {
  const journal = readJournal(storage);
  if (!journal) throw new SetupError('journal', 'There is no recovery record to download.');
  let instructions = 'VOLT NAVEE DEBUGGER RECOVERY — no scooter owner identity or log lines are included.\n\n'
    + 'First: reconnect the same phone and use Connect phone and recover on the website.\n'
    + 'If that fails, these details are for you or a trusted technical helper.\n'
    + 'Keep this browser profile and the phone lock directory intact. Do not start another helper.\n\n'
    + JSON.stringify(journal, null, 2) + '\n\n';
  if (journal.stage !== 'preparing') {
    const shell = command => `adb -s ${quote(journal.serial)} shell ${quote(command)}`;
    instructions += 'Advanced macOS/Linux fallback with Android Platform-Tools:\n'
      + '1. Turn USB debugging off and back on, reconnect, and approve this trusted computer. Close browser USB access first.\n'
      + '2. Wait at least eleven minutes after recording started (the phone timer runs for ten). If the guard was forcibly killed or wedged, restart the phone first. A reboot ends the old timer; it does not prove the old log setting was restored.\n'
      + '3. Verify the displayed serial matches your selected phone. Restore the exact pre-recording value with these commands (they do not remove the lock):\n\n'
      + shell(`am force-stop --user 0 ${PACKAGE}`) + '\n'
      + shell(`setprop ${PROPERTY} ${quote(journal.original)}`) + '\n'
      + shell(`getprop ${PROPERTY}`) + '\n\n'
      + 'Expected getprop output (JSON string, preserving spaces): ' + JSON.stringify(journal.original) + '\n\n'
      + '4. Return to the same browser and try Connect phone and recover to verify and release the session. If the timer ended without acknowledgment or preparation is damaged, ask a trusted developer to compare the lock token/owner/original with this record before removing only this session’s files. Never remove a different or active helper’s lock.\n';
  } else instructions += 'Preparation was not confirmed; this record does NOT contain a verified original value. The page never requested temporary logging in this stage. Do not restore a guessed setting. A trusted helper can inspect the matching random token on the phone and verify that no guard was armed before releasing incomplete preparation.\n';
  downloadText(instructions, 'volt-debugger-recovery.txt', 'text/plain');
}));
window.addEventListener('beforeunload', event => {
  if (session?.busy || pendingRecovery() || (capture && !sentId)) { event.preventDefault(); event.returnValue = ''; }
});
window.addEventListener('pagehide', () => {
  session?.cancel(); session?.forgetResult(); capture = null;
  // The armed phone watchdog is the fallback; the next visit verifies cleanup.
  if (!session?.busy) phone?.close(true).catch(() => {});
});
navigator.serviceWorker?.addEventListener('controllerchange', () => { session?.cancel(); session?.forgetResult(); validateEnvironment(); });
$('model').replaceChildren(...[['', 'Choose your scooter'], ...[...MODELS, OTHER_MODEL].map(name => [name, name])].map(([value, label]) => {
  const option = document.createElement('option'); option.value = value; option.textContent = label; return option;
}));
details(); clearLive(); validateEnvironment();
panel(pendingRecovery() ? 'recovery' : 'problem-panel', 0, false);
