// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * Handles failed jobs of a {@link TypedJob}, receiving the payload mapped to the type's payload
 * class; like a {@link JobFailureHandler} otherwise.
 *
 * @param <T> the payload type
 * @see org.bikesched.Bikesched#registerJobFailureHandler(TypedJob, TypedJobFailureHandler)
 */
@FunctionalInterface
public interface TypedJobFailureHandler<T extends @Nullable Object> {

  /**
   * Handles the failed job.
   *
   * @param job the failed job, with the stored error
   * @param payload the job's payload mapped by the {@code JsonMapper}, {@code null} if it has none
   * @throws Exception if the handling failed; it runs again
   */
  void handle(FailedJob job, @Nullable T payload) throws Exception;
}
