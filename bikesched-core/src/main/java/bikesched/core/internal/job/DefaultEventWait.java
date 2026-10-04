package bikesched.core.internal.job;

import bikesched.core.event.Event;
import bikesched.core.event.EventHandler;
import bikesched.core.event.EventTimeoutException;
import bikesched.core.event.EventWait;
import bikesched.core.event.TimeoutHandler;
import java.time.Duration;
import java.util.Objects;

/** The {@link EventWait} implementation, waiting as a step of a {@link DefaultJob}. */
final class DefaultEventWait implements EventWait {

  private final DefaultJob job;
  private final String stepName;
  private final String eventName;
  private final String eventKey;

  DefaultEventWait(DefaultJob job, String stepName, String eventName, String eventKey) {
    this.job = Objects.requireNonNull(job, "job");
    this.stepName = stepName;
    this.eventName = eventName;
    this.eventKey = eventKey;
  }

  @Override
  public <T> Timed<T> timeout(Duration timeout, TimeoutHandler<T> handler) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
    return new DefaultTimed<>(this, timeout, Objects.requireNonNull(handler, "handler"));
  }

  @Override
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
      job.fail(message).remainingAttempts(0); // waiting again would time out again
      throw new EventTimeoutException(message);
    }
    return job.inStep(stepName, () -> handler.handle(event));
  }

  @Override
  public <T> T receive(Class<T> payloadType) {
    Objects.requireNonNull(payloadType, "payloadType");
    return unchecked(() -> receive(event -> event.payload(payloadType)));
  }

  /** The {@link EventWait.Timed} implementation. */
  private static final class DefaultTimed<T> implements Timed<T> {

    private final DefaultEventWait wait;
    private final Duration timeout;
    private final TimeoutHandler<T> timeoutHandler;

    private DefaultTimed(
        DefaultEventWait wait, Duration timeout, TimeoutHandler<T> timeoutHandler) {
      this.wait = wait;
      this.timeout = timeout;
      this.timeoutHandler = timeoutHandler;
    }

    @Override
    public T receive(EventHandler<? extends T> handler) throws Exception {
      Objects.requireNonNull(handler, "handler");
      Event event = wait.job.receiveEvent(wait.stepName, wait.eventName, wait.eventKey, timeout);
      return wait.job.inStep(
          wait.stepName, () -> event == null ? timeoutHandler.handle() : handler.handle(event));
    }

    @Override
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
