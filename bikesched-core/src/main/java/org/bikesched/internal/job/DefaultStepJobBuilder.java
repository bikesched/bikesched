// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.StepJobBuilder;
import org.jspecify.annotations.Nullable;

/** The {@link StepJobBuilder} implementation, starting the child as a step of its parent. */
final class DefaultStepJobBuilder<P extends @Nullable Object>
    extends BaseJobBuilder<StepJobBuilder<P>, P> implements StepJobBuilder<P> {

  private final DefaultJob parent;
  private @Nullable String stepName;
  private @Nullable CompensationHandler<? super UUID> compensation;

  DefaultStepJobBuilder(
      DefaultJob parent,
      String type,
      Class<P> payloadType,
      JsonMapper jsonMapper,
      Scheduler scheduler) {
    super(type, payloadType, jsonMapper, scheduler);
    this.parent = parent;
  }

  @Override
  StepJobBuilder<P> self() {
    return this;
  }

  @Override
  public StepJobBuilder<P> stepName(String stepName) {
    ValidationUtil.requireName(stepName, "step name");
    this.stepName = stepName;
    return this;
  }

  @Override
  public StepJobBuilder<P> compensateWith(CompensationHandler<? super UUID> compensation) {
    this.compensation = Objects.requireNonNull(compensation, "compensation");
    return this;
  }

  @Override
  public UUID schedule() {
    return schedule(Instant.now());
  }

  @Override
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    scheduler.requireHandler(type, null);
    return parent.startChildJob(stepName, newJob(at), compensation);
  }

  @Override
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }
}
