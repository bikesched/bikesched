package org.bikesched.internal.database;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.Recurrence;

/**
 * The stored attributes of a job to be scheduled, assembled by {@link JobBuilder} (or of an
 * occurrence of a recurring job, see {@code RecurringJob}).
 *
 * @param type the job type
 * @param payload the payload as JSON text, may be {@code null}
 * @param scheduledAt the earliest time the job may run
 * @param priority higher priorities are claimed first
 * @param maxAttempts number of attempts before the job is marked {@code FAILED}
 * @param timeout maximum duration of an attempt, {@code null} for the executing node's default
 * @param schedule the description of the recurring job's schedule this job is an occurrence of;
 *     {@code null} for a one-off job
 * @param fixedRateAt the planned run of an occurrence whose schedule {@link
 *     Recurrence#isFixedRate() is fixed-rate}; {@code null} otherwise
 */
public record NewJob(
    String type,
    String payload,
    Instant scheduledAt,
    int priority,
    int maxAttempts,
    Duration timeout,
    String schedule,
    Instant fixedRateAt) {

  public NewJob {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(scheduledAt, "scheduledAt");
  }

  /** A one-off job. */
  public NewJob(
      String type,
      String payload,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      Duration timeout) {
    this(type, payload, scheduledAt, priority, maxAttempts, timeout, null, null);
  }
}
