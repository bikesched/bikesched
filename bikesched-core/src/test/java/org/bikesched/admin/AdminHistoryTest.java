// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bikesched.HistoryTesting.actions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.history.Actor;
import org.bikesched.history.ActorType;
import org.bikesched.history.EventAction;
import org.bikesched.history.EventEntry;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobAction;
import org.bikesched.history.StepAction;
import org.bikesched.history.StepEntry;
import org.bikesched.history.SubjectType;
import org.junit.jupiter.api.Test;

/**
 * {@link Admin#findHistory(HistoryQuery)}: every subject newest first, its filters, keyset paging,
 * the bounded scan and the names of the writing nodes.
 */
class AdminHistoryTest extends AbstractBikeschedTest {

  @Test
  void findsTheEntriesOfEverySubjectNewestFirstAndPagesThroughThem() throws Exception {
    String name = unique("feed-job");
    String event = unique("feed-event");
    try (Bikesched bikesched = notPolling("node-feed")) {
      bikesched.registerJobHandler(name, () -> {});
      Instant since = databaseNow();
      UUID first = bikesched.newJob(name).schedule(Duration.ofHours(1));
      UUID second = bikesched.newJob(name).schedule(Duration.ofHours(1));
      UUID third = bikesched.newJob(name).actor(Actor.user("simon")).schedule(Duration.ofHours(1));
      UUID eventId = bikesched.newEvent(event, "k").actor(Actor.user("simon")).publish();
      Admin admin = bikesched.admin();
      HistoryQuery query = HistoryQuery.newest(10).withSince(since);

      HistoryPage all = admin.findHistory(query);
      assertThat(all.entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(eventId, third, second, first);
      assertThat(all.next()).isNull();

      HistoryPage page = admin.findHistory(HistoryQuery.newest(3).withSince(since));
      assertThat(page.entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(eventId, third, second);
      assertThat(page.next()).isEqualTo(HistoryPosition.of(page.entries().getLast()));
      HistoryPage rest =
          admin.findHistory(HistoryQuery.newest(3).withSince(since).after(page.next()));
      assertThat(rest.entries()).extracting(HistoryEntry::subjectId).containsExactly(first);
      assertThat(rest.next()).isNull();

      assertThat(admin.findHistory(query.withActorType(ActorType.USER)).entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(eventId, third);
      assertThat(admin.findHistory(query.withActorType(ActorType.APPLICATION)).entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(second, first);
      assertThat(admin.findHistory(query.withEntryType(EventEntry.class)).entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(eventId);
      assertThat(admin.findHistory(query.withActions(JobAction.SCHEDULED)).entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(third, second, first);
      assertThat(
              admin
                  .findHistory(query.withActions(JobAction.SCHEDULED, EventAction.PUBLISHED))
                  .entries())
          .hasSize(4);
      assertThat(admin.findHistory(query.withActions(StepAction.SUCCEEDED)).entries())
          .as("a step action matches step entries only")
          .isEmpty();
      assertThat(admin.findHistory(query.withEntryType(StepEntry.class)).entries()).isEmpty();
      Instant secondAt = all.entries().get(2).createdAt();
      assertThat(admin.findHistory(query.withUntil(secondAt)).entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(first);
      assertThat(admin.findHistory(HistoryQuery.newest(10).withSince(secondAt)).entries())
          .extracting(HistoryEntry::subjectId)
          .containsExactly(eventId, third, second);
    } finally {
      execute("delete from bikesched_jobs where name = '" + name + "'");
    }
  }

  @Test
  void findsTheEntriesASubjectCaused() throws Exception {
    String event = unique("feed-caused-event");
    String receiver = unique("feed-caused-job");
    try (Bikesched bikesched = node("node-feed-caused")) {
      UUID jobId =
          bikesched
              .newJob(receiver)
              .handler(job -> job.waitForEvent("await", event, "k", String.class).receive())
              .schedule();
      awaitState(jobId, "WAITING");
      UUID eventId = bikesched.newEvent(event, "k").payload("\"paid\"").publish();
      awaitState(jobId, "SUCCEEDED");

      HistoryPage caused =
          bikesched.admin().findHistory(HistoryQuery.newest(10).withCausedBy(eventId));
      assertThat(actions(caused.entries())).containsExactly("RESUMED", "await RECEIVED");
      assertThat(caused.entries())
          .allSatisfy(entry -> assertThat(entry.subjectId()).isEqualTo(jobId));
    } finally {
      execute("delete from bikesched_jobs where name = '" + receiver + "'");
    }
  }

  @Test
  void aPageStopsAfterTheBoundedScanAndTheNextOneGoesOn() throws Exception {
    // Entries long ago, so the time bounds keep other tests' entries out.
    Instant since = Instant.parse("2001-01-01T00:00:00Z");
    Instant until = Instant.parse("2001-01-02T00:00:00Z");
    UUID eventId = UUID.randomUUID();
    try (Bikesched bikesched = notPolling("node-feed-scan")) {
      execute(
          "insert into bikesched_history (subject_id, id, subject_type, action, created_at,"
              + " actor_type, data) values ('"
              + eventId
              + "', gen_random_uuid(), 'EVENT', 'PUBLISHED', '2001-01-01T01:00:00Z',"
              + " 'APPLICATION', '{\"subjectName\": \"scan\"}')");
      execute(
          "insert into bikesched_history (subject_id, id, subject_type, action, state, attempt,"
              + " created_at, actor_type)"
              + " select gen_random_uuid(), gen_random_uuid(), 'JOB', 'SCHEDULED', 'SCHEDULED', 0,"
              + " '2001-01-01T02:00:00Z'::timestamptz + make_interval(secs => i), 'APPLICATION'"
              + " from generate_series(1, "
              + (HistoryQuery.SCAN_LIMIT + 10)
              + ") i");
      HistoryQuery query =
          HistoryQuery.newest(10)
              .withSince(since)
              .withUntil(until)
              .withActions(EventAction.PUBLISHED);

      HistoryPage page = bikesched.admin().findHistory(query);
      assertThat(page.entries()).isEmpty();
      assertThat(page.next()).as("the last scanned entry").isNotNull();
      assertThat(page.next().createdAt())
          .isEqualTo(Instant.parse("2001-01-01T02:00:00Z").plusSeconds(11));

      HistoryPage next = bikesched.admin().findHistory(query.after(page.next()));
      assertThat(next.entries()).extracting(HistoryEntry::subjectId).containsExactly(eventId);
      assertThat(next.next()).isNull();
      assertThat(bikesched.admin().findHistory(query.withActions()).entries())
          .as("without filters after the scan, the page fills up")
          .hasSize(10);
    } finally {
      execute("delete from bikesched_history where created_at < '2001-01-02T00:00:00Z'");
    }
  }

  @Test
  void namesTheWritingNodesAlsoAfterTheyAreGone() throws Exception {
    String name = unique("feed-node-job");
    UUID nodeId;
    Instant since = databaseNow();
    try (Bikesched reader = notPolling("node-feed-reader")) {
      try (Bikesched bikesched = node("node-feed-writer")) {
        nodeId = bikesched.node().id();
        bikesched.registerJobHandler(name, () -> {});
        awaitState(bikesched.newJob(name).schedule(), "SUCCEEDED");
        HistoryPage page = reader.admin().findHistory(HistoryQuery.newest(50).withSince(since));
        assertThat(page.subjects().get(nodeId))
            .isEqualTo(new HistorySubject(nodeId, SubjectType.NODE, "node-feed-writer"));
      }
      assertThat(countRows("bikesched_nodes", "id = '" + nodeId + "'")).isZero();

      HistoryPage page = reader.admin().findHistory(HistoryQuery.newest(50).withSince(since));
      assertThat(page.entries()).extracting(HistoryEntry::nodeId).contains(nodeId);
      assertThat(page.subjects().get(nodeId))
          .isEqualTo(new HistorySubject(nodeId, SubjectType.NODE, "node-feed-writer"));
    } finally {
      execute("delete from bikesched_jobs where name = '" + name + "'");
    }
  }

  @Test
  void tellsWhetherItRecordsTheHistory() {
    try (Bikesched recording = notPolling("node-feed-on");
        Bikesched off = testNode("node-feed-off").autoStartPolling(false).build()) {
      assertThat(recording.admin().isRecordingHistory()).isTrue();
      assertThat(off.admin().isRecordingHistory()).isFalse();
    }
  }

  @Test
  void validatesTheQuery() {
    assertThatThrownBy(() -> HistoryQuery.newest(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HistoryQuery.newest(JobQuery.MAX_LIMIT + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HistoryQuery.newest(1).withEntryType(HistoryEntry.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Instant databaseNow() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("select clock_timestamp()")) {
      rs.next();
      return rs.getObject(1, java.time.OffsetDateTime.class).toInstant();
    }
  }

  private static Bikesched node(String name) {
    return testNode(name).history(Duration.ofDays(1)).build();
  }

  private static Bikesched notPolling(String name) {
    return testNode(name).history(Duration.ofDays(1)).autoStartPolling(false).build();
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
