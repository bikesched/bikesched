package bikesched.core;

/**
 * Handles an event a job waited for, see {@link EventWait#receive(EventHandler)}.
 *
 * @param <T> the type of the result
 */
@FunctionalInterface
public interface EventHandler<T> {

  /**
   * Handles the received event. Runs every time the job's handler reaches the wait after the event
   * was received, also when the job's handler runs again later (e.g. after another wait or a
   * retry), so it should only inspect or map the event; put side effects into {@link
   * Job#step(String, Class, StepHandler) steps}.
   *
   * @param event the received event
   * @return the result returned by {@code receive}, may be {@code null}
   * @throws Exception to fail the job's attempt
   */
  T handle(Event event) throws Exception;
}
