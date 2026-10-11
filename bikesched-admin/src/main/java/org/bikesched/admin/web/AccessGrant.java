// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.util.Objects;

/**
 * The outcome of an {@link AdminAccess} check: who sent the request and what it may do.
 *
 * @param user the user name shown in the page's header, {@code null} if unknown (e.g. {@link
 *     AdminAccess#open()})
 * @param level what the request may do
 */
public record AccessGrant(String user, AccessLevel level) {

  private static final AccessGrant NONE = new AccessGrant(null, AccessLevel.NONE);

  /** Checks that the level is set. */
  public AccessGrant {
    Objects.requireNonNull(level, "level");
  }

  /**
   * Denies a request.
   *
   * @return a grant with {@link AccessLevel#NONE} and no user
   */
  public static AccessGrant none() {
    return NONE;
  }
}
