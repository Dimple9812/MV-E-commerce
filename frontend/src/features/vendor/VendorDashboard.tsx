import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { EmptyState, ErrorState, PageHeader, PageSpinner, Select, Stat, StatusBadge } from '@/components/ui';
import { BarList, TrendChart } from '@/components/charts';
import { http } from '@/lib/api';
import type { VendorOverview } from '@/lib/types';
import { useVendorDashboard } from './hooks';
import { money, relative } from '@/lib/format';

export function FreshnessNote({ at }: { at?: string | null }) {
  return (
    <p className="text-xs text-slate" title="Analytics are built from events and can lag behind the live order data by a few seconds">
      Updated from order events{at ? `, last one ${relative(at)}` : ''}. Figures can trail live orders by a few seconds.
    </p>
  );
}

export function VendorDashboard() {
  const [days, setDays] = useState(30);
  const dash = useVendorDashboard();
  const overview = useQuery({
    queryKey: ['vendor', 'analytics', days],
    queryFn: () => http.get<VendorOverview>(`/api/v1/vendor/analytics/overview?days=${days}`),
    refetchInterval: 30_000,
  });
  if (dash.isLoading) return <PageSpinner />;
  if (dash.isError) return <ErrorState error={dash.error} onRetry={() => dash.refetch()} />;
  const { vendor, earnings } = dash.data!;
  const o = overview.data;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title={vendor.businessName}
        description={<span className="inline-flex items-center gap-2"><StatusBadge status={vendor.status} /> Commission {(vendor.commissionRate * 100).toFixed(1)}%</span>}
        actions={<><Link to="/vendor/products/new" className="btn-primary">Add a product</Link><Link to={`/vendors/${vendor.id}`} className="btn-quiet">View storefront</Link></>} />

      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-label="Earnings">
        <Stat label="Gross sales" value={money(earnings.grossSales)} sub={`${earnings.salesCount} sales, ${earnings.refundCount} refunds`} />
        <Stat label="Commission" value={money(earnings.commission)} />
        <Stat label="Net earnings" value={money(earnings.netEarnings)} />
        <Stat label="Balance to pay out" value={money(earnings.balance)} sub={`${money(earnings.paidOut)} paid so far`} />
      </section>

      <section className="panel p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-xl font-bold">Net sales per day</h2>
          <label className="flex items-center gap-2 text-sm text-slate">Period
            <Select value={days} onChange={(e) => setDays(Number(e.target.value))} className="w-auto py-1.5">
              <option value={7}>7 days</option><option value={30}>30 days</option><option value={90}>90 days</option>
            </Select>
          </label>
        </div>
        {overview.isError ? <ErrorState error={overview.error} onRetry={() => overview.refetch()} /> : !o ? <PageSpinner /> : (
          <>
            <div className="mb-4 grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
              <div><div className="text-slate">Orders</div><div className="text-lg font-bold tabular">{o.totals.orders}</div></div>
              <div><div className="text-slate">Revenue</div><div className="text-lg font-bold tabular">{money(o.totals.revenue)}</div></div>
              <div><div className="text-slate">Net</div><div className="text-lg font-bold tabular">{money(o.totals.net)}</div></div>
              <div><div className="text-slate">Cancelled</div><div className="text-lg font-bold tabular">{o.totals.cancelled}</div></div>
            </div>
            <TrendChart data={o.salesOverTime} valueKey="net" label="Net sales" asMoney />
            <div className="mt-3"><FreshnessNote at={o.lastEventAt} /></div>
          </>
        )}
      </section>

      {o && (
        <div className="grid gap-6 lg:grid-cols-2">
          <section className="panel p-5">
            <h2 className="mb-3 text-xl font-bold">Best sellers</h2>
            {o.bestSellers.length === 0 ? <p className="text-slate">No confirmed sales in this period yet.</p>
              : <BarList data={o.bestSellers.slice(0, 6)} nameKey="product_name" valueKey="units" label="Units sold" />}
          </section>
          <section className="panel p-5">
            <div className="mb-3 flex items-center justify-between"><h2 className="text-xl font-bold">Running low</h2><Link to="/vendor/inventory?low=1" className="text-sm font-semibold">Manage stock</Link></div>
            {o.inventoryAlerts.length === 0 ? <EmptyState title="Stock levels look fine">Variants at or below their threshold show up here.</EmptyState> : (
              <ul className="divide-y divide-line text-sm">
                {o.inventoryAlerts.map((a) => (
                  <li key={a.sku} className="flex justify-between gap-3 py-2">
                    <span><span className="font-medium">{a.product_name}</span> <span className="text-slate">{a.sku}</span></span>
                    <span className="font-semibold text-brick tabular">{a.available} left</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
