import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRef, useState } from 'react';
import { Trash2, Upload } from 'lucide-react';
import { useToast } from '@/components/Toast';
import { Dialog, EmptyState, ErrorState, Field, InlineError, Input, PageHeader, PageSpinner, Pagination, ProductArt, Select, StatusBadge, Textarea } from '@/components/ui';
import { useCategories } from '@/features/store/catalog';
import { http, qs } from '@/lib/api';
import type { Category, Page, Product, ProductSummary, Variant } from '@/lib/types';
import { date, priceRange } from '@/lib/format';
import { formatAttributes, parseAttributes } from '@/lib/attributes';

const STATUSES = ['', 'DRAFT', 'PUBLISHED', 'ARCHIVED', 'BLOCKED'] as const;

export function VendorProducts() {
  const [status, setStatus] = useState<string>('');
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: ['vendor', 'products', status, page],
    queryFn: () => http.get<Page<ProductSummary>>(`/api/v1/vendor/products${qs({ status, page, size: 20 })}`),
  });
  return (
    <div>
      <PageHeader title="Products" actions={<Link to="/vendor/products/new" className="btn-primary">Add a product</Link>} />
      <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Filter by status">
        {STATUSES.map((s) => (
          <button key={s} className={status === s ? 'btn-primary py-1.5' : 'btn-quiet py-1.5'} onClick={() => { setStatus(s); setPage(0); }}>
            {s ? s.charAt(0) + s.slice(1).toLowerCase() : 'All'}
          </button>
        ))}
      </div>
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> :
        list.data!.items.length === 0 ? (
          <EmptyState title={status ? 'Nothing with this status' : 'No products yet'}
            action={!status && <Link to="/vendor/products/new" className="btn-primary">Add your first product</Link>}>
            {!status && 'Create a product with at least one variant, add stock, then publish it.'}
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>Product</th><th>Status</th><th>Price</th><th>Variants</th><th>Stock</th><th>Updated</th></tr></thead>
              <tbody>
                {list.data!.items.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <Link to={`/vendor/products/${p.id}`} className="flex items-center gap-3 font-medium hover:underline">
                        <span className="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-line"><ProductArt name={p.name} url={p.imageUrl} /></span>
                        {p.name}
                      </Link>
                    </td>
                    <td><StatusBadge status={p.status} /></td>
                    <td className="tabular">{priceRange(p.minPrice, p.maxPrice)}</td>
                    <td className="tabular">{p.variantCount}</td>
                    <td>{p.inStock ? 'In stock' : <span className="text-brick">Out of stock</span>}</td>
                    <td className="text-slate">{date(p.updatedAt)}</td>
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

// ---------- editor ----------

const money2 = z.coerce.number({ invalid_type_error: 'Enter a price' }).min(0.01, 'At least 0.01').max(999999.99)
  .refine((v) => Math.round(v * 100) === v * 100, 'At most two decimals');
const optionalMoney = z.union([z.literal(''), money2]).optional();

const variantSchema = z.object({
  sku: z.string().trim().min(2, 'At least 2 characters').max(64).regex(/^[A-Za-z0-9\-_.]+$/, 'Letters, digits and - _ . only'),
  name: z.string().trim().min(1, 'Name the variant').max(120),
  attributes: z.string().max(400).optional(),
  price: money2,
  compareAtPrice: optionalMoney,
});

const productSchema = z.object({
  categoryId: z.string().uuid('Choose a category'),
  name: z.string().trim().min(3, 'At least 3 characters').max(200),
  description: z.string().max(5000).optional(),
  brand: z.string().trim().max(100).optional(),
  maxPerOrder: z.coerce.number().int().min(1).max(100),
});
const newProductSchema = productSchema.extend({ variants: z.array(variantSchema).min(1, 'Add at least one variant').max(50) });
type NewProductForm = z.infer<typeof newProductSchema>;
type ProductForm = z.infer<typeof productSchema>;

function CategoryOptions({ categories }: { categories?: Category[] }) {
  const roots = (categories ?? []).filter((c) => !c.parentId);
  return (
    <>
      <option value="">Choose a category</option>
      {roots.map((r) => (
        <optgroup key={r.id} label={r.name}>
          <option value={r.id}>{r.name} (general)</option>
          {(categories ?? []).filter((c) => c.parentId === r.id).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </optgroup>
      ))}
    </>
  );
}

function variantPayload(v: z.infer<typeof variantSchema>) {
  return {
    sku: v.sku, name: v.name, attributes: parseAttributes(v.attributes), price: v.price,
    compareAtPrice: v.compareAtPrice === '' || v.compareAtPrice === undefined ? null : v.compareAtPrice,
  };
}

export function NewProductPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const categories = useCategories();
  const form = useForm<NewProductForm>({
    resolver: zodResolver(newProductSchema),
    defaultValues: { maxPerOrder: 10, variants: [{ sku: '', name: 'Standard', price: '' as unknown as number }] },
  });
  const variants = useFieldArray({ control: form.control, name: 'variants' });
  const create = useMutation({
    mutationFn: (v: NewProductForm) => http.post<Product>('/api/v1/vendor/products', {
      categoryId: v.categoryId, name: v.name, description: v.description, brand: v.brand || null, maxPerOrder: v.maxPerOrder,
      attributes: {}, variants: v.variants.map(variantPayload),
    }),
    onSuccess: (p) => { toast.ok('Draft created. Add stock and images, then publish.'); navigate(`/vendor/products/${p.id}`, { replace: true }); },
  });
  const e = form.formState.errors;
  return (
    <div className="max-w-3xl">
      <PageHeader title="New product" description="Products start as drafts. Customers see them after you publish." />
      <form className="flex flex-col gap-6" onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
        <section className="panel grid gap-4 p-5 sm:grid-cols-2">
          <div className="sm:col-span-2"><Field label="Name" htmlFor="p-name" error={e.name?.message}><Input id="p-name" {...form.register('name')} /></Field></div>
          <Field label="Category" htmlFor="p-cat" error={e.categoryId?.message}><Select id="p-cat" {...form.register('categoryId')}><CategoryOptions categories={categories.data} /></Select></Field>
          <Field label="Brand (optional)" htmlFor="p-brand" error={e.brand?.message}><Input id="p-brand" {...form.register('brand')} /></Field>
          <div className="sm:col-span-2"><Field label="Description" htmlFor="p-desc" error={e.description?.message}><Textarea id="p-desc" rows={5} {...form.register('description')} /></Field></div>
          <Field label="Limit per order" htmlFor="p-max" error={e.maxPerOrder?.message}><Input id="p-max" type="number" min={1} max={100} {...form.register('maxPerOrder')} /></Field>
        </section>
        <section className="panel flex flex-col gap-4 p-5">
          <div className="flex items-center justify-between"><h2 className="text-xl font-bold">Variants</h2>
            <button type="button" className="btn-quiet py-1.5" onClick={() => variants.append({ sku: '', name: '', price: '' as unknown as number })}>Add variant</button></div>
          {e.variants?.message && <p className="text-sm text-brick">{e.variants.message}</p>}
          {variants.fields.map((f, i) => {
            const ve = e.variants?.[i];
            return (
              <div key={f.id} className="grid gap-3 border-t border-line pt-4 first-of-type:border-0 first-of-type:pt-0 sm:grid-cols-6">
                <div className="sm:col-span-2"><Field label="SKU" htmlFor={`v${i}-sku`} error={ve?.sku?.message}><Input id={`v${i}-sku`} {...form.register(`variants.${i}.sku`)} /></Field></div>
                <div className="sm:col-span-2"><Field label="Variant name" htmlFor={`v${i}-name`} error={ve?.name?.message}><Input id={`v${i}-name`} {...form.register(`variants.${i}.name`)} /></Field></div>
                <Field label="Price" htmlFor={`v${i}-price`} error={ve?.price?.message}><Input id={`v${i}-price`} inputMode="decimal" {...form.register(`variants.${i}.price`)} /></Field>
                <Field label="Was (optional)" htmlFor={`v${i}-cmp`} error={ve?.compareAtPrice?.message}><Input id={`v${i}-cmp`} inputMode="decimal" {...form.register(`variants.${i}.compareAtPrice`)} /></Field>
                <div className="sm:col-span-5"><Field label="Attributes (optional)" htmlFor={`v${i}-attr`} hint="For example Color: Black, Size: M"><Input id={`v${i}-attr`} {...form.register(`variants.${i}.attributes`)} /></Field></div>
                {variants.fields.length > 1 && <button type="button" className="self-end text-sm text-slate hover:text-brick" onClick={() => variants.remove(i)}>Remove</button>}
              </div>
            );
          })}
        </section>
        <InlineError error={create.error} />
        <div className="flex gap-2">
          <button className="btn-primary" disabled={create.isPending}>Create draft</button>
          <Link to="/vendor/products" className="btn-quiet">Cancel</Link>
        </div>
      </form>
    </div>
  );
}

export function EditProductPage() {
  const { productId } = useParams();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const key = ['vendor', 'product', productId];
  const product = useQuery({ queryKey: key, queryFn: () => http.get<Product>(`/api/v1/vendor/products/${productId}`) });
  const categories = useCategories();
  const setProduct = (p: Product) => { qc.setQueryData(key, p); void qc.invalidateQueries({ queryKey: ['vendor', 'products'] }); };

  const form = useForm<ProductForm>({
    resolver: zodResolver(productSchema),
    values: product.data && {
      categoryId: product.data.categoryId, name: product.data.name, description: product.data.description ?? '',
      brand: product.data.brand ?? '', maxPerOrder: product.data.maxPerOrder,
    },
  });
  const save = useMutation({
    mutationFn: (v: ProductForm) => http.patch<Product>(`/api/v1/vendor/products/${productId}`, { ...v, brand: v.brand || null, attributes: product.data?.attributes ?? {} }),
    onSuccess: (p) => { setProduct(p); toast.ok('Saved'); },
  });
  const lifecycle = useMutation({
    mutationFn: (action: 'publish' | 'unpublish' | 'archive') => http.post<Product>(`/api/v1/vendor/products/${productId}/${action}`),
    onSuccess: (p, action) => { setProduct(p); toast.ok({ publish: 'Published', unpublish: 'Moved back to draft', archive: 'Archived' }[action]); },
    onError: (e) => toast.error(e),
  });
  const [confirmDelete, setConfirmDelete] = useState(false);
  const del = useMutation({
    mutationFn: () => http.del(`/api/v1/vendor/products/${productId}`),
    onSuccess: () => { toast.ok('Product removed'); void qc.invalidateQueries({ queryKey: ['vendor', 'products'] }); navigate('/vendor/products'); },
  });

  if (product.isLoading) return <PageSpinner />;
  if (product.isError) return <ErrorState error={product.error} onRetry={() => product.refetch()} />;
  const p = product.data!;
  const e = form.formState.errors;
  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <PageHeader title={p.name}
        description={<span className="inline-flex items-center gap-2"><StatusBadge status={p.status} />{p.status === 'PUBLISHED' && <Link to={`/products/${p.id}`} className="text-sm underline">See it in the store</Link>}</span>}
        actions={<>
          {(p.status === 'DRAFT' || p.status === 'ARCHIVED') && <button className="btn-accent" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate('publish')}>Publish</button>}
          {p.status === 'PUBLISHED' && <button className="btn-quiet" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate('unpublish')}>Unpublish</button>}
          {p.status !== 'ARCHIVED' && p.status !== 'BLOCKED' && <button className="btn-quiet" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate('archive')}>Archive</button>}
          {p.status !== 'BLOCKED' && <button className="btn-danger" onClick={() => setConfirmDelete(true)}>Delete</button>}
        </>} />
      {p.status === 'BLOCKED' && <p role="alert" className="rounded-lg bg-brick/10 px-4 py-3 text-sm text-brick">Blocked by moderation{p.moderationNote ? `: ${p.moderationNote}` : ''}. Contact support to have it reviewed.</p>}

      <form className="panel grid gap-4 p-5 sm:grid-cols-2" onSubmit={form.handleSubmit((v) => save.mutate(v))} noValidate>
        <div className="sm:col-span-2"><Field label="Name" htmlFor="e-name" error={e.name?.message}><Input id="e-name" {...form.register('name')} /></Field></div>
        <Field label="Category" htmlFor="e-cat" error={e.categoryId?.message}><Select id="e-cat" {...form.register('categoryId')}><CategoryOptions categories={categories.data} /></Select></Field>
        <Field label="Brand (optional)" htmlFor="e-brand"><Input id="e-brand" {...form.register('brand')} /></Field>
        <div className="sm:col-span-2"><Field label="Description" htmlFor="e-desc" error={e.description?.message}><Textarea id="e-desc" rows={5} {...form.register('description')} /></Field></div>
        <Field label="Limit per order" htmlFor="e-max" error={e.maxPerOrder?.message}><Input id="e-max" type="number" {...form.register('maxPerOrder')} /></Field>
        <div className="flex items-end gap-3 sm:col-span-2"><button className="btn-primary" disabled={save.isPending || !form.formState.isDirty}>Save details</button><InlineError error={save.error} /></div>
      </form>

      <VariantsPanel product={p} onChange={setProduct} />
      <ImagesPanel product={p} onChange={setProduct} />

      <Dialog open={confirmDelete} onClose={() => setConfirmDelete(false)} title="Delete this product?">
        <p className="text-slate">Drafts that were never published are deleted. Anything customers may have seen is archived instead, so past orders keep their details.</p>
        <InlineError error={del.error} />
        <div className="mt-5 flex justify-end gap-2">
          <button className="btn-quiet" onClick={() => setConfirmDelete(false)}>Keep it</button>
          <button className="btn-danger" disabled={del.isPending} onClick={() => del.mutate()}>Delete product</button>
        </div>
      </Dialog>
    </div>
  );
}

function VariantsPanel({ product, onChange }: { product: Product; onChange: (p: Product) => void }) {
  const toast = useToast();
  const [editing, setEditing] = useState<Variant | 'new' | null>(null);
  return (
    <section className="panel">
      <div className="flex items-center justify-between border-b border-line px-5 py-3">
        <h2 className="text-xl font-bold">Variants</h2>
        <div className="flex gap-2">
          <Link to="/vendor/inventory" className="btn-quiet py-1.5">Manage stock</Link>
          <button className="btn-quiet py-1.5" onClick={() => setEditing('new')}>Add variant</button>
        </div>
      </div>
      <div className="relative overflow-x-auto">
        <table className="data-table">
          <thead><tr><th>SKU</th><th>Name</th><th>Price</th><th>Available</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>
            {product.variants.map((v) => (
              <tr key={v.id}>
                <td className="font-medium">{v.sku}</td>
                <td>{v.name}<div className="text-xs text-slate">{formatAttributes(v.attributes)}</div></td>
                <td className="tabular">{priceRange(v.price, v.price)}{v.compareAtPrice ? <span className="ml-1 text-xs text-slate line-through">{priceRange(v.compareAtPrice, v.compareAtPrice)}</span> : null}</td>
                <td className="tabular">{v.availableQty}</td>
                <td>{v.active ? 'Active' : <span className="text-slate">Hidden</span>}</td>
                <td className="text-right"><button className="text-sm font-semibold" onClick={() => setEditing(v)}>Edit</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing && <VariantDialog productId={product.id} variant={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={(p) => { onChange(p); setEditing(null); toast.ok('Variant saved'); }} />}
    </section>
  );
}

const variantEditSchema = variantSchema.extend({ active: z.boolean() });
type VariantEditForm = z.infer<typeof variantEditSchema>;

function VariantDialog({ productId, variant, onClose, onSaved }: {
  productId: string; variant: Variant | null; onClose: () => void; onSaved: (p: Product) => void;
}) {
  const form = useForm<VariantEditForm>({
    resolver: zodResolver(variantEditSchema),
    defaultValues: variant
      ? { sku: variant.sku, name: variant.name, attributes: formatAttributes(variant.attributes), price: variant.price, compareAtPrice: variant.compareAtPrice ?? '', active: variant.active }
      : { sku: '', name: '', attributes: '', price: '' as unknown as number, compareAtPrice: '', active: true },
  });
  const save = useMutation({
    mutationFn: (v: VariantEditForm) => {
      const body = variantPayload(v);
      return variant
        ? http.patch<Product>(`/api/v1/vendor/products/${productId}/variants/${variant.id}`, { name: body.name, attributes: body.attributes, price: body.price, compareAtPrice: body.compareAtPrice, active: v.active })
        : http.post<Product>(`/api/v1/vendor/products/${productId}/variants`, body);
    },
    onSuccess: onSaved,
  });
  const e = form.formState.errors;
  return (
    <Dialog open onClose={onClose} title={variant ? `Edit ${variant.sku}` : 'Add a variant'}>
      <form className="grid gap-4 sm:grid-cols-2" onSubmit={form.handleSubmit((v) => save.mutate(v))} noValidate>
        <Field label="SKU" htmlFor="d-sku" hint={variant ? 'SKUs cannot change once orders may reference them' : undefined} error={e.sku?.message}><Input id="d-sku" disabled={!!variant} {...form.register('sku')} /></Field>
        <Field label="Variant name" htmlFor="d-name" error={e.name?.message}><Input id="d-name" {...form.register('name')} /></Field>
        <Field label="Price" htmlFor="d-price" error={e.price?.message}><Input id="d-price" inputMode="decimal" {...form.register('price')} /></Field>
        <Field label="Was (optional)" htmlFor="d-cmp" error={e.compareAtPrice?.message}><Input id="d-cmp" inputMode="decimal" {...form.register('compareAtPrice')} /></Field>
        <div className="sm:col-span-2"><Field label="Attributes (optional)" htmlFor="d-attr" hint="For example Color: Black, Size: M"><Input id="d-attr" {...form.register('attributes')} /></Field></div>
        {variant && <label className="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" {...form.register('active')} className="accent-[rgb(var(--ink))]" />Customers can buy this variant</label>}
        <div className="sm:col-span-2"><InlineError error={save.error} /></div>
        <div className="flex justify-end gap-2 sm:col-span-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={save.isPending}>Save variant</button>
        </div>
      </form>
    </Dialog>
  );
}

function ImagesPanel({ product, onChange }: { product: Product; onChange: (p: Product) => void }) {
  const toast = useToast();
  const fileRef = useRef<HTMLInputElement>(null);
  const [url, setUrl] = useState('');
  const upload = useMutation({
    mutationFn: (file: File) => {
      const fd = new FormData();
      fd.append('file', file);
      return http.post<Product>(`/api/v1/vendor/products/${product.id}/images/upload${qs({ altText: product.name })}`, fd);
    },
    onSuccess: (p) => { onChange(p); toast.ok('Image uploaded'); },
    onError: (e) => toast.error(e),
  });
  const addUrl = useMutation({
    mutationFn: () => http.post<Product>(`/api/v1/vendor/products/${product.id}/images`, { url: url.trim(), altText: product.name }),
    onSuccess: (p) => { onChange(p); setUrl(''); toast.ok('Image added'); },
  });
  const remove = useMutation({
    mutationFn: (imageId: string) => http.del<Product>(`/api/v1/vendor/products/${product.id}/images/${imageId}`),
    onSuccess: (p) => onChange(p),
    onError: (e) => toast.error(e),
  });
  return (
    <section className="panel p-5">
      <h2 className="text-xl font-bold">Images</h2>
      <p className="mt-1 text-sm text-slate">JPEG, PNG or WebP up to 5 MB. The first image is the one shown in search results.</p>
      <ul className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        {product.images.map((img) => (
          <li key={img.id} className="group relative aspect-square overflow-hidden rounded-lg border border-line">
            <img src={img.url} alt={img.altText ?? ''} className="h-full w-full object-cover" />
            <button className="absolute right-2 top-2 rounded-md bg-surface/90 p-1.5 text-brick" aria-label="Remove image" onClick={() => remove.mutate(img.id)}><Trash2 className="h-4 w-4" /></button>
          </li>
        ))}
      </ul>
      <div className="mt-4 flex flex-wrap items-end gap-3">
        <input ref={fileRef} type="file" accept="image/jpeg,image/png,image/webp" className="sr-only" id="img-file"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = ''; }} />
        <button className="btn-quiet" disabled={upload.isPending} onClick={() => fileRef.current?.click()}><Upload className="h-4 w-4" aria-hidden />{upload.isPending ? 'Uploading…' : 'Upload image'}</button>
        <form className="flex min-w-[16rem] flex-1 gap-2" onSubmit={(e) => { e.preventDefault(); if (url.trim()) addUrl.mutate(); }}>
          <label htmlFor="img-url" className="sr-only">Image URL</label>
          <Input id="img-url" placeholder="Or paste an https:// image URL" value={url} onChange={(e) => setUrl(e.target.value)} />
          <button className="btn-quiet" disabled={addUrl.isPending || !url.trim()}>Add</button>
        </form>
      </div>
      <InlineError error={addUrl.error} />
    </section>
  );
}
