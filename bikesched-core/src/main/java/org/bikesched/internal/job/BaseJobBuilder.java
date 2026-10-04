package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.internal.database.NewJob;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobSettings;

/**
 * The shared settings of the job builder implementations ({@link DefaultJobBuilder}, {@link
 * DefaultTransactionalJobBuilder}, {@link DefaultRecurringJobBuilder}).
 *
 * @param <B> the builder interface
 */
public abstract class BaseJobBuilder<B extends JobSettings<B>> implements JobSettings<B> {

  static final int DEFAULT_PRIORITY = 0;
  static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** Stores the jobs built by the job builders; implemented by the owning bikesched node. */
  public interface Scheduler {
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

  BaseJobBuilder(String type, JsonMapper jsonMapper, Scheduler scheduler) {
    this.type = Objects.requireNonNull(type, "type");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** Continues building with the settings of the given builder. */
  BaseJobBuilder(BaseJobBuilder<?> settings) {
    this(settings.type, settings.jsonMapper, settings.scheduler);
    this.payload = settings.payload;
    this.handler = settings.handler;
    this.priority = settings.priority;
    this.maxAttempts = settings.maxAttempts;
    this.timeout = settings.timeout;
  }

  /** This builder as its interface type. */
  abstract B self();

  @Override
  public B payload(Object payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return self();
  }

  @Override
  public B handler(JobHandler handler) {
    this.handler = Objects.requireNonNull(handler, "handler");
    return self();
  }

  @Override
  public B priority(int priority) {
    this.priority = priority;
    return self();
  }

  @Override
  public B maxAttempts(int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
    }
    this.maxAttempts = maxAttempts;
    return self();
  }

  @Override
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
