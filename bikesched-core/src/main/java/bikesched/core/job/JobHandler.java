package bikesched.core.job;

import bikesched.core.Bikesched;

/**
 * Executes jobs of one type, registered via {@link Bikesched#registerJobHandler(String,
 * JobHandler)}. Like a {@link Runnable}, but it receives the job being executed.
 *
 * <p>Returning normally marks the job {@code SUCCEEDED}. Throwing marks it {@code FAILED} once its
 * maximum number of attempts is used up and otherwise schedules it again for another attempt. Since
 * a job may be executed more than once (e.g. after its node was considered dead), handlers should
 * be idempotent.
 */
@FunctionalInterface
public interface JobHandler {

  /**
   * Executes the job. Called on a virtual thread.
   *
   * @param job the job to execute
   * @throws Exception if the job failed
   */
  void handle(Job job) throws Exception;
}
