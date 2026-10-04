package bikesched.core.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bikesched.core.event.ReceivedEvent;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The transactional guarantees of waiting for and publishing events ({@link EventStore}), where the
 * interleavings can be forced deterministically.
 */
class EventStoreTest extends AbstractStoreTest {

  private static UUID node;

  @BeforeEach
  void registerTestNode() {
    node = registerNode("node-event-store");
  }

  @Test
  void eventPublishedBetweenWaitingAndSuspendingResumesTheJobRightAway() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("race-suspend");

    var wait = database.events().awaitEvent(job.id(), "wait", "paid", "race-1", null);
    assertThat(wait.state()).isEqualTo("WAITING");

    // The event arrives after the step started waiting, but before the job is suspended (still
    // RUNNING): the step receives it, the job is left alone ...
    List<ReceivedEvent> received =
        database.events().publishEvent("paid", "race-1", "{\"amount\": 1}");
    assertThat(received).containsExactly(new ReceivedEvent(job.id(), "wait"));
    assertThat(count("id = '" + job.id() + "' and state = 'RUNNING'")).isEqualTo(1);

    // ... and suspending sees the received step and schedules the job instead of letting it wait.
    var suspended =
        database.events().suspendJob(job.id(), node, "wait", "paid", "race-1").orElseThrow();
    assertThat(suspended.waiting()).isFalse();
    assertThat(
            count(
                "id = '"
                    + job.id()
                    + "' and state = 'SCHEDULED' and scheduled_at <= now() and attempts = 0"))
        .as("scheduled right away, the attempt is not used up")
        .isEqualTo(1);
    assertThat(database.events().awaitEvent(job.id(), "wait", "paid", "race-1", null))
        .isEqualTo(new EventStore.EventWait("SUCCEEDED", "{\"amount\": 1}", null));
  }

  @Test
  void suspendedJobWaitsUntilTheEventIsPublished() {
    JobStore.ClaimedJob job = claimNewJob("suspend");

    assertThat(database.events().awaitEvent(job.id(), "wait", "paid", "suspend-1", null).state())
        .isEqualTo("WAITING");
    var suspended =
        database.events().suspendJob(job.id(), node, "wait", "paid", "suspend-1").orElseThrow();

    assertThat(suspended).isEqualTo(new EventStore.SuspendedJob(true, null));
    assertThat(stateAndSchedule(job.id())).isEqualTo("WAITING infinity");
    assertThat(database.jobs().claimJobs(node, 100))
        .extracting(JobStore.ClaimedJob::id)
        .doesNotContain(job.id());

    assertThat(database.events().publishEvent("paid", "suspend-1", null))
        .containsExactly(new ReceivedEvent(job.id(), "wait"));
    assertThat(stateAndSchedule(job.id())).startsWith("SCHEDULED ");
  }

  @Test
  void eventPublishedBeforeWaitingIsReceivedRightAway() {
    assertThat(database.events().publishEvent("paid", "early-1", "\"early\"")).isEmpty();
    JobStore.ClaimedJob job = claimNewJob("early");

    assertThat(database.events().awaitEvent(job.id(), "wait", "paid", "early-1", null))
        .isEqualTo(new EventStore.EventWait("SUCCEEDED", "\"early\"", null));
  }

  @Test
  void latestPublishedEventIsReceived() throws Exception {
    database.events().publishEvent("status", "latest-1", "\"first\"");
    Thread.sleep(5);
    database.events().publishEvent("status", "latest-1", "\"second\"");
    JobStore.ClaimedJob job = claimNewJob("latest");

    assertThat(database.events().awaitEvent(job.id(), "wait", "status", "latest-1", null).payload())
        .isEqualTo("\"second\"");
  }

  @Test
  void waitBlocksWhileAPublishingTransactionHoldsTheEventLock() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("lock");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      assertThat(
              database.events().publishEventInTransaction(connection, "paid", "lock-1", "\"tx\""))
          .isEmpty();

      // Without the lock, the wait would not see the uncommitted event and start waiting, and the
      // publisher would not see the uncommitted waiting step: the job would wait forever.
      var wait =
          CompletableFuture.supplyAsync(
              () -> database.events().awaitEvent(job.id(), "wait", "paid", "lock-1", null));
      assertThatThrownBy(() -> wait.get(300, TimeUnit.MILLISECONDS))
          .as("blocked until the publisher's transaction ends")
          .isInstanceOf(TimeoutException.class);

      connection.commit();
      assertThat(wait.get(5, TimeUnit.SECONDS).state()).isEqualTo("SUCCEEDED");
    }
  }

  @Test
  void eventPublishedInRolledBackTransactionIsNeitherStoredNorReceived() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("rollback");
    database.events().awaitEvent(job.id(), "wait", "paid", "rollback-1", null);
    database.events().suspendJob(job.id(), node, "wait", "paid", "rollback-1");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      assertThat(
              database.events().publishEventInTransaction(connection, "paid", "rollback-1", null))
          .hasSize(1);
      connection.rollback();
    }

    assertThat(countRows("bikesched_events", "event_key = 'rollback-1'")).isZero();
    assertThat(stateAndSchedule(job.id())).isEqualTo("WAITING infinity");
  }

  @Test
  void publishingOnAnAutoCommitConnectionIsRejected() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThatThrownBy(
              () -> database.events().publishEventInTransaction(connection, "paid", "auto-1", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be in auto-commit mode");
    }
    assertThat(countRows("bikesched_events", "event_key = 'auto-1'")).isZero();
  }

  @Test
  void waitWithTimeoutTimesOutOnceTheDeadlinePassed() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("deadline");

    var wait =
        database
            .events()
            .awaitEvent(job.id(), "wait", "paid", "deadline-1", Duration.ofMillis(200));
    assertThat(wait.state()).isEqualTo("WAITING");
    assertThat(wait.waitUntil()).isNotNull();
    database.events().suspendJob(job.id(), node, "wait", "paid", "deadline-1");
    assertThat(stateAndSchedule(job.id())).startsWith("WAITING 20");

    Thread.sleep(300);
    assertThat(database.jobs().claimJobs(node, 100))
        .extracting(JobStore.ClaimedJob::id)
        .contains(job.id());
    assertThat(
            database
                .events()
                .awaitEvent(job.id(), "wait", "paid", "deadline-1", Duration.ofMillis(200)))
        .isEqualTo(new EventStore.EventWait("TIMED_OUT", null, null));
    assertThat(database.events().publishEvent("paid", "deadline-1", null))
        .as("a timed out step no longer receives the event")
        .isEmpty();
  }

  @Test
  void stepNameOfAnotherWaitIsRejected() {
    JobStore.ClaimedJob job = claimNewJob("names");
    database.events().awaitEvent(job.id(), "wait", "paid", "names-1", null);

    assertThatThrownBy(
            () -> database.events().awaitEvent(job.id(), "wait", "shipped", "names-1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("waits for event paid/names-1, not for event shipped/names-1");
  }

  @Test
  void deleteEventsRemovesEventsOlderThanTheRetention() throws Exception {
    database.events().publishEvent("old", "retention-1", null);
    Thread.sleep(300);
    database.events().publishEvent("old", "retention-2", null);

    database.events().deleteEvents(Duration.ofMillis(200));

    assertThat(countRows("bikesched_events", "event_key = 'retention-1'")).isZero();
    assertThat(countRows("bikesched_events", "event_key = 'retention-2'")).isOne();
  }

  private static JobStore.ClaimedJob claimNewJob(String type) {
    UUID jobId = UUID.randomUUID();
    database
        .jobs()
        .insertJob(
            jobId,
            new NewJob(
                "event-db-" + type, null, java.time.Instant.now().minusSeconds(1), 1000, 3, null));
    return database.jobs().claimJobs(node, 100).stream()
        .filter(job -> job.id().equals(jobId))
        .findFirst()
        .orElseThrow();
  }

  private static String stateAndSchedule(UUID jobId) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "select state || ' ' || scheduled_at::text from bikesched_jobs where id = ?")) {
      statement.setObject(1, jobId);
      try (var rs = statement.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
