// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An event builder published durably as a step of a job.
 *
 * @param <P> the payload type
 */
public interface StepEventBuilder<P extends @Nullable Object> extends EventBuilder<P> {
  /**
   * Sets the durable step name; defaults to {@code publish <eventName>}.
   *
   * @param stepName durable step name
   * @return this builder
   */
  StepEventBuilder<P> stepName(String stepName);

  @Override
  StepEventBuilder<P> payload(@Nullable P payload);

  @Override
  StepEventBuilder<P> retention(java.time.Duration retention);

  /**
   * Publishes the event exactly once as a durable step: the first run publishes it, later runs of
   * the job find the step and return the same event id without publishing again.
   *
   * @return the id of the published event, the same on every run of the job
   */
  @Override
  UUID publish();
}
