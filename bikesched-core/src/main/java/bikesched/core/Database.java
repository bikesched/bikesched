package bikesched.core;

import bikesched.core.schema.SchemaMigrator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final String REGISTER_NODE_SQL = """
            insert into bikesched_nodes (id, name) values (?, ?)
            on conflict (name) do update
               set started_at = now(), last_heartbeat_at = now()
             where bikesched_nodes.last_heartbeat_at < now() - make_interval(secs => ?)
            returning id""";

    private static final String HEARTBEAT_SQL = "update bikesched_nodes set last_heartbeat_at = now() where id = ?";

    private static final String DEREGISTER_NODE_SQL = "delete from bikesched_nodes where id = ?";

    private static final String INSERT_JOB_SQL = """
            insert into bikesched_jobs (id, job_type, payload, scheduled_at, priority, max_attempts)
            values (?, ?, ?::jsonb, ?, ?, ?)""";

    // Claims due jobs in one statement: rows locked by other nodes' concurrent claims are skipped
    // (instead of waiting), and claimed rows are marked RUNNING for this node before the commit
    // releases the row locks, so no other node can pick them up afterwards.
    private static final String CLAIM_JOBS_SQL = """
            update bikesched_jobs
               set state = 'RUNNING', locked_by = ?, locked_at = now(), attempts = attempts + 1, updated_at = now()
             where id in (select id from bikesched_jobs
                           where state = 'SCHEDULED' and scheduled_at <= now()
                           order by priority desc, scheduled_at
                           limit ?
                           for update skip locked)
            returning id, job_type, payload::text, priority, scheduled_at, attempts, max_attempts""";

    private static final String COMPLETE_JOB_SQL = """
            update bikesched_jobs
               set state = 'SUCCEEDED', locked_by = null, locked_at = null, finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'""";

    private static final String CANCEL_JOB_SQL = """
            update bikesched_jobs
               set state = 'CANCELLED', locked_by = null, locked_at = null, last_error = ?,
                   finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'""";

    // A failed attempt: retried (back to SCHEDULED) while attempts remain, otherwise FAILED.
    private static final String FAIL_JOB_SQL = """
            update bikesched_jobs
               set state = case when attempts >= max_attempts then 'FAILED' else 'SCHEDULED' end,
                   finished_at = case when attempts >= max_attempts then now() end,
                   locked_by = null, locked_at = null, last_error = ?, updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'
            returning state""";

    private static final String RELEASE_JOBS_SQL = """
            update bikesched_jobs
               set state = 'SCHEDULED', locked_by = null, locked_at = null, updated_at = now()
             where locked_by = ? and state = 'RUNNING'""";

    // Releases RUNNING jobs of nodes whose heartbeat is older than the timeout (excluding the
    // calling node) and reports the released job count per dead node. Concurrent callers on other
    // nodes are safe: the second one blocks on the row locks and then no longer matches
    // state = 'RUNNING'.
    private static final String RELEASE_JOBS_OF_DEAD_NODES_SQL = """
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
    private static final String DELETE_DEAD_NODES_SQL = """
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

    private static final Comparator<Job> CLAIM_ORDER =
            Comparator.comparingInt(Job::priority).reversed().thenComparing(Job::scheduledAt);

    private final DataSource dataSource;

    Database(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
     * Registers a node under a unique name.
     *
     * @param newNodeId id to use if the name is free
     * @param name the unique node name
     * @param deadAfter heartbeat age after which an existing node with this name counts as dead
     * @return the node id: {@code newNodeId}, or the id of the dead node that was taken over;
     *     empty if a live node already holds the name
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
     * @param type the job type
     * @param payload the payload as JSON text, may be {@code null}
     * @param scheduledAt the earliest time the job may run
     * @param priority higher priorities are claimed first
     * @param maxAttempts number of attempts before the job is marked {@code FAILED}
     */
    void insertJob(UUID jobId, String type, String payload, Instant scheduledAt, int priority, int maxAttempts) {
        update(INSERT_JOB_SQL, "Scheduling bikesched job of type '" + type + "'",
                jobId, type, payload, OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC), priority, maxAttempts);
    }

    /**
     * Claims up to {@code limit} due, scheduled jobs for a node using {@code FOR UPDATE SKIP LOCKED}
     * and marks them {@code RUNNING}.
     *
     * @param nodeId the claiming node
     * @param limit maximum number of jobs to claim
     * @return the claimed jobs, highest priority and earliest scheduled first
     */
    List<Job> claimJobs(UUID nodeId, int limit) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement claim = connection.prepareStatement(CLAIM_JOBS_SQL)) {
            claim.setObject(1, nodeId);
            claim.setInt(2, limit);
            List<Job> jobs = new ArrayList<>();
            try (ResultSet rs = claim.executeQuery()) {
                while (rs.next()) {
                    jobs.add(new Job(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getInt(4),
                            rs.getObject(5, OffsetDateTime.class).toInstant(),
                            rs.getInt(6),
                            rs.getInt(7)));
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
     * Records a failed attempt of a job claimed by the node: the job is scheduled again while
     * attempts remain, otherwise it is marked {@code FAILED}.
     *
     * @param jobId the job id
     * @param nodeId the node that claimed the job
     * @param error description of the failure, stored as {@code last_error}
     * @return the job's new state ({@code SCHEDULED} or {@code FAILED}); empty if the job is not
     *     running on this node (anymore)
     */
    Optional<String> failJob(UUID jobId, UUID nodeId, String error) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement fail = connection.prepareStatement(FAIL_JOB_SQL)) {
            fail.setString(1, error);
            fail.setObject(2, jobId);
            fail.setObject(3, nodeId);
            String state = null;
            try (ResultSet rs = fail.executeQuery()) {
                if (rs.next()) {
                    state = rs.getString(1);
                }
            }
            commitIfNeeded(connection);
            return Optional.ofNullable(state);
        } catch (SQLException e) {
            throw new BikeschedException("Recording failure of bikesched job " + jobId + " failed", e);
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
     * Puts the {@code RUNNING} jobs of dead nodes (no heartbeat within {@code nodeTimeout}) back
     * to {@code SCHEDULED}.
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
                    released.add(new DeadNodeJobs(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
                }
            }
            commitIfNeeded(connection);
            return released;
        } catch (SQLException e) {
            throw new BikeschedException("Releasing jobs of dead bikesched nodes failed", e);
        }
    }

    /**
     * Deletes nodes without heartbeat for longer than {@code olderThan} that no longer hold
     * {@code RUNNING} jobs.
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
                    deleted.add(new DeletedNode(
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
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            int updated = statement.executeUpdate();
            commitIfNeeded(connection);
            return updated;
        } catch (SQLException e) {
            throw new BikeschedException(action + " failed", e);
        }
    }

    private static void commitIfNeeded(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
    }
}
