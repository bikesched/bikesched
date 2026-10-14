// SPDX-License-Identifier: Apache-2.0
// The JSON API, relative to the page, so the admin works under any mount path.

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function failure(response) {
  let message = `${response.status} ${response.statusText}`;
  try {
    message = (await response.json()).error || message;
  } catch {
    // not JSON
  }
  return new ApiError(response.status, message);
}

/**
 * GETs a resource. With an ETag, an unchanged resource answers 304: the result is then
 * {notModified: true} and nothing needs to re-render.
 */
export async function getJson(path, etag) {
  const headers = { Accept: 'application/json' };
  if (etag) {
    headers['If-None-Match'] = etag;
  }
  const response = await fetch(path, { headers, cache: 'no-store' });
  if (response.status === 304) {
    return { notModified: true, etag };
  }
  if (!response.ok) {
    throw await failure(response);
  }
  return { body: await response.json(), etag: response.headers.get('ETag') };
}

export async function getBody(path) {
  return (await getJson(path)).body;
}

/** POSTs an action; the header proves the request comes from this page (no cross-site form can set it). */
export async function post(path) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'X-Bikesched-Admin': '1' },
  });
  if (!response.ok) {
    throw await failure(response);
  }
  return response.json();
}

/** POSTs a form (non-empty values only), e.g. an event to publish; same header as post(). */
export async function postForm(path, params) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'X-Bikesched-Admin': '1' },
    body: new URLSearchParams(query(params).slice(1)),
  });
  if (!response.ok) {
    throw await failure(response);
  }
  return response.json();
}

/** A query string from the non-empty values, with a leading '?'. */
export function query(params) {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(name, value);
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}
