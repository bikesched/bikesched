// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.job.JobProcessor;
import org.bikesched.internal.util.Retry;
import org.junit.jupiter.api.Test;

/**
 * Recording an outcome that hits a database blip is retried ({@link Retry}); the injected failures
 * hit the job's virtual thread only, so claiming, heartbeats and clean-ups are unaffected.
 */
class OutcomeRecordingRetryTest extends AbstractBikeschedTest {

  @Test
  void completionIsRetriedAfterADatabaseBlip() throws Exception {
    var failures = new BlipInjectingDataSource(dataSource);
    var logs = LogCapture.of(Retry.class);
    try (logs;
        Bikesched bikesched =
            Bikesched.builder()
                .dataSource(failures)
                .nodeName("node-record-retry-" + UUID.randomUUID())
                .build()) {
      failures.failNextJobThreadConnections(1);
      UUID jobId =
          bikesched.newJob("record-retry-" + UUID.randomUUID()).handler(_ -> {}).schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(logs.messages())
          .anyMatch(
              message ->
                  message.startsWith("Completing job " + jobId + " failed, retrying in ")
                      && message.contains("(attempt 1 of 3)"));
      assertThat(failures.stillToFail()).isZero();
    }
  }

  @Test
  void anOutcomeThatCannotBeRecordedStaysRunning() throws Exception {
    var failures = new BlipInjectingDataSource(dataSource);
    var logs = LogCapture.of(JobProcessor.class);
    try (logs;
        Bikesched bikesched =
            Bikesched.builder()
                .dataSource(failures)
                .nodeName("node-record-give-up-" + UUID.randomUUID())
                .build()) {
      failures.failNextJobThreadConnections(3);
      UUID jobId =
          bikesched
              .newJob("record-give-up-" + UUID.randomUUID())
              .maxAttempts(1)
              .handler(_ -> {})
              .schedule();

      Thread.sleep(2000); // the retries are exhausted and give up
      assertThat(bikesched.findJob(jobId).orElseThrow().state())
          .as("no outcome could be recorded")
          .isEqualTo(JobState.RUNNING);
      assertThat(count("id = '" + jobId + "' and state = 'RUNNING'")).isOne();
      assertThat(logs.messages())
          .anyMatch(m -> m.startsWith("Completing job " + jobId + " failed"));
      assertThat(failures.stillToFail()).isZero();
    }
  }

  /**
   * A data source that fails the next connections of virtual job threads (the node's own threads).
   */
  static final class BlipInjectingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger failures = new AtomicInteger();

    BlipInjectingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    /** Fails this many of the next connections requested by a job thread. */
    void failNextJobThreadConnections(int count) {
      failures.set(count);
    }

    int stillToFail() {
      return failures.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
      if (Thread.currentThread().isVirtual() && failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
        throw new SQLException("injected blip");
      }
      return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return delegate.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getLogger("bikesched-test");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
