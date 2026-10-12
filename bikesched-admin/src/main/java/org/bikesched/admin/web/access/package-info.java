// SPDX-License-Identifier: Apache-2.0
/**
 * Authentication and authorization of the admin UI: an {@link
 * org.bikesched.admin.web.access.AdminAccess} decides per request what it may do ({@link
 * org.bikesched.admin.web.access.AccessLevel}), open by default, with HTTP Basic authentication
 * built in.
 */
package org.bikesched.admin.web.access;
