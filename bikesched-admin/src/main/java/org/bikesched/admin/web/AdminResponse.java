// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * The answer of {@link BikeschedAdmin#handle(AdminRequest)}, written to the HTTP response by the
 * server the admin is mounted in.
 *
 * @param status the HTTP status code
 * @param headers the response headers (content type, caching, security headers)
 * @param body the body; empty for {@code 304} and when there is none. Not copied: don't modify it.
 */
public record AdminResponse(int status, Map<String, String> headers, byte[] body) {

  /** Copies the headers. */
  public AdminResponse {
    headers = Map.copyOf(headers);
    Objects.requireNonNull(body, "body");
  }

  /**
   * Reads a header.
   *
   * @param name the header name, as the admin writes it (e.g. {@code Content-Type})
   * @return the value, {@code null} if the response has no such header
   */
  public String header(String name) {
    return headers.get(name);
  }

  /**
   * Decodes the body.
   *
   * @return the body as UTF-8 text
   */
  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }
}
