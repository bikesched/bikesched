// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bikesched.admin.EventQuery;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.JobQuery;
import org.bikesched.admin.JobSummary;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.job.JobHistoryEntry;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobState;
import org.bikesched.job.Recurrence;
import org.bikesched.job.listener.JobClaim;
import org.bikesched.job.listener.JobListener;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * References of jobs and events ({@code reference(String)} on the job and event builders): stored
 * at creation, inherited by child jobs and events published by a job, and found by the admin
 * lookups.
 */
class ReferenceTest extends AbstractBikeschedTest {

  @Test
  void jobStoresItsReference() throws Exception {
    String name = unique("reference-job");
    try (Bikesched bikesched = node("node-reference-job")) {
      UUID withReference =
          bikesched
              .newJob(name)
              .handler(() -> {})
              .reference("order-4711")
              .schedule(Duration.ofHours(1));
      UUID without = bikesched.newJob(name).schedule(Duration.ofHours(1));
      UUID cleared =
          bikesched.newJob(name).reference("x").reference(null).schedule(Duration.ofHours(1));

      assertThat(bikesched.findJob(withReference).flatMap(JobInfo::reference))
          .contains("order-4711");
      assertThat(bikesched.findJob(without).flatMap(JobInfo::reference)).isEmpty();
      assertThat(bikesched.findJob(cleared).flatMap(JobInfo::reference)).isEmpty();
      List<JobHistoryEntry> history = bikesched.findJobHistory(withReference);
      assertThat(history.getFirst().detail()).endsWith(", reference order-4711");
    } finally {
      deleteJobs(name);
    }
  }

  @Test
  void transactionalAndIdempotentJobsStoreTheirReference() throws Exception {
    String name = unique("reference-keyed");
    try (Bikesched bikesched = node("node-reference-keyed");
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID transactional =
          bikesched
              .newJob(name)
              .handler(() -> {})
              .connection(connection)
              .reference("order-1")
              .schedule(Duration.ofHours(1));
      connection.commit();
      UUID keyed =
          bikesched
              .newJob(name)
              .idempotencyKey("delivery-1")
              .reference("order-2")
              .schedule(Duration.ofHours(1));
      UUID duplicate =
          bikesched
              .newJob(name)
              .idempotencyKey("delivery-1")
              .reference("order-3")
              .schedule(Duration.ofHours(1));

      assertThat(bikesched.findJob(transactional).flatMap(JobInfo::reference)).contains("order-1");
      assertThat(duplicate).isEqualTo(keyed);
      assertThat(bikesched.findJob(keyed).flatMap(JobInfo::reference))
          .as("the duplicate's reference is ignored")
          .contains("order-2");
    } finally {
      deleteJobs(name);
    }
  }

  @Test
  void referenceIsValidatedLikeAName() throws Exception {
    try (Bikesched bikesched = node("node-reference-invalid")) {
      assertThatThrownBy(() -> bikesched.newJob("any").reference(" "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reference must not be blank");
      assertThatThrownBy(() -> bikesched.newJob("any").reference("r".repeat(201)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at most 200 characters");
      assertThatThrownBy(() -> bikesched.newEvent("any", "key").reference("r".repeat(201)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at most 200 characters");
      // 200 characters outside the BMP: 200 code points, 800 bytes in UTF-8.
      assertThatThrownBy(() -> bikesched.newJob("any").reference("😀".repeat(200)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("bytes in UTF-8");
      String longest = "r".repeat(200);
      String name = unique("reference-longest");
      try {
        UUID jobId = bikesched.newJob(name).reference(longest).schedule(Duration.ofHours(1));
        assertThat(bikesched.findJob(jobId).flatMap(JobInfo::reference)).contains(longest);
      } finally {
        deleteJobs(name);
      }
    }
  }

  @Test
  void runningJobSeesItsReferenceInTheJobTheListenerAndTheLogContext() throws Exception {
    String name = unique("reference-running");
    var seen = new CompletableFuture<List<Object>>();
    List<JobClaim> claims = new CopyOnWriteArrayList<>();
    JobListener listener =
        new JobListener() {
          @Override
          public void onJobClaimed(JobClaim claim) {
            if (claim.jobName().equals(name)) {
              claims.add(claim);
            }
          }
        };
    try (Bikesched bikesched = testNode("node-reference-running").jobListener(listener).build()) {
      bikesched.registerJobHandler(
          name,
          job ->
              seen.complete(
                  List.of(
                      job.reference(),
                      Optional.ofNullable(MDC.get(BikeschedLogContext.JOB_REFERENCE)))));
      UUID jobId = bikesched.newJob(name).reference("order-9").schedule();

      assertThat(seen.get(10, TimeUnit.SECONDS))
          .containsExactly(Optional.of("order-9"), Optional.of("order-9"));
      awaitState(jobId, "SUCCEEDED");
      assertThat(claims).extracting(JobClaim::reference).containsExactly("order-9");
    } finally {
      deleteJobs(name);
    }
  }

  @Test
  void jobWithoutReferenceHasNoLogContextEntry() throws Exception {
    String name = unique("reference-none");
    var seen = new CompletableFuture<Optional<String>>();
    try (Bikesched bikesched = testNode("node-reference-none").build()) {
      bikesched.registerJobHandler(
          name,
          job -> seen.complete(Optional.ofNullable(MDC.get(BikeschedLogContext.JOB_REFERENCE))));
      bikesched.newJob(name).schedule();

      assertThat(seen.get(10, TimeUnit.SECONDS)).isEmpty();
    } finally {
      deleteJobs(name);
    }
  }

  @Test
  void childJobsAndEventsOfAJobInheritItsReferenceUnlessSet() throws Exception {
    String parent = unique("reference-parent");
    String child = unique("reference-child");
    String event = unique("reference-event");
    try (Bikesched bikesched = testNode("node-reference-inherit").build()) {
      bikesched.registerJobHandler(child, job -> {});
      UUID parentId =
          bikesched
              .newJob(parent)
              .handler(
                  job -> {
                    job.newJob(child).stepName("inherited").schedule();
                    job.newJob(child).stepName("own").reference("payment-1").schedule();
                    job.newJob(child).stepName("none").reference(null).schedule();
                    job.newEvent(event, "inherited").stepName("publish-inherited").publish();
                    job.newEvent(event, "own")
                        .stepName("publish-own")
                        .reference("payment-2")
                        .publish();
                  })
              .reference("order-7")
              .schedule();
      awaitState(parentId, "SUCCEEDED");

      List<JobSummary> children = bikesched.admin().findJobs(JobQuery.newest(10).withName(child));
      assertThat(children)
          .extracting(JobSummary::reference)
          .containsExactlyInAnyOrder("order-7", "payment-1", null);
      List<EventSummary> events =
          bikesched.admin().findEvents(EventQuery.newest(10).withName(event));
      assertThat(events)
          .extracting(EventSummary::key, EventSummary::reference)
          .containsExactlyInAnyOrder(tuple("inherited", "order-7"), tuple("own", "payment-2"));
    } finally {
      deleteJobs(child);
      deleteJobs(parent);
      execute("delete from bikesched_events where name = '" + event + "'");
    }
  }

  @Test
  void everyOccurrenceOfARecurringJobHasItsReference() throws Exception {
    String name = unique("reference-recurring");
    try (Bikesched bikesched = node("node-reference-recurring")) {
      UUID occurrence =
          bikesched
              .newJob(name)
              .handler(() -> {})
              .reference("tenant-3")
              .schedule(Recurrence.every(Duration.ofHours(1)), Instant.now().plusSeconds(3600));

      assertThat(bikesched.findJob(occurrence).flatMap(JobInfo::reference)).contains("tenant-3");
    } finally {
      deleteJobs(name);
    }
  }

  @Test
  void eventStoresItsReferenceAndReportsIt() throws Exception {
    String name = unique("reference-node-event");
    List<EventPublication> published = new CopyOnWriteArrayList<>();
    EventListener listener =
        new EventListener() {
          @Override
          public void onEventPublished(EventPublication publication) {
            if (publication.eventName().equals(name)) {
              published.add(publication);
            }
          }
        };
    try (Bikesched bikesched =
            testNode("node-reference-event")
                .eventListener(listener)
                .autoStartPolling(false)
                .build();
        Connection connection = dataSource.getConnection()) {
      UUID first = bikesched.newEvent(name, "k-1").reference("pay-1").publish();
      UUID keyed =
          bikesched.newEvent(name, "k-2").idempotencyKey("d-1").reference("pay-2").publish();
      UUID duplicate =
          bikesched.newEvent(name, "k-2").idempotencyKey("d-1").reference("pay-3").publish();
      connection.setAutoCommit(false);
      UUID transactional =
          bikesched.newEvent(name, "k-3").connection(connection).reference("pay-4").publish();
      connection.commit();

      assertThat(duplicate).isEqualTo(keyed);
      assertThat(bikesched.admin().findEvent(first).map(EventSummary::reference)).contains("pay-1");
      assertThat(bikesched.admin().findEvent(keyed).map(EventSummary::reference)).contains("pay-2");
      assertThat(bikesched.admin().findEvent(transactional).map(EventSummary::reference))
          .contains("pay-4");
      assertThat(published)
          .extracting(EventPublication::reference)
          .as("events in the caller's transaction are not reported")
          .containsExactly("pay-1", "pay-2");
    } finally {
      execute("delete from bikesched_events where name = '" + name + "'");
    }
  }

  @Test
  void adminFindsJobsAndEventsByReference() throws Exception {
    String name = unique("reference-admin");
    String other = unique("reference-admin-other");
    String reference = unique("order");
    try (Bikesched bikesched = node("node-reference-admin")) {
      UUID older = bikesched.newJob(name).reference(reference).schedule(Duration.ofHours(1));
      UUID newer = bikesched.newJob(other).reference(reference).schedule(Duration.ofHours(1));
      bikesched.newJob(name).reference(reference + "-not").schedule(Duration.ofHours(1));
      bikesched.newJob(name).schedule(Duration.ofHours(1));
      UUID eventId = bikesched.newEvent(name, "key").reference(reference).publish();
      bikesched.newEvent(name, "key").publish();

      JobQuery query = JobQuery.newest(1).withReference(reference);
      List<JobSummary> first = bikesched.admin().findJobs(query);
      assertThat(first).extracting(JobSummary::id).containsExactly(newer);
      assertThat(bikesched.admin().findJobs(query.after(first.getLast())))
          .extracting(JobSummary::id)
          .containsExactly(older);
      assertThat(
              bikesched
                  .admin()
                  .findJobs(JobQuery.newest(10).withReference(reference).withName(name)))
          .extracting(JobSummary::id)
          .containsExactly(older);
      assertThat(
              bikesched
                  .admin()
                  .findJobs(
                      JobQuery.newest(10).withReference(reference).withStates(JobState.FAILED)))
          .isEmpty();
      assertThat(bikesched.admin().findEvents(EventQuery.newest(10).withReference(reference)))
          .extracting(EventSummary::id)
          .containsExactly(eventId);
    } finally {
      deleteJobs(name);
      deleteJobs(other);
      execute("delete from bikesched_events where name = '" + name + "'");
    }
  }

  @Test
  void lookupsByReferenceUseThePartialIndexes() throws Exception {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("set enable_seqscan = off");
      assertThat(
              plan(
                  statement,
                  "select id from bikesched_jobs where reference = 'r' order by id desc limit 50"))
          .contains("bikesched_jobs_reference_idx");
      assertThat(
              plan(
                  statement,
                  "select id from bikesched_events where expires_at > now() and reference = 'r'"
                      + " order by id desc limit 50"))
          .contains("bikesched_events_reference_idx");
      try (var rows =
          statement.executeQuery(
              "select indexdef from pg_indexes where indexname in"
                  + " ('bikesched_jobs_reference_idx', 'bikesched_events_reference_idx')")) {
        int indexes = 0;
        while (rows.next()) {
          assertThat(rows.getString(1)).endsWith("WHERE (reference IS NOT NULL)");
          indexes++;
        }
        assertThat(indexes).isEqualTo(2);
      }
    }
  }

  private static String plan(Statement statement, String sql) throws Exception {
    StringBuilder plan = new StringBuilder();
    try (var rows = statement.executeQuery("explain " + sql)) {
      while (rows.next()) {
        plan.append(rows.getString(1)).append('\n');
      }
    }
    return plan.toString();
  }

  /** A node that does not poll and records the job history. */
  private static Bikesched node(String name) {
    return testNode(name).autoStartPolling(false).jobHistory(Duration.ofDays(1)).build();
  }

  private static void deleteJobs(String name) throws Exception {
    execute(
        "delete from bikesched_job_history where job_id in"
            + " (select id from bikesched_jobs where name = '"
            + name
            + "')");
    execute("delete from bikesched_jobs where name = '" + name + "'");
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
