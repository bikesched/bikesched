// SPDX-License-Identifier: Apache-2.0
import { html } from './html.js';

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

/** "12 s ago", "3 min ago", ... */
export function ago(iso, now = Date.now()) {
  if (!iso) {
    return '-';
  }
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 172800) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86400)} d ago`;
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

export const plural = (count, one, many) => `${count} ${count === 1 ? one : many}`;

export const shortId = (id) => (id ? id.slice(0, 8) : '');

export const firstLine = (text) => (text ? text.split('\n')[0] : '');

/** JSON text of a payload or result; the API embeds them as JSON values. */
export const pretty = (value) => JSON.stringify(value === undefined ? null : value, null, 2);

export function Badge({ state }) {
  return html`<span class=${`badge state-${state}`}>${state}</span>`;
}

export function Dot({ state }) {
  return html`<span class=${`dot state-${state}`} aria-hidden="true"></span>`;
}

/** Hash routes: #/jobs/{id}?type=…&state=…, #/events/{id}?name=…&key=…&tab=… */
export function parseHash(hash = location.hash) {
  const raw = hash.replace(/^#\/?/, '');
  const [path, search = ''] = raw.split('?');
  const [section, id] = path.split('/');
  return {
    section: section === 'events' ? 'events' : 'jobs',
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
