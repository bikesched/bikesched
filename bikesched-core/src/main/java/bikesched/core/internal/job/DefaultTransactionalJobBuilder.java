package bikesched.core.internal.job;

import bikesched.core.job.TransactionalJobBuilder;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The {@link TransactionalJobBuilder} implementation. */
final class DefaultTransactionalJobBuilder extends BaseJobBuilder<TransactionalJobBuilder>
    implements TransactionalJobBuilder {

  private final Connection connection;

  DefaultTransactionalJobBuilder(DefaultJobBuilder settings, Connection connection) {
    super(settings);
    this.connection = connection;
  }

  @Override
  TransactionalJobBuilder self() {
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
