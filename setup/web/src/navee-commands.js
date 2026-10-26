// Every NAVEE command Volt knows: the controls in src/com/jjricks/volt/ScooterCommands.java,
// the reads Protocol.java names, and NAVEE's own clock sync. test/navee-log.test.mjs fails if
// an opcode, selector, name, group, kind, range or option value/label here disagrees with the
// Java source. Keys: "cmd", "cmd:selector" or "cmd:selector:sub" in lowercase hex.
// Names and value labels describe Volt's UT5 MAX layer; other models can use the same opcode
// or value differently, so a reading is a hint for review. Only the raw row is ever sent.
const OFF_ON = { 0: 'Off', 1: 'On' };
const toggle = (name, group, values = OFF_ON) => ({ name, group, kind: 'toggle', values });
const choice = (name, group, values) => ({ name, group, kind: 'choice', values });
// cap: OEM speed caps send 0 for Off, else km/h with bit 7 set; cap is the lowest enabled value.
const slider = (name, group, min, max, unit, cap) => ({ name, group, kind: 'slider', min, max, unit, ...(cap ? { cap } : {}) });
// Four big-endian bytes of local wall-clock seconds (WireCommands.localTime).
const time = (name, group, routine = false) => ({ name, group, kind: 'time', ...(routine ? { routine } : {}) });
const read = name => ({ name, group: 'Reads', kind: 'read' });
const PATTERN = { 0: 'Always on', 1: 'Follow headlights', 2: 'Breathing' }, COLOR = { 0: 'Ice blue', 1: 'Red' };
const FOLLOW = { 0: 'Off', 1: 'On', 2: 'Follow headlight' };
const lights = (target, name) => ({
  [`6d:${target}:00`]: toggle(name, 'Lights & sound'), [`6d:${target}:01`]: choice(`${name} pattern`, 'Lights & sound', PATTERN),
  [`6d:${target}:02`]: choice(`${name} color`, 'Lights & sound', COLOR), [`6d:${target}:03`]: choice(`${name} Boost color`, 'Lights & sound', COLOR)
});
export const COMMANDS = {
  '51': toggle('Electronic lock', 'Security'),
  '52': toggle('Cruise control', 'Ride'),
  '53': choice('Energy recovery', 'Ride', { 0: 'Off', 30: 'Low', 60: 'Medium', 90: 'High' }),
  '54': toggle('Tail light', 'Lights & sound'),
  '55': choice('Dashboard units', 'Preferences', { 0: 'Miles / mph', 1: 'Kilometers / km/h' }),
  '56': choice('Range estimate', 'Preferences', { 0: 'Theoretical', 1: 'Actual riding', 2: 'D-mode estimate', 3: 'Sport estimate', 4: 'Eco estimate' }),
  '57': toggle('Automatic lights', 'Lights & sound'),
  '58': choice('Acceleration profile', 'Ride', { 3: 'Eco', 5: 'Turbo' }),
  '5f': toggle('Traction control', 'Ride'),
  '60': toggle('Turn signal sound', 'Lights & sound'),
  '61': toggle('Proximity unlock', 'Security'),
  '6a': slider('Kick-start speed', 'Ride', 0, 5, 'km/h'),
  '6b': slider('Custom speed cap', 'Ride', 0, 32, 'km/h', 8),
  '6c': choice('Prompt sound', 'Lights & sound', { 0: 'Sound 1', 1: 'Sound 2', 2: 'Sound 3', 3: 'Sound 4', 4: 'Sound 5' }),
  '6d:01': choice('Ambient light', 'Lights & sound', FOLLOW),
  '6d:02': choice('Logo light', 'Lights & sound', FOLLOW),
  '6d:03': choice('Daytime running light', 'Lights & sound', FOLLOW),
  ...lights('04', 'Line lights'),
  ...lights('05', 'Star lights'),
  '6e:01': choice('Maximum speed', 'Ride', { 25: '25 km/h', 32: '32 km/h', 40: '40 km/h', 50: '50 km/h' }),
  '6e:02': toggle('Dual motor drive', 'Ride', { 0: 'Single motor', 1: 'Dual motor' }),
  '6e:03': slider('Drive mode speed cap', 'Ride', 0, 20, 'km/h', 5),
  '6f:01': toggle('Scheduled charging', 'Battery'),
  '6f:02': choice('Lock duration', 'Security', { 1: '6 hours', 2: '12 hours', 3: '24 hours' }),
  '6f:03': time('Charging start time', 'Battery'),
  '6f:04': choice('Charge ceiling', 'Battery', { 80: '80%', 85: '85%', 90: '90%', 95: '95%', 100: '100%' }),
  '6f:05': toggle('Low Battery Mode', 'Battery'),
  '6f:06': time('Clock sync', 'Reads', true), // NAVEE sends it on connect (ScooterCommands.setClock)
  '6f:07': toggle('Long range mode', 'Battery'),
  '81:01': toggle('Walk Assist', 'Ride'),
  '81:02': toggle('Power Assist', 'Ride'),
  '81:03': toggle('Hill descent control', 'Ride'),
  '82': toggle('Overspeed active control', 'Ride'),
  '70': read('Vehicle status'), '71': read('Drive data'), '72': read('Battery status'), '73': read('Firmware version'),
  '74': read('Serial number'), '75': read('Battery serial'), '76': read('Ride history'), '79': read('Extra battery data'), '7b': read('Fault codes')
};
export const GROUPS = ['Ride', 'Lights & sound', 'Battery', 'Security', 'Preferences', 'Reads'];
// ScooterCommands.sensitiveCommand: owner auth, encrypted data, PIN write, PIN read.
export const SENSITIVE = new Set([0x30, 0x31, 0x63, 0x7a]);
const hex = n => n.toString(16).padStart(2, '0');
const KEYS = Object.keys(COMMANDS);
// Follows selector bytes while some entry continues the key; returns the entry (if any),
// the key reached ("6d:04:07" for an unknown line-light sub) and the selector depth.
export function lookup(cmd, payload = []) {
  let key = hex(cmd), depth = 0;
  for (;;) {
    if (COMMANDS[key]) return { entry: COMMANDS[key], key, depth };
    if (depth >= payload.length || depth >= 2 || !KEYS.some(k => k.startsWith(`${key}:`))) return { entry: null, key, depth };
    key += `:${hex(payload[depth++])}`;
  }
}
// Local wall-clock seconds → "HH:MM" (the scooter's clock already includes the offset).
const wallClock = values => new Date(values.reduce((n, b) => n * 256 + b, 0) * 1000).toISOString().slice(11, 16);
export function valueLabel(entry, values) {
  if (entry.kind === 'time') return values.length === 4 && values.some(Boolean) ? wallClock(values) : null;
  if (values.length !== 1) return null;
  const [v] = values;
  if (entry.cap) return v === 0 ? 'Off' : v >= 128 && (v & 127) >= entry.cap && (v & 127) <= entry.max ? `${v & 127} ${entry.unit}` : null;
  if (entry.values) return entry.values[v] ?? null;
  return v >= entry.min && v <= entry.max ? `${v} ${entry.unit}` : null;
}
export const keyLabel = key => `0x${key}`;
const bounded = text => text.length > 60 ? `${text.slice(0, 57)}...` : text;
// One decoded command → known / new-value / new / read, with a plain-English reading.
// sig identifies the exact command and value, so one question covers its repeats.
export function describe(cmd, payload = [], isRead = false) {
  const { entry, key, depth } = lookup(cmd, isRead ? [] : payload), values = isRead ? [] : payload.slice(depth);
  const sig = `${key}=${values.join(',')}${isRead ? 'r' : ''}`, base = { key, values, sig, read: isRead, routine: !!entry?.routine };
  if (!entry || (isRead && entry.kind !== 'read'))
    return { ...base, status: 'new', name: null, group: null, reading: bounded(isRead ? `Read ${keyLabel(key)}` : `${keyLabel(key)}${values.length ? ` = ${values.join(', ')}` : ''}`) };
  const known = { ...base, name: entry.name, group: entry.group };
  if (entry.kind === 'read') return { ...known, status: 'read', reading: `Read ${entry.name.toLowerCase()}` };
  const label = valueLabel(entry, values);
  return label === null ? { ...known, status: 'new-value', reading: bounded(`${entry.name} = ${values.join(', ') || 'nothing'}`) }
    : { ...known, status: 'known', reading: `${entry.name} → ${label}` };
}
