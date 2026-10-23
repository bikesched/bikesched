// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedBuilder;
import org.bikesched.job.JobType;

/**
 * Testing support for applications using bikesched: a node factory with sensible test defaults,
 * wait helpers for job outcomes and time skipping for pending jobs, see {@code
 * docs/integrations/testing.md}.
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

  /**
   * Skips a pending job ahead to its next due time, so it runs now: a sleep ends, a poll checks
   * again, a retry starts, a recurring occurrence runs, and an event wait, a wait for a child job
   * or a poll whose deadline is that due time times out. All of the job's stored times (its own and
   * those of its waiting steps) move back by the same amount, so later deadlines keep their
   * distance. Listening nodes are notified, so the job is claimed at once.
   *
   * @param dataSource the database of the job
   * @param jobId the job id
   * @return {@code false} (and nothing changed) if the job is running, has ended, does not exist or
   *     waits without a due time (an event or child job without a timeout)
   */
  public static boolean fastForward(DataSource dataSource, UUID jobId) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(jobId, "jobId");
    return TimeSkip.fastForward(dataSource, jobId);
  }

  /**
   * Advances the time of the pending jobs of the given types by {@code duration}, as if that much
   * time had passed: their stored times (scheduled runs, retries, recurring occurrences, sleeps,
   * polls and deadlines of their waiting steps) move back by {@code duration}, so whatever was due
   * within it runs now and the rest keeps waiting, closer by {@code duration}. Running and ended
   * jobs, events and the clean-up of old rows are not affected. Listening nodes are notified, so
   * the jobs now due are claimed at once.
   *
   * @param dataSource the database of the jobs
   * @param duration how far to advance, not negative
   * @param type a job type
   * @param moreTypes further job types
   * @return the number of jobs moved
   */
  public static int advanceTime(
      DataSource dataSource, Duration duration, String type, String... moreTypes) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(duration, "duration");
    var types = new ArrayList<String>();
    types.add(Objects.requireNonNull(type, "type"));
    for (String moreType : moreTypes) {
      types.add(Objects.requireNonNull(moreType, "moreTypes"));
    }
    return TimeSkip.advance(dataSource, duration, types);
  }

  /**
   * Like {@link #advanceTime(DataSource, Duration, String, String...)} with the names of typed job
   * types.
   *
   * @param dataSource the database of the jobs
   * @param duration how far to advance, not negative
   * @param type a job type
   * @param moreTypes further job types
   * @return the number of jobs moved
   */
  public static int advanceTime(
      DataSource dataSource, Duration duration, JobType<?> type, JobType<?>... moreTypes) {
    Objects.requireNonNull(type, "type");
    List<String> more = new ArrayList<>();
    for (JobType<?> moreType : moreTypes) {
      more.add(Objects.requireNonNull(moreType, "moreTypes").name());
    }
    return advanceTime(dataSource, duration, type.name(), more.toArray(String[]::new));
  }
}
