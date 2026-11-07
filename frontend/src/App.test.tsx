import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import App from './App';

const resources = [
  { id: '1', code: 'ROOM-ATLAS', name: 'Atlas meeting room', type: 'ROOM', location: 'Demo / North', capacity: 8 },
  { id: '2', code: 'KIT-PROJECTOR', name: 'Portable projector kit', type: 'EQUIPMENT', location: 'Demo / Desk', capacity: 1 },
];
const response = (data: unknown) => ({ ok: true, json: async () => data });

describe('resource catalogue', () => {
  it('loads data, filters rooms and clearly labels the unfinished booking workflow', async () => {
    const fetch = vi.fn().mockResolvedValue(response(resources));
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    expect(await screen.findByText('Atlas meeting room')).toBeVisible();
    expect(screen.getByText('Portable projector kit')).toBeVisible();
    expect(screen.getByText(/Booking opens in a later build/)).toBeVisible();
    expect(fetch).toHaveBeenCalledWith('/api/resources', expect.objectContaining({ signal: expect.any(AbortSignal) }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Resource type' }), { target: { value: 'ROOM' } });
    expect(screen.queryByText('Portable projector kit')).not.toBeInTheDocument();
    expect(screen.getByText('8 people')).toBeVisible();
  });

  it('shows an empty result without inventing resources', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([])));
    render(<App />);
    expect(await screen.findByText('No resources match this view.')).toBeVisible();
  });

  it('offers a retry after an HTTP error and renders recovered data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ ok: false }).mockResolvedValueOnce(response(resources)));
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Resources could not be loaded');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Atlas meeting room')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('aborts its request on unmount', async () => {
    const fetch = vi.fn().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetch);
    const view = render(<App />);
    view.unmount();
    await waitFor(() => expect(fetch.mock.calls[0][1].signal.aborted).toBe(true));
  });

  it('handles an invalid response as an error instead of crashing or showing invented data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ error: 'unexpected' })));
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Resources could not be loaded');
    expect(screen.queryByText('Atlas meeting room')).not.toBeInTheDocument();
  });
});
