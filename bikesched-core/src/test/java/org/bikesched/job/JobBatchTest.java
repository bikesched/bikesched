// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedLogContext;
import org.bikesched.HistoryTesting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Job batches: {@link Job#newJobBatch(String, ResultJob)}, {@link JobBatch#await()} and the
 * outcomes in {@link JobBatchResult}.
 */
class JobBatchTest extends AbstractBikeschedTest {

  /** Deletes the jobs left behind (held items, waiting parents). */
  @AfterEach
  void deleteJobs() throws Exception {
    execute("delete from bikesched_jobs where name like 'batch-%'");
  }

  /** JSON text of a string, as the default mapper needs it. */
  private static String json(String text) {
    return "\"" + text + "\"";
  }

  @Test
  void aBatchRunsEveryItemAndReturnsTheResultsInItemOrder() throws Exception {
    var item = TypedJob.of("batch-basic-item", String.class, String.class);
    var parentRuns = new AtomicInteger();
    var results = new AtomicReference<List<String>>();
    try (Bikesched bikesched = testNode("node-batch-basic").history(Duration.ofHours(1)).build()) {
      bikesched.registerResultJobHandler(item, (job, payload) -> payload);
      UUID parent =
          bikesched
              .newJob("batch-basic")
              .handler(
                  job -> {
                    parentRuns.incrementAndGet();
                    JobBatch<String> batch =
                        job.newJobBatch("items", item)
                            .addAll(IntStream.range(0, 10).mapToObj(i -> json("p" + i)).toList())
                            .schedule();
                    results.set(batch.await().orElseThrow());
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(results.get())
          .containsExactlyElementsOf(IntStream.range(0, 10).mapToObj(i -> json("p" + i)).toList());
      assertThat(parentRuns).as("started once, resumed once").hasValue(2);
      assertThat(count("parent_job_id = '" + parent + "' and state = 'SUCCEEDED'")).isEqualTo(10);
      assertThat(HistoryTesting.actions(bikesched.findJobHistory(parent)))
          .containsSubsequence("items BATCH_STARTED", "SUSPENDED", "items BATCH_ENDED", "RESUMED");
    }
  }

  @Test
  void keysNameTheItemsAndTheirStepsAndAreTheItemsBatchKey() throws Exception {
    var item = TypedJob.of("batch-keys-item", String.class, String.class);
    var keys = new CopyOnWriteArrayList<String>();
    var mdcKeys = new CopyOnWriteArrayList<String>();
    var outcome = new AtomicReference<JobBatchResult<String>>();
    var ids = new AtomicReference<Map<String, UUID>>();
    try (Bikesched bikesched = testNode("node-batch-keys").build()) {
      bikesched.registerResultJobHandler(
          item,
          (job, payload) -> {
            keys.add(String.valueOf(job.batchKey()));
            mdcKeys.add(String.valueOf(MDC.get(BikeschedLogContext.BATCH_KEY)));
            return payload;
          });
      UUID parent =
          bikesched
              .newJob("batch-keys")
              .handler(
                  job -> {
                    assertThat(job.batchKey()).isNull();
                    JobBatch<String> batch =
                        job.newJobBatch("formats", item)
                            .add("webp", json("w"))
                            .add(json("positional"))
                            .addAll(List.of("a", "b"), s -> "k-" + s, s -> json(s))
                            .schedule();
                    ids.set(batch.jobIds());
                    outcome.set(batch.await());
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(ids.get()).containsOnlyKeys("webp", "1", "k-a", "k-b");
      assertThat(outcome.get().succeeded())
          .containsExactly(
              Map.entry("webp", json("w")),
              Map.entry("1", json("positional")),
              Map.entry("k-a", json("a")),
              Map.entry("k-b", json("b")));
      assertThat(keys).containsExactlyInAnyOrder("webp", "1", "k-a", "k-b");
      assertThat(mdcKeys).containsExactlyInAnyOrder("webp", "1", "k-a", "k-b");
      assertThat(bikesched.findJob(ids.get().get("webp")).orElseThrow().batchKey())
          .isEqualTo("webp");
      assertThat(bikesched.findJob(parent).orElseThrow().batchKey()).isNull();
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + parent + "' and name in ('formats/webp', 'formats/1')"))
          .isEqualTo(2);
    }
  }

  @Test
  void parallelismLimitsTheActiveItemsAndAdmitsTheOthersInOrder() throws Exception {
    var item = TypedJob.of("batch-parallel-item", String.class);
    var running = new AtomicInteger();
    var maxRunning = new AtomicInteger();
    var order = new CopyOnWriteArrayList<String>();
    try (Bikesched bikesched =
        testNode("node-batch-parallel").maxConcurrentJobs(8).history(Duration.ofHours(1)).build()) {
      bikesched.registerJobHandler(
          item,
          (job, payload) -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            order.add(job.batchKey());
            Thread.sleep(100);
            running.decrementAndGet();
          });
      UUID parent =
          bikesched
              .newJob("batch-parallel")
              .handler(
                  job ->
                      job.newJobBatch("items", item)
                          .addAll(IntStream.range(0, 6).mapToObj(i -> json("p" + i)).toList())
                          .parallelism(2)
                          .schedule()
                          .await()
                          .orElseThrow())
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(maxRunning.get()).isLessThanOrEqualTo(2);
      assertThat(order.subList(0, 2)).containsExactlyInAnyOrder("0", "1");
      // items admitted together may start in any order; their admission follows the item order
      List<java.time.Instant> admittedAt =
          itemIds(parent).stream()
              .flatMap(id -> bikesched.findJobHistory(id).stream())
              .filter(entry -> HistoryTesting.action(entry).equals("ADMITTED"))
              .map(org.bikesched.history.HistoryEntry::createdAt)
              .toList();
      assertThat(admittedAt).as("admitted in item order").hasSize(4).isSorted();
    }
  }

  @Test
  void aFailedItemIsReportedAndOrElseThrowNamesItsKey() throws Exception {
    var item = TypedJob.of("batch-fail-item", String.class, String.class);
    var outcome = new AtomicReference<JobBatchResult<String>>();
    try (Bikesched bikesched = testNode("node-batch-fail").build()) {
      bikesched.registerResultJobHandler(
          item,
          (job, payload) -> {
            if (json("bad").equals(payload)) {
              throw new IllegalStateException("bad payload");
            }
            return payload;
          });
      UUID parent =
          bikesched
              .newJob("batch-fail")
              .handler(
                  job ->
                      outcome.set(
                          job.newJobBatch("items", item)
                              .add("good", json("good"))
                              .add("bad", json("bad"))
                              .maxAttempts(1)
                              .schedule()
                              .await()))
              .schedule();
      awaitState(parent, "SUCCEEDED");

      JobBatchResult<String> result = outcome.get();
      assertThat(result.isAllSucceeded()).isFalse();
      assertThat(result.succeeded()).containsOnlyKeys("good");
      assertThat(result.unsucceeded()).containsOnlyKeys("bad");
      assertThat(result.get("bad")).isInstanceOf(JobResult.Failed.class);
      assertThatThrownBy(result::orElseThrow)
          .isInstanceOf(JobFailedException.class)
          .hasMessageContaining("Item 'bad' of job batch 'items' did not succeed")
          .hasMessageContaining("bad payload");
      assertThatThrownBy(() -> result.get("nope"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("has no item 'nope'");
    }
  }

  @Test
  void failFastEndsTheBatchAtTheFirstFailedItemAndCancelsTheOthers() throws Exception {
    var item = TypedJob.of("batch-fast-item", String.class);
    var release = new CountDownLatch(1);
    var outcome = new AtomicReference<JobBatchResult<Void>>();
    try (Bikesched bikesched = testNode("node-batch-fast").maxConcurrentJobs(8).build()) {
      bikesched.registerJobHandler(
          item,
          (job, payload) -> {
            if (json("fail").equals(payload)) {
              throw new IllegalStateException("failing fast");
            }
            release.await(10, TimeUnit.SECONDS);
          });
      UUID parent =
          bikesched
              .newJob("batch-fast")
              .handler(
                  job ->
                      outcome.set(
                          job.newJobBatch("items", item)
                              .add("slow", json("slow"))
                              .add("fail", json("fail"))
                              .add("held", json("held"))
                              .maxAttempts(1)
                              .parallelism(2)
                              .failFast()
                              .schedule()
                              .await()))
              .schedule();
      awaitState(parent, "SUCCEEDED");
      release.countDown();

      JobBatchResult<Void> result = outcome.get();
      assertThat(result.get("fail")).isInstanceOf(JobResult.Failed.class);
      assertThat(result.get("slow")).isInstanceOf(JobResult.Cancelled.class);
      assertThat(result.get("held")).isInstanceOf(JobResult.Cancelled.class);
      UUID held = result.get("held").jobId();
      assertThat(count("id = '" + held + "' and state = 'CANCELLED'")).isEqualTo(1);
      UUID slow = result.get("slow").jobId();
      assertThat(count("id = '" + slow + "' and cancel_requested_at is not null")).isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + parent + "' and name = 'items' and state = 'FAILED'"))
          .isEqualTo(1);
    }
  }

  @Test
  void aTimedOutBatchCancelsItsHeldItemsAndLeavesRunningOnes() throws Exception {
    var item = TypedJob.of("batch-timeout-item", String.class);
    var release = new CountDownLatch(1);
    var outcome = new AtomicReference<JobBatchResult<Void>>();
    var parentRuns = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-batch-timeout").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> release.await(10, TimeUnit.SECONDS));
      UUID parent =
          bikesched
              .newJob("batch-timeout")
              .handler(
                  job -> {
                    parentRuns.incrementAndGet();
                    outcome.set(
                        job.newJobBatch("items", item)
                            .add("running", json("r"))
                            .add("held", json("h"))
                            .parallelism(1)
                            .schedule()
                            .await(Duration.ofMillis(500)));
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      JobBatchResult<Void> result = outcome.get();
      assertThat(result.get("running")).isInstanceOf(JobResult.TimedOut.class);
      assertThat(result.get("held")).isInstanceOf(JobResult.TimedOut.class);
      assertThat(count("id = '" + result.get("held").jobId() + "' and state = 'CANCELLED'"))
          .isEqualTo(1);
      UUID running = result.get("running").jobId();
      assertThat(count("id = '" + running + "' and state = 'RUNNING'")).isEqualTo(1);

      release.countDown();
      awaitState(running, "SUCCEEDED");
      Thread.sleep(300);
      assertThat(parentRuns).as("the late item does not resume the parent").hasValue(2);
    }
  }

  @Test
  void cancellingTheParentCancelsTheHeldItems() throws Exception {
    var item = TypedJob.of("batch-cancel-item", String.class);
    var release = new CountDownLatch(1);
    var ids = new AtomicReference<Map<String, UUID>>();
    try (Bikesched bikesched = testNode("node-batch-cancel").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> release.await(10, TimeUnit.SECONDS));
      UUID parent =
          bikesched
              .newJob("batch-cancel")
              .handler(
                  job -> {
                    JobBatch<Void> batch =
                        job.newJobBatch("items", item)
                            .add("running", json("r"))
                            .add("held", json("h"))
                            .parallelism(1)
                            .schedule();
                    ids.set(batch.jobIds());
                    batch.await();
                  })
              .schedule();
      awaitState(parent, "WAITING");

      assertThat(bikesched.cancel(parent)).isTrue();
      awaitState(parent, "CANCELLED");
      awaitState(ids.get().get("held"), "CANCELLED");
      assertThat(count("id = '" + ids.get().get("running") + "' and state = 'RUNNING'"))
          .isEqualTo(1);
      release.countDown();
      awaitState(ids.get().get("running"), "SUCCEEDED");
    }
  }

  @Test
  void aHeldItemCanBeCancelledOnItsOwn() throws Exception {
    var item = TypedJob.of("batch-held-item", String.class);
    var release = new CountDownLatch(1);
    var ids = new AtomicReference<Map<String, UUID>>();
    var outcome = new AtomicReference<JobBatchResult<Void>>();
    try (Bikesched bikesched = testNode("node-batch-held").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> release.await(10, TimeUnit.SECONDS));
      UUID parent =
          bikesched
              .newJob("batch-held")
              .handler(
                  job -> {
                    JobBatch<Void> batch =
                        job.newJobBatch("items", item)
                            .add("first", json("1"))
                            .add("second", json("2"))
                            .add("third", json("3"))
                            .parallelism(1)
                            .schedule();
                    ids.set(batch.jobIds());
                    outcome.set(batch.await());
                  })
              .schedule();
      awaitState(parent, "WAITING");
      UUID second = ids.get().get("second");
      assertThat(count("id = '" + second + "' and state = 'HELD'")).isEqualTo(1);
      assertThat(bikesched.findJob(second).orElseThrow().state()).isEqualTo(JobState.HELD);

      assertThat(bikesched.cancel(second, "not needed")).isTrue();
      assertThat(bikesched.findJob(second).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
      release.countDown();
      awaitState(parent, "SUCCEEDED");

      assertThat(outcome.get().get("second"))
          .isEqualTo(new JobResult.Cancelled<Void>(second, "not needed"));
      assertThat(outcome.get().succeeded()).containsOnlyKeys("first", "third");
    }
  }

  @Test
  void aParentEndingWithoutWaitingCancelsItsHeldItems() throws Exception {
    var item = TypedJob.of("batch-noawait-item", String.class);
    var release = new CountDownLatch(1);
    var ids = new AtomicReference<Map<String, UUID>>();
    try (Bikesched bikesched = testNode("node-batch-noawait").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> release.await(10, TimeUnit.SECONDS));
      UUID parent =
          bikesched
              .newJob("batch-noawait")
              .handler(
                  job ->
                      ids.set(
                          job.newJobBatch("items", item)
                              .add("running", json("r"))
                              .add("held", json("h"))
                              .parallelism(1)
                              .schedule()
                              .jobIds()))
              .schedule();
      awaitState(parent, "SUCCEEDED");

      awaitState(ids.get().get("held"), "CANCELLED");
      release.countDown();
      awaitState(ids.get().get("running"), "SUCCEEDED");
    }
  }

  @Test
  void aReplayReturnsTheStoredBatchWhateverTheItemsAreNow() throws Exception {
    var item = TypedJob.of("batch-replay-item", String.class, String.class);
    var runs = new AtomicInteger();
    var outcome = new AtomicReference<JobBatchResult<String>>();
    try (Bikesched bikesched = testNode("node-batch-replay").build()) {
      bikesched.registerResultJobHandler(item, (job, payload) -> payload);
      UUID parent =
          bikesched
              .newJob("batch-replay")
              .handler(
                  job -> {
                    int run = runs.incrementAndGet();
                    var builder = job.newJobBatch("items", item).add("a", json("a"));
                    if (run > 1) {
                      builder.add("b", json("b")); // ignored: the batch was started
                    }
                    outcome.set(builder.schedule().await());
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(outcome.get().all()).containsOnlyKeys("a");
    }
  }

  @Test
  void mistakesAreRejectedBeforeAnythingIsWritten() throws Exception {
    var item = TypedJob.of("batch-mistakes-item", String.class);
    var failures = new CopyOnWriteArrayList<Throwable>();
    try (Bikesched bikesched = testNode("node-batch-mistakes").maxJobBatchSize(4).build()) {
      bikesched.registerJobHandler(item, (job, payload) -> {});
      UUID parent =
          bikesched
              .newJob("batch-mistakes")
              .handler(
                  job -> {
                    record(
                        failures,
                        () ->
                            job.newJobBatch("dup", item)
                                .add("a", json("1"))
                                .add("a", json("2"))
                                .add(json("3"))
                                .add("2", json("4"))
                                .schedule());
                    record(
                        failures,
                        () ->
                            job.newJobBatch("big", item)
                                .addAll(
                                    List.of(json("1"), json("2"), json("3"), json("4"), json("5")))
                                .schedule());
                    record(failures, () -> job.newJobBatch("zero", item).parallelism(0));
                    var empty = job.newJobBatch("empty", item).schedule().await();
                    assertThat(empty.all()).isEmpty();
                    assertThat(empty.isAllSucceeded()).isTrue();
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(failures).hasSize(3);
      assertThat(failures.get(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("duplicate keys [a, 2]");
      assertThat(failures.get(1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("more than maxJobBatchSize (4)");
      assertThat(failures.get(2)).isInstanceOf(IllegalArgumentException.class);
      assertThat(countRows("bikesched_steps", "job_id = '" + parent + "' and name <> 'empty'"))
          .as("only the empty batch was written")
          .isZero();
    }
  }

  private static void record(List<Throwable> failures, Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      failures.add(e);
    }
  }

  @Test
  void aMixedBatchRunsEachItemsJobAndReadsItsResultTypedByIt() throws Exception {
    var virus = TypedJob.of("batch-mixed-virus", String.class, String.class);
    var untyped = TypedJob.of("batch-mixed-nsfw", String.class);
    var outcome = new AtomicReference<JobBatchResult<Object>>();
    try (Bikesched bikesched = testNode("node-batch-mixed").build()) {
      bikesched.registerResultJobHandler(virus, (job, payload) -> json("clean"));
      bikesched.registerJobHandler(untyped, (job, payload) -> job.complete(json("safe")));
      bikesched.registerJobHandler("batch-mixed-named", job -> job.complete(json("named")));
      UUID parent =
          bikesched
              .newJob("batch-mixed")
              .handler(
                  job -> {
                    assertThatThrownBy(() -> job.newJobBatch("bad").add(virus, null, json("x")))
                        .isInstanceOf(NullPointerException.class);
                    outcome.set(
                        job.newJobBatch("checks")
                            .add(virus, "virus", json("f"))
                            .add(untyped, "nsfw", json("f"))
                            .add("batch-mixed-named", "named", json("f"))
                            .schedule()
                            .await());
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      JobBatchResult<Object> result = outcome.get();
      assertThat(result.get("virus", virus).orElseThrow()).isEqualTo(json("clean"));
      assertThat(result.get("nsfw", String.class).orElseThrow()).isEqualTo(json("safe"));
      assertThat(result.get("named").orElseThrow()).isEqualTo(json("named"));
      assertThatThrownBy(() -> result.get("nsfw", virus))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("batch-mixed-nsfw, not batch-mixed-virus");
    }
  }

  @Test
  void waitingForASingleItemIsRejected() throws Exception {
    var item = TypedJob.of("batch-single-item", String.class);
    var failure = new AtomicReference<Throwable>();
    try (Bikesched bikesched = testNode("node-batch-single").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> {});
      UUID parent =
          bikesched
              .newJob("batch-single")
              .handler(
                  job -> {
                    JobBatch<Void> batch = job.newJobBatch("items", item).add(json("1")).schedule();
                    try {
                      job.waitForJob(batch.jobIds().get("0"), Void.class);
                    } catch (IllegalArgumentException e) {
                      failure.set(e);
                    }
                    batch.await();
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(failure.get()).hasMessageContaining("wait for the batch with JobBatch.await()");
    }
  }

  @Test
  void theCompensationRunsForEveryStartedItemInReverseOrder() throws Exception {
    var item = TypedJob.of("batch-comp-item", String.class);
    var compensated = new CopyOnWriteArrayList<String>();
    try (Bikesched bikesched = testNode("node-batch-comp").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> {});
      UUID parent =
          bikesched
              .newJob("batch-comp")
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.newJobBatch("items", item)
                        .addAll(List.of(json("1"), json("2"), json("3")))
                        .compensateWith((step, batchItem) -> compensated.add(batchItem.key()))
                        .schedule()
                        .await();
                    throw new IllegalStateException("fails after the batch");
                  })
              .schedule();
      awaitState(parent, "FAILED");

      assertThat(compensated).containsExactly("2", "1", "0");
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + parent + "' and name = 'items' and state = 'COMPENSATED'"))
          .isEqualTo(1);
    }
  }

  @Test
  void resultJobsTypeSingleChildrenAndReturnTheirResult() throws Exception {
    var charge = TypedJob.of("batch-charge", String.class, String.class);
    var receipt = new AtomicReference<String>();
    try (Bikesched bikesched = testNode("node-batch-result-job").build()) {
      bikesched.registerResultJobHandler(charge, (job, payload) -> json("receipt"));
      UUID parent =
          bikesched
              .newJob("batch-result-parent")
              .handler(
                  job -> {
                    UUID child = job.newJob(charge).payload(json("42")).schedule();
                    receipt.set(job.waitForJob(child, charge).orElseThrow());
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(receipt.get()).isEqualTo(json("receipt"));
    }
  }

  @Test
  void itemsRunOnEveryNode() throws Exception {
    var item = TypedJob.of("batch-nodes-item", String.class);
    var nodes = new CopyOnWriteArrayList<String>();
    try (Bikesched first = testNode("node-batch-nodes-1").maxConcurrentJobs(2).build();
        Bikesched second = testNode("node-batch-nodes-2").maxConcurrentJobs(2).build()) {
      for (Bikesched node : List.of(first, second)) {
        String nodeName = node == first ? "first" : "second";
        node.registerJobHandler(
            item,
            (job, payload) -> {
              nodes.add(nodeName);
              Thread.sleep(100);
            });
      }
      UUID parent =
          first
              .newJob("batch-nodes")
              .handler(
                  job ->
                      job.newJobBatch("items", item)
                          .addAll(IntStream.range(0, 12).mapToObj(i -> json("p" + i)).toList())
                          .schedule()
                          .await()
                          .orElseThrow())
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(nodes).hasSize(12).contains("first", "second");
    }
  }

  @Test
  void itemsEndingAtTheSameTimeResumeTheParentOnce() throws Exception {
    var item = TypedJob.of("batch-race-item", String.class);
    var parentRuns = new AtomicInteger();
    var barrier = new java.util.concurrent.CyclicBarrier(4);
    try (Bikesched bikesched = testNode("node-batch-race").maxConcurrentJobs(8).build()) {
      bikesched.registerJobHandler(item, (job, payload) -> barrier.await(10, TimeUnit.SECONDS));
      UUID parent =
          bikesched
              .newJob("batch-race")
              .handler(
                  job -> {
                    parentRuns.incrementAndGet();
                    job.newJobBatch("items", item)
                        .addAll(IntStream.range(0, 4).mapToObj(i -> json("p" + i)).toList())
                        .schedule()
                        .await()
                        .orElseThrow();
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");
      Thread.sleep(300);

      assertThat(parentRuns).as("started once, resumed once").hasValue(2);
    }
  }

  @Test
  void itemsFailingAtTheSameTimeEndAFailFastBatchOnce() throws Exception {
    var item = TypedJob.of("batch-fail-race-item", String.class);
    var parentRuns = new AtomicInteger();
    var barrier = new java.util.concurrent.CyclicBarrier(3);
    var outcome = new AtomicReference<JobBatchResult<Void>>();
    try (Bikesched bikesched =
        testNode("node-batch-fail-race")
            .maxConcurrentJobs(8)
            .history(Duration.ofHours(1))
            .build()) {
      bikesched.registerJobHandler(
          item,
          (job, payload) -> {
            barrier.await(10, TimeUnit.SECONDS);
            throw new IllegalStateException("failing together");
          });
      UUID parent =
          bikesched
              .newJob("batch-fail-race")
              .handler(
                  job -> {
                    parentRuns.incrementAndGet();
                    outcome.set(
                        job.newJobBatch("items", item)
                            .addAll(IntStream.range(0, 3).mapToObj(i -> json("p" + i)).toList())
                            .maxAttempts(1)
                            .failFast()
                            .schedule()
                            .await());
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");
      Thread.sleep(300);

      assertThat(parentRuns).hasValue(2);
      assertThat(outcome.get().isAllSucceeded()).isFalse();
      assertThat(
              HistoryTesting.actions(bikesched.findJobHistory(parent)).stream()
                  .filter(action -> action.equals("items BATCH_ENDED"))
                  .count())
          .isEqualTo(1);
    }
  }

  @Test
  void aSleepingParentIsNotWokenByEveryItem() throws Exception {
    var item = TypedJob.of("batch-sleep-item", String.class);
    var parentRuns = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-batch-sleep").maxConcurrentJobs(8).build()) {
      bikesched.registerJobHandler(item, (job, payload) -> {});
      UUID parent =
          bikesched
              .newJob("batch-sleep")
              .handler(
                  job -> {
                    parentRuns.incrementAndGet();
                    JobBatch<Void> batch =
                        job.newJobBatch("items", item)
                            .addAll(IntStream.range(0, 6).mapToObj(i -> json("p" + i)).toList())
                            .schedule();
                    job.sleep("pause", Duration.ofSeconds(1));
                    batch.await().orElseThrow();
                  })
              .schedule();
      awaitState(parent, "SUCCEEDED");

      // the start, at most one replay at the batch's last item, and the end of the sleep
      assertThat(parentRuns.get()).isBetween(2, 3);
    }
  }

  @Test
  void aCompensatedBatchStartsNewItemsWhenTheParentIsRetried() throws Exception {
    var item = TypedJob.of("batch-retry-item", String.class);
    var retried = new java.util.concurrent.atomic.AtomicBoolean();
    var otherKeys = new java.util.concurrent.atomic.AtomicBoolean();
    var compensated = new CopyOnWriteArrayList<UUID>();
    var failure = new AtomicReference<Throwable>();
    try (Bikesched bikesched = testNode("node-batch-retry").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> {});
      UUID parent =
          bikesched
              .newJob("batch-retry")
              .maxAttempts(1)
              .handler(
                  job -> {
                    var builder = job.newJobBatch("items", item).add("a", json("a"));
                    if (otherKeys.get()) {
                      builder.add("b", json("b"));
                    }
                    try {
                      builder
                          .compensateWith((step, batchItem) -> compensated.add(batchItem.jobId()))
                          .schedule()
                          .await()
                          .orElseThrow();
                    } catch (IllegalStateException e) {
                      failure.set(e);
                      throw e;
                    }
                    if (!retried.get()) {
                      throw new IllegalStateException("fails after the batch");
                    }
                  })
              .schedule();
      awaitState(parent, "FAILED");
      assertThat(compensated).hasSize(1);
      UUID firstItem = compensated.getFirst();

      retried.set(true);
      assertThat(bikesched.admin().retryJob(parent).retry()).isTrue();
      awaitState(parent, "SUCCEEDED");

      assertThat(count("parent_job_id = '" + parent + "'")).as("a new item").isEqualTo(2);
      assertThat(count("parent_job_id = '" + parent + "' and state = 'SUCCEEDED'")).isEqualTo(2);
      assertThat(count("id = '" + firstItem + "'")).as("the old item stays").isEqualTo(1);
      assertThat(failure.get()).isNull();
    }
  }

  @Test
  void aCompensatedBatchRejectsOtherKeysOnTheRetry() throws Exception {
    var item = TypedJob.of("batch-rekey-item", String.class);
    var retried = new java.util.concurrent.atomic.AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    try (Bikesched bikesched = testNode("node-batch-rekey").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> {});
      UUID parent =
          bikesched
              .newJob("batch-rekey")
              .maxAttempts(1)
              .handler(
                  job -> {
                    var builder = job.newJobBatch("items", item).add("a", json("a"));
                    if (retried.get()) {
                      builder.add("b", json("b"));
                    }
                    try {
                      builder.compensateWith((step, batchItem) -> {}).schedule().await();
                    } catch (IllegalStateException e) {
                      failure.set(e);
                      throw e;
                    }
                    if (!retried.get()) {
                      throw new IllegalStateException("fails after the batch");
                    }
                  })
              .schedule();
      awaitState(parent, "FAILED");

      retried.set(true);
      assertThat(bikesched.admin().retryJob(parent).retry()).isTrue();
      await().untilAsserted(() -> assertThat(failure.get()).isNotNull());

      assertThat(failure.get()).hasMessageContaining("missing [], new [b]");
      assertThat(count("parent_job_id = '" + parent + "'")).as("no new item").isEqualTo(1);
    }
  }

  @Test
  void aRaisedLimitStoresLargeBatchesInChunks() throws Exception {
    try (Bikesched bikesched =
        testNode("node-batch-big")
            .maxJobBatchSize(2_500)
            .remoteJobNames("batch-big-item")
            .build()) {
      UUID parent =
          bikesched
              .newJob("batch-big")
              .handler(
                  job ->
                      job.newJobBatch("items", "batch-big-item")
                          .addAll(IntStream.range(0, 2_500).mapToObj(i -> json("p" + i)).toList())
                          .parallelism(1_200)
                          .schedule())
              .schedule();
      awaitState(parent, "SUCCEEDED");

      assertThat(count("parent_job_id = '" + parent + "'")).isEqualTo(2_500);
      assertThat(count("parent_job_id = '" + parent + "' and state = 'CANCELLED'"))
          .as("the parent ended without waiting: its held items are cancelled")
          .isEqualTo(1_300);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + parent + "' and name = 'items/2499' and type = 'CHILD_JOB'"))
          .isEqualTo(1);
    }
  }

  @Test
  void theAdminShowsABatchAsOneStepAndListsItsItems() throws Exception {
    var item = TypedJob.of("batch-admin-item", String.class);
    var release = new CountDownLatch(1);
    try (Bikesched bikesched = testNode("node-batch-admin").build()) {
      bikesched.registerJobHandler(item, (job, payload) -> release.await(10, TimeUnit.SECONDS));
      UUID parent =
          bikesched
              .newJob("batch-admin")
              .handler(
                  job ->
                      job.newJobBatch("items", item)
                          .add("a", json("a"))
                          .add("b", json("b"))
                          .add("c", json("c"))
                          .parallelism(1)
                          .failFast()
                          .schedule()
                          .await())
              .schedule();
      awaitState(parent, "WAITING");
      List<org.bikesched.admin.StepInfo> steps = bikesched.admin().findSteps(parent);

      assertThat(steps).as("without the item steps").hasSize(1);
      var step = steps.getFirst();
      assertThat(step.type()).isEqualTo("CHILD_JOB_BATCH");
      assertThat(step.batch()).isEqualTo(new org.bikesched.admin.StepBatch(3, 0, 1, 1, true));
      var items =
          bikesched.admin().findJobs(org.bikesched.admin.JobQuery.newest(10).withBatch(step.id()));
      assertThat(items)
          .extracting(org.bikesched.admin.JobSummary::batchKey)
          .containsExactlyInAnyOrder("a", "b", "c");
      assertThat(
              bikesched
                  .admin()
                  .findJobs(
                      org.bikesched.admin.JobQuery.newest(10)
                          .withBatch(step.id())
                          .withStates(JobState.HELD)))
          .hasSize(2);
      release.countDown();
      awaitState(parent, "SUCCEEDED");
    }
  }

  private static List<UUID> itemIds(UUID parent) throws Exception {
    List<UUID> ids = new ArrayList<>();
    try (var connection = dataSource.getConnection();
        var select =
            connection.prepareStatement(
                "select id from bikesched_jobs where parent_job_id = ? order by batch_index")) {
      select.setObject(1, parent);
      try (var rs = select.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getObject(1, UUID.class));
        }
      }
    }
    await().until(() -> !ids.isEmpty());
    return ids;
  }
}
