import { useEffect, useState } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useAuth } from '@/auth/AuthProvider';
import { getAccessToken } from '@/lib/api';
import type { ChatSide } from '@/lib/types';
import { chatApi, chatKeys } from './cache';

export function useInbox(side: ChatSide) {
  const { user } = useAuth();
  return useInfiniteQuery({
    queryKey: chatKeys.inbox(side),
    queryFn: ({ pageParam }) => chatApi.inbox(side, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? null,
    enabled: !!user,
    // live events refresh it; this is only the safety net
    refetchInterval: 60_000,
  });
}

export function useConversation(id: string | undefined) {
  return useQuery({
    queryKey: chatKeys.conversation(id ?? ''),
    queryFn: () => chatApi.conversation(id!),
    enabled: !!id,
  });
}

/** Timeline pages, newest page first; older pages are fetched as the user scrolls up. */
export function useMessages(id: string | undefined) {
  return useInfiniteQuery({
    queryKey: chatKeys.messages(id ?? ''),
    queryFn: ({ pageParam }) => chatApi.messages(id!, pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.hasMore && last.items.length ? last.items[0]!.id : undefined),
    enabled: !!id,
    staleTime: Infinity, // kept current by pushed events and the reconnect resync
  });
}

export function useUnreadChats() {
  const { user } = useAuth();
  return useQuery({
    queryKey: chatKeys.unread,
    queryFn: chatApi.unread,
    enabled: !!user,
    refetchInterval: 60_000,
  });
}

export function useQuickReplies(enabled: boolean) {
  return useQuery({ queryKey: chatKeys.quickReplies, queryFn: chatApi.quickReplies, enabled });
}

/**
 * Chat images are only served to the people in the conversation, so they are fetched with the access token and
 * shown from an object URL (an <img src> could not send the Authorization header).
 */
export function useAuthedImage(url: string | null | undefined) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!url) return undefined;
    if (url.startsWith('data:') || url.startsWith('blob:')) {
      setSrc(url);
      return undefined;
    }
    let objectUrl: string | null = null;
    let cancelled = false;
    setFailed(false);
    const token = getAccessToken();
    fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {}, credentials: 'same-origin' })
      .then(async (r) => {
        if (!r.ok) throw new Error(String(r.status));
        const blob = await r.blob();
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
      })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [url]);
  return { src, failed };
}
