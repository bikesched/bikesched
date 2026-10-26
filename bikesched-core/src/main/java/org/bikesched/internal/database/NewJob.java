// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Duration;
import java.time.Instant;
import org.bikesched.history.Actor;
import org.bikesched.job.JobBuilder;
import org.jspecify.annotations.Nullable;

/**
 * The stored attributes of a job to be scheduled, assembled by {@link JobBuilder} (or of an
 * occurrence of a recurring job, see {@code RecurringJob}).
 *
 * @param name the job name
 * @param payload the payload as JSON text, may be {@code null}
 * @param scheduledAt the earliest time the job may run
 * @param priority higher priorities are claimed first
 * @param maxAttempts number of attempts before the job is marked {@code FAILED}
 * @param timeout maximum duration of an attempt, {@code null} for the executing node's default
 * @param recurrence the description of the recurring job's recurrence this job is an occurrence of;
 *     {@code null} for a one-off job
 * @param plannedRunAt the planned run of an occurrence, not moved by retries or event waits; {@code
 *     null} for a one-off job
 * @param idempotencyKey unique per job name while the job exists: scheduling a job with the key of
 *     an existing one returns that job; {@code null} for none
 * @param reference the external entity the job is about (not unique), {@code null} for none
 * @param actor the user scheduling the job (or registering the recurring job), named on its {@code
 *     SCHEDULED} or {@code MOVED} history entry; {@code null} for the code path's actor
 */
public record NewJob(
    String name,
    @Nullable String payload,
    Instant scheduledAt,
    int priority,
    int maxAttempts,
    @Nullable Duration timeout,
    @Nullable String recurrence,
    @Nullable Instant plannedRunAt,
    @Nullable String idempotencyKey,
    @Nullable String reference,
    @Nullable Actor actor) {

  /** A job without a user. */
  public NewJob(
      String name,
      @Nullable String payload,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      @Nullable Duration timeout,
      @Nullable String recurrence,
      @Nullable Instant plannedRunAt,
      @Nullable String idempotencyKey,
      @Nullable String reference) {
    this(
        name,
        payload,
        scheduledAt,
        priority,
        maxAttempts,
        timeout,
        recurrence,
        plannedRunAt,
        idempotencyKey,
        reference,
        null);
  }

  /** A job without idempotency key and reference: a one-off job or an occurrence. */
  public NewJob(
      String name,
      @Nullable String payload,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      @Nullable Duration timeout,
      @Nullable String recurrence,
      @Nullable Instant plannedRunAt) {
    this(
        name,
        payload,
        scheduledAt,
        priority,
        maxAttempts,
        timeout,
        recurrence,
        plannedRunAt,
        null,
        null,
        null);
  }

  /** A one-off job without idempotency key and reference. */
  public NewJob(
      String name,
      @Nullable String payload,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      @Nullable Duration timeout) {
    this(name, payload, scheduledAt, priority, maxAttempts, timeout, null, null, null, null, null);
  }

  /** This job named as scheduled by the given user, or as it is for {@code null}. */
  public NewJob withActor(@Nullable Actor actor) {
    return new NewJob(
        name,
        payload,
        scheduledAt,
        priority,
        maxAttempts,
        timeout,
        recurrence,
        plannedRunAt,
        idempotencyKey,
        reference,
        actor);
  }
}
