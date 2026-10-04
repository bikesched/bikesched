package bikesched.core.internal.job;

import bikesched.core.JsonMapper;
import bikesched.core.job.JobBuilder;
import bikesched.core.job.RecurringJobBuilder;
import bikesched.core.job.Schedule;
import bikesched.core.job.TransactionalJobBuilder;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The {@link JobBuilder} implementation. */
public final class DefaultJobBuilder extends BaseJobBuilder<JobBuilder> implements JobBuilder {

  public DefaultJobBuilder(String type, JsonMapper jsonMapper, Scheduler scheduler) {
    super(type, jsonMapper, scheduler);
  }

  @Override
  JobBuilder self() {
    return this;
  }

  @Override
  public TransactionalJobBuilder connection(Connection connection) {
    return new DefaultTransactionalJobBuilder(
        this, Objects.requireNonNull(connection, "connection"));
  }

  @Override
  public RecurringJobBuilder recurring(Schedule schedule) {
    return new DefaultRecurringJobBuilder(this, Objects.requireNonNull(schedule, "schedule"));
  }

  @Override
  public UUID schedule() {
    return schedule(Instant.now());
  }

  @Override
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    return scheduler.schedule(newJob(at), handler, null);
  }

  @Override
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }
}
