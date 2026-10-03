package bikesched.core;

import bikesched.core.schema.SchemaMigrator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Encapsulates all database access of bikesched: schema migration and the SQL statements on the
 * bikesched tables. Thread-safe; every call borrows its own connection from the data source.
 *
 * <p>{@link SQLException}s are wrapped in {@link BikeschedException}. Writes are committed
 * explicitly if the data source hands out connections with auto-commit disabled.
 */
final class Database {

  // Inserts the node. If the name is taken by a dead node (stale heartbeat), the restarted node
  // takes over that row and keeps its id; if it is taken by a live node, no row is returned.
  private static final String REGISTER_NODE_SQL =
      """
            insert into bikesched_nodes (id, name) values (?, ?)
            on conflict (name) do update
               set started_at = now(), last_heartbeat_at = now()
             where bikesched_nodes.last_heartbeat_at < now() - make_interval(secs => ?)
            returning id""";

  private static final String HEARTBEAT_SQL =
      "update bikesched_nodes set last_heartbeat_at = now() where id = ?";

  private static final String DEREGISTER_NODE_SQL = "delete from bikesched_nodes where id = ?";

  private static final String INSERT_JOB_SQL =
      """
            insert into bikesched_jobs (id, job_type, payload, scheduled_at, priority, max_attempts, timeout_millis)
            values (?, ?, ?::jsonb, ?, ?, ?, ?)""";

  // Claims due jobs in one statement: rows locked by other nodes' concurrent claims are skipped
  // (instead of waiting), and claimed rows are marked RUNNING for this node before the commit
  // releases the row locks, so no other node can pick them up afterwards.
  private static final String CLAIM_JOBS_SQL =
      """
            update bikesched_jobs
               set state = 'RUNNING', locked_by = ?, locked_at = now(), attempts = attempts + 1, updated_at = now()
             where id in (select id from bikesched_jobs
                           where state = 'SCHEDULED' and scheduled_at <= now()
                           order by priority desc, scheduled_at
                           limit ?
                           for update skip locked)
            returning id, job_type, payload::text, priority, scheduled_at, attempts, max_attempts, timeout_millis""";

  private static final String COMPLETE_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SUCCEEDED', locked_by = null, locked_at = null, finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'""";

  private static final String CANCEL_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'CANCELLED', locked_by = null, locked_at = null, last_error = ?,
                   finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'""";

  // A failed attempt: retried (back to SCHEDULED) while attempts remain, otherwise FAILED. The
  // handler may set a new maximum number of attempts and delay the next attempt (p.*); all
  // expressions see the row's old values, hence the coalesce for the effective maximum.
  private static final String FAIL_JOB_SQL =
      """
            update bikesched_jobs j
               set max_attempts = coalesce(p.max_attempts, j.max_attempts),
                   state = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                then 'FAILED' else 'SCHEDULED' end,
                   finished_at = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                      then now() end,
                   scheduled_at = case when j.attempts < coalesce(p.max_attempts, j.max_attempts)
                                            and p.retry_after_millis is not null
                                       then now() + p.retry_after_millis * interval '1 millisecond'
                                       else j.scheduled_at end,
                   locked_by = null, locked_at = null, last_error = p.error, updated_at = now()
              from (values (?::text, ?::integer, ?::bigint)) as p (error, max_attempts, retry_after_millis)
             where j.id = ? and j.locked_by = ? and j.state = 'RUNNING'
            returning j.state, j.max_attempts, j.scheduled_at""";

  private static final String FIND_STEP_SQL =
      """
            select state, result::text from bikesched_steps where job_id = ? and name = ?""";

  // Starts an attempt of a step that has not succeeded yet; returns its attempt number.
  // Returns no row if the step already succeeded (e.g. concurrently).
  private static final String START_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, started_at)
            values (?, ?, 'RUNNING', 1, now())
            on conflict (job_id, name) do update
               set state = 'RUNNING', attempts = bikesched_steps.attempts + 1, started_at = now(), finished_at = null
             where bikesched_steps.state <> 'SUCCEEDED'
            returning attempts""";

  private static final String COMPLETE_STEP_SQL =
      """
            update bikesched_steps
               set state = 'SUCCEEDED', result = ?::jsonb, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING'""";

  private static final String FAIL_STEP_SQL =
      """
            update bikesched_steps
               set state = 'FAILED', last_error = ?, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING'""";

  private static final String RELEASE_JOBS_SQL =
      """
            update bikesched_jobs
               set state = 'SCHEDULED', locked_by = null, locked_at = null, updated_at = now()
             where locked_by = ? and state = 'RUNNING'""";

  // Releases RUNNING jobs of nodes whose heartbeat is older than the timeout (excluding the
  // calling node) and reports the released job count per dead node. Concurrent callers on other
  // nodes are safe: the second one blocks on the row locks and then no longer matches
  // state = 'RUNNING'.
  private static final String RELEASE_JOBS_OF_DEAD_NODES_SQL =
      """
            with released as (
                update bikesched_jobs j
                   set state = 'SCHEDULED', locked_by = null, locked_at = null, updated_at = now()
                  from bikesched_nodes n
                 where j.locked_by = n.id
                   and j.state = 'RUNNING'
                   and n.id <> ?
                   and n.last_heartbeat_at < now() - make_interval(secs => ?)
                returning n.id as node_id, n.name as node_name)
            select node_id, node_name, count(*) from released group by node_id, node_name""";

  /**
   * Jobs released from a dead node.
   *
   * @param nodeId the dead node
   * @param nodeName its name
   * @param releasedJobs number of jobs put back to {@code SCHEDULED}
   */
  record DeadNodeJobs(UUID nodeId, String nodeName, int releasedJobs) {}

  // Deletes nodes whose heartbeat is older than the given age (excluding the calling node). Nodes
  // still referenced by RUNNING jobs are kept; their jobs are released first by
  // RELEASE_JOBS_OF_DEAD_NODES_SQL. A node re-registering under the same name concurrently
  // refreshes the heartbeat and therefore no longer matches once the row lock is released.
  private static final String DELETE_DEAD_NODES_SQL =
      """
            delete from bikesched_nodes n
             where n.id <> ?
               and n.last_heartbeat_at < now() - make_interval(secs => ?)
               and not exists (select 1 from bikesched_jobs j where j.locked_by = n.id)
            returning n.id, n.name, n.last_heartbeat_at""";

  /**
   * A node removed from {@code bikesched_nodes}.
   *
   * @param nodeId the node id
   * @param nodeName its name
   * @param lastHeartbeatAt its last heartbeat
   */
  record DeletedNode(UUID nodeId, String nodeName, Instant lastHeartbeatAt) {}

  /**
   * A step that completed in an earlier attempt.
   *
   * @param result its stored result as JSON text, may be {@code null}
   */
  record CompletedStep(String result) {}

  private static final Comparator<DefaultJob> CLAIM_ORDER =
      Comparator.comparingInt(DefaultJob::priority)
          .reversed()
          .thenComparing(DefaultJob::scheduledAt);

  private final DataSource dataSource;
  private final JsonMapper jsonMapper;

  /**
   * @param dataSource the PostgreSQL data source
   * @param jsonMapper maps payloads and step results of the {@link Job}s this instance claims
   */
  Database(DataSource dataSource, JsonMapper jsonMapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
  }

  /** The mapper for payloads and step results; handed to the claimed {@link Job}s. */
  JsonMapper jsonMapper() {
    return jsonMapper;
  }

  /**
   * Brings the schema up to date (see {@link SchemaMigrator}).
   *
   * @return the migration versions applied by this call
   */
  List<Integer> migrate() {
    return new SchemaMigrator(dataSource).migrate();
  }

  /**
   * Describes the database for logging, e.g. {@code jdbc:postgresql://localhost:5432/bikesched
   * (PostgreSQL 18.0)}. The URL is {@link #redactUrl(String) redacted}, so it contains no
   * credentials.
   *
   * @return the redacted JDBC URL and the database product and version
   */
  String describe() {
    try (Connection connection = dataSource.getConnection()) {
      var metaData = connection.getMetaData();
      return redactUrl(metaData.getURL())
          + " ("
          + metaData.getDatabaseProductName()
          + " "
          + metaData.getDatabaseProductVersion()
          + ")";
    } catch (SQLException e) {
      throw new BikeschedException("Reading database metadata failed", e);
    }
  }

  /**
   * Removes everything from a JDBC URL that may carry credentials: user info before the host
   * ({@code //user:password@host}) and all properties ({@code ?user=...&password=...}, and {@code
   * ;key=value} style properties of other drivers).
   *
   * @param url the JDBC URL, may be {@code null}
   * @return the URL without credentials, e.g. {@code jdbc:postgresql://localhost:5432/bikesched}
   */
  static String redactUrl(String url) {
    if (url == null) {
      return "<unknown>";
    }
    int properties = url.length();
    for (char separator : new char[] {'?', ';'}) {
      int index = url.indexOf(separator);
      if (index >= 0) {
        properties = Math.min(properties, index);
      }
    }
    return url.substring(0, properties).replaceFirst("//[^/]*@", "//");
  }

  /**
   * Registers a node under a unique name.
   *
   * @param newNodeId id to use if the name is free
   * @param name the unique node name
   * @param deadAfter heartbeat age after which an existing node with this name counts as dead
   * @return the node id: {@code newNodeId}, or the id of the dead node that was taken over; empty
   *     if a live node already holds the name
   */
  Optional<UUID> registerNode(UUID newNodeId, String name, Duration deadAfter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement register = connection.prepareStatement(REGISTER_NODE_SQL)) {
      register.setObject(1, newNodeId);
      register.setString(2, name);
      register.setDouble(3, deadAfter.toMillis() / 1000.0);
      UUID nodeId = null;
      try (ResultSet rs = register.executeQuery()) {
        if (rs.next()) {
          nodeId = rs.getObject(1, UUID.class);
        }
      }
      commitIfNeeded(connection);
      return Optional.ofNullable(nodeId);
    } catch (SQLException e) {
      throw new BikeschedException("Could not register bikesched node '" + name + "'", e);
    }
  }

  /**
   * Updates the heartbeat of a node.
   *
   * @param nodeId the node id
   * @return {@code false} if the node is no longer registered
   */
  boolean heartbeat(UUID nodeId) {
    return update(HEARTBEAT_SQL, "Heartbeat of bikesched node " + nodeId, nodeId) > 0;
  }

  /**
   * Removes a node. Its running jobs must have been released or completed before.
   *
   * @param nodeId the node id
   * @return {@code false} if the node was not registered
   */
  boolean deregisterNode(UUID nodeId) {
    return update(DEREGISTER_NODE_SQL, "Deregistration of bikesched node " + nodeId, nodeId) > 0;
  }

  /**
   * Inserts a new {@code SCHEDULED} job.
   *
   * @param jobId the job id
   * @param job the job's attributes
   */
  void insertJob(UUID jobId, NewJob job) {
    update(
        INSERT_JOB_SQL,
        "Scheduling bikesched job of type '" + job.type() + "'",
        insertParameters(jobId, job));
  }

  /**
   * Inserts a new scheduled job using the caller's connection, as part of the caller's transaction:
   * the connection is neither committed nor closed.
   *
   * @param connection the caller's connection
   * @param jobId the new job's id
   * @param job the job's attributes
   */
  void insertJob(Connection connection, UUID jobId, NewJob job) {
    try {
      execute(connection, INSERT_JOB_SQL, insertParameters(jobId, job));
    } catch (SQLException e) {
      throw new BikeschedException(
          "Scheduling bikesched job of type '" + job.type() + "' failed", e);
    }
  }

  private static Object[] insertParameters(UUID jobId, NewJob job) {
    return new Object[] {
      jobId,
      job.type(),
      job.payload(),
      OffsetDateTime.ofInstant(job.scheduledAt(), ZoneOffset.UTC),
      job.priority(),
      job.maxAttempts(),
      job.timeout() == null ? null : job.timeout().toMillis()
    };
  }

  private static Duration toDuration(Long millis) {
    return millis == null ? null : Duration.ofMillis(millis);
  }

  /**
   * Claims up to {@code limit} due, scheduled jobs for a node using {@code FOR UPDATE SKIP LOCKED}
   * and marks them {@code RUNNING}.
   *
   * @param nodeId the claiming node
   * @param limit maximum number of jobs to claim
   * @return the claimed jobs, highest priority and earliest scheduled first
   */
  List<DefaultJob> claimJobs(UUID nodeId, int limit) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement claim = connection.prepareStatement(CLAIM_JOBS_SQL)) {
      claim.setObject(1, nodeId);
      claim.setInt(2, limit);
      List<DefaultJob> jobs = new ArrayList<>();
      try (ResultSet rs = claim.executeQuery()) {
        while (rs.next()) {
          jobs.add(
              new DefaultJob(
                  this,
                  rs.getObject(1, UUID.class),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getInt(4),
                  rs.getObject(5, OffsetDateTime.class).toInstant(),
                  rs.getInt(6),
                  rs.getInt(7),
                  toDuration(rs.getObject(8, Long.class))));
        }
      }
      commitIfNeeded(connection);
      jobs.sort(CLAIM_ORDER);
      return jobs;
    } catch (SQLException e) {
      throw new BikeschedException("Claiming jobs for bikesched node " + nodeId + " failed", e);
    }
  }

  /**
   * Marks a job claimed by the node as {@code SUCCEEDED}.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @return {@code false} if the job is not running on this node (anymore)
   */
  boolean completeJob(UUID jobId, UUID nodeId) {
    return update(COMPLETE_JOB_SQL, "Completing bikesched job " + jobId, jobId, nodeId) > 0;
  }

  /**
   * Marks a job claimed by the node as {@code CANCELLED}; it is not retried.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param message why the job was cancelled, stored as {@code last_error}; may be {@code null}
   * @return {@code false} if the job is not running on this node (anymore)
   */
  boolean cancelJob(UUID jobId, UUID nodeId, String message) {
    return update(CANCEL_JOB_SQL, "Cancelling bikesched job " + jobId, message, jobId, nodeId) > 0;
  }

  /**
   * Looks up a step that succeeded in an earlier attempt of the job.
   *
   * @param jobId the job id
   * @param name the step name
   * @return the completed step with its result, or empty if the step has not succeeded yet
   */
  Optional<CompletedStep> findCompletedStep(UUID jobId, String name) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement find = connection.prepareStatement(FIND_STEP_SQL)) {
      find.setObject(1, jobId);
      find.setString(2, name);
      try (ResultSet rs = find.executeQuery()) {
        return rs.next() && "SUCCEEDED".equals(rs.getString(1))
            ? Optional.of(new CompletedStep(rs.getString(2)))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw new BikeschedException(
          "Reading step '" + name + "' of bikesched job " + jobId + " failed", e);
    }
  }

  /**
   * Starts an attempt of a step: creates it as {@code RUNNING} or, if an earlier attempt did not
   * succeed, sets it back to {@code RUNNING} and counts the attempt.
   *
   * @param jobId the job id
   * @param name the step name
   * @return the attempt number of the step, or empty if the step has already succeeded
   */
  Optional<Integer> startStep(UUID jobId, String name) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement start = connection.prepareStatement(START_STEP_SQL)) {
      start.setObject(1, jobId);
      start.setString(2, name);
      Integer attempts = null;
      try (ResultSet rs = start.executeQuery()) {
        if (rs.next()) {
          attempts = rs.getInt(1);
        }
      }
      commitIfNeeded(connection);
      return Optional.ofNullable(attempts);
    } catch (SQLException e) {
      throw new BikeschedException(
          "Starting step '" + name + "' of bikesched job " + jobId + " failed", e);
    }
  }

  /**
   * Marks a running step {@code SUCCEEDED} and stores its result.
   *
   * @param jobId the job id
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   */
  void completeStep(UUID jobId, String name, String result) {
    update(
        COMPLETE_STEP_SQL,
        "Storing the result of step '" + name + "' of bikesched job " + jobId,
        result,
        jobId,
        name);
  }

  /**
   * Marks a running step {@code FAILED}.
   *
   * @param jobId the job id
   * @param name the step name
   * @param error description of the failure, stored as {@code last_error}
   */
  void failStep(UUID jobId, String name, String error) {
    update(
        FAIL_STEP_SQL,
        "Recording the failure of step '" + name + "' of bikesched job " + jobId,
        error,
        jobId,
        name);
  }

  /**
   * Records a failed attempt of a job claimed by the node: the job is scheduled again while
   * attempts remain, otherwise it is marked {@code FAILED}.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param error description of the failure, stored as {@code last_error}
   * @param maxAttempts the job's new maximum number of attempts, {@code null} to keep it
   * @param retryAfter delay of the next attempt from now, {@code null} to retry as soon as possible
   * @return the job after recording the failure; empty if the job is not running on this node
   *     (anymore)
   */
  Optional<FailedJob> failJob(
      UUID jobId, UUID nodeId, String error, Integer maxAttempts, Duration retryAfter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement fail = connection.prepareStatement(FAIL_JOB_SQL)) {
      fail.setString(1, error);
      fail.setObject(2, maxAttempts, Types.INTEGER);
      fail.setObject(3, retryAfter == null ? null : retryAfter.toMillis(), Types.BIGINT);
      fail.setObject(4, jobId);
      fail.setObject(5, nodeId);
      FailedJob failed = null;
      try (ResultSet rs = fail.executeQuery()) {
        if (rs.next()) {
          failed =
              new FailedJob(
                  rs.getString(1), rs.getInt(2), rs.getObject(3, OffsetDateTime.class).toInstant());
        }
      }
      commitIfNeeded(connection);
      return Optional.ofNullable(failed);
    } catch (SQLException e) {
      throw new BikeschedException("Recording failure of bikesched job " + jobId + " failed", e);
    }
  }

  /**
   * A job after recording a failed attempt.
   *
   * @param state {@code SCHEDULED} (retried) or {@code FAILED}
   * @param maxAttempts its maximum number of attempts
   * @param scheduledAt when it is retried (only meaningful if {@code SCHEDULED})
   */
  record FailedJob(String state, int maxAttempts, Instant scheduledAt) {

    boolean retried() {
      return state.equals("SCHEDULED");
    }
  }

  /**
   * Puts all jobs still running on the node back to {@code SCHEDULED}, e.g. on shutdown.
   *
   * @param nodeId the node id
   * @return the number of released jobs
   */
  int releaseJobs(UUID nodeId) {
    return update(RELEASE_JOBS_SQL, "Releasing jobs of bikesched node " + nodeId, nodeId);
  }

  /**
   * Puts the {@code RUNNING} jobs of dead nodes (no heartbeat within {@code nodeTimeout}) back to
   * {@code SCHEDULED}.
   *
   * @param callingNodeId the node doing the check; never treated as dead
   * @param nodeTimeout heartbeat age after which a node counts as dead
   * @return the dead nodes that had running jobs, with the number of released jobs
   */
  List<DeadNodeJobs> releaseJobsOfDeadNodes(UUID callingNodeId, Duration nodeTimeout) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement release = connection.prepareStatement(RELEASE_JOBS_OF_DEAD_NODES_SQL)) {
      release.setObject(1, callingNodeId);
      release.setDouble(2, nodeTimeout.toMillis() / 1000.0);
      List<DeadNodeJobs> released = new ArrayList<>();
      try (ResultSet rs = release.executeQuery()) {
        while (rs.next()) {
          released.add(
              new DeadNodeJobs(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
        }
      }
      commitIfNeeded(connection);
      return released;
    } catch (SQLException e) {
      throw new BikeschedException("Releasing jobs of dead bikesched nodes failed", e);
    }
  }

  /**
   * Deletes nodes without heartbeat for longer than {@code olderThan} that no longer hold {@code
   * RUNNING} jobs.
   *
   * @param callingNodeId the node doing the cleanup; never deleted
   * @param olderThan minimum heartbeat age of deleted nodes
   * @return the deleted nodes
   */
  List<DeletedNode> deleteDeadNodes(UUID callingNodeId, Duration olderThan) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete = connection.prepareStatement(DELETE_DEAD_NODES_SQL)) {
      delete.setObject(1, callingNodeId);
      delete.setDouble(2, olderThan.toMillis() / 1000.0);
      List<DeletedNode> deleted = new ArrayList<>();
      try (ResultSet rs = delete.executeQuery()) {
        while (rs.next()) {
          deleted.add(
              new DeletedNode(
                  rs.getObject(1, UUID.class),
                  rs.getString(2),
                  rs.getObject(3, OffsetDateTime.class).toInstant()));
        }
      }
      commitIfNeeded(connection);
      return deleted;
    } catch (SQLException e) {
      throw new BikeschedException("Deleting dead bikesched nodes failed", e);
    }
  }

  private int update(String sql, String action, Object... parameters) {
    try (Connection connection = dataSource.getConnection()) {
      int updated = execute(connection, sql, parameters);
      commitIfNeeded(connection);
      return updated;
    } catch (SQLException e) {
      throw new BikeschedException(action + " failed", e);
    }
  }

  /** Executes an update on the given connection without committing or closing it. */
  private static int execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      return statement.executeUpdate();
    }
  }

  private static void commitIfNeeded(Connection connection) throws SQLException {
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
  }
}
