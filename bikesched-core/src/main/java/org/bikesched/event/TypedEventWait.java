package org.bikesched.event;

import java.time.Duration;

/**
 * A wait for an event of an {@link EventType}, returned by {@code Job.waitForEvent(String,
 * EventType, String)}: like {@link EventWait}, but {@link #receive()} returns the payload mapped to
 * the type's payload class.
 *
 * @param <T> the payload type
 */
public interface TypedEventWait<T> {

  /**
   * Stops waiting once the timeout has passed (counted from when the job started waiting): {@link
   * #receive()} then returns the handler's result instead of an event's payload.
   *
   * @param timeout how long to wait, positive
   * @param handler produces the result once the timeout has passed, e.g. {@code () -> null}
   * @return this wait
   */
  TypedEventWait<T> timeout(Duration timeout, TimeoutHandler<? extends T> handler);

  /**
   * Returns the event's payload, suspending the job until the event is published (see {@link
   * EventWait}).
   *
   * @return the payload, {@code null} if the event has none; or the timeout handler's result
   * @throws Exception from the timeout handler
   */
  T receive() throws Exception;
}
