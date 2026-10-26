// SPDX-License-Identifier: Apache-2.0
// The history of every job, step, event and node, newest first: filters, a live list that re-reads
// its newest minute, and an entry's detail with the entries its subject caused.
import { getBody, query } from './api.js';
import {
  Badge,
  SearchChip,
  SortedTh,
  SubjectHistory,
  actor,
  historyDetail,
  jobHref,
  plural,
  preciseTime,
  pretty,
  shortId,
  stepHref,
  toHash,
} from './format.js';
import { html, useEffect, useRef, useState } from './html.js';
import { DetailHead } from './jobs.js';
import { usePolling } from './nodes.js';

const PAGE_SIZE = 50;
// Live refresh re-reads the minute before the newest entry shown: a long transaction commits its
// entries after newer ones were shown.
const WINDOW_MS = 60000;
const MAX_LIMIT = 500;

const ACTORS = [
  ['', 'Everyone'],
  ['USER', 'Users'],
  ['APPLICATION', 'Application'],
  ['JOB', 'Jobs'],
  ['NODE', 'Nodes'],
];

const SUBJECTS = [
  ['', 'All'],
  ['JOB', 'Jobs'],
  ['STEP', 'Steps'],
  ['EVENT', 'Events'],
  ['NODE', 'Nodes'],
];

const ACTIONS = {
  JOB: ['SCHEDULED', 'CLAIMED', 'SUCCEEDED', 'ATTEMPT_FAILED', 'FAILED', 'SUSPENDED', 'RESUMED', 'POSTPONED',
    'RETRIED', 'CANCEL_REQUESTED', 'CANCELLED', 'RELEASED', 'MOVED', 'DELETED', 'FAILING', 'FAILURE_HANDLER_FAILED',
    'COMPENSATING', 'COMPENSATED', 'COMPENSATION_FAILED'],
  STEP: ['STARTED', 'SUCCEEDED', 'FAILED', 'WAITING', 'RECEIVED', 'CHILD_STARTED', 'CHILD_ENDED', 'WOKE_UP',
    'TIMED_OUT', 'POSTPONED', 'FAILING', 'RESOLVED', 'PUBLISHED', 'CANCELLED', 'COMPENSATED', 'COMPENSATION_FAILED'],
  EVENT: ['PUBLISHED'],
  NODE: ['REGISTERED', 'STOPPED', 'REMOVED'],
};

const SUBJECT_LABELS = { JOB: 'job', EVENT: 'event', NODE: 'node' };

const kind = (entry) => (entry.stepId ? 'STEP' : entry.subjectType);

/** The page of a job, step, event or node. */
function subjectHref(type, id, stepId) {
  if (type === 'JOB') return stepId ? stepHref(id, stepId) : jobHref(id);
  return toHash({ section: type === 'EVENT' ? 'events' : 'nodes', id });
}

// The order of entries, newest first: their time to the microsecond, then their id.
const sortKey = (entry) => {
  const [seconds, fraction = ''] = entry.at.replace(/Z$/, '').split('.');
  return `${seconds}.${fraction.padEnd(9, '0')} ${entry.id}`;
};
const newestFirst = (a, b) => (sortKey(a) < sortKey(b) ? 1 : sortKey(a) > sortKey(b) ? -1 : 0);

/** The entries of a live re-read merged into the page by id; a re-read that overflowed replaces it. */
function merge(page, body) {
  if (body.next) {
    return body;
  }
  const shown = new Set(page.entries.map((entry) => entry.id));
  const added = body.entries.filter((entry) => !shown.has(entry.id));
  return {
    ...page,
    entries: added.length ? [...added, ...page.entries].sort(newestFirst) : page.entries,
    subjects: { ...page.subjects, ...body.subjects },
    recording: body.recording,
  };
}

// A datetime-local input's value in local time, and back to an ISO instant.
const pad = (n) => String(n).padStart(2, '0');
function localInput(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
const fromLocalInput = (value) => (value ? new Date(value).toISOString() : '');

export function HistoryView({ route, navigate, reloadKey }) {
  const params = route.params;
  const filters = {
    actor: params.actor || '',
    subject: params.subject || '',
    action: (params.subject && params.action) || '',
    until: params.until || '',
    causedBy: params.causedBy || '',
  };
  const selectedActions = filters.action ? filters.action.split(',') : [];
  const setFilters = (changes) => navigate({ section: 'history', id: route.id, params: { ...filters, ...changes } });
  const toggleAction = (action) => {
    const next = selectedActions.includes(action)
      ? selectedActions.filter((a) => a !== action)
      : [...selectedActions, action];
    setFilters({ action: next.join(',') });
  };
  const [untilInput, setUntilInput] = useState(localInput(filters.until));
  useEffect(() => setUntilInput(localInput(filters.until)), [filters.until]);

  return html`
    <div class="view-head">
      <h1>History</h1>
    </div>
    <div class="toolbar">
      <div class="field">
        <span class="field-label">Actor</span>
        <div class="chips" role="group" aria-label="Filter by actor">
          ${ACTORS.map(
            ([value, label]) => html`
              <button type="button" class="chip" aria-pressed=${filters.actor === value}
                onClick=${() => setFilters({ actor: value })}>${label}</button>`,
          )}
        </div>
      </div>
      <div class="field">
        <span class="field-label">Subject</span>
        <div class="chips" role="group" aria-label="Filter by subject">
          ${SUBJECTS.map(
            ([value, label]) => html`
              <button type="button" class="chip" aria-pressed=${filters.subject === value}
                onClick=${() => setFilters({ subject: value, action: '' })}>${label}</button>`,
          )}
        </div>
      </div>
      <form class="field" onSubmit=${(event) => {
        event.preventDefault();
        setFilters({ until: fromLocalInput(untilInput) });
      }}>
        <label class="field-label" for="history-until">Before</label>
        <div class="row">
          <input id="history-until" class="input mono" type="datetime-local" value=${untilInput}
            onInput=${(event) => setUntilInput(event.target.value)} />
          <button type="submit" class="button">Go</button>
          ${filters.until && html`<button type="button" class="button" onClick=${() => setFilters({ until: '' })}>Now</button>`}
        </div>
      </form>
    </div>
    ${filters.subject &&
    html`<div class="chips" role="group" aria-label="Filter by action">
      <button type="button" class="chip" aria-pressed=${selectedActions.length === 0} onClick=${() => setFilters({ action: '' })}>
        All actions
      </button>
      ${ACTIONS[filters.subject].map(
        (action) => html`
          <button type="button" class="chip mono" aria-pressed=${selectedActions.includes(action)}
            onClick=${() => toggleAction(action)}>${action}</button>`,
      )}
    </div>`}
    ${filters.causedBy &&
    html`<${SearchChip} kind="cause" value=${`caused by ${shortId(filters.causedBy)}`}
      onClear=${() => setFilters({ causedBy: '' })} note="Only entries this subject caused." />`}
    <${HistoryList} key=${JSON.stringify(filters)} filters=${filters} route=${route} navigate=${navigate}
      reloadKey=${reloadKey} />`;
}

function HistoryList({ filters, route, navigate, reloadKey }) {
  const [page, setPage] = useState(null);
  const [error, setError] = useState(null);
  const [live, setLive] = useState('loading');
  const [loadingMore, setLoadingMore] = useState(false);
  // The page as the polling reads it; set together with the state.
  const current = useRef(null);
  const update = (next) => {
    current.current = next;
    setPage(next);
  };
  // Live only for the newest entries: with an upper bound, nothing new shows up.
  const isLive = !filters.until;

  useEffect(() => update(null), [reloadKey]);

  usePolling(
    async (stopped) => {
      const shown = current.current;
      const newest = shown?.entries[0];
      const params = newest
        ? { ...filters, since: new Date(Date.parse(newest.at) - WINDOW_MS).toISOString(), limit: MAX_LIMIT }
        : { ...filters, limit: PAGE_SIZE };
      const body = await getBody(`api/history${query(params)}`);
      if (stopped()) return false;
      update(shown && newest ? merge(current.current, body) : body);
      setError(null);
      setLive(isLive ? 'live' : 'paused');
      return isLive;
    },
    (e) => {
      setError(e.message);
      setLive('lost');
      return true;
    },
    [reloadKey],
  );

  const loadMore = async () => {
    setLoadingMore(true);
    try {
      const body = await getBody(`api/history${query({ ...filters, after: page.next, limit: PAGE_SIZE })}`);
      const shown = new Set(current.current.entries.map((entry) => entry.id));
      update({
        ...current.current,
        entries: [...current.current.entries, ...body.entries.filter((entry) => !shown.has(entry.id))],
        next: body.next,
        subjects: { ...current.current.subjects, ...body.subjects },
      });
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoadingMore(false);
    }
  };

  const entries = page?.entries || [];
  const subjects = page?.subjects || {};
  const selected = route.id && entries.find((entry) => entry.id === route.id);
  const href = (id) => toHash({ section: 'history', id, params: filters });
  const liveText = { loading: 'Loading…', live: 'Live', paused: 'Not live: a time bound is set', lost: 'Connection lost, retrying' }[live];
  return html`
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    ${page && !page.recording &&
    html`<div class="banner notice-warning" role="status">This instance does not record the history (<code>history(…)</code> on its
      builder); the list shows what other nodes recorded.</div>`}
    <div class="split">
      <section class="panel list" aria-label="History">
        <div class="table-scroll">
          <table class="table">
            <thead>
              <tr>
                <${SortedTh} label="Time" descending=${true} title="Newest first" />
                <th>Subject</th><th>Action</th><th>State</th><th>Actor</th><th>Node</th><th>Detail</th>
              </tr>
            </thead>
            <tbody>
              ${entries.map(
                (entry) => html`
                  <tr key=${entry.id} class=${entry.id === route.id ? 'selected' : ''}
                    onClick=${() => navigate({ section: 'history', id: entry.id, params: filters })}>
                    <td class="mono muted"><a href=${href(entry.id)}>${preciseTime(entry.at)}</a></td>
                    <td><${Subject} entry=${entry} /></td>
                    <td class="history-action">${entry.action}</td>
                    <td>${entry.state && html`<${Badge} state=${entry.state} />`}</td>
                    <td><${ActorCell} entry=${entry} subjects=${subjects} /></td>
                    <td><${NodeCell} nodeId=${entry.nodeId} subjects=${subjects} /></td>
                    <td class="small history-detail" title=${historyDetail(entry)}>${historyDetail(entry)}</td>
                  </tr>`,
              )}
            </tbody>
          </table>
          ${page && entries.length === 0 &&
          html`<div class="empty">${page.next
            ? 'No matching entries among the scanned ones: load more to scan older entries.'
            : 'No entries match these filters.'}</div>`}
        </div>
        <div class="panel-foot">
          <span>${page ? `${plural(entries.length, 'entry', 'entries')} shown` : 'Loading…'}</span>
          <span role="status" class=${`live live-${live === 'paused' ? 'loading' : live}`}><span class="live-dot"></span>${liveText}</span>
          ${page?.next &&
          html`<button type="button" class="button" disabled=${loadingMore} onClick=${loadMore}>Older entries</button>`}
        </div>
      </section>
      ${route.id &&
      (selected
        ? html`<${HistoryDetail} key=${route.id} entry=${selected} subjects=${subjects} navigate=${navigate}
            onClose=${() => navigate({ section: 'history', params: filters })} />`
        : page &&
          html`<aside class="panel detail" aria-label="History entry">
            <${DetailHead} title="Entry not shown" plain=${true}
              onClose=${() => navigate({ section: 'history', params: filters })} />
            <p class="detail-body muted">Entry ${route.id} is not in the list: change the filters or load older
              entries.</p>
          </aside>`)}
    </div>`;
}

/** The subject of an entry: its type, its name linked to its page, the step's name below. */
function Subject({ entry }) {
  const name = entry.data?.subjectName || shortId(entry.subjectId);
  const step = entry.stepId && (entry.data?.stepName || shortId(entry.stepId));
  return html`
    <span class="tag">${kind(entry)}</span>${' '}<a class="mono" href=${subjectHref(entry.subjectType, entry.subjectId, entry.stepId)}
      onClick=${(event) => event.stopPropagation()}>${name}</a>
    <div class="sub">${step ? html`step ${step}` : shortId(entry.subjectId)}</div>`;
}

/** A user's name, the causing job's name for a job's entries, else "application" or "node". */
function ActorCell({ entry, subjects }) {
  const cause = entry.causedById && subjects[entry.causedById];
  if (entry.actorType === 'USER') {
    return html`<strong>${entry.actorName}</strong>`;
  }
  if (entry.actorType === 'JOB' && cause?.type === 'JOB') {
    return html`<span class="muted">job</span> <a class="mono" href=${jobHref(entry.causedById)}
      onClick=${(event) => event.stopPropagation()}>${cause.name || shortId(entry.causedById)}</a>`;
  }
  return html`<span class="muted">${actor(entry)}</span>`;
}

/** The node that wrote an entry, by name while it is known. */
function NodeCell({ nodeId, subjects }) {
  if (!nodeId) return html`<span class="muted">-</span>`;
  return html`<a class="mono" href=${toHash({ section: 'nodes', id: nodeId })}
    onClick=${(event) => event.stopPropagation()}>${subjects[nodeId]?.name || shortId(nodeId)}</a>`;
}

/** One entry with its fields and data, and the entries its subject caused. */
function HistoryDetail({ entry, subjects, navigate, onClose }) {
  const [caused, setCaused] = useState(null);
  const [error, setError] = useState(null);
  const subjectLabel = SUBJECT_LABELS[entry.subjectType];

  useEffect(() => {
    getBody(`api/history${query({ causedBy: entry.subjectId, limit: PAGE_SIZE })}`).then(
      (body) => setCaused(body),
      (e) => setError(e.message),
    );
  }, [entry.subjectId]);

  const cause = entry.causedById && subjects[entry.causedById];
  const node = entry.nodeId && subjects[entry.nodeId];
  const fields = [
    ['Time', html`<span class="mono">${preciseTime(entry.at)}</span>`],
    ['Subject', html`<span class="tag">${kind(entry)}</span> <a class="mono" title=${entry.subjectId}
      href=${subjectHref(entry.subjectType, entry.subjectId)}>${entry.data?.subjectName || ''} ${shortId(entry.subjectId)}</a>`],
    entry.stepId && ['Step', html`<a class="mono" href=${stepHref(entry.subjectId, entry.stepId)}>${entry.data?.stepName || entry.stepId}</a>`],
    ['Action', html`<span class="history-action">${entry.action}</span>`],
    entry.state && ['State after', html`<${Badge} state=${entry.state} />`],
    entry.attempt != null && ['Attempt', html`<span class="mono">${entry.attempt}</span>`],
    ['Actor', entry.actorType === 'USER' ? html`<strong>${entry.actorName}</strong> (user)` : actor(entry)],
    entry.nodeId && ['Node', html`<a class="mono" href=${toHash({ section: 'nodes', id: entry.nodeId })}>${node?.name || entry.nodeId}</a>`],
    entry.causedById && [
      'Caused by',
      cause
        ? html`${SUBJECT_LABELS[cause.type]} <a class="mono" href=${subjectHref(cause.type, entry.causedById)}>${cause.name || shortId(entry.causedById)}</a>`
        : html`<span class="mono">${entry.causedById}</span>`,
    ],
  ].filter(Boolean);
  const causedEntries = caused ? [...caused.entries].reverse() : [];
  return html`
    <aside class="panel detail" aria-label="History entry">
      <${DetailHead} title=${entry.data?.subjectName || shortId(entry.subjectId)} state=${entry.state} id=${entry.id}
        onClose=${onClose} />
      <dl class="meta">
        ${fields.map(([label, value]) => html`<div><dt>${label}</dt><dd>${value}</dd></div>`)}
      </dl>
      <div class="detail-body">
        ${historyDetail(entry) && html`<p class="small break">${historyDetail(entry)}</p>`}
        ${entry.data && html`<h3 class="section-label">Data</h3><pre class="json">${pretty(entry.data)}</pre>`}
        <h3 class="section-label">Caused by this ${subjectLabel}</h3>
        ${error && html`<p class="error-text">${error}</p>`}
        ${caused &&
        (causedEntries.length === 0
          ? html`<p class="muted small">Nothing recorded as caused by this ${subjectLabel}.</p>`
          : html`
              <${SubjectHistory} entries=${causedEntries} />
              ${caused.next &&
              html`<p class="small"><a href=${toHash({ section: 'history', params: { causedBy: entry.subjectId } })}>
                All entries this ${subjectLabel} caused</a></p>`}`)}
      </div>
    </aside>`;
}
