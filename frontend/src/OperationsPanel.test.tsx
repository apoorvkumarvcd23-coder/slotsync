import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import OperationsPanel from './OperationsPanel';
const policy = { version: 4, maxDurationMinutes: 60, approvalRequired: true };
const invalid = { id: 'batch-1', valid: false, applied: false, inserted: 0, replayed: false,
  preview: { inputRows: 2, acceptedRows: 1, rejectedRows: 1, rows: [], errors: [{ row: 3, message: 'Duplicate code in this CSV.' }] } };
const ok = (value: unknown) => ({ ok: true, json: async () => value });

describe('operations workspace', () => {
  it('keeps policy edits visible when a stale save is rejected and refreshes only on request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => ok(url.endsWith('/policy') ? policy : [])));
    const mutate = vi.fn().mockRejectedValue(new Error('Policy changed. Refresh before saving.'));
    render(<OperationsPanel role="ADMIN" mutate={mutate} onResourcesChanged={() => {}} />);
    const minutes = await screen.findByLabelText('Maximum duration (minutes)');
    await screen.findByText('v4');
    fireEvent.change(minutes, { target: { value: '30' } });
    fireEvent.click(screen.getByLabelText('Require approval'));
    fireEvent.click(screen.getByRole('button', { name: 'Save policy' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Policy changed');
    expect(minutes).toHaveValue(30);
    expect(JSON.parse(mutate.mock.calls[0][1])).toEqual({ expectedVersion: 4, maxDurationMinutes: 30, approvalRequired: false });
    fireEvent.click(screen.getByRole('button', { name: 'Refresh workspace' }));
    await vi.waitFor(() => expect(minutes).toHaveValue(60));
  });

  it('shows reconciled row errors and cannot apply an invalid batch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => ok(url.endsWith('/policy') ? policy : [])));
    const mutate = vi.fn().mockResolvedValue(ok(invalid));
    render(<OperationsPanel role="ADMIN" mutate={mutate} onResourcesChanged={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Preview CSV' }));
    const preview = await screen.findByRole('region', { name: 'Import preview' });
    expect(preview).toHaveTextContent('2 input · 1 accepted · 1 rejected');
    expect(preview).toHaveTextContent('Row 3: Duplicate code');
    expect(within(preview).getByRole('button', { name: 'Confirm import' })).toBeDisabled();
    expect(mutate).toHaveBeenCalledTimes(1);
    fireEvent.change(screen.getByLabelText('Resource CSV'), { target: { value: 'revised CSV' } });
    expect(screen.queryByRole('region', { name: 'Import preview' })).not.toBeInTheDocument();
  });

  it('a failed decision keeps the pending request visible for refresh/retry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok([{ owner: 'requester', policyVersion: 1,
      booking: { id: 'booking-1', resourceName: 'Cedar studio', startsAt: '2027-01-01T10:00:00Z', endsAt: '2027-01-01T11:00:00Z' } }])));
    const mutate = vi.fn().mockRejectedValue(new Error('Connection lost. Check the service and try again.'));
    render(<OperationsPanel role="APPROVER" mutate={mutate} onResourcesChanged={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: /^Approve$/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Connection lost');
    expect(screen.getByText('Cedar studio')).toBeVisible();
    expect(mutate).toHaveBeenCalledWith('/api/approvals/booking-1/decision', JSON.stringify({ decision: 'APPROVED' }));
  });
});
