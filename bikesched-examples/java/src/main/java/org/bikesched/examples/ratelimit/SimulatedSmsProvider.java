// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.ratelimit;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An SMS provider that accepts at most {@value #LIMIT} messages within any second, simulated in
 * memory. A call over the limit is rejected (as a real provider answers HTTP 429), printed, and
 * counted, so the example shows that the rate limit keeps every call within the provider's quota.
 */
final class SimulatedSmsProvider {

  /** The number of messages the provider accepts within any second. */
  static final int LIMIT = 2;

  private static final long WINDOW_NANOS = Duration.ofSeconds(1).toNanos();

  private final PrintStream out;
  private final Deque<Long> accepted = new ArrayDeque<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger rejected = new AtomicInteger();

  SimulatedSmsProvider(PrintStream out) {
    this.out = out;
  }

  /**
   * Sends a message.
   *
   * @param to the recipient
   * @param text the message
   * @return the provider's message id
   * @throws IllegalStateException if the call exceeds the provider's rate limit
   */
  synchronized String send(String to, String text) {
    int call = calls.incrementAndGet();
    long now = System.nanoTime();
    while (!accepted.isEmpty() && now - accepted.peekFirst() >= WINDOW_NANOS) {
      accepted.removeFirst();
    }
    if (accepted.size() >= LIMIT) {
      rejected.incrementAndGet();
      out.printf(
          "%s [sms]      rejected message to %s (call %d): too many requests%n",
          RateLimitExample.timestamp(), to, call);
      throw new IllegalStateException("429 Too Many Requests");
    }
    accepted.addLast(now);
    out.printf(
        "%s [sms]      sent \"%s\" to %s (call %d)%n",
        RateLimitExample.timestamp(), text, to, call);
    return "msg_" + call;
  }

  /** The number of calls the provider rejected because of its rate limit. */
  int rejected() {
    return rejected.get();
  }

  /** The number of calls, accepted or not. */
  int calls() {
    return calls.get();
  }
}
