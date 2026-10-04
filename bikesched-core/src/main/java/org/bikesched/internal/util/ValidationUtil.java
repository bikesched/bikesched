package org.bikesched.internal.util;

import java.util.Objects;

/**
 * Validates the names and keys stored in {@code text} columns: node names, job types, step names,
 * event names, event keys and recurrence descriptions. The limits keep index entries well below
 * PostgreSQL's B-tree maximum and match the CHECK constraints in the schema, so callers get an
 * {@link IllegalArgumentException} naming the parameter instead of an SQL error.
 *
 * <p>Messages (errors and cancel messages) are diagnostic text, so they are {@link
 * #truncateMessage(String) truncated} instead of rejected: recording a failure must not fail.
 */
public final class ValidationUtil {

  /**
   * The maximum length of a node name, job type, step name, event name or recurrence description.
   */
  public static final int MAX_NAME_LENGTH = 200;

  /** The maximum length of an event key, often a composite business id. */
  public static final int MAX_KEY_LENGTH = 500;

  /** The maximum length of a stored message ({@code last_error}, {@code cancel_message}). */
  public static final int MAX_MESSAGE_LENGTH = 4000;

  private static final String TRUNCATED = "… (truncated)";

  private ValidationUtil() {}

  /**
   * Checks a name: not null, not blank, at most {@link #MAX_NAME_LENGTH} characters.
   *
   * @param name the name to check
   * @param what what the name is, for the messages, e.g. {@code "job type"}
   * @return the name
   * @throws NullPointerException if the name is null
   * @throws IllegalArgumentException if the name is blank or too long
   */
  public static String requireName(String name, String what) {
    return require(name, what, MAX_NAME_LENGTH);
  }

  /**
   * Checks an event key: not null, not blank, at most {@link #MAX_KEY_LENGTH} characters.
   *
   * @param key the key to check
   * @param what what the key is, for the messages, e.g. {@code "event key"}
   * @return the key
   * @throws NullPointerException if the key is null
   * @throws IllegalArgumentException if the key is blank or too long
   */
  public static String requireKey(String key, String what) {
    return require(key, what, MAX_KEY_LENGTH);
  }

  /**
   * Shortens a message to at most {@link #MAX_MESSAGE_LENGTH} characters, ending in {@code "…
   * (truncated)"} if it was longer.
   *
   * @param message the message, may be {@code null}
   * @return the message, shortened if needed; {@code null} for {@code null}
   */
  public static String truncateMessage(String message) {
    if (message == null || message.codePointCount(0, message.length()) <= MAX_MESSAGE_LENGTH) {
      return message;
    }
    int keep = MAX_MESSAGE_LENGTH - TRUNCATED.length();
    return message.substring(0, message.offsetByCodePoints(0, keep)) + TRUNCATED;
  }

  private static String require(String value, String what, int maxLength) {
    Objects.requireNonNull(value, what);
    if (value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    // Code points, like PostgreSQL's char_length: a character outside the BMP counts once.
    int length = value.codePointCount(0, value.length());
    if (length > maxLength) {
      throw new IllegalArgumentException(
          what + " must have at most " + maxLength + " characters, but has " + length);
    }
    return value;
  }
}
