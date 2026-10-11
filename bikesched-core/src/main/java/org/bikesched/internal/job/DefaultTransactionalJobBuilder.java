// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.TransactionalJobBuilder;
import org.bikesched.job.TypedJobFailureHandler;
import org.bikesched.job.TypedJobHandler;

/** The {@link TransactionalJobBuilder} implementation. */
final class DefaultTransactionalJobBuilder<P> extends BaseJobBuilder<TransactionalJobBuilder<P>, P>
    implements TransactionalJobBuilder<P> {

  private final Connection connection;

  DefaultTransactionalJobBuilder(BaseJobBuilder<?, P> settings, Connection connection) {
    super(settings);
    this.connection = connection;
  }

  @Override
  TransactionalJobBuilder<P> self() {
    return this;
  }

  @Override
  public TransactionalJobBuilder<P> handler(JobHandler handler) {
    return setHandler(handler);
  }

  @Override
  public TransactionalJobBuilder<P> handler(TypedJobHandler<? super P> handler) {
    return setHandler(handler);
  }

  @Override
  public TransactionalJobBuilder<P> failureHandler(JobFailureHandler handler) {
    return setFailureHandler(handler);
  }

  @Override
  public TransactionalJobBuilder<P> failureHandler(TypedJobFailureHandler<? super P> handler) {
    return setFailureHandler(handler);
  }

  @Override
  public TransactionalJobBuilder<P> idempotencyKey(String idempotencyKey) {
    return setIdempotencyKey(idempotencyKey);
  }

  @Override
  public UUID schedule() {
    return schedule(Instant.now());
  }

  @Override
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    return scheduler.schedule(newJob(at), handler, failureHandler, connection);
  }

  @Override
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }
}
