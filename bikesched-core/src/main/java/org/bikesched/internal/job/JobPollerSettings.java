// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.RetryBackoff;
import org.bikesched.internal.event.EventNotifier;
import org.bikesched.job.listener.JobListener;

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
 * @param jobListener observes the jobs the node runs
 * @param eventNotifier reports the events the jobs publish
 */
public record JobPollerSettings(
    String nodeName,
    int maxConcurrentJobs,
    Duration pollInterval,
    Duration defaultJobTimeout,
    Duration defaultEventTimeout,
    Duration eventRetention,
    RetryBackoff retryBackoff,
    Duration hardTimeoutGrace,
    JobListener jobListener,
    EventNotifier eventNotifier) {

  /** Checks the required settings. */
  public JobPollerSettings {
    Objects.requireNonNull(nodeName, "nodeName");
    Objects.requireNonNull(pollInterval, "pollInterval");
    Objects.requireNonNull(retryBackoff, "retryBackoff");
    Objects.requireNonNull(eventRetention, "eventRetention");
    Objects.requireNonNull(jobListener, "jobListener");
    Objects.requireNonNull(eventNotifier, "eventNotifier");
  }
}
