import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { Check, Minus, Plus, ShieldCheck } from 'lucide-react';
import { useAuth } from '@/auth/AuthProvider';
import { useCart } from '@/cart/CartProvider';
import { useToast } from '@/components/Toast';
import { ErrorState, Field, InlineError, Input, PageSpinner, PriceTag, ProductArt, Stars, Textarea } from '@/components/ui';
import { http } from '@/lib/api';
import { date, money } from '@/lib/format';
import { useProduct, useRating, useReviews } from './catalog';

export function ProductPage() {
  const { id } = useParams();
  const product = useProduct(id);
  const cart = useCart();
  const toast = useToast();
  const [variantId, setVariantId] = useState<string>();
  const [qty, setQty] = useState(1);
  const [image, setImage] = useState(0);

  const p = product.data;
  const variant = useMemo(() => p?.variants.find((v) => v.id === (variantId ?? p.variants.find((x) => x.active)?.id)), [p, variantId]);

  if (product.isLoading) return <PageSpinner />;
  if (product.isError || !p) return <ErrorState title="This product is not available" error={product.error} onRetry={() => product.refetch()} />;

  const active = p.variants.filter((v) => v.active);
  const soldOut = !variant || variant.availableQty <= 0;
  const maxQty = Math.min(p.maxPerOrder, Math.max(variant?.availableQty ?? 0, 0));

  const addToCart = () => {
    if (!variant) return;
    cart.add({
      variantId: variant.id, productId: p.id, productName: p.name, variantName: variant.name,
      vendorId: p.vendor?.id ?? '', vendorName: p.vendor?.name ?? '', unitPrice: Number(variant.price),
      imageUrl: p.images[0]?.url, maxPerOrder: p.maxPerOrder,
    }, qty);
    toast.ok(`Added ${qty} × ${p.name} to your cart`);
  };

  return (
    <div className="flex flex-col gap-16">
      <div className="grid gap-10 lg:grid-cols-[1.1fr_1fr]">
        <div className="flex flex-col gap-3">
          <div className="aspect-square overflow-hidden rounded-xl border border-line">
            <ProductArt name={p.name} url={p.images[image]?.url} />
          </div>
          {p.images.length > 1 && (
            <div className="flex gap-2">
              {p.images.map((img, i) => (
                <button key={img.id} onClick={() => setImage(i)} aria-label={`Show image ${i + 1}`}
                  className={`h-16 w-16 overflow-hidden rounded-lg border-2 ${i === image ? 'border-ink' : 'border-line'}`}>
                  <img src={img.url} alt="" className="h-full w-full object-cover" />
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="flex flex-col gap-6">
          <div>
            {p.vendor && <Link to={`/vendors/${p.vendor.id}`} className="text-sm font-semibold text-slate hover:text-ink">{p.vendor.name}</Link>}
            <h1 className="mt-1 text-4xl font-extrabold leading-tight">{p.name}</h1>
            {p.ratingCount > 0 && <div className="mt-2"><Stars value={p.ratingAvg} count={p.ratingCount} /></div>}
          </div>

          <div className="flex items-center gap-3">
            <PriceTag size="lg" amount={variant?.price ?? p.minPrice} currency={p.currency} />
            {variant?.compareAtPrice && <span className="text-slate line-through tabular">{money(variant.compareAtPrice, p.currency)}</span>}
          </div>

          {active.length > 1 && (
            <fieldset>
              <legend className="mb-2 text-sm font-medium">Choose an option</legend>
              <div className="flex flex-wrap gap-2">
                {active.map((v) => (
                  <button key={v.id} type="button" onClick={() => { setVariantId(v.id); setQty(1); }}
                    aria-pressed={variant?.id === v.id}
                    className={`rounded-lg border px-4 py-2 text-sm font-medium ${variant?.id === v.id ? 'border-ink bg-ink text-mist' : 'border-line bg-surface hover:border-ink/50'} ${v.availableQty <= 0 ? 'opacity-50' : ''}`}>
                    {v.name}
                  </button>
                ))}
              </div>
            </fieldset>
          )}

          <div className="text-sm">
            {soldOut ? <span className="font-semibold text-brick">Sold out</span>
              : variant!.availableQty <= 5 ? <span className="font-semibold text-tag-deep">Only {variant!.availableQty} left</span>
                : <span className="text-leaf">In stock</span>}
            <span className="ml-2 text-slate">Limit {p.maxPerOrder} per order</span>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center rounded-lg border border-line bg-surface">
              <button className="px-3 py-2.5" onClick={() => setQty(Math.max(1, qty - 1))} aria-label="Decrease quantity" disabled={qty <= 1}><Minus className="h-4 w-4" /></button>
              <span className="w-10 text-center font-semibold tabular" aria-live="polite">{qty}</span>
              <button className="px-3 py-2.5" onClick={() => setQty(Math.min(maxQty, qty + 1))} aria-label="Increase quantity" disabled={qty >= maxQty}><Plus className="h-4 w-4" /></button>
            </div>
            <button className="btn-accent flex-1 py-3 text-base" disabled={soldOut} onClick={addToCart}>Add to cart</button>
          </div>

          <p className="flex items-start gap-2 text-sm text-slate">
            <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            Your card is only charged once {p.vendor?.name ?? 'the seller'} has reserved your items.
          </p>

          <div className="border-t border-line pt-6">
            <h2 className="mb-2 text-lg font-bold">About this item</h2>
            <p className="whitespace-pre-line leading-relaxed text-ink/85">{p.description || 'The seller has not added a description yet.'}</p>
            {p.brand && <p className="mt-3 text-sm text-slate">Brand: {p.brand}</p>}
            {variant && <p className="mt-1 text-sm text-slate">SKU {variant.sku}</p>}
          </div>
        </div>
      </div>
      <ReviewsSection productId={p.id} />
    </div>
  );
}

const reviewSchema = z.object({
  rating: z.coerce.number().int().min(1, 'Pick a rating').max(5),
  title: z.string().trim().min(3, 'At least 3 characters').max(120),
  body: z.string().trim().min(10, 'Tell other buyers a bit more (10+ characters)').max(4000),
});
type ReviewForm = z.infer<typeof reviewSchema>;

function ReviewsSection({ productId }: { productId: string }) {
  const { user } = useAuth();
  const reviews = useReviews(productId);
  const rating = useRating(productId);
  const qc = useQueryClient();
  const toast = useToast();
  const eligibility = useQuery({
    queryKey: ['review-eligibility', productId],
    queryFn: () => http.get<{ eligible: boolean; reason?: string }>(`/api/v1/products/${productId}/reviews/eligibility`),
    enabled: !!user,
  });
  const form = useForm<ReviewForm>({ resolver: zodResolver(reviewSchema), defaultValues: { rating: 5, title: '', body: '' } });
  const submit = useMutation({
    mutationFn: (v: ReviewForm) => http.post(`/api/v1/products/${productId}/reviews`, v),
    onSuccess: () => {
      toast.ok('Review published');
      form.reset();
      void qc.invalidateQueries({ queryKey: ['reviews', productId] });
      void qc.invalidateQueries({ queryKey: ['rating', productId] });
      void qc.invalidateQueries({ queryKey: ['review-eligibility', productId] });
    },
  });

  const total = rating.data?.reviewCount ?? 0;
  return (
    <section aria-labelledby="reviews-title" className="grid gap-10 border-t border-line pt-10 lg:grid-cols-[18rem_1fr]">
      <div>
        <h2 id="reviews-title" className="text-2xl font-bold">Reviews</h2>
        {total > 0 ? (
          <div className="mt-3">
            <div className="font-display text-5xl font-extrabold tabular">{Number(rating.data!.averageRating).toFixed(1)}</div>
            <Stars value={rating.data!.averageRating} count={total} />
            <ul className="mt-4 flex flex-col gap-1.5 text-sm">
              {['5', '4', '3', '2', '1'].map((s) => {
                const n = rating.data!.histogram[s] ?? 0;
                return (
                  <li key={s} className="flex items-center gap-2">
                    <span className="w-3 tabular">{s}</span>
                    <span className="h-2 flex-1 overflow-hidden rounded-full bg-line"><span className="block h-full bg-tag-deep" style={{ width: `${(n / total) * 100}%` }} /></span>
                    <span className="w-6 text-right text-slate tabular">{n}</span>
                  </li>
                );
              })}
            </ul>
          </div>
        ) : <p className="mt-2 text-slate">No reviews yet.</p>}
      </div>
      <div className="flex flex-col gap-8">
        {user && eligibility.data?.eligible && (
          <form onSubmit={form.handleSubmit((v) => submit.mutate(v))} className="panel flex flex-col gap-4 p-5" noValidate>
            <h3 className="font-display text-lg font-bold">Review your purchase</h3>
            <Field label="Rating" error={form.formState.errors.rating?.message}>
              <div className="flex gap-2" role="radiogroup" aria-label="Rating">
                {[1, 2, 3, 4, 5].map((n) => (
                  <label key={n} className={`cursor-pointer rounded-lg border px-3 py-1.5 text-sm font-semibold ${Number(form.watch('rating')) === n ? 'border-ink bg-ink text-mist' : 'border-line'}`}>
                    <input type="radio" value={n} className="sr-only" {...form.register('rating')} />{n}★
                  </label>
                ))}
              </div>
            </Field>
            <Field label="Headline" error={form.formState.errors.title?.message} htmlFor="r-title">
              <Input id="r-title" {...form.register('title')} />
            </Field>
            <Field label="Your review" error={form.formState.errors.body?.message} htmlFor="r-body">
              <Textarea id="r-body" {...form.register('body')} />
            </Field>
            <InlineError error={submit.error} />
            <button className="btn-primary self-start" disabled={submit.isPending}>Publish review</button>
          </form>
        )}
        {user && eligibility.data && !eligibility.data.eligible && eligibility.data.reason === 'NOT_PURCHASED' && (
          <p className="text-sm text-slate">Only buyers of this item can review it.</p>
        )}
        {reviews.isError && <ErrorState error={reviews.error} />}
        <ul className="flex flex-col divide-y divide-line">
          {reviews.data?.map((r) => (
            <li key={r.id} className="py-5 first:pt-0">
              <div className="flex flex-wrap items-center gap-3">
                <Stars value={r.rating} />
                <span className="font-semibold">{r.title}</span>
              </div>
              <p className="mt-2 max-w-prose leading-relaxed text-ink/85">{r.body}</p>
              <p className="mt-2 flex items-center gap-1.5 text-xs text-slate">
                {r.authorName}, {date(r.createdAt)}
                {r.verifiedPurchase && <span className="inline-flex items-center gap-0.5 text-leaf"><Check className="h-3 w-3" aria-hidden />Verified purchase</span>}
              </p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
