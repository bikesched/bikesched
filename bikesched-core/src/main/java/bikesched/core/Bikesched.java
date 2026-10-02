package bikesched.core;

import java.time.Instant;
import java.util.UUID;

/**
 * Entry point of bikesched: a database-backed job scheduler shared by all connected nodes.
 *
 * <p>Create an instance with the {@link #builder() builder}. Building it brings the database
 * schema up to date, registers this application instance as a node in {@code bikesched_nodes}
 * and starts a background heartbeat and job poller. Jobs are executed by the {@link JobHandler}
 * registered under the job's type; the job type and the handler share the same name. {@link
 * #close() Closing} it stops polling and the heartbeat and deregisters the node:
 *
 * <pre>{@code
 * try (Bikesched bikesched = Bikesched.builder()
 *         .dataSource(dataSource)
 *         .node("worker-1")          // optional, defaults to the host name
 *         .build()) {
 *     // register once, schedule by name ...
 *     bikesched.registerHandler("send-mail", job -> mailer.send(job.payload()));
 *     bikesched.schedule("send-mail", "{\"to\": \"alice@example.com\"}");
 *
 *     // ... or register and schedule in one call
 *     bikesched.schedule("send-sms", "{\"to\": \"+49...\"}", job -> sms.send(job.payload()));
 * }
 * }</pre>
 *
 * <p>Every node polls all due jobs regardless of their type. A node that claims a job it has no
 * handler for logs an error and fails that attempt (the job is retried while attempts remain), so
 * all nodes sharing a database should register the same handlers.
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
     * be {@link #schedule scheduled} and are executed by this handler on this node.
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
     * Schedules a job to run as soon as possible.
     *
     * @param type the job type; a handler must be registered for it
     * @param payload the job payload as JSON text, may be {@code null}
     * @return the id of the scheduled job
     * @throws IllegalArgumentException if no handler is registered for the type
     * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid JSON
     * @throws IllegalStateException if this instance is closed
     */
    default UUID schedule(String type, String payload) {
        return schedule(type, payload, Instant.now());
    }

    /**
     * Schedules a job to run at or after the given time. The job is stored in
     * {@code bikesched_jobs} and executed by whichever node claims it first; it uses the default
     * priority (0) and maximum number of attempts (3).
     *
     * @param type the job type; a handler must be registered for it
     * @param payload the job payload as JSON text, may be {@code null}
     * @param scheduledAt the earliest time the job may run
     * @return the id of the scheduled job
     * @throws IllegalArgumentException if no handler is registered for the type
     * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid JSON
     * @throws IllegalStateException if this instance is closed
     */
    UUID schedule(String type, String payload, Instant scheduledAt);

    /**
     * Schedules a job to run as soon as possible, registering its handler under the job type if
     * none is registered yet.
     *
     * @param type the job type, also the name the handler is registered under
     * @param payload the job payload as JSON text, may be {@code null}
     * @param handler executes jobs of this type; ignored if a handler is already registered for it
     * @return the id of the scheduled job
     * @throws IllegalArgumentException if the type is blank
     * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid JSON
     * @throws IllegalStateException if this instance is closed
     * @see #schedule(String, String, Instant, JobHandler)
     */
    default UUID schedule(String type, String payload, JobHandler handler) {
        return schedule(type, payload, Instant.now(), handler);
    }

    /**
     * Schedules a job to run at or after the given time, registering its handler under the job
     * type if none is registered yet. If a handler is already registered for the type, that one
     * is kept and executes the job.
     *
     * @param type the job type, also the name the handler is registered under
     * @param payload the job payload as JSON text, may be {@code null}
     * @param scheduledAt the earliest time the job may run
     * @param handler executes jobs of this type; ignored if a handler is already registered for it
     * @return the id of the scheduled job
     * @throws IllegalArgumentException if the type is blank
     * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid JSON
     * @throws IllegalStateException if this instance is closed
     */
    UUID schedule(String type, String payload, Instant scheduledAt, JobHandler handler);

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
