// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds HTTP Basic authentication for the admin UI, see {@link AdminAccess#basicAuth()}. Passwords
 * are kept as SHA-256 digests only and compared in constant time.
 */
public final class BasicAuthBuilder {

  private final Map<String, BasicAuthAccess.User> users = new LinkedHashMap<>();

  BasicAuthBuilder() {}

  /**
   * Adds a user who may see everything and retry and cancel jobs.
   *
   * @param user the user name, not empty, without {@code :}
   * @param password the password, not empty
   * @return this builder
   * @throws IllegalArgumentException if the user name or password is invalid or the user was
   *     already added
   */
  public BasicAuthBuilder operator(String user, String password) {
    return add(user, password, AccessLevel.OPERATE);
  }

  /**
   * Adds a user who may see everything but not retry or cancel jobs.
   *
   * @param user the user name, not empty, without {@code :}
   * @param password the password, not empty
   * @return this builder
   * @throws IllegalArgumentException if the user name or password is invalid or the user was
   *     already added
   */
  public BasicAuthBuilder viewer(String user, String password) {
    return add(user, password, AccessLevel.VIEW);
  }

  /**
   * Builds the access.
   *
   * @return the access checking the {@code Authorization} header of every request
   * @throws IllegalStateException if no user was added
   */
  public AdminAccess build() {
    if (users.isEmpty()) {
      throw new IllegalStateException("Basic authentication needs at least one user");
    }
    return new BasicAuthAccess(users);
  }

  private BasicAuthBuilder add(String user, String password, AccessLevel level) {
    Objects.requireNonNull(user, "user");
    if (user.isEmpty() || user.indexOf(':') >= 0) {
      throw new IllegalArgumentException("user must be non-empty and contain no ':': " + user);
    }
    if (password == null || password.isEmpty()) {
      throw new IllegalArgumentException("password of " + user + " must be non-empty");
    }
    if (users.containsKey(user)) {
      throw new IllegalArgumentException("user " + user + " was already added");
    }
    users.put(user, new BasicAuthAccess.User(BasicAuthAccess.digest(password), level));
    return this;
  }
}
