// Synthetic NAVEE logcat rows in the shapes NAVEE 2.1.x prints. No real capture.
export function bytes(cmd, payload = [], flag = 0, read = false) {
  const data = read ? [0x55, 0xaa, flag, cmd] : [0x55, 0xaa, flag, cmd, payload.length, ...payload];
  return [...data, data.reduce((a, b) => a + b, 0) & 255, 0xfe, 0xfd];
}
// ByteUtil.g: "[55 aa …]"; other styles exercise the sensitive-frame scanner.
export function hex(data, style = 'navee') {
  const pairs = data.map(x => x.toString(16).padStart(2, '0'));
  switch (style) {
    case 'navee': return `[${pairs.join(' ')}]`;
    case 'plain': return pairs.join(' ');
    case 'colon': return pairs.join(':');
    case 'dash': return pairs.join('-');
    case 'comma': return pairs.join(', ');
    case 'none': return pairs.join('');
    case 'upper': return `[${pairs.join(' ').toUpperCase()}]`;
    case '0x': return pairs.map(x => `0x${x}`).join(',');
    case '0X': return pairs.map(x => `0X${x.toUpperCase()}`).join(', ');
    default: throw new Error(style);
  }
}
export const frame = (cmd, payload, style) => hex(bytes(cmd, payload), style);
// LogHelper.a(): "[<thread>] <Class>.<method>: <message>" under the NAVEE tag.
export const line = (message, stamp = '1800000000.125', prefix = '[2] BleHandler.l: ') =>
  `${stamp}  1234  5678 D com.navee.ucaret: ${prefix}${message}`;
export const START = '1800000000.000000000';
