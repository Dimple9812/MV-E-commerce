/**
 * The real-time link to chat-service. The app talks to a {@link ChatTransport}; production uses STOMP over a
 * WebSocket to /ws/chat, the public demo registers an in-browser transport (BroadcastChannel) instead.
 *
 * Reconnection: exponential backoff (1 s doubling to 30 s) with jitter, and a fresh access token for every attempt.
 * The app is told about each state so it can show "Reconnecting…" and resynchronise missed messages afterwards.
 */
import { Client, type IFrame, type IMessage } from '@stomp/stompjs';
import { getAccessToken, refreshSession } from '@/lib/api';
import type { ChatEvent } from '@/lib/types';

export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting';
export type ChatDestination = '/app/chat.send' | '/app/chat.typing' | '/app/chat.read';

export interface TransportHandlers {
  onState(state: ConnectionState): void;
  /** called after every successful (re)connection; `resumed` is true when an earlier connection was lost */
  onConnected(resumed: boolean): void;
  onEvent(event: ChatEvent): void;
}

export interface ChatTransport {
  start(handlers: TransportHandlers): void;
  stop(): void;
  /** @returns false when not connected (the caller then falls back to HTTP) */
  publish(destination: ChatDestination, body: unknown): boolean;
  /** reconnect now, e.g. after the access token was renewed */
  restart(): void;
}

const QUEUES = ['messages', 'typing', 'receipts', 'presence', 'errors'] as const;

export function backoffDelay(attempt: number, random = Math.random): number {
  const base = Math.min(30_000, 1000 * 2 ** Math.max(0, attempt - 1));
  return Math.round(base * (0.75 + random() * 0.5)); // ±25 % jitter so tabs do not reconnect in lockstep
}

export class StompTransport implements ChatTransport {
  private client: Client | null = null;
  private handlers: TransportHandlers | null = null;
  private attempts = 0;
  private everConnected = false;
  private retryTimer: number | null = null;
  private stopped = true;
  /** a planned reconnect (new token): do not show "Reconnecting…" for it */
  private quiet = false;

  constructor(private readonly url = defaultUrl()) {}

  start(handlers: TransportHandlers) {
    this.handlers = handlers;
    this.stopped = false;
    this.open();
  }

  stop() {
    this.stopped = true;
    if (this.retryTimer) window.clearTimeout(this.retryTimer);
    this.retryTimer = null;
    void this.client?.deactivate();
    this.client = null;
    this.everConnected = false;
    this.attempts = 0;
    this.handlers?.onState('idle');
  }

  restart() {
    if (this.stopped) return;
    this.quiet = this.everConnected;
    const old = this.client;
    this.client = null;
    void old?.deactivate();
    this.open();
  }

  publish(destination: ChatDestination, body: unknown): boolean {
    if (!this.client?.connected) return false;
    this.client.publish({ destination, body: JSON.stringify(body), headers: { 'content-type': 'application/json' } });
    return true;
  }

  private open() {
    if (this.stopped) return;
    if (!this.quiet) this.handlers?.onState(this.everConnected ? 'reconnecting' : 'connecting');
    const client = new Client({
      brokerURL: this.url,
      reconnectDelay: 0, // reconnection is ours: backoff with jitter and a fresh token each time
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      beforeConnect: async (c) => {
        const token = getAccessToken() ?? (await refreshSession())?.accessToken;
        c.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      onConnect: () => {
        const resumed = this.everConnected;
        this.everConnected = true;
        this.attempts = 0;
        this.quiet = false;
        QUEUES.forEach((q) => client.subscribe(`/user/queue/${q}`, (m: IMessage) => {
          try {
            this.handlers?.onEvent({ queue: q, payload: JSON.parse(m.body) } as ChatEvent);
          } catch { /* ignore a malformed frame */ }
        }));
        this.handlers?.onState('connected');
        this.handlers?.onConnected(resumed);
      },
      onStompError: (frame: IFrame) => {
        // token_expired / unauthorized: the server closes the socket; renew the token before the next attempt
        if (frame.headers.message === 'token_expired' || frame.headers.message === 'unauthorized') {
          void refreshSession();
        }
      },
      onWebSocketClose: () => {
        if (this.client !== client || this.stopped) return;
        this.scheduleRetry();
      },
    });
    this.client = client;
    client.activate();
  }

  private scheduleRetry() {
    if (this.retryTimer) return;
    this.quiet = false;
    this.attempts += 1;
    this.handlers?.onState(this.everConnected ? 'reconnecting' : 'connecting');
    void this.client?.deactivate();
    this.client = null;
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null;
      this.open();
    }, backoffDelay(this.attempts));
  }
}

function defaultUrl() {
  const { protocol, host } = window.location;
  return `${protocol === 'https:' ? 'wss' : 'ws'}://${host}/ws/chat`;
}

// ---------- pluggable factory (the demo build swaps in its own transport) ----------

let factory: () => ChatTransport = () => new StompTransport();

export function setChatTransportFactory(f: () => ChatTransport) {
  factory = f;
}

export function createChatTransport(): ChatTransport {
  return factory();
}
