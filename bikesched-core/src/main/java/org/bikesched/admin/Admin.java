// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only lookups for operating bikesched, see {@link org.bikesched.Bikesched#admin()}. Every
 * method reads in its own read-only transaction; none changes anything (retrying and cancelling
 * stay on {@link org.bikesched.Bikesched}).
 *
 * <pre>{@code
 * Admin admin = bikesched.admin();
 * admin.findJobs(JobQuery.newest(50).withStates(JobState.FAILED))
 *     .forEach(job -> log.warn("{} {} failed: {}", job.type(), job.id(), job.lastError()));
 * }</pre>
 *
 * <p>Payloads and results are only read by {@link #findJobData(UUID)} and {@link
 * #findEventPayload(UUID)}, so callers that must not see them never load them. Every method throws
 * a {@link org.bikesched.BikeschedException} if the database cannot be accessed.
 */
public interface Admin {

  /**
   * Finds jobs matching the query, newest first (by id: job ids are time-ordered).
   *
   * @param query the filters, the page size and the position after the previous page
   * @return at most {@link JobQuery#limit()} jobs
   */
  List<JobSummary> findJobs(JobQuery query);

  /**
   * Finds the jobs whose id ends with the given hex digits, newest first, e.g. to look up the short
   * id the admin UI shows: the last 8 hex digits, the random part of the time-ordered id.
   *
   * @param idSuffix at least 8 and at most 32 hex digits at the end of the id; case, hyphens and
   *     surrounding whitespace are ignored, so a full id matches too
   * @param limit the maximum number of jobs, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the matching jobs
   * @throws IllegalArgumentException if the suffix has fewer than 8 or more than 32 hex digits or
   *     other characters
   */
  List<JobSummary> findJobsByIdSuffix(String idSuffix, int limit);

  /**
   * Looks up one job.
   *
   * @param jobId the job id
   * @return the job; empty if there is no such job (e.g. deleted after the job retention)
   */
  Optional<JobSummary> findJob(UUID jobId);

  /**
   * Looks up the job of a type with an idempotency key, the one scheduling with that key returns
   * instead of a new job.
   *
   * @param type the job type
   * @param idempotencyKey the idempotency key
   * @return the job; empty if no job of the type has the key
   */
  Optional<JobSummary> findJob(String type, String idempotencyKey);

  /**
   * Reads a job's payload and result as JSON text.
   *
   * @param jobId the job id
   * @return the payload and result; empty if there is no such job
   */
  Optional<JobData> findJobData(UUID jobId);

  /**
   * Reads a job's steps in the order they started.
   *
   * @param jobId the job id
   * @return the steps; empty if the job has none or does not exist
   */
  List<StepInfo> findSteps(UUID jobId);

  /**
   * Looks up one step by its id, see {@link StepInfo#id()}.
   *
   * @param stepId the step id
   * @return the step; empty if there is no such step (e.g. its job was deleted)
   */
  Optional<StepInfo> findStep(UUID stepId);

  /**
   * Reads a step's result as JSON text: what a step returned, the payload an event wait received,
   * the ready value of a poll or the result of a child job.
   *
   * @param stepId the step id
   * @return the result ({@code null} JSON text if the step has none, e.g. it has not succeeded);
   *     empty if there is no such step
   */
  Optional<String> findStepResult(UUID stepId);

  /**
   * Counts the jobs of every type in the table by state. Types this node has a handler for are
   * listed even without any job yet, see {@link JobTypeSummary#handled()}.
   *
   * @return one entry per job type, by type name
   */
  List<JobTypeSummary> findJobTypes();

  /**
   * Reads the runs of a recurring job: its pending occurrence and the latest ended ones. Jobs of
   * the type scheduled without a recurrence are not runs and left out.
   *
   * @param type the job type of the recurring job
   * @param limit the maximum number of ended runs, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the pending run, if any, and at most {@code limit} ended runs, newest first; both empty
   *     if the type has no recurring runs
   */
  RecurringJobRuns findRecurringJobRuns(String type, int limit);

  /**
   * Finds unexpired events matching the query, newest first (by publication time, then id).
   *
   * @param query the filters, the page size and the position after the previous page
   * @return at most {@link EventQuery#limit()} events
   */
  List<EventSummary> findEvents(EventQuery query);

  /**
   * Finds the unexpired events whose id ends with the given hex digits, newest first, e.g. the
   * short id the admin UI shows (the last 8 hex digits).
   *
   * @param idSuffix at least 8 and at most 32 hex digits at the end of the id; case, hyphens and
   *     surrounding whitespace are ignored, so a full id matches too
   * @param limit the maximum number of events, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the matching events
   * @throws IllegalArgumentException if the suffix has fewer than 8 or more than 32 hex digits or
   *     other characters
   */
  List<EventSummary> findEventsByIdSuffix(String idSuffix, int limit);

  /**
   * Looks up one event, also an expired one that was not deleted yet.
   *
   * @param eventId the event id
   * @return the event; empty if there is no such event (e.g. deleted after it expired)
   */
  Optional<EventSummary> findEvent(UUID eventId);

  /**
   * Reads an event's payload as JSON text.
   *
   * @param eventId the event id
   * @return the payload ({@code null} JSON text if the event has none); empty if there is no such
   *     event
   */
  Optional<String> findEventPayload(UUID eventId);

  /**
   * Counts the unexpired events and the waiting steps of every event name: a name that steps wait
   * for but nobody published yet is listed with no events.
   *
   * @return one entry per event name, by name
   */
  List<EventNameSummary> findEventNames();

  /**
   * Finds the steps that published the event (a job's {@code publishEvent} step) or received it (an
   * event wait), in the order they started.
   *
   * @param eventId the event id
   * @return the steps with their jobs; empty if the event was published outside a job and no job
   *     received it yet
   */
  List<EventStep> findEventSteps(UUID eventId);

  /**
   * Finds event waits still waiting for an event of the given name, longest waiting first.
   *
   * @param eventName the event name
   * @param keyPrefix only keys starting with it; {@code null} or empty for every key
   * @param limit the maximum number of steps, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the waiting steps with their jobs
   */
  List<WaitingStep> findWaitingSteps(String eventName, @Nullable String keyPrefix, int limit);

  /**
   * Lists the registered nodes.
   *
   * @return the nodes, by name
   */
  List<NodeInfo> findNodes();

  /**
   * Finds a registered node.
   *
   * @param nodeId the node id
   * @return the node, empty if no node with this id is registered (any more)
   */
  Optional<NodeInfo> findNode(UUID nodeId);
}
