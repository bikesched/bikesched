// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.UuidUtil;
import org.jspecify.annotations.Nullable;

/**
 * The {@code bikesched_events} table and steps waiting for events: waiting for, suspending at and
 * publishing events, each in one transaction holding a transaction-scoped advisory lock per event
 * name and key. Changes of steps and jobs are recorded in the {@link HistoryStore job history}, if
 * enabled. Thread-safe; every call borrows its own connection from the {@link DatabaseAccess}.
 */
public final class EventStore {

  private final DatabaseAccess access;
  private final HistoryStore history;

  EventStore(DatabaseAccess access, HistoryStore history) {
    this.access = access;
    this.history = history;
  }

  // ---- Events ----
  // Waiting for an event, suspending a waiting job and publishing an event each run in one
  // transaction that first takes a transaction-scoped advisory lock on (event name, event key).
  // This serializes them per event, so neither side can miss the other under READ COMMITTED:
  // without it, a waiter could check for the event while a publisher checks for waiters, both
  // finding nothing before the other commits, and the job would wait forever.
  private static final String LOCK_EVENT_SQL =
      """
            select pg_advisory_xact_lock(
                       hashtextextended('bikesched-event:' || jsonb_build_array(?::text, ?::text)::text, 0))""";

  private static final String FIND_EVENT_STEP_SQL =
      """
            select id, type, state, result::text as result, event_name, event_key, wake_at,
                   wake_at <= now() as deadline_passed, coalesce(last_error, '') as last_error
              from bikesched_steps where job_id = ? and name = ? for update""";

  private static final String LATEST_EVENT_SQL =
      """
            select id, payload::text as payload from bikesched_events
             where name = ? and key = ? and expires_at > now()
             order by published_at desc limit 1""";

  private static final String RECEIVE_EVENT_STEP_SQL =
      """
            insert into bikesched_steps (id, job_id, name, type, state, result, attempts, event_name, event_key, event_id, finished_at)
            values (?, ?, ?, 'EVENT_WAIT', 'SUCCEEDED', ?::jsonb, 1, ?, ?, ?, now())""";

  private static final String WAIT_EVENT_STEP_SQL =
      """
            insert into bikesched_steps (id, job_id, name, type, state, attempts, event_name, event_key, wake_at)
            values (?, ?, ?, 'EVENT_WAIT', 'WAITING', 1, ?, ?, now() + ? * interval '1 millisecond')
            returning wake_at""";

  private static final String TIME_OUT_EVENT_STEP_SQL =
      """
            update bikesched_steps set state = 'TIMED_OUT', finished_at = now()
             where id = ? and state = 'WAITING'""";

  private static final String INSERT_EVENT_SQL =
      """
            insert into bikesched_events (id, name, key, payload, idempotency_key, expires_at)
            values (?, ?, ?, ?::jsonb, ?, now() + ? * interval '1 millisecond')""";

  // An unexpired event published with the same idempotency key (under the event's advisory lock).
  private static final String FIND_KEYED_EVENT_SQL =
      """
            select id from bikesched_events
             where name = ? and key = ? and idempotency_key = ? and expires_at > now()
             limit 1""";

  // Completes all steps waiting for the event with its payload (copied into each step, so steps do
  // not depend on the event, which is deleted after its retention) and its id (a plain reference)
  // and resumes their WAITING jobs.
  // Jobs that are still RUNNING (suspending right now, or resumed by their deadline) are left
  // alone: suspending (StepStore.suspend) resp. the handler's wait then find the step completed.
  // Returns per step whether its job was resumed, and the job's attempt count and type.
  private static final String RESUME_WAITING_STEPS_SQL =
      """
            with received as (
                update bikesched_steps
                   set state = 'SUCCEEDED', result = ?::jsonb, event_id = ?, finished_at = now()
                 where type = 'EVENT_WAIT' and event_name = ? and event_key = ? and state = 'WAITING'
                returning job_id, name),
            resumed as (
                update bikesched_jobs j
                   set state = 'SCHEDULED', scheduled_at = now(), updated_at = now()
                  from received r
                 where j.id = r.job_id and j.state = 'WAITING'
                returning j.id, j.attempts, j.type)
            select r.job_id, r.name, x.id is not null as job_resumed,
                   coalesce(x.attempts, 0) as attempts, x.type
              from received r left join resumed x on x.id = r.job_id""";

  // A batch of expired events, oldest expiry first from the given expires_at on (see
  // DeletedBatch). Skips locked rows, so concurrent clean-ups on several nodes delete different
  // events.
  private static final String DELETE_EVENTS_SQL =
      """
            with cutoff as (select coalesce(?, now()) as at),
            batch as (
                select id from bikesched_events
                 where expires_at >= coalesce(?, '-infinity'::timestamptz)
                   and expires_at <= (select at from cutoff)
                 order by expires_at
                 limit ? for update skip locked),
            deleted as (
                delete from bikesched_events e using batch b where e.id = b.id
                returning e.expires_at)
            select count(*) as count, max(expires_at) as last, (select at from cutoff) as cutoff
              from deleted""";

  // A publishing step records the published event (name, key and id), so that later runs return
  // the same id and can check that they publish the same event.
  private static final String FIND_PUBLISH_STEP_SQL =
      """
            select type, event_name, event_key, event_id
              from bikesched_steps where job_id = ? and name = ?""";

  private static final String INSERT_PUBLISH_STEP_SQL =
      """
            insert into bikesched_steps (id, job_id, name, type, state, attempts, event_name, event_key, event_id, finished_at)
            values (?, ?, ?, 'EVENT_PUBLISH', 'SUCCEEDED', 1, ?, ?, ?, now())""";

  /** The outcome of waiting for an event, by the state of the waiting step. */
  public sealed interface EventWaitResult {

    /**
     * The event was received ({@code SUCCEEDED}), or the step was resolved by its failure handler.
     *
     * @param json the received event's payload as stored with the step; JSON text, may be {@code
     *     null}
     */
    record Received(@Nullable String json) implements EventWaitResult {}

    /**
     * The wait timed out ({@code TIMED_OUT}).
     *
     * @param step the waiting step's id and name
     */
    record TimedOut(StepRef step) implements EventWaitResult {}

    /**
     * Its failure handler has to run again ({@code FAILING}).
     *
     * @param step the waiting step's id and name
     * @param error the stored error, handed to the failure handler
     */
    record Failing(StepRef step, String error) implements EventWaitResult {}

    /** The wait failed after its failure handler ran ({@code FAILED}). */
    record Failed() implements EventWaitResult {}

    /**
     * The step waits for the event ({@code WAITING}).
     *
     * @param step the waiting step's id and name
     * @param waitUntil the deadline of the wait, {@code null} if none
     */
    record Waiting(StepRef step, @Nullable Instant waitUntil) implements EventWaitResult {}
  }

  /**
   * Waits for an event as a step of a job: returns the step's earlier outcome (received, timed out,
   * failing or failed) if any, receives the latest published event with that name and key (its
   * payload is stored with the step), or marks the step {@code WAITING}. Runs in one transaction
   * holding the event's advisory lock.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param stepName the step name
   * @param eventName the event name
   * @param eventKey the event key
   * @param timeout how long to wait at most, counted from the first wait; {@code null} for no limit
   * @return the outcome
   * @throws IllegalStateException if the step name belongs to a step waiting for another event or
   *     to another type of step
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public EventWaitResult awaitEvent(
      UUID jobId,
      JobLock lock,
      String stepName,
      String eventName,
      String eventKey,
      @Nullable Duration timeout) {
    return access.inTransaction(
        db -> {
          StepStore.requireJobLock(db, jobId, lock);
          lockEvent(db, eventName, eventKey);
          Optional<EventStep> step =
              db.one(
                  FIND_EVENT_STEP_SQL,
                  row ->
                      new EventStep(
                          new StepRef(row.uuid("id"), stepName),
                          row.enumValue("type", StepType.class),
                          row.enumValue("state", StepState.class),
                          row.string("result"),
                          row.string("event_name"),
                          row.string("event_key"),
                          row.instant("wake_at"),
                          row.bool("deadline_passed"),
                          row.string("last_error")),
                  jobId,
                  stepName);
          if (step.isPresent()) {
            EventStep found = step.get();
            if (found.type() != StepType.EVENT_WAIT
                || !eventName.equals(found.eventName())
                || !eventKey.equals(found.eventKey())) {
              throw new IllegalStateException(
                  "Step '"
                      + stepName
                      + "' of job "
                      + jobId
                      + (found.type() == StepType.EVENT_WAIT
                          ? " waits for event " + found.eventName() + "/" + found.eventKey()
                          : " is " + found.type().description())
                      + ", not for event "
                      + eventName
                      + "/"
                      + eventKey
                      + "; step names must be unique within a job");
            }
            return switch (found.state()) {
              case SUCCEEDED -> new EventWaitResult.Received(found.result());
              case TIMED_OUT -> new EventWaitResult.TimedOut(found.step());
              case FAILING -> new EventWaitResult.Failing(found.step(), found.error());
              case FAILED -> new EventWaitResult.Failed();
              case WAITING, RUNNING, COMPENSATED, CANCELLED, POSTPONED -> {
                if (found.deadlinePassed()) {
                  db.update(TIME_OUT_EVENT_STEP_SQL, found.step().id());
                  history.record(db, Change.step(jobId, stepName, "TIMED_OUT", null, null));
                  yield new EventWaitResult.TimedOut(found.step());
                }
                yield new EventWaitResult.Waiting(found.step(), found.waitUntil());
              }
            };
          }
          Optional<PublishedEvent> published =
              db.one(
                  LATEST_EVENT_SQL,
                  row -> new PublishedEvent(row.uuid("id"), row.string("payload")),
                  eventName,
                  eventKey);
          if (published.isPresent()) {
            String payload = published.get().payload();
            UUID eventId = published.get().id();
            db.update(
                RECEIVE_EVENT_STEP_SQL,
                UuidUtil.timeOrdered(),
                jobId,
                stepName,
                payload,
                eventName,
                eventKey,
                eventId);
            history.record(
                db,
                Change.step(
                    jobId,
                    stepName,
                    "SUCCEEDED",
                    null,
                    "received event " + eventId + " right away"));
            return new EventWaitResult.Received(payload);
          }
          StepRef created = new StepRef(UuidUtil.timeOrdered(), stepName);
          EventWaitResult.Waiting waiting =
              db.one(
                      WAIT_EVENT_STEP_SQL,
                      row -> new EventWaitResult.Waiting(created, row.instant("wake_at")),
                      created.id(),
                      jobId,
                      stepName,
                      eventName,
                      eventKey,
                      Sql.millis(timeout))
                  .orElseThrow();
          history.record(
              db,
              Change.step(
                  jobId,
                  stepName,
                  "WAITING",
                  null,
                  "waiting for event "
                      + eventName
                      + "/"
                      + eventKey
                      + (waiting.waitUntil() == null ? "" : " until " + waiting.waitUntil())));
          return waiting;
        },
        e ->
            new BikeschedException(
                "Waiting for event "
                    + eventName
                    + "/"
                    + eventKey
                    + " in step '"
                    + stepName
                    + "' of job "
                    + jobId
                    + " failed",
                e));
  }

  /**
   * Ends a running attempt whose handler suspended at a waiting event step, see {@link
   * StepStore#suspendJob}. Runs in one transaction holding the event's advisory lock.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param step the waiting step
   * @param eventName the event the step waits for
   * @param eventKey the event key
   * @return the job after suspending; empty if the job is not running on this node (anymore)
   */
  public Optional<StepStore.SuspendedJob> suspendJob(
      UUID jobId, JobLock lock, StepRef step, String eventName, String eventKey) {
    return access.inTransaction(
        db -> {
          lockEvent(db, eventName, eventKey);
          return StepStore.suspend(db, history, jobId, lock, step, null);
        },
        e -> new BikeschedException("Suspending bikesched job " + jobId + " failed", e));
  }

  /**
   * Publishes an event: stores it and completes all steps waiting for it with its payload, resuming
   * their jobs. Runs in bikesched's own transaction holding the event's advisory lock.
   *
   * @param eventName the event name
   * @param eventKey the event key
   * @param payload the payload as JSON text, may be {@code null}
   * @param retention how long steps starting to wait later still receive it (and it is kept); may
   *     be zero
   * @param idempotencyKey while an event with the same name, key and idempotency key is unexpired,
   *     it is returned instead of publishing again; {@code null} for none
   * @return the published event, or the earlier one with the idempotency key
   */
  public Publication publishEvent(
      String eventName,
      String eventKey,
      @Nullable String payload,
      Duration retention,
      @Nullable String idempotencyKey) {
    return access.inTransaction(
        db -> publish(db, eventName, eventKey, payload, retention, idempotencyKey),
        e ->
            new BikeschedException(
                "Publishing event " + eventName + "/" + eventKey + " failed", e));
  }

  /**
   * Publishes an event as part of the caller's transaction (see {@link #publishEvent(String,
   * String, String, Duration, String)}): the connection is neither committed nor closed, and the
   * event's advisory lock is held until the caller ends the transaction.
   *
   * @param connection the caller's connection, not in auto-commit mode
   * @param eventName the event name
   * @param eventKey the event key
   * @param payload the payload as JSON text, may be {@code null}
   * @param retention how long steps starting to wait later still receive it; may be zero
   * @param idempotencyKey see {@link #publishEvent(String, String, String, Duration, String)}
   * @return the published event (visible to others once the caller commits), or the earlier one
   *     with the idempotency key
   * @throws IllegalArgumentException if the connection is in auto-commit mode
   */
  public Publication publishEventInTransaction(
      Connection connection,
      String eventName,
      String eventKey,
      @Nullable String payload,
      Duration retention,
      @Nullable String idempotencyKey) {
    return access.onCallerConnection(
        connection,
        db -> {
          if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                "Publishing an event on the caller's connection requires a transaction:"
                    + " the connection must not be in auto-commit mode");
          }
          return publish(db, eventName, eventKey, payload, retention, idempotencyKey);
        },
        e ->
            new BikeschedException(
                "Publishing event " + eventName + "/" + eventKey + " failed", e));
  }

  /**
   * Publishes an event exactly once as a durable step of a job: the first run publishes it and
   * records the step {@code SUCCEEDED} with the event's id as its result in the same transaction,
   * fenced by the job lock; later runs find the step and return the stored id without publishing.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param stepName the publishing step
   * @param eventName the event name
   * @param eventKey the event key
   * @param payload the payload as JSON text, may be {@code null}
   * @param retention how long steps starting to wait later still receive it; may be zero
   * @param compensating whether the job replays for compensation: then the step must exist
   * @return the id of the published event, the same on every run, and the publication if this run
   *     published it
   * @throws IllegalStateException if the step name belongs to another step or to a publishing step
   *     that has not succeeded, or a compensation replay finds no publishing step
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public StepPublication publishEventAsStep(
      UUID jobId,
      JobLock lock,
      String stepName,
      String eventName,
      String eventKey,
      @Nullable String payload,
      Duration retention,
      boolean compensating) {
    return access.inTransaction(
        db -> {
          StepStore.requireJobLock(db, jobId, lock);
          Optional<PublishStep> previous =
              db.one(
                  FIND_PUBLISH_STEP_SQL,
                  row ->
                      new PublishStep(
                          row.enumValue("type", StepType.class),
                          row.string("event_name"),
                          row.string("event_key"),
                          row.uuid("event_id")),
                  jobId,
                  stepName);
          if (previous.isPresent()) {
            return new StepPublication(
                previous.get().eventId(jobId, stepName, eventName, eventKey), null);
          }
          if (compensating) {
            throw new IllegalStateException(
                "Compensation replay has no succeeded publishing step '" + stepName + "'");
          }
          Publication publication = publish(db, eventName, eventKey, payload, retention, null);
          db.update(
              INSERT_PUBLISH_STEP_SQL,
              UuidUtil.timeOrdered(),
              jobId,
              stepName,
              eventName,
              eventKey,
              publication.eventId());
          history.record(
              db,
              Change.step(
                  jobId,
                  stepName,
                  "SUCCEEDED",
                  lock.nodeId(),
                  "published event "
                      + publication.eventId()
                      + " "
                      + eventName
                      + "/"
                      + eventKey
                      + " to "
                      + publication.received()
                      + " step(s)"));
          return new StepPublication(publication.eventId(), publication);
        },
        e ->
            new BikeschedException(
                "Publishing event " + eventName + "/" + eventKey + " for job " + jobId + " failed",
                e));
  }

  /** A row of {@code FIND_PUBLISH_STEP_SQL}: a step (of any type) of a job. */
  // Publishing steps are created SUCCEEDED and never change: they are not compensable, and
  // cancelling a job only cancels WAITING and RUNNING steps.
  private record PublishStep(StepType type, String eventName, String eventKey, UUID eventId) {

    /**
     * The id of the event this publishing step published, after checking that it published the same
     * event as the current run.
     */
    UUID eventId(UUID jobId, String stepName, String expectedName, String expectedKey) {
      if (type != StepType.EVENT_PUBLISH
          || !expectedName.equals(eventName)
          || !expectedKey.equals(eventKey)) {
        throw new IllegalStateException(
            "Step '"
                + stepName
                + "' of job "
                + jobId
                + (type == StepType.EVENT_PUBLISH
                    ? " published event " + eventName + "/" + eventKey
                    : " is " + type.description())
                + ", not event "
                + expectedName
                + "/"
                + expectedKey
                + "; step names must be unique within a job");
      }
      return eventId;
    }
  }

  private Publication publish(
      Sql db,
      String eventName,
      String eventKey,
      @Nullable String payload,
      Duration retention,
      @Nullable String idempotencyKey)
      throws SQLException {
    lockEvent(db, eventName, eventKey);
    if (idempotencyKey != null) {
      Optional<UUID> earlier =
          db.one(FIND_KEYED_EVENT_SQL, row -> row.uuid("id"), eventName, eventKey, idempotencyKey);
      if (earlier.isPresent()) {
        return new Publication(earlier.get(), 0, true);
      }
    }
    UUID eventId = UuidUtil.timeOrdered();
    db.update(
        INSERT_EVENT_SQL,
        eventId,
        eventName,
        eventKey,
        payload,
        idempotencyKey,
        retention.toMillis());
    List<Resumption> resumptions =
        db.list(
            RESUME_WAITING_STEPS_SQL,
            row ->
                new Resumption(
                    row.uuid("job_id"),
                    row.string("name"),
                    row.bool("job_resumed"),
                    row.integer("attempts"),
                    row.string("type")),
            payload,
            eventId,
            eventName,
            eventKey);
    List<Change> changes = new ArrayList<>();
    for (Resumption resumption : resumptions) {
      changes.add(
          Change.step(
              resumption.jobId(),
              resumption.stepName(),
              "SUCCEEDED",
              null,
              "received event " + eventId));
      if (resumption.jobResumed()) {
        db.dueNow(resumption.type());
        changes.add(
            Change.job(
                resumption.jobId(),
                "SCHEDULED",
                resumption.attempts(),
                null,
                "resumed by event " + eventId));
      }
    }
    history.record(db, changes);
    return new Publication(eventId, resumptions.size(), false);
  }

  /**
   * A published event.
   *
   * @param eventId the id of the stored event
   * @param received the number of waiting steps that received it
   * @param duplicate whether an unexpired event had the idempotency key: then nothing was published
   *     and {@code eventId} is that event's id
   */
  public record Publication(UUID eventId, int received, boolean duplicate) {}

  /**
   * The event of a publishing step.
   *
   * @param eventId the id of the step's event
   * @param publication the publication if this run published the event, {@code null} if an earlier
   *     run did
   */
  public record StepPublication(UUID eventId, @Nullable Publication publication) {}

  /**
   * Deletes up to {@code limit} expired events (their retention has passed), oldest expiry first.
   *
   * @param after only events expired at or after this time, {@code null} for all
   * @param cutoff only events expired at or before this time, {@code null} for now
   * @param limit the maximum number of events to delete
   * @return the deleted batch
   */
  public DeletedBatch deleteExpiredEvents(
      @Nullable Instant after, @Nullable Instant cutoff, int limit) {
    return access.withConnection(
        db ->
            db.one(
                    DELETE_EVENTS_SQL,
                    DeletedBatch.MAPPER,
                    Sql.timestamp(cutoff),
                    Sql.timestamp(after),
                    limit)
                .orElseThrow(),
        e -> new BikeschedException("Deleting old bikesched events failed", e));
  }

  private static void lockEvent(Sql db, String eventName, String eventKey) throws SQLException {
    db.execute(LOCK_EVENT_SQL, eventName, eventKey);
  }

  /** An event found in {@code bikesched_events}; its payload may be {@code null}. */
  private record PublishedEvent(UUID id, @Nullable String payload) {}

  /** A row of {@code RESUME_WAITING_STEPS_SQL}: a step that received the event. */
  private record Resumption(
      UUID jobId, String stepName, boolean jobResumed, int attempts, String type) {}

  /** A row of {@code FIND_EVENT_STEP_SQL}: a step (of any type) of a job. */
  private record EventStep(
      StepRef step,
      StepType type,
      StepState state,
      String result,
      String eventName,
      String eventKey,
      Instant waitUntil,
      boolean deadlinePassed,
      String error) {}
}
