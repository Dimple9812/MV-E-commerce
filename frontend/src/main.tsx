import { StrictMode, type ComponentType } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/lib/api';
import { AuthProvider } from '@/auth/AuthProvider';
import { CartProvider } from '@/cart/CartProvider';
import { ToastProvider } from '@/components/Toast';
import { App } from './App';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      // Client errors (4xx) will not fix themselves; retry only network failures and 5xx, at most twice.
      retry: (count, error) => count < 2 && !(error instanceof ApiError && error.status < 500),
    },
  },
});

async function start() {
  // The public demo build (VITE_DEMO_MODE=true) answers /api calls in the browser; the import is dynamic so the
  // regular build contains none of it.
  let Banner: ComponentType | null = null;
  if (import.meta.env.VITE_DEMO_MODE === 'true') {
    const demo = await import('./demo');
    demo.installDemoBackend();
    Banner = demo.DemoBanner;
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <AuthProvider>
            <CartProvider>
              <ToastProvider>
                {Banner && <Banner />}
                <App />
              </ToastProvider>
            </CartProvider>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </StrictMode>,
  );
}

void start();
