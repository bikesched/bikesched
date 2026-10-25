// SPDX-License-Identifier: Apache-2.0
// Scheduling a job: the form in the job detail panel (route #/jobs/new), with the job names this
// node handles, a prefill from the latest job of a name or from a job ("Schedule similar") and
// the time it becomes due.
import { getBody, postForm, query } from './api.js';
import { dateTime, plural, shortId } from './format.js';
import { html, useEffect, useState } from './html.js';
import { Picker } from './picker.js';
import { DetailHead } from './jobs.js';

const UNITS = [
  ['S', 'seconds', 1],
  ['M', 'minutes', 60],
  ['H', 'hours', 3600],
  ['D', 'days', 86400],
];

const TIMEOUTS = [
  ['', 'Node default'],
  ['PT30S', '30 seconds'],
  ['PT1M', '1 minute'],
  ['PT5M', '5 minutes'],
  ['PT15M', '15 minutes'],
  ['PT1H', '1 hour'],
];

/** The job the page scheduled last, so its detail can say so once when it opens. */
let lastScheduled = null;

export function takeScheduled(jobId) {
  const scheduled = lastScheduled && lastScheduled.id === jobId ? lastScheduled : null;
  lastScheduled = null;
  return scheduled;
}

const pretty = (payload) => (payload === undefined || payload === null ? '' : JSON.stringify(payload, null, 2));
const failedCount = (summary) => (summary.counts.FAILED || 0) + (summary.counts.COMPENSATION_FAILED || 0);

function nameSub(summary) {
  const parts = [];
  const failed = failedCount(summary);
  if (failed > 0) parts.push(`${failed} failed`);
  if (summary.recurrence) parts.push(`${summary.recurrence}, one-off run`);
  if (summary.total === 0) parts.push('no jobs yet');
  return parts.join(' · ') || undefined;
}

// ISO-8601 for a whole number of a unit, e.g. PT10M or P1D.
const isoDelay = (amount, unit) => (unit === 'D' ? `P${amount}D` : `PT${amount}${unit}`);

/**
 * The form. jobNames: the job names (only the handled ones are offered); prefill: {from} (a job
 * id: "Schedule similar") or {name}, or nothing. onScheduled(id) is called after scheduling.
 */
export function ScheduleForm({ jobNames, prefill, payloadsShown, onClose, onScheduled }) {
  const [name, setName] = useState(prefill.name || '');
  const [payload, setPayload] = useState('');
  const [when, setWhen] = useState('now');
  const [amount, setAmount] = useState('10');
  const [unit, setUnit] = useState('M');
  const [priority, setPriority] = useState('0');
  const [maxAttempts, setMaxAttempts] = useState('3');
  const [timeout, setTimeoutValue] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [reference, setReference] = useState('');
  const [source, setSource] = useState(null);
  const [error, setError] = useState(null);
  const [sending, setSending] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  const handled = jobNames.filter((summary) => summary.handled);
  const summary = handled.find((candidate) => candidate.name === name);

  // Copies the payload from a job; with settings (Schedule similar) also priority, attempts and
  // reference.
  const copyFrom = async (jobId, settings) => {
    const data = await getBody(`api/jobs/${jobId}`);
    setName(data.job.name);
    setPayload(data.payloadsShown ? pretty(data.payload) : '');
    if (settings) {
      setPriority(String(data.job.priority));
      setMaxAttempts(String(data.job.maxAttempts));
      setReference(data.job.reference || '');
    }
    setSource(data.job);
  };
  const copyLatest = async (jobName) => {
    const body = await getBody(`api/jobs${query({ name: jobName, limit: 1 })}`);
    if (body.jobs.length > 0) {
      await copyFrom(body.jobs[0].id, false);
    } else {
      setSource(null);
      setPayload('');
    }
  };

  useEffect(() => {
    const start = prefill.from
      ? copyFrom(prefill.from, true)
      : prefill.name
        ? copyLatest(prefill.name)
        : Promise.resolve();
    start.catch((e) => setError(e.message));
  }, [prefill.from, prefill.name]);

  // Keeps the due time of "In" current while the form is open.
  useEffect(() => {
    if (when !== 'in') return undefined;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [when]);

  let jsonError = null;
  if (payload.trim()) {
    try {
      JSON.parse(payload);
    } catch (e) {
      jsonError = e.message;
    }
  }
  const wholeNumber = (text) => /^-?\d+$/.test(text.trim());
  const amountError = when === 'in' && !(wholeNumber(amount) && Number(amount) >= 0) ? 'A whole number, 0 or more' : null;
  const priorityError = wholeNumber(priority) ? null : 'A whole number';
  const attemptsError = wholeNumber(maxAttempts) && Number(maxAttempts) >= 1 ? null : 'At least 1';
  const referenceError = [...reference].length > 200 ? 'At most 200 characters' : null;
  const due =
    when === 'in' && !amountError ? new Date(now + Number(amount) * UNITS.find(([u]) => u === unit)[2] * 1000) : null;
  const ready =
    summary && !jsonError && !amountError && !priorityError && !attemptsError && !referenceError && !sending;

  const pickName = (picked) => {
    setName(picked);
    copyLatest(picked).catch((e) => setError(e.message));
  };
  const clear = () => {
    setSource(null);
    setPayload('');
  };
  const submit = async (event) => {
    event.preventDefault();
    if (!ready) return;
    setSending(true);
    setError(null);
    try {
      const result = await postForm('api/jobs', {
        name,
        payload: payload.trim(),
        delay: when === 'in' ? isoDelay(Number(amount), unit) : '',
        priority: priority.trim() === '0' ? '' : priority.trim(),
        maxAttempts: maxAttempts.trim(),
        timeout,
        idempotencyKey: idempotencyKey.trim(),
        // Sent as typed: a reference may have surrounding spaces, and the search matches it exactly.
        reference: reference.trim() ? reference : '',
      });
      lastScheduled = { ...result, idempotencyKey: idempotencyKey.trim(), due: when === 'now' ? null : due.toISOString() };
      onScheduled(result.id);
    } catch (e) {
      setError(e.message);
      setSending(false);
    }
  };

  if (handled.length === 0) {
    return html`<aside class="panel detail" aria-label="Schedule job">
      <${DetailHead} title="Schedule job" plain=${true} onClose=${onClose} />
      <p class="detail-body muted">This node has no job handlers, so it cannot schedule jobs. Serve the admin from a node that registers them.</p>
    </aside>`;
  }

  const whenText = when === 'now' ? 'now' : due ? `at ${dateTime(due.toISOString())}` : 'later';
  const items = handled.map((entry) => ({
    name: entry.name,
    count: entry.total,
    sub: nameSub(entry),
    subClass: failedCount(entry) > 0 ? 'sub-failed' : undefined,
    recurring: Boolean(entry.recurrence),
  }));

  return html`
    <aside class="panel detail" aria-label="Schedule job">
      <${DetailHead} title="Schedule job" plain=${true} onClose=${onClose} />
      <form class="publish" onSubmit=${submit}>
        ${source &&
        html`<div class="prefill-note" role="status">
          <span>${prefill.from ? 'Copied from' : 'Payload copied from the latest'} <span class="mono">${source.name}</span> job, <a class="mono" href=${`#/jobs/${source.id}`}>${shortId(source.id)}</a> (${dateTime(source.createdAt)}).</span>
          <button type="button" class="link-button" onClick=${clear}>Clear</button>
        </div>`}
        <div class="form-grid">
          <div class="field">
            <span class="field-label">Name</span>
            <${Picker} label="Job name" placeholder="Search job names" emptyLabel="Pick a job name"
              items=${items} selected=${name || null} onSelect=${pickName} />
            ${summary?.recurrence && html`<span class="muted small">Recurring: this adds one run, the recurrence stays as it is.</span>`}
          </div>
          <div class="field">
            <span class="field-label" id="schedule-when">When</span>
            <div class="segmented" role="group" aria-labelledby="schedule-when">
              ${[['now', 'Now'], ['in', 'In']].map(
                ([value, label]) => html`<button type="button" aria-pressed=${when === value} onClick=${() => setWhen(value)}>${label}</button>`,
              )}
            </div>
            ${when === 'in' &&
            html`<div class="delay-row">
              <input class=${`input mono${amountError ? ' invalid' : ''}`} type="text" inputmode="numeric" aria-label="Delay"
                value=${amount} onInput=${(event) => setAmount(event.target.value)} />
              <select class="input" aria-label="Delay unit" value=${unit} onChange=${(event) => setUnit(event.target.value)}>
                ${UNITS.map(([value, label]) => html`<option value=${value}>${label}</option>`)}
              </select>
            </div>`}
            <span class=${amountError ? 'field-error' : 'muted small'}>
              ${amountError || (when === 'now' ? 'Due right away.' : due ? `Due at ${dateTime(due.toISOString())} (your time).` : '')}
            </span>
          </div>
        </div>
        <div class="field">
          <label class="field-label" for="schedule-payload">Payload</label>
          <textarea id="schedule-payload" class=${`input mono textarea${jsonError ? ' invalid' : ''}`} rows="7"
            spellcheck="false" value=${payload} onInput=${(event) => setPayload(event.target.value)}
            aria-describedby="schedule-payload-hint"></textarea>
          <span id="schedule-payload-hint" class=${jsonError ? 'field-error' : 'muted small'}>
            ${jsonError
              ? `Not valid JSON: ${jsonError}`
              : payloadsShown
                ? 'JSON, optional. The handler reads it with job.payload(…).'
                : 'JSON, optional. Payloads are hidden on this admin, so none is copied from earlier jobs.'}
          </span>
        </div>
        <details class="more-options">
          <summary>More options</summary>
          <div class="form-grid">
            <div class="field">
              <label class="field-label" for="schedule-priority">Priority</label>
              <input id="schedule-priority" class=${`input mono${priorityError ? ' invalid' : ''}`} type="text" inputmode="numeric"
                value=${priority} onInput=${(event) => setPriority(event.target.value)} />
              <span class=${priorityError ? 'field-error' : 'muted small'}>${priorityError || 'Higher runs first among due jobs.'}</span>
            </div>
            <div class="field">
              <label class="field-label" for="schedule-attempts">Max attempts</label>
              <input id="schedule-attempts" class=${`input mono${attemptsError ? ' invalid' : ''}`} type="text" inputmode="numeric"
                value=${maxAttempts} onInput=${(event) => setMaxAttempts(event.target.value)} />
              <span class=${attemptsError ? 'field-error' : 'muted small'}>${attemptsError || 'Attempts before it is FAILED.'}</span>
            </div>
            <div class="field">
              <label class="field-label" for="schedule-timeout">Timeout</label>
              <select id="schedule-timeout" class="input" value=${timeout} onChange=${(event) => setTimeoutValue(event.target.value)}>
                ${TIMEOUTS.map(([value, label]) => html`<option value=${value}>${label}</option>`)}
              </select>
              <span class="muted small">Per attempt.</span>
            </div>
            <div class="field">
              <label class="field-label" for="schedule-idempotency">Idempotency key</label>
              <input id="schedule-idempotency" class="input mono" type="text" placeholder="optional" value=${idempotencyKey}
                onInput=${(event) => setIdempotencyKey(event.target.value)} />
              <span class="muted small">A job of the name with this key is not scheduled twice.</span>
            </div>
            <div class="field">
              <label class="field-label" for="schedule-reference">Reference</label>
              <input id="schedule-reference" class=${`input mono${referenceError ? ' invalid' : ''}`} type="text"
                placeholder="optional, e.g. order-4711" value=${reference}
                onInput=${(event) => setReference(event.target.value)} />
              <span class=${referenceError ? 'field-error' : 'muted small'}>${referenceError || 'The entity the job is about, to find it by.'}</span>
            </div>
          </div>
        </details>
        ${summary &&
        html`<div class="preview" role="status">
          <strong>Runs as a new ${summary.name} job ${whenText}</strong>
          <span class="muted">
            Any node with a ${summary.name} handler and a free slot claims it.
            ${summary.total > 0
              ? ` ${plural(summary.total, 'job', 'jobs')} of this name so far${failedCount(summary) > 0 ? `, ${failedCount(summary)} failed` : ''}.`
              : ' It is the first job of this name.'}
          </span>
        </div>`}
        ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
        <div class="form-actions">
          <button type="button" class="button" onClick=${onClose}>Cancel</button>
          <button type="submit" class="button primary" disabled=${!ready}>${sending ? 'Scheduling…' : 'Schedule job'}</button>
        </div>
      </form>
    </aside>`;
}
