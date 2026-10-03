package bikesched.core;

import java.util.UUID;

/**
 * Entry point of bikesched: a database-backed job scheduler shared by all connected nodes.
 *
 * <p>Create an instance with the {@link #builder() builder}. Building it brings the database
 * schema up to date, registers this application instance as a node in {@code bikesched_nodes}
 * and starts a background heartbeat and job poller. Jobs are executed by the {@link JobHandler}
 * registered under the job's type; the job type and the handler share the same name. Jobs are
 * scheduled with {@link #newJob(String)}. {@link #close() Closing} it stops polling and the
 * heartbeat and deregisters the node:
 *
 * <pre>{@code
 * try (Bikesched bikesched = Bikesched.builder()
 *         .dataSource(dataSource)
 *         .node("worker-1")          // optional, defaults to the host name
 *         .build()) {
 *     // register the handler once, schedule jobs by type ...
 *     bikesched.registerHandler("send-mail", job -> mailer.send(job.payload()));
 *     bikesched.newJob("send-mail").payload("{\"to\": \"alice@example.com\"}").schedule();
 *
 *     // ... or pass the handler along when scheduling
 *     bikesched.newJob("send-sms")
 *             .payload("{\"to\": \"+49...\"}")
 *             .handler(job -> sms.send(job.payload()))
 *             .schedule(Duration.ofMinutes(5));
 * }
 * }</pre>
 *
 * <p>Every node polls all due jobs regardless of their type. A node that claims a job it has no
 * handler for logs an error and fails that attempt (the job is retried while attempts remain), so
 * all nodes sharing a database should register the same handlers.
 *
 * <p>Log output of bikesched threads and job handlers carries the node and job in the logging
 * context, see {@link BikeschedLogContext}.
 */
public interface Bikesched extends AutoCloseable {

    /**
     * Returns a new builder for a {@link Bikesched} instance.
     *
     * @return a new builder
     */
    static BikeschedBuilder builder() {
        return new BikeschedBuilder();
    }

    /**
     * Registers the handler executing jobs of the given type. From then on jobs of that type can
     * be scheduled with {@link #newJob(String)} and are executed by this handler on this node.
     *
     * @param type the job type
     * @param handler executes the jobs; receives the job being executed
     * @return this instance
     * @throws IllegalArgumentException if the type is blank or already has a handler
     */
    Bikesched registerHandler(String type, JobHandler handler);

    /**
     * Registers a handler for jobs of the given type that does not need the job itself (e.g. no
     * payload).
     *
     * @param type the job type
     * @param handler executes the jobs
     * @return this instance
     * @throws IllegalArgumentException if the type is blank or already has a handler
     * @see #registerHandler(String, JobHandler)
     */
    default Bikesched registerHandler(String type, Runnable handler) {
        return registerHandler(type, _ -> handler.run());
    }

    /**
     * Starts building a job of the given type; {@link JobBuilder#schedule()} (or one of its
     * overloads) stores it. This is the only way to schedule jobs:
     *
     * <pre>{@code
     * bikesched.newJob("ping").schedule();
     * bikesched.newJob("send-mail").payload(json).handler(mailHandler).schedule();
     * bikesched.newJob("report").priority(5).maxAttempts(1).schedule(Duration.ofMinutes(10));
     * bikesched.newJob("cleanup").schedule(Instant.parse("2026-10-03T06:00:00Z"));
     * }</pre>
     *
     * @param type the job type, also the name its handler is registered under
     * @return a builder for the job
     * @throws IllegalArgumentException if the type is blank
     */
    JobBuilder newJob(String type);

    /**
     * Starts polling for jobs and executing them on this node. Only needed if the node was built
     * with {@link BikeschedBuilder#autoStartPolling(boolean) autoStartPolling(false)}; calling it
     * while already polling has no effect.
     *
     * @throws IllegalStateException if this instance is closed
     */
    void startPolling();

    /**
     * Returns whether this node polls for and executes jobs.
     *
     * @return {@code true} once polling was started and until the instance is closed
     */
    boolean isPolling();

    /**
     * Returns the id of the node this instance registered as.
     *
     * @return the node id
     */
    UUID nodeId();

    /**
     * Returns the unique name of the node this instance registered as.
     *
     * @return the node name
     */
    String nodeName();

    /**
     * Stops polling and the heartbeat, releases unfinished jobs and removes this node from
     * {@code bikesched_nodes}. Idempotent.
     */
    @Override
    void close();
}
