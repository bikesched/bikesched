// SPDX-License-Identifier: Apache-2.0
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
    try (Bikesched bikesched = Bikesched.builder(dataSource).nodeName("node-a").build()) {
      assertThat(bikesched.node().name()).isEqualTo("node-a");
      assertThat(nodeName(bikesched.node().id())).isEqualTo("node-a");
    }
  }

  @Test
  void nodeNameDefaultsToHostName() throws Exception {
    try (Bikesched bikesched = Bikesched.builder(dataSource).build()) {
      String hostName = InetAddress.getLocalHost().getHostName();
      assertThat(bikesched.node().name()).isEqualTo(hostName);
      assertThat(nodeName(bikesched.node().id())).isEqualTo(hostName);
    }
  }

  @Test
  void builderRequiresDataSource() {
    assertThatThrownBy(() -> Bikesched.builder(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("dataSource");
  }

  @Test
  void nodeTimeoutMustBeAtLeastTwiceTheHeartbeatInterval() {
    assertThatThrownBy(
            () ->
                Bikesched.builder(dataSource)
                    .heartbeatInterval(Duration.ofSeconds(5))
                    .nodeTimeout(Duration.ofSeconds(5))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "nodeTimeout (PT5S) must be at least twice the heartbeatInterval (PT5S), otherwise a healthy"
                + " node's jobs are released");
  }

  @Test
  void pollHeartbeatAndNodeTimeoutAreConfigurable() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder(dataSource)
            .nodeName("node-timings-" + UUID.randomUUID())
            .pollInterval(Duration.ofMillis(50))
            .heartbeatInterval(Duration.ofMillis(100))
            .nodeTimeout(Duration.ofSeconds(5))
            .build()) {
      String type = "timings-" + UUID.randomUUID();
      bikesched.registerJobHandler(type, ignored -> {});
      UUID jobId = bikesched.newJob(type).schedule();
      awaitState(jobId, "SUCCEEDED");
      assertThat(nodeName(bikesched.node().id())).isEqualTo(bikesched.node().name());
    }
  }

  @Test
  void migrateCreatesTheSchemaWithoutPollingOrRegisteringANode() throws Exception {
    String nodeName = "node-migrate-" + UUID.randomUUID();
    try (Bikesched bikesched =
        Bikesched.builder(dataSource).nodeName(nodeName).autoStartPolling(false).build()) {
      assertThat(bikesched.node().isPolling()).isFalse();

      bikesched.migrate();

      assertThat(bikesched.findJob(UUID.randomUUID())).isEmpty();
      assertThat(countRows("bikesched_nodes", "name = '" + nodeName + "'"))
          .as("not registered")
          .isZero();
      assertThat(bikesched.node().isPolling()).isFalse();
    }
  }

  @Test
  void migrateFailsOnceClosed() {
    Bikesched bikesched =
        Bikesched.builder(dataSource)
            .nodeName("node-migrate-closed-" + UUID.randomUUID())
            .autoStartPolling(false)
            .build();
    bikesched.close();
    assertThatThrownBy(bikesched::migrate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is closed");
  }

  @Test
  void nodeNameMustNotBeBlank() {
    assertThatThrownBy(() -> Bikesched.builder(dataSource).nodeName(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nodeTimeoutMustCoverAtLeastTwoHeartbeats() {
    assertThatThrownBy(
            () ->
                Bikesched.builder(dataSource)
                    .heartbeatInterval(Duration.ofSeconds(5))
                    .nodeTimeout(Duration.ofSeconds(9))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nodeTimeout");
  }

  @Test
  void cleanUpIntervalsMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder(dataSource).nodeCleanUpInterval(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nodeCleanUpInterval");
    assertThatThrownBy(
            () -> Bikesched.builder(dataSource).eventCleanUpInterval(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventCleanUpInterval");
  }

  @Test
  void maxConcurrentJobsMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder(dataSource).maxConcurrentJobs(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Runs {@code count} jobs that each take 200 ms on a node with the given limit (default if null),
   * waits until all succeeded and returns the peak number of jobs running at once.
   */
  @Test
  void pollingCanBeStartedLater() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder(dataSource)
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

      Node node = bikesched.start();

      assertThat(bikesched.start()).as("no effect while already polling").isSameAs(node);
      assertThat(node.isPolling()).isTrue();
      assertThat(ran.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void startAfterCloseFails() throws Exception {
    Bikesched bikesched =
        Bikesched.builder(dataSource)
            .nodeName("node-closed-polling")
            .autoStartPolling(false)
            .build();
    bikesched.close();

    assertThat(bikesched.node().isPolling()).isFalse();
    assertThatThrownBy(bikesched::start).isInstanceOf(IllegalStateException.class);
    assertThat(bikesched.node().id()).as("never registered").isNull();
  }

  @Test
  void closeStopsHeartbeatAndDeregistersNode() throws Exception {
    Bikesched bikesched =
        Bikesched.builder(dataSource)
            .nodeName("node-closing")
            .heartbeatInterval(Duration.ofMillis(50))
            .build();
    long start = System.nanoTime();
    bikesched.close();
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("close() does not wait for the pending one-time recurring job repair")
        .isLessThan(Duration.ofSeconds(5));
    bikesched.close(); // idempotent

    assertThat(nodeName(bikesched.node().id())).isNull();
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
    Bikesched bikesched = Bikesched.builder(dataSource).nodeName("node-releasing").build();
    // Simulate a job claimed by this node whose processing never finished.
    execute(
        "insert into bikesched_jobs (id, name, state, locked_by_node_id, lock_token, locked_at) "
            + "values (gen_random_uuid(), 'stuck', 'RUNNING', '"
            + bikesched.node().id()
            + "', gen_random_uuid(), now())");

    bikesched.close();

    assertThat(count("name = 'stuck' and state = 'SCHEDULED' and locked_by_node_id is null"))
        .isEqualTo(1);
    assertThat(nodeName(bikesched.node().id())).isNull();
    execute("delete from bikesched_jobs where name = 'stuck'");
  }

  @Test
  void logsRedactedDatabaseUrlOnStartup() {
    var logs = LogCapture.of(DatabaseInitializer.class);
    try (logs;
        Bikesched ignored = Bikesched.builder(dataSource).nodeName("node-db-url").build()) {
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
