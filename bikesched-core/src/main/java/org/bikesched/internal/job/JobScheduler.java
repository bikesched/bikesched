// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.NewJob;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobHandler;
import org.bikesched.job.Recurrence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the jobs built by the job builders and registers recurring jobs. Every operation starts
 * with {@code beforeDatabaseAccess}, which checks that the node is open and migrates the schema on
 * first use.
 */
public final class JobScheduler implements BaseJobBuilder.Scheduler {

  private static final Logger LOG = LoggerFactory.getLogger(JobScheduler.class);

  private final Database database;
  private final JobRegistry registry;
  private final String nodeName;
  private final Runnable beforeDatabaseAccess;

  /**
   * @param database the database
   * @param registry the node's job types
   * @param nodeName the node's name, for logging
   * @param beforeDatabaseAccess runs first in every operation; throws if the node is closed
   */
  public JobScheduler(
      Database database, JobRegistry registry, String nodeName, Runnable beforeDatabaseAccess) {
    this.database = Objects.requireNonNull(database, "database");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.beforeDatabaseAccess =
        Objects.requireNonNull(beforeDatabaseAccess, "beforeDatabaseAccess");
  }

  /** Stores a job built by a {@link JobBuilder}; registers its handler first if given and new. */
  @Override
  public UUID schedule(NewJob job, JobHandler handler, Connection connection) {
    beforeDatabaseAccess.run();
    String type = job.type();
    requireHandler(type, handler);
    UUID jobId = UUID.randomUUID();
    if (connection == null) {
      database.jobs().insertJob(jobId, job);
    } else {
      database.jobs().insertJob(connection, jobId, job);
    }
    LOG.debug(
        "Scheduled job {} of type {} at {} (priority {}, max {} attempt(s), timeout {}){}",
        jobId,
        type,
        job.scheduledAt(),
        job.priority(),
        job.maxAttempts(),
        job.timeout() == null ? "node default" : job.timeout(),
        connection == null ? "" : " in the caller's transaction");
    return jobId;
  }

  /** Registers a recurring job, see {@link JobBuilder#schedule(Recurrence)}. */
  @Override
  public UUID scheduleRecurring(RecurringJob job, JobHandler handler, Instant firstRun) {
    beforeDatabaseAccess.run();
    String type = job.type();
    requireHandler(type, handler);
    Instant now = Instant.now();
    Instant first = firstRun != null ? firstRun : job.nextRun(now, now);
    var registration = database.jobs().registerRecurringJob(job.occurrence(first));
    registry.addRecurringJob(job);
    String recurrence = job.recurrence().description();
    if (registration.created()) {
      LOG.info(
          "Registered recurring job {} ({}), first occurrence at {}",
          type,
          recurrence,
          registration.scheduledAt());
    } else if (registration.previousRecurrence() != null) {
      LOG.info(
          "Recurrence of recurring job {} changed from '{}' to '{}', next occurrence moved to {}",
          type,
          registration.previousRecurrence(),
          recurrence,
          registration.scheduledAt());
    } else {
      LOG.info(
          "Registered recurring job {} ({}), pending occurrence {} at {}",
          type,
          recurrence,
          registration.state(),
          registration.scheduledAt());
    }
    return registration.jobId();
  }

  /**
   * Removes a recurring job from this node and deletes its pending occurrence.
   *
   * @param type the recurring job's type
   * @return whether a pending occurrence was deleted
   */
  public boolean removeRecurringJob(String type) {
    beforeDatabaseAccess.run();
    boolean registered = registry.removeRecurringJob(type);
    int deleted = database.jobs().deletePendingOccurrences(type);
    LOG.info(
        "Removed recurring job {}{}{}",
        type,
        registered ? " from this node" : "",
        deleted > 0 ? ", deleted its pending occurrence" : ", no pending occurrence to delete");
    return deleted > 0;
  }

  /** Checks that the job type has a handler, registering the given one first if it has none. */
  private void requireHandler(String type, JobHandler handler) {
    if (handler != null) {
      if (registry.register(type, handler)) {
        LOG.info("bikesched node {} registered handler for job type {}", nodeName, type);
      } else {
        LOG.debug("Handler for job type {} already registered, keeping it", type);
      }
    }
    registry.requireHandler(type);
  }
}
