import { Link, useNavigate } from 'react-router-dom';
import { Minus, Plus, Trash2 } from 'lucide-react';
import { useMemo } from 'react';
import { useCart, type CartLine } from '@/cart/CartProvider';
import { useAuth } from '@/auth/AuthProvider';
import { EmptyState, PageHeader, PriceTag, ProductArt } from '@/components/ui';
import { money } from '@/lib/format';
import { useAvailability } from './catalog';

export function CartPage() {
  const { lines, setQuantity, remove } = useCart();
  const { user } = useAuth();
  const navigate = useNavigate();
  const availability = useAvailability(lines.map((l) => l.variantId));
  const stock = new Map(availability.data?.map((a) => [a.variantId, a.available]));

  const byVendor = useMemo(() => {
    const m = new Map<string, { name: string; lines: CartLine[] }>();
    lines.forEach((l) => {
      const g = m.get(l.vendorId) ?? { name: l.vendorName, lines: [] };
      g.lines.push(l);
      m.set(l.vendorId, g);
    });
    return [...m.entries()];
  }, [lines]);

  const subtotal = lines.reduce((s, l) => s + l.unitPrice * l.quantity, 0);
  const problems = lines.filter((l) => stock.has(l.variantId) && (stock.get(l.variantId) ?? 0) < l.quantity);

  if (lines.length === 0) {
    return <EmptyState title="Your cart is empty" action={<Link to="/search" className="btn-primary">Browse the market</Link>}>
      Add something from any seller; everything checks out together.
    </EmptyState>;
  }

  return (
    <div>
      <PageHeader title="Your cart" description={`${byVendor.length} seller${byVendor.length === 1 ? '' : 's'}, one payment. Each seller ships their part separately.`} />
      <div className="grid gap-8 lg:grid-cols-[1fr_20rem]">
        <div className="flex flex-col gap-6">
          {byVendor.map(([vendorId, g]) => (
            <section key={vendorId} className="panel" aria-label={`Items from ${g.name}`}>
              <h2 className="border-b border-line px-5 py-3 font-display text-lg font-bold">{g.name}</h2>
              <ul className="divide-y divide-line">
                {g.lines.map((l) => {
                  const available = stock.get(l.variantId);
                  return (
                    <li key={l.variantId} className="flex gap-4 px-5 py-4">
                      <Link to={`/products/${l.productId}`} className="h-20 w-20 shrink-0 overflow-hidden rounded-lg border border-line">
                        <ProductArt name={l.productName} url={l.imageUrl} />
                      </Link>
                      <div className="flex flex-1 flex-col gap-1">
                        <Link to={`/products/${l.productId}`} className="font-medium hover:underline">{l.productName}</Link>
                        <span className="text-sm text-slate">{l.variantName}</span>
                        {available !== undefined && available < l.quantity && (
                          <span className="text-sm font-semibold text-brick">
                            {available === 0 ? 'Sold out — remove it to continue' : `Only ${available} available`}
                          </span>
                        )}
                        <div className="mt-1 flex items-center gap-3">
                          <div className="flex items-center rounded-lg border border-line">
                            <button className="px-2.5 py-1.5" aria-label="Decrease" onClick={() => setQuantity(l.variantId, l.quantity - 1)} disabled={l.quantity <= 1}><Minus className="h-3.5 w-3.5" /></button>
                            <span className="w-8 text-center text-sm font-semibold tabular">{l.quantity}</span>
                            <button className="px-2.5 py-1.5" aria-label="Increase" onClick={() => setQuantity(l.variantId, l.quantity + 1)} disabled={l.quantity >= l.maxPerOrder}><Plus className="h-3.5 w-3.5" /></button>
                          </div>
                          <button className="text-sm text-slate hover:text-brick" onClick={() => remove(l.variantId)}>
                            <Trash2 className="mr-1 inline h-3.5 w-3.5" aria-hidden />Remove
                          </button>
                        </div>
                      </div>
                      <div className="text-right font-semibold tabular">{money(l.unitPrice * l.quantity)}</div>
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </div>
        <aside className="panel h-fit p-5 lg:sticky lg:top-24">
          <div className="flex items-center justify-between">
            <span className="text-slate">Items</span>
            <PriceTag amount={subtotal} />
          </div>
          <p className="mt-3 text-sm text-slate">Shipping, tax and any coupon are calculated by the server at checkout. Orders over $50 per seller ship free.</p>
          <button className="btn-primary mt-5 w-full py-3" disabled={problems.length > 0}
            onClick={() => navigate(user ? '/checkout' : '/login?next=/checkout')}>
            {user ? 'Go to checkout' : 'Sign in to check out'}
          </button>
        </aside>
      </div>
    </div>
  );
}
