// Plain-English names for NAVEE commands Volt already knows, taken from
// src/com/jjricks/volt/ScooterCommands.java (test/navee-log.test.mjs checks the
// labels and opcodes against that file). Keys: "cmd", "cmd:selector" or
// "cmd:selector:sub" in lowercase hex. Labels describe Volt's UT5 MAX layer;
// other models may use the same opcode differently, so the page shows raw values.
export const COMMANDS = {
  '51': 'Electronic lock', '52': 'Cruise control', '53': 'Energy recovery', '54': 'Tail light',
  '55': 'Dashboard units', '56': 'Range estimate', '57': 'Automatic lights', '58': 'Acceleration profile',
  '5f': 'Traction control', '60': 'Turn signal sound', '61': 'Proximity unlock', '6a': 'Kick-start speed',
  '6b': 'Custom speed cap', '6c': 'Prompt sound', '82': 'Overspeed active control',
  '6d:01': 'Ambient light', '6d:02': 'Logo light', '6d:03': 'Daytime running light',
  '6d:04:00': 'Line lights', '6d:04:01': 'Line lights pattern', '6d:04:02': 'Line lights color', '6d:04:03': 'Line lights Boost color',
  '6d:05:00': 'Star lights', '6d:05:01': 'Star lights pattern', '6d:05:02': 'Star lights color', '6d:05:03': 'Star lights Boost color',
  '6e:01': 'Maximum speed', '6e:02': 'Dual motor drive', '6e:03': 'Drive mode speed cap',
  '6f:01': 'Scheduled charging', '6f:02': 'Lock duration', '6f:03': 'Charging start time', '6f:04': 'Charge ceiling',
  '6f:05': 'Low Battery Mode', '6f:06': 'Clock sync', '6f:07': 'Long range mode',
  '81:01': 'Walk Assist', '81:02': 'Power Assist', '81:03': 'Hill descent control',
  '70': 'Settings readback'
};
// ScooterCommands.sensitiveCommand: owner auth, encrypted data, PIN write, PIN read.
export const SENSITIVE = new Set([0x30, 0x31, 0x63, 0x7a]);
const hex = n => n.toString(16).padStart(2, '0');
// Longest known selector wins; returns [name, number of selector bytes].
export function commandName(cmd, payload = []) {
  for (let depth = Math.min(2, payload.length); depth >= 0; depth--) {
    const key = [cmd, ...payload.slice(0, depth)].map(hex).join(':');
    if (COMMANDS[key]) return [COMMANDS[key], depth];
  }
  return [null, 0];
}
