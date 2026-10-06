package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.RetryBackoff;

/**
 * The node settings the {@link JobPoller} uses.
 *
 * @param nodeName the node's name, for logging
 * @param maxConcurrentJobs number of jobs running at the same time at most
 * @param pollInterval delay between two polls
 * @param defaultJobTimeout timeout of jobs without their own; {@code null} for none
 * @param defaultEventTimeout timeout of event waits without their own; {@code null} for none
 * @param retryBackoff delay of the retry after a failed attempt
 * @param hardTimeoutGrace how long after its timeout an attempt whose handler ignores the interrupt
 *     is abandoned; {@code null} for no hard timeout
 */
public record JobPollerSettings(
    String nodeName,
    int maxConcurrentJobs,
    Duration pollInterval,
    Duration defaultJobTimeout,
    Duration defaultEventTimeout,
    RetryBackoff retryBackoff,
    Duration hardTimeoutGrace) {

  /** Checks the required settings. */
  public JobPollerSettings {
    Objects.requireNonNull(nodeName, "nodeName");
    Objects.requireNonNull(pollInterval, "pollInterval");
    Objects.requireNonNull(retryBackoff, "retryBackoff");
  }
}
