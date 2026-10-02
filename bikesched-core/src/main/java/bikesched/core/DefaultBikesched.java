package bikesched.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link Bikesched} implementation. All database access goes through {@link Database}.
 *
 * <p>Threads:
 *
 * <ul>
 *   <li>{@code bikesched-heartbeat}: single scheduled thread updating this node's heartbeat,
 *       releasing the running jobs of dead nodes (no heartbeat for {@link Settings#nodeTimeout()})
 *       and then removing nodes without heartbeat for twice that long
 *   <li>{@code bikesched-poller}: single scheduled thread claiming due jobs (skip locked) and
 *       handing them to the job executor
 *   <li>{@code bikesched-job-N}: one virtual thread per claimed job, running the {@link
 *       JobHandler} registered for its type (or failing the attempt if there is none)
 * </ul>
 *
 * <p>Concurrency limit: a semaphore holds one permit per job slot ({@link
 * Settings#maxConcurrentJobs()}). The poller claims at most {@code min(free slots,
 * POLL_BATCH_SIZE)} jobs and skips polling while no slot is free; each job returns its slot when
 * it ends. Jobs are therefore only claimed when they can start immediately and never wait in a
 * local queue while marked {@code RUNNING} in the database.
 */
final class DefaultBikesched implements Bikesched {

    static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    static final Duration DEFAULT_NODE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    static final int DEFAULT_MAX_CONCURRENT_JOBS = 10;

    /** Maximum number of jobs claimed per poll. */
    static final int POLL_BATCH_SIZE = 10;

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(DefaultBikesched.class);

    private final Database database;
    private final UUID nodeId;
    private final String nodeName;
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
    private final Duration nodeTimeout;
    private final Semaphore jobSlots;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ScheduledExecutorService pollerExecutor;
    private final ExecutorService jobExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DefaultBikesched(Database database, UUID nodeId, Settings settings) {
        this.database = database;
        this.nodeId = nodeId;
        this.nodeName = settings.nodeName();
        this.nodeTimeout = settings.nodeTimeout();
        this.jobSlots = new Semaphore(settings.maxConcurrentJobs());
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("bikesched-heartbeat").daemon().factory());
        this.pollerExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("bikesched-poller").daemon().factory());
        this.jobExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("bikesched-job-", 0).factory());
    }

    /** Migrates the schema, registers the node and starts its heartbeat and job poller. */
    static DefaultBikesched create(Database database, Settings settings) {
        database.migrate();
        String nodeName = settings.nodeName();
        UUID nodeId = database
                .registerNode(UUID.randomUUID(), nodeName, settings.nodeTimeout())
                .orElseThrow(() -> new BikeschedException("A live bikesched node named '" + nodeName
                        + "' is already registered; node names must be unique"));
        // A freshly started node runs nothing yet: jobs still RUNNING under this id belong to a
        // previous (crashed) instance with the same name whose row was taken over.
        int orphaned = database.releaseJobs(nodeId);
        if (orphaned > 0) {
            LOG.warn("Released {} job(s) left running by a previous instance of bikesched node {} ({})",
                    orphaned, nodeName, nodeId);
        }
        var bikesched = new DefaultBikesched(database, nodeId, settings);
        bikesched.scheduleWithFixedDelay(bikesched.heartbeatExecutor, bikesched::heartbeat, settings.heartbeatInterval());
        bikesched.scheduleWithFixedDelay(bikesched.pollerExecutor, bikesched::poll, settings.pollInterval());
        LOG.info("bikesched node {} ({}) registered, running up to {} job(s) concurrently",
                nodeName, nodeId, settings.maxConcurrentJobs());
        return bikesched;
    }

    @Override
    public Bikesched registerHandler(String type, JobHandler handler) {
        requireValidType(type);
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalArgumentException("A handler for job type '" + type + "' is already registered");
        }
        LOG.info("bikesched node {} ({}) registered handler for job type {}", nodeName, nodeId, type);
        return this;
    }

    @Override
    public UUID schedule(String type, String payload, Instant scheduledAt, JobHandler handler) {
        requireValidType(type);
        Objects.requireNonNull(handler, "handler");
        if (closed.get()) {
            throw new IllegalStateException("bikesched node " + nodeName + " is closed");
        }
        if (handlers.putIfAbsent(type, handler) == null) {
            LOG.info("bikesched node {} ({}) registered handler for job type {}", nodeName, nodeId, type);
        } else {
            LOG.debug("Handler for job type {} already registered, keeping it", type);
        }
        return schedule(type, payload, scheduledAt);
    }

    @Override
    public UUID schedule(String type, String payload, Instant scheduledAt) {
        requireValidType(type);
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        if (closed.get()) {
            throw new IllegalStateException("bikesched node " + nodeName + " is closed");
        }
        if (!handlers.containsKey(type)) {
            throw new IllegalArgumentException("Unknown job type '" + type + "': no handler registered (known types: "
                    + new TreeSet<>(handlers.keySet()) + ")");
        }
        UUID jobId = UUID.randomUUID();
        database.insertJob(jobId, type, payload, scheduledAt);
        LOG.debug("Scheduled job {} of type {} at {}", jobId, type, scheduledAt);
        return jobId;
    }

    @Override
    public UUID nodeId() {
        return nodeId;
    }

    @Override
    public String nodeName() {
        return nodeName;
    }

    /**
     * Stops polling, lets in-flight jobs finish (up to a timeout), releases jobs that are still
     * running back to {@code SCHEDULED}, stops the heartbeat and deregisters the node.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        shutdownAndAwait(pollerExecutor, "poller");
        shutdownAndAwait(jobExecutor, "job executor");
        shutdownAndAwait(heartbeatExecutor, "heartbeat");
        try {
            int released = database.releaseJobs(nodeId);
            if (released > 0) {
                LOG.info("Released {} unfinished job(s) of bikesched node {} ({})", released, nodeName, nodeId);
            }
            database.deregisterNode(nodeId);
            LOG.info("bikesched node {} ({}) deregistered", nodeName, nodeId);
        } catch (BikeschedException e) {
            // The row goes stale and can be taken over by a node with the same name later.
            LOG.warn("Could not deregister bikesched node {} ({})", nodeName, nodeId, e);
        }
    }

    private void scheduleWithFixedDelay(ScheduledExecutorService executor, Runnable task, Duration interval) {
        long millis = interval.toMillis();
        executor.scheduleWithFixedDelay(task, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        // Must never throw: an exception would silently cancel all future heartbeats.
        try {
            if (database.heartbeat(nodeId)) {
                LOG.trace("Heartbeat of bikesched node {} ({})", nodeName, nodeId);
            } else {
                LOG.warn("bikesched node {} ({}) is no longer registered", nodeName, nodeId);
            }
        } catch (RuntimeException e) {
            LOG.warn("Heartbeat of bikesched node {} ({}) failed", nodeName, nodeId, e);
        }
        releaseJobsOfDeadNodes();
        deleteDeadNodes();
    }

    /**
     * Removes nodes without heartbeat for twice {@link Settings#nodeTimeout()}. Runs after
     * {@link #releaseJobsOfDeadNodes()}, so their jobs are free by then. The grace period beyond
     * the timeout keeps the row (and id) around for a crashed node that restarts under the same
     * name shortly after.
     */
    private void deleteDeadNodes() {
        try {
            Duration olderThan = nodeTimeout.multipliedBy(2);
            List<Database.DeletedNode> deleted = database.deleteDeadNodes(nodeId, olderThan);
            if (!deleted.isEmpty()) {
                LOG.info("Removed {} dead bikesched node(s) without heartbeat for more than {}: {}",
                        deleted.size(), olderThan, deleted.stream()
                                .map(n -> n.nodeName() + " (" + n.nodeId() + ", last heartbeat " + n.lastHeartbeatAt() + ")")
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        } catch (RuntimeException e) {
            LOG.warn("Removing dead bikesched nodes failed on node {} ({})", nodeName, nodeId, e);
        }
    }

    /** Puts jobs of nodes without heartbeat for {@link Settings#nodeTimeout()} back to SCHEDULED. */
    private void releaseJobsOfDeadNodes() {
        try {
            for (var dead : database.releaseJobsOfDeadNodes(nodeId, nodeTimeout)) {
                LOG.warn("Released {} running job(s) of dead bikesched node {} ({}): no heartbeat for {}",
                        dead.releasedJobs(), dead.nodeName(), dead.nodeId(), nodeTimeout);
            }
        } catch (RuntimeException e) {
            LOG.warn("Releasing jobs of dead bikesched nodes failed on node {} ({})", nodeName, nodeId, e);
        }
    }

    private void poll() {
        // Must never throw: an exception would silently cancel all future polls.
        try {
            int free = jobSlots.availablePermits();
            if (free == 0) {
                LOG.debug("bikesched node {} ({}) is at capacity, skipping poll", nodeName, nodeId);
                return;
            }
            if (handlers.isEmpty()) {
                // Avoids claiming (and failing) jobs right after startup, before the application
                // had a chance to register its handlers.
                LOG.debug("bikesched node {} ({}) has no job handlers registered, skipping poll", nodeName, nodeId);
                return;
            }
            int limit = Math.min(free, POLL_BATCH_SIZE);
            LOG.debug("bikesched node {} ({}) polling for up to {} job(s), {} free slot(s)",
                    nodeName, nodeId, limit, free);
            // Never blocks: only this (single) poller thread acquires, job threads only release,
            // so the available permits can only have grown since they were read.
            jobSlots.acquireUninterruptibly(limit);
            List<Job> jobs = List.of();
            try {
                jobs = database.claimJobs(nodeId, limit);
            } finally {
                jobSlots.release(limit - jobs.size()); // unused slots; all of them if claiming failed
            }
            if (!jobs.isEmpty()) {
                LOG.debug("bikesched node {} ({}) claimed {} job(s)", nodeName, nodeId, jobs.size());
            }
            for (Job job : jobs) {
                dispatch(job);
            }
        } catch (RuntimeException e) {
            LOG.warn("Polling for jobs failed on bikesched node {} ({})", nodeName, nodeId, e);
        }
    }

    /** Runs a claimed job on its own virtual thread; the job's slot is held until it ends. */
    private void dispatch(Job job) {
        try {
            jobExecutor.execute(() -> {
                try {
                    process(job);
                } finally {
                    jobSlots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutting down: the job stays RUNNING in the database and is released in close().
            jobSlots.release();
        }
    }

    /** Runs the job's handler; verified here because claiming is not filtered by job type. */
    private void process(Job job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            LOG.error("No handler registered for job type {} on bikesched node {} ({}), cannot run job {}",
                    job.type(), nodeName, nodeId, job.id());
            recordFailure(job, new BikeschedException("No handler registered for job type '" + job.type()
                    + "' on bikesched node " + nodeName));
            return;
        }
        try {
            handler.run(job);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure(job, e);
            return;
        }
        try {
            if (!database.completeJob(job.id(), nodeId)) {
                LOG.warn("Job {} was no longer running on this node when completing it", job.id());
            }
        } catch (RuntimeException e) {
            LOG.warn("Completing job {} failed", job.id(), e);
        }
    }

    private void recordFailure(Job job, Exception failure) {
        try {
            var newState = database.failJob(job.id(), nodeId, failure.toString());
            if (newState.isEmpty()) {
                LOG.warn("Job {} of type {} failed and was no longer running on this node", job.id(), job.type(), failure);
            } else if (newState.get().equals("FAILED")) {
                LOG.error("Job {} of type {} failed permanently after {} attempt(s)",
                        job.id(), job.type(), job.attempts(), failure);
            } else {
                LOG.warn("Job {} of type {} failed (attempt {} of {}), it will be retried",
                        job.id(), job.type(), job.attempts(), job.maxAttempts(), failure);
            }
        } catch (RuntimeException e) {
            e.addSuppressed(failure);
            LOG.warn("Recording failure of job {} failed", job.id(), e);
        }
    }

    private static void shutdownAndAwait(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("bikesched {} did not terminate within {}, interrupting it", name, SHUTDOWN_TIMEOUT);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void requireValidType(String type) {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("job type must not be blank");
        }
    }

    static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            String fromEnv = System.getenv("HOSTNAME");
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
            throw new BikeschedException("Cannot determine host name; set a node name via Bikesched.builder().node(...)", e);
        }
    }
}
