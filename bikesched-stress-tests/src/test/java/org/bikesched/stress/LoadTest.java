// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Throughput on one and on three nodes with the default settings, and that no step runs twice while
 * nodes compete for the jobs. The number is printed, not asserted: it depends on the machine.
 *
 * <p>Scaled with Gradle properties: {@code stress.jobs} (backlog size, default 5000) and {@code
 * stress.concurrency} ({@code maxConcurrentJobs} per node, default 20).
 */
class LoadTest {

  private static final int JOBS = Integer.getInteger("stress.jobs", 5_000);
  private static final int CONCURRENCY = Integer.getInteger("stress.concurrency", 20);

  private static StressDatabase database;

  @BeforeAll
  static void startDatabase() {
    database = new StressDatabase();
  }

  @AfterAll
  static void stopDatabase() {
    database.close();
  }

  @AfterEach
  void closePools() {
    database.closePools();
  }

  @ParameterizedTest(name = "{0} node(s)")
  @ValueSource(ints = {1, 3})
  void drainsABacklog(int nodeCount) throws Exception {
    String type = "backlog-" + UUID.randomUUID();
    var executions = new Executions();
    try (Bikesched client = node("client-" + UUID.randomUUID(), 5)) {
      client.registerJobHandler(type, job -> {}); // never runs: not polling
      for (int i = 0; i < JOBS; i++) {
        client.newJob(type).schedule();
      }
    }

    long start = System.nanoTime();
    List<Bikesched> nodes = new ArrayList<>();
    for (int i = 0; i < nodeCount; i++) {
      Bikesched node = node("load-" + i + "-" + UUID.randomUUID(), CONCURRENCY + 5);
      node.registerJobHandler(
          type,
          job -> {
            job.step("one", () -> executions.record(job.id(), "one"));
            job.step("two", () -> executions.record(job.id(), "two"));
          });
      nodes.add(node);
    }
    nodes.forEach(Bikesched::start);
    try {
      await("backlog drained")
          .atMost(Duration.ofMinutes(10))
          .pollInterval(Duration.ofMillis(100))
          .until(
              () ->
                  database.queryLong(
                          "select count(*) from bikesched_jobs where type = '"
                              + type
                              + "' and state = 'SUCCEEDED'")
                      == JOBS);
      long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();

      System.out.printf(
          "backlog: %d jobs with 2 steps on %d node(s): %d ms, %.0f jobs/s%n",
          JOBS, nodeCount, millis, JOBS * 1_000.0 / millis);
      assertThat(executions.distinct()).as("steps run").isEqualTo(2 * JOBS);
      assertThat(executions.repeated()).as("steps run more than once").isZero();
    } finally {
      nodes.forEach(Bikesched::close);
    }
  }

  /** A node with the default settings, apart from the concurrency; started by the caller. */
  private static Bikesched node(String name, int poolSize) {
    return Bikesched.builder()
        .dataSource(database.pool(name, poolSize))
        .nodeName(name)
        .maxConcurrentJobs(CONCURRENCY)
        .autoStartPolling(false)
        .build();
  }
}
