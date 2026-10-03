package bikesched.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The stored attributes of a job to be scheduled, assembled by {@link JobBuilder}.
 *
 * @param type the job type
 * @param payload the payload as JSON text, may be {@code null}
 * @param scheduledAt the earliest time the job may run
 * @param priority higher priorities are claimed first
 * @param maxAttempts number of attempts before the job is marked {@code FAILED}
 * @param timeout maximum duration of an attempt, {@code null} for the executing node's default
 */
record NewJob(
    String type,
    String payload,
    Instant scheduledAt,
    int priority,
    int maxAttempts,
    Duration timeout) {

  NewJob {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(scheduledAt, "scheduledAt");
  }
}
