import { useEffect, useRef, useState } from 'react';
import type { Resource } from './App';
import OperationsPanel from './OperationsPanel';

type Session = { username: string | null; role: string | null; csrfToken: string; csrfHeader: string };
type Booking = { id: string; resourceId: string; resourceName: string; startsAt: string; endsAt: string; status: string };
const initialStart = () => {
  const value = new Date(Date.now() + 86400000);
  value.setUTCHours(10, 0, 0, 0);
  return value.toISOString().slice(0, 16);
};
const showTime = (value: string) => new Date(value).toLocaleString('en-GB', { timeZone: 'UTC', hour12: false }) + ' UTC';

export default function BookingPanel({ resources, selectedId, onResourcesChanged = () => {} }: { resources: Resource[]; selectedId: string; onResourcesChanged?: () => void }) {
  const [session, setSession] = useState<Session | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [resourceId, setResourceId] = useState(selectedId || resources[0]?.id || '');
  const [start, setStart] = useState(initialStart);
  const [end, setEnd] = useState(() => new Date(new Date(start + 'Z').getTime() + 3600000).toISOString().slice(0, 16));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const request = useRef<{ payload: string; key: string } | null>(null);

  useEffect(() => { if (selectedId) setResourceId(selectedId); }, [selectedId]);

  async function loadBookings() {
    const result = await fetch('/api/bookings', { credentials: 'same-origin' });
    if (!result.ok) throw new Error('Bookings could not be loaded. Sign in again if your session expired.');
    setBookings(await result.json() as Booking[]);
  }

  async function loadSession() {
    const response = await fetch('/api/session', { credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) throw new Error('Session service is unavailable.');
    const current = await response.json() as Session;
    if (typeof current.csrfToken !== 'string' || typeof current.csrfHeader !== 'string') throw new Error('Invalid session response.');
    setSession(current);
    if (current.role === 'REQUESTER') await loadBookings();
    return current;
  }

  useEffect(() => { void loadSession().catch(() => setError('Session service is unavailable. Reopen bookings to retry.')); }, []);

  async function mutate(path: string, body?: string, extraHeaders?: Record<string, string>, form = false) {
    if (!session) throw new Error('Load your session first.');
    let response: Response;
    try {
      response = await fetch(path, {
        method: 'POST', credentials: 'same-origin', body,
        headers: { [session.csrfHeader]: session.csrfToken,
          ...(body ? { 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json' } : {}), ...extraHeaders },
      });
    } catch {
      throw new Error(path === '/api/bookings'
        ? 'Connection lost. Retry the unchanged booking form safely.'
        : 'Connection lost. Check the service and try again.');
    }
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as { code?: string; message?: string };
      const message = problem.code === 'LOGIN_FAILED' ? 'Username or password is incorrect.'
        : problem.code === 'SLOT_CONFLICT' ? 'That slot is already held. Choose another time or resource.'
        : problem.code === 'ACCESS_DENIED' ? 'Your session or permissions changed. Sign out and sign in again.'
        : problem.message || 'Request could not be confirmed. You can safely retry the same booking form.';
      throw new Error(message);
    }
    return response;
  }

  async function login(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      await mutate('/api/login', new URLSearchParams({ username, password }).toString(), undefined, true);
      await loadSession(); // Authentication rotates the CSRF token.
    } catch (failure) { setError((failure as Error).message); }
    finally { setPassword(''); setBusy(false); }
  }

  async function logout() {
    setBusy(true); setError(''); setNotice('');
    try {
      await mutate('/api/logout');
      setBookings([]); setSession(null); request.current = null;
      await loadSession();
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }

  async function book(event: React.FormEvent) {
    event.preventDefault(); setError(''); setNotice('');
    if (!resourceId || !start || !end || new Date(start + 'Z') >= new Date(end + 'Z')) {
      setError('Select a resource and an end time after the start.'); return;
    }
    const payload = JSON.stringify({ resourceId, startsAt: new Date(start + 'Z').toISOString(), endsAt: new Date(end + 'Z').toISOString() });
    if (!request.current || request.current.payload !== payload) request.current = { payload, key: crypto.randomUUID() };
    setBusy(true);
    try {
      const response = await mutate('/api/bookings', payload, { 'Idempotency-Key': request.current.key });
      const saved = await response.json() as Booking;
      setBookings(previous => [saved, ...previous.filter(booking => booking.id !== saved.id)]);
      request.current = null;
      setNotice(saved.status === 'APPROVED' ? 'Booking confirmed by the current policy.' : 'Booking submitted. Pending bookings hold the slot until a decision or cancellation.');
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }

  async function cancel(id: string) {
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await mutate('/api/bookings/' + id + '/cancel');
      const cancelled = await response.json() as Booking;
      setBookings(previous => previous.map(booking => booking.id === id ? cancelled : booking));
      setNotice('Booking cancelled. The slot is available for a new request.');
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }

  return <section className="booking-workspace" aria-label="Bookings workspace">
    <p className="eyebrow">YOUR CAMPUS WORKSPACE</p>
    {error && <p role="alert" className="notice error">{error}</p>}
    {notice && <p role="status" className="notice">{notice}</p>}
    {!session && !error && <p role="status">Loading your session…</p>}
    {session && !session.username && <form onSubmit={login} className="login-form">
      <h2>Sign in to SlotSync</h2>
      <p>Use a generated demo account from the local setup. Passwords are kept outside the repository.</p>
      <label>Username<input autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} required maxLength={100} /></label>
      <label>Password<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required /></label>
      <button disabled={busy} type="submit">Sign in</button>
    </form>}
    {session?.username && <>
      <div className="signed-in"><span>Signed in as <strong>{session.username}</strong> · {session.role}</span><button onClick={logout} disabled={busy}>Sign out</button></div>
      {session.role !== 'REQUESTER' ? <OperationsPanel key={session.username} role={session.role || ''} mutate={mutate} onResourcesChanged={onResourcesChanged} /> : <>
        <form onSubmit={book} className="booking-form" aria-label="Create booking">
          <h2>Reserve a resource</h2>
          <p>Enter both times in UTC. The current policy sets the maximum duration and whether approval is required. Pending and approved bookings hold the slot.</p>
          <label>Resource<select aria-label="Booking resource" value={resourceId} onChange={e => setResourceId(e.target.value)} required>
            <option value="">Choose a resource</option>{resources.map(resource => <option key={resource.id} value={resource.id}>{resource.name}</option>)}
          </select></label>
          <div className="time-fields">
            <label>Start (UTC)<input type="datetime-local" value={start} onChange={e => setStart(e.target.value)} required /></label>
            <label>End (UTC)<input type="datetime-local" value={end} onChange={e => setEnd(e.target.value)} required /></label>
          </div>
          <button type="submit" disabled={busy}>Create booking</button>
        </form>
        <div className="own-bookings" aria-label="Your bookings">
          <div className="catalogue-heading"><h2>My bookings</h2><button disabled={busy} onClick={() => void loadBookings().catch(f => setError((f as Error).message))}>Refresh bookings</button></div>
          {bookings.length === 0 && <p>No bookings yet.</p>}
          {bookings.map(booking => <article className="booking-row" key={booking.id}>
            <div><h3>{booking.resourceName}</h3><p>{showTime(booking.startsAt)} — {showTime(booking.endsAt)}</p><strong className="type-label">{booking.status}</strong></div>
            {['PENDING', 'APPROVED'].includes(booking.status) && <button disabled={busy} onClick={() => cancel(booking.id)}>Cancel booking</button>}
          </article>)}
        </div>
      </>}
    </>}
  </section>;
}
