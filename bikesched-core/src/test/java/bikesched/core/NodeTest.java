package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import bikesched.core.internal.Heartbeat;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Node registration: heartbeats, unique names, taking over stale nodes and cleaning up dead nodes.
 */
class NodeTest extends AbstractBikeschedTest {

  @Test
  void heartbeatUpdatesNode() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-heartbeat")
            .heartbeatInterval(Duration.ofMillis(100))
            .build()) {
      UUID id = bikesched.node().id().orElseThrow();
      OffsetDateTime registered = lastHeartbeat(id);
      await().untilAsserted(() -> assertThat(lastHeartbeat(id)).isAfter(registered));
    }
  }

  @Test
  void nodeRegistersAndHeartbeatsOnlyOncePolling() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-registers-later")
            .heartbeatInterval(Duration.ofMillis(50))
            .autoStartPolling(false)
            .build()) {
      // Scheduling needs no registration (the handler is never run: the node does not poll).
      bikesched.newJob("not-polling").handler(job -> {}).schedule();
      execute("delete from bikesched_jobs where job_type = 'not-polling'");

      assertThat(bikesched.node().name()).isEqualTo("node-registers-later");
      assertThat(bikesched.node().id()).isEmpty();
      assertThat(bikesched.node().isPolling()).isFalse();
      assertThat(countRows("bikesched_nodes", "name = 'node-registers-later'")).isZero();

      Node node = bikesched.startPolling();

      assertThat(node).isSameAs(bikesched.node());
      assertThat(node.isPolling()).isTrue();
      UUID id = node.id().orElseThrow();
      assertThat(nodeName(id)).isEqualTo("node-registers-later");
      OffsetDateTime registered = lastHeartbeat(id);
      await()
          .untilAsserted(
              () ->
                  assertThat(lastHeartbeat(id)).as("heartbeats while polling").isAfter(registered));
    }
  }

  @Test
  void closingANodeThatNeverPolledDeregistersNothing() {
    var logs = LogCapture.of(bikesched.core.internal.DefaultBikesched.class);
    try (logs) {
      Bikesched.builder()
          .dataSource(dataSource)
          .nodeName("node-never-polled")
          .autoStartPolling(false)
          .build()
          .close();
    }
    assertThat(logs.messages())
        .contains(
            "bikesched node node-never-polled created; it registers, sends heartbeats and polls"
                + " for jobs once startPolling() is called",
            "bikesched node node-never-polled closed (it never polled, so it was not registered)");
  }

  @Test
  void startPollingFailsIfALiveNodeHasTheName() {
    try (Bikesched polling =
            Bikesched.builder().dataSource(dataSource).nodeName("node-taken").build();
        Bikesched idle =
            Bikesched.builder()
                .dataSource(dataSource)
                .nodeName("node-taken")
                .autoStartPolling(false)
                .build()) {
      assertThatThrownBy(idle::startPolling)
          .isInstanceOf(BikeschedException.class)
          .hasMessage(
              "A live bikesched node named 'node-taken' is already registered; node names must"
                  + " be unique");
      assertThat(idle.node().isPolling()).isFalse();
      assertThat(idle.node().id()).isEmpty();
      assertThat(polling.node().isPolling()).isTrue();
    }
  }

  @Test
  void rejectsSecondLiveNodeWithSameName() {
    try (Bikesched first =
        Bikesched.builder().dataSource(dataSource).nodeName("node-dup").build()) {
      assertThatThrownBy(
              () -> Bikesched.builder().dataSource(dataSource).nodeName("node-dup").build())
          .isInstanceOf(BikeschedException.class)
          .hasMessageContaining("live bikesched node named 'node-dup' is already registered");
      assertThat(first.node().name()).isEqualTo("node-dup");
    }
  }

  @Test
  void takesOverStaleNodeWithSameName() throws SQLException {
    Bikesched.builder()
        .dataSource(dataSource)
        .nodeName("bootstrap")
        .build()
        .close(); // ensure schema
    UUID staleId = UUID.randomUUID();
    try (var connection = dataSource.getConnection();
        var insert =
            connection.prepareStatement(
                """
                        insert into bikesched_nodes (id, name, last_heartbeat_at)
                        values (?, 'node-restarted', now() - interval '1 hour')""")) {
      insert.setObject(1, staleId);
      insert.executeUpdate();
    }
    // A job the crashed previous instance was still running (scheduled in the future so the
    // new instance's poller doesn't immediately execute it after the release).
    execute(
        "insert into bikesched_jobs (id, job_type, state, locked_by, scheduled_at) values "
            + "(gen_random_uuid(), 'orphan-restart', 'RUNNING', '"
            + staleId
            + "', now() + interval '1 hour')");

    try (Bikesched bikesched =
        Bikesched.builder().dataSource(dataSource).nodeName("node-restarted").build()) {
      assertThat(bikesched.node().id().orElseThrow()).isEqualTo(staleId);
      assertThat(count("job_type = 'orphan-restart' and state = 'SCHEDULED' and locked_by is null"))
          .as("jobs left running by the previous instance are released on startup")
          .isEqualTo(1);
    }
    execute("delete from bikesched_jobs where job_type = 'orphan-restart'");
  }

  @Test
  void heartbeatReleasesJobsOfDeadNodesAndThenRemovesThem() throws Exception {
    var logs = LogCapture.of(Heartbeat.class);
    UUID deadNode = UUID.randomUUID();
    try (logs;
        Bikesched bikesched =
            Bikesched.builder()
                .dataSource(dataSource)
                .nodeName("node-reaper")
                .heartbeatInterval(Duration.ofMillis(50))
                .nodeTimeout(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(50))
                .build()) {
      bikesched.registerJobHandler("orphan", () -> {});
      execute(
          "insert into bikesched_nodes (id, name, last_heartbeat_at) "
              + "values ('"
              + deadNode
              + "', 'node-dead', now() - interval '1 minute')");
      execute(
          "insert into bikesched_jobs (id, job_type, state, locked_by) select gen_random_uuid(), "
              + "'orphan', 'RUNNING', '"
              + deadNode
              + "' from generate_series(1, 2)");

      // Released by the heartbeat, then claimed and executed by this node's poller; the dead
      // node itself is removed once its jobs no longer reference it.
      await()
          .untilAsserted(
              () -> {
                assertThat(count("job_type = 'orphan' and state = 'SUCCEEDED'")).isEqualTo(2);
                assertThat(nodeName(deadNode)).isNull();
              });
    }

    List<String> messages = logs.messages();
    int released =
        indexOfMatch(
            messages,
            "Released 2 running job\\(s\\) of dead bikesched node node-dead \\(.*\\): no heartbeat for PT1S");
    int removed =
        indexOfMatch(
            messages,
            "Removed 1 dead bikesched node\\(s\\) without heartbeat for more than PT2S: "
                + "node-dead \\("
                + deadNode
                + ", last heartbeat .*\\)");
    assertThat(released).as("jobs released").isNotNegative();
    assertThat(removed).as("node removed after its jobs were released").isGreaterThan(released);
  }

  private static int indexOfMatch(List<String> messages, String regex) {
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i).matches(regex)) {
        return i;
      }
    }
    return -1;
  }
}
