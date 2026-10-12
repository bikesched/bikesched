// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
   * Finds jobs matching the query, newest first (by creation time, then id).
   *
   * @param query the filters, the page size and the position after the previous page
   * @return at most {@link JobQuery#limit()} jobs
   */
  List<JobSummary> findJobs(JobQuery query);

  /**
   * Finds the jobs whose id starts with the given hex digits, newest first, e.g. to look up the
   * short id a log line or an alert shows.
   *
   * @param idPrefix at least 8 and at most 32 hex digits of the id; case, hyphens and surrounding
   *     whitespace are ignored, so a full id matches too
   * @param limit the maximum number of jobs, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the matching jobs
   * @throws IllegalArgumentException if the prefix has fewer than 8 or more than 32 hex digits or
   *     other characters
   */
  List<JobSummary> findJobsByIdPrefix(String idPrefix, int limit);

  /**
   * Looks up one job.
   *
   * @param jobId the job id
   * @return the job; empty if there is no such job (e.g. deleted after the job retention)
   */
  Optional<JobSummary> findJob(UUID jobId);

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
   * Counts the jobs of every type in the table by state.
   *
   * @return one entry per job type, by type name
   */
  List<JobTypeSummary> findJobTypes();

  /**
   * Finds unexpired events matching the query, newest first (by publication time, then id).
   *
   * @param query the filters, the page size and the position after the previous page
   * @return at most {@link EventQuery#limit()} events
   */
  List<EventSummary> findEvents(EventQuery query);

  /**
   * Finds the unexpired events whose id starts with the given hex digits, newest first.
   *
   * @param idPrefix at least 8 and at most 32 hex digits of the id; case, hyphens and surrounding
   *     whitespace are ignored, so a full id matches too
   * @param limit the maximum number of events, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the matching events
   * @throws IllegalArgumentException if the prefix has fewer than 8 or more than 32 hex digits or
   *     other characters
   */
  List<EventSummary> findEventsByIdPrefix(String idPrefix, int limit);

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
   * Counts the unexpired events of every name.
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
  List<WaitingStep> findWaitingSteps(String eventName, String keyPrefix, int limit);

  /**
   * Lists the registered nodes.
   *
   * @return the nodes, by name
   */
  List<NodeInfo> findNodes();
}
