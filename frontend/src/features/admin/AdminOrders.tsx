import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { useToast } from '@/components/Toast';
import { Dialog, EmptyState, ErrorState, Field, InlineError, Input, PageHeader, PageSpinner, Pagination, StatusBadge, Textarea } from '@/components/ui';
import { ApiError, http, qs } from '@/lib/api';
import type { Order, OrderSummary, Page, Payment, SagaView } from '@/lib/types';
import { date, humanize, money, shortId } from '@/lib/format';

const ORDER_FILTERS: [string, string][] = [['', 'All'], ['PENDING', 'Pending'], ['CONFIRMED', 'Confirmed'], ['SHIPPED', 'Shipped'],
  ['DELIVERED', 'Delivered'], ['CANCELLED', 'Cancelled'], ['REFUND_FAILED', 'Refund failed'], ['FAILED', 'Failed']];

export function AdminOrdersPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: ['admin', 'orders', status, page],
    queryFn: () => http.get<Page<OrderSummary>>(`/api/v1/admin/orders${qs({ status, page, size: 20 })}`),
  });
  return (
    <div>
      <PageHeader title="Orders" />
      <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Filter by status">
        {ORDER_FILTERS.map(([v, t]) => <button key={v} className={status === v ? 'btn-primary py-1.5' : 'btn-quiet py-1.5'} onClick={() => { setStatus(v); setPage(0); }}>{t}</button>)}
      </div>
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : list.data!.items.length === 0 ? <EmptyState title="No orders here" /> : (
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>Order</th><th>Placed</th><th>Sellers</th><th>Items</th><th className="text-right">Total</th><th>Status</th></tr></thead>
            <tbody>
              {list.data!.items.map((o) => (
                <tr key={o.id}>
                  <td><Link to={`/admin/orders/${o.id}`} className="font-semibold hover:underline">{shortId(o.id)}</Link></td>
                  <td className="text-slate">{date(o.createdAt, true)}</td>
                  <td className="tabular">{o.vendorCount}</td>
                  <td className="tabular">{o.itemCount}</td>
                  <td className="text-right tabular">{money(o.grandTotal, o.currency)}</td>
                  <td><StatusBadge status={o.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onPage={setPage} />}
    </div>
  );
}

function usePaymentForOrder(orderId?: string) {
  return useQuery({
    queryKey: ['admin', 'payment', orderId],
    queryFn: async () => {
      try {
        return await http.get<Payment>(`/api/v1/payments/by-order/${orderId}`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: !!orderId,
  });
}

export function AdminOrderDetailPage() {
  const { orderId } = useParams();
  const order = useQuery({ queryKey: ['admin', 'order', orderId], queryFn: () => http.get<Order>(`/api/v1/admin/orders/${orderId}`) });
  const saga = useQuery({ queryKey: ['admin', 'saga', orderId], queryFn: () => http.get<SagaView>(`/api/v1/admin/orders/${orderId}/saga`) });
  const payment = usePaymentForOrder(orderId);
  const [refunding, setRefunding] = useState(false);
  if (order.isLoading) return <PageSpinner />;
  if (order.isError) return <ErrorState error={order.error} onRetry={() => order.refetch()} />;
  const o = order.data!;
  const p = payment.data;
  const refundable = p && (p.status === 'CAPTURED' || p.status === 'PARTIALLY_REFUNDED') && Number(p.amount) > Number(p.refundedAmount);
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={`Order ${shortId(o.id)}`} description={<span className="inline-flex items-center gap-2"><StatusBadge status={o.status} /> Placed {date(o.createdAt, true)}</span>}
        actions={<Link to="/admin/orders" className="btn-quiet">All orders</Link>} />
      <div className="grid gap-6 lg:grid-cols-[1fr_24rem]">
        <div className="flex flex-col gap-6">
          {o.suborders.map((s) => (
            <section key={s.id} className="panel">
              <div className="flex items-center justify-between border-b border-line px-5 py-3"><h2 className="text-lg font-bold">{s.vendorName}</h2><StatusBadge status={s.status} /></div>
              <ul className="divide-y divide-line px-5 text-sm">
                {s.items.map((i) => <li key={i.id} className="flex justify-between gap-3 py-2"><span>{i.quantity} × {i.productName} <span className="text-slate">({i.sku})</span></span><span className="tabular">{money(i.lineTotal, o.currency)}</span></li>)}
              </ul>
              <div className="flex justify-between border-t border-line px-5 py-2 text-sm text-slate"><span>Shipping {money(s.shippingAmount)}, tax {money(s.taxAmount)}{s.trackingNumber ? `, tracking ${s.carrier} ${s.trackingNumber}` : ''}</span><span className="font-semibold text-ink tabular">{money(s.total, o.currency)}</span></div>
            </section>
          ))}
          <section className="panel p-5">
            <h2 className="mb-3 text-lg font-bold">Checkout saga</h2>
            {saga.isLoading ? <PageSpinner /> : saga.isError ? <ErrorState error={saga.error} /> : <SagaTimeline saga={saga.data!} />}
          </section>
        </div>
        <aside className="flex flex-col gap-6">
          <section className="panel p-5 text-sm">
            <h2 className="mb-3 text-lg font-bold">Totals</h2>
            <dl className="grid grid-cols-2 gap-y-1">
              <dt className="text-slate">Items</dt><dd className="text-right tabular">{money(o.itemsSubtotal)}</dd>
              <dt className="text-slate">Discount{o.couponCode ? ` (${o.couponCode})` : ''}</dt><dd className="text-right tabular">{money(-o.discountTotal)}</dd>
              <dt className="text-slate">Shipping</dt><dd className="text-right tabular">{money(o.shippingTotal)}</dd>
              <dt className="text-slate">Tax</dt><dd className="text-right tabular">{money(o.taxTotal)}</dd>
              <dt className="font-semibold">Total</dt><dd className="text-right font-semibold tabular">{money(o.grandTotal, o.currency)}</dd>
            </dl>
            {o.cancelReason && <p className="mt-3 text-slate">Cancelled: {o.cancelReason}</p>}
          </section>
          <section className="panel p-5 text-sm">
            <div className="mb-3 flex items-center justify-between"><h2 className="text-lg font-bold">Payment</h2>{p && <StatusBadge status={p.status} />}</div>
            {payment.isLoading ? <PageSpinner /> : payment.isError ? <ErrorState error={payment.error} /> : !p ? <p className="text-slate">No payment was created for this order.</p> : (
              <>
                <dl className="grid grid-cols-2 gap-y-1">
                  <dt className="text-slate">Amount</dt><dd className="text-right tabular">{money(p.amount, p.currency)}</dd>
                  <dt className="text-slate">Refunded</dt><dd className="text-right tabular">{money(p.refundedAmount, p.currency)}</dd>
                  {p.failureCode && <><dt className="text-slate">Failure</dt><dd className="text-right">{humanize(p.failureCode)}</dd></>}
                  <dt className="text-slate">Authorised</dt><dd className="text-right">{date(p.authorizedAt, true)}</dd>
                  <dt className="text-slate">Captured</dt><dd className="text-right">{date(p.capturedAt, true)}</dd>
                </dl>
                {p.refunds.length > 0 && (
                  <ul className="mt-3 flex flex-col gap-1 border-t border-line pt-3">
                    {p.refunds.map((r) => <li key={r.id} className="flex justify-between gap-2"><span>{r.reason}</span><span className="flex items-center gap-2 tabular">{money(r.amount)} <StatusBadge status={r.status} /></span></li>)}
                  </ul>
                )}
                {refundable && <button className="btn-quiet mt-4 w-full" onClick={() => setRefunding(true)}>Issue refund</button>}
              </>
            )}
          </section>
          <section className="panel p-5 text-sm">
            <h2 className="mb-3 text-lg font-bold">Ship to</h2>
            <address className="not-italic text-slate">{o.shippingAddress.recipient}<br />{o.shippingAddress.line1}<br />{o.shippingAddress.city} {o.shippingAddress.postalCode}, {o.shippingAddress.country}</address>
          </section>
        </aside>
      </div>
      {refunding && p && <RefundDialog payment={p} onClose={() => setRefunding(false)} />}
    </div>
  );
}

function SagaTimeline({ saga }: { saga: SagaView }) {
  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
        <StatusBadge status={saga.state} />
        <span className="text-slate">Attempt {saga.attempt}</span>
        {saga.compensationReason && <span className="text-brick">Compensating because: {saga.compensationReason}</span>}
      </div>
      <ol className="relative ml-2 border-l border-line">
        {saga.transitions.map((t, i) => (
          <li key={i} className="mb-4 ml-5">
            <span className="absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border-2 border-surface bg-ink" aria-hidden />
            <div className="text-sm"><span className="font-semibold">{humanize(t.to)}</span> <span className="text-slate">after {t.trigger}</span></div>
            <div className="text-xs text-slate">{date(t.at, true)}{t.detail ? `. ${t.detail}` : ''}</div>
          </li>
        ))}
      </ol>
      {saga.lastError && <p className="text-sm text-brick">Last error: {saga.lastError}</p>}
    </div>
  );
}

function RefundDialog({ payment, onClose }: { payment: Payment; onClose: () => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const remaining = Math.round((Number(payment.amount) - Number(payment.refundedAmount)) * 100) / 100;
  const [amount, setAmount] = useState(String(remaining));
  const [reason, setReason] = useState('');
  // One key per dialog: a retried click cannot refund twice.
  const key = useRef(crypto.randomUUID());
  const n = Number(amount);
  const valid = n >= 0.01 && n <= remaining && Math.round(n * 100) === n * 100 && reason.trim().length >= 3;
  const refund = useMutation({
    mutationFn: () => http.post(`/api/v1/payments/${payment.id}/refunds`, { amount: n, reason: reason.trim() }, { 'Idempotency-Key': key.current }),
    onSuccess: () => { toast.ok('Refund requested'); void qc.invalidateQueries({ queryKey: ['admin'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} title="Issue a refund">
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); if (valid) refund.mutate(); }}>
        <Field label="Amount" htmlFor="r-amount" hint={`Up to ${money(remaining, payment.currency)}`}><Input id="r-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <Field label="Reason" htmlFor="r-reason"><Textarea id="r-reason" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <InlineError error={refund.error} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!valid || refund.isPending}>Refund {valid ? money(n, payment.currency) : ''}</button>
        </div>
      </form>
    </Dialog>
  );
}

const PAYMENT_FILTERS: [string, string][] = [['', 'All'], ['CAPTURED', 'Captured'], ['AUTHORIZED', 'Authorised'], ['UNKNOWN', 'Awaiting reconciliation'],
  ['DECLINED', 'Declined'], ['CAPTURE_FAILED', 'Capture failed'], ['VOIDED', 'Voided'], ['REFUNDED', 'Refunded']];

export function AdminPaymentsPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const summary = useQuery({ queryKey: ['admin', 'payments', 'summary'], queryFn: () => http.get<{ status: string; count: number; amount: number; refunded: number }[]>('/api/v1/admin/payments/summary') });
  const list = useQuery({
    queryKey: ['admin', 'payments', status, page],
    queryFn: () => http.get<Page<Payment>>(`/api/v1/admin/payments${qs({ status, page, size: 20 })}`),
  });
  return (
    <div>
      <PageHeader title="Payments" description="Payments in the sandbox provider. Unknown outcomes are settled by the reconciliation job." />
      {summary.data && (
        <div className="mb-6 flex flex-wrap gap-2">
          {summary.data.map((s) => (
            <div key={s.status} className="panel px-4 py-2 text-sm"><StatusBadge status={s.status} /><div className="mt-1 font-semibold tabular">{s.count} for {money(s.amount)}</div></div>
          ))}
        </div>
      )}
      <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Filter by status">
        {PAYMENT_FILTERS.map(([v, t]) => <button key={v} className={status === v ? 'btn-primary py-1.5' : 'btn-quiet py-1.5'} onClick={() => { setStatus(v); setPage(0); }}>{t}</button>)}
      </div>
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : list.data!.items.length === 0 ? <EmptyState title="No payments here" /> : (
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>Order</th><th>Created</th><th className="text-right">Amount</th><th className="text-right">Refunded</th><th>Status</th><th>Failure</th></tr></thead>
            <tbody>
              {list.data!.items.map((p) => (
                <tr key={p.id}>
                  <td><Link to={`/admin/orders/${p.orderId}`} className="font-semibold hover:underline">{shortId(p.orderId)}</Link></td>
                  <td className="text-slate">{date(p.createdAt, true)}</td>
                  <td className="text-right tabular">{money(p.amount, p.currency)}</td>
                  <td className="text-right tabular">{Number(p.refundedAmount) > 0 ? money(p.refundedAmount, p.currency) : '—'}</td>
                  <td><StatusBadge status={p.status} /></td>
                  <td className="text-slate">{p.failureCode ? humanize(p.failureCode) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onPage={setPage} />}
    </div>
  );
}
