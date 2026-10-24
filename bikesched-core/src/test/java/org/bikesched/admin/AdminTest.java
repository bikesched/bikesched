// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedException;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;
import org.bikesched.job.Recurrence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The read-only lookups of {@link Bikesched#admin()}. */
class AdminTest extends AbstractBikeschedTest {

  private final String prefix = "admin-" + UUID.randomUUID().toString().substring(0, 8) + "-";

  /** Deletes the jobs and events left behind (scheduled jobs of a node that does not poll). */
  @AfterEach
  void deleteRows() throws Exception {
    execute("delete from bikesched_jobs where name like '" + prefix + "%'");
    execute("delete from bikesched_events where name like '" + prefix + "%'");
  }

  @Test
  void findsJobsByTypeNewestFirstInPagesWithoutGaps() {
    String type = prefix + "paged";
    try (Bikesched bikesched = testNode(prefix + "node").autoStartPolling(false).build()) {
      bikesched.registerJobHandler(type, ignored -> {});
      bikesched.registerJobHandler(prefix + "other", ignored -> {});
      List<UUID> scheduled = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        scheduled.add(bikesched.newJob(type).schedule(Duration.ofHours(1)));
      }
      bikesched.newJob(prefix + "other").schedule(Duration.ofHours(1));

      Admin admin = bikesched.admin();
      JobQuery query = JobQuery.newest(2).withName(type);
      List<UUID> found = new ArrayList<>();
      List<Integer> pageSizes = new ArrayList<>();
      List<JobSummary> page = admin.findJobs(query);
      while (!page.isEmpty()) {
        pageSizes.add(page.size());
        page.forEach(job -> found.add(job.id()));
        query = query.after(page.getLast());
        page = admin.findJobs(query);
      }

      assertThat(pageSizes).containsExactly(2, 2, 1);
      assertThat(found).containsExactlyElementsOf(scheduled.reversed());
      JobSummary newest = admin.findJobs(JobQuery.newest(1).withName(type)).getFirst();
      assertThat(newest.name()).isEqualTo(type);
      assertThat(newest.type()).isEqualTo(JobType.ONE_OFF);
      assertThat(newest.state()).isEqualTo(JobState.SCHEDULED);
      assertThat(newest.maxAttempts()).isPositive();
      assertThat(newest.scheduledAt()).isAfter(newest.createdAt());
      assertThat(newest.parentJobId()).isNull();
    }
  }

  @Test
  void filtersByStatesAndCountsTheTypes() {
    String type = prefix + "states";
    try (Bikesched bikesched = testNode(prefix + "node").build()) {
      bikesched.registerJobHandler(
          type,
          job -> {
            if (job.payload(String.class).equals("\"fail\"")) { // the default mapper: JSON text
              job.fail("failed on purpose");
            }
          });
      UUID failed = bikesched.newJob(type).payload("\"fail\"").maxAttempts(1).schedule();
      UUID succeeded = bikesched.newJob(type).payload("\"ok\"").schedule();
      UUID later = bikesched.newJob(type).payload("\"ok\"").schedule(Duration.ofHours(1));
      awaitState(failed, "FAILED");
      awaitState(succeeded, "SUCCEEDED");

      Admin admin = bikesched.admin();
      assertThat(admin.findJobs(JobQuery.newest(10).withName(type).withStates(JobState.FAILED)))
          .singleElement()
          .satisfies(
              job -> {
                assertThat(job.id()).isEqualTo(failed);
                assertThat(job.lastError()).contains("failed on purpose");
                assertThat(job.finishedAt()).isNotNull();
              });
      assertThat(
              admin.findJobs(
                  JobQuery.newest(10)
                      .withName(type)
                      .withStates(JobState.SUCCEEDED, JobState.SCHEDULED)))
          .extracting(JobSummary::id)
          .containsExactlyInAnyOrder(succeeded, later);

      JobNameSummary summary =
          admin.findJobNames().stream()
              .filter(t -> t.name().equals(type))
              .findFirst()
              .orElseThrow();
      assertThat(summary.count(JobState.FAILED)).isOne();
      assertThat(summary.count(JobState.SUCCEEDED)).isOne();
      assertThat(summary.count(JobState.SCHEDULED)).isOne();
      assertThat(summary.count(JobState.RUNNING)).isZero();
      assertThat(summary.total()).isEqualTo(3);
    }
  }

  @Test
  void marksTheTypesThisNodeHandlesAndFindsJobsByIdempotencyKey() {
    String handled = prefix + "handled";
    String fresh = prefix + "fresh";
    String foreign = prefix + "foreign";
    try (Bikesched other = testNode(prefix + "other").build();
        Bikesched bikesched = testNode(prefix + "node").build()) {
      other.registerJobHandler(foreign, ignored -> {});
      other.newJob(foreign).schedule(Duration.ofHours(1));
      bikesched.registerJobHandler(handled, ignored -> {});
      bikesched.registerJobHandler(fresh, ignored -> {});
      UUID keyed =
          bikesched
              .newJob(handled)
              .payload("\"ignored\"")
              .payloadJson("{\"a\": 1}")
              .idempotencyKey("key-1")
              .schedule(Duration.ofHours(1));
      UUID empty = bikesched.newJob(handled).payloadJson(null).schedule(Duration.ofHours(1));

      Admin admin = bikesched.admin();
      assertThat(admin.findJobNames())
          .filteredOn(type -> type.name().startsWith(prefix))
          .extracting(JobNameSummary::name, JobNameSummary::total, JobNameSummary::handled)
          .containsExactly(
              tuple(foreign, 1, false), tuple(fresh, 0, true), tuple(handled, 2, true));
      assertThat(admin.findJobData(keyed).orElseThrow().payload()).isEqualTo("{\"a\": 1}");
      assertThat(admin.findJobData(empty).orElseThrow().payload()).isNull();
      assertThat(admin.findJob(handled, "key-1")).map(JobSummary::id).contains(keyed);
      assertThat(admin.findJob(handled, "key-2")).isEmpty();
      assertThat(admin.findJob(fresh, "key-1")).isEmpty();
      assertThatThrownBy(() -> bikesched.newJob(fresh).payloadJson("{\"a\": }").schedule())
          .isInstanceOf(BikeschedException.class);
    }
  }

  @Test
  void readsAJobWithItsDataAndSteps() {
    String type = prefix + "steps";
    String event = prefix + "event";
    try (Bikesched bikesched = testNode(prefix + "node").build()) {
      bikesched.registerJobHandler(
          type,
          job -> {
            job.step("first", String.class, ignored -> "\"one\"");
            job.waitForEvent("await", event, "key-1", String.class).receive();
            job.complete("{\"done\": true}");
          });
      UUID jobId =
          bikesched.newJob(type).payload("{\"order\": 42}").idempotencyKey("order-42").schedule();
      awaitState(jobId, "WAITING");

      Admin admin = bikesched.admin();
      JobSummary waiting = admin.findJob(jobId).orElseThrow();
      assertThat(waiting.state()).isEqualTo(JobState.WAITING);
      assertThat(waiting.scheduledAt()).as("waiting without a deadline").isNull();
      assertThat(waiting.idempotencyKey()).isEqualTo("order-42");
      assertThat(admin.findSteps(jobId))
          .extracting(StepInfo::name, StepInfo::type, StepInfo::state)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("first", "STEP", "SUCCEEDED"),
              org.assertj.core.groups.Tuple.tuple("await", "EVENT_WAIT", "WAITING"));
      assertThat(admin.findWaitingSteps(event, null, 10))
          .singleElement()
          .satisfies(
              step -> {
                assertThat(step.jobId()).isEqualTo(jobId);
                assertThat(step.jobName()).isEqualTo(type);
                assertThat(step.stepName()).isEqualTo("await");
                assertThat(step.eventKey()).isEqualTo("key-1");
              });
      assertThat(admin.findWaitingSteps(event, "key-", 10)).hasSize(1);
      assertThat(admin.findWaitingSteps(event, "other", 10)).isEmpty();
      assertThat(admin.findEventNames())
          .as("a name only waited for is listed without events")
          .contains(new EventNameSummary(event, 0, 1));

      UUID eventId = bikesched.newEvent(event, "key-1").payload("\"paid\"").publish();
      awaitState(jobId, "SUCCEEDED");

      assertThat(admin.findJobData(jobId))
          .contains(new JobData("{\"order\": 42}", "{\"done\": true}"));
      assertThat(admin.findSteps(jobId))
          .extracting(StepInfo::name, StepInfo::state)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("first", "SUCCEEDED"),
              org.assertj.core.groups.Tuple.tuple("await", "SUCCEEDED"));
      assertThat(admin.findWaitingSteps(event, null, 10)).isEmpty();
      assertThat(admin.findEventNames()).contains(new EventNameSummary(event, 1, 0));
      assertThat(admin.findEvent(eventId))
          .hasValueSatisfying(
              summary -> {
                assertThat(summary.name()).isEqualTo(event);
                assertThat(summary.key()).isEqualTo("key-1");
                assertThat(summary.receivedBy()).isOne();
                assertThat(summary.latest()).isTrue();
              });
      assertThat(admin.findEventSteps(eventId))
          .singleElement()
          .satisfies(
              step -> {
                assertThat(step.jobId()).isEqualTo(jobId);
                assertThat(step.stepName()).isEqualTo("await");
                assertThat(step.isPublisher()).isFalse();
              });
      assertThat(admin.findEventPayload(eventId)).contains("\"paid\"");
      assertThat(admin.findJob(UUID.randomUUID())).isEmpty();
      assertThat(admin.findSteps(UUID.randomUUID())).isEmpty();
    }
  }

  @Test
  void findsEventsByNameAndKeyPrefixInPages() {
    String name = prefix + "events";
    try (Bikesched bikesched = testNode(prefix + "node").autoStartPolling(false).build()) {
      UUID first = bikesched.newEvent(name, "order-1").publish();
      UUID second = bikesched.newEvent(name, "order-1").publish();
      UUID third = bikesched.newEvent(name, "invoice-1").publish();
      bikesched.newEvent(prefix + "other", "order-1").publish();

      Admin admin = bikesched.admin();
      List<EventSummary> page = admin.findEvents(EventQuery.newest(2).withName(name));
      assertThat(page).extracting(EventSummary::id).containsExactly(third, second);
      assertThat(admin.findEvents(EventQuery.newest(2).withName(name).after(page.getLast())))
          .extracting(EventSummary::id)
          .containsExactly(first);
      List<EventSummary> orders =
          admin.findEvents(EventQuery.newest(10).withName(name).withKeyPrefix("order-"));
      assertThat(orders).extracting(EventSummary::id).containsExactly(second, first);
      assertThat(orders)
          .as("the newest event of a name and key is the one a new wait receives")
          .extracting(EventSummary::latest)
          .containsExactly(true, false);
      assertThat(orders.getFirst().receivedBy()).isZero();
      assertThat(admin.findEventNames())
          .contains(new EventNameSummary(name, 3, 0), new EventNameSummary(prefix + "other", 1, 0));
      assertThat(admin.findEventSteps(first)).as("published outside a job").isEmpty();
    }
  }

  @Test
  void findsTheStepThatPublishedAnEvent() {
    String type = prefix + "publisher";
    String name = prefix + "published";
    var eventId = new AtomicReference<UUID>();
    try (Bikesched bikesched = testNode(prefix + "node").build()) {
      bikesched.registerJobHandler(type, job -> eventId.set(job.newEvent(name, "k").publish()));
      UUID jobId = bikesched.newJob(type).schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(bikesched.admin().findEventSteps(eventId.get()))
          .singleElement()
          .satisfies(
              step -> {
                assertThat(step.jobId()).isEqualTo(jobId);
                assertThat(step.jobName()).isEqualTo(type);
                assertThat(step.jobState()).isEqualTo(JobState.SUCCEEDED);
                assertThat(step.isPublisher()).isTrue();
              });
    }
  }

  @Test
  void linksChildJobsAndTheirParents() {
    String parentType = prefix + "parent";
    String childType = prefix + "child";
    var childId = new AtomicReference<UUID>();
    try (Bikesched bikesched = testNode(prefix + "node").build()) {
      bikesched.registerJobHandler(childType, job -> job.complete("\"child result\""));
      bikesched.registerJobHandler(
          parentType,
          job -> {
            UUID child = job.newJob(childType).stepName("start-child").schedule();
            childId.set(child);
            job.waitForJob(child, String.class);
          });
      UUID parentId = bikesched.newJob(parentType).schedule();
      awaitState(parentId, "SUCCEEDED");

      Admin admin = bikesched.admin();
      JobSummary child = admin.findJob(childId.get()).orElseThrow();
      assertThat(child.parentJobId()).isEqualTo(parentId);
      assertThat(child.parentJobName()).isEqualTo(parentType);
      assertThat(admin.findSteps(parentId))
          .filteredOn(step -> step.type().equals("CHILD_JOB"))
          .singleElement()
          .satisfies(
              step -> {
                assertThat(step.childJobId()).isEqualTo(childId.get());
                assertThat(step.childJobName()).isEqualTo(childType);
                assertThat(step.childJobState()).isEqualTo(JobState.SUCCEEDED);
                assertThat(step.childJobExists()).isTrue();
              });
    }
  }

  @Test
  void listsNodesAliveAndDead() throws Exception {
    UUID deadId = UUID.randomUUID();
    String deadName = prefix + "dead";
    execute(
        "insert into bikesched_nodes (id, name, started_at, last_heartbeat_at) values ('"
            + deadId
            + "', '"
            + deadName
            + "', now() - interval '2 hours', now() - interval '1 hour')");
    try (Bikesched bikesched = testNode(prefix + "alive").autoStartPolling(false).build()) {
      bikesched.start(); // registers the node
      await()
          .untilAsserted(
              () ->
                  assertThat(bikesched.admin().findNodes())
                      .anySatisfy(
                          node -> {
                            assertThat(node.name()).isEqualTo(prefix + "alive");
                            assertThat(node.alive()).isTrue();
                          })
                      .anySatisfy(
                          node -> {
                            assertThat(node.id()).isEqualTo(deadId);
                            assertThat(node.alive()).isFalse();
                          }));
    } finally {
      execute("delete from bikesched_nodes where id = '" + deadId + "'");
    }
  }

  @Test
  void findsANodeWithTheJobsItRuns() throws Exception {
    String type = prefix + "on-node";
    CountDownLatch release = new CountDownLatch(1);
    try (Bikesched bikesched = testNode(prefix + "runner").build()) {
      bikesched.registerJobHandler(type, ignored -> release.await());
      UUID running = bikesched.newJob(type).schedule();
      UUID scheduled = bikesched.newJob(type).schedule(Duration.ofHours(1));
      UUID nodeId = bikesched.node().id().orElseThrow();
      Admin admin = bikesched.admin();

      await()
          .untilAsserted(
              () -> assertThat(admin.findNode(nodeId).orElseThrow().runningJobs()).isEqualTo(1));
      NodeInfo node = admin.findNode(nodeId).orElseThrow();
      assertThat(node.name()).isEqualTo(prefix + "runner");
      assertThat(node.alive()).isTrue();
      assertThat(admin.findNodes()).contains(node);
      assertThat(admin.findJobs(JobQuery.newest(10).withNode(nodeId)))
          .extracting(JobSummary::id)
          .containsExactly(running);
      assertThat(admin.findJobs(JobQuery.newest(10).withName(type)))
          .extracting(JobSummary::id)
          .containsExactlyInAnyOrder(running, scheduled);
      assertThat(admin.findNode(UUID.randomUUID())).isEmpty();

      release.countDown();
      await()
          .untilAsserted(
              () -> assertThat(admin.findNode(nodeId).orElseThrow().runningJobs()).isZero());
      assertThat(admin.findJobs(JobQuery.newest(10).withNode(nodeId))).isEmpty();
      execute("delete from bikesched_jobs where id = '" + scheduled + "'");
    } finally {
      release.countDown();
    }
  }

  @Test
  void findsJobsAndEventsByTheLastDigitsOfTheirId() {
    String type = prefix + "by-id";
    try (Bikesched bikesched = testNode(prefix + "node").autoStartPolling(false).build()) {
      bikesched.registerJobHandler(type, ignored -> {});
      UUID jobId = bikesched.newJob(type).schedule(Duration.ofHours(1));
      UUID eventId = bikesched.newEvent(prefix + "by-id", "k").publish();
      String jobShort = jobId.toString().substring(28);

      Admin admin = bikesched.admin();
      assertThat(admin.findJobsByIdSuffix(jobShort, 10))
          .extracting(JobSummary::id)
          .containsExactly(jobId);
      assertThat(admin.findJobsByIdSuffix(" " + jobId.toString().toUpperCase() + " ", 10))
          .as("a full id, upper case and padded")
          .extracting(JobSummary::id)
          .containsExactly(jobId);
      assertThat(admin.findJobsByIdSuffix(jobId.toString().replace("-", ""), 10))
          .as("a full id without hyphens")
          .extracting(JobSummary::id)
          .containsExactly(jobId);
      assertThat(admin.findEventsByIdSuffix(eventId.toString().substring(19), 10))
          .as("a suffix with a hyphen")
          .extracting(EventSummary::id)
          .containsExactly(eventId);
      assertThat(admin.findJobsByIdSuffix(eventId.toString(), 10)).as("only jobs").isEmpty();
      assertThat(admin.findEventsByIdSuffix(UUID.randomUUID().toString(), 10)).isEmpty();
      String otherStart = "f" + jobId.toString().replace("-", "").substring(1);
      assertThat(admin.findJobsByIdSuffix(otherStart, 10))
          .as("the same short id, another id")
          .isEmpty();
    }
  }

  @Test
  void rejectsIdSuffixesThatAreTooShortOrNotHex() {
    try (Bikesched bikesched = testNode(prefix + "node").autoStartPolling(false).build()) {
      Admin admin = bikesched.admin();
      assertThatThrownBy(() -> admin.findJobsByIdSuffix("3f2a9c1", 10))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 8 and 32 hex digits");
      assertThatThrownBy(() -> admin.findEventsByIdSuffix("3f2a9c1g", 10))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> admin.findJobsByIdSuffix(UUID.randomUUID() + "0", 10))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> admin.findJobsByIdSuffix("3f2a9c1e", 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void findsThePendingAndTheLatestEndedRunsOfARecurringJob() {
    String type = prefix + "recurring";
    try (Bikesched bikesched = testNode(prefix + "node").build()) {
      bikesched.registerJobHandler(type, ignored -> {});
      UUID oneOff = bikesched.newJob(type).schedule(Duration.ofHours(1));
      bikesched.newJob(type).schedule(Recurrence.every(Duration.ofMillis(200)), Duration.ZERO);
      Admin admin = bikesched.admin();

      await().until(() -> admin.findRecurringJobRuns(type, 10).previous().size() >= 3);
      RecurringJobRuns runs = admin.findRecurringJobRuns(type, 2);

      assertThat(runs.name()).isEqualTo(type);
      assertThat(runs.pending()).isNotNull();
      assertThat(runs.pending().recurrence()).isEqualTo("every PT0.2S");
      assertThat(runs.previous()).hasSize(2);
      assertThat(runs.previous())
          .allSatisfy(run -> assertThat(run.state()).isEqualTo(JobState.SUCCEEDED))
          .extracting(JobSummary::id)
          .doesNotContain(oneOff, runs.pending().id());
      assertThat(runs.previous().getFirst().createdAt())
          .isAfterOrEqualTo(runs.previous().getLast().createdAt());

      // A run in progress ends normally, without a successor.
      bikesched.removeRecurringJob(type);
      await().until(() -> admin.findRecurringJobRuns(type, 10).pending() == null);
      assertThat(admin.findRecurringJobRuns(type, 10).previous()).hasSizeGreaterThanOrEqualTo(3);
      assertThat(admin.findRecurringJobRuns(prefix + "unknown", 10))
          .isEqualTo(new RecurringJobRuns(prefix + "unknown", null, List.of()));
      assertThatThrownBy(() -> admin.findRecurringJobRuns(type, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsLimitsOutOfRange() {
    assertThatThrownBy(() -> JobQuery.newest(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JobQuery.newest(JobQuery.MAX_LIMIT + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventQuery.newest(0)).isInstanceOf(IllegalArgumentException.class);
    try (Bikesched bikesched = testNode(prefix + "node").autoStartPolling(false).build()) {
      assertThatThrownBy(() -> bikesched.admin().findWaitingSteps("e", null, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
