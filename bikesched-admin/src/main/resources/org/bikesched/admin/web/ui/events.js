// SPDX-License-Identifier: Apache-2.0
// Published events by name and key, one event with its publisher and receivers, and the jobs
// still waiting for an event name.
import { getBody, query } from './api.js';
import {
  Badge,
  IdSearch,
  IdSearchChip,
  dateTime,
  duration,
  isFullId,
  jobHref,
  plural,
  shortId,
  toHash,
} from './format.js';
import { html, useEffect, useState } from './html.js';
import { DetailHead, Payload } from './jobs.js';
import { Picker } from './picker.js';
import { PublishForm, takePublished } from './publish.js';

const PAGE_SIZE = 50;

// The route of the publish form is #/events/new; these parameters only prefill it.
const PUBLISH_PARAMS = ['pubName', 'pubKey', 'from'];
const listParams = (params) =>
  Object.fromEntries(Object.entries(params).filter(([name]) => !PUBLISH_PARAMS.includes(name)));

export function EventsView({ route, navigate, names, me, reloadKey, onPublished }) {
  const canPublish = me?.level === 'OPERATE';
  // An id search (route.params.id) replaces the name and key filters.
  const id = route.params.id || '';
  const name = id ? '' : route.params.name || '';
  const key = id ? '' : route.params.key || '';
  const tab = name && route.params.tab === 'waiting' ? 'waiting' : 'published';
  const [keyInput, setKeyInput] = useState(key);
  useEffect(() => setKeyInput(key), [key]);

  const setParams = (params) => navigate({ ...route, params: { ...route.params, ...params } });
  const search = (text) => navigate({ section: 'events', id: null, params: text ? { id: text } : {} });
  // Opens the form next to the published list, keeping its filters.
  const publish = (prefill) =>
    navigate({ section: 'events', id: 'new', params: { ...listParams(route.params), tab: '', ...prefill } });
  const nameItems = names.map((entry) => ({
    name: entry.name,
    count: entry.count,
    sub: entry.waiting > 0 ? `${plural(entry.waiting, 'job', 'jobs')} waiting` : undefined,
  }));

  return html`
    <div class="view-head">
      <h1>${id ? `Event id ${id}` : name || 'All events'}</h1>
      <div class="row">
        <span class="muted">Unexpired events, newest first</span>
        ${canPublish &&
        html`<button type="button" class="button primary" onClick=${() => publish({})}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
            stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"></path></svg>
          Publish event
        </button>`}
      </div>
    </div>
    <div class="toolbar">
      <div class="field picker-field">
        <div class="field-head">
          <span class="field-label">Event name</span>
          <span class="muted">${plural(names.length, 'event name', 'event names')}</span>
        </div>
        <${Picker} label="Event name" allLabel="All event names" placeholder="Search event names"
          items=${nameItems} selected=${name || null}
          onSelect=${(next) => navigate({ section: 'events', params: { name: next } })} />
      </div>
      <${IdSearch} kind="event" value=${id} onSearch=${search} />
      ${!id &&
      html`<form class="field" onSubmit=${(event) => {
        event.preventDefault();
        setParams({ key: keyInput.trim() });
      }}>
        <label class="field-label" for="event-key">Key starts with</label>
        <div class="row">
          <input id="event-key" class="input mono" type="search" value=${keyInput}
            placeholder="e.g. order-42" onInput=${(event) => setKeyInput(event.target.value)} />
          <button type="submit" class="button">Filter</button>
        </div>
      </form>`}
    </div>
    ${id &&
    html`<${IdSearchChip} kind="event" value=${id} onClear=${() => search('')}
      note="Name and key filters are off while searching by id." />`}
    ${name &&
    html`<div class="tabs view-tabs" role="tablist" aria-label="Event views">
      <button type="button" role="tab" class="tab" aria-selected=${tab === 'published'}
        onClick=${() => setParams({ tab: '' })}>Published</button>
      <button type="button" role="tab" class="tab" aria-selected=${tab === 'waiting'}
        onClick=${() => navigate({ section: 'events', id: null, params: { ...route.params, tab: 'waiting' } })}>
        Waiting jobs
      </button>
    </div>`}
    ${tab === 'waiting'
      ? html`<${WaitingJobs} name=${name} keyPrefix=${key} reloadKey=${reloadKey}
          onPublish=${canPublish ? (step) => publish({ pubName: step.eventName, pubKey: step.eventKey }) : null} />`
      : html`<${EventList} route=${route} navigate=${navigate} idPrefix=${id} name=${name} keyPrefix=${key}
          names=${names} me=${me} reloadKey=${reloadKey}
          onPublishSimilar=${canPublish ? (eventId) => publish({ from: eventId }) : null}
          onPublished=${(eventId) => {
            navigate({ section: 'events', id: eventId, params: listParams(route.params) });
            onPublished();
          }} />`}`;
}

function EventList({ route, navigate, idPrefix, name, keyPrefix, names, me, reloadKey, onPublishSimilar, onPublished }) {
  const [events, setEvents] = useState([]);
  const [next, setNext] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = async (before) => {
    setLoading(true);
    try {
      const params = idPrefix
        ? { id: idPrefix, limit: PAGE_SIZE }
        : { name, key: keyPrefix, before, limit: PAGE_SIZE };
      const body = await getBody(`api/events${query(params)}`);
      setEvents((previous) => (before ? [...previous, ...body.events] : body.events));
      setNext(body.next);
      setError(null);
      if (idPrefix && body.events.length === 1 && !route.id) {
        navigate({ ...route, id: body.events[0].id });
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setEvents([]);
    setNext(null);
    load();
  }, [idPrefix, name, keyPrefix, reloadKey]);

  const href = (id) => toHash({ ...route, id });
  const empty = !idPrefix
    ? 'No unexpired events match these filters.'
    : isFullId(idPrefix)
      ? `No unexpired event has the id ${idPrefix}.`
      : `No event id starts with ${idPrefix}.`;
  return html`
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    <div class="split">
      <section class="panel list" aria-label="Events">
        <div class="table-scroll">
          <table class="table">
            <thead>
              <tr><th>Event</th><th>Name</th><th>Key</th><th>Published</th><th>Expires</th><th>Received by</th></tr>
            </thead>
            <tbody>
              ${events.map(
                (event) => html`
                  <tr key=${event.id} class=${event.id === route.id ? 'selected' : ''}
                    onClick=${() => navigate({ ...route, id: event.id })}>
                    <td><a class="mono" href=${href(event.id)}>${shortId(event.id)}</a></td>
                    <td class="mono">${event.name}</td>
                    <td class="mono">${event.key}${event.latest && html` <span class="tag latest">latest</span>`}</td>
                    <td class="mono muted">${dateTime(event.publishedAt)}</td>
                    <td class="mono muted">${dateTime(event.expiresAt)}</td>
                    <td class="mono">${event.receivedBy}</td>
                  </tr>`,
              )}
            </tbody>
          </table>
          ${!loading && events.length === 0 && html`<div class="empty">${empty}</div>`}
        </div>
        <div class="panel-foot">
          <span>${loading ? 'Loading…' : plural(events.length, 'event', 'events') + ' shown'}</span>
          ${next && html`<button type="button" class="button" onClick=${() => load(next)}>Older events</button>`}
        </div>
      </section>
      ${route.id === 'new'
        ? onPublishSimilar
          ? html`<${PublishForm} key=${PUBLISH_PARAMS.map((p) => route.params[p] || '').join('/')}
            names=${names} payloadsShown=${me.payloadsShown}
            prefill=${{ from: route.params.from, name: route.params.pubName, key: route.params.pubKey }}
            onClose=${() => navigate({ ...route, id: null, params: listParams(route.params) })}
            onPublished=${onPublished} />`
          : me &&
            html`<aside class="panel detail" aria-label="Publish event">
              <${DetailHead} title="Publish event" plain=${true} onClose=${() => navigate({ ...route, id: null, params: listParams(route.params) })} />
              <p class="detail-body muted">Only operators can publish events.</p>
            </aside>`
        : route.id &&
          html`<${EventDetail} key=${route.id} eventId=${route.id} onPublishSimilar=${onPublishSimilar}
            onClose=${() => navigate({ ...route, id: null })} />`}
    </div>`;
}

function EventDetail({ eventId, onClose, onPublishSimilar }) {
  const [published] = useState(() => takePublished(eventId));
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [tab, setTab] = useState('jobs');

  useEffect(() => {
    getBody(`api/events/${eventId}`).then(setData, (e) => setError(e.message));
  }, [eventId]);

  if (error || !data) {
    return html`<aside class="panel detail" aria-label="Event details">
      <${DetailHead} title=${error ? 'Event not found' : 'Loading…'} onClose=${onClose} />
      ${error && html`<p class="detail-body muted">${error}</p>`}
    </aside>`;
  }

  const { event, steps } = data;
  const publishers = steps.filter((step) => step.publisher);
  const receivers = steps.filter((step) => !step.publisher);
  const fields = [
    ['Key', event.key],
    ['Published', dateTime(event.publishedAt)],
    ['Expires', dateTime(event.expiresAt)],
    ['Retention', duration(event.publishedAt, event.expiresAt)],
    ['Received by', plural(event.receivedBy, 'job', 'jobs')],
  ];
  if (event.idempotencyKey) fields.push(['Idempotency key', event.idempotencyKey]);

  const jobLink = (step) => html`
    <li class="row">
      <a class="mono" href=${jobHref(step.jobId)}>${step.jobType} ${shortId(step.jobId)}</a>
      <${Badge} state=${step.jobState} />
      <span class="muted small">step <span class="mono">${step.stepName}</span></span>
    </li>`;

  return html`
    <aside class="panel detail" aria-label="Event details">
      <${DetailHead} title=${event.name} id=${event.id} onClose=${onClose}>
        ${event.latest && html`<span class="small muted"><span class="tag latest">latest</span> A new wait for this name and key receives this event.</span>`}
      <//>
      <dl class="meta">
        ${fields.map(([label, value]) => html`<div><dt>${label}</dt><dd class="mono" title=${value}>${value}</dd></div>`)}
      </dl>
      ${published &&
      html`<div class="notice notice-success" role="status">
        Published <span class="mono strong">${shortId(published.id)}</span>.
        ${published.received > 0 ? ` ${plural(published.received, 'job', 'jobs')} received it.` : ' No job was waiting for it.'}
      </div>`}
      ${onPublishSimilar &&
      html`<div class="actions">
        <button type="button" class="button" onClick=${() => onPublishSimilar(event.id)}>Publish similar</button>
      </div>`}
      <div class="tabs" role="tablist" aria-label="Event detail sections">
        <button type="button" role="tab" class="tab" aria-selected=${tab === 'jobs'} onClick=${() => setTab('jobs')}>Jobs</button>
        <button type="button" role="tab" class="tab" aria-selected=${tab === 'payload'} onClick=${() => setTab('payload')}>Payload</button>
      </div>
      <div class="detail-body" role="tabpanel">
        ${tab === 'jobs' &&
        html`
          <h3 class="section-label">Published by</h3>
          ${publishers.length > 0
            ? html`<ul class="links">${publishers.map(jobLink)}</ul>`
            : html`<p class="muted small">Outside a job.</p>`}
          <h3 class="section-label">Received by</h3>
          ${receivers.length > 0
            ? html`<ul class="links">${receivers.map(jobLink)}</ul>`
            : html`<p class="muted small">No job has received this event yet. A step that starts waiting for it
                before it expires receives it right away.</p>`}`}
        ${tab === 'payload' && html`<${Payload} data=${data} owner="event" />`}
      </div>
    </aside>`;
}

function WaitingJobs({ name, keyPrefix, reloadKey, onPublish }) {
  const [steps, setSteps] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    setSteps(null);
    getBody(`api/waiting${query({ name, key: keyPrefix, limit: 200 })}`).then(
      (body) => {
        setSteps(body.steps);
        setError(null);
      },
      (e) => setError(e.message),
    );
  }, [name, keyPrefix, reloadKey]);

  return html`
    ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
    <section class="panel list" aria-label="Waiting jobs">
      <div class="table-scroll">
        <table class="table">
          <thead>
            <tr><th>Job</th><th>Type</th><th>Step</th><th>Key</th><th>Waiting since</th><th>Timeout</th>${onPublish && html`<th><span class="sr-only">Publish</span></th>`}</tr>
          </thead>
          <tbody>
            ${(steps || []).map(
              (step) => html`
                <tr key=${`${step.jobId}/${step.stepName}`}>
                  <td><a class="mono" href=${jobHref(step.jobId)}>${shortId(step.jobId)}</a></td>
                  <td class="mono">${step.jobType}</td>
                  <td class="mono">${step.stepName}</td>
                  <td class="mono">${step.eventKey}</td>
                  <td class="mono muted">${dateTime(step.since)}</td>
                  <td class="mono muted">${step.timeoutAt ? dateTime(step.timeoutAt) : 'none'}</td>
                  ${onPublish &&
                  html`<td class="cell-action">
                    <button type="button" class="button small" aria-label=${`Publish ${step.eventName} / ${step.eventKey}`}
                      onClick=${() => onPublish(step)}>Publish…</button>
                  </td>`}
                </tr>`,
            )}
          </tbody>
        </table>
        ${steps && steps.length === 0 && html`<div class="empty">No job is waiting for ${name}.</div>`}
      </div>
      <div class="panel-foot"><span>${steps ? `${plural(steps.length, 'waiting step', 'waiting steps')}, longest waiting first` : 'Loading…'}</span></div>
    </section>`;
}
