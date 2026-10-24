// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.util.UuidUtil;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.Recurrence;
import org.bikesched.job.TypedJob;
import org.jspecify.annotations.Nullable;
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
  private final RecurrenceParsers recurrenceParsers;
  private final Runnable beforeDatabaseAccess;

  /**
   * @param database the database
   * @param registry the node's job names
   * @param nodeName the node's name, for logging
   * @param recurrenceParsers the node's recurrence parsers
   * @param beforeDatabaseAccess runs first in every operation; throws if the node is closed
   */
  public JobScheduler(
      Database database,
      JobRegistry registry,
      String nodeName,
      RecurrenceParsers recurrenceParsers,
      Runnable beforeDatabaseAccess) {
    this.database = database;
    this.registry = registry;
    this.nodeName = nodeName;
    this.recurrenceParsers = recurrenceParsers;
    this.beforeDatabaseAccess = beforeDatabaseAccess;
  }

  /**
   * Stores a job built by a {@link JobBuilder}, unless a job of its name has its idempotency key;
   * registers its handler first if given and new.
   *
   * @return the id of the new job, or of the existing job with the idempotency key
   */
  @Override
  public UUID schedule(
      NewJob job,
      @Nullable JobHandler handler,
      @Nullable JobFailureHandler failureHandler,
      @Nullable Connection connection) {
    beforeDatabaseAccess.run();
    String name = job.name();
    requireHandler(name, handler);
    registerFailureHandler(name, failureHandler);
    UUID jobId = UuidUtil.timeOrdered();
    UUID stored =
        connection == null
            ? database.jobs().insertJob(jobId, job)
            : database.jobs().insertJob(connection, jobId, job);
    if (!stored.equals(jobId)) {
      LOG.debug(
          "Job {} named {} has idempotency key {}, not scheduling another one",
          stored,
          name,
          job.idempotencyKey());
      return stored;
    }
    LOG.debug(
        "Scheduled job {} named {} at {} (priority {}, max {} attempt(s), timeout {}){}",
        jobId,
        name,
        job.scheduledAt(),
        job.priority(),
        job.maxAttempts(),
        job.timeout() == null ? "node default" : job.timeout(),
        connection == null ? "" : " in the caller's transaction");
    return jobId;
  }

  /**
   * Remembers the payload class of a typed job name, see {@link JobRegistry#requirePayloadType}.
   *
   * @param typedJob the job name and its payload class
   * @throws IllegalArgumentException if the name is already used with another payload class
   */
  public void requirePayloadType(TypedJob<?> typedJob) {
    registry.requirePayloadType(typedJob);
  }

  @Override
  public Recurrence parseRecurrence(String expression) {
    return recurrenceParsers.parse(expression);
  }

  /** Registers a recurring job, see {@link JobBuilder#schedule(Recurrence)}. */
  @Override
  public UUID scheduleRecurring(
      RecurringJob job,
      @Nullable JobHandler handler,
      @Nullable JobFailureHandler failureHandler,
      @Nullable Instant firstRun) {
    beforeDatabaseAccess.run();
    String name = job.name();
    requireHandler(name, handler);
    registerFailureHandler(name, failureHandler);
    Instant now = Instant.now();
    Instant first = firstRun != null ? firstRun : job.nextRun(now, now);
    var registration = database.jobs().registerRecurringJob(job.occurrence(first));
    registry.addRecurringJob(job);
    String recurrence = job.recurrence().description();
    if (registration.created()) {
      LOG.info(
          "Registered recurring job {} ({}), first occurrence at {}",
          name,
          recurrence,
          registration.scheduledAt());
    } else if (registration.previousRecurrence() != null) {
      LOG.info(
          "Recurrence of recurring job {} changed from '{}' to '{}', next occurrence moved to {}",
          name,
          registration.previousRecurrence(),
          recurrence,
          registration.scheduledAt());
    } else {
      LOG.info(
          "Registered recurring job {} ({}), pending occurrence {} at {}",
          name,
          recurrence,
          registration.state(),
          registration.scheduledAt());
    }
    return registration.jobId();
  }

  /**
   * Removes a recurring job from this node and deletes its pending occurrence.
   *
   * @param name the recurring job's name
   * @return whether a pending occurrence was deleted
   */
  public boolean removeRecurringJob(String name) {
    beforeDatabaseAccess.run();
    boolean registered = registry.removeRecurringJob(name);
    int deleted = database.jobs().deletePendingOccurrences(name);
    LOG.info(
        "Removed recurring job {}{}{}",
        name,
        registered ? " from this node" : "",
        deleted > 0 ? ", deleted its pending occurrence" : ", no pending occurrence to delete");
    return deleted > 0;
  }

  /**
   * Checks that the job name has a handler, registering the given one first if it has none.
   *
   * @param name the job name
   * @param handler the handler set on the builder, {@code null} if none
   * @throws IllegalArgumentException if the name has no handler
   */
  @Override
  public void requireHandler(String name, @Nullable JobHandler handler) {
    if (handler != null) {
      if (registry.register(name, handler)) {
        LOG.info("bikesched node {} registered handler for job name {}", nodeName, name);
      } else {
        LOG.debug("Handler for job name {} already registered, keeping it", name);
      }
    }
    registry.requireHandler(name);
  }

  /** Registers a builder's failure handler, if given, unless the name has one. */
  private void registerFailureHandler(String name, @Nullable JobFailureHandler failureHandler) {
    if (failureHandler == null) {
      return;
    }
    if (registry.registerFailureHandler(name, failureHandler)) {
      LOG.info("bikesched node {} registered failure handler for job name {}", nodeName, name);
    } else {
      LOG.debug("Failure handler for job name {} already registered, keeping it", name);
    }
  }
}
