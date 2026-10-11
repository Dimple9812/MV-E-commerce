import { Link } from 'react-router-dom';
import { useMemo } from 'react';
import { ErrorState, Skeleton } from '@/components/ui';
import { useCategories, useProducts } from './catalog';
import { ProductGrid } from './ProductCard';
import { initials } from '@/lib/format';

export function HomePage() {
  const newest = useProducts({ sort: 'newest', size: 8 });
  const rated = useProducts({ sort: 'rating', size: 4, inStock: true });
  const all = useProducts({ size: 60 });
  const categories = useCategories();

  // the storefront has no "list vendors" endpoint: stalls are derived from what is actually on sale
  const stalls = useMemo(() => {
    const m = new Map<string, { id: string; name: string; count: number }>();
    all.data?.items.forEach((p) => {
      const s = m.get(p.vendorId) ?? { id: p.vendorId, name: p.vendorName, count: 0 };
      s.count++;
      m.set(p.vendorId, s);
    });
    return [...m.values()].sort((a, b) => b.count - a.count);
  }, [all.data]);

  const topLevel = categories.data?.filter((c) => !c.parentId) ?? [];

  return (
    <div className="flex flex-col gap-14">
      <section className="grid gap-8 lg:grid-cols-[1.1fr_1fr] lg:items-end">
        <div>
          <h1 className="text-5xl font-extrabold leading-[0.95] sm:text-6xl lg:text-7xl">
            Small shops.<br />One checkout.
          </h1>
          <p className="mt-5 max-w-md text-lg text-slate">
            Buy from independent sellers across the market and pay once. Each seller packs and ships their own part of your order.
          </p>
          <div className="mt-7 flex flex-wrap gap-3">
            <Link to="/search" className="btn-primary">Browse the market</Link>
            <Link to="/sell" className="btn-quiet">Open a stall</Link>
          </div>
        </div>
        <div>
          <h2 className="mb-3 text-lg font-bold">Stalls open today</h2>
          {all.isLoading ? (
            <div className="flex flex-col gap-2">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-16" />)}</div>
          ) : stalls.length === 0 ? (
            <p className="panel px-4 py-5 text-slate">No sellers have opened yet. Run the demo seed script to stock the market.</p>
          ) : (
            <ul className="flex flex-col divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
              {stalls.slice(0, 5).map((s) => (
                <li key={s.id}>
                  <Link to={`/vendors/${s.id}`} className="flex items-center gap-4 px-4 py-3 hover:bg-mist">
                    <span className="flex h-10 w-10 items-center justify-center rounded-tag bg-tag font-display font-bold text-[#17233F]">
                      {initials(s.name)}
                    </span>
                    <span className="flex-1 font-medium">{s.name}</span>
                    <span className="text-sm text-slate tabular">{s.count} item{s.count === 1 ? '' : 's'}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>

      {topLevel.length > 0 && (
        <section aria-labelledby="cats">
          <h2 id="cats" className="sr-only">Categories</h2>
          <div className="flex flex-wrap gap-2">
            {topLevel.map((c) => (
              <Link key={c.id} to={`/search?category=${c.slug}`}
                className="rounded-full border border-line bg-surface px-4 py-2 text-sm font-medium hover:border-ink">
                {c.name}
              </Link>
            ))}
          </div>
        </section>
      )}

      <section>
        <div className="mb-5 flex items-end justify-between">
          <h2 className="text-2xl font-bold">Just arrived</h2>
          <Link to="/search?sort=newest" className="text-sm font-semibold text-slate hover:text-ink">See everything new</Link>
        </div>
        {newest.isError ? <ErrorState error={newest.error} onRetry={() => newest.refetch()} />
          : <ProductGrid items={newest.data?.items} loading={newest.isLoading} />}
      </section>

      {(rated.data?.items.some((p) => p.ratingCount > 0)) && (
        <section>
          <h2 className="mb-5 text-2xl font-bold">Loved by buyers</h2>
          <ProductGrid items={rated.data?.items.filter((p) => p.ratingCount > 0)} />
        </section>
      )}
    </div>
  );
}
