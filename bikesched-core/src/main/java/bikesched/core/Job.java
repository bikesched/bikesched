package bikesched.core;

import java.time.Duration;
import java.util.UUID;

/**
 * A job being executed, as seen by its {@link JobHandler}.
 *
 * <p>A handler can {@link #cancel() cancel} the job it executes: once the handler returns, the job
 * ends in state {@code CANCELLED} and is not retried, regardless of the remaining attempts.
 * Cancelling does not stop the handler, so return right after it:
 *
 * <pre>{@code
 * bikesched.registerHandler("send-mail", job -> {
 *     if (customerDeleted(job)) {
 *         job.cancel("customer deleted");
 *         return;
 *     }
 *     mailer.send(job.payload());
 * });
 * }</pre>
 *
 * <p>A handler can split its work into durable {@link #step(String, Class, StepHandler) steps}: a
 * step that completed is not executed again when the job is retried, its stored result is returned
 * instead.
 */
public interface Job {

  /**
   * Returns the job id.
   *
   * @return the job id
   */
  UUID id();

  /**
   * Returns the job type, which identifies the handler that executes the job.
   *
   * @return the job type
   */
  String type();

  /**
   * Returns the number of attempts including the current one.
   *
   * @return the attempt number, starting at 1
   */
  int attempts();

  /**
   * Returns the maximum number of attempts.
   *
   * @return the maximum number of attempts
   */
  int maxAttempts();

  /**
   * Returns the raw job payload.
   *
   * @return the payload as JSON text, may be {@code null}
   * @see #payload(Class)
   */
  String payload();

  /**
   * Returns the job payload converted by the configured {@link JsonMapper}:
   *
   * <pre>{@code
   * Order order = job.payload(Order.class);
   * }</pre>
   *
   * @param type the type to convert the payload to
   * @param <T> the type to convert the payload to
   * @return the payload, or {@code null} if the job has none
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default {@link JsonStringMapper} for types other than {@link String}
   */
  <T> T payload(Class<T> type);

  /**
   * Executes a durable step of this job, like a durable function: if the step already completed in
   * an earlier attempt of the job, its stored result is returned without executing the handler
   * again. Otherwise the handler is executed, and its result is stored in {@code bikesched_steps}
   * (committed right away) and returned. Results are converted to and from JSON by the configured
   * {@link JsonMapper}.
   *
   * <pre>{@code
   * bikesched.registerHandler("order", job -> {
   *     Order order = job.payload(Order.class);
   *     Payment payment = job.step("charge-card", Payment.class, step -> payments.charge(order));
   *     job.step("send-confirmation", Void.class, step -> {
   *         mailer.send(order, payment);
   *         return null;
   *     });
   * });
   * }</pre>
   *
   * <p>If the handler throws, the step is marked {@code FAILED} and the exception is rethrown,
   * which fails the job's attempt: on the next attempt, completed steps are skipped and the failed
   * step is executed again. Step names identify steps across attempts, so they must be unique
   * within the job and the handler must call its steps in a deterministic way. A step may still run
   * more than once (e.g. if the node dies before its result is stored), so steps should be
   * idempotent. Steps run on the calling thread, one at a time.
   *
   * @param name the step name, unique within the job
   * @param resultType the type of the step result, used to read a stored result back
   * @param handler executes the step and returns its result (may be {@code null})
   * @param <T> the type of the step result
   * @return the step result, may be {@code null}
   * @throws Exception the exception thrown by the handler
   * @throws BikeschedException if the step cannot be read or stored, e.g. because the mapped result
   *     is not valid JSON
   * @throws IllegalArgumentException if the name is blank
   */
  <T> T step(String name, Class<T> resultType, StepHandler<T> handler) throws Exception;

  /**
   * Executes a durable step whose result is raw JSON text, stored as is (without the {@link
   * JsonMapper}), like {@link #payload()}. See {@link #step(String, Class, StepHandler)}.
   *
   * @param name the step name, unique within the job
   * @param handler executes the step and returns its result as JSON text (may be {@code null})
   * @return the step result as JSON text, may be {@code null}
   * @throws Exception the exception thrown by the handler
   * @throws BikeschedException if the step cannot be read or stored, e.g. because the result is not
   *     valid JSON
   * @throws IllegalArgumentException if the name is blank
   */
  String step(String name, StepHandler<String> handler) throws Exception;

  /**
   * Starts waiting for an event, as a durable step; {@link EventWait#receive(EventHandler)} returns
   * it once the event published with the given name and key was received. If it has not been
   * published yet, the job's attempt ends in {@code receive} (it does not return) and the job is
   * suspended in state {@code WAITING}, using no thread and no concurrency slot. Once someone
   * {@link Bikesched#newEvent(String, String) publishes} the event, the job is scheduled again and
   * its handler runs again from the start: completed steps are skipped, and {@code receive} now
   * passes the event to the event handler and returns its result.
   *
   * <pre>{@code
   * bikesched.registerHandler("order", job -> {
   *     Order order = job.payload(Order.class);
   *     job.step("create-invoice", Invoice.class, step -> billing.createInvoice(order));
   *     Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
   *             .timeout(Duration.ofDays(3), () -> Payment.missing())   // optional
   *             .receive(event -> event.payload(Payment.class));
   *     job.step("ship", Void.class, step -> {
   *         shipping.ship(order, payment);
   *         return null;
   *     });
   * });
   *
   * // elsewhere, e.g. in a payment webhook
   * bikesched.newEvent("payment-received", orderId).payload(payment).publish();
   * }</pre>
   *
   * <p>Events published before the job starts waiting are not lost: they are kept for the {@link
   * BikeschedBuilder#eventRetention(Duration) retention period}, and the step receives the latest
   * one right away. An event is delivered to every step waiting for it. The received event is
   * stored with the step; the event handler runs whenever {@code receive} returns (see {@link
   * EventHandler#handle(Event)}), and if it throws, the job's attempt fails.
   *
   * <p>Because the job's handler runs again after the wait, code outside of steps must be cheap and
   * deterministic, and steps and waits must be called with the same names in the same order on
   * every run. Waiting does not use up an attempt. Do not catch the {@link Error} {@code receive}
   * throws to suspend the job; if the handler does anyway, the job is still suspended once the
   * handler returns ({@link #cancel() cancelling} or {@link #fail() failing} the attempt takes
   * precedence).
   *
   * @param stepName the step name, unique within the job
   * @param eventName the name of the event, e.g. {@code payment-received}
   * @param eventKey identifies the event instance waited for, e.g. an order id
   * @return the wait, to set an optional timeout and receive the event
   * @throws IllegalArgumentException if the step or event name is blank
   */
  EventWait waitForEvent(String stepName, String eventName, String eventKey);

  /**
   * Marks this job as cancelled. Once the handler returns, bikesched sets the job to {@code
   * CANCELLED} (it is not retried) and clears {@code last_error}. The handler keeps running until
   * it returns; a cancelled job stays cancelled even if the handler then throws or its attempt
   * times out.
   */
  default void cancel() {
    cancel(null);
  }

  /**
   * Marks this job as cancelled, storing the message as the job's {@code last_error}. If called
   * repeatedly, the last message wins.
   *
   * @param message why the job was cancelled, may be {@code null}
   * @see #cancel()
   */
  void cancel(String message);

  /**
   * Marks the current attempt as failed, like throwing from the handler but without an exception
   * and with control over the retry. Once the handler returns, the job is retried while attempts
   * remain (by default as soon as possible), otherwise it is marked {@code FAILED}. Like {@link
   * #cancel()}, it does not stop the handler, so return right after it.
   *
   * @return the failure, to configure the retry
   * @see #fail(String)
   */
  default JobFailure fail() {
    return fail(null);
  }

  /**
   * Marks the current attempt as failed with a message, stored as the job's {@code last_error}. The
   * returned failure sets how many more attempts the job gets and when the next one runs:
   *
   * <pre>{@code
   * if (provider.isBusy()) {
   *     job.fail("provider busy").retries(5).retryAfter(Duration.ofMinutes(1));
   *     return;
   * }
   * }</pre>
   *
   * <p>An explicit failure wins over an exception thrown by the handler afterwards and over a
   * timeout; {@link #cancel(String) cancelling} the job wins over failing it. If called repeatedly,
   * the last call wins.
   *
   * @param message why the attempt failed, may be {@code null}
   * @return the failure, to configure the retry
   */
  JobFailure fail(String message);

  /**
   * Sets a new timeout for the running attempt, counted from now, replacing the current deadline
   * (or starting one if the attempt had none). Useful when the work turns out to take longer than
   * expected, e.g. for a large video to encode or while polling an external system for a result:
   *
   * <pre>{@code
   * while (!encoder.isDone(task)) {
   *     job.updateTimeout(Duration.ofMinutes(2)); // still making progress: two more minutes
   *     Thread.sleep(Duration.ofSeconds(10));
   * }
   * }</pre>
   *
   * <p>Applies to the current attempt only; a retry starts with the job's original timeout (see
   * {@link JobBuilder#timeout(Duration)}). May be called from other threads than the handler's.
   *
   * @param timeout the new timeout from now, positive
   * @throws IllegalStateException if the attempt is not running (anymore) or already timed out
   */
  void updateTimeout(Duration timeout);
}
