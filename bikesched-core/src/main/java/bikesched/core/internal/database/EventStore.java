package bikesched.core.internal.database;

import bikesched.core.BikeschedException;
import bikesched.core.event.EventWait;
import bikesched.core.event.ReceivedEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code bikesched_events} table and steps waiting for events: waiting for, suspending at and
 * publishing events, each in one transaction holding a transaction-scoped advisory lock per event
 * name and key. Thread-safe; every call borrows its own connection from the {@link DatabaseAccess}.
 */
public final class EventStore {

  private final DatabaseAccess access;

  EventStore(DatabaseAccess access) {
    this.access = Objects.requireNonNull(access, "access");
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
            select payload::text from bikesched_events
             where event_name = ? and event_key = ?
             order by published_at desc limit 1""";

  private static final String RECEIVE_EVENT_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, result, attempts, event_name, event_key, finished_at)
            values (?, ?, 'SUCCEEDED', ?::jsonb, 1, ?, ?, now())
            on conflict (job_id, name) do update
               set state = 'SUCCEEDED', result = excluded.result, finished_at = now()""";

  private static final String WAIT_EVENT_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, event_name, event_key, wait_until)
            values (?, ?, 'WAITING', 1, ?, ?, now() + ? * interval '1 millisecond')
            returning wait_until""";

  private static final String TIME_OUT_EVENT_STEP_SQL =
      """
            update bikesched_steps set state = 'TIMED_OUT', finished_at = now()
             where job_id = ? and name = ? and state = 'WAITING'""";

  // Ends a RUNNING attempt whose handler suspended at a waiting event step: WAITING until the
  // event arrives or the wait's deadline (scheduled_at; 'infinity' without one) has passed. If the
  // event was published since the step started waiting (it is no longer WAITING), the job is
  // scheduled right away, as is a job whose cancellation was requested (to cancel it). Suspending
  // does not use up an attempt.
  private static final String SUSPEND_JOB_SQL =
      """
            update bikesched_jobs j
               set state = case when s.state = 'WAITING' then 'WAITING' else 'SCHEDULED' end,
                   scheduled_at = case when s.state = 'WAITING' and j.cancel_requested_at is null
                                       then coalesce(s.wait_until, 'infinity')
                                       else now() end,
                   attempts = j.attempts - 1, locked_by = null, locked_at = null, updated_at = now()
              from bikesched_steps s
             where j.id = ? and j.locked_by = ? and j.state = 'RUNNING'
               and s.job_id = j.id and s.name = ?
            returning j.state, s.wait_until""";

  private static final String INSERT_EVENT_SQL =
      """
            insert into bikesched_events (id, event_name, event_key, payload) values (?, ?, ?, ?::jsonb)""";

  // Completes all steps waiting for the event with its payload and resumes their WAITING jobs.
  // Jobs that are still RUNNING (suspending right now, or resumed by their deadline) are left
  // alone: SUSPEND_JOB_SQL resp. the handler's wait then find the step completed.
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
                 where j.id = r.job_id and j.state = 'WAITING')
            select job_id, name from received""";

  private static final String DELETE_EVENTS_SQL =
      """
            delete from bikesched_events where published_at < now() - make_interval(secs => ?)""";

  /**
   * The outcome of waiting for an event.
   *
   * @param state {@code SUCCEEDED} (received, see {@code payload}), {@code TIMED_OUT} or {@code
   *     WAITING}
   * @param payload the received event's payload as JSON text, may be {@code null}
   * @param waitUntil the deadline of a {@code WAITING} step, {@code null} if none
   */
  public record EventWait(String state, String payload, Instant waitUntil) {}

  /**
   * A job after suspending it.
   *
   * @param waiting {@code true} if it is {@code WAITING}, {@code false} if it was scheduled right
   *     away because the event arrived meanwhile
   * @param waitUntil the deadline of the wait, {@code null} if none
   */
  public record SuspendedJob(boolean waiting, Instant waitUntil) {}

  /**
   * Waits for an event as a step of a job: returns the step's earlier outcome (received or timed
   * out) if any, receives the latest published event with that name and key, or marks the step
   * {@code WAITING}. Runs in one transaction holding the event's advisory lock.
   *
   * @param jobId the job id
   * @param stepName the step name
   * @param eventName the event name
   * @param eventKey the event key
   * @param timeout how long to wait at most, counted from the first wait; {@code null} for no limit
   * @return the outcome
   * @throws IllegalStateException if the step name belongs to a step waiting for another event or
   *     to a regular step
   */
  public EventWait awaitEvent(
      UUID jobId, String stepName, String eventName, String eventKey, Duration timeout) {
    return access.inTransaction(
        db -> {
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
                  return new EventWait("TIMED_OUT", null, null);
                }
                return new EventWait("WAITING", null, found.waitUntil());
              }
            }
          }
          Optional<PublishedEvent> published =
              db.one(
                  LATEST_EVENT_SQL, row -> new PublishedEvent(row.string(1)), eventName, eventKey);
          if (published.isPresent()) {
            String payload = published.get().payload();
            db.update(RECEIVE_EVENT_STEP_SQL, jobId, stepName, payload, eventName, eventKey);
            return new EventWait("SUCCEEDED", payload, null);
          }
          return db.one(
                  WAIT_EVENT_STEP_SQL,
                  row -> new EventWait("WAITING", null, row.instant(1)),
                  jobId,
                  stepName,
                  eventName,
                  eventKey,
                  Sql.millis(timeout))
              .orElseThrow();
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
   * Ends a running attempt whose handler suspended at a waiting event step, see {@code
   * SUSPEND_JOB_SQL}. Runs in one transaction holding the event's advisory lock.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param stepName the waiting step
   * @param eventName the event the step waits for
   * @param eventKey the event key
   * @return the job after suspending; empty if the job is not running on this node (anymore)
   */
  public Optional<SuspendedJob> suspendJob(
      UUID jobId, UUID nodeId, String stepName, String eventName, String eventKey) {
    return access.inTransaction(
        db -> {
          lockEvent(db, eventName, eventKey);
          return db.one(
              SUSPEND_JOB_SQL,
              row -> new SuspendedJob("WAITING".equals(row.string(1)), row.instant(2)),
              jobId,
              nodeId,
              stepName);
        },
        e -> new BikeschedException("Suspending bikesched job " + jobId + " failed", e));
  }

  /**
   * Publishes an event: stores it and completes all steps waiting for it, resuming their jobs. Runs
   * in bikesched's own transaction holding the event's advisory lock.
   *
   * @param eventName the event name
   * @param eventKey the event key
   * @param payload the payload as JSON text, may be {@code null}
   * @return the steps that received the event
   */
  public List<ReceivedEvent> publishEvent(String eventName, String eventKey, String payload) {
    return access.inTransaction(
        db -> publishEvent(db, eventName, eventKey, payload),
        e ->
            new BikeschedException(
                "Publishing event " + eventName + "/" + eventKey + " failed", e));
  }

  /**
   * Publishes an event as part of the caller's transaction (see {@link #publishEvent(String,
   * String, String)}): the connection is neither committed nor closed, and the event's advisory
   * lock is held until the caller ends the transaction.
   *
   * @param connection the caller's connection, not in auto-commit mode
   * @param eventName the event name
   * @param eventKey the event key
   * @param payload the payload as JSON text, may be {@code null}
   * @return the steps that received the event (visible to others once the caller commits)
   * @throws IllegalArgumentException if the connection is in auto-commit mode
   */
  public List<ReceivedEvent> publishEventInTransaction(
      Connection connection, String eventName, String eventKey, String payload) {
    return access.onCallerConnection(
        connection,
        db -> {
          if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                "Publishing an event on the caller's connection requires a transaction:"
                    + " the connection must not be in auto-commit mode");
          }
          return publishEvent(db, eventName, eventKey, payload);
        },
        e ->
            new BikeschedException(
                "Publishing event " + eventName + "/" + eventKey + " failed", e));
  }

  private static List<ReceivedEvent> publishEvent(
      Sql db, String eventName, String eventKey, String payload) throws SQLException {
    lockEvent(db, eventName, eventKey);
    db.update(INSERT_EVENT_SQL, UUID.randomUUID(), eventName, eventKey, payload);
    return db.list(
        RESUME_WAITING_STEPS_SQL,
        row -> new ReceivedEvent(row.uuid(1), row.string(2)),
        payload,
        eventName,
        eventKey);
  }

  /**
   * Deletes events published longer ago than the retention period.
   *
   * @param retention how long events are kept
   * @return the number of deleted events
   */
  public int deleteEvents(Duration retention) {
    return access.withConnection(
        db -> db.update(DELETE_EVENTS_SQL, Sql.seconds(retention)),
        e -> new BikeschedException("Deleting old bikesched events failed", e));
  }

  private static void lockEvent(Sql db, String eventName, String eventKey) throws SQLException {
    db.execute(LOCK_EVENT_SQL, eventName, eventKey);
  }

  /** An event found in {@code bikesched_events}; its payload may be {@code null}. */
  private record PublishedEvent(String payload) {}

  /** A row of {@code FIND_EVENT_STEP_SQL}: a step (event step or regular step) of a job. */
  private record EventStep(
      String state,
      String result,
      String eventName,
      String eventKey,
      Instant waitUntil,
      boolean deadlinePassed) {}
}
