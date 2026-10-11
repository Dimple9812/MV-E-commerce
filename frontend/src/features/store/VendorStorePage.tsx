import { useParams } from 'react-router-dom';
import { useState } from 'react';
import { EmptyState, ErrorState, PageSpinner, Pagination } from '@/components/ui';
import { date, initials } from '@/lib/format';
import { useProducts, useVendor } from './catalog';
import { ProductGrid } from './ProductCard';

export function VendorStorePage() {
  const { id } = useParams();
  const [page, setPage] = useState(0);
  const vendor = useVendor(id);
  const products = useProducts({ vendorId: id, page, size: 24, sort: 'newest' });

  if (vendor.isLoading) return <PageSpinner />;
  if (vendor.isError || !vendor.data) return <ErrorState title="This seller is not available" error={vendor.error} />;
  const v = vendor.data;
  return (
    <div className="flex flex-col gap-10">
      <header className="flex flex-col gap-5 border-b border-line pb-8 sm:flex-row sm:items-center">
        <span className="flex h-20 w-20 shrink-0 items-center justify-center rounded-tag bg-tag font-display text-3xl font-extrabold text-[#17233F]">
          {initials(v.businessName)}
        </span>
        <div>
          <h1 className="text-4xl font-extrabold">{v.businessName}</h1>
          {v.description && <p className="mt-2 max-w-2xl text-slate">{v.description}</p>}
          <p className="mt-2 text-sm text-slate">Ships from {v.country}. Selling on MarketFlow since {date(v.memberSince)}.</p>
        </div>
      </header>
      {products.isError ? <ErrorState error={products.error} onRetry={() => products.refetch()} />
        : products.data?.items.length === 0 ? <EmptyState title="Nothing on the shelves yet">This seller has not published products yet.</EmptyState>
          : <ProductGrid items={products.data?.items} loading={products.isLoading} />}
      {products.data && <Pagination page={page} totalPages={products.data.totalPages} onPage={setPage} />}
    </div>
  );
}
