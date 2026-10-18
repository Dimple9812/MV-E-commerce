import clsx from 'clsx';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useToast } from '@/components/Toast';
import { EmptyState, ErrorState, PageHeader, PageSpinner, StatusBadge } from '@/components/ui';
import { http } from '@/lib/api';
import { date, humanize } from '@/lib/format';
import type { ChatReport, Page } from '@/lib/types';
import { chatKeys } from './cache';
import { ChatWindow } from './ChatWindow';

const REASONS: Record<string, string> = {
  SCAM: 'Scam or fraud', OFF_PLATFORM_PAYMENT: 'Payment outside MarketFlow', ABUSE: 'Harassment or abuse', SPAM: 'Spam', OTHER: 'Other',
};

/** Reported conversations: read the whole conversation (deleted messages included), then dismiss or close it. */
export function AdminChatReportsPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [status, setStatus] = useState<'OPEN' | 'RESOLVED'>('OPEN');
  const [selected, setSelected] = useState<ChatReport | null>(null);
  const reports = useQuery({
    queryKey: ['admin', 'chat-reports', status],
    queryFn: () => http.get<Page<ChatReport>>(`/api/v1/admin/chat/reports?status=${status}&size=50`),
  });
  const resolve = useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'DISMISS' | 'BLOCK_CONVERSATION' }) =>
      http.post<ChatReport>(`/api/v1/admin/chat/reports/${id}/resolve`, { action, note: action === 'DISMISS' ? 'No violation found' : 'Closed after review' }),
    onSuccess: (r) => {
      toast.ok(r.resolution === 'CONVERSATION_BLOCKED' ? 'Conversation closed. Both sides were told.' : 'Report dismissed');
      setSelected(r);
      void qc.invalidateQueries({ queryKey: ['admin', 'chat-reports'] });
      void qc.invalidateQueries({ queryKey: chatKeys.conversation(r.conversationId) });
    },
    onError: (e) => toast.error(e),
  });
  return (
    <div>
      <PageHeader title="Chat reports" description="Conversations reported by buyers or sellers. You can read them; you cannot write in them." />
      <div className="mb-4 flex gap-2" role="group" aria-label="Filter by status">
        {(['OPEN', 'RESOLVED'] as const).map((s) => (
          <button key={s} aria-pressed={status === s} onClick={() => { setStatus(s); setSelected(null); }}
            className={clsx('rounded-full border px-3 py-1 text-sm font-medium', status === s ? 'border-ink bg-ink text-mist' : 'border-line bg-surface')}>
            {s === 'OPEN' ? 'Open' : 'Resolved'}
          </button>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-[22rem_1fr]">
        <div>
          {reports.isLoading ? <PageSpinner /> : reports.isError ? <ErrorState error={reports.error} onRetry={() => reports.refetch()} />
            : reports.data!.items.length === 0 ? <EmptyState title={status === 'OPEN' ? 'Nothing to review' : 'No resolved reports'} /> : (
              <ul className="flex flex-col gap-2">
                {reports.data!.items.map((r) => (
                  <li key={r.id}>
                    <button onClick={() => setSelected(r)} aria-pressed={selected?.id === r.id}
                      className={clsx('panel w-full p-4 text-left text-sm hover:border-ink/40', selected?.id === r.id && 'border-ink')}>
                      <span className="flex items-center justify-between gap-2"><span className="font-semibold">{REASONS[r.reason]}</span><StatusBadge status={r.resolution ?? r.status} /></span>
                      <span className="mt-1 block text-slate">{r.buyerName} ↔ {r.vendorName}</span>
                      <span className="block text-xs text-slate">Reported by the {r.reporterSide === 'BUYER' ? 'buyer' : 'shop'}, {date(r.createdAt, true)}{r.violations > 0 && ` · ${r.violations} blocked contact-detail attempt${r.violations === 1 ? '' : 's'}`}</span>
                      {r.details && <span className="mt-1 block italic">“{r.details}”</span>}
                    </button>
                  </li>
                ))}
              </ul>
            )}
        </div>
        <div className="panel flex h-[calc(100vh-16rem)] min-h-[28rem] flex-col overflow-hidden">
          {selected ? (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-2 text-sm">
                <span>{REASONS[selected.reason]}{selected.resolution && ` · ${humanize(selected.resolution)}`}</span>
                {selected.status === 'OPEN' && (
                  <span className="flex gap-2">
                    <button className="btn-quiet py-1.5" disabled={resolve.isPending} onClick={() => resolve.mutate({ id: selected.id, action: 'DISMISS' })}>Dismiss</button>
                    <button className="btn-danger py-1.5" disabled={resolve.isPending} onClick={() => resolve.mutate({ id: selected.id, action: 'BLOCK_CONVERSATION' })}>Close conversation</button>
                  </span>
                )}
              </div>
              <div className="min-h-0 flex-1"><ChatWindow key={selected.conversationId} conversationId={selected.conversationId} orderLink={(o) => `/admin/orders/${o}`} /></div>
            </>
          ) : <p className="m-auto p-6 text-sm text-slate">Select a report to read the conversation.</p>}
        </div>
      </div>
    </div>
  );
}
