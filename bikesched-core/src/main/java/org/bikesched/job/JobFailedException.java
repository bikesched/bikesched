// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.io.Serial;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link JobResult#orElseThrow()} when a child job did not succeed, or the wait for it
 * timed out, and by {@link JobBatch#await()}, {@link JobBatch#await(java.time.Duration)} and {@link
 * JobBatchResult#orElseThrow()} for the items of a batch.
 */
public final class JobFailedException extends BikeschedException {

  @Serial private static final long serialVersionUID = 1L;

  /** The child's id. */
  private final UUID jobId;

  /** The outcome; not serialized (records of a sealed interface, not {@code Serializable}). */
  private final transient @Nullable JobResult<?> result;

  /**
   * Creates the exception.
   *
   * @param result the outcome of the child job, not {@link JobResult.Succeeded}
   */
  public JobFailedException(JobResult<?> result) {
    this(describe(result), result);
  }

  /**
   * Creates the exception with a message of its own, e.g. naming the items of a batch that did not
   * succeed.
   *
   * @param message the message
   * @param result the outcome of the (first) child job that did not succeed
   */
  public JobFailedException(String message, JobResult<?> result) {
    super(message);
    this.jobId = result.jobId();
    this.result = result;
  }

  /**
   * Returns the id of the child job.
   *
   * @return the child's id
   */
  public UUID jobId() {
    return jobId;
  }

  /**
   * Returns the outcome of the child job.
   *
   * @return the outcome; {@code null} after deserialization
   */
  public @Nullable JobResult<?> result() {
    return result;
  }

  private static String describe(JobResult<?> result) {
    return switch (result) {
      case JobResult.Succeeded<?> s -> "Job " + s.jobId() + " succeeded";
      case JobResult.Failed<?> f -> "Job " + f.jobId() + " failed: " + f.error();
      case JobResult.Cancelled<?> c ->
          "Job " + c.jobId() + " was cancelled" + (c.message() == null ? "" : ": " + c.message());
      case JobResult.CompensationFailed<?> c ->
          "Compensating job " + c.jobId() + " failed: " + c.error();
      case JobResult.TimedOut<?> t -> "Waiting for job " + t.jobId() + " timed out";
    };
  }
}
