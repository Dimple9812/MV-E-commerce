import { expect, test, type Page } from '@playwright/test';

/**
 * Live chat in the public demo build: two tabs of one browser share the in-browser backend, one signed in as the
 * Customer and one as the Seller. Messages, typing and read receipts travel between them over a BroadcastChannel.
 * Run with E2E_DEMO=1 against `vite preview` of a demo build.
 */
test.skip(!process.env.E2E_DEMO, 'demo build only (set E2E_DEMO=1)');

const banner = (page: Page) => page.getByRole('region', { name: 'About this demo' });
const inbox = (page: Page) => page.getByRole('navigation', { name: 'Conversations' });

async function signInAs(page: Page, role: 'Customer' | 'Seller') {
  await banner(page).getByRole('button', { name: role }).click();
  await expect(banner(page).getByRole('button', { name: role })).toBeDisabled();
}

function watchErrors(page: Page) {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(m.text()); });
  return errors;
}

test('customer and seller chat live in two tabs, with typing, receipts and the contact filter', async ({ context }) => {
  const buyer = await context.newPage();
  const seller = await context.newPage();
  const errors = [...watchErrors(buyer), ...watchErrors(seller)];
  await buyer.goto('/');
  await expect(banner(buyer)).toContainText('Open a second tab as Seller to see live chat');
  await signInAs(buyer, 'Customer');
  await seller.goto('/');
  await signInAs(seller, 'Seller');

  // each tab keeps its own demo user: the buyer tab is still the customer after reloading
  await buyer.reload();
  await expect(banner(buyer).getByRole('button', { name: 'Customer' })).toBeDisabled();

  // the seller waits in the shop inbox
  await seller.goto('/vendor/messages');
  await expect(seller.getByRole('heading', { name: 'Messages' }).first()).toBeVisible();

  // the buyer starts a conversation from a product page; the product card is offered as an attachment
  await buyer.goto('/search?q=headphones');
  await buyer.getByRole('link', { name: /Aurora Studio Headphones/ }).first().click();
  await buyer.getByRole('button', { name: 'Ask Aurora Sound Co.' }).click();
  await expect(buyer).toHaveURL(/\/account\/messages\/[0-9a-f-]{36}/);
  const text = `Do these come in white? (run ${Date.now().toString(36)})`;
  await buyer.getByPlaceholder(/^Message /).fill(text);
  await buyer.getByRole('button', { name: 'Send' }).click();
  await expect(buyer.getByRole('log', { name: 'Messages' }).getByText(text)).toBeVisible();

  // ...and it appears in the seller's inbox without a reload
  const row = inbox(seller).getByRole('link', { name: /Ada M\..*Aurora Studio Headphones/ });
  await expect(row).toContainText(text, { timeout: 10_000 });
  await row.click();
  await expect(seller.getByRole('log', { name: 'Messages' }).getByText(text)).toBeVisible();

  // opening it marks it read: the buyer's message turns to "Seen"
  await expect(buyer.getByRole('log', { name: 'Messages' }).getByText('Seen').last()).toBeAttached({ timeout: 10_000 });

  // typing indicator, then the reply arrives live
  await seller.getByPlaceholder(/^Message /).pressSequentially('Yes, white', { delay: 40 });
  await expect(buyer.getByText(/is typing/)).toBeVisible({ timeout: 10_000 });
  await seller.getByPlaceholder(/^Message /).fill('Yes, white is in stock and ships tomorrow.');
  await seller.getByRole('button', { name: 'Send' }).click();
  await expect(buyer.getByRole('log', { name: 'Messages' }).getByText('Yes, white is in stock and ships tomorrow.')).toBeVisible({ timeout: 10_000 });

  // contact details are refused with a reason, and nothing reaches the seller
  await buyer.getByPlaceholder(/^Message /).fill('Easier on WhatsApp, call me on +1 415 555 0199');
  await buyer.getByRole('button', { name: 'Send' }).click();
  await expect(buyer.getByRole('alert').filter({ hasText: /contact|payment/i }).first()).toBeVisible();
  await expect(seller.getByText('+1 415 555 0199')).toHaveCount(0);

  expect(errors).toEqual([]);
});

test('seeded conversations include order updates, and resetting the demo resets chats', async ({ page }) => {
  await page.goto('/');
  await signInAs(page, 'Customer');
  await page.goto('/account/messages');
  await inbox(page).getByRole('link', { name: /Aurora Sound Co\./ }).first().click();
  await expect(page.getByRole('log', { name: 'Messages' })).toBeVisible();

  // a message of ours, then a reset throws it away
  const text = `Before the reset (run ${Date.now().toString(36)})`;
  await page.goto('/account/messages');
  await inbox(page).getByRole('link', { name: /Kitchen & Co/ }).first().click();
  await page.getByPlaceholder(/^Message /).fill(text);
  await page.getByRole('button', { name: 'Send' }).click();
  await expect(page.getByRole('log', { name: 'Messages' }).getByText(text)).toBeVisible();

  await banner(page).getByRole('button', { name: 'How it works' }).click();
  await banner(page).getByRole('button', { name: 'Reset the demo data' }).click();
  await expect(page).toHaveURL(/\/$/);
  await signInAs(page, 'Customer');
  await page.goto('/account/messages');
  await inbox(page).getByRole('link', { name: /Kitchen & Co/ }).first().click();
  await expect(page.getByRole('log', { name: 'Messages' })).toBeVisible();
  await expect(page.getByText(text)).toHaveCount(0);
});

test('an order conversation shows SYSTEM status messages', async ({ page }) => {
  await page.goto('/');
  await signInAs(page, 'Customer');
  await page.goto('/account/messages');
  // the seeded order conversation carries the status updates posted from the order events
  await inbox(page).getByRole('link', { name: /Order #/ }).first().click();
  await expect(page.getByText(/Order confirmed/)).toBeVisible();
});
