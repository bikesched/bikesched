// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Throughput and latency on one and on three nodes with the default settings, and that no job runs
 * twice while nodes compete for them. The numbers are reported, not asserted: they depend on the
 * machine. Each run appends them to {@code build/reports/stress/load.txt}.
 *
 * <p>Scaled with Gradle properties: {@code stress.jobs} (backlog size, default 5000), {@code
 * stress.rate} (jobs per second under steady load, default 200), {@code stress.seconds} (duration
 * of the steady load, default 20), {@code stress.mixedJobs} (jobs with child jobs, event waits,
 * sleeps and timeouts, default 1000) and {@code stress.concurrency} ({@code maxConcurrentJobs} per
 * node, default 20).
 */
class LoadTest {

  private static final int JOBS = Integer.getInteger("stress.jobs", 5_000);
  private static final int RATE = Integer.getInteger("stress.rate", 200);
  private static final int SECONDS = Integer.getInteger("stress.seconds", 20);
  private static final int MIXED_JOBS = Integer.getInteger("stress.mixedJobs", 1_000);
  private static final int CONCURRENCY = Integer.getInteger("stress.concurrency", 20);
  private static final Path REPORT = Path.of("build", "reports", "stress", "load.txt");

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
    try (Bikesched client = client(type)) {
      for (int i = 0; i < JOBS; i++) {
        client.newJob(type).schedule();
      }
    }

    long start = System.nanoTime();
    List<Bikesched> nodes = startNodes(nodeCount, type, executions, null);
    try {
      await("backlog drained")
          .atMost(Duration.ofMinutes(10))
          .pollInterval(Duration.ofMillis(100))
          .until(() -> succeeded(type) == JOBS);
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      report(
          "backlog: %d jobs with 2 steps on %d node(s): %s, %.0f jobs/s",
          JOBS, nodeCount, elapsed, JOBS * 1_000.0 / elapsed.toMillis());
      assertThat(executions.distinct()).as("steps run").isEqualTo(2 * JOBS);
      assertThat(executions.repeated()).as("steps run more than once").isZero();
    } finally {
      nodes.forEach(Bikesched::close);
    }
  }

  @ParameterizedTest(name = "{0} node(s)")
  @ValueSource(ints = {1, 3})
  void startsJobsRightAwayUnderSteadyLoad(int nodeCount) throws Exception {
    String type = "steady-" + UUID.randomUUID();
    var executions = new Executions();
    Map<UUID, Long> started = new ConcurrentHashMap<>();
    Map<UUID, Long> scheduled = new ConcurrentHashMap<>();
    List<Bikesched> nodes = startNodes(nodeCount, type, executions, started);
    int jobs = RATE * SECONDS;
    try (Bikesched client = client(type)) {
      long begin = System.nanoTime();
      long interval = 1_000_000_000L / RATE;
      for (int i = 0; i < jobs; i++) {
        LockSupport.parkNanos(begin + i * interval - System.nanoTime());
        long now = System.nanoTime();
        UUID id = client.newJob(type).schedule();
        scheduled.put(id, now);
      }
      await("every job succeeded")
          .atMost(Duration.ofMinutes(5))
          .pollInterval(Duration.ofMillis(100))
          .until(() -> succeeded(type) == jobs);

      long[] latencies =
          scheduled.entrySet().stream()
              .mapToLong(e -> started.get(e.getKey()) - e.getValue())
              .sorted()
              .toArray();
      report(
          "steady load: %d jobs/s for %d s on %d node(s), scheduled by another instance:"
              + " start latency p50 %s, p95 %s, p99 %s, max %s",
          RATE,
          SECONDS,
          nodeCount,
          percentile(latencies, 50),
          percentile(latencies, 95),
          percentile(latencies, 99),
          Duration.ofNanos(latencies[latencies.length - 1]));
      assertThat(executions.repeated()).as("steps run more than once").isZero();
    } finally {
      nodes.forEach(Bikesched::close);
    }
  }

  @ParameterizedTest(name = "{0} node(s)")
  @ValueSource(ints = {1, 3})
  void runsJobsWithChildJobsEventWaitsSleepsAndTimeouts(int nodeCount) throws Exception {
    var mixed = new MixedJobs();
    List<Bikesched> nodes = startNodes(nodeCount, mixed::register);
    try (Bikesched client = client(mixed.types().toArray(String[]::new))) {
      long start = System.nanoTime();
      List<UUID> parents = new ArrayList<>();
      for (int i = 0; i < MIXED_JOBS; i++) {
        parents.add(mixed.schedule(client));
      }
      for (UUID parent : parents) {
        mixed.publishEvent(client, parent);
      }
      await("every parent job succeeded")
          .atMost(Duration.ofMinutes(10))
          .pollInterval(Duration.ofMillis(100))
          .until(() -> database.queryLong(mixed.countParents("SUCCEEDED")) == MIXED_JOBS);
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      report(
          "mixed: %d jobs (step, child job whose first attempt times out, event wait, sleep of"
              + " %s, wait for the child, step) on %d node(s): %s, %.0f jobs/s",
          MIXED_JOBS,
          MixedJobs.SLEEP,
          nodeCount,
          elapsed,
          MIXED_JOBS * 1_000.0 / elapsed.toMillis());
      Executions executions = mixed.executions();
      assertThat(executions.jobsWith("one")).isEqualTo(MIXED_JOBS);
      assertThat(executions.jobsWith("two")).isEqualTo(MIXED_JOBS);
      assertThat(executions.jobsWith("child-attempt-1")).isEqualTo(MIXED_JOBS);
      assertThat(executions.jobsWith("child-attempt-2"))
          .as("children retried after their timeout")
          .isEqualTo(MIXED_JOBS);
      assertThat(executions.jobsWith("child-attempt-3")).isZero();
      assertThat(executions.repeated()).as("steps run more than once").isZero();
      assertThat(database.queryLong(mixed.countChildren("SUCCEEDED"))).isEqualTo(MIXED_JOBS);
    } finally {
      nodes.forEach(Bikesched::close);
    }
  }

  /**
   * An instance that only schedules: it does not poll, but tells the nodes about new jobs. It has
   * no-op handlers for the types, which scheduling requires.
   */
  private static Bikesched client(String... types) {
    Bikesched client =
        Bikesched.builder()
            .dataSource(database.pool("client-" + UUID.randomUUID(), 5))
            .nodeName("client-" + UUID.randomUUID())
            .autoStartPolling(false)
            .build();
    for (String type : types) {
      client.registerJobHandler(type, _ -> {}); // never runs: not polling
    }
    return client;
  }

  /**
   * Nodes with the default settings, apart from the concurrency, running jobs with two steps;
   * records the start of every job's first attempt into {@code started}, if given.
   */
  private static List<Bikesched> startNodes(
      int count, String type, Executions executions, Map<UUID, Long> started) {
    return startNodes(
        count,
        node ->
            node.registerJobHandler(
                type,
                job -> {
                  if (started != null) {
                    started.putIfAbsent(job.id(), System.nanoTime());
                  }
                  job.step("one", () -> executions.record(job.id(), "one"));
                  job.step("two", () -> executions.record(job.id(), "two"));
                }));
  }

  /** Nodes with the default settings, apart from the concurrency, with the given handlers. */
  private static List<Bikesched> startNodes(int count, Consumer<Bikesched> handlers) {
    List<Bikesched> nodes = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String name = "load-" + i + "-" + UUID.randomUUID();
      HikariDataSource pool = database.pool(name, CONCURRENCY + 5);
      Bikesched node =
          Bikesched.builder()
              .dataSource(pool)
              .nodeName(name)
              .maxConcurrentJobs(CONCURRENCY)
              .autoStartPolling(false)
              .build();
      handlers.accept(node);
      nodes.add(node);
    }
    nodes.forEach(Bikesched::start);
    return nodes;
  }

  private static long succeeded(String type) throws Exception {
    return database.queryLong(
        "select count(*) from bikesched_jobs where type = '" + type + "' and state = 'SUCCEEDED'");
  }

  private static Duration percentile(long[] sorted, int percentile) {
    int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
    return Duration.ofNanos(sorted[Math.max(0, index)]);
  }

  private static void report(String format, Object... args) throws IOException {
    String line =
        String.format(
            format,
            Arrays.stream(args).map(a -> a instanceof Duration d ? millis(d) : a).toArray());
    System.out.println(line);
    Files.createDirectories(REPORT.getParent());
    Files.writeString(
        REPORT,
        line + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private static String millis(Duration duration) {
    return String.format("%.1f ms", duration.toNanos() / 1_000_000.0);
  }
}
