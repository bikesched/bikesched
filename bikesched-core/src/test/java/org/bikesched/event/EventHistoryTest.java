// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bikesched.HistoryTesting.actions;
import static org.bikesched.HistoryTesting.data;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.history.ActorType;
import org.bikesched.history.EventAction;
import org.bikesched.history.EventEntry;
import org.bikesched.history.HistoryEntry;
import org.junit.jupiter.api.Test;

/**
 * The history of events: a publish records the event's {@code PUBLISHED} entry and, caused by the
 * event, the steps that received it and the jobs it resumed; {@link Bikesched#findEventHistory}
 * reads them, also after the event and the jobs were deleted.
 */
class EventHistoryTest extends AbstractBikeschedTest {

  @Test
  void publishRecordsTheEventAndWhatItCaused() throws Exception {
    String event = unique("history-event");
    try (Bikesched bikesched = node("node-event-history")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-receiver"))
              .handler(job -> job.waitForEvent("await", event, "k", String.class).receive())
              .schedule();
      awaitState(jobId, "WAITING");

      UUID eventId =
          bikesched
              .newEvent(event, "k")
              .payload("\"paid\"")
              .retention(Duration.ofHours(2))
              .idempotencyKey("once")
              .reference("order-7")
              .publish();
      awaitState(jobId, "SUCCEEDED");

      List<HistoryEntry> history = bikesched.findEventHistory(eventId);
      assertThat(actions(history))
          .containsExactly(event + " PUBLISHED", "await RECEIVED", "RESUMED");
      EventEntry published = (EventEntry) history.getFirst();
      assertThat(published.eventId()).isEqualTo(eventId);
      assertThat(published.action()).isEqualTo(EventAction.PUBLISHED);
      assertThat(published.actorType()).isEqualTo(ActorType.APPLICATION);
      assertThat(published.nodeId()).isEqualTo(bikesched.node().id());
      assertThat(published.causedById()).isNull();
      assertThat(data(published, "key")).isEqualTo("k");
      assertThat(data(published, "retention")).isEqualTo("PT2H");
      assertThat(data(published, "received")).isEqualTo("1");
      assertThat(data(published, "idempotencyKey")).isEqualTo("once");
      assertThat(data(published, "reference")).isEqualTo("order-7");
      assertThat(history.subList(1, 3))
          .allSatisfy(
              entry -> {
                assertThat(entry.subjectId()).isEqualTo(jobId);
                assertThat(entry.causedById()).isEqualTo(eventId);
                assertThat(entry.actorType()).isEqualTo(ActorType.APPLICATION);
              });

      // A duplicate of the idempotency key changes nothing and records nothing.
      bikesched.newEvent(event, "k").idempotencyKey("once").publish();
      assertThat(bikesched.findEventHistory(eventId)).hasSize(3);

      // The entries outlive the event and the job.
      execute("delete from bikesched_events where id = '" + eventId + "'");
      execute("delete from bikesched_jobs where id = '" + jobId + "'");
      assertThat(actions(bikesched.findEventHistory(eventId)))
          .containsExactly(event + " PUBLISHED", "await RECEIVED", "RESUMED");
    }
  }

  @Test
  void aStepReceivingAnEarlierEventRecordsTheEventAsItsCause() throws Exception {
    String event = unique("history-early");
    try (Bikesched bikesched = node("node-event-history-early")) {
      UUID eventId = bikesched.newEvent(event, "k").payload("\"x\"").publish();
      UUID jobId =
          bikesched
              .newJob(unique("history-late-receiver"))
              .handler(job -> job.waitForEvent("await", event, "k", String.class).receive())
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      List<HistoryEntry> history = bikesched.findEventHistory(eventId);
      assertThat(actions(history)).containsExactly(event + " PUBLISHED", "await RECEIVED");
      assertThat(data(history.getFirst(), "received")).isEqualTo("0");
      assertThat(history.getLast().actorType()).isEqualTo(ActorType.JOB);
    }
  }

  @Test
  void anEventPublishedByAJobIsCausedByIt() throws Exception {
    String event = unique("history-job-event");
    try (Bikesched bikesched = node("node-event-history-job")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-publisher"))
              .handler(job -> job.newEvent(event, "k").stepName("publish").publish())
              .schedule();
      awaitState(jobId, "SUCCEEDED");
      UUID eventId = eventId(event);

      HistoryEntry published = bikesched.findEventHistory(eventId).getFirst();
      assertThat(published.action()).isEqualTo(EventAction.PUBLISHED);
      assertThat(published.actorType()).isEqualTo(ActorType.JOB);
      assertThat(published.causedById()).isEqualTo(jobId);
    }
  }

  @Test
  void anEventPublishedInTheCallersTransactionIsRecordedWithIt() throws Exception {
    String event = unique("history-tx-event");
    try (Bikesched bikesched = node("node-event-history-tx");
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID eventId = bikesched.newEvent(event, "k").connection(connection).publish();
      connection.rollback();

      assertThat(bikesched.findEventHistory(eventId)).as("rolled back with the event").isEmpty();
    }
  }

  @Test
  void nothingIsRecordedWithoutTheHistory() {
    try (Bikesched bikesched = testNode("node-event-history-off").build()) {
      UUID eventId = bikesched.newEvent(unique("history-off"), "k").publish();
      assertThat(bikesched.findEventHistory(eventId)).isEmpty();
    }
  }

  private static UUID eventId(String name) throws Exception {
    try (var connection = dataSource.getConnection();
        var query = connection.prepareStatement("select id from bikesched_events where name = ?")) {
      query.setString(1, name);
      try (var rs = query.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  /** A polling node recording the history. */
  private static Bikesched node(String name) {
    return testNode(name).history(Duration.ofDays(1)).build();
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
