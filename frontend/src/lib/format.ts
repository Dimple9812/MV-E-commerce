const currencyFormatters = new Map<string, Intl.NumberFormat>();

/** Formats a decimal amount coming from the API. Display only - the server computes every total. */
export function money(amount: number | string | null | undefined, currency = 'USD'): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  let f = currencyFormatters.get(currency);
  if (!f) {
    f = new Intl.NumberFormat('en-US', { style: 'currency', currency });
    currencyFormatters.set(currency, f);
  }
  return f.format(Number(amount));
}

export function priceRange(min?: number | null, max?: number | null, currency = 'USD'): string {
  if (min === null || min === undefined) return '—';
  if (max === null || max === undefined || Number(max) === Number(min)) return money(min, currency);
  return `${money(min, currency)} – ${money(max, currency)}`;
}

export function date(iso?: string | null, withTime = false): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return withTime
    ? d.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

export function relative(iso?: string | null): string {
  if (!iso) return '—';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return date(iso);
}

export function shortId(id: string): string {
  return '#' + id.slice(0, 8).toUpperCase();
}

export function humanize(code?: string | null): string {
  if (!code) return '';
  const s = code.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('');
}

/** "Usually replies within an hour" from a median first-reply time in seconds. */
export function replyTime(seconds?: number | null): string | null {
  if (!seconds || seconds <= 0) return null;
  if (seconds <= 15 * 60) return 'Usually replies within minutes';
  if (seconds <= 3600) return 'Usually replies within an hour';
  const hours = Math.ceil(seconds / 3600);
  if (hours < 24) return `Usually replies within ${hours} hours`;
  const days = Math.ceil(hours / 24);
  return `Usually replies within ${days} day${days === 1 ? '' : 's'}`;
}

/** "10:42" today, "Mon 10:42" this week, otherwise a date. */
export function chatTime(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
  if (now.getTime() - d.getTime() < 6 * 86_400_000) {
    return d.toLocaleString('en-US', { weekday: 'short', hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}
