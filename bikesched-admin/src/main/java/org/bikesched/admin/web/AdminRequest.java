// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A request to {@link BikeschedAdmin#handle(AdminRequest)}, translated from the HTTP request of
 * whatever server the admin is mounted in.
 *
 * <pre>{@code
 * AdminResponse response =
 *     admin.handle(AdminRequest.of("GET", "/api/jobs", "state=FAILED")
 *         .withHeader("Authorization", authorization));
 * }</pre>
 *
 * @param method the HTTP method, e.g. {@code GET}
 * @param path the path relative to where the admin is mounted, starting with {@code /} (e.g. {@code
 *     /api/jobs} for {@code /bikesched/api/jobs} mounted at {@code /bikesched})
 * @param query the raw (URL-encoded) query string without {@code ?}, {@code null} if none
 * @param headers the request headers, one value per name; names are case-insensitive
 * @param remoteAddress the client's address for log messages, {@code null} if unknown
 * @param body the request body as text, {@code null} if none; only publishing an event reads one
 *     (form-encoded)
 */
public record AdminRequest(
    String method,
    String path,
    String query,
    Map<String, String> headers,
    String remoteAddress,
    String body) {

  /** Checks the method and path and copies the headers into a case-insensitive map. */
  public AdminRequest {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("path must start with '/': " + path);
    }
    var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    if (headers != null) {
      copy.putAll(headers);
    }
    headers = Collections.unmodifiableMap(copy);
  }

  /**
   * Creates a request without headers.
   *
   * @param method the HTTP method
   * @param path the path relative to the mount point, starting with {@code /}
   * @param query the raw query string, {@code null} if none
   * @return the request
   */
  public static AdminRequest of(String method, String path, String query) {
    return new AdminRequest(method, path, query, Map.of(), null, null);
  }

  /**
   * Returns a copy with one more header.
   *
   * @param name the header name
   * @param value the header value
   * @return the new request
   */
  public AdminRequest withHeader(String name, String value) {
    var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    copy.putAll(headers);
    copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
    return new AdminRequest(method, path, query, copy, remoteAddress, body);
  }

  /**
   * Returns a copy with the client's address.
   *
   * @param remoteAddress the address, for log messages
   * @return the new request
   */
  public AdminRequest withRemoteAddress(String remoteAddress) {
    return new AdminRequest(method, path, query, headers, remoteAddress, body);
  }

  /**
   * Returns a copy with a body.
   *
   * @param body the request body as text, {@code null} for none
   * @return the new request
   */
  public AdminRequest withBody(String body) {
    return new AdminRequest(method, path, query, headers, remoteAddress, body);
  }

  /**
   * Reads a header.
   *
   * @param name the header name, case-insensitive
   * @return the value, {@code null} if the request has no such header
   */
  public String header(String name) {
    return headers.get(name);
  }
}
