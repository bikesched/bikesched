// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.sql.Connection;
import java.time.Duration;
import org.bikesched.BikeschedBuilder;
import org.bikesched.JsonMapper;
import org.jspecify.annotations.Nullable;

/**
 * The settings shared by all job builders: payload, priority, attempts and timeout. Each setter
 * returns the concrete builder, so they can be called in any order:
 *
 * <ul>
 *   <li>{@link JobBuilder}: a one-off job or, with {@link JobBuilder#schedule(Recurrence)}, a
 *       recurring job, from {@link org.bikesched.Bikesched#newJob(String)} or {@link
 *       org.bikesched.Bikesched#newJob(JobType)}
 *   <li>{@link TransactionalJobBuilder}: a one-off job stored in the caller's transaction, from
 *       {@link JobBuilder#connection(Connection)}
 *   <li>{@link IdempotentJobBuilder}: a one-off job scheduled once per idempotency key, from {@link
 *       JobBuilder#idempotencyKey(String)}
 *   <li>{@link StepJobBuilder}: a child job started as a step of its parent, from {@link
 *       Job#newJob(String)} or {@link Job#newJob(JobType)}
 * </ul>
 *
 * <p>The builders of jobs scheduled through {@link org.bikesched.Bikesched} can also set the
 * handler, see {@link JobHandlerSettings}.
 *
 * @param <B> the concrete builder type
 * @param <P> the payload type: {@code Object} for a job type given as a string, the payload class
 *     of a {@link JobType}
 */
public interface JobSettings<B extends JobSettings<B, P>, P extends @Nullable Object> {

  /**
   * Sets the job payload (optional, default none), converted to JSON right away by the configured
   * {@link JsonMapper}. Without a configured mapper the payload must be a {@link String} that
   * already contains JSON.
   *
   * @param payload the payload, may be {@code null}
   * @return this builder
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default mapper (no {@code jsonMapper} configured) for types other than {@link
   *     String}
   * @see Job#payload(Class)
   */
  B payload(@Nullable P payload);

  /**
   * Sets the priority (optional, default 0). Due jobs with a higher priority are claimed first.
   *
   * @param priority the priority
   * @return this builder
   */
  B priority(int priority);

  /**
   * Sets how often the job is attempted before it is marked {@code FAILED} (optional, default 3).
   *
   * @param maxAttempts the maximum number of attempts, at least 1
   * @return this builder
   */
  B maxAttempts(int maxAttempts);

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
  B timeout(Duration timeout);
}
