// SPDX-License-Identifier: Apache-2.0
// The job list with its state filter, and the live job detail.
import { ApiError, getBody, getJson, post, query } from './api.js';
import {
  Badge,
  Dot,
  ENDED,
  RecurringIcon,
  ReferenceLinks,
  STATES,
  SearchChip,
  SearchField,
  SortedTh,
  dateTime,
  elapsed,
  firstLine,
  isFullId,
  jobHref,
  plural,
  preciseTime,
  pretty,
  shortId,
  toHash,
} from './format.js';
import { html, useEffect, useRef, useState } from './html.js';
import { Picker } from './picker.js';
import { ScheduleForm, takeScheduled } from './schedule.js';

const PAGE_SIZE = 50;
const POLL_MS = 3000;
const RETRY_MS = 10000;

// The route of the schedule form is #/jobs/new; these parameters only prefill it.
const SCHEDULE_PARAMS = ['schedName', 'from'];

// How a job was started (its type), as shown in its detail.
const JOB_TYPES = { SINGLE: 'Single', RECURRING: 'Recurring', CHILD: 'Child job' };
const listParams = (params) =>
  Object.fromEntries(Object.entries(params).filter(([name]) => !SCHEDULE_PARAMS.includes(name)));

export function JobsView({ route, navigate, jobNames, me, nodes, reloadKey, onChanged, onScheduled }) {
  const canSchedule = me?.level === 'OPERATE';
  // A search by id or reference (route.params.search) replaces the name and state filters.
  const search = route.params.search || '';
  const jobName = search ? '' : route.params.name || '';
  const state = search ? '' : route.params.state || '';
  const selected = state ? state.split(',') : [];
  const [jobs, setJobs] = useState([]);
  const [next, setNext] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = async (before) => {
    setLoading(true);
    try {
      const params = search ? { search, limit: PAGE_SIZE } : { name: jobName, state, before, limit: PAGE_SIZE };
      const body = await getBody(`api/jobs${query(params)}`);
      setJobs((previous) => (before ? [...previous, ...body.jobs] : body.jobs));
      setNext(body.next);
      setError(null);
      if (search && body.jobs.length === 1 && !route.id) {
        navigate({ ...route, id: body.jobs[0].id, stepId: null });
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setJobs([]);
    setNext(null);
    load();
  }, [search, jobName, state, reloadKey]);

  const counts = {};
  for (const summary of jobNames) {
    if (!jobName || summary.name === jobName) {
      for (const [name, count] of Object.entries(summary.counts)) {
        counts[name] = (counts[name] || 0) + count;
      }
    }
  }

  // Handled names without jobs are only offered by the schedule form.
  const listedNames = jobNames.filter((summary) => summary.total > 0);
  const nameItems = listedNames.map((summary) => {
    const failed = (summary.counts.FAILED || 0) + (summary.counts.COMPENSATION_FAILED || 0);
    const item = { name: summary.name, count: summary.total, recurring: Boolean(summary.recurrence) };
    if (failed > 0) return { ...item, sub: `${failed} failed`, subClass: 'sub-failed' };
    if (summary.recurrence) return { ...item, sub: summary.recurrence };
    return item;
  });

  const setStates = (states) =>
    navigate({ ...route, params: { ...route.params, state: STATES.filter((s) => states.includes(s)).join(',') } });
  const toggle = (s) => setStates(selected.includes(s) ? selected.filter((x) => x !== s) : [...selected, s]);
  const open = (jobId) => navigate({ ...route, id: jobId, stepId: null, params: listParams(route.params) });
  const href = (jobId) => toHash({ ...route, id: jobId, stepId: null, params: listParams(route.params) });
  const searchFor = (text) => navigate({ section: 'jobs', id: null, params: text ? { search: text } : {} });
  const empty = !search
    ? 'No jobs match these filters.'
    : isFullId(search)
      ? `No job has the id or reference ${search} (anymore).`
      : `No job id ends with ${search}, and no job has it as its reference.`;
  // The open job's live updates replace only its own row; the rest of the list stays as loaded.
  const updateRow = (job) => setJobs((list) => list.map((row) => (row.id === job.id ? job : row)));
  // Opens the form next to the list, keeping its filters.
  const schedule = (prefill) => navigate({ section: 'jobs', id: 'new', params: { ...listParams(route.params), ...prefill } });
  const closeForm = () => navigate({ ...route, id: null, params: listParams(route.params) });
  // A step's detail replaces its job's next to the list; closing it returns to the job.
  const stepRoute = (stepId) => ({ ...route, stepId, params: listParams(route.params) });

  return html`
    <div class="view-head">
      <h1>${search ? `Jobs matching ${search}` : jobName || 'All jobs'}</h1>
      <div class="row">
        ${canSchedule &&
        html`<button type="button" class="button primary" onClick=${() => schedule({ schedName: jobName })}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
            stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"></path></svg>
          Schedule job
        </button>`}
      </div>
    </div>
    <div class="toolbar">
      <div class="field picker-field">
        <div class="field-head">
          <span class="field-label">Job name</span>
          <span class="muted">${plural(listedNames.length, 'job name', 'job names')}</span>
        </div>
        <${Picker} label="Job name" allLabel="All job names" placeholder="Search job names"
          items=${nameItems} selected=${jobName || null}
          onSelect=${(next) => navigate({ section: 'jobs', params: { name: next, state } })} />
      </div>
      <${SearchField} kind="job" value=${search} onSearch=${searchFor} />
    </div>
    ${search
      ? html`<${SearchChip} kind="job" value=${search} onClear=${() => searchFor('')}
          note="Name and state filters are off while searching by id or reference." />`
      : html`<div class="chips" role="group" aria-label="Filter by state">
      <button type="button" class="chip" aria-pressed=${selected.length === 0} onClick=${() => setStates([])}>
        All states
      </button>
      ${STATES.map(
        (s) => html`
          <button type="button" class="chip" aria-pressed=${selected.includes(s)} onClick=${() => toggle(s)}>
            <${Dot} state=${s} />
            <span>${s}</span>
            <span class="count">${counts[s] || 0}</span>
          </button>`,
      )}
    </div>`}
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    <div class="split">
      <section class="panel list" aria-label="Jobs">
        <div class="table-scroll">
          <table class="table">
            <thead>
              <tr>
                <th>Job</th><th>Name</th><th>State</th><th>Attempts</th>
                <${SortedTh} label="Created" descending=${true} title="Newest first" />
                <th>Scheduled</th><th>Updated</th><th>Last error</th>
              </tr>
            </thead>
            <tbody>
              ${jobs.map(
                (job) => html`
                  <tr key=${job.id} class=${job.id === route.id ? 'selected' : ''} onClick=${() => open(job.id)}>
                    <td><a class="mono" href=${href(job.id)}>${shortId(job.id)}</a></td>
                    <td class="mono">
                      ${job.name}${job.recurrence && html` <${RecurringIcon} />`}
                      ${job.recurrence && html`<div class="sub">${job.recurrence}</div>`}
                      ${job.parentJobId &&
                      html`<div class="sub">child of ${job.parentJobName || shortId(job.parentJobId)}</div>`}
                      ${job.reference && html`<div class="sub" title=${job.reference}>ref ${job.reference}</div>`}
                    </td>
                    <td><${Badge} state=${job.state} /></td>
                    <td class="mono">${job.attempts}/${job.maxAttempts}</td>
                    <td class="mono muted">${dateTime(job.createdAt)}</td>
                    <td class="mono muted">${dateTime(job.scheduledAt)}</td>
                    <td class="mono muted">${dateTime(job.updatedAt)}</td>
                    <td class="error-cell" title=${job.lastError || ''}>${firstLine(job.lastError)}</td>
                  </tr>`,
              )}
            </tbody>
          </table>
          ${!loading && jobs.length === 0 && html`<div class="empty">${empty}</div>`}
        </div>
        <div class="panel-foot">
          <span>${loading ? 'Loading…' : plural(jobs.length, 'job', 'jobs') + ' shown'}</span>
          ${next && html`<button type="button" class="button" onClick=${() => load(next)}>Older jobs</button>`}
        </div>
      </section>
      ${route.id === 'new'
        ? canSchedule
          ? html`<${ScheduleForm} key=${SCHEDULE_PARAMS.map((p) => route.params[p] || '').join('/')}
            jobNames=${jobNames} payloadsShown=${me.payloadsShown}
            prefill=${{ from: route.params.from, name: route.params.schedName }}
            onClose=${closeForm}
            onScheduled=${(jobId) => {
              navigate({ section: 'jobs', id: jobId, params: listParams(route.params) });
              onScheduled();
            }} />`
          : me &&
            html`<aside class="panel detail" aria-label="Schedule job">
              <${DetailHead} title="Schedule job" plain=${true} onClose=${closeForm} />
              <p class="detail-body muted">Only operators can schedule jobs.</p>
            </aside>`
        : route.id && route.stepId
          ? html`<${StepDetail} key=${route.stepId} stepId=${route.stepId} nodes=${nodes}
              jobHref=${toHash(stepRoute(null))} onClose=${() => navigate(stepRoute(null))} />`
          : route.id &&
            html`<${JobDetail} key=${route.id} jobId=${route.id} me=${me} nodes=${nodes}
              stepHref=${(stepId) => toHash(stepRoute(stepId))}
              onUpdate=${updateRow} onChanged=${onChanged} onClose=${() => navigate({ ...route, id: null })}
              onScheduleSimilar=${canSchedule ? (jobId) => schedule({ from: jobId }) : null} />`}
    </div>`;
}

/**
 * One job, polled every 3 seconds while it is open, the tab is visible and the job has not ended.
 * The ETag makes an unchanged job a 304 without a body, so nothing re-renders.
 */
export function JobDetail({ jobId, me, nodes, stepHref, onUpdate, onChanged, onClose, onScheduleSimilar }) {
  const [scheduled] = useState(() => takeScheduled(jobId));
  const [data, setData] = useState(null);
  const [live, setLive] = useState('loading');
  const [updatedAt, setUpdatedAt] = useState(null);
  const [missing, setMissing] = useState(false);
  const [tab, setTab] = useState('steps');
  const [notice, setNotice] = useState(null);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [pollKey, setPollKey] = useState(0);
  const etag = useRef(null);
  const latest = useRef(null);

  useEffect(() => {
    let timer = null;
    let stopped = false;
    let ended = false;
    let busy = false;
    const schedule = (ms) => {
      clearTimeout(timer);
      timer = setTimeout(tick, ms);
    };
    async function tick() {
      if (stopped || ended || busy || document.hidden) {
        return; // a hidden tab resumes on visibilitychange
      }
      busy = true;
      try {
        const result = await getJson(`api/jobs/${jobId}`, etag.current);
        if (stopped) return;
        if (!result.notModified) {
          etag.current = result.etag;
          latest.current = result.body;
          setData(result.body);
          onUpdate?.(result.body.job);
        }
        setUpdatedAt(new Date());
        if (ENDED.includes(latest.current.job.state)) {
          ended = true;
          setLive('stopped');
        } else {
          setLive('live');
          schedule(POLL_MS);
        }
      } catch (e) {
        if (stopped) return;
        if (e instanceof ApiError && e.status === 404) {
          ended = true;
          setMissing(true);
        } else {
          setLive('lost');
          schedule(RETRY_MS);
        }
      } finally {
        busy = false;
      }
    }
    const onVisibility = () => {
      if (!document.hidden) {
        tick();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [jobId, pollKey]);

  if (missing) {
    return html`<aside class="panel detail" aria-label="Job details">
      <${DetailHead} title="Job not found" onClose=${onClose} />
      <p class="detail-body muted">Job ${jobId} does not exist (anymore): ended jobs are deleted after the job retention.</p>
    </aside>`;
  }
  if (!data) {
    return html`<aside class="panel detail" aria-label="Job details">
      <${DetailHead} title="Loading…" onClose=${onClose} />
    </aside>`;
  }

  const { job, steps, history, ancestors } = data;
  const operator = me?.level === 'OPERATE';
  const canRetry = operator && job.state === 'FAILED';
  const canCancel = operator && !ENDED.includes(job.state) && job.state !== 'COMPENSATING';

  const act = async (action) => {
    setConfirmCancel(false);
    try {
      const body = await post(`api/jobs/${jobId}/${action}`);
      if (action === 'retry') {
        setNotice(body.retried ? 'Retry scheduled.' : 'The job could not be retried: it has not failed.');
      } else {
        setNotice(
          body.cancelled
            ? 'Cancellation requested.'
            : 'The job could not be cancelled: it has ended or is compensating.',
        );
      }
      etag.current = null;
      setPollKey((key) => key + 1); // polls again, also after the job had ended
      onChanged?.();
    } catch (e) {
      setNotice(e.message);
    }
  };

  const liveText = {
    loading: 'Loading…',
    live: 'Live',
    stopped: `Updated ${updatedAt ? updatedAt.toLocaleTimeString() : ''}`,
    lost: 'Connection lost, retrying',
  }[live];

  const fields = [
    ['Type', JOB_TYPES[job.type] || job.type],
    ['Attempts', `${job.attempts}/${job.maxAttempts}`],
    ['Priority', job.priority],
    ['Scheduled', dateTime(job.scheduledAt)],
    ['Created', dateTime(job.createdAt)],
    ['Updated', dateTime(job.updatedAt)],
    ['Finished', dateTime(job.finishedAt)],
  ];
  const node = job.nodeId && nodes.find((candidate) => candidate.id === job.nodeId);
  if (job.nodeName) {
    fields.push(['Node', node ? html`<a href=${toHash({ section: 'nodes', id: node.id })}>${job.nodeName}</a>` : job.nodeName, job.nodeName]);
  }
  if (job.recurrence) fields.push(['Recurrence', job.recurrence]);
  if (job.idempotencyKey) fields.push(['Idempotency key', job.idempotencyKey]);
  if (job.reference) fields.push(['Reference', html`<${ReferenceLinks} reference=${job.reference} />`, job.reference]);

  const parent = ancestors.length > 0 ? ancestors[ancestors.length - 1] : null;
  const tabs = [['steps', `Steps (${steps.length})`]];
  if (job.recurrence) tabs.push(['recurrence', html`<${RecurringIcon} label="" /> Recurrence`]);
  if (history.length > 0) tabs.push(['history', `History (${history.length})`]);
  tabs.push(['data', 'Data']);
  const shownTab = tabs.some(([id]) => id === tab) ? tab : 'steps';

  return html`
    <aside class="panel detail" aria-label="Job details">
      ${ancestors.length > 0 && html`<${AncestorPath} ancestors=${ancestors} job=${job} />`}
      <${DetailHead} title=${job.name} state=${job.state} id=${job.id} onClose=${onClose}>
        <span role="status" class=${`live live-${live}`}><span class="live-dot"></span>${liveText}</span>
      <//>
      <dl class="meta">
        ${fields.map(
          ([label, value, title = String(value)]) => html`<div><dt>${label}</dt><dd class="mono" title=${title}>${value}</dd></div>`,
        )}
      </dl>
      ${parent &&
      html`<div class="parent-note">
        <span>
          Child job of
          ${parent.exists
            ? html` <a class="mono" href=${jobHref(parent.id)}>${parent.name} ${shortId(parent.id)}</a>`
            : html` <span class="mono">${shortId(parent.id)} (deleted)</span>`}.
          Its outcome completes the parent's step${parent.stepName && html` <code>${parent.stepName}</code>`}.
        </span>
        ${parent.exists && html`<a class="button small" href=${jobHref(parent.id)}>Parent job</a>`}
      </div>`}
      ${scheduled &&
      (scheduled.created
        ? html`<div class="notice notice-success" role="status">
            Scheduled <span class="mono strong">${shortId(scheduled.id)}</span>${scheduled.due ? `, due at ${dateTime(scheduled.due)}` : ' to run now'}.
          </div>`
        : html`<div class="notice notice-warning" role="status">
            Not scheduled again: this job already has the idempotency key <span class="mono">${scheduled.idempotencyKey}</span>.
          </div>`)}
      ${job.lastError && html`<pre class="error-box">${job.lastError}</pre>`}
      ${(canRetry || canCancel || onScheduleSimilar) &&
      html`<div class="actions">
        ${canRetry && html`<button type="button" class="button primary" onClick=${() => act('retry')}>Retry job</button>`}
        ${canCancel &&
        (confirmCancel
          ? html`<span class="confirm">Cancel this job?</span>
              <button type="button" class="button danger" onClick=${() => act('cancel')}>Yes, cancel</button>
              <button type="button" class="button" onClick=${() => setConfirmCancel(false)}>Keep it</button>`
          : html`<button type="button" class="button danger-outline" onClick=${() => setConfirmCancel(true)}>Cancel job</button>`)}
        ${onScheduleSimilar && !confirmCancel &&
        html`<button type="button" class="button" onClick=${() => onScheduleSimilar(job.id)}>Schedule similar…</button>`}
      </div>`}
      ${notice && html`<div class="notice" role="status">${notice}</div>`}
      <div class="tabs" role="tablist" aria-label="Job detail sections">
        ${tabs.map(
          ([id, label]) => html`<button type="button" role="tab" class="tab" aria-selected=${shownTab === id}
            onClick=${() => setTab(id)}>${label}</button>`,
        )}
      </div>
      <div class="detail-body" role="tabpanel">
        ${shownTab === 'steps' &&
        html`<${Steps} steps=${steps} ended=${ENDED.includes(job.state)} operator=${operator} stepHref=${stepHref} />`}
        ${shownTab === 'recurrence' && html`<${Recurrence} job=${job} />`}
        ${shownTab === 'history' &&
        html`<${History} history=${history} nodes=${nodes} stepHref=${(name) => {
          const step = steps.find((candidate) => candidate.name === name);
          return step && stepHref(step.id);
        }} />`}
        ${shownTab === 'data' && html`<${JobData} data=${data} ended=${ENDED.includes(job.state)} />`}
      </div>
    </aside>`;
}

// Step states without a running attempt; a later attempt or a compensation of the job can still
// change them.
const STEP_ENDED = ['SUCCEEDED', 'FAILED', 'COMPENSATED', 'CANCELLED'];

/**
 * One step by its id, with its result and its entries of the job history. Polled every 3 seconds
 * while it is open, the tab is visible and its job has not ended: a failed step runs again in the
 * job's next attempt, a succeeded one may be compensated.
 */
function StepDetail({ stepId, nodes, jobHref: backHref, onClose }) {
  const [data, setData] = useState(null);
  const [live, setLive] = useState('loading');
  const [missing, setMissing] = useState(false);
  const etag = useRef(null);

  useEffect(() => {
    let timer = null;
    let stopped = false;
    let ended = false;
    async function tick() {
      if (stopped || ended || document.hidden) {
        return; // a hidden tab resumes on visibilitychange
      }
      try {
        const result = await getJson(`api/steps/${stepId}`, etag.current);
        if (stopped) return;
        if (!result.notModified) {
          etag.current = result.etag;
          setData(result.body);
        }
        const body = result.notModified ? null : result.body;
        if (body && (!body.job || ENDED.includes(body.job.state))) {
          ended = true;
          setLive('stopped');
        } else {
          setLive('live');
          timer = setTimeout(tick, POLL_MS);
        }
      } catch (e) {
        if (stopped) return;
        if (e instanceof ApiError && e.status === 404) {
          ended = true;
          setMissing(true);
        } else {
          setLive('lost');
          timer = setTimeout(tick, RETRY_MS);
        }
      }
    }
    const onVisibility = () => {
      if (!document.hidden) {
        clearTimeout(timer);
        tick();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [stepId]);

  if (missing) {
    return html`<aside class="panel detail" aria-label="Step details">
      <${DetailHead} title="Step not found" onClose=${onClose} />
      <p class="detail-body muted">Step ${stepId} does not exist (anymore): steps are deleted with their job.</p>
    </aside>`;
  }
  if (!data) {
    return html`<aside class="panel detail" aria-label="Step details">
      <${DetailHead} title="Loading…" onClose=${onClose} />
    </aside>`;
  }

  const { step, job, history } = data;
  const liveText = { loading: 'Loading…', live: 'Live', stopped: 'Job ended', lost: 'Connection lost, retrying' }[live];
  const poll = step.type === 'POLL';
  const fields = [
    ['Type', step.type],
    [poll ? 'Checks' : 'Attempts', step.attempts],
    ['Started', dateTime(step.startedAt)],
    ['Finished', dateTime(step.finishedAt)],
  ];
  if (step.finishedAt) fields.push(['Took', elapsed(step.startedAt, step.finishedAt)]);
  if (step.waitUntil && !step.finishedAt) fields.push([poll ? 'Next check' : 'Until', dateTime(step.waitUntil)]);
  if (step.deadline) fields.push(['Deadline', dateTime(step.deadline)]);
  if (step.failureAttempts > 0) fields.push(['Failure handler runs', step.failureAttempts]);
  if (step.compensable) fields.push(['Compensation', step.state === 'COMPENSATED' ? 'ran' : 'pending if the job fails']);
  if (step.nodeId) {
    const node = nodes.find((candidate) => candidate.id === step.nodeId);
    fields.push(['Node', node ? html`<a href=${toHash({ section: 'nodes', id: node.id })}>${node.name}</a>` : shortId(step.nodeId), node?.name || step.nodeId]);
  }

  return html`
    <aside class="panel detail" aria-label="Step details">
      <nav class="path" aria-label="Job">
        ${job
          ? html`<a class="mono" href=${backHref}>${job.name} ${shortId(job.id)}</a>`
          : html`<span class="mono muted">${shortId(step.jobId)}</span>`}
        <span class="path-sep">›</span>
        <span class="mono strong">${step.name}</span>
      </nav>
      <${DetailHead} title=${step.name} state=${step.state} id=${step.id} onClose=${onClose}>
        <span role="status" class=${`live live-${live}`}><span class="live-dot"></span>${liveText}</span>
      <//>
      <dl class="meta">
        ${fields.map(
          ([label, value, title = String(value)]) => html`<div><dt>${label}</dt><dd class="mono" title=${title}>${value}</dd></div>`,
        )}
      </dl>
      ${step.eventName &&
      html`<div class="step-event small detail-note">
        <span>Event <span class="mono">${step.eventName}</span> / <span class="mono">${step.eventKey}</span></span>
        ${step.eventId && html`<a class="button small" href=${toHash({ section: 'events', id: step.eventId })}>Open event</a>`}
      </div>`}
      ${step.type === 'CHILD_JOB' && step.childJobId && html`<div class="detail-note"><${ChildJob} step=${step} /></div>`}
      ${step.lastError && html`<pre class="error-box">${step.lastError}</pre>`}
      <div class="detail-body">
        <section aria-label="Result">
          <h3 class="section-label">Result</h3>
          ${data.payloadsShown && data.result == null
            ? html`<p class="muted">${STEP_ENDED.includes(step.state) ? 'The step has no result.' : 'No result yet: the step has not succeeded.'}</p>`
            : html`<${Payload} data=${data} name="result" />`}
        </section>
        <section aria-label="History">
          <h3 class="section-label">History</h3>
          ${history.length > 0
            ? html`<${History} history=${history} nodes=${nodes} stepHref=${() => null} />`
            : html`<p class="muted">No history: the job history is off, or the step has no entries.</p>`}
        </section>
      </div>
    </aside>`;
}

export function DetailHead({ title, state, id, plain, onClose, children }) {
  return html`
    <div class="detail-head">
      <div class="detail-title">
        <div class="row">
          <span class=${plain ? 'strong' : 'mono strong'}>${title}</span>
          ${state && html`<${Badge} state=${state} />`}
        </div>
        ${id && html`<span class="mono muted small break">${id}</span>`}
        ${children}
      </div>
      <button type="button" class="icon-button" aria-label="Close details" onClick=${onClose}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"></path></svg>
      </button>
    </div>`;
}

/** Root parent first, the step that started each child, then this job; long paths collapse. */
function AncestorPath({ ancestors, job }) {
  const items = ancestors.map((ancestor) => ({ ancestor }));
  const shown =
    items.length > 3 ? [items[0], { ellipsis: true }, ...items.slice(items.length - 2)] : items;
  return html`
    <nav class="path" aria-label="Parent jobs">
      ${shown.map((item) =>
        item.ellipsis
          ? html`<span class="path-sep">…</span>`
          : html`
              ${item.ancestor.exists
                ? html`<a class="mono" href=${jobHref(item.ancestor.id)}>${item.ancestor.name}</a>`
                : html`<span class="mono muted">${shortId(item.ancestor.id)} (deleted)</span>`}
              <span class="path-sep">›</span>
              ${item.ancestor.stepName &&
              html`<span class="mono muted">${item.ancestor.stepName}</span><span class="path-sep">›</span>`}`,
      )}
      <span class="mono strong">${job.name}</span>
    </nav>`;
}

// Steps that have not ended while their job has not: their duration counts up.
const OPEN_STEP_STATES = ['RUNNING', 'WAITING'];

// Opens the publish form for the event a step waits for, next to the name's published events.
const publishHref = (step) =>
  toHash({
    section: 'events',
    id: 'new',
    params: { name: step.eventName, pubName: step.eventName, pubKey: step.eventKey },
  });

function Steps({ steps, ended, operator, stepHref: hrefOf }) {
  const counting = !ended && steps.some((step) => !step.finishedAt && OPEN_STEP_STATES.includes(step.state));
  const [now, setNow] = useState(() => new Date().toISOString());
  useEffect(() => {
    if (!counting) return undefined;
    const timer = setInterval(() => setNow(new Date().toISOString()), POLL_MS);
    return () => clearInterval(timer);
  }, [counting]);
  if (steps.length === 0) {
    return html`<p class="muted">No steps yet: the handler has not recorded any.</p>`;
  }
  return html`
    <ol class="steps">
      ${steps.map((step) => {
        const open = !ended && !step.finishedAt && OPEN_STEP_STATES.includes(step.state);
        const took = step.finishedAt ? elapsed(step.startedAt, step.finishedAt) : open ? elapsed(step.startedAt, now) : null;
        // A step's start is reset on each attempt, so the duration is the last attempt's.
        const note = [open && 'so far', step.attempts > 1 && `last of ${step.attempts} attempts`].filter(Boolean).join(', ');
        return html`
          <li class="step">
            <div class="step-head">
              <div class="row">
                <a class="mono strong" href=${hrefOf(step.id)} title="Step details">${step.name}</a>
                <span class="tag">${step.type}</span>
                <${Badge} state=${step.state} />
                ${!took && step.attempts > 1 && html`<span class="muted small">${step.attempts} attempts</span>`}
              </div>
              ${took &&
              html`<span class="step-duration"><span class="mono strong">${took}</span>${note && html` <span class="muted small">${note}</span>`}</span>`}
            </div>
            <div class="step-times muted small mono">
              ${dateTime(step.startedAt)}${step.finishedAt && ` → ${dateTime(step.finishedAt)}`}
              ${step.waitUntil && !step.finishedAt && ` · until ${dateTime(step.waitUntil)}`}
            </div>
            ${step.eventName &&
            html`<div class="step-event small">
              <span>Event <span class="mono">${step.eventName}</span> / <span class="mono">${step.eventKey}</span></span>
              ${operator && !ended && step.type === 'EVENT_WAIT' && step.state === 'WAITING' &&
              html`<a class="button small" href=${publishHref(step)}>Publish event…</a>`}
            </div>`}
            ${step.type === 'CHILD_JOB' && step.childJobId && html`<${ChildJob} step=${step} />`}
            ${step.lastError && html`<pre class="error-box small">${step.lastError}</pre>`}
          </li>`;
      })}
    </ol>`;
}

const RECURRING_RUNS = 10;

/**
 * The runs of the job's recurring job: the pending one, then the last ended ones. Refreshes while
 * shown, since runs keep coming after the open job has ended.
 */
function Recurrence({ job }) {
  const [runs, setRuns] = useState(null);
  const [error, setError] = useState(null);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    let stopped = false;
    const load = () => {
      if (document.hidden) return;
      getBody(`api/recurring-runs${query({ name: job.name, limit: RECURRING_RUNS })}`).then(
        (body) => !stopped && (setRuns(body), setError(null)),
        (e) => !stopped && setError(e.message),
      );
    };
    load();
    const poll = setInterval(load, POLL_MS);
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => {
      stopped = true;
      clearInterval(poll);
      clearInterval(tick);
    };
  }, [job.name]);
  if (error) return html`<p class="error-text">${error}</p>`;
  if (!runs) return html`<p class="muted">Loading…</p>`;

  const { pending, previous } = runs;
  const run = (other, end) => html`
    <li class=${`run ${other.id === job.id ? 'current' : ''}`}>
      ${other.id === job.id
        ? html`<span class="mono strong">${shortId(other.id)}</span>`
        : html`<a class="mono" href=${jobHref(other.id)}>${shortId(other.id)}</a>`}
      <span><${Badge} state=${other.state} /></span>
      <span class="run-time">
        <span class="mono muted">${dateTime(other.scheduledAt)}</span>
        ${other.id === job.id && html`<span class="run-this">this job</span>`}
        ${other.lastError && html`<span class="run-error" title=${other.lastError}>${firstLine(other.lastError)}</span>`}
      </span>
      <span class="mono strong">${end}</span>
    </li>`;
  const scheduled = pending?.state === 'SCHEDULED';
  const nowIso = new Date(now).toISOString();
  // The next run counts down to its scheduled time.
  const pendingEnd = (other) => {
    return Date.parse(other.scheduledAt) > now ? `in ${elapsed(nowIso, other.scheduledAt)}` : 'due';
  };
  return html`
    <div class="recurrence">
      <section aria-label=${scheduled || !pending ? 'Next run' : 'Current run'}>
        <h3 class="section-label">${scheduled || !pending ? 'Next run' : 'Current run'}</h3>
        ${pending
          ? html`<ol class="runs pending">${run(pending, pendingEnd(pending))}</ol>`
          : html`<p class="muted">No run is scheduled: the recurring job was removed, or no node registers it.</p>`}
      </section>
      <section aria-label="Previous runs">
        <h3 class="section-label">Previous runs <span class="muted">· last ${RECURRING_RUNS}</span></h3>
        ${previous.length === 0
          ? html`<p class="muted">No previous runs yet.</p>`
          : html`<ol class="runs">${previous.map((other) => run(other, elapsed(other.scheduledAt, other.finishedAt)))}</ol>`}
      </section>
      <a href=${toHash({ section: 'jobs', params: { name: job.name } })}>All ${job.name} jobs in the list</a>
    </div>`;
}

function ChildJob({ step }) {
  return html`
    <div class="child">
      <span class="muted small">Child</span>
      <span class="mono">${step.childJobName}</span>
      <span class="mono muted">${shortId(step.childJobId)}</span>
      ${step.childJobState && html`<${Badge} state=${step.childJobState} />`}
      ${step.childJobExists
        ? html`<a class="button small" href=${jobHref(step.childJobId)}>Open child</a>`
        : html`<span class="muted small">(deleted)</span>`}
    </div>`;
}

function History({ history, nodes, stepHref: hrefOf }) {
  const nodeName = (id) => nodes.find((node) => node.id === id)?.name || shortId(id) || '-';
  return html`
    <ol class="steps history">
      ${history.map(
        (entry) => html`
          <li class=${entry.stepName ? 'step history-entry step-entry' : 'step history-entry'}>
            <div class="step-head">
              <div class="row">
                <span class="tag">${entry.stepName ? 'STEP' : 'JOB'}</span>
                ${entry.stepName && (hrefOf(entry.stepName)
                  ? html`<a class="mono strong" href=${hrefOf(entry.stepName)}>${entry.stepName}</a>`
                  : html`<span class="mono strong">${entry.stepName}</span>`)}
                <${Badge} state=${entry.state} />
              </div>
              <span class="mono muted small">${preciseTime(entry.at)}</span>
            </div>
            <div class="muted small">
              attempt ${entry.attempt}${entry.nodeId && html` · <span class="mono">${nodeName(entry.nodeId)}</span>`}
            </div>
            ${entry.detail && html`<div class="small break">${entry.detail}</div>`}
          </li>`,
      )}
    </ol>`;
}

/** A job's payload or result, or an event's payload; a note instead when the server hides them. */
/** The Data tab: the job's payload on top, its result below. */
function JobData({ data, ended }) {
  if (!data.payloadsShown) {
    return html`<${Payload} data=${data} />`;
  }
  return html`
    <div class="job-data">
      <section aria-label="Payload">
        <h3 class="section-label">Payload</h3>
        <${Payload} data=${data} />
      </section>
      <section aria-label="Result">
        <h3 class="section-label">Result</h3>
        <${Payload} data=${data} name="result" ended=${ended} />
      </section>
    </div>`;
}

export function Payload({ data, name = 'payload', owner = 'job', ended = true }) {
  const label = name === 'result' ? 'Result' : 'Payload';
  if (!data.payloadsShown) {
    return html`
      <div class="disabled-note">
        <strong>${label} display disabled</strong>
        <p>Payloads and results may hold personal or secret data, so the server does not read them.
          Enable them where the admin is created:</p>
        <code>BikeschedAdmin.of(bikesched).showPayloads(true)</code>
      </div>`;
  }
  if (name === 'result' && data.result == null) {
    return html`<p class="muted">${ended ? 'The job has no result.' : 'No result yet: the job has not ended.'}</p>`;
  }
  // A missing payload and a JSON null look the same: there is nothing to show.
  if (name === 'payload' && data.payload == null) {
    return html`<p class="muted">The ${owner} has no payload.</p>`;
  }
  return html`<pre class="json">${pretty(data[name])}</pre>`;
}
