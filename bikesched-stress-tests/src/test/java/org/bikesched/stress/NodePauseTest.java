// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bikesched.Bikesched;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * A node stops dead in the middle of a step, like in a long garbage collection pause or a frozen
 * VM, and continues later: the node runs in a JVM of its own ({@link PausableNode}), paused with
 * {@code kill -STOP} and resumed with {@code kill -CONT}.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "pauses the node with kill -STOP")
class NodePauseTest {

  private static StressDatabase database;

  @BeforeAll
  static void startDatabase() throws Exception {
    database = new StressDatabase();
    database.execute(PausableNode.EFFECTS_TABLE);
  }

  @AfterAll
  static void stopDatabase() {
    database.close();
  }

  @Test
  void aShortPauseChangesNothing() throws Exception {
    String type = "short-pause-" + UUID.randomUUID();
    try (var paused = PausedProcess.start(type, "solo-" + UUID.randomUUID())) {
      UUID jobId;
      try (Bikesched client = client()) {
        jobId = client.newJob(type).handler(_ -> {}).schedule(); // never runs: not polling
      }
      awaitEffect(jobId, "two-started");

      paused.pause();
      Thread.sleep(StressNodes.NODE_TIMEOUT.dividedBy(2).toMillis());
      paused.resume();

      await("job succeeded")
          .atMost(Duration.ofSeconds(30))
          .until(() -> database.state(jobId).equals("SUCCEEDED"));
      assertThat(database.effects(jobId)).containsExactly("one", "two-started", "two-ended");
    }
  }

  @Test
  void anotherNodeTakesOverTheJobsOfANodePausedPastItsTimeout() throws Exception {
    String type = "long-pause-" + UUID.randomUUID();
    String soloType = "solo-" + UUID.randomUUID();
    try (var paused = PausedProcess.start(type, soloType);
        Bikesched other =
            StressNodes.node(database.dataSource(), "other").autoStartPolling(false).build()) {
      other.registerJobHandler(
          type, PausableNode.handler(database.dataSource(), "other", Duration.ZERO));
      UUID jobId = other.newJob(type).schedule();
      awaitEffect(jobId, "two-started");
      other.start(); // only now: the paused node has the job

      paused.pause();
      // Past the node timeout, the other node releases the job and runs it; past twice the
      // timeout, it removes the paused node.
      await("the other node ran the job")
          .atMost(Duration.ofSeconds(30))
          .until(() -> other.findJob(jobId).orElseThrow().state() == JobState.SUCCEEDED);
      Thread.sleep(StressNodes.NODE_TIMEOUT.multipliedBy(2).plusSeconds(2).toMillis());
      assertThat(
              database.queryLong("select count(*) from bikesched_nodes where id = " + paused.id()))
          .as("the paused node was removed")
          .isZero();
      paused.resume();

      // The paused node finishes step two, but cannot store it: the job is no longer its claim.
      await("the paused node's step two ended")
          .atMost(Duration.ofSeconds(30))
          .until(() -> database.effects(jobId).contains("two-ended"));
      Thread.sleep(2_000);
      assertThat(database.effects(jobId))
          .as("step one ran once, step two on both nodes")
          .containsExactly("one", "two-started", "two-started", "two-ended", "two-ended");
      assertThat(database.state(jobId)).isEqualTo("SUCCEEDED");

      // The resumed node works again: it runs a job of a type only it handles.
      UUID soloJob;
      try (Bikesched client = client()) {
        soloJob = client.newJob(soloType).handler(_ -> {}).schedule();
      }
      await("the resumed node runs jobs again")
          .atMost(Duration.ofSeconds(30))
          .until(() -> database.state(soloJob).equals("SUCCEEDED"));
    }
  }

  private static Bikesched client() {
    return StressNodes.node(database.dataSource(), "client").autoStartPolling(false).build();
  }

  private static void awaitEffect(UUID jobId, String step) {
    await(step + " of job " + jobId)
        .atMost(Duration.ofSeconds(30))
        .until(() -> database.effects(jobId).contains(step));
  }

  /** A {@link PausableNode} running in its own JVM, with its output in {@code build/}. */
  private record PausedProcess(Process process, UUID nodeId) implements AutoCloseable {

    static PausedProcess start(String type, String soloType) throws IOException {
      TcpProxy proxy = database.newProxy();
      Path log = Files.createDirectories(Path.of("build", "stress-logs")).resolve(type + ".log");
      String java = ProcessHandle.current().info().command().orElseThrow();
      Process process =
          new ProcessBuilder(
                  java,
                  "-cp",
                  System.getProperty("java.class.path"),
                  PausableNode.class.getName(),
                  database.jdbcUrl(proxy),
                  database.username(),
                  database.password(),
                  "paused",
                  type,
                  soloType)
              .redirectErrorStream(true)
              .start();
      // The first line tells the node id; everything else goes to the log file.
      var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
      UUID nodeId = null;
      List<String> lines = new ArrayList<>();
      for (String line; (line = output.readLine()) != null; ) {
        lines.add(line);
        if (line.startsWith("started ")) {
          nodeId = UUID.fromString(line.substring("started ".length()));
          break;
        }
      }
      if (nodeId == null) {
        throw new IllegalStateException("node did not start:\n" + String.join("\n", lines));
      }
      Files.write(log, lines);
      Thread.ofVirtual().start(() -> copy(output, log.toFile()));
      return new PausedProcess(process, nodeId);
    }

    String id() {
      return "'" + nodeId + "'";
    }

    void pause() throws Exception {
      signal("STOP");
    }

    void resume() throws Exception {
      signal("CONT");
    }

    private void signal(String signal) throws Exception {
      int exit =
          new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid()))
              .inheritIO()
              .start()
              .waitFor();
      assertThat(exit).as("kill -" + signal).isZero();
    }

    @Override
    public void close() throws IOException {
      process.getOutputStream().close(); // the node closes itself
      try {
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }

    private static void copy(BufferedReader output, File log) {
      try (var writer = Files.newBufferedWriter(log.toPath(), StandardOpenOption.APPEND)) {
        for (String line; (line = output.readLine()) != null; ) {
          writer.write(line);
          writer.newLine();
          writer.flush();
        }
      } catch (IOException e) {
        // the process ended
      }
    }
  }
}
