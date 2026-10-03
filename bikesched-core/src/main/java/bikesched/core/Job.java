package bikesched.core;

import java.time.Duration;
import java.util.UUID;

/**
 * A job being executed, as seen by its {@link JobHandler}.
 *
 * <p>A handler can {@link #cancel() cancel} the job it executes: once the handler returns, the
 * job ends in state {@code CANCELLED} and is not retried, regardless of the remaining attempts.
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
 * step that completed is not executed again when the job is retried, its stored result is
 * returned instead.
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
     * Executes a durable step of this job, like a durable function: if the step already completed
     * in an earlier attempt of the job, its stored result is returned without executing the
     * handler again. Otherwise the handler is executed, and its result is stored in
     * {@code bikesched_steps} (committed right away) and returned. Results are converted to and
     * from JSON by the configured {@link JsonMapper}.
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
     * which fails the job's attempt: on the next attempt, completed steps are skipped and the
     * failed step is executed again. Step names identify steps across attempts, so they must be
     * unique within the job and the handler must call its steps in a deterministic way. A step
     * may still run more than once (e.g. if the node dies before its result is stored), so steps
     * should be idempotent. Steps run on the calling thread, one at a time.
     *
     * @param name the step name, unique within the job
     * @param resultType the type of the step result, used to read a stored result back
     * @param handler executes the step and returns its result (may be {@code null})
     * @param <T> the type of the step result
     * @return the step result, may be {@code null}
     * @throws Exception the exception thrown by the handler
     * @throws BikeschedException if the step cannot be read or stored, e.g. because the mapped
     *     result is not valid JSON
     * @throws IllegalArgumentException if the name is blank
     */
    <T> T step(String name, Class<T> resultType, StepHandler<T> handler) throws Exception;

    /**
     * Executes a durable step whose result is raw JSON text, stored as is (without the
     * {@link JsonMapper}), like {@link #payload()}. See {@link #step(String, Class, StepHandler)}.
     *
     * @param name the step name, unique within the job
     * @param handler executes the step and returns its result as JSON text (may be {@code null})
     * @return the step result as JSON text, may be {@code null}
     * @throws Exception the exception thrown by the handler
     * @throws BikeschedException if the step cannot be read or stored, e.g. because the result
     *     is not valid JSON
     * @throws IllegalArgumentException if the name is blank
     */
    String step(String name, StepHandler<String> handler) throws Exception;

    /**
     * Marks this job as cancelled. Once the handler returns, bikesched sets the job to
     * {@code CANCELLED} (it is not retried) and clears {@code last_error}. The handler keeps
     * running until it returns; a cancelled job stays cancelled even if the handler then throws
     * or its attempt times out.
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
