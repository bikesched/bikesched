// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries a call that failed, e.g. recording an attempt's outcome after a database blip: without it
 * a job whose outcome could not be recorded stays {@code RUNNING} on a node that is alive and keeps
 * heartbeating, so nothing releases it.
 *
 * <p>Attempts run on the calling thread with a fixed delay in between; the call must be safe to
 * repeat (recording an outcome is: the claim's lock token makes the writes idempotent). Only the
 * last failure is thrown.
 */
public final class Retry {

  private static final Logger LOG = LoggerFactory.getLogger(Retry.class);

  private final int attempts;
  private final Duration delay;

  private Retry(int attempts, Duration delay) {
    this.attempts = attempts;
    this.delay = delay;
  }

  /**
   * Creates a retry.
   *
   * @param attempts how often the call is made at most
   * @param delay how long to wait between two attempts
   * @return the retry
   * @throws IllegalArgumentException if there is less than one attempt or the delay is negative
   */
  public static Retry of(int attempts, Duration delay) {
    if (attempts < 1) {
      throw new IllegalArgumentException("attempts must be at least 1, but was " + attempts);
    }
    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative, but was " + delay);
    }
    return new Retry(attempts, delay);
  }

  /**
   * Calls and, on a failure, calls again after the delay, until it succeeds or the attempts are
   * used.
   *
   * @param action what the call does, for the log, e.g. {@code "Completing job 42 failed"}
   * @param call the call
   * @return what the call returned
   * @throws RuntimeException the last failure if every attempt failed; a failure that is not
   *     retried when the calling thread is interrupted (the node is closing), with the interrupt
   *     flag set again
   */
  public <T> T call(String action, Supplier<T> call) {
    for (int attempt = 1; ; attempt++) {
      try {
        return call.get();
      } catch (RuntimeException e) {
        if (attempt >= attempts) {
          throw e;
        }
        LOG.warn(
            "{}, retrying in {} ms (attempt {} of {})",
            action,
            delay.toMillis(),
            attempt,
            attempts,
            e);
        try {
          Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt(); // closing: do not retry, the outcome is not recorded
          throw e;
        }
      }
    }
  }
}
