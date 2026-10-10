// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.time.Duration;
import org.bikesched.BikeschedBuilder;
import org.bikesched.RetryBackoff;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code bikesched.*} configuration properties, applied to the {@link BikeschedBuilder} by the
 * starter. Every option is optional and defaults to the builder's own default, so an application
 * that sets nothing gets the same node it would build by hand.
 *
 * <pre>{@code
 * bikesched:
 *   node-name: orders-1            # unique per instance; defaults to <spring.application.name>-<host name>
 *   poll-interval: 1s
 *   max-concurrent-jobs: 20
 *   retry-backoff:
 *     exponential:
 *       initial: 5s
 *       max: 10m
 *   auto-start:
 *     enabled: false               # e.g. a web instance that only schedules jobs
 * }</pre>
 *
 * @param nodeName unique name of the node in the cluster; see {@link NodeNames}
 * @param dataSource name of the {@code DataSource} bean to use if the context has several
 * @param migrateOnStartup whether the schema is migrated and validated at startup instead of on
 *     first use; defaults to {@code true}
 * @param autoStart whether the node starts automatically with the application
 * @param metrics whether a {@code MicrometerJobListener} and a {@code MicrometerEventListener} are
 *     registered when {@code bikesched-micrometer} and a {@code MeterRegistry} are present;
 *     defaults to {@code true}
 * @param maxConcurrentJobs how many jobs the node executes at the same time
 * @param pollInterval delay between two polls for due jobs
 * @param listenForDueJobs whether the node listens for due jobs and notifies the other nodes
 * @param notifyInterval how long the node collects due job types before notifying them
 * @param heartbeatInterval interval between the heartbeats the node writes
 * @param nodeTimeout time without heartbeat after which a node is considered dead
 * @param defaultJobTimeout timeout for jobs scheduled without one
 * @param defaultEventTimeout timeout for event waits without one
 * @param retryBackoff delay before retrying a failed attempt
 * @param failureHandlerAttempts how often a failure handler runs before it is given up
 * @param eventRetention how long published events are kept for steps waiting later
 * @param eventCleanUpInterval how often expired events are deleted
 * @param nodeCleanUpInterval how often the node releases the jobs of dead nodes
 * @param jobHistoryRetention how long the job history is kept; enables it
 * @param jobHistoryCleanUpInterval how often the job history is cleaned up
 * @param jobRetention how long ended jobs are kept
 * @param jobCleanUpInterval how often ended jobs are deleted
 * @param statementTimeout maximum duration of a database statement
 * @param hardTimeoutGrace how long after its timeout an attempt that ignores the interrupt is
 *     abandoned
 */
@ConfigurationProperties("bikesched")
public record BikeschedProperties(
    String nodeName,
    String dataSource,
    Boolean migrateOnStartup,
    AutoStart autoStart,
    Metrics metrics,
    Integer maxConcurrentJobs,
    Duration pollInterval,
    Boolean listenForDueJobs,
    Duration notifyInterval,
    Duration heartbeatInterval,
    Duration nodeTimeout,
    Duration defaultJobTimeout,
    Duration defaultEventTimeout,
    Backoff retryBackoff,
    Integer failureHandlerAttempts,
    Duration eventRetention,
    Duration eventCleanUpInterval,
    Duration nodeCleanUpInterval,
    Duration jobHistoryRetention,
    Duration jobHistoryCleanUpInterval,
    Duration jobRetention,
    Duration jobCleanUpInterval,
    Duration statementTimeout,
    Duration hardTimeoutGrace) {

  /**
   * Whether the node starts automatically with the application.
   *
   * @param enabled {@code false} to build the node without registering it: it executes no jobs,
   *     sends no heartbeats and only schedules jobs; call {@code start()} to start it later
   */
  public record AutoStart(Boolean enabled) {}

  /**
   * Micrometer integration.
   *
   * @param enabled {@code false} to register no automatic {@code MicrometerJobListener} and {@code
   *     MicrometerEventListener}
   */
  public record Metrics(Boolean enabled) {}

  /**
   * Delay before retrying a failed attempt of a job; either a fixed delay or an exponential one.
   *
   * @param fixed the same delay before every retry
   * @param exponential delay that grows with the number of attempts
   */
  public record Backoff(Duration fixed, Exponential exponential) {

    /**
     * Doubling delay, each delay randomly shortened by up to half.
     *
     * @param initial delay after the first failed attempt
     * @param max upper bound of the delay
     */
    public record Exponential(Duration initial, Duration max) {}
  }

  /** Applies the set options to the builder; unset ones keep the builder's default. */
  void applyTo(BikeschedBuilder builder) {
    if (nodeName != null) {
      builder.nodeName(nodeName);
    }
    if (maxConcurrentJobs != null) {
      builder.maxConcurrentJobs(maxConcurrentJobs);
    }
    if (pollInterval != null) {
      builder.pollInterval(pollInterval);
    }
    if (listenForDueJobs != null) {
      builder.listenForDueJobs(listenForDueJobs);
    }
    if (notifyInterval != null) {
      builder.notifyInterval(notifyInterval);
    }
    if (heartbeatInterval != null) {
      builder.heartbeatInterval(heartbeatInterval);
    }
    if (nodeTimeout != null) {
      builder.nodeTimeout(nodeTimeout);
    }
    if (defaultJobTimeout != null) {
      builder.defaultJobTimeout(defaultJobTimeout);
    }
    if (defaultEventTimeout != null) {
      builder.defaultEventTimeout(defaultEventTimeout);
    }
    if (retryBackoff != null) {
      builder.retryBackoff(backoff());
    }
    if (failureHandlerAttempts != null) {
      builder.failureHandlerAttempts(failureHandlerAttempts);
    }
    if (eventRetention != null) {
      builder.eventRetention(eventRetention);
    }
    if (eventCleanUpInterval != null) {
      builder.eventCleanUpInterval(eventCleanUpInterval);
    }
    if (nodeCleanUpInterval != null) {
      builder.nodeCleanUpInterval(nodeCleanUpInterval);
    }
    if (jobHistoryRetention != null) {
      builder.jobHistory(jobHistoryRetention);
    }
    if (jobHistoryCleanUpInterval != null) {
      builder.jobHistoryCleanUpInterval(jobHistoryCleanUpInterval);
    }
    if (jobRetention != null) {
      builder.jobRetention(jobRetention);
    }
    if (jobCleanUpInterval != null) {
      builder.jobCleanUpInterval(jobCleanUpInterval);
    }
    if (statementTimeout != null) {
      builder.statementTimeout(statementTimeout);
    }
    if (hardTimeoutGrace != null) {
      builder.hardTimeoutGrace(hardTimeoutGrace);
    }
  }

  /** Whether the schema is migrated and validated at startup; defaults to {@code true}. */
  boolean migrateOnStartupEnabled() {
    return migrateOnStartup == null || migrateOnStartup;
  }

  /** Whether the node starts with the application; defaults to {@code true}. */
  boolean autoStartEnabled() {
    return autoStart == null || autoStart.enabled() == null || autoStart.enabled();
  }

  /** Whether the automatic Micrometer listeners are registered; defaults to {@code true}. */
  boolean metricsEnabled() {
    return metrics == null || metrics.enabled() == null || metrics.enabled();
  }

  /** The configured backoff, validated. */
  private RetryBackoff backoff() {
    var backoff = retryBackoff;
    if (backoff.fixed() != null && backoff.exponential() != null) {
      throw new IllegalArgumentException(
          "bikesched.retry-backoff: set either fixed or exponential, not both");
    }
    if (backoff.fixed() != null) {
      return RetryBackoff.fixed(backoff.fixed());
    }
    if (backoff.exponential() != null) {
      var exponential = backoff.exponential();
      if (exponential.initial() == null || exponential.max() == null) {
        throw new IllegalArgumentException(
            "bikesched.retry-backoff.exponential: both initial and max are required");
      }
      return RetryBackoff.exponential(exponential.initial(), exponential.max());
    }
    throw new IllegalArgumentException("bikesched.retry-backoff: set either fixed or exponential");
  }
}
