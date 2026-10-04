package org.bikesched.event;

import java.io.Serial;
import org.bikesched.BikeschedBuilder;
import org.bikesched.BikeschedException;

/**
 * Thrown by {@link EventWait#receive(EventHandler)} when a wait without its own {@link
 * EventWait#timeout(java.time.Duration, TimeoutHandler) timeout} exceeded the node's {@link
 * BikeschedBuilder#defaultEventTimeout(java.time.Duration) default event timeout}. The job then
 * fails without further attempts ({@code FAILED}, with this exception's message as {@code
 * last_error}), even if the handler catches this exception: retrying would time out again.
 *
 * <p>To react to a timeout instead, give the wait its own timeout handler.
 */
public final class EventTimeoutException extends BikeschedException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the detail message
   */
  public EventTimeoutException(String message) {
    super(message);
  }
}
