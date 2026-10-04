package org.bikesched.job;

/**
 * Handles jobs of a {@link JobType}, receiving the payload already mapped to the type's payload
 * class. Like a {@link JobHandler}, it is called for every attempt (and replay) of the job.
 *
 * @param <T> the payload type
 */
@FunctionalInterface
public interface TypedJobHandler<T> {

  /**
   * Handles a job.
   *
   * @param job the job being executed
   * @param payload the job's payload mapped by the {@code JsonMapper}, {@code null} if it has none
   * @throws Exception to fail the attempt
   */
  void handle(Job job, T payload) throws Exception;
}
