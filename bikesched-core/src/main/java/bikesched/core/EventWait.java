package bikesched.core;

import java.time.Duration;
import java.util.Objects;

/**
 * A wait for an event as a durable step of a job, obtained via {@link Job#waitForEvent(String,
 * String, String)}. Optionally {@link #timeout(Duration, TimeoutHandler) limited in time}; one of
 * the {@code receive} methods then waits:
 *
 * <pre>{@code
 * Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
 *         .timeout(Duration.ofDays(3), () -> Payment.missing())   // optional
 *         .receive(event -> event.payload(Payment.class));
 * }</pre>
 *
 * <p>{@code receive} returns right away if the event was already received (in this or an earlier
 * run of the job's handler) or was published before; otherwise the job's attempt ends there (the
 * method does not return) and the job is suspended until the event is published or the timeout
 * passed. Not thread-safe.
 */
public final class EventWait {

  private final DefaultJob job;
  private final String stepName;
  private final String eventName;
  private final String eventKey;

  EventWait(DefaultJob job, String stepName, String eventName, String eventKey) {
    this.job = Objects.requireNonNull(job, "job");
    this.stepName = stepName;
    this.eventName = eventName;
    this.eventKey = eventKey;
  }

  /**
   * Limits the wait (optional): once the given time, counted from when the step first started
   * waiting, has passed without the event, the job runs again and {@code receive} returns the
   * result of the timeout handler instead, also on later runs. Without it, the node's {@link
   * BikeschedBuilder#defaultEventTimeout(Duration) default event timeout} applies, if any.
   *
   * <p>The timeout handler determines the result type, which the event handler must match. If it
   * only returns {@code null}, state the type explicitly: {@code .<Payment>timeout(d, () -> null)}.
   *
   * @param timeout how long to wait at most, positive
   * @param handler produces the result if the wait timed out
   * @param <T> the result type
   * @return the wait with a timeout
   * @throws IllegalArgumentException if the timeout is not positive
   */
  public <T> Timed<T> timeout(Duration timeout, TimeoutHandler<T> handler) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
    return new Timed<>(this, timeout, Objects.requireNonNull(handler, "handler"));
  }

  /**
   * Waits for the event and passes it to the handler.
   *
   * @param handler handles the received event, see {@link EventHandler#handle(Event)}
   * @param <T> the type of the handler's result
   * @return the result of the handler, may be {@code null}
   * @throws Exception the exception thrown by the handler
   * @throws EventTimeoutException if the wait exceeded the node's {@link
   *     BikeschedBuilder#defaultEventTimeout(Duration) default event timeout}; the job fails
   * @throws IllegalStateException if the step name is used for another step of the job
   * @throws BikeschedException if the step cannot be read or stored
   */
  public <T> T receive(EventHandler<T> handler) throws Exception {
    Objects.requireNonNull(handler, "handler");
    Duration defaultTimeout = job.defaultEventTimeout();
    Event event = job.receiveEvent(stepName, eventName, eventKey, defaultTimeout);
    if (event == null) {
      String message =
          "Waiting for event "
              + eventName
              + "/"
              + eventKey
              + " in step '"
              + stepName
              + "' timed out"
              + (defaultTimeout == null
                  ? ""
                  : " after " + defaultTimeout + " (default event timeout)");
      job.fail(message).retries(0); // waiting again would time out again
      throw new EventTimeoutException(message);
    }
    return job.inStep(stepName, () -> handler.handle(event));
  }

  /**
   * Waits for the event and returns its payload converted by the {@link JsonMapper}; short for
   * {@code receive(event -> event.payload(payloadType))}.
   *
   * @param payloadType the type to convert the payload to
   * @param <T> the payload type
   * @return the payload, may be {@code null}
   * @throws EventTimeoutException if the wait exceeded the node's {@link
   *     BikeschedBuilder#defaultEventTimeout(Duration) default event timeout}; the job fails
   * @throws IllegalStateException if the step name is used for another step of the job
   * @throws BikeschedException if the step cannot be read or stored
   */
  public <T> T receive(Class<T> payloadType) {
    Objects.requireNonNull(payloadType, "payloadType");
    return unchecked(() -> receive(event -> event.payload(payloadType)));
  }

  /**
   * A wait for an event with a timeout, see {@link EventWait#timeout(Duration, TimeoutHandler)}.
   *
   * @param <T> the result type, given by the timeout handler
   */
  public static final class Timed<T> {

    private final EventWait wait;
    private final Duration timeout;
    private final TimeoutHandler<T> timeoutHandler;

    private Timed(EventWait wait, Duration timeout, TimeoutHandler<T> timeoutHandler) {
      this.wait = wait;
      this.timeout = timeout;
      this.timeoutHandler = timeoutHandler;
    }

    /**
     * Waits for the event at most until the timeout and passes it to the handler; returns the
     * result of the timeout handler instead if it was not received in time.
     *
     * @param handler handles the received event, see {@link EventHandler#handle(Event)}
     * @return the result of the event handler or the timeout handler, may be {@code null}
     * @throws Exception the exception thrown by either handler
     * @throws IllegalStateException if the step name is used for another step of the job
     * @throws BikeschedException if the step cannot be read or stored
     */
    public T receive(EventHandler<? extends T> handler) throws Exception {
      Objects.requireNonNull(handler, "handler");
      Event event = wait.job.receiveEvent(wait.stepName, wait.eventName, wait.eventKey, timeout);
      return wait.job.inStep(
          wait.stepName, () -> event == null ? timeoutHandler.handle() : handler.handle(event));
    }

    /**
     * Waits for the event at most until the timeout and returns its payload converted by the {@link
     * JsonMapper}; returns the result of the timeout handler instead if it was not received in
     * time.
     *
     * @param payloadType the type to convert the payload to
     * @return the payload or the result of the timeout handler, may be {@code null}
     * @throws Exception the exception thrown by the timeout handler
     * @throws IllegalStateException if the step name is used for another step of the job
     * @throws BikeschedException if the step cannot be read or stored
     */
    public T receive(Class<? extends T> payloadType) throws Exception {
      Objects.requireNonNull(payloadType, "payloadType");
      return receive(event -> event.payload(payloadType));
    }
  }

  /**
   * A call that only throws unchecked exceptions, though declared with {@code throws Exception}.
   */
  @FunctionalInterface
  private interface Call<T> {
    T call() throws Exception;
  }

  private static <T> T unchecked(Call<T> call) {
    try {
      return call.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("unexpected checked exception", e);
    }
  }
}
