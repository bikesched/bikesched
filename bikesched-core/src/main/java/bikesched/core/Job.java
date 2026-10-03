package bikesched.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A job claimed from {@code bikesched_jobs} for execution on this node.
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
public final class Job {

    private static final Logger LOG = LoggerFactory.getLogger(Job.class);

    private final Database database;
    private final UUID id;
    private final String type;
    private final String payload;
    private final int priority;
    private final Instant scheduledAt;
    private final int attempts;
    private final int maxAttempts;

    // Set by the handler, read by bikesched after the handler returned (the handler may use other threads).
    private volatile boolean cancelled;
    private volatile String cancelMessage;

    Job(Database database, UUID id, String type, String payload, int priority, Instant scheduledAt, int attempts,
            int maxAttempts) {
        this.database = Objects.requireNonNull(database, "database");
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.payload = payload;
        this.priority = priority;
        this.scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Returns the job id.
     *
     * @return the job id
     */
    public UUID id() {
        return id;
    }

    /**
     * Returns the job type, which identifies the handler that executes the job.
     *
     * @return the job type
     */
    public String type() {
        return type;
    }

    /**
     * Returns the raw job payload.
     *
     * @return the payload as JSON text, may be {@code null}
     * @see #payload(Class)
     */
    public String payload() {
        return payload;
    }

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
    public <T> T payload(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return payload == null ? null : database.jsonMapper().fromJson(payload, type);
    }

    /**
     * Returns the priority; higher priorities are claimed first.
     *
     * @return the priority
     */
    public int priority() {
        return priority;
    }

    /**
     * Returns the earliest time the job may run.
     *
     * @return the scheduled time
     */
    public Instant scheduledAt() {
        return scheduledAt;
    }

    /**
     * Returns the number of attempts including the current one.
     *
     * @return the attempt number, starting at 1
     */
    public int attempts() {
        return attempts;
    }

    /**
     * Returns the maximum number of attempts.
     *
     * @return the maximum number of attempts
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Marks this job as cancelled. Once the handler returns, bikesched sets the job to
     * {@code CANCELLED} (it is not retried) and clears {@code last_error}. The handler keeps
     * running until it returns; a cancelled job stays cancelled even if the handler then throws.
     */
    public void cancel() {
        cancel(null);
    }

    /**
     * Marks this job as cancelled, storing the message as the job's {@code last_error}. If called
     * repeatedly, the last message wins.
     *
     * @param message why the job was cancelled, may be {@code null}
     * @see #cancel()
     */
    public void cancel(String message) {
        this.cancelMessage = message;
        this.cancelled = true;
    }

    /**
     * Returns whether the handler cancelled this job.
     *
     * @return {@code true} after {@link #cancel()} or {@link #cancel(String)}
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns the message passed to {@link #cancel(String)}.
     *
     * @return the message, or {@code null} if the job was not cancelled or cancelled without one
     */
    public String cancelMessage() {
        return cancelMessage;
    }

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
    public <T> T step(String name, Class<T> resultType, StepHandler<T> handler) throws Exception {
        Objects.requireNonNull(resultType, "resultType");
        JsonMapper mapper = database.jsonMapper();
        return runStep(name, handler, mapper::toJson, json -> mapper.fromJson(json, resultType));
    }

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
    public String step(String name, StepHandler<String> handler) throws Exception {
        return runStep(name, handler, json -> json, json -> json);
    }

    private <T> T runStep(String name, StepHandler<T> handler, Function<? super T, String> toJson,
            Function<String, T> fromJson) throws Exception {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        if (name.isBlank()) {
            throw new IllegalArgumentException("step name must not be blank");
        }
        String outerStep = MDC.get(BikeschedLogContext.STEP_NAME);
        MDC.put(BikeschedLogContext.STEP_NAME, name);
        try {
            var completed = database.findCompletedStep(id, name);
            if (completed.isEmpty()) {
                var attempt = database.startStep(id, name);
                if (attempt.isPresent()) {
                    return executeStep(name, attempt.get(), handler, toJson);
                }
                completed = database.findCompletedStep(id, name); // completed in the meantime
            }
            LOG.debug("Step {} of job {} already completed, reusing its result", name, id);
            String json = completed.map(Database.CompletedStep::result).orElse(null);
            return json == null ? null : fromJson.apply(json);
        } finally {
            if (outerStep == null) {
                MDC.remove(BikeschedLogContext.STEP_NAME);
            } else {
                MDC.put(BikeschedLogContext.STEP_NAME, outerStep);
            }
        }
    }

    private <T> T executeStep(String name, int attempt, StepHandler<T> handler, Function<? super T, String> toJson)
            throws Exception {
        LOG.debug("Executing step {} of job {} (attempt {})", name, id, attempt);
        T result;
        try {
            result = handler.run(new Step(id, name, attempt));
            database.completeStep(id, name, result == null ? null : toJson.apply(result));
        } catch (Exception e) {
            recordStepFailure(name, attempt, e);
            throw e;
        }
        LOG.debug("Step {} of job {} completed", name, id);
        return result;
    }

    private void recordStepFailure(String name, int attempt, Exception failure) {
        LOG.debug("Step {} of job {} failed (attempt {}): {}", name, id, attempt, failure.toString());
        try {
            database.failStep(id, name, failure.toString());
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    @Override
    public String toString() {
        return "Job[id=" + id + ", type=" + type + ", payload=" + payload + ", priority=" + priority
                + ", scheduledAt=" + scheduledAt + ", attempts=" + attempts + ", maxAttempts=" + maxAttempts
                + (cancelled ? ", cancelled" + (cancelMessage == null ? "" : "=" + cancelMessage) : "") + "]";
    }
}
