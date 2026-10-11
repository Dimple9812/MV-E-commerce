import { humanize, money, priceRange, shortId } from './format';

describe('format', () => {
  it('formats API decimals as currency without touching the value', () => {
    expect(money(1234.5)).toBe('$1,234.50');
    expect(money('19.99')).toBe('$19.99');
    expect(money(null)).toBe('—');
    expect(money(10, 'EUR')).toBe('€10.00');
  });

  it('collapses equal price ranges', () => {
    expect(priceRange(5, 5)).toBe('$5.00');
    expect(priceRange(5, 12)).toBe('$5.00 – $12.00');
    expect(priceRange(undefined, 3)).toBe('—');
  });

  it('humanizes status codes and shortens ids', () => {
    expect(humanize('PARTIALLY_SHIPPED')).toBe('Partially shipped');
    expect(humanize(undefined)).toBe('');
    expect(shortId('9f2c0d11-aaaa-bbbb-cccc-000000000000')).toBe('#9F2C0D11');
  });
});
