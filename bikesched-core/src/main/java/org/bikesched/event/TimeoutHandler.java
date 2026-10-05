package org.bikesched.event;

import org.bikesched.job.Job;
import org.bikesched.job.StepHandler;

/**
 * Handles a wait for an event that timed out, see {@link EventWait#timeout(java.time.Duration,
 * TimeoutHandler)}.
 *
 * @param <T> the type of the result
 */
@FunctionalInterface
public interface TimeoutHandler<T> {

  /**
   * Handles the timed out wait. Runs every time the job's handler reaches the wait after its
   * deadline passed, also when the job's handler runs again later, so it should only produce a
   * fallback result or decide what happens to the job (e.g. {@link Job#cancel(String)}); put side
   * effects into {@link Job#step(String, Class, StepHandler) steps}.
   *
   * @return the result returned by {@link EventWait.Timed#receive(Class)}, may be {@code null}
   * @throws Exception to fail the job's attempt
   */
  T handle() throws Exception;
}
