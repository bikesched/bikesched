// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/**
 * Handles failed jobs of a {@link JobType}, receiving the payload mapped to the type's payload
 * class; like a {@link JobFailureHandler} otherwise.
 *
 * @param <T> the payload type
 * @see org.bikesched.Bikesched#registerJobFailureHandler(JobType, TypedJobFailureHandler)
 */
@FunctionalInterface
public interface TypedJobFailureHandler<T> {

  /**
   * Handles the failed job.
   *
   * @param job the failed job, with the stored error
   * @param payload the job's payload mapped by the {@code JsonMapper}, {@code null} if it has none
   * @throws Exception if the handling failed; it runs again
   */
  void handle(FailedJob job, T payload) throws Exception;
}
