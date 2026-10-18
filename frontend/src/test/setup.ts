import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { setChatTransportFactory } from '@/features/chat/transport';

// no real WebSocket in unit tests; chat tests install a FakeChatTransport themselves
setChatTransportFactory(() => ({ start: (h) => h.onState('idle'), stop: () => undefined, restart: () => undefined, publish: () => false }));

afterEach(() => {
  cleanup();
  localStorage.clear();
});
