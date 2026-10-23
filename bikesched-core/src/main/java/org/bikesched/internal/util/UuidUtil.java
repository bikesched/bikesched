// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates the ids of jobs, events and nodes: time-ordered UUIDs of version 7 (RFC 9562), so new
 * rows land at the end of the primary keys and of the indexes led by a job id instead of on random
 * pages.
 *
 * <p>The 48 most significant bits are the Unix time in milliseconds, the 12 bits after the version
 * a counter (RFC 9562, method 1) that keeps the ids of one JVM strictly increasing, also within a
 * millisecond, and the 62 bits after the variant are random. The last 8 hex digits, the admin's
 * short id, are therefore random.
 */
public final class UuidUtil {

  private static final SecureRandom RANDOM = new SecureRandom();

  // The last id's milliseconds and counter: (millis << 12) | counter.
  private static final AtomicLong LAST = new AtomicLong();

  private UuidUtil() {}

  /**
   * Returns a new time-ordered id, greater than every id this JVM returned before.
   *
   * @return a UUID of version 7
   */
  public static UUID timeOrdered() {
    long now = System.currentTimeMillis() << 12;
    // A counter overflowing within a millisecond (4096 ids) borrows the next millisecond.
    long stamp = LAST.updateAndGet(last -> Math.max(now, last + 1));
    long msb = ((stamp >>> 12) << 16) | 0x7000L | (stamp & 0xFFFL);
    long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
