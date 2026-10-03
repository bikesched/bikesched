package bikesched.core.internal;

import bikesched.core.job.JobBuilder;
import bikesched.core.job.Schedule;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The stored attributes of a job to be scheduled, assembled by {@link JobBuilder} (or of an
 * occurrence of a recurring job, see {@link RecurringJob}).
 *
 * @param type the job type
 * @param payload the payload as JSON text, may be {@code null}
 * @param scheduledAt the earliest time the job may run
 * @param priority higher priorities are claimed first
 * @param maxAttempts number of attempts before the job is marked {@code FAILED}
 * @param timeout maximum duration of an attempt, {@code null} for the executing node's default
 * @param schedule the description of the recurring job's schedule this job is an occurrence of;
 *     {@code null} for a one-off job
 * @param plannedAt the planned run of an occurrence whose schedule {@link Schedule#usePlannedTime()
 *     uses planned times}; {@code null} otherwise
 */
record NewJob(
    String type,
    String payload,
    Instant scheduledAt,
    int priority,
    int maxAttempts,
    Duration timeout,
    String schedule,
    Instant plannedAt) {

  NewJob {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(scheduledAt, "scheduledAt");
  }

  /** A one-off job. */
  NewJob(
      String type,
      String payload,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      Duration timeout) {
    this(type, payload, scheduledAt, priority, maxAttempts, timeout, null, null);
  }
}
