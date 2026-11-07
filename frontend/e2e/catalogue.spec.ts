import { expect, test } from '@playwright/test';

test('real catalogue API, type filter and mobile layout', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Atlas meeting room' })).toBeVisible();
  await expect(page.locator('article')).toHaveCount(3);
  await expect(page.getByText('Synthetic demonstration data')).toBeVisible();
  const api = await page.request.get('/api/resources');
  expect(api.status()).toBe(200);
  expect((await api.json()).map((r: { code: string }) => r.code)).toEqual(['KIT-PROJECTOR', 'ROOM-ATLAS', 'ROOM-BIRCH']);
  await page.screenshot({ path: 'test-results/catalogue-desktop.png', fullPage: true });
  await page.getByRole('combobox', { name: 'Resource type' }).selectOption('ROOM');
  await expect(page.locator('article')).toHaveCount(2);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: 'Atlas meeting room' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/catalogue-mobile.png', fullPage: true });
  expect(errors).toEqual([]);
});
