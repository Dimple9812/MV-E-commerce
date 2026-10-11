import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Check, CircleDashed, X } from 'lucide-react';
import clsx from 'clsx';
import { Dialog, EmptyState, ErrorState, Field, InlineError, PageHeader, PageSpinner, Pagination, ProductArt, StatusBadge, Textarea } from '@/components/ui';
import { useToast } from '@/components/Toast';
import { http } from '@/lib/api';
import type { Order, OrderSummary, Page, Payment } from '@/lib/types';
import { date, humanize, money, shortId } from '@/lib/format';

const IN_FLIGHT = ['PENDING', 'CANCELLATION_REQUESTED'];

export function OrdersPage() {
  const [page, setPage] = useState(0);
  const orders = useQuery({
    queryKey: ['orders', page],
    queryFn: () => http.get<Page<OrderSummary>>(`/api/v1/orders?page=${page}&size=20`),
  });
  if (orders.isLoading) return <PageSpinner />;
  if (orders.isError) return <ErrorState error={orders.error} onRetry={() => orders.refetch()} />;
  return (
    <div>
      <PageHeader title="Your orders" />
      {orders.data!.items.length === 0 ? (
        <EmptyState title="No orders yet" action={<Link to="/search" className="btn-primary">Start shopping</Link>} />
      ) : (
        <ul className="panel divide-y divide-line">
          {orders.data!.items.map((o) => (
            <li key={o.id}>
              <Link to={`/account/orders/${o.id}`} className="grid grid-cols-2 gap-2 px-5 py-4 hover:bg-mist sm:grid-cols-[1fr_auto_auto_auto] sm:items-center sm:gap-6">
                <span className="font-semibold">{shortId(o.id)}<span className="block text-sm font-normal text-slate">{date(o.createdAt, true)}</span></span>
                <span className="text-sm text-slate">{o.itemCount} item{o.itemCount === 1 ? '' : 's'} from {o.vendorCount} seller{o.vendorCount === 1 ? '' : 's'}</span>
                <span className="font-semibold tabular">{money(o.grandTotal, o.currency)}</span>
                <span className="justify-self-start sm:justify-self-end"><StatusBadge status={o.status} /></span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <Pagination page={page} totalPages={orders.data!.totalPages} onPage={setPage} />
    </div>
  );
}

/** Customer-facing view of the checkout saga. Steps derive from the persisted saga state. */
function CheckoutProgress({ order }: { order: Order }) {
  const state = order.checkoutState ?? '';
  const steps = [
    { label: 'Reserving your items', done: !['CREATED', 'INVENTORY_PENDING'].includes(state) },
    { label: 'Authorising payment', done: ['PAYMENT_AUTHORIZED', 'INVENTORY_CONFIRM_PENDING', 'CONFIRMED'].includes(state) },
    { label: 'Charging your card', done: ['INVENTORY_CONFIRM_PENDING', 'CONFIRMED'].includes(state) },
    { label: 'Order confirmed', done: state === 'CONFIRMED' },
  ];
  const failed = ['CANCELLED', 'FAILED', 'COMPENSATING'].includes(state);
  const current = steps.findIndex((s) => !s.done);
  return (
    <ol className="flex flex-col gap-3 sm:flex-row sm:gap-0" aria-label="Checkout progress">
      {steps.map((s, i) => (
        <li key={s.label} className="flex flex-1 items-center gap-2">
          <span className={clsx('flex h-7 w-7 shrink-0 items-center justify-center rounded-full border-2',
            s.done ? 'border-leaf bg-leaf text-white' : failed && i === current ? 'border-brick text-brick' : i === current ? 'border-ink' : 'border-line text-slate')}>
            {s.done ? <Check className="h-4 w-4" /> : failed && i === current ? <X className="h-4 w-4" />
              : <CircleDashed className={clsx('h-4 w-4', i === current && 'animate-spin')} style={{ animationDuration: '2.5s' }} />}
          </span>
          <span className={clsx('text-sm', s.done ? 'font-medium' : 'text-slate')}>{s.label}</span>
          {i < steps.length - 1 && <span className="mx-2 hidden h-px flex-1 bg-line sm:block" />}
        </li>
      ))}
    </ol>
  );
}

const REASONS: Record<string, string> = {
  OUT_OF_STOCK: 'An item sold out before we could reserve it. You were not charged.',
  PAYMENT_DECLINED: 'Your bank declined the payment. You were not charged; try another card.',
  CAPTURE_FAILED: 'The payment could not be completed. The authorisation was released.',
  STOCK_UNAVAILABLE_AFTER_PAYMENT: 'The stock ran out after payment. A full refund has been issued.',
  INVENTORY_TIMEOUT: 'We could not reserve your items in time. You were not charged.',
  PAYMENT_UNRESOLVED: 'We could not confirm your payment with the bank. Support has been notified.',
};

export function OrderDetailPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const qc = useQueryClient();
  const toast = useToast();
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const order = useQuery({
    queryKey: ['order', id],
    queryFn: () => http.get<Order>(`/api/v1/orders/${id}`),
    // poll while the asynchronous saga or a cancellation is still running
    refetchInterval: (q) => (q.state.data && IN_FLIGHT.includes(q.state.data.status) ? 1500 : false),
  });
  const payment = useQuery({
    queryKey: ['payment', id, order.data?.status],
    queryFn: () => http.get<Payment>(`/api/v1/payments/by-order/${id}`),
    enabled: !!order.data && order.data.checkoutState !== 'INVENTORY_PENDING',
    retry: false,
  });
  const cancel = useMutation({
    mutationFn: () => http.post<Order>(`/api/v1/orders/${id}/cancel`, { reason }),
    onSuccess: (o) => {
      qc.setQueryData(['order', id], o);
      setCancelOpen(false);
      toast.ok('Cancellation requested. Your refund is being processed.');
    },
  });

  if (order.isLoading) return <PageSpinner />;
  if (order.isError || !order.data) return <ErrorState title="Order not found" error={order.error} />;
  const o = order.data;
  const cancellable = o.status === 'CONFIRMED' && o.suborders.every((s) => s.status === 'CONFIRMED');

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title={`Order ${shortId(o.id)}`}
        description={<>Placed {date(o.createdAt, true)}. <StatusBadge status={o.status} /></>}
        actions={cancellable && <button className="btn-danger" onClick={() => setCancelOpen(true)}>Cancel order</button>} />

      {params.get('placed') && o.status === 'PENDING' && (
        <p className="panel border-tag-deep/40 bg-tag/15 px-5 py-4">Thanks! We are reserving your items and confirming payment. This usually takes a few seconds.</p>
      )}
      {(o.status === 'PENDING' || o.checkoutState) && o.status !== 'CANCELLED' && o.status !== 'FAILED' && (
        <section className="panel p-5"><CheckoutProgress order={o} /></section>
      )}
      {(o.status === 'CANCELLED' || o.status === 'FAILED') && (
        <p className="panel border-brick/30 px-5 py-4">{REASONS[o.cancelReason ?? ''] ?? `This order was cancelled${o.cancelReason ? `: ${o.cancelReason}` : ''}.`}</p>
      )}

      <div className="grid gap-8 lg:grid-cols-[1fr_20rem]">
        <div className="flex flex-col gap-5">
          {o.suborders.map((s) => (
            <section key={s.id} className="panel" aria-label={`Part shipped by ${s.vendorName}`}>
              <header className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-5 py-3">
                <h2 className="font-display text-lg font-bold">From {s.vendorName}</h2>
                <StatusBadge status={s.status} />
              </header>
              {s.trackingNumber && (
                <p className="border-b border-line px-5 py-2 text-sm">Shipped with {s.carrier}, tracking <span className="font-semibold">{s.trackingNumber}</span>{s.deliveredAt && `, delivered ${date(s.deliveredAt)}`}</p>
              )}
              <ul className="divide-y divide-line">
                {s.items.map((i) => (
                  <li key={i.id} className="flex gap-4 px-5 py-3">
                    <Link to={`/products/${i.productId}`} className="h-14 w-14 shrink-0 overflow-hidden rounded-lg border border-line"><ProductArt name={i.productName} url={i.imageUrl} /></Link>
                    <div className="flex-1 text-sm">
                      <Link to={`/products/${i.productId}`} className="font-medium hover:underline">{i.productName}</Link>
                      <div className="text-slate">{i.variantName}, {i.quantity} × {money(i.unitPrice, o.currency)}</div>
                    </div>
                    <div className="text-sm font-semibold tabular">{money(i.lineTotal, o.currency)}</div>
                  </li>
                ))}
              </ul>
              <footer className="flex justify-between px-5 py-3 text-sm text-slate">
                <span>Shipping {Number(s.shippingAmount) === 0 ? 'free' : money(s.shippingAmount, o.currency)}</span>
                <span className="font-semibold text-ink tabular">{money(s.total, o.currency)}</span>
              </footer>
            </section>
          ))}
        </div>
        <aside className="flex flex-col gap-5">
          <section className="panel p-5 text-sm">
            <h2 className="mb-3 font-display text-lg font-bold">Total</h2>
            <dl className="grid grid-cols-2 gap-y-1.5">
              <dt className="text-slate">Items</dt><dd className="text-right tabular">{money(o.itemsSubtotal, o.currency)}</dd>
              {Number(o.discountTotal) > 0 && <><dt className="text-slate">Discount {o.couponCode}</dt><dd className="text-right tabular">−{money(o.discountTotal, o.currency)}</dd></>}
              <dt className="text-slate">Shipping</dt><dd className="text-right tabular">{money(o.shippingTotal, o.currency)}</dd>
              <dt className="text-slate">Tax</dt><dd className="text-right tabular">{money(o.taxTotal, o.currency)}</dd>
              <dt className="border-t border-line pt-2 font-semibold">Total</dt><dd className="border-t border-line pt-2 text-right font-display text-lg font-bold tabular">{money(o.grandTotal, o.currency)}</dd>
            </dl>
            {payment.data && <p className="mt-3 text-slate">Payment: {humanize(payment.data.status)}{Number(payment.data.refundedAmount) > 0 && `, refunded ${money(payment.data.refundedAmount, o.currency)}`}</p>}
          </section>
          <section className="panel p-5 text-sm">
            <h2 className="mb-2 font-display text-lg font-bold">Delivering to</h2>
            <p className="text-slate">{[o.shippingAddress.recipient, o.shippingAddress.line1, o.shippingAddress.line2, `${o.shippingAddress.city} ${o.shippingAddress.postalCode}`, o.shippingAddress.country].filter(Boolean).join(', ')}</p>
          </section>
          <section className="panel p-5 text-sm">
            <h2 className="mb-2 font-display text-lg font-bold">History</h2>
            <ol className="flex flex-col gap-2">
              {o.history.map((h, i) => (
                <li key={i}><span className="font-medium">{humanize(h.toStatus)}</span> <span className="text-slate">{date(h.at, true)}{h.reason ? `, ${/^[A-Z_]+$/.test(h.reason) ? humanize(h.reason).toLowerCase() : h.reason}` : ''}</span></li>
              ))}
            </ol>
          </section>
        </aside>
      </div>

      <Dialog open={cancelOpen} onClose={() => setCancelOpen(false)} title="Cancel this order?">
        <p className="mb-4 text-sm text-slate">Nothing has shipped yet, so you will get a full refund of {money(o.grandTotal, o.currency)}.</p>
        <Field label="Reason" htmlFor="reason">
          <Textarea id="reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="For example: ordered the wrong size" />
        </Field>
        <InlineError error={cancel.error} />
        <div className="mt-4 flex justify-end gap-2">
          <button className="btn-quiet" onClick={() => setCancelOpen(false)}>Keep order</button>
          <button className="btn-danger" disabled={reason.trim().length < 3 || cancel.isPending} onClick={() => cancel.mutate()}>Cancel and refund</button>
        </div>
      </Dialog>
    </div>
  );
}
