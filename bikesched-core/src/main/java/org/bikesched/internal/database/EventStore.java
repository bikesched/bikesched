// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.event.EventWait;
import org.bikesched.event.ReceivedEvent;
import org.bikesched.internal.database.HistoryStore.Change;

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
    this.access = Objects.requireNonNull(access, "access");
    this.history = Objects.requireNonNull(history, "history");
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
            select state, result::text, event_name, event_key, wait_until, wait_until <= now()
              from bikesched_steps where job_id = ? and name = ? for update""";

  private static final String LATEST_EVENT_SQL =
      """
            select id, payload::text from bikesched_events
             where name = ? and key = ? and expires_at > now()
             order by published_at desc limit 1""";

  private static final String RECEIVE_EVENT_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, result, attempts, event_name, event_key, finished_at)
            values (?, ?, 'SUCCEEDED', ?::jsonb, 1, ?, ?, now())""";

  private static final String WAIT_EVENT_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, event_name, event_key, wait_until)
            values (?, ?, 'WAITING', 1, ?, ?, now() + ? * interval '1 millisecond')
            returning wait_until""";

  private static final String TIME_OUT_EVENT_STEP_SQL =
      """
            update bikesched_steps set state = 'TIMED_OUT', finished_at = now()
             where job_id = ? and name = ? and state = 'WAITING'""";

  private static final String INSERT_EVENT_SQL =
      """
            insert into bikesched_events (id, name, key, payload, expires_at)
            values (?, ?, ?, ?::jsonb, now() + ? * interval '1 millisecond')""";

  // Completes all steps waiting for the event with its payload (copied into each step, so steps do
  // not depend on the event, which is deleted after its retention) and resumes their WAITING jobs.
  // Jobs that are still RUNNING (suspending right now, or resumed by their deadline) are left
  // alone: suspending (StepStore.suspend) resp. the handler's wait then find the step completed.
  // Returns per step whether its job was resumed, and the job's attempt count.
  private static final String RESUME_WAITING_STEPS_SQL =
      """
            with received as (
                update bikesched_steps
                   set state = 'SUCCEEDED', result = ?::jsonb, finished_at = now()
                 where event_name = ? and event_key = ? and state = 'WAITING'
                returning job_id, name),
            resumed as (
                update bikesched_jobs j
                   set state = 'SCHEDULED', scheduled_at = now(), updated_at = now()
                  from received r
                 where j.id = r.job_id and j.state = 'WAITING'
                returning j.id, j.attempts)
            select r.job_id, r.name, x.id is not null, coalesce(x.attempts, 0)
              from received r left join resumed x on x.id = r.job_id""";

  private static final String DELETE_EVENTS_SQL =
      """
            delete from bikesched_events where expires_at <= now()""";

  /**
   * The outcome of waiting for an event.
   *
   * @param state {@code SUCCEEDED} (received, see {@code json}), {@code TIMED_OUT} or {@code
   *     WAITING}
   * @param json the received event's payload as stored with the step; JSON text, may be {@code
   *     null}
   * @param waitUntil the deadline of a {@code WAITING} step, {@code null} if none
   */
  public record EventWait(String state, String json, Instant waitUntil) {}

  /**
   * Waits for an event as a step of a job: returns the step's earlier outcome (received or timed
   * out) if any, receives the latest published event with that name and key (its payload is stored
   * with the step), or marks the step {@code WAITING}. Runs in one transaction holding the event's
   * advisory lock.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param stepName the step name
   * @param eventName the event name
   * @param eventKey the event key
   * @param timeout how long to wait at most, counted from the first wait; {@code null} for no limit
   * @return the outcome
   * @throws IllegalStateException if the step name belongs to a step waiting for another event or
   *     to a regular step
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public EventWait awaitEvent(
      UUID jobId,
      JobLock lock,
      String stepName,
      String eventName,
      String eventKey,
      Duration timeout) {
    return access.inTransaction(
        db -> {
          StepStore.requireJobLock(db, jobId, lock);
          lockEvent(db, eventName, eventKey);
          Optional<EventStep> step =
              db.one(
                  FIND_EVENT_STEP_SQL,
                  row ->
                      new EventStep(
                          row.string(1),
                          row.string(2),
                          row.string(3),
                          row.string(4),
                          row.instant(5),
                          row.bool(6)),
                  jobId,
                  stepName);
          if (step.isPresent()) {
            EventStep found = step.get();
            if (!eventName.equals(found.eventName()) || !eventKey.equals(found.eventKey())) {
              throw new IllegalStateException(
                  "Step '"
                      + stepName
                      + "' of job "
                      + jobId
                      + (found.eventName() == null
                          ? " is a regular step"
                          : " waits for event " + found.eventName() + "/" + found.eventKey())
                      + ", not for event "
                      + eventName
                      + "/"
                      + eventKey
                      + "; step names must be unique within a job");
            }
            switch (found.state()) {
              case "SUCCEEDED" -> {
                return new EventWait(found.state(), found.result(), null);
              }
              case "TIMED_OUT" -> {
                return new EventWait(found.state(), null, null);
              }
              default -> { // WAITING
                if (found.deadlinePassed()) {
                  db.update(TIME_OUT_EVENT_STEP_SQL, jobId, stepName);
                  history.record(db, Change.step(jobId, stepName, "TIMED_OUT", null, null));
                  return new EventWait("TIMED_OUT", null, null);
                }
                return new EventWait("WAITING", null, found.waitUntil());
              }
            }
          }
          Optional<PublishedEvent> published =
              db.one(
                  LATEST_EVENT_SQL,
                  row -> new PublishedEvent(row.uuid(1), row.string(2)),
                  eventName,
                  eventKey);
          if (published.isPresent()) {
            String payload = published.get().payload();
            UUID eventId = published.get().id();
            db.update(RECEIVE_EVENT_STEP_SQL, jobId, stepName, payload, eventName, eventKey);
            history.record(
                db,
                Change.step(
                    jobId,
                    stepName,
                    "SUCCEEDED",
                    null,
                    "received event " + eventId + " right away"));
            return new EventWait("SUCCEEDED", payload, null);
          }
          EventWait waiting =
              db.one(
                      WAIT_EVENT_STEP_SQL,
                      row -> new EventWait("WAITING", null, row.instant(1)),
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
   * @param stepName the waiting step
   * @param eventName the event the step waits for
   * @param eventKey the event key
   * @return the job after suspending; empty if the job is not running on this node (anymore)
   */
  public Optional<StepStore.SuspendedJob> suspendJob(
      UUID jobId, JobLock lock, String stepName, String eventName, String eventKey) {
    return access.inTransaction(
        db -> {
          lockEvent(db, eventName, eventKey);
          return StepStore.suspend(db, history, jobId, lock, stepName);
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
   * @return the steps that received the event
   */
  public List<ReceivedEvent> publishEvent(
      String eventName, String eventKey, String payload, Duration retention) {
    return access.inTransaction(
        db -> publish(db, eventName, eventKey, payload, retention),
        e ->
            new BikeschedException(
                "Publishing event " + eventName + "/" + eventKey + " failed", e));
  }

  /**
   * Publishes an event as part of the caller's transaction (see {@link #publishEvent(String,
   * String, String, Duration)}): the connection is neither committed nor closed, and the event's
   * advisory lock is held until the caller ends the transaction.
   *
   * @param connection the caller's connection, not in auto-commit mode
   * @param eventName the event name
   * @param eventKey the event key
   * @param payload the payload as JSON text, may be {@code null}
   * @param retention how long steps starting to wait later still receive it; may be zero
   * @return the steps that received the event (visible to others once the caller commits)
   * @throws IllegalArgumentException if the connection is in auto-commit mode
   */
  public List<ReceivedEvent> publishEventInTransaction(
      Connection connection,
      String eventName,
      String eventKey,
      String payload,
      Duration retention) {
    return access.onCallerConnection(
        connection,
        db -> {
          if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                "Publishing an event on the caller's connection requires a transaction:"
                    + " the connection must not be in auto-commit mode");
          }
          return publish(db, eventName, eventKey, payload, retention);
        },
        e ->
            new BikeschedException(
                "Publishing event " + eventName + "/" + eventKey + " failed", e));
  }

  private List<ReceivedEvent> publish(
      Sql db, String eventName, String eventKey, String payload, Duration retention)
      throws SQLException {
    Objects.requireNonNull(retention, "retention");
    lockEvent(db, eventName, eventKey);
    UUID eventId = UUID.randomUUID();
    db.update(INSERT_EVENT_SQL, eventId, eventName, eventKey, payload, retention.toMillis());
    List<Resumption> resumptions =
        db.list(
            RESUME_WAITING_STEPS_SQL,
            row -> new Resumption(row.uuid(1), row.string(2), row.bool(3), row.integer(4)),
            payload,
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
    return resumptions.stream().map(r -> new ReceivedEvent(r.jobId(), r.stepName())).toList();
  }

  /**
   * Deletes expired events (their retention has passed).
   *
   * @return the number of deleted events
   */
  public int deleteExpiredEvents() {
    return access.withConnection(
        db -> db.update(DELETE_EVENTS_SQL),
        e -> new BikeschedException("Deleting old bikesched events failed", e));
  }

  private static void lockEvent(Sql db, String eventName, String eventKey) throws SQLException {
    db.execute(LOCK_EVENT_SQL, eventName, eventKey);
  }

  /** An event found in {@code bikesched_events}; its payload may be {@code null}. */
  private record PublishedEvent(UUID id, String payload) {}

  /** A row of {@code RESUME_WAITING_STEPS_SQL}: a step that received the event. */
  private record Resumption(UUID jobId, String stepName, boolean jobResumed, int attempts) {}

  /** A row of {@code FIND_EVENT_STEP_SQL}: a step (event step or regular step) of a job. */
  private record EventStep(
      String state,
      String result,
      String eventName,
      String eventKey,
      Instant waitUntil,
      boolean deadlinePassed) {}
}
