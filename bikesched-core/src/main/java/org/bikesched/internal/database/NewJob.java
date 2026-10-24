// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Duration;
import java.time.Instant;
import org.bikesched.job.JobBuilder;
import org.jspecify.annotations.Nullable;

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
 * @param recurrence the description of the recurring job's recurrence this job is an occurrence of;
 *     {@code null} for a one-off job
 * @param plannedRunAt the planned run of an occurrence, not moved by retries or event waits; {@code
 *     null} for a one-off job
 * @param idempotencyKey unique per job type while the job exists: scheduling a job with the key of
 *     an existing one returns that job; {@code null} for none
 */
public record NewJob(
    String type,
    @Nullable String payload,
    Instant scheduledAt,
    int priority,
    int maxAttempts,
    @Nullable Duration timeout,
    @Nullable String recurrence,
    @Nullable Instant plannedRunAt,
    @Nullable String idempotencyKey) {

  /** A job without idempotency key: a one-off job or an occurrence. */
  public NewJob(
      String type,
      @Nullable String payload,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      @Nullable Duration timeout,
      @Nullable String recurrence,
      @Nullable Instant plannedRunAt) {
    this(
        type, payload, scheduledAt, priority, maxAttempts, timeout, recurrence, plannedRunAt, null);
  }

  /** A one-off job without idempotency key. */
  public NewJob(
      String type,
      @Nullable String payload,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      @Nullable Duration timeout) {
    this(type, payload, scheduledAt, priority, maxAttempts, timeout, null, null, null);
  }
}
