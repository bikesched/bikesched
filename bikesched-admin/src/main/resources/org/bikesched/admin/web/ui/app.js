// SPDX-License-Identifier: Apache-2.0
// The page: header with the Jobs and Events tabs and the nodes, and the view of the current hash
// route.
import { getBody } from './api.js';
import { EventsView } from './events.js';
import { ago, parseHash, plural, toHash } from './format.js';
import { html, render, useEffect, useRef, useState } from './html.js';
import { JobsView } from './jobs.js';

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

function NodesSummary({ nodes }) {
  const dead = nodes.filter((node) => !node.alive).length;
  return html`
    <span class=${`nodes-summary ${dead ? 'dead' : 'alive'}`} title=${nodes.map((node) => `${node.name}: ${node.alive ? 'alive' : 'dead'}`).join('\n')}>
      <span class="node-dot" aria-hidden="true"></span>
      ${dead ? `${dead} of ${plural(nodes.length, 'node', 'nodes')} dead` : plural(nodes.length, 'node', 'nodes')}
    </span>`;
}

function Header({ me, nodes, tabs, onReload }) {
  const now = useNow(5000);
  const role = me?.level === 'OPERATE' ? 'operator' : 'viewer';
  return html`
    <header class="topbar">
      <a class="brand" href="#/jobs"><img class="brand-logo" src="logo.svg" alt="" width="32" height="32" /><span class="brand-name">bikesched</span><span class="muted">admin</span></a>
      <nav class="sections" aria-label="Sections">
        ${tabs.map(
          (tab) => html`
            <a class="section-tab" href=${tab.href} aria-current=${tab.current ? 'page' : undefined}>
              ${tab.label}<span class="count">${tab.total}</span>
            </a>`,
        )}
      </nav>
      <div class="topbar-nodes">
        <span class="nodes-label muted small">Nodes</span>
        ${nodes.map(
          (node) => html`
            <span class=${`node ${node.alive ? 'alive' : 'dead'}`} title=${`${node.name}: ${node.alive ? 'alive' : 'no heartbeat within the node timeout'}`}>
              <span class="node-dot" aria-hidden="true"></span>
              <span class="mono">${node.name}</span>
              <span class="muted">${node.alive ? ago(node.lastHeartbeatAt, now) : 'dead'}</span>
            </span>`,
        )}
        ${nodes.length > 0 && html`<${NodesSummary} nodes=${nodes} />`}
        ${nodes.length === 0 && html`<span class="muted small">No nodes registered</span>`}
        ${me?.user && html`<span class="small muted">Signed in as <strong>${me.user}</strong> (${role})</span>`}
        ${me && !me.user && me.level !== 'OPERATE' && html`<span class="small muted">Read only</span>`}
      </div>
      <button type="button" class="button reload" onClick=${onReload}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M21 12a9 9 0 1 1-2.64-6.36"></path><path d="M21 3v6h-6"></path>
        </svg>
        Reload
      </button>
    </header>`;
}

function App() {
  const [route, setRoute] = useState(parseHash());
  // The last route of each section, so its tab returns to the list as it was left.
  const lastRoutes = useRef({ jobs: { section: 'jobs' }, events: { section: 'events' } });
  lastRoutes.current[route.section] = route;
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
  // The current section's tab clears its filters, the other one returns to where it was left.
  const tab = (section, label, total) => ({
    label,
    total,
    current: route.section === section,
    href: toHash(route.section === section ? { section } : lastRoutes.current[section]),
  });
  const tabs = [
    tab('jobs', 'Jobs', types.reduce((sum, type) => sum + type.total, 0)),
    tab('events', 'Events', names.reduce((sum, name) => sum + name.count, 0)),
  ];

  return html`
    <${Header} me=${me} nodes=${nodes} tabs=${tabs} onReload=${reload} />
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    <main class="content">
      ${jobs
        ? html`<${JobsView} route=${route} navigate=${navigate} types=${types} me=${me} nodes=${nodes}
            reloadKey=${reloadKey} onChanged=${() => setCountsKey((key) => key + 1)} />`
        : html`<${EventsView} route=${route} navigate=${navigate} names=${names} me=${me}
            reloadKey=${reloadKey} onPublished=${reload} />`}
    </main>`;
}

render(html`<${App} />`, document.getElementById('app'));
