import { expect, test, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
const passwords = JSON.parse(readFileSync(new URL('../../.secrets/demo-users.json', import.meta.url), 'utf8')) as Record<string, string>;
async function signIn(page: Page, name: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'My bookings / Sign in' }).click();
  await page.getByLabel('Username', { exact: true }).fill(name);
  await page.getByLabel('Password', { exact: true }).fill(passwords[name]);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByText('Signed in as')).toContainText(name);
}
async function post(page: Page, path: string, data: unknown, key?: string) {
  const session = await (await page.request.get('/api/session')).json();
  return page.request.post(path, { data, headers: { [session.csrfHeader]: session.csrfToken, ...(key ? { 'Idempotency-Key': key } : {}) } });
}
async function save(page: Page, horizon: number) {
  await expect(page.getByLabel('Booking horizon (days)')).toBeEnabled();
  await page.getByLabel('Booking horizon (days)').fill(String(horizon));
  const result = page.waitForResponse(r => r.url().endsWith('/api/admin/policy') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Save policy', exact: true }).click();
  const response = await result; expect(response.status()).toBe(200);
  await expect(page.getByRole('button', { name: 'Save policy', exact: true })).toBeEnabled();
  return response.json();
}

test('weekly policy UI enforces horizon/hours and preserves original booking replay', async ({ page, browser, baseURL }) => {
  test.setTimeout(90000);
  await signIn(page, 'admin');
  const initial = await (await page.request.get('/api/admin/policy')).json();
  const context = await browser.newContext({ baseURL }); const requester = await context.newPage();
  await signIn(requester, 'requester');
  const day = new Date(Date.now() + 3 * 86400000); day.setUTCHours(0, 0, 0, 0);
  const at = (hour: number) => new Date(day.getTime() + hour * 3600000).toISOString();
  const room = '10000000-0000-0000-0000-000000000002';
  const originalPayload = { resourceId: room, startsAt: at(10), endsAt: at(11) };
  const key = 'weekly-policy-' + crypto.randomUUID();
  const originalResponse = await post(requester, '/api/bookings', originalPayload, key);
  expect(originalResponse.status()).toBe(201); const original = await originalResponse.json();
  let createdId: string | undefined;
  try {
    await page.getByLabel('Maximum duration (minutes)').fill('120');
    await page.getByLabel('Opening time (UTC)').fill('09:00');
    await page.getByLabel('Closing time (UTC)').fill('17:00');
    const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
    const targetDay = (day.getUTCDay() + 6) % 7;
    for (let i = 0; i < days.length; i++) await page.getByLabel(days[i], { exact: true }).setChecked(i === targetDay);
    const short = await save(page, 1);
    const horizon = await post(requester, '/api/bookings', { resourceId: room, startsAt: at(12), endsAt: at(13) }, crypto.randomUUID());
    expect(horizon.status()).toBe(400); expect((await horizon.json()).code).toBe('HORIZON_EXCEEDED');
    const weekly = await save(page, 7); expect(weekly.version).toBeGreaterThan(short.version);
    await page.reload();
    await page.getByRole('button', { name: 'My bookings / Sign in' }).click();
    await expect(page.getByLabel('Booking horizon (days)')).toHaveValue('7');
    await expect(page.getByLabel('Opening time (UTC)')).toHaveValue('09:00');
    await expect(page.getByLabel('Closing time (UTC)')).toHaveValue('17:00');
    const form = requester.getByRole('form', { name: 'Create booking' });
    await requester.getByLabel('Booking resource').selectOption(room);
    await requester.getByLabel('Start (UTC)').fill(at(8).slice(0, 16));
    await requester.getByLabel('End (UTC)').fill(at(9).slice(0, 16));
    await form.getByRole('button', { name: 'Create booking', exact: true }).click();
    await expect(requester.getByRole('alert')).toContainText('UTC opening days and hours');
    await requester.getByLabel('Start (UTC)').fill(at(16).slice(0, 16));
    await requester.getByLabel('End (UTC)').fill(at(17).slice(0, 16));
    const created = requester.waitForResponse(r => r.url().endsWith('/api/bookings') && r.request().method() === 'POST');
    await form.getByRole('button', { name: 'Create booking', exact: true }).click();
    const createdResponse = await created; expect(createdResponse.status()).toBe(201);
    createdId = (await createdResponse.json()).id;
    const replay = await post(requester, '/api/bookings', originalPayload, key);
    expect(replay.status()).toBe(200); expect(replay.headers()['idempotent-replayed']).toBe('true');
    expect(await replay.json()).toEqual(original);
    await page.screenshot({ path: 'test-results/weekly-policy.png', fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: 'test-results/weekly-policy-mobile.png', fullPage: true });
    writeFileSync('test-results/weekly-policy.json', JSON.stringify({ status: 'passed', integration: 'Actual React/Spring/PostgreSQL and Chromium; no service/browser mocks',
      horizonDenied: true, outsideHoursDenied: true, exactClosingAccepted: true, uiReloadRetainsSchedule: true, originalResponseReplay: true,
      mobileOverflow: false, policy: weekly, originalPolicyVersion: initial.version, images: ['weekly-policy.png', 'weekly-policy-mobile.png'] }, null, 2) + '\n');
  } finally {
    for (const id of [original.id, createdId].filter(Boolean)) {
      expect((await post(requester, '/api/bookings/' + id + '/cancel', undefined)).status()).toBe(200);
    }
    const current = await (await page.request.get('/api/admin/policy')).json();
    const { version: _old, ...settings } = initial;
    expect((await post(page, '/api/admin/policy', { ...settings, expectedVersion: current.version })).status()).toBe(200);
    await context.close();
  }
});
