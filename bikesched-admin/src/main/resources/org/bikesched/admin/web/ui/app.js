// SPDX-License-Identifier: Apache-2.0
// The page: header with the Jobs, Events and Nodes tabs, and the view of the current hash route.
import { getBody } from './api.js';
import { EventsView } from './events.js';
import { parseHash, toHash } from './format.js';
import { html, render, useEffect, useRef, useState } from './html.js';
import { JobsView } from './jobs.js';
import { NodesView } from './nodes.js';

const navigate = (route) => {
  location.hash = toHash(route);
};

function Header({ me, tabs, onReload }) {
  const role = me?.level === 'OPERATE' ? 'operator' : 'viewer';
  return html`
    <header class="topbar">
      <a class="brand" href="#/jobs"><img class="brand-logo" src="logo.svg" alt="" width="32" height="32" /><span class="brand-name">bikesched</span><span class="muted">admin</span></a>
      <nav class="sections" aria-label="Sections">
        ${tabs.map(
          (tab) => html`
            <a class="section-tab" href=${tab.href} aria-current=${tab.current ? 'page' : undefined}>
              ${tab.label}<span class="count">${tab.total}</span>
              ${tab.offline > 0 &&
              html`<span class="offline-marker" title=${`${tab.offline} offline`}><span class="marker-dot" aria-hidden="true"></span>${tab.offline} offline</span>`}
            </a>`,
        )}
      </nav>
      <div class="topbar-user">
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
  const lastRoutes = useRef({ jobs: { section: 'jobs' }, events: { section: 'events' }, nodes: { section: 'nodes' } });
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
  // The current section's tab clears its filters, the other one returns to where it was left.
  const tab = (section, label, total, offline = 0) => ({
    label,
    total,
    offline,
    current: route.section === section,
    href: toHash(route.section === section ? { section } : lastRoutes.current[section]),
  });
  const tabs = [
    tab('jobs', 'Jobs', types.reduce((sum, type) => sum + type.total, 0)),
    tab('events', 'Events', names.reduce((sum, name) => sum + name.count, 0)),
    tab('nodes', 'Nodes', nodes.length, nodes.filter((node) => !node.alive).length),
  ];

  return html`
    <${Header} me=${me} tabs=${tabs} onReload=${reload} />
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    <main class="content">
      ${route.section === 'jobs' &&
      html`<${JobsView} route=${route} navigate=${navigate} types=${types} me=${me} nodes=${nodes}
        reloadKey=${reloadKey} onChanged=${() => setCountsKey((key) => key + 1)} />`}
      ${route.section === 'events' &&
      html`<${EventsView} route=${route} navigate=${navigate} names=${names} me=${me}
        reloadKey=${reloadKey} onPublished=${reload} />`}
      ${route.section === 'nodes' && html`<${NodesView} route=${route} navigate=${navigate} reloadKey=${reloadKey} />`}
    </main>`;
}

render(html`<${App} />`, document.getElementById('app'));
