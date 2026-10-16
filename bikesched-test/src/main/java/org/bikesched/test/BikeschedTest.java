// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedBuilder;

/**
 * Testing support for applications using bikesched: a node factory with sensible test defaults and
 * wait helpers for job outcomes, see {@code docs/integrations/testing.md}.
 */
public final class BikeschedTest {

  /** The poll interval and {@link JobAwait} poll interval used by this module: 50 ms. */
  static final Duration POLL_INTERVAL = Duration.ofMillis(50);

  /** The default {@link JobAwait} timeout: 10 seconds. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private BikeschedTest() {}

  /**
   * Like {@link #testNode(DataSource, String)} with the prefix {@code "test"}.
   *
   * @param dataSource the application's test database
   * @return a builder for a test node
   */
  public static BikeschedBuilder testNode(DataSource dataSource) {
    return testNode(dataSource, "test");
  }

  /**
   * Returns a builder for a test node on {@code dataSource}: a node name unique to this call
   * ({@code <nodeNamePrefix>-<random UUID>}, so tests run in parallel without clashing) and a poll
   * interval of 50 ms, so a job scheduled "now" is claimed promptly. Every other option (JSON
   * mapper, heartbeat interval, concurrency, listeners, …) is the caller's; this module never
   * starts, resets or migrates the database itself.
   *
   * @param dataSource the application's test database
   * @param nodeNamePrefix the prefix of the generated node name
   * @return a builder for a test node
   */
  public static BikeschedBuilder testNode(DataSource dataSource, String nodeNamePrefix) {
    return Bikesched.builder()
        .dataSource(dataSource)
        .nodeName(nodeNamePrefix + "-" + UUID.randomUUID())
        .pollInterval(POLL_INTERVAL);
  }

  /**
   * Returns a {@link JobAwait} for waiting on the outcome of a job.
   *
   * @param bikesched the node to read the job from
   * @param jobId the job id
   * @return a new {@link JobAwait}, with the default timeout of 10 seconds
   */
  public static JobAwait awaitJob(Bikesched bikesched, UUID jobId) {
    return new JobAwait(bikesched, jobId);
  }
}
