// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import com.sun.net.httpserver.HttpHandler;
import java.util.Objects;
import org.bikesched.Bikesched;

/**
 * The admin UI of a {@link Bikesched} node: one page listing jobs, steps, events and nodes, with
 * retry and cancel, and the JSON API it reads. Mount it in any HTTP server, e.g. the JDK's:
 *
 * <pre>{@code
 * HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
 * server.createContext("/bikesched", BikeschedAdmin.of(bikesched).httpHandler());
 * server.start(); // http://localhost:8081/bikesched/
 * }</pre>
 *
 * <p>Other frameworks translate their request into an {@link AdminRequest} and write the {@link
 * AdminResponse} of {@link #handle(AdminRequest)}.
 *
 * <p>Instances are immutable and thread-safe: the setters return a copy. By default every request
 * may retry and cancel jobs ({@link AdminAccess#open()}) and payloads and results are not shown;
 * see {@link #access(AdminAccess)}, {@link #readOnly(boolean)} and {@link #showPayloads(boolean)}.
 */
public final class BikeschedAdmin {

  private final Bikesched bikesched;

  private final boolean readOnly;

  private final boolean showPayloads;

  private final AdminAccess access;

  private final StaticFiles files;

  private final AdminHandler handler;

  private BikeschedAdmin(
      Bikesched bikesched,
      boolean readOnly,
      boolean showPayloads,
      AdminAccess access,
      StaticFiles files) {
    this.bikesched = bikesched;
    this.readOnly = readOnly;
    this.showPayloads = showPayloads;
    this.access = access;
    this.files = files;
    this.handler = new AdminHandler(bikesched, readOnly, showPayloads, access, files);
  }

  /**
   * Creates the admin of a node: open access, retry and cancel enabled, payloads hidden.
   *
   * @param bikesched the node whose database the admin reads; it does not need to poll
   * @return the admin
   */
  public static BikeschedAdmin of(Bikesched bikesched) {
    return new BikeschedAdmin(
        Objects.requireNonNull(bikesched, "bikesched"),
        false,
        false,
        AdminAccess.open(),
        new StaticFiles());
  }

  /**
   * Switches retry and cancel off for everyone: every access level is capped at {@link
   * AccessLevel#VIEW}.
   *
   * @param readOnly {@code true} to only show jobs (default {@code false})
   * @return a copy with the setting
   */
  public BikeschedAdmin readOnly(boolean readOnly) {
    return new BikeschedAdmin(bikesched, readOnly, showPayloads, access, files);
  }

  /**
   * Shows the payloads and results of jobs and the payloads of events. Off by default, since they
   * may hold personal or secret data: without it, they are never read from the database, so no
   * response of the JSON API contains them.
   *
   * @param showPayloads {@code true} to show them (default {@code false})
   * @return a copy with the setting
   */
  public BikeschedAdmin showPayloads(boolean showPayloads) {
    return new BikeschedAdmin(bikesched, readOnly, showPayloads, access, files);
  }

  /**
   * Sets who may see the admin and who may retry and cancel jobs, e.g. {@link
   * AdminAccess#basicAuth()}.
   *
   * @param access decides per request (default {@link AdminAccess#open()})
   * @return a copy with the setting
   */
  public BikeschedAdmin access(AdminAccess access) {
    return new BikeschedAdmin(
        bikesched, readOnly, showPayloads, Objects.requireNonNull(access, "access"), files);
  }

  /**
   * Answers a request: the page and its files, or the JSON API under {@code /api/}. Errors are
   * answered, not thrown: {@code 400} for malformed parameters, {@code 401}/{@code 403} when access
   * is denied, {@code 404} for unknown paths, jobs and events, {@code 503} if the database cannot
   * be accessed.
   *
   * @param request the request, with its path relative to where the admin is mounted
   * @return the response to write
   */
  public AdminResponse handle(AdminRequest request) {
    Objects.requireNonNull(request, "request");
    return handler.handle(request);
  }

  /**
   * Adapts the admin to the JDK's {@code com.sun.net.httpserver}: mount it with {@code
   * createContext(path, handler)}. A request to the context path without a trailing slash is
   * redirected to it, so the page's relative links work.
   *
   * @return the handler
   */
  public HttpHandler httpHandler() {
    return new ExchangeHandler(this, access.challenge() != null);
  }
}
