// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.jspecify.annotations.Nullable;

/**
 * Builds a one-off job with an idempotency key, obtained via {@link
 * JobBuilder#idempotencyKey(String)}: a caller that retries, e.g. a webhook delivered twice,
 * schedules the job once.
 *
 * <pre>{@code
 * UUID id = bikesched.newJob("process-payment")
 *     .payload(payment)
 *     .idempotencyKey(webhook.deliveryId())
 *     .schedule();   // a retry with the same key returns the same id
 * }</pre>
 *
 * <p>The key is unique per job type for as long as the job exists: until the job retention ({@code
 * BikeschedBuilder.jobRetention}) deletes it after it ended, whatever its end state ({@code
 * SUCCEEDED}, {@code FAILED}, {@code CANCELLED}). While a job of the type has the key, {@code
 * schedule} returns its id and stores nothing: the payload, due time and other settings of the
 * duplicate are ignored, and so is a builder reused for a second {@code schedule} call. The
 * handlers set on this builder are registered as usual.
 *
 * <p>Keys taken from clients (request headers, form fields) need the tenant or user in the key if
 * several of them share a job type. A recurring job cannot have a key: this builder has no {@code
 * schedule(Recurrence)}. Not thread-safe.
 *
 * @param <P> the payload type: {@code Object} for a job type given as a string, the payload class
 *     of a {@link JobType}
 */
public interface IdempotentJobBuilder<P extends @Nullable Object>
    extends JobHandlerSettings<IdempotentJobBuilder<P>, P> {

  /**
   * Continues with storing the job in the caller's transaction, see {@link
   * JobBuilder#connection(Connection)}; it keeps the idempotency key and the settings made so far.
   * If a concurrent transaction stores a job with the same key, scheduling waits until that
   * transaction ends.
   *
   * @param connection the caller's connection
   * @return a builder with the settings made so far, storing the job on the connection
   */
  TransactionalJobBuilder<P> connection(Connection connection);

  /**
   * Schedules the job to run as soon as possible, unless a job of its type has the idempotency key.
   *
   * @return the id of the scheduled job, or of the existing job with the idempotency key
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule();

  /**
   * Schedules the job to run at or after the given time, unless a job of its type has the
   * idempotency key.
   *
   * @param at the earliest time the job may run
   * @return the id of the scheduled job, or of the existing job with the idempotency key
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule(Instant at);

  /**
   * Schedules the job to run once the given delay has passed, unless a job of its type has the
   * idempotency key.
   *
   * @param delay how long to wait before the job may run, not negative
   * @return the id of the scheduled job, or of the existing job with the idempotency key
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule(Duration delay);
}
