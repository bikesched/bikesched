package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.RecurringJobBuilder;
import org.bikesched.job.Schedule;
import org.bikesched.job.TransactionalJobBuilder;

/** The {@link JobBuilder} implementation. */
public final class DefaultJobBuilder<P> extends BaseJobBuilder<JobBuilder<P>, P>
    implements JobBuilder<P> {

  public DefaultJobBuilder(
      String type, Class<P> payloadType, JsonMapper jsonMapper, Scheduler scheduler) {
    super(type, payloadType, jsonMapper, scheduler);
  }

  @Override
  JobBuilder<P> self() {
    return this;
  }

  @Override
  public TransactionalJobBuilder<P> connection(Connection connection) {
    return new DefaultTransactionalJobBuilder<>(
        this, Objects.requireNonNull(connection, "connection"));
  }

  @Override
  public RecurringJobBuilder<P> recurring(Schedule schedule) {
    return new DefaultRecurringJobBuilder<>(this, Objects.requireNonNull(schedule, "schedule"));
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
