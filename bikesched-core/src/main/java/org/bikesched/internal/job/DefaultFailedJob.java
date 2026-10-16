// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.UUID;
import org.bikesched.internal.database.JobPhase;
import org.bikesched.job.FailedJob;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/** The {@link FailedJob} handed to a job's failure handler: a view of the claimed FAILING job. */
final class DefaultFailedJob implements FailedJob {

  private final DefaultJob job;
  private final JobState state;

  /**
   * @param job the claimed job
   * @param failing what it was claimed for: the state it ends in once its failure handler ran
   */
  DefaultFailedJob(DefaultJob job, JobPhase.HandleFailure failing) {
    this.job = job;
    this.state = failing.endState();
  }

  @Override
  public UUID id() {
    return job.id();
  }

  @Override
  public String type() {
    return job.type();
  }

  @Override
  public @Nullable String payload() {
    return job.payload();
  }

  @Override
  public <T extends @Nullable Object> @Nullable T payload(Class<T> type) {
    return job.payload(type);
  }

  @Override
  public JobState state() {
    return state;
  }

  @Override
  public @Nullable String error() {
    return job.lastError();
  }

  @Override
  public String toString() {
    return "FailedJob[id="
        + job.id()
        + ", type="
        + job.type()
        + ", state="
        + state
        + ", error="
        + job.lastError()
        + "]";
  }
}
