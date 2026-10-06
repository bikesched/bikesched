// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.job.TransactionalJobBuilder;

/** The {@link TransactionalJobBuilder} implementation. */
final class DefaultTransactionalJobBuilder<P> extends BaseJobBuilder<TransactionalJobBuilder<P>, P>
    implements TransactionalJobBuilder<P> {

  private final Connection connection;

  DefaultTransactionalJobBuilder(DefaultJobBuilder<P> settings, Connection connection) {
    super(settings);
    this.connection = connection;
  }

  @Override
  TransactionalJobBuilder<P> self() {
    return this;
  }

  @Override
  public UUID schedule() {
    return schedule(Instant.now());
  }

  @Override
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    return scheduler.schedule(newJob(at), handler, connection);
  }

  @Override
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }
}
