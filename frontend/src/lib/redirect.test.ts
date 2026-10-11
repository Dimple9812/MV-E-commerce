import { safeNext } from './redirect';

describe('safeNext', () => {
  it.each([
    ['/checkout', '/checkout'],
    ['/account/orders?page=2', '/account/orders?page=2'],
    [null, '/'],
    ['', '/'],
    ['https://evil.example', '/'],
    ['//evil.example', '/'],
    ['/\\evil.example', '/'],
    ['/\\/evil.example', '/'],
    ['/%0d%0aSet-Cookie', '/%0d%0aSet-Cookie'],
    ['/\u0000evil', '/'],
    ['javascript:alert(1)', '/'],
  ])('%s -> %s', (input, expected) => {
    expect(safeNext(input)).toBe(expected);
  });
});
