// SPDX-License-Identifier: Apache-2.0
// The node list and the live node detail with the jobs the node runs.
import { ApiError, getBody } from './api.js';
import { Badge, dateTime, duration, jobHref, plural, shortId, toHash } from './format.js';
import { html, useEffect, useState } from './html.js';
import { DetailHead } from './jobs.js';

const POLL_MS = 3000;
const RETRY_MS = 10000;

const nodeState = (node) => (node.alive ? 'ONLINE' : 'OFFLINE');

/**
 * Calls load every 3 seconds while the tab is visible, and right away when it becomes visible
 * again; load returns false to stop. onError gets a failure and returns false to stop too.
 */
function usePolling(load, onError, deps) {
  useEffect(() => {
    let timer = null;
    let stopped = false;
    let busy = false;
    const schedule = (ms) => {
      clearTimeout(timer);
      timer = setTimeout(tick, ms);
    };
    async function tick() {
      if (stopped || busy || document.hidden) {
        return;
      }
      busy = true;
      try {
        const more = await load(() => stopped);
        if (!stopped && more !== false) schedule(POLL_MS);
      } catch (e) {
        if (!stopped && onError(e) !== false) schedule(RETRY_MS);
      } finally {
        busy = false;
      }
    }
    const onVisibility = () => {
      if (!document.hidden) tick();
    };
    document.addEventListener('visibilitychange', onVisibility);
    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, deps);
}

export function NodesView({ route, navigate, reloadKey }) {
  const [nodes, setNodes] = useState(null);
  const [error, setError] = useState(null);
  const [now, setNow] = useState(() => new Date().toISOString());

  usePolling(
    async (stopped) => {
      const body = await getBody('api/nodes');
      if (stopped()) return false;
      setNodes(body.nodes);
      setNow(new Date().toISOString());
      setError(null);
      return true;
    },
    (e) => setError(e.message),
    [reloadKey],
  );

  const href = (nodeId) => toHash({ section: 'nodes', id: nodeId });
  const open = (nodeId) => navigate({ section: 'nodes', id: nodeId });
  return html`
    <div class="view-head">
      <h1>Nodes</h1>
      <span class="muted">Registered nodes, by name</span>
    </div>
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    <div class="split">
      <section class="panel list" aria-label="Nodes">
        <div class="table-scroll">
          <table class="table">
            <thead>
              <tr><th>Node</th><th>State</th><th>Started</th><th>Last heartbeat</th><th>Running jobs</th></tr>
            </thead>
            <tbody>
              ${(nodes || []).map(
                (node) => html`
                  <tr key=${node.id} class=${node.id === route.id ? 'selected' : ''} onClick=${() => open(node.id)}>
                    <td>
                      <a class="mono" href=${href(node.id)}>${node.name}</a>
                      <div class="sub">${shortId(node.id)}</div>
                    </td>
                    <td><${Badge} state=${nodeState(node)} /></td>
                    <td class="mono muted">${dateTime(node.startedAt)}</td>
                    <td class=${node.alive ? 'mono muted' : 'mono offline-text'} title=${`${duration(node.lastHeartbeatAt, now)} ago`}>
                      ${dateTime(node.lastHeartbeatAt)}
                    </td>
                    <td class="mono">${node.runningJobs}</td>
                  </tr>`,
              )}
            </tbody>
          </table>
          ${nodes && nodes.length === 0 && html`<div class="empty">No nodes registered.</div>`}
        </div>
        <div class="panel-foot">
          <span>${nodes ? plural(nodes.length, 'node', 'nodes') + ' registered' : 'Loading…'}</span>
        </div>
      </section>
      ${route.id &&
      html`<${NodeDetail} key=${route.id} nodeId=${route.id} onClose=${() => navigate({ section: 'nodes' })} />`}
    </div>`;
}

/** One node with the jobs it runs, polled every 3 seconds while it is open and the tab is visible. */
function NodeDetail({ nodeId, onClose }) {
  const [data, setData] = useState(null);
  const [live, setLive] = useState('loading');
  const [missing, setMissing] = useState(false);
  const [now, setNow] = useState(() => new Date().toISOString());

  usePolling(
    async (stopped) => {
      const [nodeBody, jobsBody] = await Promise.all([
        getBody(`api/nodes/${nodeId}`),
        getBody(`api/jobs?node=${encodeURIComponent(nodeId)}&limit=100`),
      ]);
      if (stopped()) return false;
      setData({ node: nodeBody.node, jobs: jobsBody.jobs });
      setNow(new Date().toISOString());
      setLive('live');
      return true;
    },
    (e) => {
      if (e instanceof ApiError && e.status === 404) {
        setMissing(true);
        return false;
      }
      setLive('lost');
      return true;
    },
    [nodeId],
  );

  if (missing) {
    return html`<aside class="panel detail" aria-label="Node details">
      <${DetailHead} title="Node not found" plain=${true} onClose=${onClose} />
      <p class="detail-body muted">Node ${nodeId} is not registered (anymore): other nodes remove a node after twice the node timeout without heartbeat.</p>
    </aside>`;
  }
  if (!data) {
    return html`<aside class="panel detail" aria-label="Node details">
      <${DetailHead} title="Loading…" plain=${true} onClose=${onClose} />
    </aside>`;
  }

  const { node, jobs } = data;
  const silent = duration(node.lastHeartbeatAt, now);
  const fields = [
    ['Last heartbeat', `${dateTime(node.lastHeartbeatAt)} · ${silent} ago`],
    ['Started', node.alive ? `${dateTime(node.startedAt)} · up ${duration(node.startedAt, now)}` : dateTime(node.startedAt)],
  ];
  const liveText = { loading: 'Loading…', live: 'Live', lost: 'Connection lost, retrying' }[live];
  return html`
    <aside class="panel detail" aria-label="Node details">
      <${DetailHead} title=${node.name} state=${nodeState(node)} id=${node.id} onClose=${onClose}>
        <span role="status" class=${`live live-${live}`}><span class="live-dot"></span>${liveText}</span>
      <//>
      ${!node.alive &&
      html`<div class="notice notice-error" role="status">
        No heartbeat for ${silent}. Other nodes release its running jobs for a retry and remove the node
        after twice the node timeout.
      </div>`}
      <dl class="meta">
        ${fields.map(([label, value]) => html`<div><dt>${label}</dt><dd class="mono" title=${value}>${value}</dd></div>`)}
      </dl>
      <div class="detail-body">
        <h3 class="section-label">Running jobs (${jobs.length})</h3>
        ${jobs.length === 0
          ? html`<p class="muted">This node runs no job right now.</p>`
          : html`<ul class="links node-jobs">
              ${jobs.map(
                (job) => html`<li class="row">
                  <a class="mono" href=${jobHref(job.id)}>${shortId(job.id)}</a>
                  <span class="mono">${job.type}</span>
                  <${Badge} state=${job.state} />
                  <span class="muted small mono">since ${dateTime(job.updatedAt)}</span>
                </li>`,
              )}
            </ul>`}
      </div>
    </aside>`;
}
