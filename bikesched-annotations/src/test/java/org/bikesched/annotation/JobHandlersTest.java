package org.bikesched.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bikesched.Bikesched;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.Job;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Annotated job handlers registered on a node and running jobs. */
class JobHandlersTest {

  public record Mail(String to) {}

  /** One container for this test class; Ryuk removes it when the JVM ends. */
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  private static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
  }

  public static class MailJobs {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @JobHandler(type = "send-mail")
    public void sendMail(Job job, Mail mail) {
      calls.add("send-mail " + mail.to() + " attempt " + job.attempts());
    }

    @JobHandler(type = "log-mail")
    public void logMail(Mail mail) {
      calls.add("log-mail " + mail.to());
    }

    @JobHandler(type = "ping")
    public void ping() {
      calls.add("ping");
    }

    @JobHandler(type = "flaky")
    public void flaky(Job job) throws Exception {
      if (job.attempts() == 1) {
        throw new java.io.IOException("server down");
      }
      calls.add("flaky attempt " + job.attempts());
    }
  }

  @Test
  void annotatedMethodsHandleJobs() {
    var jobs = new MailJobs();
    try (Bikesched bikesched = node()) {
      assertThat(JobHandlers.register(bikesched, jobs))
          .extracting(AnnotatedJobHandler::type)
          .containsExactly("flaky", "log-mail", "ping", "send-mail");

      List<UUID> ids =
          List.of(
              bikesched
                  .newJob(JobType.of("send-mail", Mail.class))
                  .payload(new Mail("a@x"))
                  .schedule(),
              bikesched
                  .newJob(JobType.of("log-mail", Mail.class))
                  .payload(new Mail("b@x"))
                  .schedule(),
              bikesched.newJob("ping").schedule(),
              bikesched.newJob("flaky").schedule());
      for (UUID id : ids) {
        await()
            .atMost(Duration.ofSeconds(15))
            .until(() -> bikesched.findJob(id).orElseThrow().state(), JobState.SUCCEEDED::equals);
      }
    }
    assertThat(jobs.calls)
        .containsExactlyInAnyOrder(
            "send-mail a@x attempt 1", "log-mail b@x", "ping", "flaky attempt 2");
  }

  @Test
  void payloadTypeIsCheckedLikeForTypedHandlers() {
    try (Bikesched bikesched = node()) {
      JobHandlers.register(bikesched, new MailJobs());

      assertThatThrownBy(() -> bikesched.newJob(JobType.of("send-mail", String.class)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already used with payload type");
      assertThatThrownBy(() -> JobHandlers.register(bikesched, new MailJobs()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }
  }

  private static Bikesched node() {
    return Bikesched.builder()
        .dataSource(DATA_SOURCE)
        .nodeName("annotations-" + UUID.randomUUID())
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
