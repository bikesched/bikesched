package org.bikesched.internal;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedBuilder;
import org.bikesched.RetryBackoff;
import org.bikesched.internal.event.EventCleanUp;
import org.bikesched.internal.util.ValidationUtil;

/**
 * Validated configuration of a {@link DefaultBikesched} node, assembled by {@link
 * BikeschedBuilder}.
 *
 * @param nodeName unique node name
 * @param heartbeatInterval interval between heartbeats
 * @param nodeTimeout a node without heartbeat for this long is considered dead: its running jobs
 *     are released and its name may be taken over
 * @param pollInterval interval between job polls
 * @param maxConcurrentJobs maximum number of jobs executed at the same time on this node
 * @param autoStartPolling whether job polling starts when the node is built; otherwise it starts
 *     with {@link Bikesched#startPolling()}
 * @param defaultJobTimeout maximum duration of a job attempt for jobs without a timeout of their
 *     own; {@code null} for none
 * @param retryBackoff delay of the retry after a failed attempt
 * @param eventRetention how long published events are kept for steps that start waiting later
 * @param defaultEventTimeout maximum wait for an event for waits without a timeout of their own;
 *     {@code null} for none
 * @param nodeCleanUpInterval interval between node clean-ups ({@link NodeCleanUp})
 * @param eventCleanUpInterval interval between event clean-ups ({@link EventCleanUp})
 */
public record Settings(
    String nodeName,
    Duration heartbeatInterval,
    Duration nodeTimeout,
    Duration pollInterval,
    int maxConcurrentJobs,
    boolean autoStartPolling,
    Duration defaultJobTimeout,
    RetryBackoff retryBackoff,
    Duration eventRetention,
    Duration defaultEventTimeout,
    Duration nodeCleanUpInterval,
    Duration eventCleanUpInterval) {

  /** Validates the settings. */
  public Settings {
    ValidationUtil.requireName(nodeName, "node name");
    Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
    Objects.requireNonNull(nodeTimeout, "nodeTimeout");
    Objects.requireNonNull(pollInterval, "pollInterval");
    Objects.requireNonNull(retryBackoff, "retryBackoff");
    Objects.requireNonNull(eventRetention, "eventRetention");
    Objects.requireNonNull(nodeCleanUpInterval, "nodeCleanUpInterval");
    Objects.requireNonNull(eventCleanUpInterval, "eventCleanUpInterval");
    if (maxConcurrentJobs < 1) {
      throw new IllegalArgumentException("maxConcurrentJobs must be >= 1: " + maxConcurrentJobs);
    }
    if (nodeTimeout.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
      throw new IllegalArgumentException(
          "nodeTimeout ("
              + nodeTimeout
              + ") must be at least twice the heartbeatInterval ("
              + heartbeatInterval
              + ")");
    }
  }
}
