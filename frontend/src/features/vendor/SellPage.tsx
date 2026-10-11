import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useAuth } from '@/auth/AuthProvider';
import { ErrorState, Field, InlineError, Input, PageSpinner, StatusBadge, Textarea } from '@/components/ui';
import { ApiError, http } from '@/lib/api';
import type { Vendor } from '@/lib/types';
import { date } from '@/lib/format';
import { vendorProfileSchema, type VendorProfileForm } from './vendorSchema';

export function VendorProfileFields({ form }: { form: ReturnType<typeof useForm<VendorProfileForm>> }) {
  const e = form.formState.errors;
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <div className="sm:col-span-2"><Field label="Shop name" htmlFor="v-name" error={e.businessName?.message}><Input id="v-name" {...form.register('businessName')} /></Field></div>
      <div className="sm:col-span-2"><Field label="What you sell" htmlFor="v-desc" hint="Shown on your storefront page" error={e.description?.message}><Textarea id="v-desc" {...form.register('description')} /></Field></div>
      <Field label="Contact e-mail" htmlFor="v-email" error={e.contactEmail?.message}><Input id="v-email" type="email" {...form.register('contactEmail')} /></Field>
      <Field label="Phone (optional)" htmlFor="v-phone" error={e.contactPhone?.message}><Input id="v-phone" {...form.register('contactPhone')} /></Field>
      <Field label="Country code" htmlFor="v-country" error={e.country?.message}><Input id="v-country" maxLength={2} {...form.register('country')} /></Field>
    </div>
  );
}

/**
 * /sell: explains selling, takes the application, then shows its review status. Approval grants the VENDOR role
 * asynchronously (vendor.events -> identity), so once approved we re-issue the access token to pick it up.
 */
export function SellPage() {
  const { user, hasRole, reload } = useAuth();
  const qc = useQueryClient();
  const mine = useQuery({
    queryKey: ['vendor', 'me'],
    queryFn: async () => {
      try {
        return await http.get<Vendor>('/api/v1/vendors/me');
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: !!user,
    refetchInterval: (q) => (q.state.data?.status === 'PENDING' ? 15_000 : false),
  });
  const form = useForm<VendorProfileForm>({
    resolver: zodResolver(vendorProfileSchema),
    defaultValues: { contactEmail: user?.email ?? '', country: 'US' },
  });
  const apply = useMutation({
    mutationFn: (v: VendorProfileForm) => http.post<Vendor>('/api/v1/vendors/applications', v),
    onSuccess: (v) => qc.setQueryData(['vendor', 'me'], v),
  });

  const approved = mine.data?.status === 'APPROVED';
  // a boolean, not hasRole itself: every token refresh creates a new user object and a new hasRole function
  const isVendor = hasRole('VENDOR');
  useEffect(() => {
    if (!approved || isVendor) return;
    void reload();
    const t = window.setInterval(() => void reload(), 3000);
    return () => window.clearInterval(t);
  }, [approved, isVendor, reload]);

  return (
    <div className="mx-auto grid max-w-5xl gap-10 md:grid-cols-[1fr_1.2fr]">
      <div>
        <h1 className="text-4xl font-extrabold">Sell on MarketFlow</h1>
        <p className="mt-3 text-lg text-slate">Open a shop next to other independent sellers. Customers check out once; you get your part of the order to pack and ship.</p>
        <dl className="mt-8 flex flex-col gap-5">
          <div><dt className="font-semibold">You set the prices</dt><dd className="text-slate">Variants, stock and limits per order are yours to manage.</dd></div>
          <div><dt className="font-semibold">Commission only on sales</dt><dd className="text-slate">The default rate is 10% of the item subtotal after discounts. No listing fees.</dd></div>
          <div><dt className="font-semibold">Stock that cannot oversell</dt><dd className="text-slate">Items are reserved while a customer pays and released if payment fails.</dd></div>
        </dl>
      </div>
      <div>
        {!user ? (
          <div className="panel p-6">
            <h2 className="text-xl font-bold">Start with an account</h2>
            <p className="mt-2 text-slate">Applications are linked to your MarketFlow account.</p>
            <div className="mt-5 flex gap-2">
              <Link to="/register?next=/sell" className="btn-primary">Create account</Link>
              <Link to="/login?next=/sell" className="btn-quiet">Sign in</Link>
            </div>
          </div>
        ) : mine.isLoading ? <PageSpinner /> : mine.isError ? <ErrorState error={mine.error} onRetry={() => mine.refetch()} /> : mine.data ? (
          <ApplicationStatus vendor={mine.data} roleReady={isVendor} />
        ) : (
          <form className="panel flex flex-col gap-5 p-6" onSubmit={form.handleSubmit((v) => apply.mutate(v))} noValidate>
            <h2 className="text-xl font-bold">Apply to sell</h2>
            <VendorProfileFields form={form} />
            <InlineError error={apply.error} />
            <button className="btn-primary self-start" disabled={apply.isPending}>Submit application</button>
          </form>
        )}
      </div>
    </div>
  );
}

function ApplicationStatus({ vendor, roleReady }: { vendor: Vendor; roleReady: boolean }) {
  return (
    <div className="panel flex flex-col gap-3 p-6">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-xl font-bold">{vendor.businessName}</h2>
        <StatusBadge status={vendor.status} />
      </div>
      <p className="text-sm text-slate">Submitted {date(vendor.submittedAt)}</p>
      {vendor.status === 'PENDING' && <p>An administrator is reviewing your application. This page updates on its own.</p>}
      {vendor.status === 'APPROVED' && (roleReady
        ? <><p>Your shop is open.</p><Link to="/vendor" className="btn-primary self-start">Go to the seller hub</Link></>
        : <p>Approved. Your seller access is being activated; this takes a few seconds.</p>)}
      {vendor.status === 'REJECTED' && <p>The application was not approved{vendor.statusReason ? `: ${vendor.statusReason}` : '.'}</p>}
      {vendor.status === 'SUSPENDED' && <p>Your shop is suspended{vendor.statusReason ? `: ${vendor.statusReason}` : '.'} Contact support to resolve it.</p>}
    </div>
  );
}
