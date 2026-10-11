import { Link } from 'react-router-dom';
import type { ProductCard as Card } from '@/lib/types';
import { PriceTag, ProductArt, Skeleton, Stars } from '@/components/ui';
import { money } from '@/lib/format';

export function ProductCard({ p }: { p: Card }) {
  const range = Number(p.maxPrice) > Number(p.minPrice);
  return (
    <Link to={`/products/${p.id}`} className="group flex flex-col gap-3 rounded-xl p-2 -m-2 hover:bg-surface focus-visible:bg-surface">
      <div className="relative aspect-[4/5] overflow-hidden rounded-lg border border-line">
        <ProductArt name={p.name} url={p.imageUrl} className="transition-transform duration-300 group-hover:scale-[1.03]" />
        {!p.inStock && (
          <span className="absolute left-2 top-2 rounded-full bg-ink px-2.5 py-0.5 text-xs font-semibold text-mist">Sold out</span>
        )}
      </div>
      <div className="flex flex-col gap-1.5 px-0.5">
        <div className="text-xs text-slate">{p.vendorName}</div>
        <div className="line-clamp-2 font-medium leading-snug">{p.name}</div>
        <div className="flex items-center justify-between gap-2">
          <PriceTag size="sm">{range ? `from ${money(p.minPrice, p.currency)}` : money(p.minPrice, p.currency)}</PriceTag>
          {p.ratingCount > 0 && <Stars value={p.ratingAvg} count={p.ratingCount} size={12} />}
        </div>
      </div>
    </Link>
  );
}

export function ProductGrid({ items, loading, columns = 4 }: { items?: Card[]; loading?: boolean; columns?: 3 | 4 }) {
  const cls = columns === 3 ? 'grid-cols-2 md:grid-cols-3' : 'grid-cols-2 md:grid-cols-3 lg:grid-cols-4';
  if (loading) {
    return (
      <div className={`grid gap-x-5 gap-y-8 ${cls}`}>
        {Array.from({ length: columns * 2 }).map((_, i) => (
          <div key={i} className="flex flex-col gap-3"><Skeleton className="aspect-[4/5]" /><Skeleton className="h-4 w-3/4" /><Skeleton className="h-6 w-20" /></div>
        ))}
      </div>
    );
  }
  return <div className={`grid gap-x-5 gap-y-8 ${cls}`}>{items?.map((p) => <ProductCard key={p.id} p={p} />)}</div>;
}
