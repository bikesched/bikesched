package bikesched.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
 */
public final class Job {

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

    Job(UUID id, String type, String payload, int priority, Instant scheduledAt, int attempts, int maxAttempts) {
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
     * Returns the job payload.
     *
     * @return the payload as JSON text, may be {@code null}
     */
    public String payload() {
        return payload;
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

    @Override
    public String toString() {
        return "Job[id=" + id + ", type=" + type + ", payload=" + payload + ", priority=" + priority
                + ", scheduledAt=" + scheduledAt + ", attempts=" + attempts + ", maxAttempts=" + maxAttempts
                + (cancelled ? ", cancelled" + (cancelMessage == null ? "" : "=" + cancelMessage) : "") + "]";
    }
}
