// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal;

import java.time.Duration;
import java.util.List;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedBuilder;
import org.bikesched.RetryBackoff;
import org.bikesched.event.listener.EventListener;
import org.bikesched.job.RecurrenceParser;
import org.bikesched.job.listener.JobListener;

/**
 * Configuration of a node, assembled and validated by {@link BikeschedBuilder}.
 *
 * @param nodeName unique node name
 * @param heartbeatInterval interval between heartbeats
 * @param nodeTimeout a node without heartbeat for this long is considered dead: its running jobs
 *     are released and its name may be taken over
 * @param pollInterval interval between job polls
 * @param listenForDueJobs whether the node listens for due jobs and notifies the other nodes
 * @param notifyInterval how long the node collects due job types before notifying them
 * @param maxConcurrentJobs maximum number of jobs executed at the same time on this node
 * @param autoStartPolling whether job polling starts when the node is built; otherwise it starts
 *     with {@link Bikesched#start()}
 * @param migrateSchema whether first database use migrates rather than only validating history
 * @param defaultJobTimeout maximum duration of a job attempt for jobs without a timeout of their
 *     own; {@code null} for none
 * @param retryBackoff delay of the retry after a failed attempt
 * @param failureHandlerAttempts how often a failure handler runs before it is given up
 * @param eventRetention how long events published through this node are kept for steps that start
 *     waiting later, unless the event sets its own retention
 * @param defaultEventTimeout maximum wait for an event for waits without a timeout of their own;
 *     {@code null} for none
 * @param nodeCleanUpInterval interval between node clean-ups (releases jobs of dead nodes)
 * @param eventCleanUpInterval interval between event clean-ups (deletes expired events)
 * @param jobHistoryRetention how long job history entries are kept; {@code null} if the node does
 *     not record job history
 * @param jobHistoryCleanUpInterval interval between job history clean-ups (deletes old entries)
 * @param jobRetention how long ended jobs are kept
 * @param jobCleanUpInterval interval between job clean-ups (deletes ended jobs)
 * @param statementTimeout maximum duration of a database statement; {@code null} to leave it to the
 *     data source
 * @param hardTimeoutGrace how long after its timeout an attempt whose handler ignores the interrupt
 *     is abandoned; {@code null} for no hard timeout
 * @param jobListener observes the jobs the node runs
 * @param eventListener observes the events published through the node
 * @param recurrenceParsers parse the recurrence expressions of {@code schedule(String)}
 */
public record Settings(
    String nodeName,
    Duration heartbeatInterval,
    Duration nodeTimeout,
    Duration pollInterval,
    boolean listenForDueJobs,
    Duration notifyInterval,
    int maxConcurrentJobs,
    boolean autoStartPolling,
    boolean migrateSchema,
    Duration defaultJobTimeout,
    RetryBackoff retryBackoff,
    int failureHandlerAttempts,
    Duration eventRetention,
    Duration defaultEventTimeout,
    Duration nodeCleanUpInterval,
    Duration eventCleanUpInterval,
    Duration jobHistoryRetention,
    Duration jobHistoryCleanUpInterval,
    Duration jobRetention,
    Duration jobCleanUpInterval,
    Duration statementTimeout,
    Duration hardTimeoutGrace,
    JobListener jobListener,
    EventListener eventListener,
    List<RecurrenceParser> recurrenceParsers) {

  /** Copies the recurrence parsers. */
  public Settings {
    recurrenceParsers = List.copyOf(recurrenceParsers);
  }
}
