// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.job.IdempotentJobBuilder;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.TransactionalJobBuilder;
import org.bikesched.job.TypedJobFailureHandler;
import org.bikesched.job.TypedJobHandler;
import org.jspecify.annotations.Nullable;

/** The {@link IdempotentJobBuilder} implementation. */
final class DefaultIdempotentJobBuilder<P extends @Nullable Object>
    extends BaseJobBuilder<IdempotentJobBuilder<P>, P> implements IdempotentJobBuilder<P> {

  DefaultIdempotentJobBuilder(DefaultJobBuilder<P> settings) {
    super(settings);
  }

  @Override
  IdempotentJobBuilder<P> self() {
    return this;
  }

  @Override
  public IdempotentJobBuilder<P> handler(JobHandler handler) {
    return setHandler(handler);
  }

  @Override
  public IdempotentJobBuilder<P> handler(TypedJobHandler<? super P> handler) {
    return setHandler(handler);
  }

  @Override
  public IdempotentJobBuilder<P> failureHandler(JobFailureHandler handler) {
    return setFailureHandler(handler);
  }

  @Override
  public IdempotentJobBuilder<P> failureHandler(TypedJobFailureHandler<? super P> handler) {
    return setFailureHandler(handler);
  }

  @Override
  public TransactionalJobBuilder<P> connection(Connection connection) {
    return new DefaultTransactionalJobBuilder<>(
        this, Objects.requireNonNull(connection, "connection"));
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
}
