import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import BookingPanel from './BookingPanel';

const resources = [{ id: 'resource-id', code: 'ROOM', name: 'Demo room', type: 'ROOM' as const, location: 'Synthetic campus', capacity: 8 }];
const anonymous = { username: null, role: null, csrfToken: 'before-login', csrfHeader: 'X-CSRF-TOKEN' };
const requester = { username: 'requester', role: 'REQUESTER', csrfToken: 'after-login', csrfHeader: 'X-CSRF-TOKEN' };
const ok = (value: unknown, status = 200) => ({ ok: true, status, json: async () => value });
const saved = { id: 'booking-id', resourceId: 'resource-id', resourceName: 'Demo room', startsAt: '2027-01-01T10:00:00Z', endsAt: '2027-01-01T11:00:00Z', status: 'PENDING' };

describe('booking workspace', () => {
  it('refreshes CSRF after actual login and clears the password field', async () => {
    let logged = false;
    const fetch = vi.fn().mockImplementation(async (url: string, request?: RequestInit) => {
      if (url === '/api/session') return ok(logged ? requester : anonymous);
      if (url === '/api/login') {
        expect((request!.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('before-login');
        logged = true; return ok(null, 204);
      }
      return ok([]);
    });
    vi.stubGlobal('fetch', fetch);
    render(<BookingPanel resources={resources} selectedId="resource-id" />);
    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'requester' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'synthetic-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('form', { name: 'Create booking' })).toBeVisible();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(call => call[0] === '/api/session')).toHaveLength(2);
  });

  it('retains one request key on a lost response, uses the refreshed CSRF and cancels safely', async () => {
    const keys: string[] = [];
    let posts = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, request?: RequestInit) => {
      if (url === '/api/session') return ok(requester);
      if (url === '/api/bookings' && request?.method === 'POST') {
        keys.push((request.headers as Record<string, string>)['Idempotency-Key']);
        expect((request.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('after-login');
        if (++posts === 1) throw new TypeError('lost response');
        return ok(saved, 200);
      }
      if (url.endsWith('/cancel')) return ok({ ...saved, status: 'CANCELLED' });
      return ok([]);
    }));
    render(<BookingPanel resources={resources} selectedId="resource-id" />);
    const button = await screen.findByRole('button', { name: 'Create booking' });
    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Retry the unchanged booking form safely');
    fireEvent.click(button);
    expect(await screen.findByText('PENDING', { exact: true })).toBeVisible();
    expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1]);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel booking' }));
    expect(await screen.findByText('CANCELLED', { exact: true })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Cancel booking' })).not.toBeInTheDocument();
  });

  it('shows role boundaries without requesting another users bookings', async () => {
    const fetch = vi.fn().mockImplementation(async (url: string) => url === '/api/session' ? ok({ ...requester, role: 'APPROVER' }) : ok([]));
    vi.stubGlobal('fetch', fetch);
    render(<BookingPanel resources={resources} selectedId="" />);
    expect(await screen.findByText('No pending requests.')).toBeVisible();
    expect(screen.queryByRole('form', { name: 'Create booking' })).not.toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalledWith('/api/bookings', expect.anything());
  });

  it('rejects reversed times locally and surfaces real conflict feedback', async () => {
    const fetch = vi.fn().mockImplementation(async (url: string, request?: RequestInit) =>
      url === '/api/session' ? ok(requester) : request?.method === 'POST'
        ? { ok: false, json: async () => ({ code: 'SLOT_CONFLICT' }) } : ok([]));
    vi.stubGlobal('fetch', fetch);
    render(<BookingPanel resources={resources} selectedId="resource-id" />);
    const start = await screen.findByLabelText('Start (UTC)');
    fireEvent.change(start, { target: { value: '2027-01-02T10:00' } });
    fireEvent.change(screen.getByLabelText('End (UTC)'), { target: { value: '2027-01-01T10:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create booking' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('end time after the start');
    expect(fetch.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(0);
    fireEvent.change(screen.getByLabelText('End (UTC)'), { target: { value: '2027-01-02T11:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create booking' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That slot is already held');
  });

  it('logout drops private booking state and loads an anonymous CSRF token', async () => {
    let logged = true;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/logout') { logged = false; return ok(null, 204); }
      if (url === '/api/session') return ok(logged ? requester : anonymous);
      return ok([saved]);
    }));
    render(<BookingPanel resources={resources} selectedId="" />);
    expect(await screen.findByText('PENDING', { exact: true })).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByLabelText('Username')).toBeVisible();
    await waitFor(() => expect(screen.queryByText('PENDING', { exact: true })).not.toBeInTheDocument());
  });
});
