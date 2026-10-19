import clsx from 'clsx';
import { MessageCircle } from 'lucide-react';
import type { ReactNode } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/ui';
import type { ChatSide } from '@/lib/types';
import { ChatWindow, type Attachment } from './ChatWindow';
import { ConversationList } from './Inbox';

/**
 * Inbox and conversation side by side; on small screens one or the other (the URL decides).
 * A "Message seller" button navigates here with the card to attach in the router state.
 */
export function MessagesLayout({ side, base, title, description, empty, orderLink, extra }: {
  side: ChatSide; base: string; title: string; description: string; empty: string;
  orderLink: (orderId: string) => string | null; extra?: ReactNode;
}) {
  const { id } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const attachment = (location.state as { attach?: Attachment } | null)?.attach ?? null;
  return (
    <div className="flex flex-col">
      <PageHeader title={title} description={description} actions={extra} />
      <div className="panel grid h-[calc(100vh-14rem)] min-h-[30rem] overflow-hidden md:grid-cols-[20rem_1fr]">
        <div className={clsx('min-h-0 flex-col border-line md:flex md:border-r', id ? 'hidden' : 'flex')}>
          <ConversationList side={side} activeId={id} linkTo={(c) => `${base}/${c}`} emptyText={empty} />
        </div>
        <div className={clsx('min-h-0 flex-col', id ? 'flex' : 'hidden md:flex')}>
          {id ? (
            <ChatWindow key={id} conversationId={id} backTo={base} attachment={attachment} orderLink={orderLink}
              onAttachmentUsed={() => navigate(location.pathname, { replace: true, state: null })} />
          ) : (
            <div className="flex h-full flex-col items-center justify-center gap-2 p-6 text-center text-slate">
              <MessageCircle className="h-10 w-10" aria-hidden />
              <p>Choose a conversation.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export function BuyerMessagesPage() {
  return (
    <MessagesLayout side="BUYER" base="/account/messages" title="Messages"
      description="Your conversations with sellers. Order updates appear here as they happen."
      empty="No conversations yet. Use “Message seller” on a product, a shop or one of your orders."
      orderLink={(orderId) => `/account/orders/${orderId}`} />
  );
}
