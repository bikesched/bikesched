package bikesched.core;

import java.sql.Connection;
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
    /** {@code connection} is the caller's connection, or {@code null} to use bikesched's own. */
    UUID schedule(NewJob job, JobHandler handler, Connection connection);
  }

  private final String type;
  private final JsonMapper jsonMapper;
  private final Scheduler scheduler;
  private String payload;
  private JobHandler handler;
  private int priority = DEFAULT_PRIORITY;
  private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
  private Duration timeout;
  private Connection connection;

  JobBuilder(String type, JsonMapper jsonMapper, Scheduler scheduler) {
    this.type = Objects.requireNonNull(type, "type");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

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
  public JobBuilder payload(Object payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return this;
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
  public JobBuilder timeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
    this.timeout = timeout;
    return this;
  }

  /**
   * Stores the job using the given connection, as part of the caller's transaction (optional,
   * default: bikesched's own data source, committed immediately). The job is then created
   * atomically with the caller's other changes: it becomes visible to the pollers only once the
   * caller commits, and disappears if the caller rolls back.
   *
   * <p>bikesched only executes the insert on the connection: it does not commit, roll back, close
   * it or change its auto-commit mode. The connection must point to the database (and schema)
   * bikesched uses. If scheduling fails with a {@link BikeschedException}, PostgreSQL aborts the
   * caller's transaction, which then has to be rolled back.
   *
   * <pre>{@code
   * try (Connection connection = dataSource.getConnection()) {
   *     connection.setAutoCommit(false);
   *     insertOrder(connection, order);
   *     bikesched.newJob("send-confirmation").payload(json).connection(connection).schedule();
   *     connection.commit();   // order and job are stored together, or not at all
   * }
   * }</pre>
   *
   * @param connection the caller's connection, or {@code null} to use bikesched's own
   * @return this builder
   */
  public JobBuilder connection(Connection connection) {
    this.connection = connection;
    return this;
  }

  /**
   * Schedules the job to run as soon as possible.
   *
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
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
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    return scheduler.schedule(
        new NewJob(type, payload, at, priority, maxAttempts, timeout), handler, connection);
  }

  /**
   * Schedules the job to run once the given delay has passed.
   *
   * @param delay how long to wait before the job may run, not negative
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
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
