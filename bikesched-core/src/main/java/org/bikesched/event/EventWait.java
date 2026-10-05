package org.bikesched.event;

import java.time.Duration;
import org.bikesched.BikeschedBuilder;
import org.bikesched.BikeschedException;
import org.bikesched.JsonMapper;

/**
 * A wait for an event as a durable step of a job, obtained via {@link
 * org.bikesched.job.Job#waitForEvent(String, String, String)}. Optionally {@link #timeout(Duration,
 * TimeoutHandler) limited in time}; one of the {@code receive} methods then waits:
 *
 * <pre>{@code
 * Receipt receipt = job.waitForEvent("await-payment", "payment-received", order.id())
 *         .timeout(Duration.ofDays(3), () -> Receipt.missing())   // optional
 *         .receive(Receipt.class, event -> Receipt.of(event.payload(Payment.class)));
 * }</pre>
 *
 * <p>Like a step, the wait stores its result: once the event is received, the event handler maps
 * it, the result is stored with the step, and later runs of the job's handler return the stored
 * result without running the event handler again. {@code receive} returns right away if the event
 * was already received (in this or an earlier run of the job's handler) or was published before;
 * otherwise the job's attempt ends there (the method does not return) and the job is suspended
 * until the event is published or the timeout passed. Not thread-safe.
 */
public interface EventWait {

  /**
   * Limits the wait (optional): once the given time, counted from when the step first started
   * waiting, has passed without the event, the job runs again and {@code receive} returns the
   * result of the timeout handler instead, also on later runs. Without it, the node's {@link
   * BikeschedBuilder#defaultEventTimeout(Duration) default event timeout} applies, if any.
   *
   * <p>The timeout handler determines the result type, which the event handler's result must match.
   * If it only returns {@code null}, state the type explicitly: {@code .<Payment>timeout(d, () ->
   * null)}. Its result is not stored: it runs every time the job's handler reaches the timed-out
   * wait.
   *
   * @param timeout how long to wait at most, positive
   * @param handler produces the result if the wait timed out
   * @param <T> the result type
   * @return the wait with a timeout
   * @throws IllegalArgumentException if the timeout is not positive
   */
  <T> Timed<T> timeout(Duration timeout, TimeoutHandler<T> handler);

  /**
   * Waits for the event, maps it with the handler and stores the result as the step's result.
   *
   * @param resultType the type of the handler's result, to read the stored result back on later
   *     runs; mapped by the {@link JsonMapper}
   * @param handler maps the received event, see {@link EventHandler#handle(Event)}
   * @param <T> the result type
   * @return the result of the handler, may be {@code null}
   * @throws Exception the exception thrown by the handler
   * @throws EventTimeoutException if the wait exceeded the node's {@link
   *     BikeschedBuilder#defaultEventTimeout(Duration) default event timeout}; the job fails
   * @throws IllegalStateException if the step name is used for another step of the job
   * @throws BikeschedException if the step cannot be read or stored
   */
  <T> T receive(Class<T> resultType, EventHandler<? extends T> handler) throws Exception;

  /**
   * Waits for the event and returns its payload converted by the {@link JsonMapper}; the payload is
   * stored as the step's result as it is.
   *
   * @param payloadType the type to convert the payload to
   * @param <T> the payload type
   * @return the payload, may be {@code null}
   * @throws EventTimeoutException if the wait exceeded the node's {@link
   *     BikeschedBuilder#defaultEventTimeout(Duration) default event timeout}; the job fails
   * @throws IllegalStateException if the step name is used for another step of the job
   * @throws BikeschedException if the step cannot be read or stored
   */
  <T> T receive(Class<T> payloadType);

  /**
   * A wait for an event with a timeout, see {@link EventWait#timeout(Duration, TimeoutHandler)}.
   *
   * @param <T> the result type, given by the timeout handler
   */
  interface Timed<T> {

    /**
     * Waits for the event at most until the timeout, maps it with the handler and stores the result
     * as the step's result; returns the result of the timeout handler instead if it was not
     * received in time.
     *
     * @param resultType the type of the handler's result, to read the stored result back on later
     *     runs; mapped by the {@link JsonMapper}
     * @param handler maps the received event, see {@link EventHandler#handle(Event)}
     * @param <R> the type of the handler's result
     * @return the result of the event handler or the timeout handler, may be {@code null}
     * @throws Exception the exception thrown by either handler
     * @throws IllegalStateException if the step name is used for another step of the job
     * @throws BikeschedException if the step cannot be read or stored
     */
    <R extends T> T receive(Class<R> resultType, EventHandler<? extends R> handler)
        throws Exception;

    /**
     * Waits for the event at most until the timeout and returns its payload converted by the {@link
     * JsonMapper} (stored as the step's result as it is); returns the result of the timeout handler
     * instead if it was not received in time.
     *
     * @param payloadType the type to convert the payload to
     * @return the payload or the result of the timeout handler, may be {@code null}
     * @throws Exception the exception thrown by the timeout handler
     * @throws IllegalStateException if the step name is used for another step of the job
     * @throws BikeschedException if the step cannot be read or stored
     */
    T receive(Class<? extends T> payloadType) throws Exception;
  }
}
