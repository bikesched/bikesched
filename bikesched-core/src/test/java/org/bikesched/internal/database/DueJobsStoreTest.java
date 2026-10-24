// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * The jobs the stores report due ({@link DueJobs}) after their writes committed, the notifications
 * ({@link JobStore#notifyDue}, also in a caller's transaction), listening and the next-due query.
 */
class DueJobsStoreTest extends AbstractStoreTest {

  private static final Duration KEPT = Duration.ofDays(1);

  private final Recorder due = new Recorder();

  @BeforeEach
  void report() {
    database.reportDueJobs(due);
  }

  @AfterEach
  void stopReporting() {
    database.reportDueJobs(DueJobs.NONE);
  }

  @Test
  void aJobDueNowIsReportedDueNowAndADelayedOneOnlyWithItsDueTime() {
    database.jobs().insertJob(UUID.randomUUID(), job("due-now", Instant.now()));
    Instant later = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    database.jobs().insertJob(UUID.randomUUID(), job("due-later", later));

    assertThat(due.now).containsExactly(Set.of("due-now"));
    assertThat(due.at).containsExactly(Map.entry("due-later", later));
  }

  @Test
  void aDuplicateOfAnIdempotentJobIsNotReported() {
    NewJob keyed = new NewJob("keyed", null, Instant.now(), 0, 3, null, null, null, "k-1");
    UUID first = database.jobs().insertJob(UUID.randomUUID(), keyed);
    due.clear();

    assertThat(database.jobs().insertJob(UUID.randomUUID(), keyed)).isEqualTo(first);

    assertThat(due.now).isEmpty();
    assertThat(due.at).isEmpty();
  }

  @Test
  void claimingAndCompletingReportNothing() {
    database.jobs().insertJob(UUID.randomUUID(), job("claimed", Instant.now()));
    due.clear();
    UUID node = registerNode("node-due-claim");

    var claimed = claimJobs(node, 1).getFirst();
    database.jobs().completeJob(claimed.id(), claimed.lock());

    assertThat(due.now).isEmpty();
    assertThat(due.at).isEmpty();
  }

  @Test
  void aRetriedAttemptIsReportedOnlyWithItsDueTime() {
    database.jobs().insertJob(UUID.randomUUID(), job("retried", Instant.now()));
    UUID node = registerNode("node-due-retry");
    var claimed = claimJobs(node, 1).getFirst();
    due.clear();

    var failed =
        database
            .jobs()
            .failJob(claimed.id(), claimed.lock(), "boom", null, Duration.ofMinutes(1))
            .orElseThrow();

    assertThat(due.now).isEmpty();
    assertThat(due.at).containsExactly(Map.entry("retried", failed.scheduledAt()));
  }

  @Test
  void aPostponementIsReportedOnlyWithItsDueTime() {
    database.jobs().insertJob(UUID.randomUUID(), job("postponed", Instant.now()));
    UUID node = registerNode("node-due-postpone");
    var claimed = claimJobs(node, 1).getFirst();
    due.clear();

    var postponed =
        database
            .steps()
            .postponeJob(claimed.id(), claimed.lock(), null, Duration.ofMinutes(1))
            .orElseThrow();

    assertThat(due.now).isEmpty();
    assertThat(due.at).containsExactly(Map.entry("postponed", postponed.scheduledAt()));
  }

  @Test
  void aSuspensionIsReportedWithItsWakeUpTime() {
    database.jobs().insertJob(UUID.randomUUID(), job("sleeping", Instant.now()));
    UUID node = registerNode("node-due-sleep");
    var claimed = claimJobs(node, 1).getFirst();
    var sleep =
        database.steps().sleep(claimed.id(), claimed.lock(), "nap", Duration.ofMinutes(1), null);
    due.clear();

    database.steps().suspendJob(claimed.id(), claimed.lock(), step(claimed.id(), "nap"));

    assertThat(due.now).isEmpty();
    assertThat(due.at).containsExactly(Map.entry("sleeping", sleep.wakeUpAt()));
  }

  @Test
  void aSuspensionWithoutDeadlineReportsNothing() {
    database.jobs().insertJob(UUID.randomUUID(), job("waiting", Instant.now()));
    UUID node = registerNode("node-due-wait");
    var claimed = claimJobs(node, 1).getFirst();
    database.events().awaitEvent(claimed.id(), claimed.lock(), "wait", "paid", "due-1", null);
    due.clear();

    database
        .events()
        .suspendJob(claimed.id(), claimed.lock(), step(claimed.id(), "wait"), "paid", "due-1");

    assertThat(due.now).isEmpty();
    assertThat(due.at).isEmpty();
  }

  @Test
  void anEventResumingAWaitingJobReportsItsType() {
    database.jobs().insertJob(UUID.randomUUID(), job("resumed", Instant.now()));
    UUID node = registerNode("node-due-event");
    var claimed = claimJobs(node, 1).getFirst();
    database.events().awaitEvent(claimed.id(), claimed.lock(), "wait", "paid", "due-2", null);
    database
        .events()
        .suspendJob(claimed.id(), claimed.lock(), step(claimed.id(), "wait"), "paid", "due-2");
    due.clear();

    database.events().publishEvent("paid", "due-2", null, KEPT, null);
    database.events().publishEvent("paid", "nobody-waits", null, KEPT, null);

    assertThat(due.now).containsExactly(Set.of("resumed"));
  }

  @Test
  void retryingAndCancellingReportTheirType() {
    UUID failedJob = UUID.randomUUID();
    database.jobs().insertJob(failedJob, job("failed", Instant.now()));
    UUID node = registerNode("node-due-retry-cancel");
    var claimed = claimJobs(node, 1).getFirst();
    database.jobs().failJob(claimed.id(), claimed.lock(), "boom", 1, null);
    UUID delayed = UUID.randomUUID();
    database.jobs().insertJob(delayed, job("cancelled", Instant.now().plusSeconds(60)));
    due.clear();

    assertThat(database.jobs().retryJob(failedJob)).isTrue();
    assertThat(database.jobs().requestCancel(delayed, null)).isTrue();

    assertThat(due.now).containsExactly(Set.of("failed"), Set.of("cancelled"));
  }

  @Test
  void cancellingARunningJobReportsNothing() {
    UUID jobId = UUID.randomUUID();
    database.jobs().insertJob(jobId, job("running", Instant.now()));
    claimJobs(registerNode("node-due-cancel-running"), 1);
    due.clear();

    assertThat(database.jobs().requestCancel(jobId, null)).isTrue();

    assertThat(due.now).isEmpty();
  }

  @Test
  void releasedJobsAreReportedDueNow() {
    database.jobs().insertJob(UUID.randomUUID(), job("released-a", Instant.now()));
    database.jobs().insertJob(UUID.randomUUID(), job("released-b", Instant.now()));
    UUID node = registerNode("node-due-release");
    claimJobs(node, 2);
    due.clear();

    assertThat(database.jobs().releaseJobs(node)).isEqualTo(2);

    assertThat(due.now).containsExactly(Set.of("released-a", "released-b"));
  }

  @Test
  void anOccurrenceIsReportedLikeAJob() {
    Instant later = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);

    database.jobs().insertOccurrence(occurrence("recurring-now", Instant.now()));
    database.jobs().insertOccurrence(occurrence("recurring-later", later));
    database.jobs().insertOccurrence(occurrence("recurring-now", Instant.now())); // pending

    assertThat(due.now).containsExactly(Set.of("recurring-now"));
    assertThat(due.at).containsExactly(Map.entry("recurring-later", later));
  }

  @Test
  void aRolledBackWriteReportsNothing() throws SQLException {
    // A job in the caller's transaction is not reported (bikesched does not see the commit) ...
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      database.jobs().insertJob(connection, UUID.randomUUID(), job("caller", Instant.now()));
      connection.rollback();
    }
    // ... nor a failing write of its own.
    try {
      database
          .jobs()
          .insertJob(UUID.randomUUID(), new NewJob("broken", "{", Instant.now(), 0, 1, null));
    } catch (RuntimeException expected) {
      // invalid JSON
    }

    assertThat(due.now).isEmpty();
  }

  @Test
  void notificationsReachAListeningConnection() throws SQLException {
    try (Connection listener = listen()) {
      database.jobs().notifyDue(List.of("a", "b c", "a"));

      assertThat(receive(listener)).containsExactlyInAnyOrder("a", "b c");
    }
  }

  @Test
  void aJobDueNowInTheCallersTransactionIsNotifiedOnCommitOnly() throws SQLException {
    due.notifying = true;
    try (Connection listener = listen();
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      database.jobs().insertJob(connection, UUID.randomUUID(), job("rolled-back", Instant.now()));
      connection.rollback();
      database.jobs().insertJob(connection, UUID.randomUUID(), job("committed", Instant.now()));
      assertThat(receive(listener)).as("before the commit").isEmpty();
      connection.commit();

      assertThat(receive(listener)).containsExactly("committed");
    }
    assertThat(due.now).as("not reported after the caller's commit").isEmpty();
  }

  @Test
  void anEventInTheCallersTransactionIsNotifiedOnCommit() throws SQLException {
    database.jobs().insertJob(UUID.randomUUID(), job("resumed-by-caller", Instant.now()));
    UUID node = registerNode("node-due-caller-event");
    var claimed = claimJobs(node, 1).getFirst();
    database.events().awaitEvent(claimed.id(), claimed.lock(), "wait", "paid", "due-3", null);
    database
        .events()
        .suspendJob(claimed.id(), claimed.lock(), step(claimed.id(), "wait"), "paid", "due-3");
    due.notifying = true;
    try (Connection listener = listen();
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      database.events().publishEventInTransaction(connection, "paid", "due-3", null, KEPT, null);
      connection.commit();

      assertThat(receive(listener)).containsExactly("resumed-by-caller");
    }
  }

  @Test
  void withoutNotifyingTheCallersTransactionSendsNothing() throws SQLException {
    try (Connection listener = listen();
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      database.jobs().insertJob(connection, UUID.randomUUID(), job("silent", Instant.now()));
      connection.commit();

      assertThat(receive(listener)).isEmpty();
    }
  }

  @Test
  void theNextDueQueryLooksIntoTheWindowOnly() throws SQLException {
    insertJobs(1, 0, "now() - interval '1 second'"); // due already: not counted
    insertJobs(1, 0, "now() + interval '5 seconds'");
    insertJobs(1, 0, "now() + interval '1 hour'");
    execute(
        "insert into bikesched_jobs (id, name, scheduled_at) values (gen_random_uuid(), 'other', now() + interval '1 second')");

    Duration inWindow =
        database.jobs().nextDueIn(List.of("test"), Duration.ofSeconds(10)).orElseThrow();
    assertThat(inWindow).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(5));
    assertThat(database.jobs().nextDueIn(List.of("test"), Duration.ofSeconds(2))).isEmpty();
    assertThat(database.jobs().nextDueIn(List.of("unknown"), Duration.ofHours(2))).isEmpty();
  }

  @Test
  void listeningReceivesNotificationsUntilClosed() throws Exception {
    try (DueJobsListening listening = database.jobs().listen()) {
      database.jobs().notifyDue(List.of("x"));
      assertThat(listening.receive(Duration.ofSeconds(5))).containsExactly("x");
      assertThat(listening.receive(Duration.ofMillis(100))).isEmpty();
    }
  }

  @Test
  void closingEndsABlockedReceive() throws Exception {
    DueJobsListening listening = database.jobs().listen();
    var receiving =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    listening.receive(Duration.ofSeconds(30));
                  } catch (RuntimeException expected) {
                    // the connection was closed
                  }
                });
    Thread.sleep(200);
    long start = System.nanoTime();
    listening.close();

    assertThat(receiving.join(Duration.ofSeconds(5))).isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }

  private static NewJob job(String type, Instant at) {
    return new NewJob(type, null, at, 0, 3, null);
  }

  private static NewJob occurrence(String type, Instant at) {
    return new NewJob(type, null, at, 0, 3, null, "every PT1M", at);
  }

  private static Connection listen() throws SQLException {
    Connection connection = dataSource.getConnection();
    try (var statement = connection.createStatement()) {
      statement.execute("listen " + JobStore.CHANNEL);
    }
    return connection;
  }

  /** The payloads arriving within half a second. */
  private static List<String> receive(Connection listener) throws SQLException {
    List<String> payloads = new ArrayList<>();
    PGNotification[] received = listener.unwrap(PGConnection.class).getNotifications(500);
    if (received != null) {
      for (PGNotification notification : received) {
        payloads.add(notification.getParameter());
      }
    }
    return payloads;
  }

  /** Records what the stores report. */
  private static final class Recorder implements DueJobs {

    final List<Set<String>> now = new CopyOnWriteArrayList<>();
    final Map<String, Instant> at = new ConcurrentHashMap<>();
    volatile boolean notifying;

    @Override
    public void dueNow(Set<String> types) {
      now.add(types);
    }

    @Override
    public void dueAt(String type, Instant dueAt) {
      at.put(type, dueAt);
    }

    @Override
    public boolean isNotifying() {
      return notifying;
    }

    void clear() {
      now.clear();
      at.clear();
    }
  }
}
