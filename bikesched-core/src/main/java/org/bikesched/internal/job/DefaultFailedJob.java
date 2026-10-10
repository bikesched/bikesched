// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.UUID;
import org.bikesched.job.FailedJob;
import org.bikesched.job.JobState;

/** The {@link FailedJob} handed to a job's failure handler: a view of the claimed FAILING job. */
final class DefaultFailedJob implements FailedJob {

  private final DefaultJob job;

  DefaultFailedJob(DefaultJob job) {
    this.job = job;
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
  public String payload() {
    return job.payload();
  }

  @Override
  public <T> T payload(Class<T> type) {
    return job.payload(type);
  }

  @Override
  public JobState state() {
    return JobState.valueOf(job.failingState());
  }

  @Override
  public String error() {
    return job.lastError();
  }

  @Override
  public String toString() {
    return "FailedJob[id="
        + job.id()
        + ", type="
        + job.type()
        + ", state="
        + job.failingState()
        + ", error="
        + job.lastError()
        + "]";
  }
}
