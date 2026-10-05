package org.bikesched.event;

import org.bikesched.job.Job;
import org.bikesched.job.StepHandler;

/**
 * Maps an event a job waited for to the wait's result, see {@link EventWait#receive(Class,
 * EventHandler)}.
 *
 * @param <T> the type of the result
 */
@FunctionalInterface
public interface EventHandler<T> {

  /**
   * Maps the received event. Like a {@link Job#step(String, Class, StepHandler) step}, it runs once
   * the event was received and its result is stored with the wait: later runs of the job's handler
   * return the stored result without calling it again. If it throws, or the attempt ends before the
   * result is stored, it runs again in the next attempt.
   *
   * @param event the received event
   * @return the result returned by {@code receive}, may be {@code null}; mapped by the {@code
   *     JsonMapper} to store it
   * @throws Exception to fail the job's attempt
   */
  T handle(Event event) throws Exception;
}
