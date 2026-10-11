// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

/**
 * Decides per request what it may do in the admin UI. Called for every request, also for the page
 * and its static files, so implementations must be thread-safe and fast.
 *
 * <pre>{@code
 * BikeschedAdmin.of(bikesched)
 *     .access(AdminAccess.basicAuth()
 *         .operator("ops", System.getenv("ADMIN_OPS_PASSWORD"))
 *         .viewer("support", System.getenv("ADMIN_SUPPORT_PASSWORD"))
 *         .build());
 * }</pre>
 *
 * <p>Custom implementations map anything in the request to a level, e.g. a header set by an
 * authenticating reverse proxy.
 */
public interface AdminAccess {

  /**
   * Decides what a request may do.
   *
   * @param request the request, with its headers and remote address
   * @return the user and level; {@link AccessGrant#none()} to deny it
   */
  AccessGrant check(AdminRequest request);

  /**
   * The {@code WWW-Authenticate} header value sent with a {@code 401} when {@link #check} denies a
   * request, e.g. {@code Basic realm="…"}.
   *
   * @return the challenge; {@code null} (the default) answers denied requests with {@code 403}
   */
  default String challenge() {
    return null;
  }

  /**
   * Lets every request retry and cancel jobs: the application protects the path the admin is
   * mounted at itself (e.g. Spring Security or a reverse proxy). The default of {@link
   * BikeschedAdmin}.
   *
   * @return an access granting {@link AccessLevel#OPERATE} to every request
   */
  static AdminAccess open() {
    return OpenAccess.INSTANCE;
  }

  /**
   * Starts building HTTP Basic authentication with operators (may retry and cancel) and viewers
   * (read only). The browser asks for the credentials with its own dialog. Basic authentication
   * sends the password with every request, so serve the admin over HTTPS.
   *
   * @return a builder adding the users
   */
  static BasicAuthBuilder basicAuth() {
    return new BasicAuthBuilder();
  }
}
