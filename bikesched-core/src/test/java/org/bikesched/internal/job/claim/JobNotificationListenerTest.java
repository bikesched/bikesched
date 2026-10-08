// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The {@link JobNotificationListener}: connecting, reconnecting, the self-check and closing. */
class JobNotificationListenerTest extends AbstractBikeschedTest {

  private final Map<UUID, Instant> started = new ConcurrentHashMap<>();

  @AfterEach
  void deleteJobs() throws SQLException {
    execute("delete from bikesched_jobs where type like 'listener-%'");
  }

  @Test
  void theListenerReconnectsAfterItsConnectionWasTerminated() throws Exception {
    var logs = LogCapture.of(JobNotificationListener.class);
    try (logs;
        Bikesched worker =
            testNode("node-listener-reconnect").pollInterval(Duration.ofSeconds(1)).build()) {
      await()
          .until(
              () -> logs.messages().stream().anyMatch(m -> m.endsWith(" listening for due jobs")));

      execute(
          "select pg_terminate_backend(pid) from pg_stat_activity"
              + " where query = 'listen bikesched_jobs' and pid <> pg_backend_pid()");

      await()
          .until(
              () ->
                  logs.messages().stream()
                      .anyMatch(m -> m.endsWith("reconnected, listening again for due jobs")));
      assertThat(logs.messages())
          .anyMatch(m -> m.contains("stopped receiving notifications of due jobs, reconnecting"))
          .noneMatch(m -> m.contains("did not receive its own notification"));
      assertThat(worker.node().isPolling()).isTrue();
    }
  }

  @Test
  void startingDoesNotWaitForTheListenersConnection() {
    DataSource slowForTheListener =
        dataSource(
            connection -> connection,
            () -> {
              if (Thread.currentThread().getName().equals("bikesched-listener")) {
                sleep(3000); // like an exhausted pool's connection timeout
                throw new SQLException("Connection is not available, request timed out", "08001");
              }
            });
    var logs = LogCapture.of(JobNotificationListener.class);
    long start = System.nanoTime();
    try (logs;
        Bikesched worker =
            testNode("node-listener-exhausted")
                .dataSource(slowForTheListener)
                .pollInterval(Duration.ofSeconds(1))
                .build()) {
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      assertThat(worker.node().isPolling()).isTrue();

      await()
          .until(
              () ->
                  logs.messages().stream()
                      .anyMatch(
                          m -> m.contains("could not listen for due jobs, retrying in PT1S")));
    }
  }

  @Test
  void theSelfCheckWarnsIfNotificationsDoNotArrive() {
    // Like PgBouncer in transaction mode: the LISTEN does not stick to the connection.
    DataSource notListening =
        dataSource(
            connection ->
                Thread.currentThread().getName().equals("bikesched-listener")
                    ? rewriting(connection, "listen bikesched_jobs", "unlisten *")
                    : connection,
            () -> {});
    var logs = LogCapture.of(JobNotificationListener.class);
    try (logs;
        Bikesched _ = testNode("node-listener-self-check").dataSource(notListening).build()) {
      await()
          .atMost(Duration.ofSeconds(3))
          .until(
              () ->
                  logs.messages().stream()
                      .anyMatch(
                          m -> m.contains("did not receive its own notification within PT1S")));
    }
  }

  @Test
  void theSelfCheckPassesSilently() throws Exception {
    var logs = LogCapture.of(JobNotificationListener.class);
    try (logs;
        Bikesched _ = testNode("node-listener-self-check-ok").build()) {
      await().until(() -> logs.messages().stream().anyMatch(m -> m.endsWith("its self-check")));
      Thread.sleep(1200);
      assertThat(logs.messages()).noneMatch(m -> m.contains("did not receive"));
    }
  }

  @Test
  void connectionsThatAreNoPostgresConnectionsWarnOnce() throws Exception {
    DataSource wrapped =
        dataSource(
            connection ->
                Thread.currentThread().getName().equals("bikesched-listener")
                    ? opaque(connection)
                    : connection,
            () -> {});
    var logs = LogCapture.of(JobNotificationListener.class);
    try (logs;
        Bikesched worker =
            testNode("node-listener-opaque")
                .dataSource(wrapped)
                .pollInterval(Duration.ofMillis(200))
                .build()) {
      worker.registerJobHandler("listener-opaque", job -> started.put(job.id(), Instant.now()));
      await()
          .until(
              () ->
                  logs.messages().stream()
                      .anyMatch(
                          m -> m.contains("cannot listen for due jobs, it polls every PT0.2S")));

      UUID jobId = worker.newJob("listener-opaque").schedule(Duration.ofMillis(100));
      await().until(() -> started.containsKey(jobId));
      Thread.sleep(500);
      assertThat(logs.messages()).filteredOn(m -> m.contains("cannot listen")).hasSize(1);
    }
  }

  @Test
  void notificationsOfTypesTheNodeDoesNotHandleAreIgnored() {
    var logs = LogCapture.of(JobNotificationListener.class);
    try (logs;
        Bikesched worker =
            testNode("node-listener-foreign").pollInterval(Duration.ofMinutes(1)).build();
        Bikesched client =
            testNode("node-listener-foreign-client").autoStartPolling(false).build()) {
      worker.registerJobHandler("listener-handled", job -> started.put(job.id(), Instant.now()));
      client.registerJobHandler("listener-foreign", _ -> {});
      client.registerJobHandler("listener-handled", _ -> {});

      client.newJob("listener-foreign").schedule();
      await().until(() -> logs.messages().stream().anyMatch(m -> m.endsWith("[listener-foreign]")));

      UUID handled = client.newJob("listener-handled").schedule();
      await()
          .until(
              () ->
                  logs.messages().stream()
                      .anyMatch(m -> m.endsWith("[listener-handled], polling")));
      await().atMost(Duration.ofSeconds(2)).until(() -> started.containsKey(handled));
    }
  }

  @Test
  void closingIsPrompt() {
    var logs = LogCapture.of(JobNotificationListener.class);
    Bikesched worker = testNode("node-listener-close").pollInterval(Duration.ofMinutes(1)).build();
    await()
        .until(() -> logs.messages().stream().anyMatch(m -> m.endsWith(" listening for due jobs")));
    logs.close();

    long start = System.nanoTime();
    worker.close();

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    assertThat(Thread.getAllStackTraces().keySet())
        .noneMatch(t -> t.getName().equals("bikesched-listener"));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @FunctionalInterface
  private interface ConnectionCheck {
    void check() throws SQLException;
  }

  /** The test data source: runs the check before, and the wrapper on, each connection. */
  private static DataSource dataSource(UnaryOperator<Connection> wrapper, ConnectionCheck check) {
    return (DataSource)
        Proxy.newProxyInstance(
            JobNotificationListenerTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (_, method, args) -> {
              if (method.getName().equals("getConnection")) {
                check.check();
                return wrapper.apply((Connection) invoke(method, dataSource, args));
              }
              return invoke(method, dataSource, args);
            });
  }

  /** A connection running {@code replacement} instead of the statement {@code sql}. */
  private static Connection rewriting(Connection connection, String sql, String replacement) {
    return proxy(
        connection,
        (method, args) -> {
          if (method.getName().equals("prepareStatement") && sql.equals(args[0])) {
            args[0] = replacement;
          }
          return invoke(method, connection, args);
        });
  }

  /** A connection that does not unwrap to the PostgreSQL connection. */
  private static Connection opaque(Connection connection) {
    return proxy(
        connection,
        (method, args) ->
            method.getName().equals("isWrapperFor") ? false : invoke(method, connection, args));
  }

  @FunctionalInterface
  private interface Handler {
    Object handle(java.lang.reflect.Method method, Object[] args) throws Throwable;
  }

  private static Connection proxy(Connection connection, Handler handler) {
    return (Connection)
        Proxy.newProxyInstance(
            JobNotificationListenerTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (_, method, args) -> handler.handle(method, args));
  }

  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
