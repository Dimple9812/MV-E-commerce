import { useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useCart } from '@/cart/CartProvider';
import { EmptyState, ErrorState, Field, InlineError, Input, PageHeader, PriceTag, Skeleton } from '@/components/ui';
import { http } from '@/lib/api';
import type { Address, Order } from '@/lib/types';
import { money } from '@/lib/format';

/** Sandbox payment methods. These map to deterministic behaviours of the local sandbox provider. */
const CARDS = [
  { token: 'tok_visa', label: 'Visa ending 4242', note: 'Approved' },
  { token: 'tok_async', label: 'Card with bank confirmation', note: 'Approved a few seconds later via webhook' },
  { token: 'tok_timeout', label: 'Card on a slow network', note: 'Response lost; the payment is reconciled' },
  { token: 'tok_decline', label: 'Visa ending 0002', note: 'Declined by the bank' },
  { token: 'tok_capture_fail', label: 'Card that fails at capture', note: 'Authorised, then capture fails' },
] as const;

const addressSchema = z.object({
  recipient: z.string().trim().min(2, 'Who should receive it?').max(120),
  line1: z.string().trim().min(3, 'Street and number').max(200),
  line2: z.string().trim().max(200).optional(),
  city: z.string().trim().min(2, 'City').max(100),
  state: z.string().trim().max(100).optional(),
  postalCode: z.string().trim().min(2, 'Postal code').max(20),
  country: z.string().trim().regex(/^[A-Za-z]{2}$/, 'Two-letter country code, e.g. US'),
  phone: z.string().trim().max(30).regex(/^[0-9+()\-\s]*$/, 'Digits, spaces, + ( ) - only').optional(),
});
type AddressForm = z.infer<typeof addressSchema>;

export function CheckoutPage() {
  const { lines, clear } = useCart();
  const navigate = useNavigate();
  const addresses = useQuery({ queryKey: ['addresses'], queryFn: () => http.get<Address[]>('/api/v1/users/me/addresses') });
  const [addressId, setAddressId] = useState<string | 'new'>();
  const [token, setToken] = useState<string>('tok_visa');
  const [coupon, setCoupon] = useState('');
  const [saveAddress, setSaveAddress] = useState(true);
  const form = useForm<AddressForm>({ resolver: zodResolver(addressSchema), defaultValues: { country: 'US' } });

  // One idempotency key per checkout attempt: retries of the same click (double submit, network retry) reuse it,
  // so the server can never create two orders for one intent. It changes only when the cart content changes.
  const cartKey = lines.map((l) => `${l.variantId}x${l.quantity}`).join('|');
  const keyRef = useRef<{ cart: string; key: string }>();
  if (!keyRef.current || keyRef.current.cart !== cartKey) {
    keyRef.current = { cart: cartKey, key: crypto.randomUUID() };
  }

  const selectedId = addressId ?? addresses.data?.find((a) => a.isDefault)?.id ?? (addresses.data?.length ? addresses.data[0]!.id : 'new');
  const estimate = useMemo(() => lines.reduce((s, l) => s + l.unitPrice * l.quantity, 0), [lines]);

  const place = useMutation({
    mutationFn: async () => {
      let shippingAddress: AddressForm;
      if (selectedId === 'new') {
        const ok = await form.trigger();
        if (!ok) throw new Error('Check the delivery address');
        shippingAddress = form.getValues();
        if (saveAddress) {
          await http.post('/api/v1/users/me/addresses', { ...shippingAddress, label: 'Home', makeDefault: !addresses.data?.length });
        }
      } else {
        const a = addresses.data!.find((x) => x.id === selectedId)!;
        shippingAddress = { recipient: a.recipient, line1: a.line1, line2: a.line2, city: a.city, state: a.state,
          postalCode: a.postalCode, country: a.country, phone: a.phone };
      }
      return http.post<Order>('/api/v1/orders/checkout', {
        items: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })),
        shippingAddress, paymentToken: token, couponCode: coupon.trim() || undefined,
      }, { 'Idempotency-Key': keyRef.current!.key });
    },
    onSuccess: (order) => {
      clear();
      navigate(`/account/orders/${order.id}?placed=1`, { replace: true });
    },
  });

  if (lines.length === 0 && !place.isSuccess) {
    return <EmptyState title="Nothing to check out" action={<Link to="/search" className="btn-primary">Browse the market</Link>} />;
  }

  return (
    <div>
      <PageHeader title="Checkout" />
      <div className="grid gap-8 lg:grid-cols-[1fr_22rem]">
        <div className="flex flex-col gap-8">
          <section aria-labelledby="ship">
            <h2 id="ship" className="mb-3 text-xl font-bold">Delivery address</h2>
            {/* Wait for saved addresses: rendering the "new address" form first and swapping it out a moment
                later moves the form under the user's cursor. */}
            {addresses.isLoading ? <Skeleton className="h-24" /> : addresses.isError ? (
              <ErrorState error={addresses.error} title="Saved addresses could not be loaded" onRetry={() => addresses.refetch()} />
            ) : (<>
            <div className="flex flex-col gap-2">
              {addresses.data?.map((a) => (
                <label key={a.id} className={`panel flex cursor-pointer gap-3 p-4 ${selectedId === a.id ? 'border-ink' : ''}`}>
                  <input type="radio" name="address" checked={selectedId === a.id} onChange={() => setAddressId(a.id)} className="mt-1 accent-[rgb(var(--ink))]" />
                  <span>
                    <span className="font-semibold">{a.label}</span><br />
                    <span className="text-sm text-slate">{a.recipient}, {a.line1}{a.line2 ? `, ${a.line2}` : ''}, {a.city} {a.postalCode}, {a.country}</span>
                  </span>
                </label>
              ))}
              <label className={`panel flex cursor-pointer gap-3 p-4 ${selectedId === 'new' ? 'border-ink' : ''}`}>
                <input type="radio" name="address" checked={selectedId === 'new'} onChange={() => setAddressId('new')} className="mt-1 accent-[rgb(var(--ink))]" />
                <span className="font-semibold">Use a new address</span>
              </label>
            </div>
            {selectedId === 'new' && (
              <div className="mt-4 grid gap-4 sm:grid-cols-2">
                <div className="sm:col-span-2"><Field label="Recipient" htmlFor="recipient" error={form.formState.errors.recipient?.message}><Input id="recipient" autoComplete="name" {...form.register('recipient')} /></Field></div>
                <div className="sm:col-span-2"><Field label="Street address" htmlFor="line1" error={form.formState.errors.line1?.message}><Input id="line1" autoComplete="address-line1" {...form.register('line1')} /></Field></div>
                <div className="sm:col-span-2"><Field label="Apartment, suite (optional)" htmlFor="line2"><Input id="line2" autoComplete="address-line2" {...form.register('line2')} /></Field></div>
                <Field label="City" htmlFor="city" error={form.formState.errors.city?.message}><Input id="city" autoComplete="address-level2" {...form.register('city')} /></Field>
                <Field label="State / region (optional)" htmlFor="state"><Input id="state" autoComplete="address-level1" {...form.register('state')} /></Field>
                <Field label="Postal code" htmlFor="postalCode" error={form.formState.errors.postalCode?.message}><Input id="postalCode" autoComplete="postal-code" {...form.register('postalCode')} /></Field>
                <Field label="Country code" htmlFor="country" error={form.formState.errors.country?.message}><Input id="country" maxLength={2} autoComplete="country" {...form.register('country')} /></Field>
                <label className="flex items-center gap-2 text-sm sm:col-span-2">
                  <input type="checkbox" checked={saveAddress} onChange={(e) => setSaveAddress(e.target.checked)} className="accent-[rgb(var(--ink))]" />
                  Save this address to my account
                </label>
              </div>
            )}
            </>)}
          </section>

          <section aria-labelledby="pay">
            <h2 id="pay" className="text-xl font-bold">Payment</h2>
            <p className="mb-3 mt-1 text-sm text-slate">Sandbox mode: no real card is charged. Pick a test card to see how each case is handled.</p>
            <div className="grid gap-2 sm:grid-cols-2">
              {CARDS.map((c) => (
                <label key={c.token} className={`panel flex cursor-pointer gap-3 p-4 ${token === c.token ? 'border-ink' : ''}`}>
                  <input type="radio" name="card" checked={token === c.token} onChange={() => setToken(c.token)} className="mt-1 accent-[rgb(var(--ink))]" />
                  <span><span className="font-semibold">{c.label}</span><br /><span className="text-sm text-slate">{c.note}</span></span>
                </label>
              ))}
            </div>
          </section>
        </div>

        <aside className="panel h-fit p-5 lg:sticky lg:top-24">
          <h2 className="font-display text-lg font-bold">Order summary</h2>
          <ul className="mt-3 flex flex-col gap-2 text-sm">
            {lines.map((l) => (
              <li key={l.variantId} className="flex justify-between gap-3">
                <span className="text-slate">{l.quantity} × {l.productName}</span>
                <span className="tabular">{money(l.unitPrice * l.quantity)}</span>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex gap-2">
            <label htmlFor="coupon" className="sr-only">Coupon code</label>
            <Input id="coupon" placeholder="Coupon code" value={coupon} onChange={(e) => setCoupon(e.target.value)} />
          </div>
          <p className="mt-1 text-xs text-slate">Try WELCOME10 for 10% off.</p>
          <div className="mt-4 flex items-center justify-between border-t border-line pt-4">
            <span className="text-slate">Items</span>
            <PriceTag amount={estimate} />
          </div>
          <p className="mt-2 text-xs text-slate">Final shipping, tax and discount are confirmed on the next screen.</p>
          <InlineError error={place.error} />
          <button className="btn-accent mt-4 w-full py-3 text-base" disabled={place.isPending || !addresses.isSuccess} onClick={() => place.mutate()}>
            {place.isPending ? 'Placing order…' : 'Place order'}
          </button>
        </aside>
      </div>
    </div>
  );
}
