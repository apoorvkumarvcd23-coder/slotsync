import { useEffect, useState } from 'react';

type Mutate = (path: string, body?: string) => Promise<Response>;
type Policy = { version: number; maxDurationMinutes: number; approvalRequired: boolean };
type Batch = { id: string; valid: boolean; applied: boolean; inserted: number; replayed: boolean;
  preview: { inputRows: number; acceptedRows: number; rejectedRows: number;
    rows: { row: number; code: string; name: string; type: string; location: string; capacity: number }[];
    errors: { row: number; message: string }[] } };
type Audit = { id: string; actor: string; action: string; entityId: string; details: string; createdAt: string };
type Approval = { owner: string; policyVersion: number;
  booking: { id: string; resourceName: string; startsAt: string; endsAt: string } };

async function read<T>(path: string): Promise<T> {
  const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error('Workspace could not be loaded. Refresh or sign in again.');
  return response.json() as Promise<T>;
}
const time = (value: string) => new Date(value).toLocaleString('en-GB', { timeZone: 'UTC', hour12: false }) + ' UTC';
function describeChange(value: string): string {
  try {
    const detail = JSON.parse(value) as { from?: string | null; to?: string; previousVersion?: number;
      policy?: Policy; inputRows?: number; inserted?: number };
    if (detail.policy) return 'Policy v' + detail.previousVersion + ' → v' + detail.policy.version
      + ' · maximum ' + detail.policy.maxDurationMinutes + ' minutes · approval ' + (detail.policy.approvalRequired ? 'required' : 'automatic');
    if (typeof detail.to === 'string') return (detail.from || 'New booking') + ' → ' + detail.to;
    if (typeof detail.inserted === 'number') return detail.inserted + ' of ' + detail.inputRows + ' resources imported';
  } catch { /* Unknown historical details stay opaque rather than rendering raw payloads. */ }
  return 'Change recorded';
}

export default function OperationsPanel({ role, mutate, onResourcesChanged }: { role: string; mutate: Mutate; onResourcesChanged: () => void }) {
  return role === 'APPROVER' ? <Approvals mutate={mutate} /> : role === 'ADMIN'
    ? <Admin mutate={mutate} onResourcesChanged={onResourcesChanged} /> : null;
}

function Approvals({ mutate }: { mutate: Mutate }) {
  const [queue, setQueue] = useState<Approval[]>([]);
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  async function refresh() { setQueue(await read<Approval[]>('/api/approvals')); setLoaded(true); }
  useEffect(() => { void refresh().catch(f => setError((f as Error).message)); }, []);
  async function decide(id: string, decision: 'APPROVED' | 'REJECTED') {
    setBusy(true); setError(''); setNotice('');
    try { await mutate('/api/approvals/' + id + '/decision', JSON.stringify({ decision })); await refresh(); setNotice('Decision saved: ' + decision); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  return <section aria-label="Approval workspace">
    <div className="catalogue-heading"><h2>Pending approvals</h2><button disabled={busy} onClick={() => void refresh().catch(f => setError((f as Error).message))}>Refresh queue</button></div>
    <p>Pending requests hold their slot. You cannot decide your own booking. Showing up to 100 requests.</p>
    {error && <p role="alert" className="notice error">{error}</p>}
    {notice && <p role="status" className="notice">{notice}</p>}
    {!loaded && !error && <p role="status">Loading approval queue…</p>}
    {loaded && queue.length === 0 && <p>No pending requests.</p>}
    {queue.map(item => <article className="booking-row" key={item.booking.id}>
      <div><h3>{item.booking.resourceName}</h3><p>{item.owner} · policy v{item.policyVersion}</p><p>{time(item.booking.startsAt)} — {time(item.booking.endsAt)}</p></div>
      <div className="actions"><button disabled={busy} onClick={() => void decide(item.booking.id, 'APPROVED')}>Approve</button>
        <button disabled={busy} onClick={() => void decide(item.booking.id, 'REJECTED')}>Reject</button></div>
    </article>)}
  </section>;
}

function Admin({ mutate, onResourcesChanged }: { mutate: Mutate; onResourcesChanged: () => void }) {
  const [policy, setPolicy] = useState<Policy | null>(null);
  const [minutes, setMinutes] = useState('1440');
  const [approval, setApproval] = useState(true);
  const [csv, setCsv] = useState('code,name,type,location,capacity\nROOM-CEDAR,Cedar studio,ROOM,Demo campus / East wing,12');
  const [batch, setBatch] = useState<Batch | null>(null);
  const [recent, setRecent] = useState<Batch[]>([]);
  const [audit, setAudit] = useState<Audit[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  async function refresh() {
    const [p, imports, events] = await Promise.all([read<Policy>('/api/admin/policy'), read<Batch[]>('/api/admin/imports'), read<Audit[]>('/api/admin/audit')]);
    setPolicy(p); setMinutes(String(p.maxDurationMinutes)); setApproval(p.approvalRequired); setRecent(imports); setAudit(events);
  }
  useEffect(() => { void refresh().catch(f => setError((f as Error).message)); }, []);
  async function act(work: () => Promise<void>) {
    setBusy(true); setError(''); setNotice('');
    try { await work(); } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function save(event: React.FormEvent) {
    event.preventDefault();
    await act(async () => {
      if (!policy || !/^[1-9][0-9]{0,3}$/.test(minutes) || Number(minutes) > 1440) throw new Error('Enter a maximum duration from 1 to 1440 minutes.');
      const p = await (await mutate('/api/admin/policy', JSON.stringify({ expectedVersion: policy.version, maxDurationMinutes: Number(minutes), approvalRequired: approval }))).json() as Policy;
      await refresh(); setNotice('Policy v' + p.version + ' saved. Existing bookings keep their original policy.');
    });
  }
  async function preview(event: React.FormEvent) {
    event.preventDefault(); setBatch(null);
    await act(async () => {
      const value = await (await mutate('/api/admin/imports/preview', JSON.stringify({ csv }))).json() as Batch;
      setBatch(value); setRecent(await read<Batch[]>('/api/admin/imports'));
    });
  }
  async function apply() {
    await act(async () => {
      if (!batch) return;
      const value = await (await mutate('/api/admin/imports/' + batch.id + '/apply')).json() as Batch;
      setBatch(value); await refresh(); onResourcesChanged();
      setNotice(value.replayed ? 'Import already applied; no duplicate resources created.' : 'Import applied: ' + value.inserted + ' resources inserted.');
    });
  }
  return <section aria-label="Administration workspace">
    <div className="catalogue-heading"><h2>Administration</h2><button disabled={busy} onClick={() => void act(refresh)}>Refresh workspace</button></div>
    {error && <p role="alert" className="notice error">{error}</p>}
    {notice && <p role="status" className="notice">{notice}</p>}
    {!policy && !error && <p role="status">Loading administration…</p>}
    <form onSubmit={save} aria-label="Booking policy" className="admin-block">
      <h3>Booking policy {policy && <span>v{policy.version}</span>}</h3>
      <p>Applies to new requests. Existing bookings keep their saved policy version. Booking horizon remains one year.</p>
      <label>Maximum duration (minutes)<input type="number" min="1" max="1440" step="1" required value={minutes} onChange={e => setMinutes(e.target.value)} /></label>
      <label className="check-label"><input type="checkbox" checked={approval} onChange={e => setApproval(e.target.checked)} />Require approval</label>
      <button disabled={busy || !policy} type="submit">Save policy</button>
    </form>
    <form onSubmit={preview} aria-label="Resource CSV import" className="admin-block">
      <h3>Import resources</h3>
      <p>Create-only CSV, at most 100 rows / 64 KiB. Fixed columns: code,name,type,location,capacity. Types: ROOM or EQUIPMENT. Duplicate codes reject the whole batch. Quoted commas are supported; multiline cells are not.</p>
      <label htmlFor="resource-csv">Resource CSV</label><textarea id="resource-csv" rows={6} maxLength={65536} value={csv} onChange={e => { setCsv(e.target.value); setBatch(null); }} required />
      <button disabled={busy} type="submit">Preview CSV</button>
    </form>
    {batch && <section aria-label="Import preview" className="admin-block">
      <h3>Import preview</h3>
      <p>{batch.preview.inputRows} input · {batch.preview.acceptedRows} accepted · {batch.preview.rejectedRows} rejected</p>
      {batch.preview.errors.map(row => <p className="row-error" key={row.row}>Row {row.row}: {row.message}</p>)}
      <div className="table-scroll"><table><thead><tr><th>Row</th><th>Code</th><th>Name</th><th>Type</th><th>Location</th><th>Capacity</th></tr></thead>
        <tbody>{batch.preview.rows.map(row => <tr key={row.row}><td>{row.row}</td><td>{row.code}</td><td>{row.name}</td><td>{row.type}</td><td>{row.location}</td><td>{row.capacity}</td></tr>)}</tbody></table></div>
      <p>{batch.applied ? 'Applied: ' + batch.inserted + ' inserted. Reapplying is safe.' : batch.valid ? 'Nothing written yet. Confirm to atomically create all resources.' : 'Nothing imported. Fix every error and preview again.'}</p>
      <button disabled={busy || !batch.valid} onClick={() => void apply()}>{batch.applied ? 'Retry applied import' : 'Confirm import'}</button>
    </section>}
    <section className="admin-block" aria-label="Recent imports"><h3>Your recent import batches</h3>
      {recent.length === 0 && <p>No previews yet.</p>}
      {recent.map(item => <p key={item.id}><button disabled={busy} onClick={() => setBatch(item)}>Open batch {item.id.slice(0, 8)}</button> {item.applied ? item.inserted + ' inserted' : item.valid ? 'Ready to apply' : 'Has row errors'}</p>)}
    </section>
    <section className="admin-block" aria-label="Audit history"><h3>Audit history</h3><p>Latest 200 booking, decision, policy and import events.</p>
      <div className="table-scroll"><table><thead><tr><th>UTC time</th><th>Actor</th><th>Action</th><th>Entity</th><th>Change</th></tr></thead>
        <tbody>{audit.map(row => <tr key={row.id}><td>{time(row.createdAt)}</td><td>{row.actor}</td><td>{row.action}</td><td>{row.entityId}</td><td>{describeChange(row.details)}</td></tr>)}</tbody></table></div>
    </section>
  </section>;
}
