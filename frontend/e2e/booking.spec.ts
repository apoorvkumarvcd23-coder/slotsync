import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';

const passwords = JSON.parse(readFileSync(new URL('../../.secrets/demo-users.json', import.meta.url), 'utf8')) as Record<string, string>;
const room = '10000000-0000-0000-0000-000000000001';
const other = '10000000-0000-0000-0000-000000000002';
const interval = (days: number) => {
  const start = new Date(Date.now() + days * 86400000); start.setUTCHours(10, 0, 0, 0);
  return { startsAt: start.toISOString(), endsAt: new Date(start.getTime() + 3600000).toISOString() };
};
async function signIn(page: Page, username: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'My bookings / Sign in' }).click();
  await page.getByLabel('Username', { exact: true }).fill(username);
  await page.getByLabel('Password', { exact: true }).fill(passwords[username]);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('form', { name: 'Create booking' })).toBeVisible();
}
async function apiSession(api: APIRequestContext, username: string) {
  const initial = await (await api.get('/api/session')).json();
  expect((await api.post('/api/login', { form: { username, password: passwords[username] },
    headers: { [initial.csrfHeader]: initial.csrfToken } })).status()).toBe(204);
  const current = await (await api.get('/api/session')).json();
  return { [current.csrfHeader]: current.csrfToken };
}

test('requester logs in, books, sees conflict, cancels and rebooks through React', async ({ page }) => {
  await signIn(page, 'requester');
  const cookie = (await page.context().cookies()).find(value => value.name === 'JSESSIONID');
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe('Lax');
  const times = interval(2);
  await page.getByRole('combobox', { name: 'Booking resource' }).selectOption(room);
  await page.getByLabel('Start (UTC)').fill(times.startsAt.slice(0, 16));
  await page.getByLabel('End (UTC)').fill(times.endsAt.slice(0, 16));
  await page.getByRole('button', { name: 'Create booking', exact: true }).click();
  await expect(page.getByText('PENDING', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/booking-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/booking-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Create booking', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('slot is already held');
  await page.getByRole('button', { name: 'Cancel booking', exact: true }).click();
  await expect(page.getByText('PENDING', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Create booking', exact: true }).click();
  await expect(page.getByText('PENDING', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel booking', exact: true }).click();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByLabel('Username', { exact: true })).toBeVisible();
});

test('actual cookie sessions enforce role, ownership and CSRF boundaries', async ({ playwright, baseURL }) => {
  const owner = await playwright.request.newContext({ baseURL });
  const stranger = await playwright.request.newContext({ baseURL });
  const anonymous = await playwright.request.newContext({ baseURL });
  const headers = await apiSession(owner, 'requester');
  const strangerHeaders = await apiSession(stranger, 'requester2');
  const times = interval(6);
  const data = { resourceId: other, ...times };
  expect((await anonymous.get('/api/bookings')).status()).toBe(401);
  expect((await owner.post('/api/bookings', { data })).status()).toBe(403);
  const result = await owner.post('/api/bookings', { headers: { ...headers, 'Idempotency-Key': 'browser-' + crypto.randomUUID() }, data });
  expect(result.status()).toBe(201); const booking = await result.json();
  expect((await stranger.get('/api/bookings/' + booking.id)).status()).toBe(404);
  expect((await stranger.post('/api/bookings/' + booking.id + '/cancel', { headers: strangerHeaders })).status()).toBe(404);
  for (const name of ['approver', 'admin']) {
    const role = await playwright.request.newContext({ baseURL });
    const roleHeaders = await apiSession(role, name);
    expect((await role.get('/api/bookings')).status()).toBe(403);
    expect((await role.post('/api/bookings', { data, headers: { ...roleHeaders, 'Idempotency-Key': crypto.randomUUID() } })).status()).toBe(403);
    await role.dispose();
  }
  expect((await owner.post('/api/bookings/' + booking.id + '/cancel', { headers })).status()).toBe(200);
  expect((await owner.post('/api/logout', { headers })).status()).toBe(204);
  expect((await owner.get('/api/bookings')).status()).toBe(401);
  await owner.dispose(); await stranger.dispose(); await anonymous.dispose();
});

test('lost create response replays the same request key against PostgreSQL', async ({ page }) => {
  await signIn(page, 'requester2');
  const times = interval(3);
  await page.getByRole('combobox', { name: 'Booking resource' }).selectOption(other);
  await page.getByLabel('Start (UTC)').fill(times.startsAt.slice(0, 16));
  await page.getByLabel('End (UTC)').fill(times.endsAt.slice(0, 16));
  const keys: string[] = [];
  await page.route('**/api/bookings', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    keys.push(route.request().headers()['idempotency-key']);
    if (keys.length === 1) {
      const committed = await route.fetch();
      expect(committed.status()).toBe(201);
      await route.abort('failed');
    } else { await route.continue(); }
  });
  await page.getByRole('button', { name: 'Create booking', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Retry the unchanged booking form safely');
  const replayed = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/bookings'));
  await page.getByRole('button', { name: 'Create booking', exact: true }).click();
  const response = await replayed;
  expect(response.status()).toBe(200);
  expect(response.headers()['idempotent-replayed']).toBe('true');
  await expect(page.getByText('PENDING', { exact: true })).toHaveCount(1);
  expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1]);
  await page.getByRole('button', { name: 'Cancel booking', exact: true }).click();
});
