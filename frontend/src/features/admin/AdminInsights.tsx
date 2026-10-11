import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { EmptyState, ErrorState, Input, PageHeader, PageSpinner, Pagination, Select, Stat, StatusBadge } from '@/components/ui';
import { BarList, TrendChart } from '@/components/charts';
import { FreshnessNote } from '@/features/vendor/VendorDashboard';
import { http, qs } from '@/lib/api';
import type { AdminOverview, AuditEntry, Page, SagaView } from '@/lib/types';
import { date, humanize, money, relative, shortId } from '@/lib/format';

export function AdminOverviewPage() {
  const [days, setDays] = useState(30);
  const q = useQuery({
    queryKey: ['admin', 'overview', days],
    queryFn: () => http.get<AdminOverview>(`/api/v1/admin/analytics/overview?days=${days}`),
    refetchInterval: 30_000,
  });
  if (q.isLoading) return <PageSpinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const o = q.data!;
  const ops = o.operations;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Marketplace overview" description={<FreshnessNote at={o.lastEventAt} />}
        actions={<label className="flex items-center gap-2 text-sm text-slate">Period
          <Select value={days} onChange={(e) => setDays(Number(e.target.value))} className="w-auto py-1.5">
            <option value={7}>7 days</option><option value={30}>30 days</option><option value={90}>90 days</option>
          </Select></label>} />

      {(ops.deadLetters24h > 0 || ops.stuckPendingOrders > 0 || ops.unresolvedPayments > 0) && (
        <Link to="/admin/operations" className="flex flex-wrap items-center gap-x-6 gap-y-1 rounded-xl border border-tag-deep/40 bg-tag/15 px-5 py-3 text-sm">
          <span className="font-semibold">Needs attention</span>
          {ops.deadLetters24h > 0 && <span>{ops.deadLetters24h} dead-lettered events in 24 h</span>}
          {ops.stuckPendingOrders > 0 && <span>{ops.stuckPendingOrders} orders pending over 10 min</span>}
          {ops.unresolvedPayments > 0 && <span>{ops.unresolvedPayments} payments awaiting reconciliation</span>}
        </Link>
      )}

      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-label="Totals">
        <Stat label="Gross merchandise value" value={money(o.totals.gmv)} sub={`${o.totals.confirmed} confirmed orders`} />
        <Stat label="Average order value" value={money(o.totals.averageOrderValue)} />
        <Stat label="Payment success rate" value={o.paymentRates.successRate === null ? '—' : `${Number(o.paymentRates.successRate).toFixed(1)}%`}
          sub={`${o.paymentRates.succeeded} succeeded, ${o.paymentRates.failed} failed`} />
        <Stat label="Refunded" value={money(o.totals.refundTotal)} sub={`${o.totals.cancelled} cancelled orders`} />
      </section>

      <section className="panel p-5">
        <h2 className="mb-4 text-xl font-bold">Gross merchandise value per day</h2>
        <TrendChart data={o.ordersOverTime} valueKey="gmv" label="GMV" asMoney />
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="panel p-5">
          <h2 className="mb-3 text-xl font-bold">Sales by seller</h2>
          {o.salesByVendor.length === 0 ? <p className="text-slate">No confirmed sales in this period.</p> :
            <BarList data={o.salesByVendor.slice(0, 8)} nameKey="vendor_name" valueKey="revenue" label="Revenue" asMoney />}
        </section>
        <section className="panel p-5">
          <h2 className="mb-3 text-xl font-bold">Best-selling products</h2>
          {o.bestSellers.length === 0 ? <p className="text-slate">No confirmed sales in this period.</p> :
            <BarList data={o.bestSellers.slice(0, 8)} nameKey="product_name" valueKey="units" label="Units" />}
        </section>
        <section className="panel p-5">
          <h2 className="mb-3 text-xl font-bold">Orders by status</h2>
          <ul className="flex flex-col gap-2 text-sm">
            {o.statusDistribution.map((s) => (
              <li key={s.status} className="flex items-center justify-between"><StatusBadge status={s.status} /><span className="font-semibold tabular">{s.count}</span></li>
            ))}
          </ul>
        </section>
        <section className="panel p-5">
          <h2 className="mb-3 text-xl font-bold">Low stock across sellers</h2>
          {o.inventoryAlerts.length === 0 ? <p className="text-slate">No open low-stock alerts.</p> : (
            <ul className="divide-y divide-line text-sm">
              {o.inventoryAlerts.slice(0, 8).map((a) => (
                <li key={a.variant_id} className="flex justify-between gap-3 py-2">
                  <span><span className="font-medium">{a.product_name}</span> <span className="text-slate">{a.sku}{a.business_name ? `, ${a.business_name}` : ''}</span></span>
                  <span className="font-semibold text-brick tabular">{a.available}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}

// ---------- operations ----------

interface DeadLetter { topic: string; partition_no: number; offset_no: number; consumer: string; exception: string; event_type?: string; event_id?: string; received_at: string }
interface FailedNotification { id: string; template: string; channel: string; attempts: number; last_error?: string; created_at: string; recipient: string }

const STUCK_STATES = ['RECONCILIATION_REQUIRED', 'COMPENSATING', 'PAYMENT_PENDING', 'INVENTORY_PENDING', 'INVENTORY_CONFIRM_PENDING', 'FAILED'];

export function AdminOperationsPage() {
  const summary = useQuery({ queryKey: ['admin', 'sagas', 'summary'], queryFn: () => http.get<Record<string, number>>('/api/v1/admin/sagas/summary'), refetchInterval: 15_000 });
  const [state, setState] = useState('RECONCILIATION_REQUIRED');
  const sagas = useQuery({ queryKey: ['admin', 'sagas', state], queryFn: () => http.get<SagaView[]>(`/api/v1/admin/sagas?state=${state}&limit=50`) });
  const dlt = useQuery({ queryKey: ['admin', 'dlt'], queryFn: () => http.get<DeadLetter[]>('/api/v1/admin/analytics/dead-letters?limit=50') });
  const failed = useQuery({ queryKey: ['admin', 'notifications', 'failed'], queryFn: () => http.get<FailedNotification[]>('/api/v1/admin/notifications/failed?limit=50') });

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Operations" description="Checkout sagas, dead-lettered events and undelivered notifications. Replays are run from the runbook, not from this screen." />
      <section>
        <h2 className="mb-3 text-xl font-bold">Checkout sagas by state</h2>
        {summary.isError ? <ErrorState error={summary.error} /> : (
          <div className="flex flex-wrap gap-2">
            {Object.entries(summary.data ?? {}).sort().map(([s, n]) => (
              <button key={s} onClick={() => setState(s)} className={`rounded-lg border px-3 py-2 text-left text-sm ${state === s ? 'border-ink bg-ink text-mist' : 'border-line bg-surface'}`}>
                <div className="font-semibold tabular">{n}</div><div className="text-xs opacity-80">{humanize(s)}</div>
              </button>
            ))}
          </div>
        )}
        <div className="mt-4">
          {sagas.isLoading ? <PageSpinner /> : sagas.isError ? <ErrorState error={sagas.error} /> : sagas.data!.length === 0 ? (
            <p className="text-slate">No sagas in {humanize(state).toLowerCase()}.{STUCK_STATES.includes(state) ? ' Good.' : ''}</p>
          ) : (
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th>Order</th><th>State</th><th>Attempt</th><th>Deadline</th><th>Last error</th><th>Updated</th></tr></thead>
                <tbody>
                  {sagas.data!.map((s) => (
                    <tr key={s.orderId}>
                      <td><Link to={`/admin/orders/${s.orderId}`} className="font-semibold hover:underline">{shortId(s.orderId)}</Link></td>
                      <td><StatusBadge status={s.state} /></td>
                      <td className="tabular">{s.attempt}</td>
                      <td className="text-slate">{date(s.deadlineAt, true)}</td>
                      <td className="max-w-xs truncate text-slate" title={s.lastError ?? s.compensationReason ?? ''}>{s.lastError ?? s.compensationReason ?? '—'}</td>
                      <td className="text-slate">{relative(s.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

      <section>
        <h2 className="mb-1 text-xl font-bold">Dead-lettered events</h2>
        <p className="mb-3 text-sm text-slate">Messages a consumer could not process after its retries. See docs/runbooks/dlt-replay.md to inspect and replay them.</p>
        {dlt.isLoading ? <PageSpinner /> : dlt.isError ? <ErrorState error={dlt.error} /> : dlt.data!.length === 0 ? <EmptyState title="No dead letters">Every event has been processed.</EmptyState> : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>Received</th><th>Topic</th><th>Consumer</th><th>Event</th><th>Error</th></tr></thead>
              <tbody>
                {dlt.data!.map((d) => (
                  <tr key={`${d.topic}-${d.partition_no}-${d.offset_no}`}>
                    <td className="whitespace-nowrap text-slate">{relative(d.received_at)}</td>
                    <td className="font-medium">{d.topic}<div className="text-xs text-slate">partition {d.partition_no}, offset {d.offset_no}</div></td>
                    <td>{d.consumer}</td>
                    <td>{d.event_type ?? '—'}{d.event_id && <div className="text-xs text-slate">{d.event_id}</div>}</td>
                    <td className="max-w-sm break-words text-brick">{d.exception}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-xl font-bold">Undelivered notifications</h2>
        {failed.isLoading ? <PageSpinner /> : failed.isError ? <ErrorState error={failed.error} /> : failed.data!.length === 0 ? <p className="text-slate">All notifications were delivered.</p> : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>Created</th><th>Template</th><th>Recipient</th><th>Attempts</th><th>Last error</th></tr></thead>
              <tbody>
                {failed.data!.map((n) => (
                  <tr key={n.id}>
                    <td className="text-slate">{relative(n.created_at)}</td>
                    <td>{humanize(n.template)}</td>
                    <td>{n.recipient}</td>
                    <td className="tabular">{n.attempts}</td>
                    <td className="text-brick">{n.last_error ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

// ---------- audit ----------

export function AdminAuditPage() {
  const [action, setAction] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ['admin', 'audit', action, resourceId, page],
    queryFn: () => http.get<Page<AuditEntry>>(`/api/v1/admin/audit${qs({ action: action.trim(), resourceId: resourceId.trim(), page, size: 50 })}`),
  });
  return (
    <div>
      <PageHeader title="Audit log" description="Security-relevant and administrative actions recorded by every service." />
      <div className="mb-4 flex flex-wrap gap-3">
        <div className="w-60"><label htmlFor="a-action" className="sr-only">Action prefix</label><Input id="a-action" placeholder="Action, e.g. vendor." value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }} /></div>
        <div className="w-72"><label htmlFor="a-res" className="sr-only">Resource ID</label><Input id="a-res" placeholder="Resource ID" value={resourceId} onChange={(e) => { setResourceId(e.target.value); setPage(0); }} /></div>
      </div>
      {q.isLoading ? <PageSpinner /> : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : q.data!.items.length === 0 ? <EmptyState title="No matching entries" /> : (
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>When</th><th>Action</th><th>Actor</th><th>Resource</th><th>Outcome</th><th>Service</th></tr></thead>
            <tbody>
              {q.data!.items.map((a) => (
                <tr key={a.event_id}>
                  <td className="whitespace-nowrap text-slate">{date(a.occurred_at, true)}</td>
                  <td className="font-medium">{a.action}</td>
                  <td>{a.actor_role ? humanize(a.actor_role) : 'System'}{a.actor_id && <div className="text-xs text-slate">{a.actor_id.slice(0, 8)}</div>}</td>
                  <td>{a.resource_type}<div className="text-xs text-slate">{a.resource_id}</div></td>
                  <td><StatusBadge status={a.outcome === 'SUCCESS' ? 'SUCCEEDED' : a.outcome === 'FAILURE' ? 'FAILED' : a.outcome} label={humanize(a.outcome)} /></td>
                  <td className="text-slate">{a.service}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {q.data && <Pagination page={page} totalPages={q.data.totalPages} onPage={setPage} />}
    </div>
  );
}
