import { useSearchParams } from 'react-router-dom';
import { EmptyState, ErrorState, Pagination, Select } from '@/components/ui';
import { useCategories, useProducts } from './catalog';
import { ProductGrid } from './ProductCard';

const SORTS = [
  ['relevance', 'Best match'], ['newest', 'Newest'], ['price_asc', 'Price: low to high'],
  ['price_desc', 'Price: high to low'], ['rating', 'Highest rated'],
] as const;

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const get = (k: string) => params.get(k) ?? undefined;
  const page = Number(params.get('page') ?? 0);
  const q = get('q');
  const query = {
    q, category: get('category'), minPrice: get('minPrice'), maxPrice: get('maxPrice'), minRating: get('minRating'),
    inStock: params.get('inStock') === 'true' ? true : undefined,
    sort: get('sort') ?? (q ? 'relevance' : 'newest'), page, size: 24,
  };
  const products = useProducts(query);
  const categories = useCategories();

  const set = (k: string, v?: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    next.delete('page');
    setParams(next);
  };

  const category = categories.data?.find((c) => c.slug === query.category);

  return (
    <div className="grid gap-8 lg:grid-cols-[15rem_1fr]">
      <aside aria-label="Filters" className="flex flex-col gap-6 lg:sticky lg:top-24 lg:self-start">
        <div>
          <h2 className="mb-2 font-display text-lg font-bold">Category</h2>
          <ul className="flex flex-col gap-0.5 text-sm">
            <li><button onClick={() => set('category')} className={!query.category ? 'font-semibold' : 'text-slate hover:text-ink'}>All categories</button></li>
            {categories.data?.filter((c) => !c.parentId).map((c) => (
              <li key={c.id}>
                <button onClick={() => set('category', c.slug)} className={query.category === c.slug ? 'font-semibold' : 'text-slate hover:text-ink'}>{c.name}</button>
                <ul className="ml-3 mt-0.5 flex flex-col gap-0.5">
                  {categories.data.filter((s) => s.parentId === c.id).map((s) => (
                    <li key={s.id}><button onClick={() => set('category', s.slug)} className={query.category === s.slug ? 'font-semibold' : 'text-slate hover:text-ink'}>{s.name}</button></li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </div>
        <div>
          <h2 className="mb-2 font-display text-lg font-bold">Price</h2>
          <div className="flex items-center gap-2">
            <input aria-label="Minimum price" className="input" inputMode="decimal" placeholder="Min" defaultValue={query.minPrice}
              onBlur={(e) => set('minPrice', e.target.value.replace(/[^0-9.]/g, ''))} />
            <span className="text-slate">to</span>
            <input aria-label="Maximum price" className="input" inputMode="decimal" placeholder="Max" defaultValue={query.maxPrice}
              onBlur={(e) => set('maxPrice', e.target.value.replace(/[^0-9.]/g, ''))} />
          </div>
        </div>
        <div>
          <h2 className="mb-2 font-display text-lg font-bold">Rating</h2>
          <Select aria-label="Minimum rating" value={query.minRating ?? ''} onChange={(e) => set('minRating', e.target.value)}>
            <option value="">Any rating</option>
            <option value="4">4 stars and up</option>
            <option value="3">3 stars and up</option>
          </Select>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={query.inStock === true} onChange={(e) => set('inStock', e.target.checked ? 'true' : undefined)}
            className="h-4 w-4 accent-[rgb(var(--ink))]" />
          In stock only
        </label>
      </aside>

      <section>
        <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-3xl font-extrabold">{q ? `Results for “${q}”` : category?.name ?? 'The whole market'}</h1>
            {products.data && <p className="mt-1 text-sm text-slate tabular">{products.data.totalItems} product{products.data.totalItems === 1 ? '' : 's'}</p>}
          </div>
          <Select aria-label="Sort by" className="w-auto" value={query.sort} onChange={(e) => set('sort', e.target.value)}>
            {SORTS.filter(([v]) => v !== 'relevance' || q).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>
        </div>
        {products.isError ? <ErrorState error={products.error} onRetry={() => products.refetch()} />
          : products.data?.items.length === 0 ? (
            <EmptyState title="Nothing matches yet">Try a broader search term, or clear a filter or two.</EmptyState>
          ) : <ProductGrid items={products.data?.items} loading={products.isLoading} columns={3} />}
        {products.data && (
          <Pagination page={page} totalPages={products.data.totalPages}
            onPage={(p) => { const n = new URLSearchParams(params); n.set('page', String(p)); setParams(n); window.scrollTo(0, 0); }} />
        )}
      </section>
    </div>
  );
}
