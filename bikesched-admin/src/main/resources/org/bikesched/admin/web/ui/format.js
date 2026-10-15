// SPDX-License-Identifier: Apache-2.0
import { html, useEffect, useState } from './html.js';

export const STATES = [
  'SCHEDULED',
  'RUNNING',
  'WAITING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'FAILING',
  'COMPENSATING',
  'COMPENSATION_FAILED',
];

/** States a job does not leave on its own; its detail stops polling then. */
export const ENDED = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'COMPENSATION_FAILED'];

const pad = (n, width = 2) => String(n).padStart(width, '0');

/** A time of day for today's times, a date and time otherwise; '-' for none. */
export function dateTime(iso) {
  if (!iso) {
    return '-';
  }
  const date = new Date(iso);
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  if (date.toDateString() === new Date().toDateString()) {
    return time;
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${time}`;
}

/** A time of day with milliseconds, for the history. */
export function preciseTime(iso) {
  const date = new Date(iso);
  return `${dateTime(iso)}.${pad(date.getMilliseconds(), 3)}`;
}

/** A duration between two times: "7 d", "2 h 5 min", "30 s". */
export function duration(fromIso, toIso) {
  let seconds = Math.max(0, Math.round((Date.parse(toIso) - Date.parse(fromIso)) / 1000));
  const days = Math.floor(seconds / 86400);
  seconds -= days * 86400;
  const hours = Math.floor(seconds / 3600);
  seconds -= hours * 3600;
  const minutes = Math.floor(seconds / 60);
  seconds -= minutes * 60;
  const parts = [];
  if (days) parts.push(`${days} d`);
  if (hours) parts.push(`${hours} h`);
  if (minutes) parts.push(`${minutes} min`);
  if (seconds || parts.length === 0) parts.push(`${seconds} s`);
  return parts.slice(0, 2).join(' ');
}

/**
 * How long something took, for steps: "412 ms", "1.8 s", "5 min 37 s", "2 h 14 min"; at most two
 * adjacent units.
 */
export function elapsed(fromIso, toIso) {
  const millis = Math.max(0, Date.parse(toIso) - Date.parse(fromIso));
  if (millis < 1000) return `${millis} ms`;
  if (millis < 10000) return `${(Math.floor(millis / 100) / 10).toFixed(1)} s`;
  const units = [['d', 86400], ['h', 3600], ['min', 60], ['s', 1]];
  let seconds = Math.floor(millis / 1000);
  const first = units.findIndex(([, size]) => seconds >= size);
  const parts = [];
  for (const [name, size] of units.slice(first, first + 2)) {
    const count = Math.floor(seconds / size);
    seconds -= count * size;
    if (count || parts.length === 0) parts.push(`${count} ${name}`);
  }
  return parts.join(' ');
}

export const plural = (count, one, many) => `${count} ${count === 1 ? one : many}`;

export const shortId = (id) => (id ? id.slice(0, 8) : '');

export const firstLine = (text) => (text ? text.split('\n')[0] : '');

/** JSON text of a payload or result; the API embeds them as JSON values. */
export const pretty = (value) => JSON.stringify(value === undefined ? null : value, null, 2);

/** Marks a recurring job (in the list, the type picker and the detail's tab). */
export function RecurringIcon({ label = 'recurring' }) {
  const path = html`<path d="M17 2l4 4-4 4M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4 4-4M21 13v1a4 4 0 0 1-4 4H3" />`;
  return label
    ? html`<svg class="recurring-icon" width="14" height="14" viewBox="0 0 24 24" role="img" aria-label=${label}><title>${label}</title>${path}</svg>`
    : html`<svg class="recurring-icon" width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">${path}</svg>`;
}

/** The header of the column a table is sorted by, with an arrow; title says how. */
export function SortedTh({ label, descending, title }) {
  return html`<th aria-sort=${descending ? 'descending' : 'ascending'} title=${title}>
    ${label}<span class="sort-arrow" aria-hidden="true">${descending ? '↓' : '↑'}</span>
  </th>`;
}

export function Badge({ state }) {
  return html`<span class=${`badge state-${state}`}>${state}</span>`;
}

export function Dot({ state }) {
  return html`<span class=${`dot state-${state}`} aria-hidden="true"></span>`;
}

/** Hash routes: #/jobs/{id}?type=…&state=…, #/events/{id}?name=…&key=…&tab=…, #/nodes/{id} */
export function parseHash(hash = location.hash) {
  const raw = hash.replace(/^#\/?/, '');
  const [path, search = ''] = raw.split('?');
  const [section, id] = path.split('/');
  return {
    section: ['events', 'nodes'].includes(section) ? section : 'jobs',
    id: id || null,
    params: Object.fromEntries(new URLSearchParams(search)),
  };
}

export function toHash({ section, id, params = {} }) {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value) {
      search.set(name, value);
    }
  }
  const text = search.toString();
  return `#/${section}${id ? `/${id}` : ''}${text ? `?${text}` : ''}`;
}

export const jobHref = (id) => toHash({ section: 'jobs', id });

// Search by id: a full id or at least its first 8 hex digits; whitespace, hyphens and case are
// ignored, as on the server.
const idDigits = (text) => text.trim().replace(/-/g, '').toLowerCase();

export function idSearchHint(text) {
  const digits = idDigits(text);
  if (!digits) return '';
  if (!/^[0-9a-f]+$/.test(digits)) return 'An id has only the digits 0-9, a-f and hyphens';
  if (digits.length < 8) return 'Type at least 8 characters of the id';
  if (digits.length > 32) return 'An id has 32 hex digits';
  return '';
}

export const isFullId = (text) => idDigits(text).length === 32;

export function IdSearch({ kind, value, onSearch }) {
  const [input, setInput] = useState(value);
  useEffect(() => setInput(value), [value]);
  const hint = idSearchHint(input);
  const id = `${kind}-id-search`;
  return html`
    <form class="field" role="search" onSubmit=${(event) => {
      event.preventDefault();
      if (!hint) onSearch(input.trim().toLowerCase());
    }}>
      <label class="field-label" for=${id}>${kind === 'job' ? 'Job id' : 'Event id'}</label>
      <div class="row">
        <input id=${id} class="input mono id-input" type="search" value=${input}
          placeholder="Full id or its first 8 characters" aria-describedby=${hint ? `${id}-hint` : undefined}
          onInput=${(event) => setInput(event.target.value)}
          onKeyDown=${(event) => {
            if (event.key === 'Escape') {
              setInput('');
              onSearch('');
            }
          }} />
        <button type="submit" class="button" disabled=${!!hint}>Search</button>
        ${hint && html`<span id=${`${id}-hint`} class="filter-hint" role="status">${hint}</span>`}
      </div>
    </form>`;
}

/** The chip of an active id search, which clears it, with a note that the other filters are off. */
export function IdSearchChip({ kind, value, note, onClear }) {
  return html`
    <div class="chips">
      <button type="button" class="chip" aria-pressed="true" aria-label=${`Clear the ${kind} id search`} onClick=${onClear}>
        <span class="mono">id ${value}</span><span aria-hidden="true">×</span>
      </button>
      <span class="muted">${note}</span>
    </div>`;
}
