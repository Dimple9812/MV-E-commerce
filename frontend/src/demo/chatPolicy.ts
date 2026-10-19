/**
 * The demo's copy of chat-service's ContentPolicy (services/chat-service/.../domain/ContentPolicy.java): the same
 * shape rules and the same off-platform contact detection, so the demo refuses exactly what the real service
 * refuses. Keep the two in step.
 */
export type Violation = 'PHONE_NUMBER' | 'EMAIL_ADDRESS' | 'PAYMENT_LINK';

export const MAX_LENGTH = 2000;

const BLOCKED_HOSTS = ['paypal.me', 'paypal.com', 'venmo.com', 'cash.app', 'wa.me', 'whatsapp.com', 't.me', 'telegram.me',
  'revolut.me', 'buymeacoffee.com', 'ko-fi.com', 'pay.google.com', 'gpay.app.goo.gl', 'paytm.me', 'phon.pe', 'razorpay.me',
  'wise.com', 'zelle.com', 'payoneer.com', 'skrill.com'];

const PHONE = /(?<![\w/])\+?\(?\d[\d\s().-]{5,22}\d(?![\w/])/g;
const PLAIN_AT = /[\p{L}\p{N}._%+-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*/gu;
const BRACKETED_AT = /[\p{L}\p{N}._%+-]+\s*[([{]\s*at\s*[)\]}]\s*[\p{L}\p{N}-]+(?:\s*(?:\.|[([{]\s*dot\s*[)\]}])\s*[\p{L}\p{N}-]+)+/iu;
const SPELLED_AT = /[\p{L}\p{N}._%+-]+\s+at\s+[\p{L}\p{N}-]+(?:\s*(?:\(dot\)|\[dot\]|\s+dot\s+)\s*[\p{L}\p{N}-]+)+/iu;
const UPI_ID = /[\w.-]{2,}@(?:ok\w+|upi|ybl|ibl|axl|paytm|apl|yapl|ptyes|ptaxis|pthdfc|ptsbi|icici|sbi|hdfcbank|axisbank|kotak|jupiteraxis|fbl|waaxis|wahdfcbank|waicici|wasbi|freecharge|airtel|jio|slice|naviaxis|abfspay)\b/i;
const UPI_LINK = /\bupi:\/\//i;
const LINK_HOST = /(?:https?:\/\/)?(?:www\.)?((?:[\p{L}\p{N}-]+\.)+\p{L}{2,})(?:[/:?#]\S*)?/giu;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const AMOUNT = /^\d{1,3}(?:[.,]\d{3})*[.,]\d{2}$/;

/** @throws Error with a user-facing reason */
export function normaliseText(raw: unknown, required: boolean): string | null {
  const text = (typeof raw === 'string' ? raw : '').replace(/\r\n/g, '\n').trim();
  if (!text) {
    if (required) throw new Error('Write a message first');
    return null;
  }
  if ([...text].length > MAX_LENGTH) throw new Error(`Messages can be at most ${MAX_LENGTH} characters`);
  if (CONTROL.test(text)) throw new Error('The message contains characters that cannot be sent');
  return text;
}

export function violations(text: string | null): Violation[] {
  if (!text) return [];
  const found = new Set<Violation>();
  for (const m of text.matchAll(PHONE)) {
    const digits = m[0].replace(/\D/g, '').length;
    if (digits >= 7 && digits <= 15 && !AMOUNT.test(m[0]) && !ISO_DATE.test(m[0].trim())) found.add('PHONE_NUMBER');
  }
  for (const m of text.matchAll(PLAIN_AT)) {
    const domain = m[0].slice(m[0].indexOf('@'));
    if (domain.includes('.') && !new RegExp(`^${UPI_ID.source}$`, 'i').test(m[0])) found.add('EMAIL_ADDRESS');
  }
  if (BRACKETED_AT.test(text) || SPELLED_AT.test(text)) found.add('EMAIL_ADDRESS');
  if (UPI_ID.test(text) || UPI_LINK.test(text)) found.add('PAYMENT_LINK');
  for (const m of text.matchAll(LINK_HOST)) {
    const host = m[1]!.toLowerCase();
    if (BLOCKED_HOSTS.some((b) => host === b || host.endsWith(`.${b}`))) found.add('PAYMENT_LINK');
  }
  return [...found];
}

export const REFUSAL = 'For your safety, keep contact details and payments on MarketFlow. Remove phone numbers, e-mail addresses and payment links, then send again.';
