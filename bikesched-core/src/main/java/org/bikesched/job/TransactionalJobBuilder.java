// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.BikeschedException;

/**
 * Builds a job stored in the caller's transaction, obtained via {@link
 * JobBuilder#connection(Connection)}: the job becomes visible to the pollers only once the caller
 * commits, and disappears if the caller rolls back. bikesched does not commit, roll back or close
 * the connection, nor change its auto-commit mode.
 *
 * <p>Every {@code schedule} call stores a new job, unless the builder has an {@link
 * #idempotencyKey(String) idempotency key}. Not thread-safe.
 *
 * @param <P> the payload type: {@code Object} for a job type given as a string, the payload class
 *     of a {@link JobType}
 */
public interface TransactionalJobBuilder<P>
    extends JobHandlerSettings<TransactionalJobBuilder<P>, P> {

  /**
   * Sets an idempotency key: while a job of this type has the key, scheduling returns that job's id
   * instead of storing another one, see {@link IdempotentJobBuilder}. If a concurrent transaction
   * stores a job with the same key, scheduling waits until that transaction ends; a job stored in a
   * transaction that rolls back leaves the key free.
   *
   * @param idempotencyKey the key, unique per job type, not blank, at most 500 characters
   * @return this builder
   * @throws IllegalArgumentException if the key is blank or too long
   */
  TransactionalJobBuilder<P> idempotencyKey(String idempotencyKey);

  /**
   * Schedules the job to run as soon as possible once the caller's transaction commits.
   *
   * @return the id of the scheduled job, or of the existing job with the idempotency key
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON; PostgreSQL then aborts the caller's transaction, which has to be rolled back
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule();

  /**
   * Schedules the job to run at or after the given time, once the caller's transaction commits.
   *
   * @param at the earliest time the job may run
   * @return the id of the scheduled job, or of the existing job with the idempotency key
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON; PostgreSQL then aborts the caller's transaction, which has to be rolled back
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule(Instant at);

  /**
   * Schedules the job to run once the given delay has passed, once the caller's transaction
   * commits.
   *
   * @param delay how long to wait before the job may run, not negative
   * @return the id of the scheduled job, or of the existing job with the idempotency key
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON; PostgreSQL then aborts the caller's transaction, which has to be rolled back
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule(Duration delay);
}
