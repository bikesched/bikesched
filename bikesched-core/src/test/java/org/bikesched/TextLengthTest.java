// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bikesched.event.EventType;
import org.bikesched.job.JobType;
import org.bikesched.job.Recurrence;
import org.junit.jupiter.api.Test;

/**
 * Text lengths: names (node names, job types, step and event names, recurrence descriptions) have
 * at most 200 characters, event keys 500. The longest allowed values work end to end (including the
 * unique and lookup indexes), longer ones are rejected before reaching the database, and the schema
 * enforces the same limits. Error and cancel messages are truncated instead.
 */
class TextLengthTest extends AbstractBikeschedTest {

  @Test
  void longestAllowedNamesAndKeysWorkEndToEnd() throws Exception {
    String type = ofLength("max-type-" + UUID.randomUUID(), 200);
    String stepName = ofLength("step-", 200);
    String eventName = ofLength("max-event-" + UUID.randomUUID(), 200);
    String eventKey = ofLength("key-", 500);
    var received = new CompletableFuture<String>();
    try (Bikesched bikesched = testNode(ofLength("node-max-" + UUID.randomUUID(), 200)).build()) {
      UUID jobId =
          bikesched
              .newJob(type)
              .handler(
                  job ->
                      received.complete(
                          job.waitForEvent(stepName, eventName, eventKey, String.class).receive()))
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent(eventName, eventKey).payload("\"done\"").publish();

      // The default mapper keeps the JSON text.
      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo("\"done\"");
      awaitState(jobId, "SUCCEEDED");
    }
  }

  @Test
  void longestAllowedJobTypeWorksForRecurringJobs() throws Exception {
    String type = ofLength("max-recurring-" + UUID.randomUUID(), 200);
    try (Bikesched bikesched = testNode("node-max-recurring").autoStartPolling(false).build()) {
      UUID first =
          bikesched.newJob(type).handler(() -> {}).schedule(Recurrence.every(Duration.ofHours(1)));
      UUID again =
          bikesched.newJob(type).handler(() -> {}).schedule(Recurrence.every(Duration.ofHours(1)));

      assertThat(again).isEqualTo(first);
      bikesched.removeRecurringJob(type);
    }
  }

  @Test
  void tooLongNamesAreRejected() throws Exception {
    String tooLong = "x".repeat(201);
    assertThatThrownBy(() -> JobType.of(tooLong, String.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("job type must have at most 200 characters, but has 201");
    assertThatThrownBy(() -> EventType.of(tooLong, String.class))
        .hasMessage("event name must have at most 200 characters, but has 201");
    assertThatThrownBy(() -> Bikesched.builder().nodeName(tooLong))
        .hasMessage("node name must have at most 200 characters, but has 201");

    try (Bikesched bikesched = testNode("node-too-long").autoStartPolling(false).build()) {
      assertThatThrownBy(() -> bikesched.newJob(tooLong))
          .hasMessage("job type must have at most 200 characters, but has 201");
      assertThatThrownBy(() -> bikesched.newEvent(tooLong, "key"))
          .hasMessage("event name must have at most 200 characters, but has 201");
      assertThatThrownBy(() -> bikesched.newEvent("event", "k".repeat(501)))
          .hasMessage("event key must have at most 500 characters, but has 501");
      assertThatThrownBy(() -> bikesched.newEvent("event", " "))
          .hasMessage("event key must not be blank");
    }
  }

  @Test
  void tooLongStepNameIsRejected() throws Exception {
    var failure = new CompletableFuture<Throwable>();
    try (Bikesched bikesched = testNode("node-step-too-long").build()) {
      bikesched
          .newJob(unique("step-too-long"))
          .maxAttempts(1)
          .handler(
              job -> {
                try {
                  job.step("s".repeat(201), String.class, step -> null);
                } catch (IllegalArgumentException e) {
                  failure.complete(e);
                }
              })
          .schedule();

      assertThat(failure.get(10, TimeUnit.SECONDS))
          .hasMessage("step name must have at most 200 characters, but has 201");
    }
  }

  @Test
  void recurrenceDescriptionIsLimited() throws Exception {
    assertThatThrownBy(() -> Recurrence.of("d".repeat(201), previous -> previous.plusSeconds(60)))
        .hasMessage("recurrence description must have at most 200 characters, but has 201");

    // A Recurrence implemented directly is checked when it is registered.
    Recurrence custom =
        new Recurrence() {
          @Override
          public java.time.Instant next(java.time.Instant previous) {
            return previous.plusSeconds(60);
          }

          @Override
          public String description() {
            return "d".repeat(201);
          }
        };
    try (Bikesched bikesched = testNode("node-long-recurrence").autoStartPolling(false).build()) {
      assertThatThrownBy(
              () -> bikesched.newJob(unique("long-recurrence")).handler(() -> {}).schedule(custom))
          .hasMessage("recurrence description must have at most 200 characters, but has 201");
    }
  }

  @Test
  void longErrorAndCancelMessagesAreStoredTruncated() throws Exception {
    String huge = "e".repeat(10_000);
    try (Bikesched bikesched = testNode("node-long-messages").build()) {
      UUID failed =
          bikesched
              .newJob(unique("long-error"))
              .maxAttempts(1)
              .handler(
                  () -> {
                    throw new IllegalStateException(huge);
                  })
              .schedule();
      UUID cancelled =
          bikesched.newJob(unique("long-cancel")).handler(job -> job.cancel(huge)).schedule();
      awaitState(failed, "FAILED");
      awaitState(cancelled, "CANCELLED");

      assertThat(
              count(
                  "id = '"
                      + failed
                      + "' and char_length(last_error) = 4000"
                      + " and last_error like 'java.lang.IllegalStateException: eee%… (truncated)'"))
          .isEqualTo(1);
      assertThat(count("id = '" + cancelled + "' and char_length(last_error) = 4000")).isEqualTo(1);

      UUID requested =
          bikesched
              .newJob(unique("long-request"))
              .handler(() -> {})
              .schedule(java.time.Duration.ofHours(1));
      assertThat(bikesched.cancel(requested, huge)).isTrue();
      assertThat(count("id = '" + requested + "' and char_length(cancel_message) = 4000"))
          .isEqualTo(1);
      awaitState(requested, "CANCELLED");
    }
  }

  @Test
  void lengthIsCountedInCharactersLikePostgres() {
    // 200 emoji: 400 UTF-16 chars, but 200 characters for Java's code points and Postgres.
    assertThat(JobType.of("\uD83D\uDEB2".repeat(200), String.class).name()).hasSize(400);
  }

  @Test
  void schemaEnforcesTheLimits() {
    assertThatThrownBy(
            () ->
                execute(
                    "insert into bikesched_jobs (id, type) values (gen_random_uuid(), '"
                        + "x".repeat(201)
                        + "')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("bikesched_jobs_type_check");
    assertThatThrownBy(
            () ->
                execute(
                    "insert into bikesched_events (id, name, key, expires_at)"
                        + " values (gen_random_uuid(), 'event', '', now())"))
        .hasMessageContaining("bikesched_events_name_key_check");
    assertThatThrownBy(
            () -> execute("insert into bikesched_nodes (id, name) values (gen_random_uuid(), '')"))
        .hasMessageContaining("bikesched_nodes_name_check");
    assertThatThrownBy(
            () ->
                execute(
                    "insert into bikesched_jobs (id, type, recurrence)"
                        + " values (gen_random_uuid(), 'x', '"
                        + "d".repeat(201)
                        + "')"))
        .hasMessageContaining("bikesched_jobs_recurrence_check");
  }

  /** The prefix padded with {@code x} to exactly the given length. */
  private static String ofLength(String prefix, int length) {
    return prefix + "x".repeat(length - prefix.length());
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
