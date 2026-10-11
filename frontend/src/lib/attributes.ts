/** "Color: Black, Size: M" <-> { Color: 'Black', Size: 'M' } */
export function parseAttributes(text?: string): Record<string, string> {
  const out: Record<string, string> = {};
  (text ?? '').split(',').map((p) => p.trim()).filter(Boolean).forEach((pair) => {
    const i = pair.indexOf(':');
    if (i > 0) out[pair.slice(0, i).trim()] = pair.slice(i + 1).trim();
  });
  return out;
}
export const formatAttributes = (a?: Record<string, string>): string => Object.entries(a ?? {}).map(([k, v]) => `${k}: ${v}`).join(', ');

