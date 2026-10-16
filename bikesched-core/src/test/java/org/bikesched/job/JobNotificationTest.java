// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedBuilder;
import org.bikesched.LogCapture;
import org.bikesched.internal.job.DueJobsNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * Due jobs start right away instead of on the next poll: notified by the node that made them due
 * (LISTEN/NOTIFY), or by the node's own wake-up timer. The workers poll every minute, so only
 * notifications and the timer can start the jobs in time.
 */
class JobNotificationTest extends AbstractBikeschedTest {

  private static final Duration MINUTE = Duration.ofMinutes(1);

  /** How late a due job may start: the notify interval (100 ms) plus a generous margin. */
  private static final Duration IN_TIME = Duration.ofSeconds(1);

  private final Map<UUID, Instant> started = new ConcurrentHashMap<>();

  @AfterEach
  void deleteJobs() throws SQLException {
    execute("delete from bikesched_jobs where type like 'notify-%'");
  }

  private static BikeschedBuilder worker(String name) {
    return testNode(name).pollInterval(MINUTE);
  }

  /** A node that schedules and publishes, but does not poll. */
  private static BikeschedBuilder client(String name) {
    return testNode(name).pollInterval(MINUTE).autoStartPolling(false);
  }

  /** Records when a job's handler started. */
  private JobHandler recordStart() {
    return job -> started.putIfAbsent(job.id(), Instant.now());
  }

  private void assertStartedInTime(UUID jobId, Instant dueAt) {
    await().atMost(Duration.ofSeconds(5)).until(() -> started.containsKey(jobId));
    assertThat(Duration.between(dueAt, started.get(jobId))).isLessThan(IN_TIME);
  }

  @Test
  void aJobScheduledByAnotherNodeStartsRightAway() {
    try (Bikesched worker = worker("node-notify-worker").build();
        Bikesched client = client("node-notify-client").build()) {
      worker.registerJobHandler("notify-now", recordStart());
      client.registerJobHandler("notify-now", ignored -> {});

      Instant scheduled = Instant.now();
      UUID jobId = client.newJob("notify-now").schedule();

      assertStartedInTime(jobId, scheduled);
    }
  }

  @Test
  void anEventPublishedByAnotherNodeResumesTheWaitingJobRightAway() {
    Map<UUID, Instant> received = new ConcurrentHashMap<>();
    try (Bikesched worker = worker("node-notify-event-worker").build();
        Bikesched client = client("node-notify-event-client").build()) {
      worker.registerJobHandler(
          "notify-event",
          job -> {
            job.waitForEvent("wait", "notify-paid", job.id().toString(), String.class).receive();
            received.put(job.id(), Instant.now());
          });
      UUID jobId = worker.newJob("notify-event").schedule();
      awaitState(jobId, "WAITING");

      Instant published = Instant.now();
      client.newEvent("notify-paid", jobId.toString()).payload("\"paid\"").publish();

      await().atMost(Duration.ofSeconds(5)).until(() -> received.containsKey(jobId));
      assertThat(Duration.between(published, received.get(jobId))).isLessThan(IN_TIME);
    }
  }

  @Test
  void aParentResumesRightAwayWhenItsChildEnds() {
    Map<String, Instant> ended = new ConcurrentHashMap<>();
    try (Bikesched worker = worker("node-notify-child").build()) {
      worker.registerJobHandler(
          "notify-child",
          ignored -> {
            sleep(300);
            ended.put("child", Instant.now());
          });
      worker.registerJobHandler(
          "notify-parent",
          job -> {
            UUID child = job.newJob("notify-child").schedule();
            job.waitForJob(child, String.class);
            ended.put("parent", Instant.now());
          });
      worker.newJob("notify-parent").schedule();

      await().atMost(Duration.ofSeconds(5)).until(() -> ended.containsKey("parent"));
      assertThat(Duration.between(ended.get("child"), ended.get("parent"))).isLessThan(IN_TIME);
    }
  }

  @Test
  void retryingAndCancellingFromAnotherNodeTakeEffectRightAway() {
    AtomicInteger attempts = new AtomicInteger();
    try (Bikesched worker = worker("node-notify-retry-worker").build();
        Bikesched client = client("node-notify-retry-client").build()) {
      worker.registerJobHandler(
          "notify-retry",
          job -> {
            if (attempts.incrementAndGet() == 1) {
              throw new IllegalStateException("first run fails");
            }
            started.put(job.id(), Instant.now());
          });
      worker.registerJobHandler(
          "notify-cancel",
          job -> job.waitForEvent("wait", "notify-never", "x", String.class).receive());
      UUID failed = worker.newJob("notify-retry").maxAttempts(1).schedule();
      UUID waiting = worker.newJob("notify-cancel").schedule();
      awaitState(failed, "FAILED");
      awaitState(waiting, "WAITING");

      Instant requested = Instant.now();
      assertThat(client.retry(failed)).isTrue();
      assertThat(client.cancel(waiting)).isTrue();

      assertStartedInTime(failed, requested);
      await()
          .atMost(IN_TIME)
          .untilAsserted(() -> assertThat(state(waiting)).isEqualTo("CANCELLED"));
    }
  }

  @Test
  void jobsReleasedByAClosingNodeStartRightAwayOnAnother() throws Exception {
    try (Bikesched worker = worker("node-notify-released").build()) {
      worker.registerJobHandler("notify-released", recordStart());
      Bikesched closing = worker("node-notify-closing").build();
      UUID closingId = closing.node().id().orElseThrow();
      UUID jobId = UUID.randomUUID();
      execute(
          "insert into bikesched_jobs (id, type, state, locked_by, lock_token, locked_at)"
              + " values ('"
              + jobId
              + "', 'notify-released', 'RUNNING', '"
              + closingId
              + "', gen_random_uuid(), now())");

      Instant closed = Instant.now();
      closing.close();

      assertStartedInTime(jobId, closed);
    }
  }

  @Test
  void delayedWorkOfTheNodeStartsWhenDue() {
    Map<String, List<Instant>> runs = new ConcurrentHashMap<>();
    try (Bikesched worker = worker("node-notify-later").build()) {
      worker.registerJobHandler(
          "notify-sleep",
          job -> {
            step(job, "start", () -> run(runs, "sleep"));
            job.sleep("nap", Duration.ofSeconds(1));
            run(runs, "sleep");
          });
      worker.registerJobHandler(
          "notify-poll",
          job ->
              job.newPoll("check", String.class, Duration.ofSeconds(1))
                  .run(
                      step -> {
                        run(runs, "poll");
                        return step.attempts() < 2 ? Poll.<String>notReady() : Poll.ready("\"ok\"");
                      }));
      worker.registerJobHandler(
          "notify-backoff",
          job -> {
            run(runs, "backoff");
            if (job.attempts() == 1) {
              job.fail().retryAfter(Duration.ofSeconds(1));
            }
          });
      worker.registerJobHandler(
          "notify-deadline",
          job -> {
            step(job, "start", () -> run(runs, "deadline"));
            job.waitForEvent("wait", "notify-never", job.id().toString(), String.class)
                .timeout(Duration.ofSeconds(1))
                .onFailure(failing -> failing.resolve("\"timed out\""))
                .receive();
            run(runs, "deadline");
          });
      worker.registerJobHandler("notify-delayed", recordStart());

      Instant scheduled = Instant.now();
      for (String type : List.of("sleep", "poll", "backoff", "deadline")) {
        worker.newJob("notify-" + type).schedule();
      }
      UUID delayed = worker.newJob("notify-delayed").schedule(Duration.ofSeconds(1));

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(runs)
                      .allSatisfy((ignored, times) -> assertThat(times).hasSize(2))
                      .hasSize(4));
      for (String kind : List.of("sleep", "poll", "backoff", "deadline")) {
        List<Instant> times = runs.get(kind);
        assertThat(Duration.between(times.get(0), times.get(1)))
            .as(kind)
            .isBetween(Duration.ofMillis(900), Duration.ofSeconds(1).plus(IN_TIME));
      }
      assertStartedInTime(delayed, scheduled.plusSeconds(1));
    }
  }

  @Test
  void occurrencesOfARecurringJobStartWhenDue() {
    List<Instant> runs = new CopyOnWriteArrayList<>();
    try (Bikesched worker = worker("node-notify-recurring").build()) {
      worker.registerJobHandler("notify-recurring", ignored -> runs.add(Instant.now()));
      worker.newJob("notify-recurring").schedule(Recurrence.every(Duration.ofSeconds(1)));

      await().atMost(Duration.ofSeconds(10)).until(() -> runs.size() >= 3);
      worker.removeRecurringJob("notify-recurring");
      for (int i = 1; i < 3; i++) {
        assertThat(Duration.between(runs.get(i - 1), runs.get(i)))
            .isBetween(Duration.ofMillis(500), Duration.ofSeconds(1).plus(IN_TIME));
      }
    }
  }

  @Test
  void aDelayedJobScheduledByAnotherNodeStartsByTheNextIntervalPoll() {
    try (Bikesched worker =
            worker("node-notify-elsewhere").pollInterval(Duration.ofSeconds(3)).build();
        Bikesched client = client("node-notify-elsewhere-client").build()) {
      worker.registerJobHandler("notify-elsewhere", recordStart());
      client.registerJobHandler("notify-elsewhere", ignored -> {});

      Instant dueAt = Instant.now().plusSeconds(1);
      UUID jobId = client.newJob("notify-elsewhere").schedule(dueAt);

      await().atMost(Duration.ofSeconds(6)).until(() -> started.containsKey(jobId));
      assertThat(started.get(jobId)).isBefore(dueAt.plusSeconds(3).plus(IN_TIME));
    }
  }

  @Test
  void jobsAndEventsInTheCallersTransactionStartAfterTheCommitOnly() throws Exception {
    Map<UUID, Instant> received = new ConcurrentHashMap<>();
    try (Bikesched worker = worker("node-notify-caller").build();
        Bikesched client = client("node-notify-caller-client").build();
        Connection connection = dataSource.getConnection()) {
      worker.registerJobHandler("notify-caller", recordStart());
      worker.registerJobHandler(
          "notify-caller-event",
          job -> {
            job.waitForEvent("wait", "notify-caller-paid", job.id().toString(), String.class)
                .receive();
            received.put(job.id(), Instant.now());
          });
      client.registerJobHandler("notify-caller", ignored -> {});
      UUID waiting = worker.newJob("notify-caller-event").schedule();
      awaitState(waiting, "WAITING");
      connection.setAutoCommit(false);

      UUID rolledBack = client.newJob("notify-caller").connection(connection).schedule();
      connection.rollback();
      UUID committed = client.newJob("notify-caller").connection(connection).schedule();
      client
          .newEvent("notify-caller-paid", waiting.toString())
          .payload("\"paid\"")
          .connection(connection)
          .publish();
      Thread.sleep(300);
      assertThat(started).as("before the commit").isEmpty();
      Instant commit = Instant.now();
      connection.commit();

      assertStartedInTime(committed, commit);
      await().atMost(Duration.ofSeconds(5)).until(() -> received.containsKey(waiting));
      assertThat(Duration.between(commit, received.get(waiting))).isLessThan(IN_TIME);
      assertThat(started).doesNotContainKey(rolledBack);
    }
  }

  @Test
  void aBurstOfJobsIsNotifiedInFewNotificationsAndAllRun() throws Exception {
    int jobs = 300;
    try (Bikesched worker = worker("node-notify-burst").build();
        Bikesched client = client("node-notify-burst-client").build();
        Connection listener = listen()) {
      worker.registerJobHandler("notify-burst", recordStart());
      client.registerJobHandler("notify-burst", ignored -> {});

      Instant burstStart = Instant.now();
      for (int i = 0; i < jobs; i++) {
        client.newJob("notify-burst").schedule();
      }
      Duration burst = Duration.between(burstStart, Instant.now());

      await().atMost(Duration.ofSeconds(30)).until(() -> started.size() == jobs);
      long notifications = received(listener).stream().filter("notify-burst"::equals).count();
      assertThat(notifications)
          .as("one per notify interval of the %s burst", burst)
          .isPositive()
          .isLessThanOrEqualTo(burst.toMillis() / 100 + 2);
    }
  }

  @Test
  void theNotifyIntervalDelaysTheNotification() {
    try (Bikesched worker = worker("node-notify-interval").build();
        Bikesched client =
            client("node-notify-interval-client").notifyInterval(Duration.ofMillis(500)).build()) {
      worker.registerJobHandler("notify-interval", recordStart());
      client.registerJobHandler("notify-interval", ignored -> {});
      settle();

      Instant scheduled = Instant.now();
      UUID jobId = client.newJob("notify-interval").schedule();

      await().atMost(Duration.ofSeconds(5)).until(() -> started.containsKey(jobId));
      assertThat(Duration.between(scheduled, started.get(jobId)))
          .isBetween(Duration.ofMillis(450), Duration.ofMillis(500).plus(IN_TIME));
    }
  }

  @Test
  void anIdleNodeSendsNothing() throws Exception {
    try (Bikesched idle = worker("node-notify-idle").build()) {
      idle.registerJobHandler("notify-idle", recordStart());
      Thread.sleep(1500); // its self-check is over
      try (Connection listener = listen()) {
        Thread.sleep(1000);
        assertThat(received(listener)).isEmpty();
      }
    }
  }

  @Test
  void aFailedNotificationIsDroppedWithAWarningAndLaterOnesAreSent() throws Exception {
    AtomicBoolean failing = new AtomicBoolean(true);
    DataSource failingNotifier =
        dataSource(
            () -> {
              if (failing.get() && Thread.currentThread().getName().equals("bikesched-notifier")) {
                throw new SQLException("notifying is broken", "08001");
              }
            });
    var logs = LogCapture.of(DueJobsNotifier.class);
    try (logs;
        Bikesched worker = worker("node-notify-failing").build();
        Bikesched client =
            client("node-notify-failing-client").dataSource(failingNotifier).build()) {
      worker.registerJobHandler("notify-failing", recordStart());
      client.registerJobHandler("notify-failing", ignored -> {});
      settle();

      UUID lost = client.newJob("notify-failing").schedule();
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> logs.messages().stream().anyMatch(m -> m.contains("could not notify")));
      Thread.sleep(500);
      assertThat(started).as("the notification was lost").doesNotContainKey(lost);

      failing.set(false);
      Instant scheduled = Instant.now();
      UUID notified = client.newJob("notify-failing").schedule();

      assertStartedInTime(notified, scheduled);
      assertStartedInTime(lost, scheduled); // claimed by the same poll
      assertThat(logs.messages()).filteredOn(m -> m.contains("could not notify")).hasSize(1);
    }
  }

  @Test
  void aNodeStartedAfterAJobWentToSleepWakesItOnTime() {
    List<Instant> runs = new CopyOnWriteArrayList<>();
    UUID jobId;
    try (Bikesched first = worker("node-notify-asleep-1").build()) {
      first.registerJobHandler(
          "notify-asleep",
          job -> {
            runs.add(Instant.now());
            job.sleep("nap", Duration.ofSeconds(2));
            runs.add(Instant.now());
          });
      jobId = first.newJob("notify-asleep").schedule();
      awaitState(jobId, "WAITING");
    }
    try (Bikesched second = worker("node-notify-asleep-2").autoStartPolling(false).build()) {
      second.registerJobHandler(
          "notify-asleep",
          job -> {
            job.sleep("nap", Duration.ofSeconds(2));
            runs.add(Instant.now());
          });
      second.start();

      awaitState(jobId, "SUCCEEDED");
      assertThat(Duration.between(runs.getFirst(), runs.getLast()))
          .isBetween(Duration.ofSeconds(2), Duration.ofSeconds(2).plus(IN_TIME));
    }
  }

  @Test
  void aHandlerRegisteredAfterStartingRunsItsDueAndDelayedJobsOnTime() {
    try (Bikesched worker = worker("node-notify-late-handler").build();
        Bikesched client = client("node-notify-late-handler-client").build()) {
      client.registerJobHandler("notify-late-handler", ignored -> {});
      UUID due = client.newJob("notify-late-handler").schedule();
      Instant delayedAt = Instant.now().plusSeconds(2);
      UUID delayed = client.newJob("notify-late-handler").schedule(delayedAt);

      Instant registered = Instant.now();
      worker.registerJobHandler("notify-late-handler", recordStart());

      assertStartedInTime(due, registered);
      assertStartedInTime(delayed, delayedAt);
    }
  }

  @Test
  void aJobMadeDueWhileTheNodeIsBusyStartsWhenASlotFrees() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (Bikesched worker = worker("node-notify-busy").maxConcurrentJobs(1).build();
        Bikesched client = client("node-notify-busy-client").build()) {
      worker.registerJobHandler(
          "notify-busy",
          job -> {
            started.put(job.id(), Instant.now());
            release.await();
          });
      client.registerJobHandler("notify-busy", ignored -> {});
      UUID first = client.newJob("notify-busy").schedule();
      await().atMost(Duration.ofSeconds(5)).until(() -> started.containsKey(first));

      UUID second = client.newJob("notify-busy").schedule();
      Thread.sleep(500);
      assertThat(started).as("no free slot").doesNotContainKey(second);
      Instant freed = Instant.now();
      release.countDown();

      assertStartedInTime(second, freed);
    }
  }

  @Test
  void manyDelayedJobsScheduledConcurrentlyAllStartOnTime() throws Exception {
    Map<UUID, Instant> dueAt = new ConcurrentHashMap<>();
    try (Bikesched worker = worker("node-notify-concurrent").build()) {
      worker.registerJobHandler("notify-concurrent", recordStart());
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < 4; t++) {
        threads.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      for (int i = 0; i < 10; i++) {
                        Instant at =
                            Instant.now()
                                .plusMillis(ThreadLocalRandom.current().nextLong(100, 1500));
                        dueAt.put(worker.newJob("notify-concurrent").schedule(at), at);
                      }
                    }));
      }
      for (Thread thread : threads) {
        thread.join();
      }

      await().atMost(Duration.ofSeconds(10)).until(() -> started.size() == 40);
      dueAt.forEach(
          (jobId, at) ->
              assertThat(Duration.between(at, started.get(jobId)))
                  .as("job %s", jobId)
                  .isLessThan(IN_TIME));
    }
  }

  @Test
  void withoutListeningNoNotificationsAreSentOrReceived() throws Exception {
    var logs = LogCapture.of(org.bikesched.internal.DefaultBikesched.class);
    try (logs;
        Bikesched worker = worker("node-notify-off").listenForDueJobs(false).build();
        Bikesched client = client("node-notify-off-client").listenForDueJobs(false).build();
        Connection listener = listen()) {
      worker.registerJobHandler("notify-off", recordStart());
      client.registerJobHandler("notify-off", ignored -> {});
      settle();

      UUID jobId = client.newJob("notify-off").schedule();
      Thread.sleep(1000);

      assertThat(started).doesNotContainKey(jobId);
      assertThat(received(listener)).isEmpty();
      assertThat(logs.messages())
          .anyMatch(m -> m.contains("registered, polling for jobs every PT1M"))
          .noneMatch(m -> m.contains("listening"));
    }
  }

  private static void run(Map<String, List<Instant>> runs, String kind) {
    runs.computeIfAbsent(kind, ignored -> new CopyOnWriteArrayList<>()).add(Instant.now());
  }

  /** Runs the action once per job, in a step (handlers replay). */
  private static void step(Job job, String name, Runnable action) throws Exception {
    job.step(
        name,
        Void.class,
        ignored -> {
          action.run();
          return null;
        });
  }

  private static String state(UUID jobId) throws SQLException {
    try (var connection = dataSource.getConnection();
        var select = connection.prepareStatement("select state from bikesched_jobs where id = ?")) {
      select.setObject(1, jobId);
      try (var rs = select.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  /** Lets the worker finish the polls of its start (connecting, new handlers). */
  private static void settle() {
    sleep(500);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static Connection listen() throws SQLException {
    Connection connection = dataSource.getConnection();
    try (var statement = connection.createStatement()) {
      statement.execute("listen bikesched_jobs");
    }
    return connection;
  }

  /** The payloads received so far (waiting up to 100 ms). */
  static List<String> received(Connection listener) throws SQLException {
    List<String> payloads = new ArrayList<>();
    PGNotification[] notifications = listener.unwrap(PGConnection.class).getNotifications(100);
    if (notifications != null) {
      for (PGNotification notification : notifications) {
        payloads.add(notification.getParameter());
      }
    }
    return payloads;
  }

  /** A check before each connection of the test data source; may throw to fail it. */
  @FunctionalInterface
  interface ConnectionCheck {
    void check() throws SQLException;
  }

  /** The test data source, running the check before handing out a connection. */
  static DataSource dataSource(ConnectionCheck check) {
    return (DataSource)
        Proxy.newProxyInstance(
            JobNotificationTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (ignored, method, args) -> {
              if (method.getName().equals("getConnection")) {
                check.check();
              }
              try {
                return method.invoke(dataSource, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }
}
