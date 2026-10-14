// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.event.EventWait;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.StepFailureHandler;

/**
 * The {@link EventWait} implementation, waiting as a step of a {@link DefaultJob}.
 *
 * @param <T> the payload type
 */
final class DefaultEventWait<T> implements EventWait<T> {

  private final DefaultJob job;
  private final String stepName;
  private final String eventName;
  private final String eventKey;
  private final Class<T> payloadType;
  private Duration timeout;
  private StepFailureHandler<T> failureHandler;

  DefaultEventWait(
      DefaultJob job, String stepName, String eventName, String eventKey, Class<T> payloadType) {
    this.job = Objects.requireNonNull(job, "job");
    this.stepName = stepName;
    this.eventName = eventName;
    this.eventKey = eventKey;
    this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
  }

  @Override
  public EventWait<T> timeout(Duration timeout) {
    ValidationUtil.requirePositive(timeout, "timeout");
    this.timeout = timeout;
    return this;
  }

  @Override
  public EventWait<T> onFailure(StepFailureHandler<T> handler) {
    this.failureHandler = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /** Waits with its own timeout, or else the node's default event timeout, if any. */
  @Override
  public T receive() {
    Duration effective = timeout != null ? timeout : job.defaultEventTimeout();
    String timeoutMessage =
        "Waiting for event "
            + eventName
            + "/"
            + eventKey
            + " in step '"
            + stepName
            + "' timed out"
            + (effective == null ? "" : " after " + effective)
            + (timeout == null && effective != null ? " (default event timeout)" : "");
    return job.awaitEventStep(
        stepName, eventName, eventKey, effective, payloadType, failureHandler, timeoutMessage);
  }
}
