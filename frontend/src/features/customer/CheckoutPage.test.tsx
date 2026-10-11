import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setSession } from '@/lib/api';
import { json, mockApi, renderApp, tokenFor } from '@/test/harness';
import { CheckoutPage } from './CheckoutPage';

const address = { id: 'a1', label: 'Home', recipient: 'Ada Moreno', line1: '1 Analytical St', city: 'London', postalCode: 'N1 9GU', country: 'GB', isDefault: true };

function seedCart() {
  localStorage.setItem('marketflow.cart.v1', JSON.stringify([{ variantId: 'v1', productId: 'p1', productName: 'Turntable', variantName: 'Walnut',
    vendorId: 'ven1', vendorName: 'Aurora Audio', unitPrice: 249, quantity: 1, maxPerOrder: 3 }]));
}

describe('checkout', () => {
  afterEach(() => { vi.restoreAllMocks(); setSession(null); });

  it('reuses one idempotency key when the same order is retried after a failure', async () => {
    seedCart();
    let attempt = 0;
    const { calls } = mockApi({
      'POST /api/v1/auth/refresh': () => json(200, tokenFor()),
      'GET /api/v1/users/me/addresses': () => json(200, [address]),
      'POST /api/v1/orders/checkout': () => (++attempt === 1
        ? json(503, { status: 503, detail: 'Order service is temporarily unavailable' })
        : json(201, { id: 'o1', status: 'PENDING' })),
    });
    renderApp(<CheckoutPage />, { path: '/checkout', route: '/checkout' });
    const place = await screen.findByRole('button', { name: 'Place order' });
    await screen.findByText('Home');
    await userEvent.click(place);
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable');
    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByTestId('location')).toHaveTextContent('/account/orders/o1?placed=1');

    const posts = calls.filter((c) => c.key === 'POST /api/v1/orders/checkout');
    expect(posts).toHaveLength(2);
    const keys = posts.map((c) => (c.init!.headers as Record<string, string>)['Idempotency-Key']);
    expect(keys[0]).toBeTruthy();
    expect(keys[0]).toBe(keys[1]);
    const body = JSON.parse(String(posts[1]!.init!.body));
    expect(body.items).toEqual([{ variantId: 'v1', quantity: 1 }]);
    expect(body).not.toHaveProperty('unitPrice');
    expect(body.paymentToken).toBe('tok_visa');
    await waitFor(() => expect(JSON.parse(localStorage.getItem('marketflow.cart.v1')!)).toEqual([]));
  });

  it('requires a valid new address before calling the API', async () => {
    seedCart();
    const { calls } = mockApi({
      'POST /api/v1/auth/refresh': () => json(200, tokenFor()),
      'GET /api/v1/users/me/addresses': () => json(200, []),
    });
    renderApp(<CheckoutPage />, { path: '/checkout', route: '/checkout' });
    await userEvent.click(await screen.findByRole('button', { name: 'Place order' }));
    expect(await screen.findByText('Who should receive it?')).toBeInTheDocument();
    expect(calls.some((c) => c.key === 'POST /api/v1/orders/checkout')).toBe(false);
  });
});
