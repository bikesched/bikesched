package org.bikesched.job;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import org.bikesched.BikeschedBuilder;
import org.bikesched.JsonMapper;

/**
 * The settings shared by all job builders: payload, handler, priority, attempts and timeout. Each
 * setter returns the concrete builder, so they can be called in any order:
 *
 * <ul>
 *   <li>{@link JobBuilder}: a one-off job, from {@link org.bikesched.Bikesched#newJob(String)} or
 *       {@link org.bikesched.Bikesched#newJob(JobType)}
 *   <li>{@link TransactionalJobBuilder}: a one-off job stored in the caller's transaction, from
 *       {@link JobBuilder#connection(Connection)}
 *   <li>{@link RecurringJobBuilder}: a recurring job, from {@link JobBuilder#recurring(Schedule)}
 * </ul>
 *
 * @param <B> the concrete builder type
 * @param <P> the payload type: {@code Object} for a job type given as a string, the payload class
 *     of a {@link JobType}
 */
public interface JobSettings<B extends JobSettings<B, P>, P> {

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
  B payload(P payload);

  /**
   * Sets the handler executing jobs of this type (optional if one is already registered). It is
   * registered under the job type when the job is scheduled. <strong>If a handler is already
   * registered for the type, that one is kept and this handler is ignored</strong>, so the same
   * {@code newJob(type).handler(...).schedule()} code can run repeatedly (lambdas are usually a new
   * instance on every call).
   *
   * @param handler executes jobs of this type
   * @return this builder
   * @see org.bikesched.Bikesched#registerJobHandler(String, JobHandler)
   */
  B handler(JobHandler handler);

  /**
   * Sets a handler that does not need the job itself (e.g. no payload).
   *
   * @param handler executes jobs of this type
   * @return this builder
   * @see #handler(JobHandler)
   */
  default B handler(Runnable handler) {
    Objects.requireNonNull(handler, "handler");
    return handler(_ -> handler.run());
  }

  /**
   * Sets a handler receiving the payload mapped to the payload type (the {@link JobType}'s payload
   * class, {@code Object} for a job type given as a string). Registered like {@link
   * #handler(JobHandler)}.
   *
   * @param handler executes jobs of this type
   * @return this builder
   * @see org.bikesched.Bikesched#registerJobHandler(JobType, TypedJobHandler)
   */
  B handler(TypedJobHandler<? super P> handler);

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
