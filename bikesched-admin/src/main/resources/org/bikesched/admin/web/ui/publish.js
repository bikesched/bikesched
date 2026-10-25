// SPDX-License-Identifier: Apache-2.0
// Publishing an event: the form in the event detail panel (route #/events/new), with name
// suggestions, a prefill from the latest event of a name and the jobs that would continue.
import { getBody, postForm, query } from './api.js';
import { dateTime, jobHref, plural, shortId } from './format.js';
import { html, useEffect, useRef, useState } from './html.js';
import { DetailHead } from './jobs.js';

const RETENTIONS = [
  ['', 'Node default'],
  ['PT0S', '0: only jobs waiting now'],
  ['PT1H', '1 hour'],
  ['P1D', '1 day'],
  ['P7D', '7 days'],
];

/** The event the page published last, so its detail can say so once when it opens. */
let lastPublished = null;

export function takePublished(eventId) {
  const published = lastPublished && lastPublished.id === eventId ? lastPublished : null;
  lastPublished = null;
  return published;
}

const pretty = (payload) => (payload === undefined || payload === null ? '' : JSON.stringify(payload, null, 2));

function nameSub(entry) {
  const parts = [plural(entry.count, 'event', 'events')];
  if (entry.waiting > 0) parts.push(`${plural(entry.waiting, 'job', 'jobs')} waiting`);
  return parts.join(' · ');
}

/** A text input with the known event names as suggestions; any other name can be typed too. */
function NameInput({ value, names, onInput, onPick }) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const root = useRef(null);
  const needle = value.trim().toLowerCase();
  const matches = names.filter((entry) => entry.name.toLowerCase().includes(needle)).slice(0, 50);

  useEffect(() => {
    if (!open) return undefined;
    const onPointer = (event) => {
      if (!root.current.contains(event.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onPointer);
    return () => document.removeEventListener('mousedown', onPointer);
  }, [open]);

  const pick = (name) => {
    setOpen(false);
    onPick(name);
  };
  const onKeyDown = (event) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setActive((index) => Math.max(0, Math.min(matches.length - 1, index + step)));
    } else if (event.key === 'Enter' && open && matches[active]) {
      event.preventDefault();
      pick(matches[active].name);
    } else if (event.key === 'Escape') {
      setOpen(false);
    }
  };

  return html`
    <div class="combo" ref=${root}>
      <input id="publish-name" class="input mono" type="text" role="combobox" autocomplete="off"
        aria-expanded=${open && matches.length > 0} aria-controls="publish-names" value=${value}
        placeholder="e.g. payment-received"
        onFocus=${() => setOpen(true)}
        onInput=${(event) => {
          onInput(event.target.value);
          setActive(0);
          setOpen(true);
        }}
        onKeyDown=${onKeyDown} />
      ${open &&
      matches.length > 0 &&
      html`<div id="publish-names" class="picker-popup combo-popup" role="listbox" aria-label="Event names">
        <div class="picker-options">
          ${matches.map(
            (entry, index) => html`
              <button type="button" role="option" class="picker-option" aria-selected=${index === active}
                onMouseDown=${(event) => event.preventDefault()} onClick=${() => pick(entry.name)}>
                <span class="picker-option-text">
                  <span class="picker-option-name">${entry.name}</span>
                  <span class="picker-option-sub">${nameSub(entry)}</span>
                </span>
              </button>`,
          )}
        </div>
        ${!names.some((entry) => entry.name === value.trim()) &&
        html`<div class="combo-foot">Or keep typing to publish a new name.</div>`}
      </div>`}
    </div>`;
}

/**
 * The form. prefill: {from} (an event id: "Publish similar") or {name, key} (a waiting job), or
 * nothing. onPublished(id) is called after publishing.
 */
export function PublishForm({ names, prefill, payloadsShown, onClose, onPublished }) {
  const [name, setName] = useState(prefill.name || '');
  const [key, setKey] = useState(prefill.key || '');
  const [payload, setPayload] = useState('');
  const [retention, setRetention] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [reference, setReference] = useState('');
  const [source, setSource] = useState(null);
  const [waiting, setWaiting] = useState(null);
  const [error, setError] = useState(null);
  const [sending, setSending] = useState(false);

  // Copies key and reference (unless the key is kept) and payload from an event; payloads only come
  // with showPayloads.
  const copyFrom = async (eventId, keepKey) => {
    const data = await getBody(`api/events/${eventId}`);
    setName(data.event.name);
    if (!keepKey) {
      setKey(data.event.key);
      setReference(data.event.reference || '');
    }
    if (data.payloadsShown) setPayload(pretty(data.payload));
    setSource(data.event);
  };
  const copyLatest = async (eventName, keepKey) => {
    const body = await getBody(`api/events${query({ name: eventName, limit: 1 })}`);
    if (body.events.length > 0) {
      await copyFrom(body.events[0].id, keepKey);
    } else {
      setSource(null);
    }
  };

  useEffect(() => {
    const start = prefill.from
      ? copyFrom(prefill.from, false)
      : prefill.name
        ? copyLatest(prefill.name, Boolean(prefill.key))
        : Promise.resolve();
    start.catch((e) => setError(e.message));
  }, [prefill.from, prefill.name, prefill.key]);

  // The jobs waiting for the name, looked up once typing pauses.
  const trimmedName = name.trim();
  useEffect(() => {
    setWaiting(null);
    if (!trimmedName) return undefined;
    const timer = setTimeout(() => {
      getBody(`api/waiting${query({ name: trimmedName, limit: 200 })}`).then(
        (body) => setWaiting({ name: trimmedName, steps: body.steps }),
        () => setWaiting(null),
      );
    }, 250);
    return () => clearTimeout(timer);
  }, [trimmedName]);

  let jsonError = null;
  if (payload.trim()) {
    try {
      JSON.parse(payload);
    } catch (e) {
      jsonError = e.message;
    }
  }
  const known = names.some((entry) => entry.name === trimmedName);
  const trimmedKey = key.trim();
  const steps = waiting && waiting.name === trimmedName ? waiting.steps : [];
  const receivers = steps.filter((step) => step.eventKey === trimmedKey);
  const waitingKeys = [...new Set(steps.map((step) => step.eventKey))].filter((k) => k !== trimmedKey).slice(0, 8);
  const referenceError = [...reference].length > 200 ? 'At most 200 characters' : null;
  const ready = trimmedName && trimmedKey && !jsonError && !referenceError && !sending;
  const kept = RETENTIONS.find(([value]) => value === retention)[1].toLowerCase();

  const pickName = (picked) => {
    setName(picked);
    copyLatest(picked, false).catch((e) => setError(e.message));
  };
  const clear = () => {
    setSource(null);
    setKey('');
    setReference('');
    setPayload('');
  };
  const submit = async (event) => {
    event.preventDefault();
    if (!ready) return;
    setSending(true);
    setError(null);
    try {
      const result = await postForm('api/events', {
        name: trimmedName,
        key: trimmedKey,
        payload: payload.trim(),
        retention,
        idempotencyKey: idempotencyKey.trim(),
        // Sent as typed: a reference may have surrounding spaces, and the search matches it exactly.
        reference: reference.trim() ? reference : '',
      });
      lastPublished = result;
      onPublished(result.id);
    } catch (e) {
      setError(e.message);
      setSending(false);
    }
  };

  return html`
    <aside class="panel detail" aria-label="Publish event">
      <${DetailHead} title="Publish event" plain=${true} onClose=${onClose} />
      <form class="publish" onSubmit=${submit}>
        ${source &&
        html`<div class="prefill-note" role="status">
          <span>${payloadsShown ? 'Key and payload' : 'Key'} copied from the latest <span class="mono">${source.name}</span> event, <a class="mono" href=${`#/events/${source.id}`}>${shortId(source.id)}</a> (${dateTime(source.publishedAt)}).</span>
          <button type="button" class="link-button" onClick=${clear}>Clear</button>
        </div>`}
        <div class="form-grid">
          <div class="field">
            <label class="field-label" for="publish-name">Name</label>
            <${NameInput} value=${name} names=${names} onInput=${setName} onPick=${pickName} />
            ${trimmedName && !known &&
            html`<span class="field-warning">New name: no job has waited for or published it. Check the spelling.</span>`}
          </div>
          <div class="field">
            <label class="field-label" for="publish-key">Key</label>
            <input id="publish-key" class="input mono" type="text" value=${key} onInput=${(event) => setKey(event.target.value)} />
            ${waitingKeys.length > 0 &&
            html`<span class="key-chips"><span class="muted small">Jobs wait for</span>
              ${waitingKeys.map((k) => html`<button type="button" class="key-chip mono" onClick=${() => setKey(k)}>${k}</button>`)}
            </span>`}
          </div>
        </div>
        <div class="field">
          <label class="field-label" for="publish-payload">Payload</label>
          <textarea id="publish-payload" class=${`input mono textarea${jsonError ? ' invalid' : ''}`} rows="7"
            spellcheck="false" value=${payload} onInput=${(event) => setPayload(event.target.value)}
            aria-describedby="publish-payload-hint"></textarea>
          <span id="publish-payload-hint" class=${jsonError ? 'field-error' : 'muted small'}>
            ${jsonError
              ? `Not valid JSON: ${jsonError}`
              : payloadsShown
                ? 'JSON, optional. Waiting steps receive it as their result.'
                : 'JSON, optional. Payloads are hidden on this admin, so none is copied from earlier events.'}
          </span>
        </div>
        <div class="form-grid">
          <div class="field">
            <label class="field-label" for="publish-retention">Retention</label>
            <select id="publish-retention" class="input" value=${retention} onChange=${(event) => setRetention(event.target.value)}>
              ${RETENTIONS.map(([value, label]) => html`<option value=${value}>${label}</option>`)}
            </select>
            <span class="muted small">How long later waits still receive it.</span>
          </div>
          <div class="field">
            <label class="field-label" for="publish-idempotency">Idempotency key</label>
            <input id="publish-idempotency" class="input mono" type="text" placeholder="optional" value=${idempotencyKey}
              onInput=${(event) => setIdempotencyKey(event.target.value)} />
            <span class="muted small">A second publish with it returns the first event.</span>
          </div>
          <div class="field">
            <label class="field-label" for="publish-reference">Reference</label>
            <input id="publish-reference" class=${`input mono${referenceError ? ' invalid' : ''}`} type="text"
              placeholder="optional, e.g. pay_3NqK8L" value=${reference}
              onInput=${(event) => setReference(event.target.value)} />
            <span class=${referenceError ? 'field-error' : 'muted small'}>${referenceError || 'The entity the event is about, to find it by.'}</span>
          </div>
        </div>
        ${trimmedName && trimmedKey &&
        html`<div class=${`preview${receivers.length > 0 ? ' preview-receivers' : ''}`} role="status">
          ${receivers.length > 0
            ? html`<strong>${plural(receivers.length, 'job waits', 'jobs wait')} for ${trimmedName} / ${trimmedKey} and ${receivers.length === 1 ? 'continues' : 'continue'} right away</strong>
                <ul class="links">
                  ${receivers.map(
                    (step) => html`<li class="row">
                      <a class="mono" href=${jobHref(step.jobId)}>${step.jobName} ${shortId(step.jobId)}</a>
                      <span class="muted small">step <span class="mono">${step.stepName}</span></span>
                    </li>`,
                  )}
                </ul>`
            : html`<strong>No job waits for ${trimmedName} / ${trimmedKey} right now</strong>
                <span class="muted">
                  ${retention === 'PT0S'
                    ? 'With retention 0 it is not kept, so no job will receive it.'
                    : `It is kept (${kept}), so a job that starts waiting for it before then receives it.`}
                  ${waitingKeys.length > 0 && ' Pick a waiting key above to continue a job now.'}
                </span>`}
        </div>`}
        ${error && html`<div class="banner banner-error" role="alert">${error}</div>`}
        <div class="form-actions">
          <button type="button" class="button" onClick=${onClose}>Cancel</button>
          <button type="submit" class="button primary" disabled=${!ready}>${sending ? 'Publishing…' : 'Publish event'}</button>
        </div>
      </form>
    </aside>`;
}
