import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

/**
 * Client-side cart. Lines keep a display snapshot (name, price at the time it was added) only to render the
 * cart; checkout sends variant IDs and quantities, and the server re-prices everything from its own catalog
 * projection. The cart page refreshes prices and availability from the API.
 */
export interface CartLine {
  variantId: string;
  productId: string;
  productName: string;
  variantName: string;
  vendorId: string;
  vendorName: string;
  unitPrice: number;
  imageUrl?: string | null;
  quantity: number;
  maxPerOrder: number;
}

interface CartState {
  lines: CartLine[];
  count: number;
  add: (line: Omit<CartLine, 'quantity'>, qty?: number) => void;
  setQuantity: (variantId: string, qty: number) => void;
  remove: (variantId: string) => void;
  clear: () => void;
}

const KEY = 'marketflow.cart.v1';
const CartContext = createContext<CartState | null>(null);

function load(): CartLine[] {
  try {
    const raw = window.localStorage.getItem(KEY);
    const parsed = raw ? (JSON.parse(raw) as CartLine[]) : [];
    return Array.isArray(parsed) ? parsed.filter((l) => l.variantId && l.quantity > 0) : [];
  } catch {
    return [];
  }
}

export function CartProvider({ children }: { children: ReactNode }) {
  const [lines, setLines] = useState<CartLine[]>(load);

  useEffect(() => {
    try {
      window.localStorage.setItem(KEY, JSON.stringify(lines));
    } catch {
      /* storage unavailable (private mode): the cart still works for this tab */
    }
  }, [lines]);

  const add = useCallback((line: Omit<CartLine, 'quantity'>, qty = 1) => {
    setLines((prev) => {
      const existing = prev.find((l) => l.variantId === line.variantId);
      if (existing) {
        return prev.map((l) => l.variantId === line.variantId
          ? { ...l, ...line, quantity: Math.min(l.quantity + qty, line.maxPerOrder) } : l);
      }
      return [...prev, { ...line, quantity: Math.min(qty, line.maxPerOrder) }];
    });
  }, []);

  const setQuantity = useCallback((variantId: string, qty: number) => {
    setLines((prev) => prev.map((l) => l.variantId === variantId
      ? { ...l, quantity: Math.max(1, Math.min(qty, l.maxPerOrder)) } : l));
  }, []);

  const remove = useCallback((variantId: string) => setLines((p) => p.filter((l) => l.variantId !== variantId)), []);
  const clear = useCallback(() => setLines([]), []);

  const value = useMemo(() => ({
    lines, add, setQuantity, remove, clear, count: lines.reduce((n, l) => n + l.quantity, 0),
  }), [lines, add, setQuantity, remove, clear]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartState {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart must be used inside CartProvider');
  return ctx;
}
