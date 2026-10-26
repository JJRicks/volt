// NAVEE debugger capture: the owner-capture session (same lock, journal, profile
// checks, watchdog, force-stop and exact restoration) with a longer, user-stopped
// recording of filtered NAVEE Bluetooth rows instead of the authentication poll.
import { CaptureSession, readJournal } from './capture.js';
import { MAX_CAPTURE_BYTES, PACKAGE, SetupError } from './protocol.js';
import { LIMITS, LogRecorder, logcatCommand, safeText } from './navee-log.js';

// The phone watchdog restores NAVEE's log setting after ten minutes even if the
// browser vanishes. Recording stops a minute earlier so normal cleanup wins.
export const WATCH_SECONDS = 600, RECORD_MS = 540_000, POLL_MS = 2_000;
const BATCH = 100;
const stampOf = ms => `${Math.floor(ms / 1000)}.${String(Math.floor(ms % 1000)).padStart(3, '0')}`;

export class DebugCaptureSession extends CaptureSession {
  constructor({ wall = () => Date.now(), onRecord = () => {}, ...options }) {
    super({ ...options, watchSeconds: WATCH_SECONDS });
    Object.assign(this, { wall, onRecord });
    this.stopping = false; this.recorder = null; this.deadline = 0; this.offset = 0;
  }
  // Stop keeps the recording; cancel() (disconnect, page exit) discards it.
  stop() { this.stopping = true; }
  async begin() {
    if (!this.busy) { this.stopping = false; this.recorder = null; this.deadline = 0; }
    return super.begin();
  }
  async collect(uid, stamp) {
    // Map the browser clock onto the phone's, for user notes only.
    const now = /^(\d{9,11}\.\d{1,9})$/.exec(await this.run('date +%s.%N'));
    if (!now) throw new SetupError('clock', 'The phone did not provide a valid capture time.');
    this.offset = Number(now[1]) * 1000 - this.wall();
    // The only device facts sent: NAVEE version, Android version and phone model.
    const facts = await this.deviceFacts();
    const recorder = this.recorder = new LogRecorder(stamp);
    const started = this.now();
    this.deadline = started + RECORD_MS;
    this.update('recording', 'Recording. Use NAVEE on your phone now.');
    this.onRecord(recorder);
    let count = BATCH, failures = 0, drains = 0, last = false, reason = 'stopped';
    for (;;) {
      this.checkCancelled();
      if (!last && (this.stopping || this.now() >= this.deadline)) { last = true; reason = this.stopping ? 'stopped' : 'limit'; }
      let output;
      try {
        output = await this.run(logcatCommand(uid, recorder.cursor, count), { maxBytes: MAX_CAPTURE_BYTES, timeout: 12_000 });
        failures = 0;
      } catch (error) {
        // A burst of large rows: ask for fewer per poll. A slow reply: retry twice.
        if (error instanceof SetupError && error.code === 'oversize' && count > 1) { count = Math.max(1, Math.floor(count / 10)); continue; }
        if (error instanceof SetupError && error.code === 'timeout' && ++failures < 3) continue;
        throw error;
      }
      const poll = recorder.ingest(output);
      this.onRecord(recorder);
      if (recorder.full) { reason = 'full'; break; }
      const more = poll.lines >= count;
      if (more && !poll.fresh) count = Math.min(count * 2, 1000); // one crowded timestamp
      else if (!more && count < BATCH) count = Math.min(BATCH, count * 2);
      if (more && ++drains < 50) continue;
      drains = 0;
      if (last) break;
      await this.pause();
    }
    this.checkCancelled();
    return { recorder, facts, reason, startedAt: new Date(Math.floor(Number(stamp) * 1000)).toISOString(),
      durationS: Math.min(LIMITS.duration, Math.max(0, Math.round((this.now() - started) / 1000))) };
  }
  async pause() {
    const until = Math.min(this.now() + POLL_MS, this.deadline);
    while (!this.cancelled && !this.stopping && this.now() < until) await this.delay(Math.min(250, until - this.now()));
  }
  async deviceFacts() {
    const read = async (command, pattern) => {
      try {
        const match = pattern.exec(await this.run(command, { maxBytes: 4096, timeout: 8_000 }));
        return match && safeText(match[1]) ? match[1] : 'unknown';
      } catch (error) {
        if (error instanceof SetupError && error.code === 'cancelled') throw error;
        return 'unknown';
      }
    };
    return {
      naveeVersion: await read(`dumpsys package ${PACKAGE} | grep -m 1 -o 'versionName=[^ ]*'`, /^versionName=([0-9A-Za-z._+-]{1,40})$/),
      android: await read('getprop ro.build.version.release', /^([0-9A-Za-z._-]{1,40})$/),
      phone: await read('getprop ro.product.model', /^([\x20-\x7e]{1,80})$/)
    };
  }
  addNote(text) {
    if (!this.recorder || !this.busy || this.stopping || this.cancelled) throw new SetupError('note', 'Notes can be added only while recording.');
    const row = this.recorder.addNote(stampOf(this.wall() + this.offset), text);
    this.onRecord(this.recorder);
    return row;
  }
  // Same gate as owner export: only after verified cleanup and lock release.
  capture() {
    if (this.busy || !this.safe || this.journal || !this.result || readJournal(this.storage))
      throw new SetupError('unsafe-export', 'Review is available only after cleanup is verified.');
    return this.result;
  }
  forgetResult() { super.forgetResult(); this.recorder = null; }
}
