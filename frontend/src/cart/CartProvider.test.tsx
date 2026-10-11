import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { CartProvider, useCart } from './CartProvider';

const line = { variantId: 'v1', productId: 'p1', productName: 'Turntable', variantName: 'Walnut', vendorId: 'ven1',
  vendorName: 'Aurora Audio', unitPrice: 249, maxPerOrder: 3 };
const wrapper = ({ children }: { children: ReactNode }) => <CartProvider>{children}</CartProvider>;

describe('cart', () => {
  it('merges repeated adds and never exceeds the per-order limit', () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => result.current.add(line, 2));
    act(() => result.current.add(line, 2));
    expect(result.current.lines).toHaveLength(1);
    expect(result.current.lines[0]!.quantity).toBe(3);
    expect(result.current.count).toBe(3);
  });

  it('clamps quantity changes to 1..limit', () => {
    const { result } = renderHook(() => useCart(), { wrapper });
    act(() => result.current.add(line));
    act(() => result.current.setQuantity('v1', 0));
    expect(result.current.lines[0]!.quantity).toBe(1);
    act(() => result.current.setQuantity('v1', 50));
    expect(result.current.lines[0]!.quantity).toBe(3);
  });

  it('persists to localStorage and restores on reload', () => {
    const first = renderHook(() => useCart(), { wrapper });
    act(() => first.result.current.add(line, 2));
    first.unmount();
    const second = renderHook(() => useCart(), { wrapper });
    expect(second.result.current.lines[0]).toMatchObject({ variantId: 'v1', quantity: 2 });
  });

  it('ignores corrupted storage instead of crashing', () => {
    localStorage.setItem('marketflow.cart.v1', '{not json');
    const { result } = renderHook(() => useCart(), { wrapper });
    expect(result.current.lines).toEqual([]);
  });
});
