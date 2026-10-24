// SPDX-License-Identifier: Apache-2.0
package org.bikesched.cron;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.cronutils.model.CronType;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bikesched.Bikesched;
import org.bikesched.annotation.JobHandler;
import org.bikesched.annotation.JobHandlers;
import org.bikesched.job.RecurrenceParser;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Recurring jobs on a cron schedule, run by a node. */
class CronRecurringJobTest {

  /** One container for this test class; Ryuk removes it when the JVM ends. */
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  private static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
  }

  public static class EverySecondJobs {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @JobHandler(name = "cron-annotated", schedule = "* * * * * *")
    public void tick() {
      calls.add("tick");
    }
  }

  @Test
  void cronRecurrenceRunsARecurringJob() {
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = node(RecurrenceParser.isoDuration())) {
      bikesched
          .newJob("cron-recurring")
          .handler(job -> calls.add("run"))
          .schedule(CronRecurrence.of("* * * * * *", CronType.SPRING53, ZoneOffset.UTC));
      await().atMost(Duration.ofSeconds(15)).until(() -> calls.size() >= 2);
      bikesched.removeRecurringJob("cron-recurring");
    }
  }

  @Test
  void scheduleStringIsParsedByTheConfiguredCronParser() {
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = node(everySecondCron())) {
      bikesched.newJob("cron-string").handler(job -> calls.add("run")).schedule("* * * * * *");
      await().atMost(Duration.ofSeconds(15)).until(() -> calls.size() >= 2);
      bikesched.removeRecurringJob("cron-string");
    }
  }

  @Test
  void annotatedCronScheduleRunsARecurringJob() {
    var jobs = new EverySecondJobs();
    try (Bikesched bikesched = node(RecurrenceParser.isoDuration(), everySecondCron())) {
      JobHandlers.register(bikesched, jobs);
      await().atMost(Duration.ofSeconds(15)).until(() -> jobs.calls.size() >= 2);
      bikesched.removeRecurringJob("cron-annotated");
    }
  }

  @Test
  void withoutTheCronParserTheNodeRejectsCronExpressions() {
    try (Bikesched bikesched = node(RecurrenceParser.isoDuration())) {
      assertThatThrownBy(
              () -> bikesched.newJob("cron-unparsed").handler(job -> {}).schedule("* * * * * *"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("No RecurrenceParser supports recurrence '* * * * * *'");
    }
  }

  private static CronRecurrenceParser everySecondCron() {
    return new CronRecurrenceParser(CronType.SPRING53, ZoneOffset.UTC);
  }

  private static Bikesched node(RecurrenceParser... parsers) {
    return BikeschedTest.testNode(DATA_SOURCE, "cron").recurrenceParsers(parsers).build();
  }
}
