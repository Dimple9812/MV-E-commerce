import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { Fragment, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useToast } from '@/components/Toast';
import { Dialog, EmptyState, ErrorState, Field, InlineError, Input, PageHeader, PageSpinner, Pagination, Stat, StatusBadge } from '@/components/ui';
import { http, qs } from '@/lib/api';
import type { EarningsEntry, InventoryItem, Page, Payout, StockMovement, Vendor, VendorSuborder } from '@/lib/types';
import { date, humanize, money, relative, shortId } from '@/lib/format';
import { useVendorDashboard } from './hooks';
import { VendorProfileFields } from './SellPage';
import { vendorProfileSchema, type VendorProfileForm } from './vendorSchema';

// ---------- inventory ----------

export function VendorInventory() {
  const [params, setParams] = useSearchParams();
  const lowOnly = params.get('low') === '1';
  const [page, setPage] = useState(0);
  const [adjusting, setAdjusting] = useState<InventoryItem | null>(null);
  const [history, setHistory] = useState<InventoryItem | null>(null);
  const list = useQuery({
    queryKey: ['vendor', 'inventory', lowOnly, page],
    queryFn: () => http.get<Page<InventoryItem>>(`/api/v1/vendor/inventory${qs({ lowStockOnly: lowOnly, page, size: 50 })}`),
  });
  return (
    <div>
      <PageHeader title="Inventory" description="Available stock can be sold. Reserved stock is held for checkouts in progress and returns to available if payment fails." />
      <label className="mb-4 flex items-center gap-2 text-sm">
        <input type="checkbox" checked={lowOnly} className="accent-[rgb(var(--ink))]"
          onChange={(e) => { setPage(0); setParams(e.target.checked ? { low: '1' } : {}); }} />
        Only show variants at or below their low-stock threshold
      </label>
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> :
        list.data!.items.length === 0 ? (
          <EmptyState title={lowOnly ? 'Nothing is running low' : 'No stock records yet'}>
            {!lowOnly && 'Stock records appear here a moment after you create a product variant.'}
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>Variant</th><th className="text-right">Available</th><th className="text-right">Reserved</th><th className="text-right">Sold</th><th>Threshold</th><th><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>
                {list.data!.items.map((i) => (
                  <tr key={i.variantId}>
                    <td><div className="font-medium">{i.productName}</div><div className="text-xs text-slate">{i.variantName}, {i.sku}</div></td>
                    <td className={`text-right font-semibold tabular ${i.lowStock ? 'text-brick' : ''}`}>{i.available}</td>
                    <td className="text-right tabular">{i.reserved}</td>
                    <td className="text-right tabular">{i.sold}</td>
                    <td><ThresholdInput item={i} /></td>
                    <td className="whitespace-nowrap text-right">
                      <button className="mr-3 text-sm font-semibold" onClick={() => setAdjusting(i)}>Adjust</button>
                      <button className="text-sm text-slate hover:text-ink" onClick={() => setHistory(i)}>History</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onPage={setPage} />}
      {adjusting && <AdjustDialog item={adjusting} onClose={() => setAdjusting(null)} />}
      {history && <MovementsDialog item={history} onClose={() => setHistory(null)} />}
    </div>
  );
}

function ThresholdInput({ item }: { item: InventoryItem }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [value, setValue] = useState(String(item.lowStockThreshold));
  const save = useMutation({
    mutationFn: (threshold: number) => http.put(`/api/v1/vendor/inventory/${item.variantId}/threshold`, { threshold }),
    onSuccess: () => { toast.ok('Threshold saved'); void qc.invalidateQueries({ queryKey: ['vendor', 'inventory'] }); },
    onError: (e) => toast.error(e),
  });
  const n = Number(value);
  const valid = Number.isInteger(n) && n >= 0 && n <= 100000;
  return (
    <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); if (valid) save.mutate(n); }}>
      <label htmlFor={`t-${item.variantId}`} className="sr-only">Low-stock threshold for {item.sku}</label>
      <Input id={`t-${item.variantId}`} type="number" min={0} value={value} onChange={(e) => setValue(e.target.value)} className="w-20 py-1.5" />
      {value !== String(item.lowStockThreshold) && <button className="text-sm font-semibold" disabled={!valid || save.isPending}>Save</button>}
    </form>
  );
}

const adjustSchema = z.object({
  delta: z.coerce.number().int('Whole units only').min(-100000).max(100000).refine((v) => v !== 0, 'Enter a non-zero change'),
  reason: z.string().trim().min(3, 'Say why, for example "Received shipment"').max(300),
});
type AdjustForm = z.infer<typeof adjustSchema>;

function AdjustDialog({ item, onClose }: { item: InventoryItem; onClose: () => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const form = useForm<AdjustForm>({ resolver: zodResolver(adjustSchema), defaultValues: { reason: '' } });
  const adjust = useMutation({
    mutationFn: (v: AdjustForm) => http.post<InventoryItem>('/api/v1/vendor/inventory/adjustments', { variantId: item.variantId, ...v }),
    onSuccess: (r) => {
      toast.ok(`Stock for ${item.sku} is now ${r.available}`);
      void qc.invalidateQueries({ queryKey: ['vendor', 'inventory'] });
      onClose();
    },
  });
  const delta = Number(form.watch('delta')) || 0;
  return (
    <Dialog open onClose={onClose} title={`Adjust stock for ${item.sku}`}>
      <form className="flex flex-col gap-4" onSubmit={form.handleSubmit((v) => adjust.mutate(v))} noValidate>
        <Field label="Change" htmlFor="adj-delta" hint="Use a negative number to remove units, for example -2 for damaged items" error={form.formState.errors.delta?.message}>
          <Input id="adj-delta" type="number" {...form.register('delta')} autoFocus />
        </Field>
        <p className="text-sm text-slate">Available goes from <span className="font-semibold text-ink tabular">{item.available}</span> to <span className="font-semibold text-ink tabular">{item.available + delta}</span>. Stock cannot go below zero.</p>
        <Field label="Reason" htmlFor="adj-reason" error={form.formState.errors.reason?.message}><Input id="adj-reason" {...form.register('reason')} /></Field>
        <InlineError error={adjust.error} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={adjust.isPending}>Save adjustment</button>
        </div>
      </form>
    </Dialog>
  );
}

function MovementsDialog({ item, onClose }: { item: InventoryItem; onClose: () => void }) {
  const moves = useQuery({
    queryKey: ['vendor', 'movements', item.variantId],
    queryFn: () => http.get<StockMovement[]>(`/api/v1/vendor/inventory/${item.variantId}/movements`),
  });
  const signed = (n: number) => (n > 0 ? `+${n}` : n === 0 ? '' : String(n));
  return (
    <Dialog open onClose={onClose} title={`Stock history for ${item.sku}`}>
      {moves.isLoading ? <PageSpinner /> : moves.isError ? <ErrorState error={moves.error} /> : moves.data!.length === 0 ? <p className="text-slate">No movements yet.</p> : (
        <div className="max-h-[60vh] overflow-y-auto">
          <table className="data-table">
            <thead><tr><th>When</th><th>What</th><th className="text-right">Avail.</th><th className="text-right">Res.</th><th className="text-right">Sold</th></tr></thead>
            <tbody>
              {moves.data!.map((m) => (
                <tr key={m.id}>
                  <td className="whitespace-nowrap text-slate">{relative(m.createdAt)}</td>
                  <td>{humanize(m.type)}{m.reason && <div className="text-xs text-slate">{m.reason}</div>}</td>
                  <td className="text-right tabular">{signed(m.deltaAvailable)}</td>
                  <td className="text-right tabular">{signed(m.deltaReserved)}</td>
                  <td className="text-right tabular">{signed(m.deltaSold)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="mt-4 flex justify-end"><button className="btn-quiet" onClick={onClose}>Close</button></div>
    </Dialog>
  );
}

// ---------- suborders ----------

const SUB_STATUSES = ['', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'PENDING'] as const;

export function VendorSuborders() {
  const [status, setStatus] = useState<string>('CONFIRMED');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const [shipping, setShipping] = useState<VendorSuborder | null>(null);
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery({
    queryKey: ['vendor', 'suborders', status, page],
    queryFn: () => http.get<Page<VendorSuborder>>(`/api/v1/vendor/suborders${qs({ status, page, size: 20 })}`),
  });
  const deliver = useMutation({
    mutationFn: (id: string) => http.post(`/api/v1/vendor/suborders/${id}/deliver`),
    onSuccess: () => { toast.ok('Marked as delivered'); void qc.invalidateQueries({ queryKey: ['vendor', 'suborders'] }); },
    onError: (e) => toast.error(e),
  });
  return (
    <div>
      <PageHeader title="Orders to fulfil" description="Each order shows only your items. Ship confirmed orders and add a tracking number so the customer is notified." />
      <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Filter by status">
        {SUB_STATUSES.map((s) => (
          <button key={s} className={status === s ? 'btn-primary py-1.5' : 'btn-quiet py-1.5'} onClick={() => { setStatus(s); setPage(0); }}>
            {s ? (s === 'CONFIRMED' ? 'To ship' : humanize(s)) : 'All'}
          </button>
        ))}
      </div>
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> :
        list.data!.items.length === 0 ? <EmptyState title={status === 'CONFIRMED' ? 'Nothing waiting to ship' : 'No orders here'}>Paid orders appear here as soon as checkout completes.</EmptyState> : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th><span className="sr-only">Expand</span></th><th>Order</th><th>Placed</th><th>Items</th><th className="text-right">Your net</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>
                {list.data!.items.map((s) => (
                  <Fragment key={s.id}>
                    <tr>
                      <td className="w-8"><button aria-label={open === s.id ? 'Hide details' : 'Show details'} aria-expanded={open === s.id} onClick={() => setOpen(open === s.id ? null : s.id)}>
                        {open === s.id ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}</button></td>
                      <td className="font-semibold">{shortId(s.orderId)}</td>
                      <td className="text-slate">{date(s.orderedAt, true)}</td>
                      <td>{s.items.reduce((n, i) => n + i.quantity, 0)} units</td>
                      <td className="text-right tabular">{money(s.vendorNetAmount)}</td>
                      <td><StatusBadge status={s.status} /></td>
                      <td className="whitespace-nowrap text-right">
                        {s.status === 'CONFIRMED' && <button className="btn-primary py-1.5" onClick={() => setShipping(s)}>Ship</button>}
                        {s.status === 'SHIPPED' && <button className="btn-quiet py-1.5" disabled={deliver.isPending} onClick={() => deliver.mutate(s.id)}>Mark delivered</button>}
                      </td>
                    </tr>
                    {open === s.id && (
                      <tr><td colSpan={7} className="bg-mist/50">
                        <div className="grid gap-6 py-2 md:grid-cols-2">
                          <ul className="flex flex-col gap-1 text-sm">
                            {s.items.map((i) => <li key={i.id} className="flex justify-between gap-3"><span>{i.quantity} × {i.productName} <span className="text-slate">({i.variantName}, {i.sku})</span></span><span className="tabular">{money(i.lineTotal)}</span></li>)}
                            <li className="mt-2 flex justify-between border-t border-line pt-2 text-slate"><span>Commission at {(s.commissionRate * 100).toFixed(1)}%</span><span className="tabular">{money(-s.commissionAmount)}</span></li>
                          </ul>
                          <div className="text-sm">
                            <div className="font-semibold">Ship to</div>
                            <address className="not-italic text-slate">{s.shippingAddress.recipient}<br />{s.shippingAddress.line1}{s.shippingAddress.line2 ? `, ${s.shippingAddress.line2}` : ''}<br />{s.shippingAddress.city} {s.shippingAddress.postalCode}, {s.shippingAddress.country}</address>
                            {s.trackingNumber && <p className="mt-2">Tracking: {s.carrier} {s.trackingNumber}</p>}
                          </div>
                        </div>
                      </td></tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onPage={setPage} />}
      {shipping && <ShipDialog suborder={shipping} onClose={() => setShipping(null)} />}
    </div>
  );
}

const shipSchema = z.object({
  carrier: z.string().trim().min(2, 'Which carrier?').max(60),
  trackingNumber: z.string().trim().min(4, 'Enter the tracking number').max(100).regex(/^[A-Za-z0-9-]+$/, 'Letters, digits and dashes only'),
});
type ShipForm = z.infer<typeof shipSchema>;

function ShipDialog({ suborder, onClose }: { suborder: VendorSuborder; onClose: () => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const form = useForm<ShipForm>({ resolver: zodResolver(shipSchema) });
  const ship = useMutation({
    mutationFn: (v: ShipForm) => http.post(`/api/v1/vendor/suborders/${suborder.id}/ship`, v),
    onSuccess: () => { toast.ok('Shipped. The customer has been notified.'); void qc.invalidateQueries({ queryKey: ['vendor', 'suborders'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} title={`Ship order ${shortId(suborder.orderId)}`}>
      <form className="flex flex-col gap-4" onSubmit={form.handleSubmit((v) => ship.mutate(v))} noValidate>
        <Field label="Carrier" htmlFor="s-carrier" error={form.formState.errors.carrier?.message}><Input id="s-carrier" placeholder="UPS, DHL, USPS…" {...form.register('carrier')} /></Field>
        <Field label="Tracking number" htmlFor="s-track" error={form.formState.errors.trackingNumber?.message}><Input id="s-track" {...form.register('trackingNumber')} /></Field>
        <InlineError error={ship.error} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={ship.isPending}>Mark as shipped</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- earnings ----------

export function VendorEarnings() {
  const [page, setPage] = useState(0);
  const dash = useVendorDashboard();
  const entries = useQuery({
    queryKey: ['vendor', 'earnings', page],
    queryFn: () => http.get<Page<EarningsEntry>>(`/api/v1/vendor/earnings?page=${page}&size=20`),
  });
  const payouts = useQuery({ queryKey: ['vendor', 'payouts'], queryFn: () => http.get<Payout[]>('/api/v1/vendor/earnings/payouts') });
  const e = dash.data?.earnings;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Earnings" description="A sale is recorded when an order is confirmed; a refund reverses it. Commission applies to the item subtotal after discounts." />
      {e && (
        <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-label="Summary">
          <Stat label="Gross sales" value={money(e.grossSales)} />
          <Stat label="Commission" value={money(e.commission)} sub={`Rate ${(e.commissionRate * 100).toFixed(1)}%`} />
          <Stat label="Net earnings" value={money(e.netEarnings)} />
          <Stat label="Balance" value={money(e.balance)} sub={`${money(e.paidOut)} paid out`} />
        </section>
      )}
      <section>
        <h2 className="mb-3 text-xl font-bold">Ledger</h2>
        {entries.isLoading ? <PageSpinner /> : entries.isError ? <ErrorState error={entries.error} onRetry={() => entries.refetch()} /> :
          entries.data!.items.length === 0 ? <EmptyState title="No earnings yet">Entries appear when your first order is confirmed.</EmptyState> : (
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th>Date</th><th>Order</th><th>Type</th><th className="text-right">Gross</th><th className="text-right">Commission</th><th className="text-right">Net</th></tr></thead>
                <tbody>
                  {entries.data!.items.map((x) => (
                    <tr key={x.id}>
                      <td className="text-slate">{date(x.occurredAt)}</td>
                      <td className="font-medium">{shortId(x.orderId)}</td>
                      <td><StatusBadge status={x.type} /></td>
                      <td className="text-right tabular">{money(x.grossAmount, x.currency)}</td>
                      <td className="text-right tabular">{money(x.commission, x.currency)}</td>
                      <td className="text-right font-semibold tabular">{money(x.netAmount, x.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        {entries.data && <Pagination page={page} totalPages={entries.data.totalPages} onPage={setPage} />}
      </section>
      <section>
        <h2 className="mb-3 text-xl font-bold">Payouts</h2>
        {payouts.data?.length ? (
          <ul className="panel divide-y divide-line">
            {payouts.data.map((p) => (
              <li key={p.id} className="flex items-center justify-between gap-3 px-5 py-3 text-sm">
                <span><span className="font-medium">{p.reference}</span> <span className="text-slate">{date(p.createdAt)}</span></span>
                <span className="font-semibold tabular">{money(p.amount, p.currency)}</span>
              </li>
            ))}
          </ul>
        ) : <p className="text-slate">No payouts recorded yet. Payouts are settled by the marketplace team.</p>}
      </section>
    </div>
  );
}

// ---------- profile ----------

export function VendorProfile() {
  const dash = useVendorDashboard();
  const qc = useQueryClient();
  const toast = useToast();
  const v = dash.data?.vendor;
  const form = useForm<VendorProfileForm>({
    resolver: zodResolver(vendorProfileSchema),
    values: v && { businessName: v.businessName, description: v.description ?? '', contactEmail: v.contactEmail, contactPhone: v.contactPhone ?? '', country: v.country },
  });
  const save = useMutation({
    mutationFn: (body: VendorProfileForm) => http.patch<Vendor>('/api/v1/vendor/profile', body),
    onSuccess: () => { toast.ok('Shop profile saved'); void qc.invalidateQueries({ queryKey: ['vendor'] }); },
  });
  if (dash.isLoading) return <PageSpinner />;
  if (dash.isError) return <ErrorState error={dash.error} onRetry={() => dash.refetch()} />;
  return (
    <div className="max-w-2xl">
      <PageHeader title="Shop profile" description="Your shop name and description appear on your storefront and product pages." />
      <form className="panel flex flex-col gap-5 p-5" onSubmit={form.handleSubmit((b) => save.mutate(b))} noValidate>
        <VendorProfileFields form={form} />
        <InlineError error={save.error} />
        <button className="btn-primary self-start" disabled={save.isPending || !form.formState.isDirty}>Save profile</button>
      </form>
    </div>
  );
}
