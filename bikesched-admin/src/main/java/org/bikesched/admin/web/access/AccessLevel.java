// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web.access;

/** What a request may do in the admin UI, decided by an {@link AdminAccess}. */
public enum AccessLevel {
  /** Nothing: the request is answered with 401 (with the access's challenge) or 403. */
  NONE,
  /** Read everything the admin shows (the page and every {@code GET} route). */
  VIEW,
  /** Also retry and cancel jobs (the {@code POST} routes). */
  OPERATE;

  /**
   * Whether this level includes another one.
   *
   * @param required the level a route needs
   * @return {@code true} if this level is at least {@code required}
   */
  public boolean allows(AccessLevel required) {
    return compareTo(required) >= 0;
  }
}
