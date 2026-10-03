package bikesched.core;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The settings shared by all job builders: payload, handler, priority, attempts and timeout. Each
 * setter returns the concrete builder, so they can be called in any order:
 *
 * <ul>
 *   <li>{@link JobBuilder}: a one-off job, from {@link Bikesched#newJob(String)}
 *   <li>{@link TransactionalJobBuilder}: a one-off job stored in the caller's transaction, from
 *       {@link JobBuilder#connection(Connection)}
 *   <li>{@link RecurringJobBuilder}: a recurring job, from {@link JobBuilder#recurring(Schedule)}
 * </ul>
 *
 * @param <B> the concrete builder type
 */
public abstract sealed class AbstractJobBuilder<B extends AbstractJobBuilder<B>>
    permits JobBuilder, TransactionalJobBuilder, RecurringJobBuilder {

  static final int DEFAULT_PRIORITY = 0;
  static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** Stores the jobs built by the job builders; implemented by the owning {@link Bikesched}. */
  interface Scheduler {
    /** {@code connection} is the caller's connection, or {@code null} to use bikesched's own. */
    UUID schedule(NewJob job, JobHandler handler, Connection connection);

    /** Registers a recurring job; {@code firstRun} is {@code null} for the schedule's next run. */
    UUID scheduleRecurring(RecurringJob job, JobHandler handler, Instant firstRun);
  }

  final String type;
  final JsonMapper jsonMapper;
  final Scheduler scheduler;
  String payload;
  JobHandler handler;
  int priority = DEFAULT_PRIORITY;
  int maxAttempts = DEFAULT_MAX_ATTEMPTS;
  Duration timeout;

  AbstractJobBuilder(String type, JsonMapper jsonMapper, Scheduler scheduler) {
    this.type = Objects.requireNonNull(type, "type");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** Continues building with the settings of the given builder. */
  AbstractJobBuilder(AbstractJobBuilder<?> settings) {
    this(settings.type, settings.jsonMapper, settings.scheduler);
    this.payload = settings.payload;
    this.handler = settings.handler;
    this.priority = settings.priority;
    this.maxAttempts = settings.maxAttempts;
    this.timeout = settings.timeout;
  }

  /** This builder as its concrete type. */
  abstract B self();

  /**
   * Sets the job payload (optional, default none), converted to JSON right away by the configured
   * {@link JsonMapper}. With the default {@link JsonStringMapper} the payload must be a {@link
   * String} that already contains JSON.
   *
   * @param payload the payload, may be {@code null}
   * @return this builder
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default {@link JsonStringMapper} for types other than {@link String}
   * @see Job#payload(Class)
   */
  public B payload(Object payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return self();
  }

  /**
   * Sets the handler executing jobs of this type (optional if one is already registered). It is
   * registered under the job type when the job is scheduled. <strong>If a handler is already
   * registered for the type, that one is kept and this handler is ignored</strong>, so the same
   * {@code newJob(type).handler(...).schedule()} code can run repeatedly (lambdas are usually a new
   * instance on every call).
   *
   * @param handler executes jobs of this type
   * @return this builder
   * @see Bikesched#registerHandler(String, JobHandler)
   */
  public B handler(JobHandler handler) {
    this.handler = Objects.requireNonNull(handler, "handler");
    return self();
  }

  /**
   * Sets a handler that does not need the job itself (e.g. no payload).
   *
   * @param handler executes jobs of this type
   * @return this builder
   * @see #handler(JobHandler)
   */
  public B handler(Runnable handler) {
    Objects.requireNonNull(handler, "handler");
    return handler(_ -> handler.run());
  }

  /**
   * Sets the priority (optional, default 0). Due jobs with a higher priority are claimed first.
   *
   * @param priority the priority
   * @return this builder
   */
  public B priority(int priority) {
    this.priority = priority;
    return self();
  }

  /**
   * Sets how often the job is attempted before it is marked {@code FAILED} (optional, default 3).
   *
   * @param maxAttempts the maximum number of attempts, at least 1
   * @return this builder
   */
  public B maxAttempts(int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
    }
    this.maxAttempts = maxAttempts;
    return self();
  }

  /**
   * Sets the maximum duration of each attempt of the job (optional, default: the executing node's
   * {@link BikeschedBuilder#defaultJobTimeout(Duration) default timeout}, if any). When an attempt
   * exceeds it, bikesched logs a warning and interrupts the handler's thread; once the handler then
   * returns or throws, the attempt counts as failed ("timed out") and is retried while attempts
   * remain.
   *
   * <p>Interrupting only stops handlers that react to it, e.g. by blocking in interruptible calls
   * ({@link Thread#sleep(long)}, most I/O on virtual threads) or by checking {@link
   * Thread#isInterrupted()}. A handler that ignores the interrupt keeps running (and keeps its job
   * slot) until it ends by itself. A handler that needs more time can extend its deadline with
   * {@link Job#updateTimeout(Duration)}.
   *
   * @param timeout the timeout, positive
   * @return this builder
   */
  public B timeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
    this.timeout = timeout;
    return self();
  }

  /** A one-off job with the current settings, running at or after the given time. */
  NewJob newJob(Instant at) {
    return new NewJob(type, payload, at, priority, maxAttempts, timeout);
  }

  static Instant after(Duration delay) {
    Objects.requireNonNull(delay, "delay");
    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative: " + delay);
    }
    return Instant.now().plus(delay);
  }
}
