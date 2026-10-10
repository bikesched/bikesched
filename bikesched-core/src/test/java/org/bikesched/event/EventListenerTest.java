// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.bikesched.internal.DefaultBikesched;
import org.junit.jupiter.api.Test;

/** The {@link EventListener} sees every committed publication once. */
class EventListenerTest extends AbstractBikeschedTest {

  /** Records the reported publications. */
  static final class RecordingListener implements EventListener {
    final List<EventPublication> publications = new CopyOnWriteArrayList<>();

    @Override
    public void onEventPublished(EventPublication publication) {
      publications.add(publication);
    }

    List<EventPublication> named(String eventName) {
      return publications.stream().filter(p -> p.eventName().equals(eventName)).toList();
    }
  }

  @Test
  void reportsNodePublicationsWithTheReceivingSteps() {
    var listener = new RecordingListener();
    String eventName = unique("listener-node");
    try (Bikesched bikesched = testNode("node-event-listener").eventListener(listener).build()) {
      UUID waiting =
          bikesched
              .newJob(unique("listener-receiver"))
              .handler(job -> job.waitForEvent("wait", eventName, "k-1", String.class).receive())
              .schedule();
      awaitState(waiting, "WAITING");

      UUID received = bikesched.newEvent(eventName, "k-1").payload("\"x\"").publish();
      UUID unreceived = bikesched.newEvent(eventName, "k-2").publish();

      assertThat(listener.named(eventName))
          .as("reported before publish() returned")
          .containsExactly(
              new EventPublication(received, eventName, EventSource.NODE, 1),
              new EventPublication(unreceived, eventName, EventSource.NODE, 0));
      awaitState(waiting, "SUCCEEDED");
    }
  }

  @Test
  void doesNotReportPublicationsInTheCallersTransaction() throws Exception {
    var listener = new RecordingListener();
    String eventName = unique("listener-tx");
    try (Bikesched bikesched = testNode("node-event-listener-tx").eventListener(listener).build();
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);

      bikesched.newEvent(eventName, "committed").connection(connection).publish();
      connection.commit();
      bikesched.newEvent(eventName, "rolled-back").connection(connection).publish();
      connection.rollback();

      assertThat(countRows("bikesched_events", "name = '" + eventName + "'")).isOne();
      assertThat(listener.named(eventName)).isEmpty();
    }
  }

  @Test
  void reportsJobPublicationsOnceAcrossReplays() {
    var listener = new RecordingListener();
    String eventName = unique("listener-job");
    String continueName = unique("listener-continue");
    List<UUID> returned = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched =
        testNode("node-event-listener-job").eventListener(listener).build()) {
      UUID jobId =
          bikesched
              .newJob(unique("listener-publisher"))
              .handler(
                  job -> {
                    returned.add(job.newEvent(eventName, "k").publish());
                    job.waitForEvent("continue", continueName, "k", String.class).receive();
                  })
              .schedule();
      awaitState(jobId, "WAITING");
      bikesched.newEvent(continueName, "k").publish();
      awaitState(jobId, "SUCCEEDED");

      assertThat(returned).as("the handler ran twice").hasSize(2);
      assertThat(listener.named(eventName))
          .as("reported by the run that published it only")
          .containsExactly(
              new EventPublication(returned.getFirst(), eventName, EventSource.JOB, 0));
    }
  }

  @Test
  void failingListenerDoesNotAffectPublishing() throws Exception {
    var logs = LogCapture.of(DefaultBikesched.class);
    var listener =
        new EventListener() {
          @Override
          public void onEventPublished(EventPublication publication) {
            throw new IllegalStateException("listener broken");
          }
        };
    String eventName = unique("listener-broken");
    try (logs;
        Bikesched bikesched =
            testNode("node-event-listener-broken").eventListener(listener).build()) {
      UUID eventId = bikesched.newEvent(eventName, "k").publish();

      assertThat(countRows("bikesched_events", "id = '" + eventId + "'")).isOne();
      await()
          .untilAsserted(
              () ->
                  assertThat(logs.messages())
                      .contains("Event listener failed for event " + eventId + ", ignoring it"));
    }
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
