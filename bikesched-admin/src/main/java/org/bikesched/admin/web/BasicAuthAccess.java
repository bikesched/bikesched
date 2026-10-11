// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Basic authentication with operators and viewers, built by {@link BasicAuthBuilder}.
 * Passwords are compared as SHA-256 digests with {@link MessageDigest#isEqual} (constant time, no
 * length leak); an unknown user is compared against a dummy digest, so its timing matches a wrong
 * password. Passwords are never logged.
 */
final class BasicAuthAccess implements AdminAccess {

  private static final Logger LOG = LoggerFactory.getLogger(BasicAuthAccess.class);

  static final String CHALLENGE = "Basic realm=\"bikesched admin\", charset=\"UTF-8\"";

  private static final byte[] DUMMY = digest("bikesched-admin-dummy");

  record User(byte[] passwordDigest, AccessLevel level) {}

  private final Map<String, User> users;

  BasicAuthAccess(Map<String, User> users) {
    this.users = Map.copyOf(users);
  }

  @Override
  public AccessGrant check(AdminRequest request) {
    String header = request.header("Authorization");
    if (header == null) {
      return AccessGrant.none(); // the browser's first request, before the login dialog
    }
    String credentials = decode(header);
    int colon = credentials == null ? -1 : credentials.indexOf(':');
    if (colon < 0) {
      LOG.warn("Malformed admin credentials from {}", request.remoteAddress());
      return AccessGrant.none();
    }
    String name = credentials.substring(0, colon);
    User user = users.get(name);
    byte[] given = digest(credentials.substring(colon + 1));
    boolean matches = MessageDigest.isEqual(user == null ? DUMMY : user.passwordDigest(), given);
    if (user == null || !matches) {
      LOG.warn("Failed admin login of user {} from {}", printable(name), request.remoteAddress());
      return AccessGrant.none();
    }
    return new AccessGrant(name, user.level());
  }

  @Override
  public String challenge() {
    return CHALLENGE;
  }

  /** The {@code user:password} of a {@code Basic} header; {@code null} if it is none. */
  private static String decode(String header) {
    if (header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return null;
    }
    try {
      return new String(
          Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** A user name safe for a log line: control characters replaced, at most 100 characters. */
  private static String printable(String name) {
    String cleaned = name.replaceAll("\\p{Cntrl}", "?");
    return cleaned.length() > 100 ? cleaned.substring(0, 100) + "…" : cleaned;
  }

  static byte[] digest(String password) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
