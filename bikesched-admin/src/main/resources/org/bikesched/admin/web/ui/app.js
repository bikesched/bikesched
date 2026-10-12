// SPDX-License-Identifier: Apache-2.0
// The page: header with the nodes, side bar with the job types and event names, and the view of
// the current hash route.
import { getBody } from './api.js';
import { EventsView } from './events.js';
import { ago, parseHash, plural, toHash } from './format.js';
import { html, render, useEffect, useState } from './html.js';
import { JobsView } from './jobs.js';
import { Picker } from './picker.js';

const navigate = (route) => {
  location.hash = toHash(route);
};

function useNow(intervalMs) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

function Header({ me, nodes, onReload }) {
  const now = useNow(5000);
  const role = me?.level === 'OPERATE' ? 'operator' : 'viewer';
  return html`
    <header class="topbar">
      <a class="brand" href="#/jobs"><img class="brand-logo" src="logo.svg" alt="" width="32" height="32" /><span class="brand-name">bikesched</span><span class="muted">admin</span></a>
      <div class="topbar-right">
        <span class="muted small">Nodes</span>
        ${nodes.map(
          (node) => html`
            <span class=${`node ${node.alive ? 'alive' : 'dead'}`} title=${`${node.name}: ${node.alive ? 'alive' : 'no heartbeat within the node timeout'}`}>
              <span class="node-dot" aria-hidden="true"></span>
              <span class="mono">${node.name}</span>
              <span class="muted">${node.alive ? ago(node.lastHeartbeatAt, now) : 'dead'}</span>
            </span>`,
        )}
        ${nodes.length === 0 && html`<span class="muted small">none registered</span>`}
        ${me?.user && html`<span class="small muted">Signed in as <strong>${me.user}</strong> (${role})</span>`}
        ${me && !me.user && me.level !== 'OPERATE' && html`<span class="small muted">Read only</span>`}
        <button type="button" class="button" onClick=${onReload}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M21 12a9 9 0 1 1-2.64-6.36"></path><path d="M21 3v6h-6"></path>
          </svg>
          Reload
        </button>
      </div>
    </header>`;
}

function Section({ title, total, current, onOpen, hint, children }) {
  return html`
    <div class="nav-section">
      <button type="button" class="nav-head" aria-current=${current ? 'page' : 'false'} onClick=${onOpen}>
        <span>${title}</span><span class="count">${total}</span>
      </button>
      ${children}
      ${hint && html`<span class="muted small">${hint}</span>`}
    </div>`;
}

function App() {
  const [route, setRoute] = useState(parseHash());
  const [reloadKey, setReloadKey] = useState(0);
  // Counts and nodes only: after a retry or cancel, the list keeps its rows until a reload.
  const [countsKey, setCountsKey] = useState(0);
  const [me, setMe] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [types, setTypes] = useState([]);
  const [names, setNames] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    const onHash = () => setRoute(parseHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    Promise.all([getBody('api/me'), getBody('api/nodes'), getBody('api/types'), getBody('api/event-names')]).then(
      ([meBody, nodesBody, typesBody, namesBody]) => {
        setMe(meBody);
        setNodes(nodesBody.nodes);
        setTypes(typesBody.types);
        setNames(namesBody.names);
        setError(null);
      },
      (e) => setError(e.message),
    );
  }, [reloadKey, countsKey]);

  const reload = () => setReloadKey((key) => key + 1);
  const jobs = route.section === 'jobs';
  const typeItems = types.map((type) => {
    const failed = (type.counts.FAILED || 0) + (type.counts.COMPENSATION_FAILED || 0);
    const item = { name: type.type, count: type.total, recurring: Boolean(type.recurrence) };
    if (failed > 0) return { ...item, sub: `${failed} failed`, subClass: 'sub-failed' };
    if (type.recurrence) return { ...item, sub: type.recurrence };
    return item;
  });
  const nameItems = names.map((name) => ({ name: name.name, count: name.count }));

  return html`
    <${Header} me=${me} nodes=${nodes} onReload=${reload} />
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    <div class="layout">
      <nav class="sidebar" aria-label="Browse">
        <${Section} title="Jobs" total=${types.reduce((sum, type) => sum + type.total, 0)} current=${jobs}
          onOpen=${() => navigate({ section: 'jobs', params: {} })}
          hint=${plural(types.length, 'job type', 'job types')}>
          <${Picker} label="Job type" allLabel="All job types" placeholder="Search job types"
            items=${typeItems} selected=${jobs ? route.params.type || null : null}
            onSelect=${(type) => navigate({ section: 'jobs', params: { type, state: jobs ? route.params.state : '' } })} />
        <//>
        <${Section} title="Events" total=${names.reduce((sum, name) => sum + name.count, 0)} current=${!jobs}
          onOpen=${() => navigate({ section: 'events', params: {} })}
          hint=${plural(names.length, 'event name', 'event names')}>
          <${Picker} label="Event name" allLabel="All event names" placeholder="Search event names"
            items=${nameItems} selected=${!jobs ? route.params.name || null : null}
            onSelect=${(name) => navigate({ section: 'events', params: { name } })} />
        <//>
      </nav>
      <main class="content">
        ${jobs
          ? html`<${JobsView} route=${route} navigate=${navigate} types=${types} me=${me} nodes=${nodes}
              reloadKey=${reloadKey} onChanged=${() => setCountsKey((key) => key + 1)} />`
          : html`<${EventsView} route=${route} navigate=${navigate} reloadKey=${reloadKey} />`}
      </main>
    </div>`;
}

render(html`<${App} />`, document.getElementById('app'));
