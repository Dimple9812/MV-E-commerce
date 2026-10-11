import clsx from 'clsx';
import { AlertTriangle, Loader2, Star } from 'lucide-react';
import { forwardRef, useEffect, useRef, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { ApiError } from '@/lib/api';
import { humanize, money } from '@/lib/format';

export function Spinner({ className }: { className?: string }) {
  return <Loader2 aria-hidden className={clsx('h-4 w-4 animate-spin', className)} />;
}

export function PageSpinner() {
  return (
    <div className="flex min-h-[40vh] items-center justify-center text-slate" role="status">
      <Spinner className="h-6 w-6" />
      <span className="sr-only">Loading</span>
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={clsx('animate-pulse rounded-lg bg-line/70', className)} />;
}

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="panel flex flex-col items-start gap-3 px-6 py-10">
      <h3 className="text-xl font-bold">{title}</h3>
      {children && <p className="max-w-prose text-slate">{children}</p>}
      {action}
    </div>
  );
}

/** Error panel: says what failed, offers a retry and shows the correlation ID for support. */
export function ErrorState({ error, onRetry, title = 'This could not be loaded' }: {
  error: unknown; onRetry?: () => void; title?: string;
}) {
  const e = error instanceof ApiError ? error : null;
  return (
    <div role="alert" className="panel flex flex-col items-start gap-2 border-brick/30 px-6 py-6">
      <div className="flex items-center gap-2 font-semibold text-brick">
        <AlertTriangle className="h-5 w-5" aria-hidden /> {title}
      </div>
      <p className="text-sm text-slate">{e?.message ?? (error instanceof Error ? error.message : 'Unexpected error')}</p>
      {e?.correlationId && <p className="text-xs text-slate">Reference {e.correlationId}</p>}
      {onRetry && <button className="btn-quiet mt-2" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function InlineError({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof Error ? error.message : String(error);
  return <p role="alert" className="rounded-lg bg-brick/10 px-3 py-2 text-sm text-brick">{msg}</p>;
}

interface FieldProps {
  label: string;
  error?: string;
  hint?: string;
  children: ReactNode;
  htmlFor?: string;
}

export function Field({ label, error, hint, children, htmlFor }: FieldProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-sm font-medium">{label}</label>
      {children}
      {hint && !error && <p className="text-xs text-slate">{hint}</p>}
      {error && <p className="text-xs font-medium text-brick">{error}</p>}
    </div>
  );
}

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...p }, ref) => <input ref={ref} className={clsx('input', className)} {...p} />);
Input.displayName = 'Input';

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, ...p }, ref) => <select ref={ref} className={clsx('input pr-8', className)} {...p} />);
Select.displayName = 'Select';

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...p }, ref) => <textarea ref={ref} className={clsx('input min-h-[96px]', className)} {...p} />);
Textarea.displayName = 'Textarea';

const tone: Record<string, string> = {
  good: 'bg-leaf/12 text-leaf ring-leaf/30',
  bad: 'bg-brick/10 text-brick ring-brick/30',
  wait: 'bg-tag/25 text-ink ring-tag-deep/40',
  info: 'bg-ink/8 text-ink ring-ink/20',
  quiet: 'bg-line/60 text-slate ring-line',
};

const statusTone: Record<string, keyof typeof tone> = {
  CONFIRMED: 'good', DELIVERED: 'good', APPROVED: 'good', PUBLISHED: 'good', CAPTURED: 'good', SENT: 'good',
  SUCCEEDED: 'good', ACTIVE: 'good', SALE: 'good', SHIPPED: 'info', PARTIALLY_SHIPPED: 'info', AUTHORIZED: 'info',
  PENDING: 'wait', CANCELLATION_REQUESTED: 'wait', UNKNOWN: 'wait', RECONCILIATION_REQUIRED: 'wait',
  COMPENSATING: 'wait', DRAFT: 'quiet', ARCHIVED: 'quiet', VOIDED: 'quiet', REFUNDED: 'quiet', HIDDEN: 'quiet',
  CANCELLED: 'bad', FAILED: 'bad', REJECTED: 'bad', SUSPENDED: 'bad', BLOCKED: 'bad', DECLINED: 'bad',
  CAPTURE_FAILED: 'bad', REFUND_FAILED: 'bad', DEACTIVATED: 'bad', REFUND: 'bad',
};

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  return (
    <span className={clsx('inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset',
      tone[statusTone[status] ?? 'info'])}>
      {label ?? humanize(status)}
    </span>
  );
}

export function PriceTag({ amount, currency = 'USD', size = 'md', children }: {
  amount?: number | null; currency?: string; size?: 'sm' | 'md' | 'lg'; children?: ReactNode;
}) {
  return (
    <span className={clsx('price-tag', size === 'sm' && 'py-0.5 text-sm', size === 'md' && 'py-1 text-base',
      size === 'lg' && 'py-1.5 pl-7 text-2xl')}>
      {children ?? money(amount, currency)}
    </span>
  );
}

export function Stars({ value, count, size = 14 }: { value: number; count?: number; size?: number }) {
  const v = Math.round(Number(value) * 2) / 2;
  return (
    <span className="inline-flex items-center gap-1 text-sm text-slate" aria-label={`Rated ${Number(value).toFixed(1)} out of 5`}>
      <span className="inline-flex" aria-hidden>
        {[1, 2, 3, 4, 5].map((i) => (
          <Star key={i} width={size} height={size}
            className={i <= v ? 'fill-tag-deep text-tag-deep' : i - 0.5 === v ? 'fill-tag/60 text-tag-deep' : 'text-line'} />
        ))}
      </span>
      {count !== undefined && <span className="tabular">({count})</span>}
    </span>
  );
}

export function Pagination({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <nav className="flex items-center justify-between gap-3 pt-4" aria-label="Pagination">
      <button className="btn-quiet" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous</button>
      <span className="text-sm text-slate tabular">Page {page + 1} of {totalPages}</span>
      <button className="btn-quiet" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</button>
    </nav>
  );
}

export function PageHeader({ title, description, actions }: { title: string; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-3xl font-extrabold sm:text-4xl">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-slate">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

/** Deterministic placeholder artwork for products without images: a tag shape on an ink tile. */
export function ProductArt({ name, url, className }: { name: string; url?: string | null; className?: string }) {
  if (url) {
    return <img src={url} alt={name} loading="lazy" className={clsx('h-full w-full object-cover', className)} />;
  }
  const hue = [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
  return (
    <div aria-hidden className={clsx('flex h-full w-full items-center justify-center', className)}
      style={{ background: `linear-gradient(150deg, hsl(${hue} 32% 88%), hsl(${(hue + 40) % 360} 28% 78%))` }}>
      <span className="font-display text-4xl font-extrabold text-ink/50">
        {name.split(/\s+/).slice(0, 2).map((w) => w[0]).join('')}
      </span>
    </div>
  );
}

export function Dialog({ open, onClose, title, children }: {
  open: boolean; onClose: () => void; title: string; children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} onClose={onClose} onCancel={onClose}
      className="w-[min(32rem,calc(100vw-2rem))] rounded-xl border border-line bg-surface p-0 text-ink backdrop:bg-ink/40">
      <div className="p-6">
        <h2 className="mb-4 text-xl font-bold">{title}</h2>
        {children}
      </div>
    </dialog>
  );
}

export function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="panel px-5 py-4">
      <div className="text-sm text-slate">{label}</div>
      <div className="mt-1 font-display text-2xl font-bold tabular">{value}</div>
      {sub && <div className="mt-0.5 text-xs text-slate">{sub}</div>}
    </div>
  );
}
