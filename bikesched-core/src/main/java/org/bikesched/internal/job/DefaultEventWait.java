package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import org.bikesched.event.EventHandler;
import org.bikesched.event.EventTimeoutException;
import org.bikesched.event.EventWait;
import org.bikesched.event.TimeoutHandler;

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
  public <T> T receive(Class<T> resultType, EventHandler<? extends T> handler) throws Exception {
    Objects.requireNonNull(resultType, "resultType");
    Objects.requireNonNull(handler, "handler");
    return receiveWithDefaultTimeout(job.reader(resultType), handler);
  }

  @Override
  public <T> T receive(Class<T> payloadType) {
    Objects.requireNonNull(payloadType, "payloadType");
    return unchecked(() -> receiveWithDefaultTimeout(job.reader(payloadType), null));
  }

  /** Waits with the node's default event timeout, if any: the job fails once it has passed. */
  private <T> T receiveWithDefaultTimeout(
      Function<String, ? extends T> read, EventHandler<? extends T> handler) throws Exception {
    Duration defaultTimeout = job.defaultEventTimeout();
    return job.awaitEvent(
        stepName,
        eventName,
        eventKey,
        defaultTimeout,
        read,
        handler,
        () -> {
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
        });
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
    public <R extends T> T receive(Class<R> resultType, EventHandler<? extends R> handler)
        throws Exception {
      Objects.requireNonNull(resultType, "resultType");
      Objects.requireNonNull(handler, "handler");
      return await(wait.job.reader(resultType), handler);
    }

    @Override
    public T receive(Class<? extends T> payloadType) throws Exception {
      Objects.requireNonNull(payloadType, "payloadType");
      return await(wait.job.reader(payloadType), null);
    }

    private T await(Function<String, ? extends T> read, EventHandler<? extends T> handler)
        throws Exception {
      return wait.job.<T>awaitEvent(
          wait.stepName,
          wait.eventName,
          wait.eventKey,
          timeout,
          read,
          handler,
          timeoutHandler::handle);
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
