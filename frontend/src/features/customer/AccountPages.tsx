import { NavLink, Outlet } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import clsx from 'clsx';
import { useAuth } from '@/auth/AuthProvider';
import { useToast } from '@/components/Toast';
import { EmptyState, ErrorState, Field, InlineError, Input, PageHeader, PageSpinner, Pagination } from '@/components/ui';
import { http } from '@/lib/api';
import type { Address, InboxItem, Page } from '@/lib/types';
import { relative } from '@/lib/format';

export function AccountLayout() {
  const link = ({ isActive }: { isActive: boolean }) =>
    clsx('rounded-lg px-3 py-2 text-sm font-medium', isActive ? 'bg-ink text-mist' : 'hover:bg-surface');
  return (
    <div className="grid gap-8 md:grid-cols-[12rem_1fr]">
      <nav className="flex gap-1 overflow-x-auto md:flex-col" aria-label="Account">
        <NavLink to="/account" end className={link}>Profile</NavLink>
        <NavLink to="/account/orders" className={link}>Orders</NavLink>
        <NavLink to="/account/addresses" className={link}>Addresses</NavLink>
        <NavLink to="/account/notifications" className={link}>Notifications</NavLink>
      </nav>
      <div className="min-w-0"><Outlet /></div>
    </div>
  );
}

export function ProfilePage() {
  const { user, reload } = useAuth();
  const toast = useToast();
  const profile = useForm<{ fullName: string }>({
    resolver: zodResolver(z.object({ fullName: z.string().trim().min(2).max(120) })),
    values: { fullName: user?.fullName ?? '' },
  });
  const saveProfile = useMutation({
    mutationFn: (v: { fullName: string }) => http.patch('/api/v1/users/me', v),
    onSuccess: async () => { await reload(); toast.ok('Profile saved'); },
  });
  const pw = useForm<{ currentPassword: string; newPassword: string }>({
    resolver: zodResolver(z.object({
      currentPassword: z.string().min(1, 'Enter your current password'),
      newPassword: z.string().min(10, 'At least 10 characters').regex(/[A-Za-z]/, 'Include a letter').regex(/\d/, 'Include a digit'),
    })),
  });
  const changePw = useMutation({
    mutationFn: (v: { currentPassword: string; newPassword: string }) => http.post('/api/v1/users/me/password', v),
    onSuccess: () => { pw.reset(); toast.ok('Password changed. Other devices were signed out.'); },
  });
  return (
    <div className="flex max-w-xl flex-col gap-8">
      <PageHeader title="Profile" description={user?.email} />
      <form className="panel flex flex-col gap-4 p-5" onSubmit={profile.handleSubmit((v) => saveProfile.mutate(v))}>
        <Field label="Full name" htmlFor="fullName" error={profile.formState.errors.fullName?.message}>
          <Input id="fullName" {...profile.register('fullName')} />
        </Field>
        <InlineError error={saveProfile.error} />
        <button className="btn-primary self-start" disabled={saveProfile.isPending}>Save profile</button>
      </form>
      <form className="panel flex flex-col gap-4 p-5" onSubmit={pw.handleSubmit((v) => changePw.mutate(v))}>
        <h2 className="font-display text-lg font-bold">Change password</h2>
        <Field label="Current password" htmlFor="cpw" error={pw.formState.errors.currentPassword?.message}>
          <Input id="cpw" type="password" autoComplete="current-password" {...pw.register('currentPassword')} />
        </Field>
        <Field label="New password" htmlFor="npw" error={pw.formState.errors.newPassword?.message}>
          <Input id="npw" type="password" autoComplete="new-password" {...pw.register('newPassword')} />
        </Field>
        <InlineError error={changePw.error} />
        <button className="btn-quiet self-start" disabled={changePw.isPending}>Change password</button>
      </form>
    </div>
  );
}

const addressSchema = z.object({
  label: z.string().trim().min(1, 'Name this address').max(40),
  recipient: z.string().trim().min(2).max(120),
  line1: z.string().trim().min(3).max(200),
  line2: z.string().trim().max(200).optional(),
  city: z.string().trim().min(2).max(100),
  state: z.string().trim().max(100).optional(),
  postalCode: z.string().trim().min(2).max(20),
  country: z.string().trim().regex(/^[A-Za-z]{2}$/, 'Two-letter country code'),
  phone: z.string().trim().max(30).optional(),
  makeDefault: z.boolean().default(false),
});
type AddressForm = z.infer<typeof addressSchema>;

export function AddressesPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const list = useQuery({ queryKey: ['addresses'], queryFn: () => http.get<Address[]>('/api/v1/users/me/addresses') });
  const form = useForm<AddressForm>({ resolver: zodResolver(addressSchema), defaultValues: { country: 'US', makeDefault: false } });
  const add = useMutation({
    mutationFn: (v: AddressForm) => http.post('/api/v1/users/me/addresses', v),
    onSuccess: () => { form.reset(); setAdding(false); toast.ok('Address added'); void qc.invalidateQueries({ queryKey: ['addresses'] }); },
  });
  const remove = useMutation({
    mutationFn: (id: string) => http.del(`/api/v1/users/me/addresses/${id}`),
    onSuccess: () => { toast.ok('Address removed'); void qc.invalidateQueries({ queryKey: ['addresses'] }); },
  });
  if (list.isLoading) return <PageSpinner />;
  if (list.isError) return <ErrorState error={list.error} onRetry={() => list.refetch()} />;
  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <PageHeader title="Addresses" actions={!adding && <button className="btn-primary" onClick={() => setAdding(true)}>Add address</button>} />
      {list.data!.length === 0 && !adding && <EmptyState title="No saved addresses">Add one now or during checkout.</EmptyState>}
      <ul className="flex flex-col gap-3">
        {list.data!.map((a) => (
          <li key={a.id} className="panel flex items-start justify-between gap-4 p-4">
            <div className="text-sm">
              <div className="font-semibold">{a.label} {a.isDefault && <span className="ml-1 text-xs font-normal text-slate">(default)</span>}</div>
              <div className="text-slate">{a.recipient}, {a.line1}{a.line2 ? `, ${a.line2}` : ''}, {a.city} {a.postalCode}, {a.country}</div>
            </div>
            <button className="text-sm text-slate hover:text-brick" onClick={() => remove.mutate(a.id)}>Remove</button>
          </li>
        ))}
      </ul>
      {adding && (
        <form className="panel grid gap-4 p-5 sm:grid-cols-2" onSubmit={form.handleSubmit((v) => add.mutate(v))} noValidate>
          {(['label', 'recipient', 'line1', 'line2', 'city', 'state', 'postalCode', 'country', 'phone'] as const).map((k) => (
            <div key={k} className={k === 'line1' || k === 'line2' ? 'sm:col-span-2' : ''}>
              <Field label={{ label: 'Label', recipient: 'Recipient', line1: 'Street address', line2: 'Apartment, suite (optional)', city: 'City', state: 'State / region (optional)', postalCode: 'Postal code', country: 'Country code', phone: 'Phone (optional)' }[k]}
                htmlFor={`a-${k}`} error={form.formState.errors[k]?.message}>
                <Input id={`a-${k}`} {...form.register(k)} />
              </Field>
            </div>
          ))}
          <label className="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" {...form.register('makeDefault')} className="accent-[rgb(var(--ink))]" />Make this my default address</label>
          <div className="flex gap-2 sm:col-span-2">
            <InlineError error={add.error} />
            <button className="btn-primary" disabled={add.isPending}>Save address</button>
            <button type="button" className="btn-quiet" onClick={() => setAdding(false)}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}

export function NotificationsPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const inbox = useQuery({
    queryKey: ['notifications', page],
    queryFn: () => http.get<Page<InboxItem>>(`/api/v1/notifications?page=${page}&size=20`),
  });
  const readAll = useMutation({
    mutationFn: () => http.post('/api/v1/notifications/read-all'),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['notifications'] }),
  });
  if (inbox.isLoading) return <PageSpinner />;
  if (inbox.isError) return <ErrorState error={inbox.error} onRetry={() => inbox.refetch()} />;
  return (
    <div className="max-w-2xl">
      <PageHeader title="Notifications" actions={inbox.data!.items.some((i) => !i.readAt) && <button className="btn-quiet" onClick={() => readAll.mutate()}>Mark all as read</button>} />
      {inbox.data!.items.length === 0 ? <EmptyState title="You're all caught up">Order updates and messages from sellers will appear here.</EmptyState> : (
        <ul className="panel divide-y divide-line">
          {inbox.data!.items.map((n) => (
            <li key={n.id} className={clsx('px-5 py-4', !n.readAt && 'bg-tag/10')}>
              <div className="flex items-baseline justify-between gap-3">
                <span className="font-semibold">{n.subject}</span>
                <span className="shrink-0 text-xs text-slate">{relative(n.createdAt)}</span>
              </div>
              <p className="mt-1 text-sm text-slate">{n.body}</p>
              {n.link && <a href={n.link} className="mt-1 inline-block text-sm font-semibold">Open</a>}
            </li>
          ))}
        </ul>
      )}
      <Pagination page={page} totalPages={inbox.data!.totalPages} onPage={setPage} />
    </div>
  );
}
