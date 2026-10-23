// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Validates the names and keys stored in {@code text} columns: node names, job types, step names,
 * event names, event keys and recurrence descriptions. The limits keep index entries below
 * PostgreSQL's B-tree maximum of 2704 bytes and match the CHECK constraints in the schema, so
 * callers get an {@link IllegalArgumentException} naming the parameter instead of an SQL error.
 *
 * <p>Besides characters, the limits count UTF-8 bytes, which is what an index entry holds: three
 * bytes per character, so any text of the Basic Multilingual Plane (every common script) can use
 * the full character limit, while characters of four bytes (emoji, for example) count more. An
 * index combining a name and a key (idempotency keys, event lookups) stays at most 2100 bytes.
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

  /** The maximum size of a name in UTF-8: three bytes per character. */
  public static final int MAX_NAME_BYTES = 3 * MAX_NAME_LENGTH;

  /** The maximum size of an event key or idempotency key in UTF-8: three bytes per character. */
  public static final int MAX_KEY_BYTES = 3 * MAX_KEY_LENGTH;

  /** The maximum length of a stored message ({@code last_error}, {@code cancel_message}). */
  public static final int MAX_MESSAGE_LENGTH = 4000;

  private static final String TRUNCATED = "… (truncated)";

  private ValidationUtil() {}

  /**
   * Checks a name: not null, not blank, at most {@link #MAX_NAME_LENGTH} characters and {@link
   * #MAX_NAME_BYTES} bytes in UTF-8.
   *
   * @param name the name to check
   * @param what what the name is, for the messages, e.g. {@code "job type"}
   * @return the name
   * @throws NullPointerException if the name is null
   * @throws IllegalArgumentException if the name is blank or too long
   */
  public static String requireName(String name, String what) {
    return require(name, what, MAX_NAME_LENGTH, MAX_NAME_BYTES);
  }

  /**
   * Checks an event key: not null, not blank, at most {@link #MAX_KEY_LENGTH} characters and {@link
   * #MAX_KEY_BYTES} bytes in UTF-8.
   *
   * @param key the key to check
   * @param what what the key is, for the messages, e.g. {@code "event key"}
   * @return the key
   * @throws NullPointerException if the key is null
   * @throws IllegalArgumentException if the key is blank or too long
   */
  public static String requireKey(String key, String what) {
    return require(key, what, MAX_KEY_LENGTH, MAX_KEY_BYTES);
  }

  /**
   * Checks a duration that must be positive: a timeout, delay, interval or sleep.
   *
   * @param duration the duration to check
   * @param what what the duration is, for the messages, e.g. {@code "timeout"}
   * @return the duration
   * @throws NullPointerException if the duration is null
   * @throws IllegalArgumentException if the duration is zero or negative
   */
  public static Duration requirePositive(Duration duration, String what) {
    Objects.requireNonNull(duration, what);
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(what + " must be positive: " + duration);
    }
    return duration;
  }

  /**
   * Shortens a message to at most {@link #MAX_MESSAGE_LENGTH} characters, ending in {@code "…
   * (truncated)"} if it was longer.
   *
   * @param message the message, may be {@code null}
   * @param <S> {@code String} or {@code @Nullable String}: the result is {@code null} only for
   *     {@code null}
   * @return the message, shortened if needed; {@code null} for {@code null}
   */
  @SuppressWarnings("unchecked") // S can only be String: the shortened message is an S
  public static <S extends @Nullable String> S truncateMessage(S message) {
    if (message == null || message.codePointCount(0, message.length()) <= MAX_MESSAGE_LENGTH) {
      return message;
    }
    int keep = MAX_MESSAGE_LENGTH - TRUNCATED.length();
    return (S) (message.substring(0, message.offsetByCodePoints(0, keep)) + TRUNCATED);
  }

  private static String require(String value, String what, int maxLength, int maxBytes) {
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
    // A UTF-16 char takes at most three bytes in UTF-8 (a surrogate pair four), so only text with
    // more than a third of the limit in chars needs encoding.
    if (value.length() * 3 > maxBytes) {
      int bytes = value.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > maxBytes) {
        throw new IllegalArgumentException(
            what
                + " must have at most "
                + maxBytes
                + " bytes in UTF-8, but has "
                + bytes
                + " (characters outside the Basic Multilingual Plane, e.g. emoji, take 4 bytes)");
      }
    }
    return value;
  }
}
