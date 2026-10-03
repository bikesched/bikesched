package bikesched.core.internal;

import bikesched.core.BikeschedException;
import bikesched.core.event.EventWait;
import bikesched.core.job.Step;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code bikesched_events} table and steps waiting for events: waiting for, suspending at and
 * publishing events, each in one transaction holding a transaction-scoped advisory lock per event
 * name and key. Thread-safe; every call borrows its own connection from the {@link Database}.
 */
final class EventStore {

  private final Database database;

  EventStore(Database database) {
    this.database = Objects.requireNonNull(database, "database");
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
  // scheduled right away. Suspending does not use up an attempt.
  private static final String SUSPEND_JOB_SQL =
      """
            update bikesched_jobs j
               set state = case when s.state = 'WAITING' then 'WAITING' else 'SCHEDULED' end,
                   scheduled_at = case when s.state = 'WAITING' then coalesce(s.wait_until, 'infinity')
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
                returning job_id, name, attempts),
            resumed as (
                update bikesched_jobs j
                   set state = 'SCHEDULED', scheduled_at = now(), updated_at = now()
                  from received r
                 where j.id = r.job_id and j.state = 'WAITING')
            select job_id, name, attempts from received""";

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
  record EventWait(String state, String payload, Instant waitUntil) {}

  /**
   * A job after suspending it.
   *
   * @param waiting {@code true} if it is {@code WAITING}, {@code false} if it was scheduled right
   *     away because the event arrived meanwhile
   * @param waitUntil the deadline of the wait, {@code null} if none
   */
  record SuspendedJob(boolean waiting, Instant waitUntil) {}

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
  EventWait awaitEvent(
      UUID jobId, String stepName, String eventName, String eventKey, Duration timeout) {
    return database.inTransaction(
        "Waiting for event "
            + eventName
            + "/"
            + eventKey
            + " in step '"
            + stepName
            + "' of job "
            + jobId,
        connection -> {
          lockEvent(connection, eventName, eventKey);
          try (PreparedStatement find = connection.prepareStatement(FIND_EVENT_STEP_SQL)) {
            find.setObject(1, jobId);
            find.setString(2, stepName);
            try (ResultSet rs = find.executeQuery()) {
              if (rs.next()) {
                String state = rs.getString(1);
                if (!eventName.equals(rs.getString(3)) || !eventKey.equals(rs.getString(4))) {
                  throw new IllegalStateException(
                      "Step '"
                          + stepName
                          + "' of job "
                          + jobId
                          + (rs.getString(3) == null
                              ? " is a regular step"
                              : " waits for event " + rs.getString(3) + "/" + rs.getString(4))
                          + ", not for event "
                          + eventName
                          + "/"
                          + eventKey
                          + "; step names must be unique within a job");
                }
                switch (state) {
                  case "SUCCEEDED" -> {
                    return new EventWait(state, rs.getString(2), null);
                  }
                  case "TIMED_OUT" -> {
                    return new EventWait(state, null, null);
                  }
                  default -> { // WAITING
                    Instant waitUntil = Database.toInstant(rs.getObject(5, OffsetDateTime.class));
                    if (rs.getBoolean(6)) {
                      Database.execute(connection, TIME_OUT_EVENT_STEP_SQL, jobId, stepName);
                      return new EventWait("TIMED_OUT", null, null);
                    }
                    return new EventWait("WAITING", null, waitUntil);
                  }
                }
              }
            }
          }
          Optional<PublishedEvent> published = latestEvent(connection, eventName, eventKey);
          if (published.isPresent()) {
            String payload = published.get().payload();
            Database.execute(
                connection, RECEIVE_EVENT_STEP_SQL, jobId, stepName, payload, eventName, eventKey);
            return new EventWait("SUCCEEDED", payload, null);
          }
          try (PreparedStatement wait = connection.prepareStatement(WAIT_EVENT_STEP_SQL)) {
            wait.setObject(1, jobId);
            wait.setString(2, stepName);
            wait.setString(3, eventName);
            wait.setString(4, eventKey);
            wait.setObject(5, timeout == null ? null : timeout.toMillis(), Types.BIGINT);
            try (ResultSet rs = wait.executeQuery()) {
              rs.next();
              return new EventWait(
                  "WAITING", null, Database.toInstant(rs.getObject(1, OffsetDateTime.class)));
            }
          }
        });
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
  Optional<SuspendedJob> suspendJob(
      UUID jobId, UUID nodeId, String stepName, String eventName, String eventKey) {
    return database.inTransaction(
        "Suspending bikesched job " + jobId,
        connection -> {
          lockEvent(connection, eventName, eventKey);
          try (PreparedStatement suspend = connection.prepareStatement(SUSPEND_JOB_SQL)) {
            suspend.setObject(1, jobId);
            suspend.setObject(2, nodeId);
            suspend.setString(3, stepName);
            try (ResultSet rs = suspend.executeQuery()) {
              return rs.next()
                  ? Optional.of(
                      new SuspendedJob(
                          "WAITING".equals(rs.getString(1)),
                          Database.toInstant(rs.getObject(2, OffsetDateTime.class))))
                  : Optional.empty();
            }
          }
        });
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
  List<Step> publishEvent(String eventName, String eventKey, String payload) {
    return database.inTransaction(
        "Publishing event " + eventName + "/" + eventKey,
        connection -> publishEvent(connection, eventName, eventKey, payload));
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
  List<Step> publishEventInTransaction(
      Connection connection, String eventName, String eventKey, String payload) {
    try {
      if (connection.getAutoCommit()) {
        throw new IllegalArgumentException(
            "Publishing an event on the caller's connection requires a transaction:"
                + " the connection must not be in auto-commit mode");
      }
      return publishEvent(connection, eventName, eventKey, payload);
    } catch (SQLException e) {
      throw new BikeschedException("Publishing event " + eventName + "/" + eventKey + " failed", e);
    }
  }

  private static List<Step> publishEvent(
      Connection connection, String eventName, String eventKey, String payload)
      throws SQLException {
    lockEvent(connection, eventName, eventKey);
    Database.execute(connection, INSERT_EVENT_SQL, UUID.randomUUID(), eventName, eventKey, payload);
    try (PreparedStatement resume = connection.prepareStatement(RESUME_WAITING_STEPS_SQL)) {
      resume.setString(1, payload);
      resume.setString(2, eventName);
      resume.setString(3, eventKey);
      List<Step> received = new ArrayList<>();
      try (ResultSet rs = resume.executeQuery()) {
        while (rs.next()) {
          received.add(new Step(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
        }
      }
      return received;
    }
  }

  /**
   * Deletes events published longer ago than the retention period.
   *
   * @param retention how long events are kept
   * @return the number of deleted events
   */
  int deleteEvents(Duration retention) {
    return database.update(
        DELETE_EVENTS_SQL, "Deleting old bikesched events", retention.toMillis() / 1000.0);
  }

  private static void lockEvent(Connection connection, String eventName, String eventKey)
      throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement(LOCK_EVENT_SQL)) {
      lock.setString(1, eventName);
      lock.setString(2, eventKey);
      lock.execute();
    }
  }

  /** An event found in {@code bikesched_events}; its payload may be {@code null}. */
  private record PublishedEvent(String payload) {}

  private static Optional<PublishedEvent> latestEvent(
      Connection connection, String eventName, String eventKey) throws SQLException {
    try (PreparedStatement find = connection.prepareStatement(LATEST_EVENT_SQL)) {
      find.setString(1, eventName);
      find.setString(2, eventKey);
      try (ResultSet rs = find.executeQuery()) {
        return rs.next() ? Optional.of(new PublishedEvent(rs.getString(1))) : Optional.empty();
      }
    }
  }
}
