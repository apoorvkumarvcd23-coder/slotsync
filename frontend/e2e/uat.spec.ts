import { expect, test, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';

const passwords = JSON.parse(readFileSync(new URL('../../.secrets/demo-users.json', import.meta.url), 'utf8')) as Record<string, string>;
const header = 'code,name,type,location,capacity\n';
type Booking = { id: string; resourceId: string; resourceName: string; startsAt: string; endsAt: string; status: string };
type Audit = { id: string; action: string; entityId: string; actor: string };
async function signIn(page: Page, name: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'My bookings / Sign in', exact: true }).click();
  await page.getByLabel('Username', { exact: true }).fill(name);
  await page.getByLabel('Password', { exact: true }).fill(passwords[name]);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByText('Signed in as', { exact: false })).toContainText(name);
  expect(await page.getByLabel('Password', { exact: true }).count()).toBe(0);
}
async function post(page: Page, path: string, data?: unknown, key?: string) {
  const session = await (await page.request.get('/api/session')).json();
  return page.request.post(path, { headers: { [session.csrfHeader]: session.csrfToken,
    ...(key ? { 'Idempotency-Key': key } : {}) }, ...(data === undefined ? {} : { data }) });
}
async function form(page: Page, resource: string, startsAt: string, minutes: number) {
  await page.getByRole('combobox', { name: 'Booking resource' }).selectOption(resource);
  await page.getByLabel('Start (UTC)').fill(startsAt.slice(0, 16));
  await page.getByLabel('End (UTC)').fill(new Date(Date.parse(startsAt) + minutes * 60000).toISOString().slice(0, 16));
}
async function submit(page: Page) {
  const response = page.waitForResponse(r => r.url().endsWith('/api/bookings') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Create booking', exact: true }).click();
  return response;
}
async function savePolicy(page: Page, minutes: number) {
  await page.getByLabel('Maximum duration (minutes)').fill(String(minutes));
  await page.getByLabel('Require approval', { exact: true }).check();
  const response = page.waitForResponse(r => r.url().endsWith('/api/admin/policy') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Save policy', exact: true }).click();
  const saved = await response; expect(saved.status()).toBe(200);
  await expect(page.getByRole('region', { name: 'Administration workspace' }).getByRole('status')).toContainText('saved. Existing bookings keep');
  await expect(page.getByRole('button', { name: 'Save policy', exact: true })).toBeEnabled();
  return saved.json();
}
const liveRow = (page: Page, name: string, status: string) => page.locator('.booking-row').filter({ hasText: name })
  .filter({ has: page.getByText(status, { exact: true }) });

test('formal UAT01–05: policy, atomic import, concurrent booking, decisions and reconciled audit', async ({ browser, baseURL }) => {
  const origin = new URL(baseURL!);
  expect(['127.0.0.1', 'localhost', '[::1]']).toContain(origin.hostname);
  const contexts = await Promise.all(['admin', 'requester', 'requester2', 'approver'].map(() => browser.newContext({ baseURL })));
  const pages = await Promise.all(contexts.map(c => c.newPage()));
  const [admin, alice, bob, approver] = pages;
  const cases: unknown[] = [];
  const run = crypto.randomUUID().slice(0, 8).toUpperCase();
  const roomCode = 'ROOM-UAT10-' + run, kitCode = 'KIT-UAT10-' + run;
  const roomName = 'UAT studio ' + run;
  const start = new Date(Date.now() + 30 * 86400000); start.setUTCHours(10, 0, 0, 0);
  const startsAt = start.toISOString();
  let old: Booking, changedPolicy: { version: number }, validBatch: { id: string }, badBatch: { id: string };
  let winner: Page, loser: Page, winnerName: string, winning: Booking, adjacent: Booking, rejected: Booking, final: Booking;
  let raceKey: string, racePayload: unknown, initialPolicy: { version: number; maxDurationMinutes: number; approvalRequired: boolean };
  try {
    await Promise.all(pages.map((page, i) => signIn(page, ['admin', 'requester', 'requester2', 'approver'][i])));
    const resourcesBefore = await (await admin.request.get('/api/resources')).json();
    await test.step('UAT01 — prospective policy, stale editor and unchanged replay', async () => {
      initialPolicy = await (await admin.request.get('/api/admin/policy')).json();
      expect(initialPolicy.maxDurationMinutes).toBe(1440); expect(initialPolicy.approvalRequired).toBe(true);
      await form(alice, '10000000-0000-0000-0000-000000000001', startsAt, 180);
      const created = await submit(alice); expect(created.status()).toBe(201); old = await created.json();
      changedPolicy = await savePolicy(admin, 120);
      expect(changedPolicy.version).toBeGreaterThan(initialPolicy.version);
      const unchanged = await savePolicy(admin, 120); expect(unchanged.version).toBe(changedPolicy.version);
      const stale = await post(admin, '/api/admin/policy', { expectedVersion: initialPolicy.version, maxDurationMinutes: 60, approvalRequired: true });
      expect(stale.status()).toBe(409); expect((await stale.json()).code).toBe('POLICY_CHANGED');
      const queue = await (await approver.request.get('/api/approvals')).json();
      expect(queue.find((r: { booking: Booking }) => r.booking.id === old.id).policyVersion).toBe(initialPolicy.version);
      expect((await (await alice.request.get('/api/bookings/' + old.id)).json()).status).toBe('PENDING');
      const replay = await post(alice, '/api/bookings', created.request().postDataJSON(), created.request().headers()['idempotency-key']);
      expect(replay.status()).toBe(200); expect(await replay.json()).toEqual(old);
      await form(alice, old.resourceId, new Date(Date.parse(startsAt) + 86400000).toISOString(), 121);
      const duration = await submit(alice); expect(duration.status()).toBe(400); expect((await duration.json()).code).toBe('DURATION_EXCEEDED');
      await expect(alice.getByRole('alert')).toContainText('maximum duration');
      await admin.screenshot({ path: 'test-results/uat-policy.png', fullPage: true });
      cases.push({ id: 'UAT01', status: 'passed', observed: { previousVersion: initialPolicy.version, newVersion: changedPolicy.version,
        oldBookingVersion: initialPolicy.version, oldBookingUnchanged: old.id, staleEditorHttp: stale.status(), durationHttp: duration.status(), unchangedSaveVersion: unchanged.version, originalResponseReplayAfterPolicyChange: true } });
    });
    let resource: { id: string };
    await test.step('UAT02 — reject invalid import, atomically apply two rows and replay once', async () => {
      const input = admin.getByLabel('Resource CSV', { exact: true });
      await input.fill(header + `${roomCode},Duplicate one,ROOM,Demo UAT,12\n${roomCode},Duplicate two,ROOM,Demo UAT,12`);
      const pending = admin.waitForResponse(r => r.url().endsWith('/api/admin/imports/preview') && r.request().method() === 'POST');
      await admin.getByRole('button', { name: 'Preview CSV', exact: true }).click(); badBatch = await (await pending).json();
      const preview = admin.getByRole('region', { name: 'Import preview' });
      await expect(preview).toContainText('2 input · 1 accepted · 1 rejected');
      await expect(preview.getByRole('button', { name: 'Confirm import', exact: true })).toBeDisabled();
      expect((await post(admin, '/api/admin/imports/' + badBatch.id + '/apply')).status()).toBe(400);
      expect(await (await admin.request.get('/api/resources')).json()).toEqual(resourcesBefore);
      await input.fill(header + `${roomCode},${roomName},ROOM,Demo UAT east,12\n${kitCode},UAT camera ${run},EQUIPMENT,Demo UAT desk,1`);
      const valid = admin.waitForResponse(r => r.url().endsWith('/api/admin/imports/preview') && r.request().method() === 'POST');
      await admin.getByRole('button', { name: 'Preview CSV', exact: true }).click(); validBatch = await (await valid).json();
      await expect(preview).toContainText('2 input · 2 accepted · 0 rejected');
      await preview.getByRole('button', { name: 'Confirm import', exact: true }).click();
      await expect(admin.getByRole('region', { name: 'Administration workspace' }).getByRole('status')).toContainText('2 resources inserted');
      const reapplied = await post(admin, '/api/admin/imports/' + validBatch.id + '/apply');
      expect((await reapplied.json()).replayed).toBe(true);
      const catalogue = await (await admin.request.get('/api/resources')).json();
      expect(catalogue).toHaveLength(resourcesBefore.length + 2);
      for (const code of [roomCode, kitCode]) expect(catalogue.filter((r: { code: string }) => r.code === code)).toHaveLength(1);
      resource = catalogue.find((r: { code: string }) => r.code === roomCode);
      await admin.screenshot({ path: 'test-results/uat-import.png', fullPage: true });
      await admin.setViewportSize({ width: 390, height: 844 });
      expect(await admin.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await admin.screenshot({ path: 'test-results/uat-mobile.png', fullPage: true });
      await admin.setViewportSize({ width: 1440, height: 1100 });
      cases.push({ id: 'UAT02', status: 'passed', observed: { input: 2, rejectedInvalidBatch: 1, invalidBatchNoResourceWrites: true,
        validBatchId: validBatch.id, inserted: 2, replayCreatesNoDuplicates: true, resourceCountBefore: resourcesBefore.length, resourceCountAfter: catalogue.length, mobileOverflow: false } });
    });
    await test.step('UAT03 — competing real browser submissions, adjacency and actor-key replay', async () => {
      // New pages load the imported catalogue from the server; no API response substitution.
      await Promise.all([signInAfterReload(alice), signInAfterReload(bob)]);
      await Promise.all([form(alice, resource!.id, startsAt, 60), form(bob, resource!.id, startsAt, 60)]);
      const responses = await Promise.all([submit(alice), submit(bob)]);
      expect(responses.map(r => r.status()).sort()).toEqual([201, 409]);
      const win = responses[0].status() === 201 ? 0 : 1;
      winner = [alice, bob][win]; loser = [bob, alice][win]; winnerName = ['requester', 'requester2'][win];
      winning = await responses[win].json();
      expect((await responses[1 - win].json()).code).toBe('SLOT_CONFLICT');
      await expect(loser.getByRole('alert')).toContainText('slot is already held');
      raceKey = responses[win].request().headers()['idempotency-key']; racePayload = responses[win].request().postDataJSON();
      const replay = await post(winner, '/api/bookings', racePayload, raceKey);
      expect(replay.status()).toBe(200); expect(replay.headers()['idempotent-replayed']).toBe('true'); expect(await replay.json()).toEqual(winning);
      const altered = await post(winner, '/api/bookings', { ...(racePayload as object), endsAt: new Date(Date.parse(winning.endsAt) + 60000).toISOString() }, raceKey);
      expect(altered.status()).toBe(409); expect((await altered.json()).code).toBe('REQUEST_KEY_REUSED');
      const neighbour = await post(loser, '/api/bookings', { resourceId: resource!.id, startsAt: winning.endsAt,
        endsAt: new Date(Date.parse(winning.endsAt) + 3600000).toISOString() }, 'uat-adjacent-' + crypto.randomUUID());
      expect(neighbour.status()).toBe(201); adjacent = await neighbour.json();
      expect((await post(loser, '/api/bookings/' + adjacent.id + '/cancel')).status()).toBe(200);
      await winner.screenshot({ path: 'test-results/uat-race.png', fullPage: true });
      cases.push({ id: 'UAT03', status: 'passed', observed: { concurrentHttp: responses.map(r => r.status()).sort(), winnerId: winning.id,
        winnerActor: winnerName, sameKeyReplay: replay.status(), changedPayload: altered.status(), adjacentHttp: neighbour.status(), oneActiveOverlap: true } });
    });
    await test.step('UAT04 — role/owner denials, approve/cancel, reject and released-slot reuse', async () => {
      expect((await loser.request.get('/api/bookings/' + winning.id)).status()).toBe(404);
      expect((await post(loser, '/api/bookings/' + winning.id + '/cancel')).status()).toBe(404);
      expect((await post(winner, '/api/approvals/' + winning.id + '/decision', { decision: 'APPROVED' })).status()).toBe(403);
      expect((await winner.request.get('/api/admin/audit')).status()).toBe(403);
      const request = approver.locator('.booking-row').filter({ hasText: roomName });
      await approver.getByRole('button', { name: 'Refresh queue', exact: true }).click();
      await expect(request).toBeVisible(); await approver.screenshot({ path: 'test-results/uat-approval.png', fullPage: true });
      await request.getByRole('button', { name: 'Approve', exact: true }).click();
      await expect(approver.getByRole('status')).toContainText('APPROVED');
      expect((await post(approver, '/api/approvals/' + winning.id + '/decision', { decision: 'APPROVED' })).status()).toBe(200);
      await winner.getByRole('button', { name: 'Refresh bookings', exact: true }).click();
      await liveRow(winner, roomName, 'APPROVED').getByRole('button', { name: 'Cancel booking', exact: true }).click();
      await expect(liveRow(winner, roomName, 'CANCELLED')).toBeVisible();
      const replacement = await submit(loser); expect(replacement.status()).toBe(201); rejected = await replacement.json();
      await approver.getByRole('button', { name: 'Refresh queue', exact: true }).click();
      await request.getByRole('button', { name: 'Reject', exact: true }).click();
      await expect(approver.getByRole('status')).toContainText('REJECTED');
      await loser.getByRole('button', { name: 'Refresh bookings', exact: true }).click();
      await expect(liveRow(loser, roomName, 'REJECTED')).toBeVisible();
      const next = await submit(loser); expect(next.status()).toBe(201); final = await next.json();
      await liveRow(loser, roomName, 'PENDING').getByRole('button', { name: 'Cancel booking', exact: true }).click();
      await expect(liveRow(loser, roomName, 'CANCELLED')).toHaveCount(2); // adjacent plus final request
      expect((await post(alice, '/api/bookings/' + old.id + '/cancel')).status()).toBe(200);
      cases.push({ id: 'UAT04', status: 'passed', observed: { foreignRead: 404, foreignCancel: 404, wrongRoleApproval: 403, wrongRoleAudit: 403,
        approved: winning.id, repeatedApprovalCreatesNoTransition: true, rejected: rejected.id, slotReusableAfterCancelAndReject: true, finalCancelled: final.id } });
    });
    await test.step('UAT05 — exact audit reconciliation and recorded durable business state', async () => {
      const restored = await savePolicy(admin, initialPolicy.maxDurationMinutes);
      const audit: Audit[] = await (await admin.request.get('/api/admin/audit')).json();
      const expected = new Map([[old.id, ['CREATED', 'CANCELLED']], [winning.id, ['CREATED', 'APPROVED', 'CANCELLED']],
        [adjacent.id, ['CREATED', 'CANCELLED']], [rejected.id, ['CREATED', 'REJECTED']], [final.id, ['CREATED', 'CANCELLED']]]);
      for (const [id, actions] of expected) expect(audit.filter(row => row.entityId === id).map(row => row.action).sort()).toEqual([...actions].sort());
      expect(audit.filter(row => row.entityId === validBatch.id && row.action === 'RESOURCES_IMPORTED')).toHaveLength(1);
      expect(audit.filter(row => row.entityId === badBatch.id)).toHaveLength(0);
      for (const version of [changedPolicy.version, restored.version]) expect(audit.filter(row => row.entityId === String(version) && row.action === 'POLICY_CHANGED')).toHaveLength(1);
      await admin.getByRole('button', { name: 'Refresh workspace', exact: true }).click();
      const history = admin.getByRole('region', { name: 'Audit history' });
      for (const action of ['POLICY_CHANGED', 'RESOURCES_IMPORTED', 'APPROVED', 'REJECTED', 'CANCELLED']) await expect(history).toContainText(action);
      await admin.screenshot({ path: 'test-results/uat-audit.png', fullPage: true });
      cases.push({ id: 'UAT05', status: 'passed', observed: { bookingEvents: 11, bookingIds: [...expected.keys()], invalidImportEvents: 0,
        successfulImportEvents: 1, changedPolicyEvents: 2, replayAddsNoAudit: true, restoredPolicy: restored,
        auditIds: audit.filter(row => expected.has(row.entityId) || row.entityId === validBatch.id).map(row => row.id).sort() } });
    });
    writeFileSync('test-results/uat.json', JSON.stringify({
      status: 'passed', recordedAt: new Date().toISOString(),
      integration: 'Actual React/Spring/PostgreSQL and separate generated-account cookie sessions; no service/browser mocks',
      cases, replayAfterRestart: { actor: winnerName!, key: raceKey!, payload: racePayload!, originalResponse: winning! },
      resourceCodes: [roomCode, kitCode], images: ['uat-policy.png', 'uat-import.png', 'uat-mobile.png', 'uat-race.png', 'uat-approval.png', 'uat-audit.png'],
      limits: 'Five synthetic core UAT cases; app-process restart is checked separately. No production scale or full December acceptance claim.'
    }, null, 2));
  } finally { for (const context of contexts) await context.close(); }
});

async function signInAfterReload(page: Page) {
  await page.reload();
  await page.getByRole('button', { name: 'My bookings / Sign in', exact: true }).click();
  await expect(page.getByRole('form', { name: 'Create booking' })).toBeVisible();
}
