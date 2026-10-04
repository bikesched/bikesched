package org.bikesched.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.event.EventCleanUp;
import org.bikesched.job.Job;
import org.bikesched.job.JobBuilder;
import org.junit.jupiter.api.Test;

/** Jobs waiting for events via {@link Job#waitForEvent}, resumed by {@link Bikesched#newEvent}. */
class EventTest extends AbstractBikeschedTest {

  @Test
  void jobWaitsForEventAndContinuesWithItsPayload() throws Exception {
    var runs = new AtomicInteger();
    var invoices = new AtomicInteger();
    List<String> received = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = node("node-event-wait")) {
      UUID jobId =
          bikesched
              .newJob("event-wait")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    job.step(
                        "invoice",
                        String.class,
                        step -> "{\"invoice\": " + invoices.incrementAndGet() + "}");
                    String payment =
                        job.waitForEvent("await-payment", "payment-received", "order-1")
                            .receive(
                                event -> event.name() + "/" + event.key() + " " + event.payload());
                    received.add(payment);
                  })
              .schedule();

      awaitState(jobId, "WAITING");
      assertThat(count("id = '" + jobId + "' and attempts = 0 and scheduled_at = 'infinity'"))
          .as("waiting does not use up an attempt")
          .isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + jobId + "' and name = 'await-payment' and state = 'WAITING'"))
          .isOne();

      List<ReceivedEvent> steps =
          bikesched.newEvent("payment-received", "order-1").payload("{\"amount\": 42}").publish();

      assertThat(steps).containsExactly(new ReceivedEvent(jobId, "await-payment"));
      awaitState(jobId, "SUCCEEDED");
      assertThat(count("id = '" + jobId + "' and attempts = 1")).isEqualTo(1);
    }
    assertThat(runs).as("handler ran again after the wait").hasValue(2);
    assertThat(invoices).as("completed step not executed again").hasValue(1);
    assertThat(received).containsExactly("payment-received/order-1 {\"amount\": 42}");
  }

  @Test
  void eventPublishedBeforeTheJobWaitsIsReceivedWithoutSuspending() throws Exception {
    var runs = new AtomicInteger();
    try (Bikesched bikesched = node("node-event-early")) {
      assertThat(bikesched.newEvent("approved", "early-1").payload("\"yes\"").publish())
          .as("nobody waiting yet")
          .isEmpty();

      UUID jobId =
          bikesched
              .newJob("event-early")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    assertThat(
                            job.waitForEvent("approval", "approved", "early-1")
                                .receive(String.class))
                        .isEqualTo("\"yes\"");
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
    }
    assertThat(runs).hasValue(1);
  }

  @Test
  void eventIsDeliveredToAllWaitingJobs() throws Exception {
    try (Bikesched bikesched = node("node-event-broadcast")) {
      JobBuilder newJob =
          bikesched
              .newJob("event-broadcast")
              .handler(
                  job -> job.waitForEvent("wait", "released", "batch-1").receive(String.class));
      UUID first = newJob.schedule();
      UUID second = newJob.schedule();
      awaitState(first, "WAITING");
      awaitState(second, "WAITING");

      List<ReceivedEvent> steps = bikesched.newEvent("released", "batch-1").publish();

      assertThat(steps).extracting(ReceivedEvent::jobId).containsExactlyInAnyOrder(first, second);
      awaitState(first, "SUCCEEDED");
      awaitState(second, "SUCCEEDED");
      assertThat(bikesched.newEvent("released", "batch-1").publish())
          .as("received steps do not receive again")
          .isEmpty();
    }
  }

  @Test
  void waitWithTimeoutReturnsTheTimeoutHandlersResultAfterTheDeadline() throws Exception {
    List<String> results = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = node("node-event-timeout")) {
      UUID jobId =
          bikesched
              .newJob("event-timeout")
              .handler(
                  job -> {
                    String approval =
                        job.waitForEvent("approval", "approved", "timeout-1")
                            .timeout(Duration.ofMillis(300), () -> "timed out")
                            .receive(Event::payload);
                    results.add(approval);
                    if (approval.equals("timed out")) {
                      job.cancel("not approved in time");
                    }
                  })
              .schedule();

      awaitState(jobId, "WAITING");
      assertThat(count("id = '" + jobId + "' and scheduled_at < now() + interval '1 minute'"))
          .as("the deadline is the job's next run")
          .isEqualTo(1);
      awaitState(jobId, "CANCELLED");

      assertThat(results).containsExactly("timed out");
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + jobId + "' and name = 'approval' and state = 'TIMED_OUT'"))
          .isOne();
    }
  }

  @Test
  void waitWithTimeoutReceivesEventInTime() throws Exception {
    List<String> results = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = node("node-event-in-time")) {
      UUID jobId =
          bikesched
              .newJob("event-in-time")
              .handler(
                  job ->
                      results.add(
                          job.waitForEvent("approval", "approved", "in-time-1")
                              .<String>timeout(Duration.ofMinutes(5), () -> null)
                              .receive(String.class)))
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent("approved", "in-time-1").payload("\"ok\"").publish();

      awaitState(jobId, "SUCCEEDED");
    }
    assertThat(results).containsExactly("\"ok\"");
  }

  @Test
  void waitWithoutTimeoutFailsTheJobAfterTheDefaultEventTimeout() throws Exception {
    var runs = new AtomicInteger();
    List<Throwable> thrown = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched =
        testNode("node-event-default-timeout")
            .defaultEventTimeout(Duration.ofMillis(300))
            .build()) {
      UUID jobId =
          bikesched
              .newJob("event-default-timeout")
              .maxAttempts(5)
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    try {
                      job.waitForEvent("wait", "never", "default-1").receive(String.class);
                    } catch (EventTimeoutException e) {
                      thrown.add(e);
                      throw e;
                    }
                  })
              .schedule();
      UUID ownTimeout =
          bikesched
              .newJob("event-own-timeout")
              .handler(
                  job ->
                      job.waitForEvent("wait", "never", "own-1")
                          .<String>timeout(Duration.ofMinutes(5), () -> null)
                          .receive(String.class))
              .schedule();

      awaitState(jobId, "FAILED");

      String message =
          "Waiting for event never/default-1 in step 'wait' timed out after PT0.3S"
              + " (default event timeout)";
      assertThat(thrown).singleElement().extracting(Throwable::getMessage).isEqualTo(message);
      assertThat(runs).as("no retries after the timeout").hasValue(2);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 1 and last_error = '"
                      + message.replace("'", "''")
                      + "'"))
          .isEqualTo(1);
      assertThat(count("id = '" + ownTimeout + "' and state = 'WAITING'"))
          .as("a wait's own timeout replaces the default")
          .isEqualTo(1);
    }
  }

  @Test
  void eventPublishedInCallersTransactionIsDeliveredOnCommitOnly() throws Exception {
    try (Bikesched bikesched = node("node-event-tx")) {
      UUID jobId =
          bikesched
              .newJob("event-tx")
              .handler(job -> job.waitForEvent("wait", "shipped", "tx-1").receive(String.class))
              .schedule();
      awaitState(jobId, "WAITING");

      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        assertThat(bikesched.newEvent("shipped", "tx-1").connection(connection).publish())
            .hasSize(1);
        Thread.sleep(300);
        assertThat(count("id = '" + jobId + "' and state = 'WAITING'"))
            .as("not resumed before the commit")
            .isEqualTo(1);
        connection.commit();
      }

      awaitState(jobId, "SUCCEEDED");
    }
  }

  @Test
  void suspensionCaughtByTheHandlerStillSuspendsTheJob() throws Exception {
    try (Bikesched bikesched = node("node-event-caught")) {
      UUID jobId =
          bikesched
              .newJob("event-caught")
              .handler(
                  job -> {
                    try {
                      job.waitForEvent("wait", "done", "caught-1").receive(String.class);
                    } catch (Throwable t) {
                      throw new IllegalStateException("swallowed " + t.getClass().getSimpleName());
                    }
                  })
              .schedule();

      awaitState(jobId, "WAITING");
      assertThat(count("id = '" + jobId + "' and last_error is null")).isEqualTo(1);
    }
  }

  @Test
  void failingEventHandlerFailsTheAttemptAndGetsTheEventAgain() throws Exception {
    var calls = new AtomicInteger();
    try (Bikesched bikesched = node("node-event-handler-fails")) {
      UUID jobId =
          bikesched
              .newJob("event-handler-fails")
              .handler(
                  job ->
                      job.waitForEvent("wait", "done", "handler-1")
                          .receive(
                              event -> {
                                if (calls.incrementAndGet() == 1) {
                                  throw new IllegalStateException("boom");
                                }
                                return event.payload();
                              }))
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent("done", "handler-1").payload("1").publish();

      awaitState(jobId, "SUCCEEDED");
      assertThat(count("id = '" + jobId + "' and attempts = 2")).isEqualTo(1);
    }
    assertThat(calls).hasValue(2);
  }

  /**
   * Publishes events while jobs start waiting for them, on several nodes: no job may miss its event
   * (a lost wakeup would leave it WAITING forever).
   */
  @Test
  void noEventIsMissedWhenPublishingConcurrentlyWithWaiting() throws Exception {
    int jobs = 40;
    List<Bikesched> nodes = new ArrayList<>();
    try {
      for (int i = 1; i <= 3; i++) {
        nodes.add(node("node-event-race-" + i));
      }
      for (Bikesched node : nodes) {
        node.registerJobHandler(
            "event-race",
            job -> {
              Thread.sleep(ThreadLocalRandom.current().nextInt(20));
              job.waitForEvent("wait", "race", job.payload(String.class)).receive(String.class);
            });
      }
      List<UUID> jobIds = new ArrayList<>();
      for (int i = 0; i < jobs; i++) {
        jobIds.add(nodes.getFirst().newJob("event-race").payload("\"race-" + i + "\"").schedule());
      }
      for (int i = 0; i < jobs; i++) {
        Thread.sleep(ThreadLocalRandom.current().nextInt(10));
        nodes.get(i % nodes.size()).newEvent("race", "\"race-" + i + "\"").publish();
      }

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(count("job_type = 'event-race' and state = 'SUCCEEDED'"))
                      .isEqualTo(jobs));
    } finally {
      nodes.forEach(Bikesched::close);
    }
  }

  @Test
  void eventCleanUpDeletesEventsOlderThanTheRetention() throws Exception {
    String key = "cleanup-" + UUID.randomUUID();
    var logs = LogCapture.of(EventCleanUp.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-event-cleanup")
                .eventRetention(Duration.ofMillis(200))
                .eventCleanUpInterval(Duration.ofMillis(50))
                .build()) {
      bikesched.newEvent("cleaned-up", key).publish();
      assertThat(countRows("bikesched_events", "event_key = '" + key + "'")).isOne();

      await()
          .untilAsserted(
              () ->
                  assertThat(countRows("bikesched_events", "event_key = '" + key + "'")).isZero());
    }
    assertThat(logs.messages())
        .anyMatch(m -> m.matches("Deleted \\d+ event\\(s\\) published more than PT0.2S ago"));
  }

  private static Bikesched node(String name) {
    return testNode(name).build();
  }
}
