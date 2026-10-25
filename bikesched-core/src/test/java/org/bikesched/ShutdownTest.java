// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bikesched.job.Job;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Closing a node concurrently and on JVM shutdown ({@code closeOnShutdown}). */
class ShutdownTest extends AbstractBikeschedTest {

  @Test
  void closeWaitsForACloseInProgress() throws Exception {
    String name = "shutdown-concurrent-" + UUID.randomUUID();
    Bikesched bikesched = testNode(name).build();
    bikesched.registerJobHandler(name, (Job job) -> Thread.sleep(1000));
    UUID nodeId = bikesched.node().id();
    UUID jobId = bikesched.newJob(name).schedule();
    awaitState(jobId, "RUNNING");

    CompletableFuture<Void> first = CompletableFuture.runAsync(bikesched::close);
    await().until(() -> !bikesched.node().isPolling());
    bikesched.close(); // the second call, while the first awaits the running job

    assertThat(countRows("bikesched_nodes", "id = '" + nodeId + "'")).isZero();
    first.get(30, TimeUnit.SECONDS);
  }

  @Test
  void interruptedCloseStillWaitsForACloseInProgress() throws Exception {
    String name = "shutdown-interrupted-" + UUID.randomUUID();
    Bikesched bikesched = testNode(name).build();
    bikesched.registerJobHandler(name, (Job job) -> Thread.sleep(1000));
    UUID nodeId = bikesched.node().id();
    UUID jobId = bikesched.newJob(name).schedule();
    awaitState(jobId, "RUNNING");

    CompletableFuture<Void> first = CompletableFuture.runAsync(bikesched::close);
    await().until(() -> !bikesched.node().isPolling());
    Thread.currentThread().interrupt();
    bikesched.close();

    assertThat(Thread.interrupted()).as("interrupt restored").isTrue();
    assertThat(countRows("bikesched_nodes", "id = '" + nodeId + "'")).isZero();
    first.get(30, TimeUnit.SECONDS);
  }

  @Test
  void closeRemovesTheShutdownHook() throws Exception {
    Bikesched bikesched =
        testNode("shutdown-hook-" + UUID.randomUUID()).closeOnShutdown(true).build();
    Thread hook = shutdownHook(bikesched);

    bikesched.close();

    assertThat(Runtime.getRuntime().removeShutdownHook(hook)).as("hook still registered").isFalse();
  }

  @Test
  void noShutdownHookByDefault() throws Exception {
    try (Bikesched bikesched = testNode("shutdown-none-" + UUID.randomUUID()).build()) {
      assertThat(shutdownHook(bikesched)).isNull();
    }
  }

  @Test
  void shutdownHookClosesTheNodeWhenTheJvmIsTerminated() throws Exception {
    String name = "shutdown-jvm-" + UUID.randomUUID();
    Path output = Files.createTempFile("bikesched-shutdown", ".log");
    Process process =
        new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                "-cp",
                testClassPath(),
                NodeWithShutdownHook.class.getName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                name)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      UUID nodeId = await().until(() -> nodeId(output), id -> id != null);
      assertThat(nodeName(nodeId)).isEqualTo(name);

      process.destroy(); // SIGTERM, which runs the shutdown hooks

      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("process ended").isTrue();
      assertThat(countRows("bikesched_nodes", "id = '" + nodeId + "'"))
          .as("node deregistered")
          .isZero();
    } finally {
      process.destroyForcibly();
      Files.delete(output);
    }
  }

  /** The node id the process printed, {@code null} before it did. */
  private static @Nullable UUID nodeId(Path output) throws IOException {
    try (var lines = Files.lines(output)) {
      return lines
          .filter(line -> line.startsWith(NodeWithShutdownHook.NODE_ID))
          .map(line -> UUID.fromString(line.substring(NodeWithShutdownHook.NODE_ID.length())))
          .findFirst()
          .orElse(null);
    }
  }

  private static String testClassPath() {
    List<String> entries = new ArrayList<>();
    for (String property : List.of("java.class.path", "jdk.module.path")) {
      String value = System.getProperty(property);
      if (value != null && !value.isBlank()) {
        entries.add(value);
      }
    }
    return String.join(File.pathSeparator, entries);
  }

  private static Thread shutdownHook(Bikesched bikesched) throws ReflectiveOperationException {
    var field = bikesched.getClass().getDeclaredField("shutdownHook");
    field.setAccessible(true);
    return (Thread) field.get(bikesched);
  }

  /** A node closed by nothing but its shutdown hook, run by {@link ShutdownTest} in its own JVM. */
  static final class NodeWithShutdownHook {

    static final String NODE_ID = "node id: ";

    private NodeWithShutdownHook() {}

    public static void main(String[] args) throws InterruptedException {
      var dataSource = new org.postgresql.ds.PGSimpleDataSource();
      dataSource.setUrl(args[0]);
      dataSource.setUser(args[1]);
      dataSource.setPassword(args[2]);
      Bikesched bikesched =
          Bikesched.builder(dataSource)
              .nodeName(args[3])
              .pollInterval(Duration.ofMillis(50))
              .closeOnShutdown(true)
              .build();
      System.out.println(NODE_ID + bikesched.node().id());
      System.out.flush();
      Thread.sleep(Duration.ofMinutes(1));
    }
  }
}
