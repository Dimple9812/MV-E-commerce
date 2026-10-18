import { Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useToast } from '@/components/Toast';
import { Dialog, Field, Input, Textarea } from '@/components/ui';
import { ApiError } from '@/lib/api';
import { chatApi, chatKeys } from './cache';
import { useQuickReplies } from './hooks';
import { MessagesLayout } from './MessagesPage';

/** Seller hub inbox (own chunk: shoppers never download it) with the quick-reply templates. */
export function VendorMessagesPage() {
  const [manage, setManage] = useState(false);
  return (
    <>
      <MessagesLayout side="VENDOR" base="/vendor/messages" title="Messages"
        description="Questions from buyers about your products and their orders. Fast answers sell more."
        empty="No buyer has written to your shop yet."
        orderLink={() => '/vendor/orders'}
        extra={<button className="btn-quiet" onClick={() => setManage(true)}>Quick replies</button>} />
      <QuickRepliesDialog open={manage} onClose={() => setManage(false)} />
    </>
  );
}

function QuickRepliesDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const replies = useQuickReplies(open);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const save = useMutation({
    mutationFn: () => chatApi.saveQuickReply({ title, body, sortOrder: replies.data?.length ?? 0 }),
    onSuccess: () => { setTitle(''); setBody(''); void qc.invalidateQueries({ queryKey: chatKeys.quickReplies }); },
    onError: (e) => toast.error(e instanceof ApiError ? e.message : 'Could not save'),
  });
  const remove = useMutation({
    mutationFn: (id: string) => chatApi.deleteQuickReply(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: chatKeys.quickReplies }),
  });
  return (
    <Dialog open={open} onClose={onClose} title="Quick replies">
      <p className="mb-3 text-sm text-slate">Saved answers you can insert into any conversation and edit before sending.</p>
      <ul className="mb-4 flex max-h-56 flex-col gap-2 overflow-y-auto">
        {replies.data?.map((q) => (
          <li key={q.id} className="flex items-start gap-2 rounded-lg border border-line p-2 text-sm">
            <span className="min-w-0 flex-1"><span className="block font-semibold">{q.title}</span><span className="block whitespace-pre-wrap text-slate">{q.body}</span></span>
            <button className="rounded p-1 text-brick hover:bg-mist" aria-label={`Delete ${q.title}`} onClick={() => remove.mutate(q.id)}><Trash2 className="h-4 w-4" /></button>
          </li>
        ))}
        {replies.data?.length === 0 && <li className="text-sm text-slate">None yet.</li>}
      </ul>
      <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <Field label="Title" htmlFor="qr-title"><Input id="qr-title" maxLength={60} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Shipping times" /></Field>
        <Field label="Reply" htmlFor="qr-body"><Textarea id="qr-body" rows={3} maxLength={1000} value={body} onChange={(e) => setBody(e.target.value)} placeholder="We ship within 2 working days…" /></Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-quiet" onClick={onClose}>Done</button>
          <button className="btn-primary" disabled={!title.trim() || !body.trim() || save.isPending}><Plus className="h-4 w-4" />Add</button>
        </div>
      </form>
    </Dialog>
  );
}
