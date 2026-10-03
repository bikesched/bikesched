package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import bikesched.core.job.JobBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Several nodes in one application, sharing a single HikariCP connection pool. */
class SharedConnectionPoolTest extends AbstractBikeschedTest {

  private static final int NODES = 3;
  private static final int JOBS = 60;

  @ParameterizedTest(name = "autoCommit={0}")
  @ValueSource(booleans = {true, false})
  void nodesSharingOnePoolExecuteEveryJobExactlyOnce(boolean autoCommit) throws Exception {
    String type = "shared-pool-" + autoCommit;
    var executedBy = new ConcurrentHashMap<UUID, String>();
    var duplicates = new AtomicInteger();

    try (HikariDataSource pool = pool(autoCommit)) {
      List<Bikesched> nodes = new ArrayList<>();
      try {
        for (int i = 1; i <= NODES; i++) {
          Bikesched node =
              Bikesched.builder()
                  .dataSource(pool)
                  .nodeName("pool-node-" + autoCommit + "-" + i)
                  .maxConcurrentJobs(3)
                  .heartbeatInterval(Duration.ofMillis(100))
                  .pollInterval(Duration.ofMillis(50))
                  .autoStartPolling(false)
                  .build();
          nodes.add(node);
          String nodeName = node.node().name();
          node.registerHandler(
              type,
              job -> {
                // A durable step: job threads use pool connections too.
                job.step(
                    "work",
                    String.class,
                    step -> {
                      Thread.sleep(20);
                      return "{\"node\": \"" + nodeName + "\"}";
                    });
                if (executedBy.putIfAbsent(job.id(), nodeName) != null) {
                  duplicates.incrementAndGet();
                }
              });
        }
        JobBuilder newJob = nodes.getFirst().newJob(type);
        for (int i = 0; i < JOBS; i++) {
          newJob.payload("{\"n\": " + i + "}").schedule();
        }
        nodes.forEach(Bikesched::startPolling);

        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(
                () ->
                    assertThat(count("job_type = '" + type + "' and state = 'SUCCEEDED'"))
                        .isEqualTo(JOBS));
      } finally {
        nodes.forEach(Bikesched::close);
      }

      assertThat(count("job_type = '" + type + "' and state = 'SUCCEEDED' and attempts = 1"))
          .isEqualTo(JOBS);
      assertThat(duplicates).as("no job executed twice").hasValue(0);
      assertThat(executedBy).hasSize(JOBS);
      Map<String, Long> perNode =
          executedBy.values().stream()
              .collect(Collectors.groupingBy(name -> name, Collectors.counting()));
      assertThat(perNode).as("work is spread across all nodes: %s", perNode).hasSize(NODES);
      assertThat(
              countRows(
                  "bikesched_steps s join bikesched_jobs j on j.id = s.job_id",
                  "j.job_type = '"
                      + type
                      + "' and s.state = 'SUCCEEDED' and s.result->>'node' like 'pool-node-%'"))
          .as("step results committed")
          .isEqualTo(JOBS);
      assertThat(countRows("bikesched_nodes", "name like 'pool-node-" + autoCommit + "-%'"))
          .as("all nodes deregistered")
          .isZero();
      assertThat(pool.getHikariPoolMXBean().getActiveConnections())
          .as("no leaked connections")
          .isZero();
    }
  }

  /** Deliberately smaller than the nodes' combined demand, so they compete for connections. */
  private static HikariDataSource pool(boolean autoCommit) {
    var config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setPoolName("bikesched-shared-" + autoCommit);
    config.setMaximumPoolSize(5);
    config.setAutoCommit(autoCommit);
    return new HikariDataSource(config);
  }
}
