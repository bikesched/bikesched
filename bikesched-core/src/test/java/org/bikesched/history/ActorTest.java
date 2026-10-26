// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bikesched.HistoryTesting.actions;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.job.Recurrence;
import org.junit.jupiter.api.Test;

/**
 * A user named with {@link Actor} on the job and event builders and on the actions of {@code
 * bikesched.admin()} goes on the entry of the action itself; its consequences carry the code path's
 * actor and the subject that caused them.
 */
class ActorTest extends AbstractBikeschedTest {

  private static final Actor SIMON = Actor.user("simon");

  @Test
  void validatesTheName() {
    assertThat(SIMON.type()).isEqualTo(ActorType.USER);
    assertThat(SIMON.name()).isEqualTo("simon");
    assertThatThrownBy(() -> Actor.user(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Actor.user("x".repeat(201)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Actor(ActorType.NODE, "node"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aUserSchedulingJobsIsNamedOnTheirScheduledEntries() throws Exception {
    String name = unique("actor-job");
    try (Bikesched bikesched = notPolling("node-actor-job");
        Connection connection = dataSource.getConnection()) {
      bikesched.registerJobHandler(name, () -> {});
      UUID plain = bikesched.newJob(name).actor(SIMON).schedule(Duration.ofHours(1));
      UUID keyed =
          bikesched.newJob(name).actor(SIMON).idempotencyKey("k").schedule(Duration.ofHours(1));
      connection.setAutoCommit(false);
      UUID transactional =
          bikesched.newJob(name).connection(connection).actor(SIMON).schedule(Duration.ofHours(1));
      connection.commit();
      UUID anonymous = bikesched.newJob(name).schedule(Duration.ofHours(1));

      for (UUID jobId : List.of(plain, keyed, transactional)) {
        HistoryEntry scheduled = bikesched.findJobHistory(jobId).getFirst();
        assertThat(scheduled.action()).isEqualTo(JobAction.SCHEDULED);
        assertThat(scheduled.actorType()).isEqualTo(ActorType.USER);
        assertThat(scheduled.actorName()).isEqualTo("simon");
      }
      HistoryEntry application = bikesched.findJobHistory(anonymous).getFirst();
      assertThat(application.actorType()).isEqualTo(ActorType.APPLICATION);
      assertThat(application.actorName()).isNull();
    } finally {
      execute("delete from bikesched_jobs where name = '" + name + "'");
    }
  }

  @Test
  void aUserRegisteringARecurringJobIsNamedOnTheOccurrenceItCreatesOrMoves() throws Exception {
    String name = unique("actor-recurring");
    try (Bikesched bikesched = notPolling("node-actor-recurring")) {
      UUID occurrence =
          bikesched
              .newJob(name)
              .handler(ignored -> {})
              .actor(SIMON)
              .schedule(Recurrence.every(Duration.ofHours(1)), Duration.ofHours(1));
      bikesched
          .newJob(name)
          .handler(ignored -> {})
          .actor(Actor.user("ada"))
          .schedule(Recurrence.every(Duration.ofHours(2)), Duration.ofHours(1));
      assertThat(bikesched.admin().removeRecurringJob(name).actor(SIMON).remove()).isTrue();

      assertThat(byUser(bikesched.findJobHistory(occurrence)))
          .containsExactly("SCHEDULED by simon", "MOVED by ada", "DELETED by simon");
    } finally {
      execute("delete from bikesched_jobs where name = '" + name + "'");
    }
  }

  @Test
  void aUserCancellingAndRetryingIsNamedOnTheRequestOnly() throws Exception {
    String name = unique("actor-actions");
    try (Bikesched bikesched = node("node-actor-actions")) {
      bikesched.registerJobHandler(name, job -> job.fail("fails"));
      UUID failed = bikesched.newJob(name).maxAttempts(1).schedule();
      awaitState(failed, "FAILED");
      assertThat(bikesched.admin().retryJob(failed).actor(SIMON).retry()).isTrue();
      UUID later = bikesched.newJob(name).schedule(Duration.ofHours(1));
      assertThat(
              bikesched.admin().cancelJob(later).message("customer called").actor(SIMON).cancel())
          .isTrue();
      awaitState(later, "CANCELLED");
      UUID anonymous = bikesched.newJob(name).schedule(Duration.ofHours(1));
      assertThat(bikesched.admin().cancelJob(anonymous).cancel()).isTrue();

      assertThat(byUser(bikesched.findJobHistory(failed))).containsExactly("RETRIED by simon");
      List<HistoryEntry> cancelled = bikesched.findJobHistory(later);
      assertThat(byUser(cancelled)).containsExactly("CANCEL_REQUESTED by simon");
      assertThat(actions(cancelled)).endsWith("CLAIMED", "CANCELLED");
      assertThat(cancelled.getLast().actorType())
          .as("cancelled by the node")
          .isEqualTo(ActorType.NODE);
      assertThat(bikesched.admin().findJob(later).orElseThrow().lastError())
          .isEqualTo("customer called");
      assertThat(byUser(bikesched.findJobHistory(anonymous))).isEmpty();

      assertThat(bikesched.admin().retryJob(UUID.randomUUID()).actor(SIMON).retry()).isFalse();
      assertThat(bikesched.admin().cancelJob(UUID.randomUUID()).cancel()).isFalse();
    } finally {
      execute("delete from bikesched_jobs where name = '" + name + "'");
    }
  }

  @Test
  void aUserPublishingIsNamedOnThePublishedEntryOnly() throws Exception {
    String event = unique("actor-event");
    String receiver = unique("actor-receiver");
    try (Bikesched bikesched = node("node-actor-event")) {
      UUID jobId =
          bikesched
              .newJob(receiver)
              .handler(job -> job.waitForEvent("await", event, "k", String.class).receive())
              .schedule();
      awaitState(jobId, "WAITING");

      UUID eventId = bikesched.newEvent(event, "k").payload("\"paid\"").actor(SIMON).publish();
      awaitState(jobId, "SUCCEEDED");

      List<HistoryEntry> history = bikesched.findEventHistory(eventId);
      assertThat(actions(history))
          .containsExactly(event + " PUBLISHED", "await RECEIVED", "RESUMED");
      assertThat(byUser(history)).containsExactly("PUBLISHED by simon");
      assertThat(history.subList(1, 3))
          .allSatisfy(
              entry -> {
                assertThat(entry.actorType()).isEqualTo(ActorType.APPLICATION);
                assertThat(entry.causedById()).isEqualTo(eventId);
              });
    } finally {
      execute("delete from bikesched_jobs where name = '" + receiver + "'");
    }
  }

  @Test
  void anActorHasNoEffectWithoutTheHistory() {
    try (Bikesched bikesched = testNode("node-actor-off").autoStartPolling(false).build()) {
      UUID eventId = bikesched.newEvent(unique("actor-off"), "k").actor(SIMON).publish();
      assertThat(bikesched.findEventHistory(eventId)).isEmpty();
    }
  }

  /** The entries naming a user, as "ACTION by user". */
  private static List<String> byUser(List<HistoryEntry> history) {
    return history.stream()
        .filter(entry -> entry.actorType() == ActorType.USER)
        .map(entry -> entry.action().name() + " by " + entry.actorName())
        .toList();
  }

  /** A polling node recording the history. */
  private static Bikesched node(String name) {
    return testNode(name).history(Duration.ofDays(1)).build();
  }

  /** A node recording the history without polling. */
  private static Bikesched notPolling(String name) {
    return testNode(name).history(Duration.ofDays(1)).autoStartPolling(false).build();
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
