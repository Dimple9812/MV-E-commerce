import { shortId } from '@/lib/format';
import type { ChatContext } from '@/lib/types';

/** One line saying what a conversation is about, for the inbox. */
export function contextLabel(c: ChatContext): string | null {
  if (c.kind === 'PRODUCT') return c.productName ?? 'Product';
  if (c.kind === 'ORDER') return `Order ${c.orderId ? shortId(c.orderId) : ''}`.trim();
  return null;
}
