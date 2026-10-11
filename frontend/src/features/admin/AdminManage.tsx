import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useToast } from '@/components/Toast';
import { Dialog, EmptyState, ErrorState, Field, InlineError, Input, PageHeader, PageSpinner, Pagination, ProductArt, Select, Stars, StatusBadge, Textarea } from '@/components/ui';
import { useCategories } from '@/features/store/catalog';
import { http, qs } from '@/lib/api';
import type { AdminReview, Category, Page, ProductSummary, User, Vendor } from '@/lib/types';
import { date, priceRange } from '@/lib/format';

function FilterChips({ value, options, onChange, label }: { value: string; options: [string, string][]; onChange: (v: string) => void; label: string }) {
  return (
    <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label={label}>
      {options.map(([v, text]) => (
        <button key={v} className={value === v ? 'btn-primary py-1.5' : 'btn-quiet py-1.5'} onClick={() => onChange(v)}>{text}</button>
      ))}
    </div>
  );
}

/** Dialog that collects a mandatory reason (reject, suspend, block). */
function ReasonDialog({ title, action, onClose, onSubmit, pending, error, intro }: {
  title: string; action: string; onClose: () => void; onSubmit: (reason: string) => void; pending: boolean; error: unknown; intro?: string;
}) {
  const [reason, setReason] = useState('');
  return (
    <Dialog open onClose={onClose} title={title}>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); if (reason.trim().length >= 3) onSubmit(reason.trim()); }}>
        {intro && <p className="text-slate">{intro}</p>}
        <Field label="Reason" htmlFor="reason" hint="Shared with the affected user and kept in the audit log">
          <Textarea id="reason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} autoFocus />
        </Field>
        <InlineError error={error} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-danger" disabled={pending || reason.trim().length < 3}>{action}</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- vendors ----------

export function AdminVendorsPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [status, setStatus] = useState('PENDING');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<{ kind: 'reject' | 'suspend' | 'commission' | 'approve'; vendor: Vendor } | null>(null);
  const list = useQuery({
    queryKey: ['admin', 'vendors', status, page],
    queryFn: () => http.get<Page<Vendor>>(`/api/v1/admin/vendors${qs({ status, page, size: 20 })}`),
  });
  const act = useMutation({
    mutationFn: ({ id, path, body }: { id: string; path: string; body?: unknown }) =>
      path === 'commission' ? http.put<Vendor>(`/api/v1/admin/vendors/${id}/commission`, body) : http.post<Vendor>(`/api/v1/admin/vendors/${id}/${path}`, body),
    onSuccess: (v) => { toast.ok(`${v.businessName}: ${v.status.toLowerCase()}`); setDialog(null); void qc.invalidateQueries({ queryKey: ['admin', 'vendors'] }); },
    onError: (e) => toast.error(e),
  });
  return (
    <div>
      <PageHeader title="Sellers" description="Approving a seller grants their account seller access; suspending hides their products from the store." />
      <FilterChips label="Filter by status" value={status} onChange={(s) => { setStatus(s); setPage(0); }}
        options={[['PENDING', 'Awaiting review'], ['APPROVED', 'Approved'], ['SUSPENDED', 'Suspended'], ['REJECTED', 'Rejected'], ['', 'All']]} />
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> :
        list.data!.items.length === 0 ? <EmptyState title={status === 'PENDING' ? 'No applications waiting' : 'No sellers here'} /> : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>Seller</th><th>Contact</th><th>Country</th><th>Commission</th><th>Status</th><th>Submitted</th><th><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>
                {list.data!.items.map((v) => (
                  <tr key={v.id}>
                    <td><div className="font-medium">{v.businessName}</div>{v.description && <div className="max-w-xs truncate text-xs text-slate">{v.description}</div>}</td>
                    <td className="text-slate">{v.contactEmail}</td>
                    <td>{v.country}</td>
                    <td className="tabular">{(v.commissionRate * 100).toFixed(1)}%</td>
                    <td><StatusBadge status={v.status} />{v.statusReason && <div className="mt-1 max-w-[12rem] text-xs text-slate">{v.statusReason}</div>}</td>
                    <td className="text-slate">{date(v.submittedAt)}</td>
                    <td className="whitespace-nowrap text-right">
                      <div className="flex justify-end gap-2">
                        {v.status === 'PENDING' && <>
                          <button className="btn-primary py-1.5" onClick={() => setDialog({ kind: 'approve', vendor: v })}>Approve</button>
                          <button className="btn-quiet py-1.5" onClick={() => setDialog({ kind: 'reject', vendor: v })}>Reject</button>
                        </>}
                        {v.status === 'APPROVED' && <>
                          <button className="btn-quiet py-1.5" onClick={() => setDialog({ kind: 'commission', vendor: v })}>Commission</button>
                          <button className="btn-danger py-1.5" onClick={() => setDialog({ kind: 'suspend', vendor: v })}>Suspend</button>
                        </>}
                        {v.status === 'SUSPENDED' && <button className="btn-quiet py-1.5" disabled={act.isPending} onClick={() => act.mutate({ id: v.id, path: 'reinstate' })}>Reinstate</button>}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onPage={setPage} />}

      {dialog?.kind === 'reject' && <ReasonDialog title={`Reject ${dialog.vendor.businessName}?`} action="Reject application" pending={act.isPending} error={act.error}
        onClose={() => setDialog(null)} onSubmit={(reason) => act.mutate({ id: dialog.vendor.id, path: 'reject', body: { reason } })} />}
      {dialog?.kind === 'suspend' && <ReasonDialog title={`Suspend ${dialog.vendor.businessName}?`} action="Suspend seller" pending={act.isPending} error={act.error}
        intro="Their products disappear from the store and they cannot publish until reinstated. Orders already placed stay valid."
        onClose={() => setDialog(null)} onSubmit={(reason) => act.mutate({ id: dialog.vendor.id, path: 'suspend', body: { reason } })} />}
      {(dialog?.kind === 'approve' || dialog?.kind === 'commission') && (
        <CommissionDialog vendor={dialog.vendor} approving={dialog.kind === 'approve'} pending={act.isPending} error={act.error} onClose={() => setDialog(null)}
          onSubmit={(rate) => act.mutate({ id: dialog.vendor.id, path: dialog.kind === 'approve' ? 'approve' : 'commission', body: { commissionRate: rate } })} />
      )}
    </div>
  );
}

function CommissionDialog({ vendor, approving, onClose, onSubmit, pending, error }: {
  vendor: Vendor; approving: boolean; onClose: () => void; onSubmit: (rate: number) => void; pending: boolean; error: unknown;
}) {
  const [pct, setPct] = useState(String(Math.round(vendor.commissionRate * 1000) / 10));
  const n = Number(pct);
  const valid = pct !== '' && n >= 0 && n <= 50 && Math.round(n * 100) === n * 100;
  return (
    <Dialog open onClose={onClose} title={approving ? `Approve ${vendor.businessName}` : `Commission for ${vendor.businessName}`}>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); if (valid) onSubmit(Number((n / 100).toFixed(4))); }}>
        <Field label="Commission rate (%)" htmlFor="rate" hint="Between 0 and 50. Applies to orders confirmed after the change." error={!valid && pct !== '' ? 'Enter 0 to 50 with up to two decimals' : undefined}>
          <Input id="rate" inputMode="decimal" value={pct} onChange={(e) => setPct(e.target.value)} />
        </Field>
        <InlineError error={error} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!valid || pending}>{approving ? 'Approve seller' : 'Save rate'}</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- users ----------

export function AdminUsersPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: ['admin', 'users', q, page],
    queryFn: () => http.get<Page<User>>(`/api/v1/admin/users${qs({ q: q.trim(), page, size: 20 })}`),
  });
  const toggle = useMutation({
    mutationFn: (u: User) => http.post<User>(`/api/v1/admin/users/${u.id}/${u.status === 'ACTIVE' ? 'deactivate' : 'activate'}`),
    onSuccess: (u) => { toast.ok(`${u.email} is ${u.status.toLowerCase()}`); void qc.invalidateQueries({ queryKey: ['admin', 'users'] }); },
    onError: (e) => toast.error(e),
  });
  return (
    <div>
      <PageHeader title="Users" description="Deactivating an account signs it out everywhere and blocks new sign-ins." />
      <div className="mb-4 max-w-sm"><label htmlFor="u-q" className="sr-only">Search users</label>
        <Input id="u-q" placeholder="Search by e-mail or name" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} /></div>
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : list.data!.items.length === 0 ? <EmptyState title="No users match" /> : (
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>Name</th><th>E-mail</th><th>Roles</th><th>Status</th><th>Joined</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {list.data!.items.map((u) => (
                <tr key={u.id}>
                  <td className="font-medium">{u.fullName}</td>
                  <td className="text-slate">{u.email}</td>
                  <td>{u.roles.map((r) => r.charAt(0) + r.slice(1).toLowerCase()).join(', ')}</td>
                  <td><StatusBadge status={u.status} /></td>
                  <td className="text-slate">{date(u.createdAt)}</td>
                  <td className="text-right">
                    {!u.roles.includes('ADMIN') && (
                      <button className={u.status === 'ACTIVE' ? 'btn-danger py-1.5' : 'btn-quiet py-1.5'} disabled={toggle.isPending} onClick={() => toggle.mutate(u)}>
                        {u.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                      </button>
                    )}
                  </td>
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

// ---------- product moderation ----------

export function AdminProductsPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [status, setStatus] = useState('PUBLISHED');
  const [page, setPage] = useState(0);
  const [blocking, setBlocking] = useState<ProductSummary | null>(null);
  const list = useQuery({
    queryKey: ['admin', 'products', status, page],
    queryFn: () => http.get<Page<ProductSummary>>(`/api/v1/admin/products${qs({ status, page, size: 20 })}`),
  });
  const act = useMutation({
    mutationFn: ({ id, block, note }: { id: string; block: boolean; note?: string }) =>
      http.post(`/api/v1/admin/products/${id}/${block ? 'block' : 'unblock'}`, block ? { note } : undefined),
    onSuccess: (_r, v) => { toast.ok(v.block ? 'Product blocked' : 'Product unblocked'); setBlocking(null); void qc.invalidateQueries({ queryKey: ['admin', 'products'] }); },
    onError: (e) => toast.error(e),
  });
  return (
    <div>
      <PageHeader title="Product moderation" description="Blocking removes a product from the store immediately. The seller sees your note." />
      <FilterChips label="Filter by status" value={status} onChange={(s) => { setStatus(s); setPage(0); }}
        options={[['PUBLISHED', 'Published'], ['BLOCKED', 'Blocked'], ['DRAFT', 'Drafts'], ['ARCHIVED', 'Archived'], ['', 'All']]} />
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : list.data!.items.length === 0 ? <EmptyState title="No products here" /> : (
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>Product</th><th>Status</th><th>Price</th><th>Updated</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {list.data!.items.map((p) => (
                <tr key={p.id}>
                  <td><div className="flex items-center gap-3">
                    <span className="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-line"><ProductArt name={p.name} url={p.imageUrl} /></span>
                    {p.status === 'PUBLISHED' ? <Link to={`/products/${p.id}`} className="font-medium hover:underline">{p.name}</Link> : <span className="font-medium">{p.name}</span>}
                  </div></td>
                  <td><StatusBadge status={p.status} /></td>
                  <td className="tabular">{priceRange(p.minPrice, p.maxPrice)}</td>
                  <td className="text-slate">{date(p.updatedAt)}</td>
                  <td className="text-right">
                    {p.status === 'BLOCKED'
                      ? <button className="btn-quiet py-1.5" disabled={act.isPending} onClick={() => act.mutate({ id: p.id, block: false })}>Unblock</button>
                      : <button className="btn-danger py-1.5" onClick={() => setBlocking(p)}>Block</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onPage={setPage} />}
      {blocking && <ReasonDialog title={`Block ${blocking.name}?`} action="Block product" pending={act.isPending} error={act.error}
        onClose={() => setBlocking(null)} onSubmit={(note) => act.mutate({ id: blocking.id, block: true, note })} />}
    </div>
  );
}

// ---------- categories ----------

const categorySchema = z.object({
  parentId: z.string().optional(),
  name: z.string().trim().min(2, 'At least 2 characters').max(80),
  slug: z.string().trim().max(100).regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'Lowercase words joined by dashes'),
  description: z.string().max(500).optional(),
  sortOrder: z.coerce.number().int().min(0).max(1000),
});
type CategoryForm = z.infer<typeof categorySchema>;
const slugify = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

export function AdminCategoriesPage() {
  const categories = useCategories();
  const qc = useQueryClient();
  const toast = useToast();
  const [editing, setEditing] = useState<Category | 'new' | null>(null);
  const del = useMutation({
    mutationFn: (id: string) => http.del(`/api/v1/admin/categories/${id}`),
    onSuccess: () => { toast.ok('Category deleted'); void qc.invalidateQueries({ queryKey: ['categories'] }); },
    onError: (e) => toast.error(e),
  });
  if (categories.isLoading) return <PageSpinner />;
  if (categories.isError) return <ErrorState error={categories.error} onRetry={() => categories.refetch()} />;
  const all = categories.data!;
  const roots = all.filter((c) => !c.parentId).sort((a, b) => a.sortOrder - b.sortOrder);
  const row = (c: Category, child: boolean) => (
    <li key={c.id} className={`flex items-center justify-between gap-3 py-2.5 ${child ? 'pl-6' : ''}`}>
      <span><span className={child ? '' : 'font-semibold'}>{c.name}</span> <span className="text-xs text-slate">/{c.slug}</span></span>
      <span className="flex gap-3 text-sm">
        <button className="font-semibold" onClick={() => setEditing(c)}>Edit</button>
        <button className="text-slate hover:text-brick" onClick={() => del.mutate(c.id)}>Delete</button>
      </span>
    </li>
  );
  return (
    <div className="max-w-2xl">
      <PageHeader title="Categories" description="Categories with products or sub-categories cannot be deleted." actions={<button className="btn-primary" onClick={() => setEditing('new')}>Add category</button>} />
      <ul className="panel divide-y divide-line px-5">
        {roots.map((r) => [row(r, false), ...all.filter((c) => c.parentId === r.id).sort((a, b) => a.sortOrder - b.sortOrder).map((c) => row(c, true))])}
      </ul>
      {editing && <CategoryDialog category={editing === 'new' ? null : editing} roots={roots} onClose={() => setEditing(null)} />}
    </div>
  );
}

function CategoryDialog({ category, roots, onClose }: { category: Category | null; roots: Category[]; onClose: () => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const form = useForm<CategoryForm>({
    resolver: zodResolver(categorySchema),
    defaultValues: category
      ? { parentId: category.parentId ?? '', name: category.name, slug: category.slug, description: category.description ?? '', sortOrder: category.sortOrder }
      : { parentId: '', name: '', slug: '', description: '', sortOrder: 0 },
  });
  const save = useMutation({
    mutationFn: (v: CategoryForm) => {
      const body = { ...v, parentId: v.parentId || null };
      return category ? http.put(`/api/v1/admin/categories/${category.id}`, body) : http.post('/api/v1/admin/categories', body);
    },
    onSuccess: () => { toast.ok('Category saved'); void qc.invalidateQueries({ queryKey: ['categories'] }); onClose(); },
  });
  const e = form.formState.errors;
  return (
    <Dialog open onClose={onClose} title={category ? `Edit ${category.name}` : 'New category'}>
      <form className="flex flex-col gap-4" onSubmit={form.handleSubmit((v) => save.mutate(v))} noValidate>
        <Field label="Name" htmlFor="c-name" error={e.name?.message}>
          <Input id="c-name" {...form.register('name', { onChange: (ev) => { if (!category && !form.getFieldState('slug').isDirty) form.setValue('slug', slugify(ev.target.value)); } })} />
        </Field>
        <Field label="Slug" htmlFor="c-slug" hint="Used in links" error={e.slug?.message}><Input id="c-slug" {...form.register('slug')} /></Field>
        <Field label="Parent" htmlFor="c-parent">
          <Select id="c-parent" {...form.register('parentId')}>
            <option value="">None (top level)</option>
            {roots.filter((r) => r.id !== category?.id).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </Select>
        </Field>
        <Field label="Description (optional)" htmlFor="c-desc"><Textarea id="c-desc" {...form.register('description')} /></Field>
        <Field label="Sort order" htmlFor="c-sort" error={e.sortOrder?.message}><Input id="c-sort" type="number" {...form.register('sortOrder')} /></Field>
        <InlineError error={save.error} />
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={save.isPending}>Save category</button>
        </div>
      </form>
    </Dialog>
  );
}

// ---------- reviews ----------

export function AdminReviewsPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [status, setStatus] = useState('PUBLISHED');
  const list = useQuery({
    queryKey: ['admin', 'reviews', status],
    queryFn: () => http.get<AdminReview[]>(`/api/v1/admin/reviews?status=${status}&limit=100`),
  });
  const act = useMutation({
    mutationFn: ({ id, hide }: { id: string; hide: boolean }) => http.post(`/api/v1/admin/reviews/${id}/${hide ? 'hide' : 'restore'}`, hide ? { note: 'Hidden by moderator' } : undefined),
    onSuccess: (_r, v) => { toast.ok(v.hide ? 'Review hidden. The product rating is recalculated.' : 'Review restored'); void qc.invalidateQueries({ queryKey: ['admin', 'reviews'] }); },
    onError: (e) => toast.error(e),
  });
  return (
    <div className="max-w-4xl">
      <PageHeader title="Reviews" description="Hidden reviews no longer count toward a product's rating." />
      <FilterChips label="Filter by status" value={status} onChange={setStatus} options={[['PUBLISHED', 'Published'], ['HIDDEN', 'Hidden']]} />
      {list.isLoading ? <PageSpinner /> : list.isError ? <ErrorState error={list.error} onRetry={() => list.refetch()} /> : list.data!.length === 0 ? <EmptyState title="No reviews here" /> : (
        <ul className="flex flex-col gap-3">
          {list.data!.map((r) => (
            <li key={r.id} className="panel flex flex-col gap-2 p-5 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-3"><Stars value={r.rating} /><span className="font-semibold">{r.title}</span>{r.verifiedPurchase && <span className="text-xs text-leaf">Verified purchase</span>}</div>
                <p className="mt-1 text-sm text-slate">{r.body}</p>
                <p className="mt-2 text-xs text-slate">{r.authorName}, {date(r.createdAt)} <Link to={`/products/${r.productId}`} className="ml-2 underline">Product</Link></p>
              </div>
              <button className={r.status === 'PUBLISHED' ? 'btn-danger shrink-0 py-1.5' : 'btn-quiet shrink-0 py-1.5'} disabled={act.isPending}
                onClick={() => act.mutate({ id: r.id, hide: r.status === 'PUBLISHED' })}>{r.status === 'PUBLISHED' ? 'Hide' : 'Restore'}</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
