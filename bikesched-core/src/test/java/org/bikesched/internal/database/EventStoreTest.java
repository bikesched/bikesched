// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
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

  /** The retention of events in these tests, unless a test is about retention. */
  private static final Duration KEPT = Duration.ofDays(1);

  private static UUID node;

  @BeforeEach
  void registerTestNode() {
    node = registerNode("node-event-store");
  }

  @Test
  void eventPublishedBetweenWaitingAndSuspendingResumesTheJobRightAway() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("race-suspend");

    var wait =
        database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "race-1", null);
    assertThat(wait).isInstanceOf(EventStore.EventWaitResult.Waiting.class);

    // The event arrives after the step started waiting, but before the job is suspended (still
    // RUNNING): the step receives it, the job is left alone ...
    assertThat(
            database
                .events()
                .publishEvent("paid", "race-1", "{\"amount\": 1}", KEPT, null)
                .received())
        .isOne();
    assertThat(count("id = '" + job.id() + "' and state = 'RUNNING'")).isEqualTo(1);

    // ... and suspending sees the received step and schedules the job instead of letting it wait.
    var suspended =
        database
            .events()
            .suspendJob(job.id(), lock(job.id()), step(job.id(), "wait"), "paid", "race-1")
            .orElseThrow();
    assertThat(suspended.waiting()).isFalse();
    assertThat(
            count(
                "id = '"
                    + job.id()
                    + "' and state = 'SCHEDULED' and scheduled_at <= now() and attempts = 0"))
        .as("scheduled right away, the attempt is not used up")
        .isEqualTo(1);
    // The next attempt replays the wait and gets the event.
    assertThat(claimJobs(node, 100)).extracting(JobStore.ClaimedJob::id).contains(job.id());
    assertThat(
            database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "race-1", null))
        .isEqualTo(new EventStore.EventWaitResult.Received("{\"amount\": 1}"));
  }

  @Test
  void suspendedJobWaitsUntilTheEventIsPublished() {
    JobStore.ClaimedJob job = claimNewJob("suspend");

    assertThat(
            database
                .events()
                .awaitEvent(job.id(), lock(job.id()), "wait", "paid", "suspend-1", null))
        .isInstanceOf(EventStore.EventWaitResult.Waiting.class);
    var suspended =
        database
            .events()
            .suspendJob(job.id(), lock(job.id()), step(job.id(), "wait"), "paid", "suspend-1")
            .orElseThrow();

    assertThat(suspended).isEqualTo(new StepStore.SuspendedJob(true, null));
    assertThat(stateAndSchedule(job.id())).isEqualTo("WAITING infinity");
    assertThat(claimJobs(node, 100)).extracting(JobStore.ClaimedJob::id).doesNotContain(job.id());

    assertThat(database.events().publishEvent("paid", "suspend-1", null, KEPT, null).received())
        .isOne();
    assertThat(stateAndSchedule(job.id())).startsWith("SCHEDULED ");
  }

  @Test
  void eventPublishedBeforeWaitingIsReceivedRightAway() throws Exception {
    assertThat(
            database.events().publishEvent("paid", "early-1", "\"early\"", KEPT, null).received())
        .isZero();
    JobStore.ClaimedJob job = claimNewJob("early");

    assertThat(
            database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "early-1", null))
        .isEqualTo(new EventStore.EventWaitResult.Received("\"early\""));
    assertThat(
            countSteps(
                "job_id = '"
                    + job.id()
                    + "' and state = 'SUCCEEDED' and result = '\"early\"'::jsonb"))
        .as("payload copied into the step")
        .isOne();
  }

  @Test
  void latestPublishedEventIsReceived() throws Exception {
    database.events().publishEvent("status", "latest-1", "\"first\"", KEPT, null);
    Thread.sleep(5);
    database.events().publishEvent("status", "latest-1", "\"second\"", KEPT, null);
    JobStore.ClaimedJob job = claimNewJob("latest");

    assertThat(
            database
                .events()
                .awaitEvent(job.id(), lock(job.id()), "wait", "status", "latest-1", null))
        .isEqualTo(new EventStore.EventWaitResult.Received("\"second\""));
  }

  @Test
  void waitBlocksWhileAPublishingTransactionHoldsTheEventLock() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("lock");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      assertThat(
              database
                  .events()
                  .publishEventInTransaction(connection, "paid", "lock-1", "\"tx\"", KEPT, null)
                  .received())
          .isZero();

      // Without the lock, the wait would not see the uncommitted event and start waiting, and the
      // publisher would not see the uncommitted waiting step: the job would wait forever.
      var wait =
          CompletableFuture.supplyAsync(
              () ->
                  database
                      .events()
                      .awaitEvent(job.id(), lock(job.id()), "wait", "paid", "lock-1", null));
      assertThatThrownBy(() -> wait.get(300, TimeUnit.MILLISECONDS))
          .as("blocked until the publisher's transaction ends")
          .isInstanceOf(TimeoutException.class);

      connection.commit();
      assertThat(wait.get(5, TimeUnit.SECONDS))
          .isInstanceOf(EventStore.EventWaitResult.Received.class);
    }
  }

  @Test
  void eventPublishedInRolledBackTransactionIsNeitherStoredNorReceived() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("rollback");
    database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "rollback-1", null);
    database
        .events()
        .suspendJob(job.id(), lock(job.id()), step(job.id(), "wait"), "paid", "rollback-1");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      assertThat(
              database
                  .events()
                  .publishEventInTransaction(connection, "paid", "rollback-1", null, KEPT, null)
                  .received())
          .isOne();
      connection.rollback();
    }

    assertThat(countRows("bikesched_events", "key = 'rollback-1'")).isZero();
    assertThat(stateAndSchedule(job.id())).isEqualTo("WAITING infinity");
  }

  @Test
  void publishingOnAnAutoCommitConnectionIsRejected() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThatThrownBy(
              () ->
                  database
                      .events()
                      .publishEventInTransaction(connection, "paid", "auto-1", null, KEPT, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be in auto-commit mode");
    }
    assertThat(countRows("bikesched_events", "key = 'auto-1'")).isZero();
  }

  @Test
  void waitWithTimeoutTimesOutOnceTheDeadlinePassed() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("deadline");

    var wait =
        database
            .events()
            .awaitEvent(
                job.id(), lock(job.id()), "wait", "paid", "deadline-1", Duration.ofMillis(200));
    assertThat(wait).isInstanceOf(EventStore.EventWaitResult.Waiting.class);
    assertThat(((EventStore.EventWaitResult.Waiting) wait).waitUntil()).isNotNull();
    database
        .events()
        .suspendJob(job.id(), lock(job.id()), step(job.id(), "wait"), "paid", "deadline-1");
    assertThat(stateAndSchedule(job.id())).startsWith("WAITING 20");

    Thread.sleep(300);
    assertThat(claimJobs(node, 100)).extracting(JobStore.ClaimedJob::id).contains(job.id());
    assertThat(
            database
                .events()
                .awaitEvent(
                    job.id(), lock(job.id()), "wait", "paid", "deadline-1", Duration.ofMillis(200)))
        .isEqualTo(new EventStore.EventWaitResult.TimedOut(step(job.id(), "wait")));
    assertThat(database.events().publishEvent("paid", "deadline-1", null, KEPT, null).received())
        .as("a timed out step no longer receives the event")
        .isZero();
  }

  @Test
  void stepNameOfAnotherWaitIsRejected() {
    JobStore.ClaimedJob job = claimNewJob("names");
    database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "names-1", null);

    assertThatThrownBy(
            () ->
                database
                    .events()
                    .awaitEvent(job.id(), lock(job.id()), "wait", "shipped", "names-1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("waits for event paid/names-1, not for event shipped/names-1");
  }

  @Test
  void expiredEventsAreDeletedAndNoLongerReceived() throws Exception {
    database.events().publishEvent("old", "retention-1", "\"short\"", Duration.ofMillis(200), null);
    database.events().publishEvent("old", "retention-2", null, KEPT, null);
    Thread.sleep(300);

    JobStore.ClaimedJob job = claimNewJob("expired");
    assertThat(
            database
                .events()
                .awaitEvent(job.id(), lock(job.id()), "wait", "old", "retention-1", null))
        .as("an expired event is not received, even before it is deleted")
        .isInstanceOf(EventStore.EventWaitResult.Waiting.class);

    assertThat(database.events().deleteExpiredEvents(null, null, 1000).count()).isOne();
    assertThat(countRows("bikesched_events", "key = 'retention-1'")).isZero();
    assertThat(countRows("bikesched_events", "key = 'retention-2'")).isOne();
  }

  @Test
  void eventWithoutRetentionOnlyReachesTheStepsWaitingNow() {
    JobStore.ClaimedJob waiting = claimNewJob("zero-waiting");
    database.events().awaitEvent(waiting.id(), lock(waiting.id()), "wait", "ping", "zero-1", null);
    database
        .events()
        .suspendJob(waiting.id(), lock(waiting.id()), step(waiting.id(), "wait"), "ping", "zero-1");

    assertThat(
            database
                .events()
                .publishEvent("ping", "zero-1", "\"now\"", Duration.ZERO, null)
                .received())
        .isOne();
    // Claimed now: claimNewJob below claims every due job.
    claim(waiting.id());

    JobStore.ClaimedJob later = claimNewJob("zero-later");
    assertThat(
            database
                .events()
                .awaitEvent(later.id(), lock(later.id()), "wait", "ping", "zero-1", null))
        .isInstanceOf(EventStore.EventWaitResult.Waiting.class);
    assertThat(database.events().deleteExpiredEvents(null, null, 1000).count()).isOne();

    assertThat(
            database
                .events()
                .awaitEvent(waiting.id(), lock(waiting.id()), "wait", "ping", "zero-1", null))
        .as("the step's copy of the payload, the event is gone")
        .isEqualTo(new EventStore.EventWaitResult.Received("\"now\""));
  }

  @Test
  void publishingCopiesThePayloadIntoTheWaitingSteps() throws Exception {
    JobStore.ClaimedJob job = claimNewJob("copied");
    database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "copied-1", null);
    database
        .events()
        .suspendJob(job.id(), lock(job.id()), step(job.id(), "wait"), "paid", "copied-1");

    database.events().publishEvent("paid", "copied-1", "{\"amount\": 7}", Duration.ZERO, null);
    assertThat(
            countSteps(
                "job_id = '"
                    + job.id()
                    + "' and state = 'SUCCEEDED' and result = '{\"amount\": 7}'::jsonb"
                    + " and finished_at is not null"))
        .isOne();
    assertThat(database.events().deleteExpiredEvents(null, null, 1000).count()).isOne();

    claim(job.id());
    assertThat(
            database
                .events()
                .awaitEvent(job.id(), lock(job.id()), "wait", "paid", "copied-1", null))
        .isEqualTo(new EventStore.EventWaitResult.Received("{\"amount\": 7}"));
  }

  @Test
  void publishingStepReturnsThePublicationOnlyWhenItPublished() {
    JobStore.ClaimedJob publisher = claimNewJob("step-publication");
    var store = database.events();

    EventStore.StepPublication first =
        store.publishEventAsStep(
            publisher.id(), lock(publisher.id()), "publish", "paid", "pub-1", null, KEPT, false);
    EventStore.StepPublication replayed =
        store.publishEventAsStep(
            publisher.id(), lock(publisher.id()), "publish", "paid", "pub-1", null, KEPT, false);

    assertThat(first.publication()).isNotNull();
    assertThat(first.publication().eventId()).isEqualTo(first.eventId());
    assertThat(replayed.eventId()).isEqualTo(first.eventId());
    assertThat(replayed.publication()).as("an earlier run published it").isNull();
  }

  @Test
  void publishingStepPublishesOnlyOnceAndReturnsTheSameEventId() throws Exception {
    JobStore.ClaimedJob receiver = claimNewJob("step-receiver");
    database
        .events()
        .awaitEvent(receiver.id(), lock(receiver.id()), "wait", "paid", "once-1", null);
    database
        .events()
        .suspendJob(
            receiver.id(), lock(receiver.id()), step(receiver.id(), "wait"), "paid", "once-1");
    JobStore.ClaimedJob publisher = claimNewJob("step-publisher");

    UUID published = publishAsStep(publisher, "publish", "paid", "once-1", "\"first\"");
    UUID replayed = publishAsStep(publisher, "publish", "paid", "once-1", "\"replayed\"");

    assertThat(replayed).as("same id on every run").isEqualTo(published);
    assertThat(countRows("bikesched_events", "key = 'once-1'")).as("published once").isOne();
    assertThat(countRows("bikesched_events", "id = '" + published + "'")).isOne();
    assertThat(
            countSteps(
                "job_id = '"
                    + publisher.id()
                    + "' and name = 'publish' and type = 'EVENT_PUBLISH' and state = 'SUCCEEDED'"
                    + " and event_name = 'paid' and event_key = 'once-1' and event_id = '"
                    + published
                    + "' and result is null"))
        .as("the publishing step records the event it published")
        .isOne();
    assertThat(
            countSteps(
                "job_id = '"
                    + receiver.id()
                    + "' and name = 'wait' and event_id = '"
                    + published
                    + "' and result = '\"first\"'::jsonb"))
        .as("the receiving step references the event")
        .isOne();
  }

  @Test
  void publishingStepRemembersItsEventAfterTheEventIsDeleted() {
    JobStore.ClaimedJob publisher = claimNewJob("deleted-publisher");
    UUID published = publishAsStep(publisher, "publish", "ping", "deleted-1", null, Duration.ZERO);
    assertThat(database.events().deleteExpiredEvents(null, null, 1000).count()).isOne();

    assertThat(publishAsStep(publisher, "publish", "ping", "deleted-1", null, Duration.ZERO))
        .as("no foreign key: the step keeps the id of the purged event, nothing is published")
        .isEqualTo(published);
    assertThat(database.events().deleteExpiredEvents(null, null, 1000).count()).isZero();
  }

  @Test
  void publishingStepReplayingAnotherEventIsRejected() {
    JobStore.ClaimedJob publisher = claimNewJob("other-publisher");
    publishAsStep(publisher, "publish", "paid", "other-1", null);

    assertThatThrownBy(() -> publishAsStep(publisher, "publish", "shipped", "other-1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("published event paid/other-1, not event shipped/other-1");
  }

  @Test
  void stepNamesAreUniqueAcrossWaitingAndPublishing() {
    JobStore.ClaimedJob job = claimNewJob("mixed");
    database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "mixed-1", null);
    publishAsStep(job, "publish", "paid", "mixed-2", null);

    assertThatThrownBy(() -> publishAsStep(job, "wait", "paid", "mixed-1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is an event wait, not event paid/mixed-1");
    assertThatThrownBy(
            () ->
                database
                    .events()
                    .awaitEvent(job.id(), lock(job.id()), "publish", "paid", "mixed-2", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is an event publish, not for event paid/mixed-2");
  }

  @Test
  void stepReceivingAPublishedEventRightAwayReferencesIt() throws Exception {
    UUID eventId = database.events().publishEvent("paid", "ref-1", null, KEPT, null).eventId();
    JobStore.ClaimedJob job = claimNewJob("ref");

    database.events().awaitEvent(job.id(), lock(job.id()), "wait", "paid", "ref-1", null);

    assertThat(
            countSteps(
                "job_id = '"
                    + job.id()
                    + "' and type = 'EVENT_WAIT' and state = 'SUCCEEDED' and event_id = '"
                    + eventId
                    + "'"))
        .isOne();
  }

  private static UUID publishAsStep(
      JobStore.ClaimedJob job, String stepName, String eventName, String eventKey, String payload) {
    return publishAsStep(job, stepName, eventName, eventKey, payload, KEPT);
  }

  private static UUID publishAsStep(
      JobStore.ClaimedJob job,
      String stepName,
      String eventName,
      String eventKey,
      String payload,
      Duration retention) {
    return database
        .events()
        .publishEventAsStep(
            job.id(), lock(job.id()), stepName, eventName, eventKey, payload, retention, false)
        .eventId();
  }

  /** Claims the (due) job, as the poller does. */
  private static void claim(UUID jobId) {
    assertThat(claimJobs(node, 100)).extracting(JobStore.ClaimedJob::id).contains(jobId);
  }

  private static JobStore.ClaimedJob claimNewJob(String type) {
    UUID jobId = UUID.randomUUID();
    database
        .jobs()
        .insertJob(
            jobId,
            new NewJob(
                "event-db-" + type, null, java.time.Instant.now().minusSeconds(1), 1000, 3, null));
    return claimJobs(node, 100).stream()
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
