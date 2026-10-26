// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.history.HistoryEntry;
import org.bikesched.job.TypedJob;
import org.jspecify.annotations.Nullable;

/**
 * Lookups and actions for operating bikesched, see {@link org.bikesched.Bikesched#admin()}. Every
 * lookup reads in its own read-only transaction. The actions ({@link #cancelJob(UUID)}, {@link
 * #retryJob(UUID)}, {@link #removeRecurringJob(String)}) do what the methods of {@link
 * org.bikesched.Bikesched} do and can name the user acting, e.g. the one signed in to an admin UI,
 * on the history entry they record.
 *
 * <pre>{@code
 * Admin admin = bikesched.admin();
 * admin.findJobs(JobQuery.newest(50).withStates(JobState.FAILED))
 *     .forEach(job -> log.warn("{} {} failed: {}", job.name(), job.id(), job.lastError()));
 * admin.retryJob(jobId).actor(Actor.user("simon")).retry();
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
   * Looks up the job of a name with an idempotency key, the one scheduling with that key returns
   * instead of a new job.
   *
   * @param name the job name
   * @param idempotencyKey the idempotency key
   * @return the job; empty if no job of the name has the key
   */
  Optional<JobSummary> findJob(String name, String idempotencyKey);

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
   * Counts the jobs of every name in the table by state. Names this node has a handler for are
   * listed even without any job yet, see {@link JobNameSummary#handled()}.
   *
   * @return one entry per job name, by name
   */
  List<JobNameSummary> findJobNames();

  /**
   * Reads the runs of a recurring job: its pending occurrence and the latest ended ones. Jobs of
   * the name scheduled without a recurrence are not runs and left out.
   *
   * @param name the job name of the recurring job
   * @param limit the maximum number of ended runs, between 1 and {@link JobQuery#MAX_LIMIT}
   * @return the pending run, if any, and at most {@code limit} ended runs, newest first; both empty
   *     if the name has no recurring runs
   */
  RecurringJobRuns findRecurringJobRuns(String name, int limit);

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

  /**
   * Reads the recorded history of a node: its {@link org.bikesched.history.NodeEntry} entries
   * ({@code REGISTERED}, {@code STOPPED}, {@code REMOVED}) and the entries it caused (its jobs
   * released after it died), oldest first. Also for a node that is no longer registered, until the
   * history's retention.
   *
   * @param nodeId the node id
   * @return the node's history; empty if nothing was recorded (or it was deleted)
   */
  List<HistoryEntry> findNodeHistory(UUID nodeId);

  /**
   * Finds history entries of every subject matching the query, newest first (by {@link
   * HistoryEntry#createdAt()}, then {@link HistoryEntry#id()}), with the names of the nodes that
   * wrote them. Reads the entries of every node, also if this instance records none ({@link
   * #isRecordingHistory()}).
   *
   * <p>The time bounds, the position, the cause and {@link org.bikesched.history.ActorType#USER}
   * follow indexes. The other filters (entry type, actions, other actor types) are applied to at
   * most {@value HistoryQuery#SCAN_LIMIT} entries per page: a page can then hold fewer entries than
   * its limit and still have a {@linkplain HistoryPage#next() next one}.
   *
   * @param query the filters, the page size and the position after the previous page
   * @return the page
   */
  HistoryPage findHistory(HistoryQuery query);

  /**
   * Tells whether this instance records the history ({@link
   * org.bikesched.BikeschedBuilder#history(java.time.Duration)}); other nodes may differ.
   *
   * @return {@code true} if the history is enabled here
   */
  boolean isRecordingHistory();

  /**
   * Starts cancelling a job, see {@link org.bikesched.Bikesched#cancel(UUID, String)}.
   *
   * @param jobId the job id
   * @return a builder for the message and the user, ending in {@link CancelJobBuilder#cancel()}
   */
  CancelJobBuilder cancelJob(UUID jobId);

  /**
   * Starts retrying a failed job, see {@link org.bikesched.Bikesched#retry(UUID)}.
   *
   * @param jobId the job id
   * @return a builder for the user, ending in {@link RetryJobBuilder#retry()}
   */
  RetryJobBuilder retryJob(UUID jobId);

  /**
   * Starts removing a recurring job, see {@link
   * org.bikesched.Bikesched#removeRecurringJob(String)}.
   *
   * @param name the job name of the recurring job
   * @return a builder for the user, ending in {@link RemoveRecurringJobBuilder#remove()}
   * @throws IllegalArgumentException if the name is blank or too long
   */
  RemoveRecurringJobBuilder removeRecurringJob(String name);

  /**
   * Starts removing a recurring job of a {@link TypedJob}, see {@link #removeRecurringJob(String)}.
   *
   * @param typedJob the job's name and payload class of the recurring job
   * @return a builder for the user, ending in {@link RemoveRecurringJobBuilder#remove()}
   */
  default RemoveRecurringJobBuilder removeRecurringJob(TypedJob<?> typedJob) {
    return removeRecurringJob(typedJob.name());
  }
}
