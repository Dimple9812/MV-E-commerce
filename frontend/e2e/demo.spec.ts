import { expect, test, type Page } from '@playwright/test';

/**
 * Journeys for the public demo build (VITE_DEMO_MODE=true), where the backend runs inside the page and each browser
 * context starts from the same sample data. Run with E2E_DEMO=1 against `vite preview` of a demo build.
 */
test.skip(!process.env.E2E_DEMO, 'demo build only (set E2E_DEMO=1)');

function watchErrors(page: Page) {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  // "Failed to load resource" is the network (web fonts in an offline test runner), not the app
  page.on('console', (m) => { if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(m.text()); });
  return errors;
}

async function signInAs(page: Page, role: 'Customer' | 'Seller' | 'Admin') {
  await page.getByRole('region', { name: 'About this demo' }).getByRole('button', { name: role }).click();
  await expect(page.getByRole('region', { name: 'About this demo' }).getByRole('button', { name: role })).toBeDisabled();
}

async function buy(page: Page, search: string, product: string, card: string) {
  await page.goto(`/search?q=${encodeURIComponent(search)}`);
  await page.getByRole('link', { name: product }).first().click();
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await page.goto('/cart');
  await page.getByRole('button', { name: 'Go to checkout' }).click();
  await expect(page.getByText('Use a new address')).toBeVisible();
  await page.getByText(card).click();
  await page.getByRole('button', { name: 'Place order' }).click();
  await expect(page).toHaveURL(/\/account\/orders\/[0-9a-f-]{36}/);
  return page.url().match(/orders\/([0-9a-f-]{36})/)![1]!;
}

test('the store opens with sample products and the demo banner', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await expect(page.getByText('Live demo.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Just arrived' })).toBeVisible();
  await page.goto('/search?q=headphones');
  await expect(page.getByRole('link', { name: /Aurora Studio Headphones/ }).first()).toBeVisible();
  await page.goto('/search?category=cookware');
  await expect(page.getByRole('link', { name: /Carbon Steel Skillet/ }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: /Ridge Trail/ })).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('order, ship, deliver and review across customer and seller', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await signInAs(page, 'Customer');
  const orderId = await buy(page, 'cable', 'Braided USB-C Cable 2m', 'Visa ending 4242');
  await expect(page.getByText('Confirmed', { exact: true }).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('Payment: Captured')).toBeVisible();

  await signInAs(page, 'Seller');
  await page.goto('/vendor/orders');
  const row = page.getByRole('row', { name: new RegExp(`#${orderId.slice(0, 8).toUpperCase()}`) });
  await row.getByRole('button', { name: 'Ship' }).click();
  await page.getByLabel('Carrier').fill('UPS');
  await page.getByLabel('Tracking number').fill('1ZDEMO123');
  await page.getByRole('button', { name: 'Mark as shipped' }).click();
  await expect(page.getByText('Shipped. The customer has been notified.')).toBeVisible();
  await page.getByRole('group', { name: 'Filter by status' }).getByRole('button', { name: 'Shipped' }).click();
  await row.getByRole('button', { name: 'Mark delivered' }).click();
  await expect(row.getByText('Delivered')).toBeVisible();

  await signInAs(page, 'Customer');
  await page.goto(`/account/orders/${orderId}`);
  await expect(page.getByText('Delivered', { exact: true }).first()).toBeVisible();
  await page.getByRole('link', { name: 'Braided USB-C Cable 2m' }).first().click();
  await page.getByLabel('Headline').fill('Sturdy cable');
  await page.getByLabel('Your review').fill('Charges my laptop at full speed and survived a month in my bag.');
  await page.getByRole('button', { name: 'Publish review' }).click();
  await expect(page.getByText('Review published')).toBeVisible();
  await expect(page.getByText('Sturdy cable')).toBeVisible();
  expect(errors).toEqual([]);
});

test('payment edge cases follow the saga and its compensations', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await signInAs(page, 'Customer');
  await buy(page, 'cookbook', 'The Weeknight Cookbook', 'Visa ending 0002');
  await expect(page.getByText('Your bank declined the payment. You were not charged; try another card.')).toBeVisible({ timeout: 15_000 });
  await buy(page, 'skillet', 'Carbon Steel Skillet 28cm', 'Card that fails at capture');
  await expect(page.getByText('The payment could not be completed. The authorisation was released.')).toBeVisible({ timeout: 15_000 });
  await buy(page, 'knife', 'Chef Knife 20cm', 'Card on a slow network');
  await expect(page.getByText('Confirmed', { exact: true }).first()).toBeVisible({ timeout: 20_000 });

  // cancel the confirmed order: refund and restock
  await page.getByRole('button', { name: 'Cancel order' }).click();
  await page.getByLabel('Reason').fill('Ordered the wrong size');
  await page.getByRole('button', { name: 'Cancel and refund' }).click();
  await expect(page.getByText('Cancelled', { exact: true }).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/Payment: Refunded/)).toBeVisible();
  expect(errors).toEqual([]);
});

test('a new user applies to sell, gets approved and publishes a product', async ({ page }) => {
  test.setTimeout(90_000);
  const errors = watchErrors(page);
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Grace Hopper');
  await page.getByLabel('E-mail').fill('grace@example.com');
  await page.getByLabel('Password').fill('Compiler1952x');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).not.toHaveURL(/register/);
  await page.goto('/sell');
  await page.getByLabel('Shop name').fill('Harbour Ceramics');
  await page.getByLabel('What you sell').fill('Small-batch mugs and bowls.');
  await page.getByLabel('Contact e-mail').fill('grace@example.com');
  await page.getByRole('button', { name: 'Submit application' }).click();
  await expect(page.getByRole('link', { name: 'Go to the seller hub' })).toBeVisible({ timeout: 30_000 });
  await page.getByRole('link', { name: 'Go to the seller hub' }).click();

  await page.goto('/vendor/products/new');
  await page.getByLabel('Name', { exact: true }).fill('Speckled Stoneware Mug');
  await page.getByLabel('Category').selectOption({ label: 'Decor' });
  await page.getByLabel('SKU').fill('HC-MUG-01');
  await page.getByLabel('Price').fill('28');
  await page.getByRole('button', { name: 'Create draft' }).click();
  await expect(page.getByText('Draft created. Add stock and images, then publish.')).toBeVisible();
  await page.getByRole('button', { name: 'Publish' }).click();
  await expect(page.getByText('Published', { exact: true }).first()).toBeVisible();

  await page.goto('/vendor/inventory');
  await page.getByRole('row', { name: /HC-MUG-01/ }).getByRole('button', { name: 'Adjust' }).click();
  await page.getByLabel('Change').fill('12');
  await page.getByLabel('Reason').fill('First batch from the kiln');
  await page.getByRole('button', { name: 'Save adjustment' }).click();
  await expect(page.getByText('Stock for HC-MUG-01 is now 12')).toBeVisible();

  await page.goto('/search?q=mug');
  await expect(page.getByRole('link', { name: /Speckled Stoneware Mug/ }).first()).toBeVisible();
  expect(errors).toEqual([]);
});

test('an admin approves a pending seller and reads the overview', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await signInAs(page, 'Admin');
  await expect(page.getByRole('heading', { name: 'Marketplace overview' })).toBeVisible();
  await page.goto('/admin/vendors');
  const row = page.getByRole('row', { name: /Fjord Candle Studio/ });
  await row.getByRole('button', { name: 'Approve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: /Approve/ }).click();
  await expect(page.getByText('No applications waiting')).toBeVisible();
  await page.getByRole('group', { name: 'Filter by status' }).getByRole('button', { name: 'Approved' }).click();
  await expect(page.getByRole('row', { name: /Fjord Candle Studio/ })).toBeVisible();
  await page.goto('/admin/operations');
  await page.goto('/admin/audit');
  await expect(page.getByText('VENDOR_APPROVED').first()).toBeVisible();
  expect(errors).toEqual([]);
});
