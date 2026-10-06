// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers for the executors of a node. */
public final class ExecutorUtil {

  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

  private static final Logger LOG = LoggerFactory.getLogger(ExecutorUtil.class);

  private ExecutorUtil() {}

  /**
   * Shuts the executor down and waits up to 10 seconds for its tasks, then interrupts them.
   *
   * @param executor the executor
   * @param name its name, for logging
   */
  public static void shutdownAndAwait(ExecutorService executor, String name) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.warn(
            "bikesched {} did not terminate within {}, interrupting it", name, SHUTDOWN_TIMEOUT);
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
