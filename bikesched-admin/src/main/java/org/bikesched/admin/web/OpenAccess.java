// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

/** {@link AdminAccess#open()}: every request may do everything. */
enum OpenAccess implements AdminAccess {
  INSTANCE;

  private static final AccessGrant OPERATE = new AccessGrant(null, AccessLevel.OPERATE);

  @Override
  public AccessGrant check(AdminRequest request) {
    return OPERATE;
  }
}
