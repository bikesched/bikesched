// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web.access;

import org.bikesched.admin.web.AdminRequest;

/** {@link AdminAccess#open()}: every request may do everything. */
enum OpenAccess implements AdminAccess {
  INSTANCE;

  private static final AccessGrant OPERATE = new AccessGrant(null, AccessLevel.OPERATE);

  @Override
  public AccessGrant check(AdminRequest request) {
    return OPERATE;
  }
}
