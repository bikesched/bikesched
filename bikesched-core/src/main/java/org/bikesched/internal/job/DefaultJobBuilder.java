// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.job.IdempotentJobBuilder;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.Recurrence;
import org.bikesched.job.TransactionalJobBuilder;
import org.bikesched.job.TypedJobFailureHandler;
import org.bikesched.job.TypedJobHandler;
import org.jspecify.annotations.Nullable;

/** The {@link JobBuilder} implementation. */
public final class DefaultJobBuilder<P extends @Nullable Object>
    extends BaseJobBuilder<JobBuilder<P>, P> implements JobBuilder<P> {

  public DefaultJobBuilder(
      String name, Class<P> payloadType, JsonMapper jsonMapper, Scheduler scheduler) {
    super(name, payloadType, jsonMapper, scheduler);
  }

  @Override
  JobBuilder<P> self() {
    return this;
  }

  @Override
  public JobBuilder<P> handler(JobHandler handler) {
    return setHandler(handler);
  }

  @Override
  public JobBuilder<P> handler(TypedJobHandler<? super P> handler) {
    return setHandler(handler);
  }

  @Override
  public JobBuilder<P> failureHandler(JobFailureHandler handler) {
    return setFailureHandler(handler);
  }

  @Override
  public JobBuilder<P> failureHandler(TypedJobFailureHandler<? super P> handler) {
    return setFailureHandler(handler);
  }

  @Override
  public TransactionalJobBuilder<P> connection(Connection connection) {
    return new DefaultTransactionalJobBuilder<>(
        this, Objects.requireNonNull(connection, "connection"));
  }

  @Override
  public IdempotentJobBuilder<P> idempotencyKey(String idempotencyKey) {
    return new DefaultIdempotentJobBuilder<>(this).setIdempotencyKey(idempotencyKey);
  }

  @Override
  public UUID schedule() {
    return schedule(Instant.now());
  }

  @Override
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    return scheduler.schedule(newJob(at), handler, failureHandler, null);
  }

  @Override
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }

  @Override
  public UUID schedule(Recurrence recurrence) {
    return scheduler.scheduleRecurring(recurringJob(recurrence), handler, failureHandler, null);
  }

  @Override
  public UUID schedule(String recurrence) {
    return schedule(scheduler.parseRecurrence(recurrence));
  }

  @Override
  public UUID schedule(Recurrence recurrence, Instant firstRun) {
    Objects.requireNonNull(firstRun, "firstRun");
    return scheduler.scheduleRecurring(recurringJob(recurrence), handler, failureHandler, firstRun);
  }

  @Override
  public UUID schedule(Recurrence recurrence, Duration delay) {
    return schedule(recurrence, after(delay));
  }

  @Override
  public UUID schedule(String recurrence, Duration delay) {
    return schedule(scheduler.parseRecurrence(recurrence), delay);
  }

  private RecurringJob recurringJob(Recurrence recurrence) {
    Objects.requireNonNull(recurrence, "recurrence");
    return new RecurringJob(name, recurrence, payload, priority, maxAttempts, timeout, reference);
  }
}
