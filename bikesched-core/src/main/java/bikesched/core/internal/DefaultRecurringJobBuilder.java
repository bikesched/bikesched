package bikesched.core.internal;

import bikesched.core.job.RecurringJobBuilder;
import bikesched.core.job.Schedule;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The {@link RecurringJobBuilder} implementation. */
final class DefaultRecurringJobBuilder extends BaseJobBuilder<RecurringJobBuilder>
    implements RecurringJobBuilder {

  private final Schedule schedule;

  DefaultRecurringJobBuilder(DefaultJobBuilder settings, Schedule schedule) {
    super(settings);
    this.schedule = schedule;
  }

  @Override
  RecurringJobBuilder self() {
    return this;
  }

  @Override
  public UUID schedule() {
    return scheduler.scheduleRecurring(recurringJob(), handler, null);
  }

  @Override
  public UUID schedule(Instant firstRun) {
    Objects.requireNonNull(firstRun, "firstRun");
    return scheduler.scheduleRecurring(recurringJob(), handler, firstRun);
  }

  @Override
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }

  private RecurringJob recurringJob() {
    return new RecurringJob(type, schedule, payload, priority, maxAttempts, timeout);
  }
}
