// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.UUID;
import org.bikesched.job.FailingStep;

/**
 * The {@link FailingStep} handed to a step's failure handler; records a {@link #resolve(Object)}
 * for {@link DefaultJob} to check after the handler returned (it may resolve from other threads).
 */
final class DefaultFailingStep<T> implements FailingStep<T> {

  private final UUID jobId;
  private final String name;
  private final String error;
  private volatile boolean resolved;
  private volatile T result;

  DefaultFailingStep(UUID jobId, String name, String error) {
    this.jobId = jobId;
    this.name = name;
    this.error = error;
  }

  @Override
  public UUID jobId() {
    return jobId;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String error() {
    return error;
  }

  @Override
  public void resolve(T result) {
    this.result = result;
    this.resolved = true;
  }

  boolean isResolved() {
    return resolved;
  }

  T result() {
    return result;
  }

  @Override
  public String toString() {
    return "FailingStep[jobId=" + jobId + ", name=" + name + ", error=" + error + "]";
  }
}
