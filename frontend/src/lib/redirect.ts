/**
 * Validates a post-login redirect target (?next=) so it can only point inside this site.
 *
 * Rejects absolute URLs, protocol-relative "//host" and anything containing a backslash or control character:
 * browsers normalise "\\" to "/", so "/\\evil.example" would otherwise become "//evil.example".
 */
export function safeNext(next: string | null): string {
  if (!next || !next.startsWith('/') || next.startsWith('//')) return '/';
  // eslint-disable-next-line no-control-regex
  return /[\\\u0000-\u001f]/.test(next) ? '/' : next;
}
