// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedBuilder;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.FailedJob;
import org.bikesched.job.Job;
import org.bikesched.job.JobState;
import org.bikesched.job.Recurrence;
import org.bikesched.job.RecurrenceParser;
import org.bikesched.job.TypedJob;
import org.bikesched.test.BikeschedTest;
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

    @JobHandler(name = "send-mail")
    public void sendMail(Job job, Mail mail) {
      calls.add("send-mail " + mail.to() + " attempt " + job.attempts());
    }

    @JobHandler(name = "log-mail")
    public void logMail(Mail mail) {
      calls.add("log-mail " + mail.to());
    }

    @JobHandler(name = "ping")
    public void ping() {
      calls.add("ping");
    }

    @JobHandler(name = "flaky")
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
          .extracting(AnnotatedJobHandler::name)
          .containsExactly("flaky", "log-mail", "ping", "send-mail");

      List<UUID> ids =
          List.of(
              bikesched
                  .newJob(TypedJob.of("send-mail", Mail.class))
                  .payload(new Mail("a@x"))
                  .schedule(),
              bikesched
                  .newJob(TypedJob.of("log-mail", Mail.class))
                  .payload(new Mail("b@x"))
                  .schedule(),
              bikesched.newJob("ping").schedule(),
              bikesched.newJob("flaky").schedule());
      for (UUID id : ids) {
        BikeschedTest.awaitJob(bikesched, id).timeout(Duration.ofSeconds(15)).untilSucceeded();
      }
    }
    assertThat(jobs.calls)
        .containsExactlyInAnyOrder(
            "send-mail a@x attempt 1", "log-mail b@x", "ping", "flaky attempt 2");
  }

  public static class PaymentJobs {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @JobHandler(name = "annotated-charge")
    public void charge(Mail mail) {
      throw new IllegalStateException("declined");
    }

    @JobFailureHandler(name = "annotated-charge")
    public void chargeFailed(Mail mail, FailedJob failed) {
      calls.add("failed " + mail.to() + ": " + failed.error());
    }

    @JobHandler(name = "annotated-ping-failing")
    public void ping() {
      throw new IllegalStateException("down");
    }

    @JobFailureHandler(name = "annotated-ping-failing")
    public void pingFailed() {
      calls.add("ping failed");
    }
  }

  @Test
  void annotatedFailureHandlersRunForFailedJobs() {
    var jobs = new PaymentJobs();
    try (Bikesched bikesched = node()) {
      assertThat(JobHandlers.register(bikesched, jobs)).hasSize(4);

      List<UUID> ids =
          List.of(
              bikesched
                  .newJob(TypedJob.of("annotated-charge", Mail.class))
                  .payload(new Mail("a@x"))
                  .maxAttempts(1)
                  .schedule(),
              bikesched.newJob("annotated-ping-failing").maxAttempts(1).schedule());
      for (UUID id : ids) {
        BikeschedTest.awaitJob(bikesched, id)
            .timeout(Duration.ofSeconds(15))
            .untilState(JobState.FAILED);
      }
      assertThatThrownBy(() -> JobHandlers.register(bikesched, new PaymentJobs()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(jobs.calls)
        .containsExactlyInAnyOrder(
            "failed a@x: java.lang.IllegalStateException: declined", "ping failed");
  }

  public static class RecurringJobs {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @JobHandler(name = "recurring-ping", schedule = "PT0.1S")
    public void ping() {
      calls.add("ping");
    }
  }

  @Test
  void annotatedRecurringHandlerRuns() {
    var jobs = new RecurringJobs();
    try (Bikesched bikesched = node()) {
      JobHandlers.register(bikesched, jobs);
      await().atMost(Duration.ofSeconds(15)).until(() -> !jobs.calls.isEmpty());
    }
    assertThat(jobs.calls).isNotEmpty();
  }

  public static class FixedDelayJobs {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @JobHandler(name = "fixed-delay-sync", fixedDelay = "PT1H", initialDelay = "PT0S")
    public void sync() {
      calls.add("sync");
    }
  }

  @Test
  void annotatedFixedDelayHandlerRunsAfterItsInitialDelay() {
    var jobs = new FixedDelayJobs();
    try (Bikesched bikesched = node()) {
      JobHandlers.register(bikesched, jobs);
      await().atMost(Duration.ofSeconds(15)).until(() -> !jobs.calls.isEmpty());

      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(
              () -> {
                var runs = bikesched.admin().findRecurringJobRuns("fixed-delay-sync", 10);
                assertThat(runs.previous()).hasSize(1);
                assertThat(runs.pending()).isNotNull();
                assertThat(runs.pending().recurrence()).isEqualTo("every PT1H after end");
              });
      bikesched.removeRecurringJob("fixed-delay-sync");
    }
    assertThat(jobs.calls).containsExactly("sync");
  }

  public static class InitialDelayJobs {
    @JobHandler(name = "initial-delay-report", schedule = "PT1H", initialDelay = "PT0S")
    public void report() {}
  }

  @Test
  void initialDelayAppliesToANewFirstOccurrenceOnly() {
    try (Bikesched first = node()) {
      first.newJob("initial-delay-report").handler(() -> {}).schedule("PT1H", Duration.ofHours(2));
    }
    try (Bikesched bikesched = node()) {
      JobHandlers.register(bikesched, new InitialDelayJobs());

      var pending = bikesched.admin().findRecurringJobRuns("initial-delay-report", 10).pending();
      assertThat(pending).isNotNull();
      assertThat(pending.scheduledAt())
          .as("registering again keeps the pending occurrence")
          .isAfter(Instant.now().plus(Duration.ofMinutes(100)));
      bikesched.removeRecurringJob("initial-delay-report");
    }
  }

  public static class CustomScheduleJobs {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @JobHandler(name = "custom-schedule-ping", schedule = "every 100 ms")
    public void ping() {
      calls.add("ping");
    }
  }

  @Test
  void schedulesAreParsedByTheNodesRecurrenceParsers() {
    RecurrenceParser everyMillis =
        new RecurrenceParser() {
          @Override
          public boolean supports(String expression) {
            return expression.matches("every \\d+ ms");
          }

          @Override
          public Recurrence parse(String expression) {
            return Recurrence.every(Duration.ofMillis(Long.parseLong(expression.split(" ")[1])));
          }
        };
    var jobs = new CustomScheduleJobs();
    try (Bikesched bikesched =
        nodeBuilder().recurrenceParsers(RecurrenceParser.isoDuration(), everyMillis).build()) {
      JobHandlers.register(bikesched, jobs);
      await().atMost(Duration.ofSeconds(15)).until(() -> !jobs.calls.isEmpty());
      bikesched.removeRecurringJob("custom-schedule-ping");
    }
  }

  @Test
  void anUnsupportedScheduleFailsTheRegistration() {
    try (Bikesched bikesched = node()) {
      assertThatThrownBy(() -> JobHandlers.register(bikesched, new CustomScheduleJobs()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("No RecurrenceParser supports recurrence 'every 100 ms'");
    }
  }

  public interface MailApi {
    void send(Mail mail);
  }

  /** Implements an interface, so the bean can be proxied with a JDK dynamic proxy. */
  public static class ProxiedMailJobs implements MailApi {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @JobHandler(name = "proxied-mail")
    public void send(Mail mail) {
      calls.add("send " + mail.to());
    }
  }

  @Test
  void aProxiedBeansHandlerIsInvokedThroughTheProxy() {
    var jobs = new ProxiedMailJobs();
    var throughProxy = new CopyOnWriteArrayList<String>();
    MailApi proxy =
        (MailApi)
            java.lang.reflect.Proxy.newProxyInstance(
                MailApi.class.getClassLoader(),
                new Class<?>[] {MailApi.class},
                (instance, method, args) -> {
                  throughProxy.add(method.getName());
                  return method.invoke(jobs, args);
                });
    try (Bikesched bikesched = node()) {
      assertThat(JobHandlers.register(bikesched, proxy, ProxiedMailJobs.class))
          .extracting(AnnotatedJobHandler::name)
          .containsExactly("proxied-mail");

      UUID jobId =
          bikesched
              .newJob(TypedJob.of("proxied-mail", Mail.class))
              .payload(new Mail("c@x"))
              .schedule();
      BikeschedTest.awaitJob(bikesched, jobId).timeout(Duration.ofSeconds(15)).untilSucceeded();
    }
    assertThat(throughProxy).as("the proxy, not the target, was called").containsExactly("send");
    assertThat(jobs.calls).containsExactly("send c@x");
  }

  @Test
  void anUnboundHandlerCannotBeRegistered() {
    AnnotatedJobHandler unbound = JobHandlers.scan(MailJobs.class).getFirst();
    try (Bikesched bikesched = node()) {
      assertThatThrownBy(() -> unbound.registerOn(bikesched))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("is not bound to a target");
    }
  }

  @Test
  void aDuplicateRegistrationNamesTheSource() {
    try (Bikesched bikesched = node()) {
      JobHandlers.scan(MailJobs.class)
          .forEach(
              handler -> handler.bindTo(new MailJobs()).registerOn(bikesched, "bean mailJobs"));
      assertThatThrownBy(
              () ->
                  JobHandlers.scan(MailJobs.class)
                      .forEach(
                          handler ->
                              handler
                                  .bindTo(new MailJobs())
                                  .registerOn(bikesched, "bean otherMailJobs")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered")
          .hasMessageContaining("(declared by bean otherMailJobs)");
    }
  }

  @Test
  void payloadTypeIsCheckedLikeForTypedHandlers() {
    try (Bikesched bikesched = node()) {
      JobHandlers.register(bikesched, new MailJobs());

      assertThatThrownBy(() -> bikesched.newJob(TypedJob.of("send-mail", String.class)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already used with payload type");
      assertThatThrownBy(() -> JobHandlers.register(bikesched, new MailJobs()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }
  }

  private static Bikesched node() {
    return nodeBuilder().build();
  }

  private static BikeschedBuilder nodeBuilder() {
    return BikeschedTest.testNode(DATA_SOURCE, "annotations").jsonMapper(new JacksonJsonMapper());
  }
}
