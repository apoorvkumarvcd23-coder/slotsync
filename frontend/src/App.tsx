import { useEffect, useState } from 'react';

export type Resource = {
  id: string;
  code: string;
  name: string;
  type: 'ROOM' | 'EQUIPMENT';
  location: string;
  capacity: number;
};

function isResource(value: unknown): value is Resource {
  if (value === null || typeof value !== 'object') return false;
  const row = value as Record<string, unknown>;
  return ['id', 'code', 'name', 'location'].every(key => typeof row[key] === 'string')
    && (row.type === 'ROOM' || row.type === 'EQUIPMENT')
    && typeof row.capacity === 'number' && Number.isInteger(row.capacity) && row.capacity > 0;
}

export default function App() {
  const [resources, setResources] = useState<Resource[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [filter, setFilter] = useState('ALL');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState('loading');
    fetch('/api/resources', { signal: controller.signal, headers: { Accept: 'application/json' } })
      .then(async response => {
        if (!response.ok) throw new Error('Resource service unavailable');
        const data: unknown = await response.json();
        if (!Array.isArray(data) || !data.every(isResource)) throw new Error('Invalid resource response');
        return data;
      })
      .then(data => {
        if (!controller.signal.aborted) {
          setResources(data);
          setState('ready');
        }
      })
      .catch(() => { if (!controller.signal.aborted) setState('error'); });
    return () => controller.abort();
  }, [attempt]);

  const visible = resources.filter(resource => filter === 'ALL' || resource.type === filter);
  return <>
    <header className="masthead">
      <a className="brand" href="/" aria-label="SlotSync home"><span className="brand-icon" aria-hidden="true">S</span>SlotSync</a>
      <span className="workspace">Demo campus <span aria-hidden="true">↗</span></span>
    </header>
    <main>
      <section className="intro" aria-labelledby="page-title">
        <p className="eyebrow">CAMPUS RESOURCE CATALOGUE</p>
        <h1 id="page-title">Space for your<br /><span>next good idea.</span></h1>
        <p className="description">Find a room to bring people together, or the equipment to make it happen.</p>
        <div className="milestone"><span className="status-dot" aria-hidden="true" />Catalogue preview · Booking opens in a later build</div>
      </section>
      <section className="catalogue" aria-labelledby="resources-title">
        <div className="catalogue-heading">
          <div><p className="eyebrow">EXPLORE THE CAMPUS</p><h2 id="resources-title">Resources <span className="count">{state === 'ready' ? visible.length : '—'}</span></h2></div>
          <label className="filter">Show
            <select value={filter} onChange={event => setFilter(event.target.value)} aria-label="Resource type">
              <option value="ALL">All resources</option><option value="ROOM">Rooms</option><option value="EQUIPMENT">Equipment</option>
            </select>
          </label>
        </div>
        {state === 'loading' && <p role="status" className="notice">Loading campus resources…</p>}
        {state === 'error' && <div role="alert" className="notice error"><h3>Resources could not be loaded</h3><p>Please check the service and try again.</p><button onClick={() => setAttempt(value => value + 1)}>Try again</button></div>}
        {state === 'ready' && visible.length === 0 && <p role="status" className="notice">No resources match this view.</p>}
        {state === 'ready' && <div className="resource-grid">{visible.map(resource => <article className="resource-card" key={resource.id}>
          <div className={`resource-art ${resource.type.toLowerCase()}`} aria-hidden="true"><span>{resource.type === 'ROOM' ? '▦' : '◈'}</span><span className="art-code">{resource.code}</span></div>
          <div className="card-body"><span className="type-label">{resource.type === 'ROOM' ? 'ROOM' : 'EQUIPMENT'}</span>
            <h3>{resource.name}</h3><p className="location">{resource.location}</p>
            <div className="card-footer"><span>{resource.type === 'ROOM' ? `${resource.capacity} people` : `${resource.capacity} kit`}</span><span className="catalogue-tag">Catalogue only</span></div>
          </div>
        </article>)}</div>}
      </section>
      <footer><span>SlotSync / A place for shared resources.</span><span>Synthetic demonstration data</span></footer>
    </main>
  </>;
}
