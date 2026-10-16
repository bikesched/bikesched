// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job.claim;

import java.time.Duration;
import org.bikesched.RetryBackoff;
import org.bikesched.event.listener.EventListener;
import org.bikesched.job.listener.JobListener;
import org.jspecify.annotations.Nullable;

/**
 * The node settings the {@link JobPoller} uses.
 *
 * @param nodeName the node's name, for logging
 * @param maxConcurrentJobs number of jobs running at the same time at most
 * @param pollInterval delay between two polls (the safety net when the node listens), and how far
 *     its next-due query looks ahead
 * @param listenForDueJobs whether the node listens for the notifications of due jobs
 * @param defaultJobTimeout timeout of jobs without their own; {@code null} for none
 * @param defaultEventTimeout timeout of event waits without their own; {@code null} for none
 * @param eventRetention how long events published by the jobs are kept, unless they set their own
 * @param retryBackoff delay of the retry after a failed attempt
 * @param failureHandlerAttempts how often a failure handler runs before it is given up
 * @param hardTimeoutGrace how long after its timeout an attempt whose handler ignores the interrupt
 *     is abandoned; {@code null} for no hard timeout
 * @param jobListener observes the jobs the node runs
 * @param eventListener observes the events the jobs publish
 */
public record JobPollerSettings(
    String nodeName,
    int maxConcurrentJobs,
    Duration pollInterval,
    boolean listenForDueJobs,
    @Nullable Duration defaultJobTimeout,
    @Nullable Duration defaultEventTimeout,
    Duration eventRetention,
    RetryBackoff retryBackoff,
    int failureHandlerAttempts,
    @Nullable Duration hardTimeoutGrace,
    JobListener jobListener,
    EventListener eventListener) {}
