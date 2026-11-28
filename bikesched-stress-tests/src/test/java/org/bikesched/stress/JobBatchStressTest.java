// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.bikesched.Bikesched;
import org.bikesched.job.TypedJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A large job batch on three nodes while the database restarts: the parent resumes once all items
 * ended, every item runs, and the parallelism holds across the nodes throughout.
 */
class JobBatchStressTest {

  private static final int ITEMS = Integer.getInteger("stress.batchItems", 5_000);
  private static final int PARALLELISM = 50;

  private static StressDatabase database;

  @BeforeAll
  static void startDatabase() {
    database = new StressDatabase();
  }

  @AfterAll
  static void stopDatabase() {
    database.close();
  }

  @Test
  void aLargeBatchKeepsItsParallelismAcrossNodesAndARestart() throws Exception {
    var executions = new Executions();
    var item = TypedJob.of("batch-item-" + UUID.randomUUID(), Integer.class);
    String parentName = "batch-parent-" + UUID.randomUUID();
    var parentRuns = new AtomicLong();
    try (Bikesched a = node("batch-a", item, executions);
        Bikesched b = node("batch-b", item, executions);
        Bikesched c = node("batch-c", item, executions)) {
      a.registerJobHandler(
          parentName,
          job -> {
            parentRuns.incrementAndGet();
            job.newJobBatch("items", item)
                .addAll(IntStream.range(0, ITEMS).boxed().toList())
                .parallelism(PARALLELISM)
                .schedule()
                .await();
          });
      UUID parent = a.newJob(parentName).timeout(Duration.ofMinutes(2)).schedule();
      await("the batch was stored")
          .atMost(Duration.ofMinutes(1))
          .until(
              () ->
                  database.queryLong(
                          "select count(*) from bikesched_steps where job_id = '"
                              + parent
                              + "' and type = 'CHILD_JOB_BATCH'")
                      == 1);
      // Samples the items admitted and not ended, as one consistent snapshot each.
      String active =
          "select count(*) from bikesched_jobs where parent_job_id = '"
              + parent
              + "' and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'FAILING', 'COMPENSATING')";
      var maxActive = new AtomicLong();
      Thread sampler =
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                      try {
                        maxActive.accumulateAndGet(database.queryLong(active), Math::max);
                        Thread.sleep(20);
                      } catch (InterruptedException e) {
                        return;
                      } catch (Exception ignored) {
                        // the database is restarting
                      }
                    }
                  });

      await("a third of the items ended")
          .atMost(Duration.ofMinutes(2))
          .until(() -> ended(parent) >= ITEMS / 3);
      database.restart();

      await("the parent succeeded")
          .atMost(Duration.ofMinutes(5))
          .pollInterval(Duration.ofMillis(500))
          .ignoreExceptions()
          .until(
              () ->
                  database.queryLong(
                          "select count(*) from bikesched_jobs where id = '"
                              + parent
                              + "' and state = 'SUCCEEDED'")
                      == 1);
      sampler.interrupt();
      sampler.join();

      assertThat(
              database.queryLong(
                  "select count(*) from bikesched_jobs where parent_job_id = '"
                      + parent
                      + "' and state = 'SUCCEEDED'"))
          .isEqualTo(ITEMS);
      assertThat(executions.jobsWith("work")).as("every item ran").isEqualTo(ITEMS);
      assertThat(maxActive.get()).as("items active at once").isLessThanOrEqualTo(PARALLELISM);
      assertThat(database.queryLong(active)).as("no items still active").isZero();
      // started, resumed once, and replays of attempts the restart cut off
      assertThat(parentRuns.get()).isBetween(2L, 4L);
    }
  }

  private static long ended(UUID parent) throws Exception {
    return database.queryLong(
        "select count(*) from bikesched_jobs where parent_job_id = '"
            + parent
            + "' and state = 'SUCCEEDED'");
  }

  private static Bikesched node(String name, TypedJob<Integer> item, Executions executions) {
    Bikesched node =
        StressNodes.node(database.pool(name, 30), name)
            .maxConcurrentJobs(20)
            .maxJobBatchSize(ITEMS)
            .build();
    node.registerJobHandler(
        item,
        (job, payload) -> {
          executions.record(job.id(), "work");
          Thread.sleep(5);
        });
    return node;
  }
}
