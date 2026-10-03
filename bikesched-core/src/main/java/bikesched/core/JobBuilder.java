package bikesched.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds and schedules a job of one type, obtained via {@link Bikesched#newJob(String)}:
 *
 * <pre>{@code
 * UUID id = bikesched.newJob("send-mail")
 *         .payload("{\"to\": \"alice@example.com\"}")
 *         .handler(job -> mailer.send(job.payload()))   // optional if already registered
 *         .priority(10)
 *         .schedule();                                   // or schedule(instant) / schedule(delay)
 * }</pre>
 *
 * <p>Every {@code schedule} call stores a new job, so a builder can be reused to schedule several
 * jobs with the same settings. Not thread-safe.
 */
public final class JobBuilder {

    static final int DEFAULT_PRIORITY = 0;
    static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** Stores a job built by a {@link JobBuilder}; implemented by the owning {@link Bikesched}. */
    @FunctionalInterface
    interface Scheduler {
        UUID schedule(
                String type, String payload, Instant scheduledAt, int priority, int maxAttempts, JobHandler handler);
    }

    private final String type;
    private final Scheduler scheduler;
    private String payload;
    private JobHandler handler;
    private int priority = DEFAULT_PRIORITY;
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    JobBuilder(String type, Scheduler scheduler) {
        this.type = Objects.requireNonNull(type, "type");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Sets the job payload (optional, default none).
     *
     * @param payload the payload as JSON text, may be {@code null}
     * @return this builder
     */
    public JobBuilder payload(String payload) {
        this.payload = payload;
        return this;
    }

    /**
     * Sets the handler executing jobs of this type (optional if one is already registered). It is
     * registered under the job type when the job is scheduled. <strong>If a handler is already
     * registered for the type, that one is kept and this handler is ignored</strong>, so the same
     * {@code newJob(type).handler(...).schedule()} code can run repeatedly (lambdas are usually a
     * new instance on every call).
     *
     * @param handler executes jobs of this type
     * @return this builder
     * @see Bikesched#registerHandler(String, JobHandler)
     */
    public JobBuilder handler(JobHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Sets a handler that does not need the job itself (e.g. no payload).
     *
     * @param handler executes jobs of this type
     * @return this builder
     * @see #handler(JobHandler)
     */
    public JobBuilder handler(Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        return handler(_ -> handler.run());
    }

    /**
     * Sets the priority (optional, default 0). Due jobs with a higher priority are claimed first.
     *
     * @param priority the priority
     * @return this builder
     */
    public JobBuilder priority(int priority) {
        this.priority = priority;
        return this;
    }

    /**
     * Sets how often the job is attempted before it is marked {@code FAILED} (optional, default 3).
     *
     * @param maxAttempts the maximum number of attempts, at least 1
     * @return this builder
     */
    public JobBuilder maxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
        return this;
    }

    /**
     * Schedules the job to run as soon as possible.
     *
     * @return the id of the scheduled job
     * @throws IllegalArgumentException if no handler is set nor registered for the job type
     * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid JSON
     * @throws IllegalStateException if the {@link Bikesched} is closed
     */
    public UUID schedule() {
        return schedule(Instant.now());
    }

    /**
     * Schedules the job to run at or after the given time.
     *
     * @param at the earliest time the job may run
     * @return the id of the scheduled job
     * @throws IllegalArgumentException if no handler is set nor registered for the job type
     * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid JSON
     * @throws IllegalStateException if the {@link Bikesched} is closed
     */
    public UUID schedule(Instant at) {
        Objects.requireNonNull(at, "at");
        return scheduler.schedule(type, payload, at, priority, maxAttempts, handler);
    }

    /**
     * Schedules the job to run once the given delay has passed.
     *
     * @param delay how long to wait before the job may run, not negative
     * @return the id of the scheduled job
     * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
     *     for the job type
     * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid JSON
     * @throws IllegalStateException if the {@link Bikesched} is closed
     */
    public UUID schedule(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative: " + delay);
        }
        return schedule(Instant.now().plus(delay));
    }
}
