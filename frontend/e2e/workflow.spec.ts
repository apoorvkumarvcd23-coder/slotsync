import { expect, test, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
const passwords = JSON.parse(readFileSync(new URL('../../.secrets/demo-users.json', import.meta.url), 'utf8')) as Record<string, string>;
async function signIn(page: Page, username: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'My bookings / Sign in' }).click();
  await page.getByLabel('Username', { exact: true }).fill(username);
  await page.getByLabel('Password', { exact: true }).fill(passwords[username]);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByText('Signed in as', { exact: false })).toContainText(username);
}
const header = 'code,name,type,location,capacity\n';
test('admin policy/import → requester booking → approver decision → audit persists', async ({ page, browser, baseURL }) => {
  test.setTimeout(90000);
  await signIn(page, 'admin');
  const adminWorkspace = page.getByRole('region', { name: 'Administration workspace' });
  await expect(page.getByRole('heading', { name: 'Administration', exact: true })).toBeVisible();
  await page.getByLabel('Maximum duration (minutes)').fill('120');
  await page.getByLabel('Require approval', { exact: true }).check();
  await page.getByRole('button', { name: 'Save policy' }).click();
  await expect(page.getByRole('status')).toContainText('saved. Existing bookings keep');
  await page.getByLabel('Resource CSV', { exact: true }).fill(header + 'ROOM-CORE07,Cedar studio,ROOM,Demo east wing,12\nROOM-CORE07,Duplicate,ROOM,Demo east wing,12');
  await page.getByRole('button', { name: 'Preview CSV' }).click();
  const preview = page.getByRole('region', { name: 'Import preview' });
  await expect(preview).toContainText('2 input · 1 accepted · 1 rejected');
  await expect(preview).toContainText('Row 3: Duplicate code');
  await expect(preview.getByRole('button', { name: 'Confirm import' })).toBeDisabled();
  await page.getByLabel('Resource CSV', { exact: true }).fill(header + 'ROOM-CORE07,"Cedar, studio",ROOM,Demo east wing,12\nKIT-CORE07,Camera kit,EQUIPMENT,Demo east desk,1');
  await page.getByRole('button', { name: 'Preview CSV' }).click();
  await expect(preview).toContainText('2 input · 2 accepted · 0 rejected');
  await preview.getByRole('button', { name: 'Confirm import' }).click();
  await expect(adminWorkspace.getByRole('status')).toContainText('2 resources inserted');
  await preview.getByRole('button', { name: 'Retry applied import' }).click();
  await expect(adminWorkspace.getByRole('status')).toContainText('no duplicate resources');
  const catalogue = await (await page.request.get('/api/resources')).json();
  expect(catalogue.filter((r: { code: string }) => r.code === 'ROOM-CORE07')).toHaveLength(1);
  const resource = catalogue.find((r: { code: string }) => r.code === 'ROOM-CORE07');
  await page.screenshot({ path: 'test-results/admin-import.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/admin-mobile.png', fullPage: true });
  const requesterContext = await browser.newContext({ baseURL });
  const approverContext = await browser.newContext({ baseURL });
  try {
    const requester = await requesterContext.newPage();
    await signIn(requester, 'requester');
    await requester.getByRole('combobox', { name: 'Booking resource' }).selectOption(resource.id);
    const start = new Date(Date.now() + 20 * 86400000); start.setUTCHours(10, 0, 0, 0);
    await requester.getByLabel('Start (UTC)').fill(start.toISOString().slice(0, 16));
    await requester.getByLabel('End (UTC)').fill(new Date(start.getTime() + 3600000).toISOString().slice(0, 16));
    await requester.getByRole('button', { name: 'Create booking', exact: true }).click();
    await expect(requester.getByText('PENDING', { exact: true })).toHaveCount(1);
    const approver = await approverContext.newPage();
    await signIn(approver, 'approver');
    const pending = approver.locator('.booking-row').filter({ hasText: 'Cedar, studio' });
    await expect(pending).toBeVisible();
    await approver.screenshot({ path: 'test-results/approval-queue.png', fullPage: true });
    await pending.getByRole('button', { name: 'Approve', exact: true }).click();
    await expect(approver.getByRole('status')).toContainText('APPROVED');
    await requester.getByRole('button', { name: 'Refresh bookings' }).click();
    await expect(requester.getByText('APPROVED', { exact: true })).toHaveCount(1);
    await requester.getByRole('button', { name: 'Cancel booking', exact: true }).click();
    await expect(requester.locator('.booking-row').filter({ hasText: 'Cedar, studio' }).getByText('CANCELLED', { exact: true })).toBeVisible();
    // Second request exercises the distinct rejection path through the actual UI.
    await requester.getByRole('button', { name: 'Create booking', exact: true }).click();
    await expect(requester.getByText('PENDING', { exact: true })).toHaveCount(1);
    await approver.getByRole('button', { name: 'Refresh queue' }).click();
    await pending.getByRole('button', { name: 'Reject', exact: true }).click();
    await expect(approver.getByRole('status')).toContainText('REJECTED');
    await requester.getByRole('button', { name: 'Refresh bookings' }).click();
    await expect(requester.getByText('REJECTED', { exact: true })).toHaveCount(1);
    await page.getByRole('button', { name: 'Refresh workspace' }).click();
    const audit = page.getByRole('region', { name: 'Audit history' });
    for (const action of ['POLICY_CHANGED', 'RESOURCES_IMPORTED', 'APPROVED', 'REJECTED', 'CANCELLED']) await expect(audit).toContainText(action);
    await page.setViewportSize({ width: 1440, height: 1100 });
    await page.screenshot({ path: 'test-results/workflow-audit.png', fullPage: true });
    // Restore the default prospective policy; the audit and booking snapshots remain.
    await page.getByLabel('Maximum duration (minutes)').fill('1440');
    await page.getByRole('button', { name: 'Save policy' }).click();
    await expect(page.getByRole('status')).toContainText('saved. Existing bookings keep');
    writeFileSync('test-results/workflow.json', JSON.stringify({
      integration: 'Actual same-origin React/Spring/PostgreSQL; generated local accounts; no backend/browser mocks',
      checks: ['prospective policy save', 'invalid duplicate preview disables apply', 'two-row atomic apply', 'reapply creates no duplicates',
        'imported resource booking', 'approve', 'cancel', 'reject', 'persisted audit', '390px admin overflow check'],
      resourcesAfterImport: catalogue.length, imported: 2, status: 'passed'
    }, null, 2));
  } finally { await requesterContext.close(); await approverContext.close(); }
});
