package bikesched.core;

import bikesched.core.event.EventBuilder;
import bikesched.core.job.Job;
import bikesched.core.job.JobBuilder;
import bikesched.core.job.JobHandler;
import bikesched.core.job.JobInfo;
import bikesched.core.job.Schedule;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry point of bikesched: a database-backed job scheduler shared by all connected nodes.
 *
 * <p>Create an instance with the {@link #builder() builder}. Building it brings the database schema
 * up to date, registers this application instance as a node in {@code bikesched_nodes} and starts a
 * background heartbeat and job poller. Jobs are executed by the {@link JobHandler} registered under
 * the job's type; the job type and the handler share the same name. Jobs are scheduled with {@link
 * #newJob(String)}. {@link #close() Closing} it stops polling and the heartbeat and deregisters the
 * node:
 *
 * <pre>{@code
 * try (Bikesched bikesched = Bikesched.builder()
 *         .dataSource(dataSource)
 *         .nodeName("worker-1")          // optional, defaults to the host name
 *         .build()) {
 *     // register the handler once, schedule jobs by type ...
 *     bikesched.registerJobHandler("send-mail", job -> mailer.send(job.payload()));
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
   * Registers the handler executing jobs of the given type. From then on jobs of that type can be
   * scheduled with {@link #newJob(String)} and are executed by this handler on this node.
   *
   * @param type the job type
   * @param handler executes the jobs; receives the job being executed
   * @return this instance
   * @throws IllegalArgumentException if the type is blank or already has a handler
   */
  Bikesched registerJobHandler(String type, JobHandler handler);

  /**
   * Registers a handler for jobs of the given type that does not need the job itself (e.g. no
   * payload).
   *
   * @param type the job type
   * @param handler executes the jobs
   * @return this instance
   * @throws IllegalArgumentException if the type is blank or already has a handler
   * @see #registerJobHandler(String, JobHandler)
   */
  default Bikesched registerJobHandler(String type, Runnable handler) {
    return registerJobHandler(type, _ -> handler.run());
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
   * Stops a recurring job (see {@link JobBuilder#recurring(Schedule)}): unregisters it on this node
   * and deletes its pending occurrence unless that is running right now (a running occurrence ends
   * normally; a node that no longer has the recurring job registered schedules no successor).
   *
   * <p>Meant for recurring jobs that were removed from the code: nodes that still register the
   * recurring job recreate its occurrence on their next heartbeat.
   *
   * @param type the job type of the recurring job
   * @return whether a pending occurrence was deleted
   */
  boolean removeRecurringJob(String type);

  /**
   * Starts building an event; {@link EventBuilder#publish()} publishes it to the steps waiting for
   * it (see {@link Job#waitForEvent(String, String, String)}), whose jobs then continue:
   *
   * <pre>{@code
   * List<ReceivedEvent> received = bikesched.newEvent("payment-received", orderId)
   *         .payload(payment)
   *         .publish();
   * }</pre>
   *
   * @param eventName the name of the event, e.g. {@code payment-received}
   * @param eventKey identifies the event instance, e.g. an order id
   * @return a builder for the event
   * @throws IllegalArgumentException if the event name is blank
   */
  EventBuilder newEvent(String eventName, String eventKey);

  /**
   * Looks up a job by its id, in a read-only transaction.
   *
   * <pre>{@code
   * bikesched.findJob(jobId).map(JobInfo::state).ifPresent(state -> ...);
   * }</pre>
   *
   * @param jobId the job id
   * @return the job's id, type and current state; empty if there is no such job (e.g. it never
   *     existed, or its recurring job was removed before it ran)
   * @throws BikeschedException if the database cannot be accessed
   */
  Optional<JobInfo> findJob(UUID jobId);

  /**
   * Cancels a job, e.g. because the customer withdrew the order. The job is cancelled by the node
   * claiming it next instead of running its handler: right away if it is scheduled or waiting for
   * an event, after its current attempt if it is running (unless that attempt ends it). Its steps
   * that completed with a {@link bikesched.core.job.StepBuilder#compensateWith compensation} are
   * compensated first; its unfinished steps, e.g. waiting for an event, are cancelled.
   *
   * @param jobId the job id
   * @return {@code false} if the job does not exist, has ended or is being compensated
   * @throws BikeschedException if the database cannot be accessed
   */
  default boolean cancel(UUID jobId) {
    return cancel(jobId, null);
  }

  /**
   * Cancels a job like {@link #cancel(UUID)}, storing the message as the job's {@code last_error}.
   *
   * @param jobId the job id
   * @param message why the job is cancelled, may be {@code null}
   * @return {@code false} if the job does not exist, has ended or is being compensated
   * @throws BikeschedException if the database cannot be accessed
   */
  boolean cancel(UUID jobId, String message);

  /**
   * Registers this instance as a node of the cluster and starts its heartbeat, then starts polling
   * for jobs and executing them. Only needed if the instance was built with {@link
   * BikeschedBuilder#autoStartPolling(boolean) autoStartPolling(false)}; calling it while already
   * polling has no effect and returns the same node.
   *
   * <p>A node taking over the name of a dead node takes over its id and releases the jobs that node
   * left running.
   *
   * <p>Migrates the schema first, unless that already happened (e.g. when a job was scheduled).
   *
   * @return the node, with its {@link Node#id() id}
   * @throws IllegalStateException if this instance is closed
   * @throws BikeschedException if the migration fails, e.g. because the database is not available,
   *     or a live node with the same name is registered
   */
  Node startPolling();

  /**
   * Returns this instance's node; it has an id only once polling was started.
   *
   * @return the node
   */
  Node node();

  /**
   * Stops polling and the heartbeat, releases unfinished jobs and removes this node from {@code
   * bikesched_nodes}. Idempotent.
   */
  @Override
  void close();
}
