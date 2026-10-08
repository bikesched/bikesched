// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job.claim;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The poller's one timer: wakes it at the earliest due time it knows of. Confined to the poller
 * thread, like the polls themselves: {@link #arm} must only be called there, and the timer fires
 * there, so arming and firing can never race.
 */
final class WakeUpTimer {

  /** The shortest delay of a timer: due times closer than this fire after it. */
  static final Duration MIN_DELAY = Duration.ofMillis(10);

  private final ScheduledExecutorService pollerExecutor;
  private final Runnable onFire;

  /** The armed timer and its due time; {@code null} while none is armed. */
  private ScheduledFuture<?> timer;

  private Instant dueAt;

  /**
   * @param pollerExecutor the poller's single thread
   * @param onFire runs on the poller thread when the timer fires
   */
  WakeUpTimer(ScheduledExecutorService pollerExecutor, Runnable onFire) {
    this.pollerExecutor = Objects.requireNonNull(pollerExecutor, "pollerExecutor");
    this.onFire = Objects.requireNonNull(onFire, "onFire");
  }

  /**
   * Arms the timer for the due time unless it is armed for an earlier one. Poller thread only.
   *
   * @param at when to fire
   * @return whether the timer was (re-)armed
   */
  boolean arm(Instant at) {
    if (dueAt != null && !at.isBefore(dueAt)) {
      return false;
    }
    if (timer != null) {
      timer.cancel(false);
    }
    long delay = Math.max(MIN_DELAY.toMillis(), Duration.between(Instant.now(), at).toMillis());
    dueAt = at;
    timer = pollerExecutor.schedule(this::fire, delay, TimeUnit.MILLISECONDS);
    return true;
  }

  private void fire() {
    timer = null;
    dueAt = null;
    onFire.run();
  }
}
