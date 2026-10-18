import clsx from 'clsx';
import { MessageCircle } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthProvider';
import { useToast } from '@/components/Toast';
import { Spinner } from '@/components/ui';
import { ApiError } from '@/lib/api';
import { chatApi } from './cache';
import type { Attachment } from './ChatWindow';

/**
 * Opens (or creates) the conversation with a shop about a product, an order or the shop in general, and offers
 * the product or order card as an attachment to the first message.
 */
export function MessageSellerButton({ vendorId, productId, suborderId, attachLabel, label = 'Message seller', className }: {
  vendorId: string; productId?: string; suborderId?: string; attachLabel?: string; label?: string; className?: string;
}) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();
  const start = useMutation({
    mutationFn: () => chatApi.start({ vendorId, productId, suborderId }),
    onSuccess: (c) => {
      const attach: Attachment | null = productId ? { type: 'PRODUCT_CARD', productId, label: attachLabel ?? 'this product' }
        : suborderId ? { type: 'ORDER_CARD', suborderId, label: attachLabel ?? 'this order' } : null;
      navigate(`/account/messages/${c.id}`, { state: attach ? { attach } : null });
    },
    onError: (e) => toast.error(e instanceof ApiError && e.code === 'own_shop' ? 'This is your own shop.' : e),
  });
  const onClick = () => {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`);
      return;
    }
    start.mutate();
  };
  return (
    <button type="button" className={clsx('btn-quiet', className)} onClick={onClick} disabled={start.isPending}>
      {start.isPending ? <Spinner /> : <MessageCircle className="h-4 w-4" aria-hidden />}{label}
    </button>
  );
}
