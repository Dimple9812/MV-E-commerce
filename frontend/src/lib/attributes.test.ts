import { formatAttributes, parseAttributes } from './attributes';

describe('variant attributes', () => {
  it('parses "Key: Value" pairs and ignores malformed parts', () => {
    expect(parseAttributes('Color: Black, Size: M, junk, : nokey')).toEqual({ Color: 'Black', Size: 'M' });
    expect(parseAttributes('')).toEqual({});
    expect(parseAttributes(undefined)).toEqual({});
  });

  it('keeps colons inside values', () => {
    expect(parseAttributes('Ratio: 16:9')).toEqual({ Ratio: '16:9' });
  });

  it('round-trips', () => {
    const a = { Color: 'Walnut', Speed: '33/45' };
    expect(parseAttributes(formatAttributes(a))).toEqual(a);
  });
});
