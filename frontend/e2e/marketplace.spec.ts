import { expect, test, type Page } from '@playwright/test';

// Demo accounts created by scripts/seed-demo.mjs. The admin password is never hard-coded: set E2E_ADMIN_PASSWORD.
const PASSWORD = process.env.E2E_DEMO_PASSWORD ?? 'DemoPass2026!';
const CUSTOMER = process.env.E2E_CUSTOMER ?? 'linus@demo.marketflow.local';
const VENDOR = process.env.E2E_VENDOR ?? 'aurora@demo.marketflow.local';
const ADMIN = process.env.E2E_ADMIN_EMAIL ?? 'admin@marketflow.local';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD;

async function signIn(page: Page, email: string, password = PASSWORD, next = '/') {
  await page.goto(`/login?next=${encodeURIComponent(next)}`);
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function addToCart(page: Page, search: string, productName: string) {
  await page.goto(`/search?q=${encodeURIComponent(search)}`);
  await page.getByRole('link', { name: productName }).first().click();
  await expect(page.getByRole('heading', { level: 1, name: productName })).toBeVisible();
  await page.getByRole('button', { name: 'Add to cart' }).click();
}

async function checkout(page: Page, card: string) {
  await page.goto('/cart');
  await page.getByRole('button', { name: 'Go to checkout' }).click();
  await expect(page.getByRole('heading', { name: 'Checkout' })).toBeVisible();
  await expect(page.getByText('Use a new address')).toBeVisible();   // saved addresses have loaded
  const recipient = page.getByLabel('Recipient');
  if (await recipient.isVisible()) {
    await recipient.fill('Linus Example');
    await page.getByLabel('Street address').fill('12 Kernel Road');
    await page.getByLabel('City', { exact: true }).fill('Portland');
    await page.getByLabel('Postal code').fill('97201');
    await page.getByLabel('Country code').fill('US');
  }
  await page.getByText(card).click();
  await page.getByRole('button', { name: 'Place order' }).click();
  await expect(page).toHaveURL(/\/account\/orders\/[0-9a-f-]{36}/);
  return page.url().match(/orders\/([0-9a-f-]{36})/)![1]!;
}

test('guests can browse and search, and are asked to sign in at checkout', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('link', { name: 'MarketFlow home' }).first()).toBeVisible();
  await addToCart(page, 'skillet', 'Carbon Steel Skillet 28cm');
  await page.goto('/cart');
  await expect(page.getByText('Carbon Steel Skillet 28cm')).toBeVisible();
  await page.getByRole('button', { name: 'Sign in to check out' }).click();
  await expect(page).toHaveURL(/\/login\?next=%2Fcheckout|\/login\?next=\/checkout/);
});

test('customers cannot open the admin area', async ({ page }) => {
  await signIn(page, CUSTOMER);
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/$/);
});

test.describe.serial('order lifecycle across customer and seller', () => {
  // The demo build keeps its data per browser context, so state cannot carry over between tests; demo.spec.ts
  // covers the same journey within one context.
  test.skip(!!process.env.E2E_DEMO, 'needs a shared backend');
  let orderId = '';

  test('a customer places an order that the saga confirms', async ({ page }) => {
    await signIn(page, CUSTOMER);
    await addToCart(page, 'cable', 'Braided USB-C Cable 2m');
    orderId = await checkout(page, 'Visa ending 4242');
    await expect(page.getByText('Confirmed', { exact: true }).first()).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Payment: Captured')).toBeVisible();
  });

  test('the seller sees only their part of the order and ships it', async ({ page }) => {
    test.skip(!orderId, 'needs the order from the previous test');
    await signIn(page, VENDOR, PASSWORD, '/vendor/orders');
    await expect(page.getByRole('heading', { name: 'Orders to fulfil' })).toBeVisible();
    const row = page.getByRole('row', { name: new RegExp(`#${orderId.slice(0, 8).toUpperCase()}`) });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: 'Ship' }).click();
    await page.getByLabel('Carrier').fill('UPS');
    await page.getByLabel('Tracking number').fill(`1Z${Date.now()}`);
    await page.getByRole('button', { name: 'Mark as shipped' }).click();
    await expect(page.getByText('Shipped. The customer has been notified.')).toBeVisible();
  });

  test('the customer sees the shipment on the order', async ({ page }) => {
    test.skip(!orderId, 'needs the order from the first test');
    await signIn(page, CUSTOMER, PASSWORD, `/account/orders/${orderId}`);
    await expect(page.getByText('Shipped', { exact: true }).first()).toBeVisible();
  });
});

test('a declined card cancels the order without charging', async ({ page }) => {
  await signIn(page, CUSTOMER);
  await addToCart(page, 'cookbook', 'The Weeknight Cookbook');
  await checkout(page, 'Visa ending 0002');
  await expect(page.getByText('Your bank declined the payment. You were not charged; try another card.')).toBeVisible({ timeout: 30_000 });
});

test('admins see the marketplace overview and the saga of an order', async ({ page }) => {
  test.skip(!ADMIN_PASSWORD, 'set E2E_ADMIN_PASSWORD to run admin checks');
  await signIn(page, ADMIN, ADMIN_PASSWORD, '/admin');
  await expect(page.getByRole('heading', { name: 'Marketplace overview' })).toBeVisible();
  await expect(page.getByText('Gross merchandise value', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Orders' }).click();
  await page.getByRole('link', { name: /^#[0-9A-F]{8}$/ }).first().click();
  await expect(page.getByRole('heading', { name: 'Checkout saga' })).toBeVisible();
});
