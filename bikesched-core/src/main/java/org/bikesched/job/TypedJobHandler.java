// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * Handles jobs of a {@link JobType}, receiving the payload already mapped to the type's payload
 * class. Like a {@link JobHandler}, it is called for every attempt (and replay) of the job.
 *
 * @param <T> the payload type
 */
@FunctionalInterface
public interface TypedJobHandler<T extends @Nullable Object> {

  /**
   * Handles a job.
   *
   * @param job the job being executed
   * @param payload the job's payload mapped by the {@code JsonMapper}, {@code null} if it has none
   * @throws Exception to fail the attempt
   */
  void handle(Job job, @Nullable T payload) throws Exception;
}
