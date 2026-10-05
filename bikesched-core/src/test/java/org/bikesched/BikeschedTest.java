package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.bikesched.internal.database.DatabaseInitializer;
import org.junit.jupiter.api.Test;

/** Building, configuring, starting/stopping polling and closing a {@link Bikesched} node. */
class BikeschedTest extends AbstractBikeschedTest {

  @Test
  void buildMigratesSchemaAndRegistersNode() throws SQLException {
    try (Bikesched bikesched =
        Bikesched.builder().dataSource(dataSource).nodeName("node-a").build()) {
      assertThat(bikesched.node().name()).isEqualTo("node-a");
      assertThat(nodeName(bikesched.node().id().orElseThrow())).isEqualTo("node-a");
    }
  }

  @Test
  void nodeNameDefaultsToHostName() throws Exception {
    try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).build()) {
      String hostName = InetAddress.getLocalHost().getHostName();
      assertThat(bikesched.node().name()).isEqualTo(hostName);
      assertThat(nodeName(bikesched.node().id().orElseThrow())).isEqualTo(hostName);
    }
  }

  @Test
  void buildRequiresDataSource() {
    assertThatThrownBy(() -> Bikesched.builder().build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("dataSource is required");
  }

  @Test
  void nodeNameMustNotBeBlank() {
    assertThatThrownBy(() -> Bikesched.builder().nodeName(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nodeTimeoutMustCoverAtLeastTwoHeartbeats() {
    assertThatThrownBy(
            () ->
                Bikesched.builder()
                    .dataSource(dataSource)
                    .heartbeatInterval(Duration.ofSeconds(5))
                    .nodeTimeout(Duration.ofSeconds(9))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nodeTimeout");
  }

  @Test
  void cleanUpIntervalsMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder().nodeCleanUpInterval(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nodeCleanUpInterval");
    assertThatThrownBy(() -> Bikesched.builder().eventCleanUpInterval(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventCleanUpInterval");
  }

  @Test
  void maxConcurrentJobsMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder().maxConcurrentJobs(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Runs {@code count} jobs that each take 200 ms on a node with the given limit (default if null),
   * waits until all succeeded and returns the peak number of jobs running at once.
   */
  @Test
  void pollingCanBeStartedLater() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-manual-polling")
            .pollInterval(Duration.ofMillis(50))
            .autoStartPolling(false)
            .build()) {
      var ran = new java.util.concurrent.CountDownLatch(1);
      UUID jobId = bikesched.newJob("manual").handler(job -> ran.countDown()).schedule();
      Thread.sleep(300); // several poll intervals

      assertThat(bikesched.node().isPolling()).isFalse();
      assertThat(count("id = '" + jobId + "' and state = 'SCHEDULED' and attempts = 0"))
          .as("not claimed while polling is off")
          .isEqualTo(1);

      Node node = bikesched.startPolling();

      assertThat(bikesched.startPolling()).as("no effect while already polling").isSameAs(node);
      assertThat(node.isPolling()).isTrue();
      assertThat(ran.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void startPollingAfterCloseFails() throws Exception {
    Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-closed-polling")
            .autoStartPolling(false)
            .build();
    bikesched.close();

    assertThat(bikesched.node().isPolling()).isFalse();
    assertThatThrownBy(bikesched::startPolling).isInstanceOf(IllegalStateException.class);
    assertThat(bikesched.node().id()).as("never registered").isEmpty();
  }

  @Test
  void closeStopsHeartbeatAndDeregistersNode() throws Exception {
    Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-closing")
            .heartbeatInterval(Duration.ofMillis(50))
            .build();
    long start = System.nanoTime();
    bikesched.close();
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("close() does not wait for the pending one-time recurring job repair")
        .isLessThan(Duration.ofSeconds(5));
    bikesched.close(); // idempotent

    assertThat(nodeName(bikesched.node().id().orElseThrow())).isNull();
    assertThat(Thread.getAllStackTraces().keySet())
        .noneMatch(
            t ->
                (t.getName().equals("bikesched-heartbeat")
                        || t.getName().equals("bikesched-cleanup")
                        || t.getName().equals("bikesched-poller"))
                    && t.isAlive());
  }

  @Test
  void closeReleasesJobsThatAreStillRunning() throws Exception {
    Bikesched bikesched =
        Bikesched.builder().dataSource(dataSource).nodeName("node-releasing").build();
    // Simulate a job claimed by this node whose processing never finished.
    execute(
        "insert into bikesched_jobs (id, job_type, state, locked_by, lock_token) "
            + "values (gen_random_uuid(), 'stuck', 'RUNNING', '"
            + bikesched.node().id().orElseThrow()
            + "', gen_random_uuid())");

    bikesched.close();

    assertThat(count("job_type = 'stuck' and state = 'SCHEDULED' and locked_by is null"))
        .isEqualTo(1);
    assertThat(nodeName(bikesched.node().id().orElseThrow())).isNull();
    execute("delete from bikesched_jobs where job_type = 'stuck'");
  }

  @Test
  void logsRedactedDatabaseUrlOnStartup() {
    var logs = LogCapture.of(DatabaseInitializer.class);
    try (logs;
        Bikesched _ = Bikesched.builder().dataSource(dataSource).nodeName("node-db-url").build()) {
      assertThat(logs.events())
          .filteredOn(e -> e.getFormattedMessage().startsWith("Database: "))
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.DEBUG);
                assertThat(e.getFormattedMessage())
                    .matches(
                        "Database: jdbc:postgresql://[^/@?]+:\\d+/\\w+ \\(PostgreSQL 18\\..*\\)");
              });
    }
  }
}
