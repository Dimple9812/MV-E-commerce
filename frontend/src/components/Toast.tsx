import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { CheckCircle2, XCircle } from 'lucide-react';

interface Toast {
  id: number;
  kind: 'ok' | 'error';
  text: string;
}

const ToastContext = createContext<(kind: Toast['kind'], text: string) => void>(() => undefined);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((kind: Toast['kind'], text: string) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, kind, text }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed inset-x-4 bottom-4 z-50 flex flex-col gap-2 sm:left-auto sm:w-96"
        aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} role="status"
            className="pointer-events-auto flex items-start gap-2 rounded-lg border border-line bg-surface px-4 py-3 text-sm shadow-lg">
            {t.kind === 'ok' ? <CheckCircle2 className="mt-0.5 h-4 w-4 text-leaf" aria-hidden />
              : <XCircle className="mt-0.5 h-4 w-4 text-brick" aria-hidden />}
            <span>{t.text}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const push = useContext(ToastContext);
  return {
    ok: (text: string) => push('ok', text),
    error: (e: unknown) => push('error', e instanceof Error ? e.message : String(e)),
  };
}
