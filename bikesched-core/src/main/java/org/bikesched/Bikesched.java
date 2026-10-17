// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.admin.Admin;
import org.bikesched.event.EventBuilder;
import org.bikesched.event.EventPublisher;
import org.bikesched.event.EventType;
import org.bikesched.event.NodeEventBuilder;
import org.bikesched.internal.util.Version;
import org.bikesched.job.Job;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobHistoryEntry;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobType;
import org.bikesched.job.JobTypeFailureHandler;
import org.bikesched.job.JobTypeHandler;
import org.bikesched.job.Recurrence;
import org.bikesched.job.TypedJobFailureHandler;
import org.bikesched.job.TypedJobHandler;
import org.jspecify.annotations.Nullable;

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
public interface Bikesched extends AutoCloseable, EventPublisher {

  /**
   * Returns a new builder for a {@link Bikesched} instance.
   *
   * @return a new builder
   */
  static BikeschedBuilder builder() {
    return new BikeschedBuilder();
  }

  /**
   * Returns the version of the bikesched library, e.g. {@code 0.1.0} or {@code 0.2.0-SNAPSHOT}. The
   * database schema is migrated to the state of this version when a node is built.
   *
   * @return the library version, or {@code "unknown"} if it cannot be determined
   */
  static String version() {
    return Version.get();
  }

  /**
   * Registers the handler executing jobs of the given type. From then on jobs of that type can be
   * scheduled with {@link #newJob(String)} and are executed by this handler on this node.
   *
   * @param type the job type, not blank, at most 200 characters
   * @param handler executes the jobs; receives the job being executed
   * @return this instance
   * @throws IllegalArgumentException if the type is blank or already has a handler
   */
  Bikesched registerJobHandler(String type, JobHandler handler);

  /**
   * Registers a handler for jobs of the given type that does not need the job itself (e.g. no
   * payload).
   *
   * @param type the job type, not blank, at most 200 characters
   * @param handler executes the jobs
   * @return this instance
   * @throws IllegalArgumentException if the type is blank or already has a handler
   * @see #registerJobHandler(String, JobHandler)
   */
  default Bikesched registerJobHandler(String type, Runnable handler) {
    return registerJobHandler(type, ignored -> handler.run());
  }

  /**
   * Registers the handler executing jobs of a {@link JobType}; it receives the payload mapped to
   * the type's payload class. Like {@link #registerJobHandler(String, JobHandler)} otherwise.
   *
   * <pre>{@code
   * static final JobType<Order> ORDER = JobType.of("order", Order.class);
   *
   * bikesched.registerJobHandler(ORDER, (job, order) -> shipping.ship(order));
   * }</pre>
   *
   * @param type the job type and its payload class
   * @param handler executes the jobs; receives the job and its payload
   * @param <T> the payload type
   * @return this instance
   * @throws IllegalArgumentException if the type already has a handler, or this node already uses
   *     the type's name with another payload class
   */
  <T extends @Nullable Object> Bikesched registerJobHandler(
      JobType<T> type, TypedJobHandler<? super T> handler);

  /**
   * Registers a handler that carries its job type, see {@link JobTypeHandler}; like {@link
   * #registerJobHandler(JobType, TypedJobHandler) registerJobHandler(handler.type(), handler)}.
   *
   * <pre>{@code
   * handlers.forEach(bikesched::registerJobHandler);   // e.g. List<JobTypeHandler<?>> from DI
   * }</pre>
   *
   * @param handler executes the jobs of {@link JobTypeHandler#type()}
   * @param <T> the payload type
   * @return this instance
   * @throws IllegalArgumentException if the type already has a handler, or this node already uses
   *     the type's name with another payload class
   */
  default <T extends @Nullable Object> Bikesched registerJobHandler(JobTypeHandler<T> handler) {
    Objects.requireNonNull(handler, "handler");
    return registerJobHandler(Objects.requireNonNull(handler.type(), "handler.type()"), handler);
  }

  /**
   * Registers the failure handler of a job type: it runs before a job of the type ends {@code
   * FAILED} or {@code COMPENSATION_FAILED}, e.g. to move it to a dead-letter table, see {@link
   * JobFailureHandler}. The job's outcome is final at that point.
   *
   * <pre>{@code
   * bikesched.registerJobFailureHandler("charge", failed ->
   *     deadLetters.insert(failed.id(), failed.type(), failed.payload(), failed.error()));
   * }</pre>
   *
   * <p>Like the job handler, register it on every node that handles the type: the node failing a
   * job decides by its own registration whether the job becomes {@code FAILING}.
   *
   * @param type the job type, not blank, at most 200 characters
   * @param handler handles the failed jobs
   * @return this instance
   * @throws IllegalArgumentException if the type is blank or already has a failure handler
   */
  Bikesched registerJobFailureHandler(String type, JobFailureHandler handler);

  /**
   * Registers the failure handler of a {@link JobType}; it receives the payload mapped to the
   * type's payload class. Like {@link #registerJobFailureHandler(String, JobFailureHandler)}
   * otherwise.
   *
   * @param type the job type and its payload class
   * @param handler handles the failed jobs; receives the job and its payload
   * @param <T> the payload type
   * @return this instance
   * @throws IllegalArgumentException if the type already has a failure handler, or this node
   *     already uses the type's name with another payload class
   */
  <T extends @Nullable Object> Bikesched registerJobFailureHandler(
      JobType<T> type, TypedJobFailureHandler<? super T> handler);

  /**
   * Registers a failure handler that carries its job type, see {@link JobTypeFailureHandler}; like
   * {@link #registerJobFailureHandler(JobType, TypedJobFailureHandler)
   * registerJobFailureHandler(handler.type(), handler)}.
   *
   * @param handler handles the failed jobs of {@link JobTypeFailureHandler#type()}
   * @param <T> the payload type
   * @return this instance
   * @throws IllegalArgumentException if the type already has a failure handler, or this node
   *     already uses the type's name with another payload class
   */
  default <T extends @Nullable Object> Bikesched registerJobFailureHandler(
      JobTypeFailureHandler<T> handler) {
    Objects.requireNonNull(handler, "handler");
    return registerJobFailureHandler(
        Objects.requireNonNull(handler.type(), "handler.type()"), handler);
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
   * @param type the job type, also the name its handler is registered under (at most 200
   *     characters)
   * @return a builder for the job
   * @throws IllegalArgumentException if the type is blank
   */
  JobBuilder<Object> newJob(String type);

  /**
   * Starts building a job of a {@link JobType}: {@link JobBuilder#payload(Object) payload(...)}
   * only accepts the type's payload class.
   *
   * <pre>{@code
   * bikesched.newJob(ORDER).payload(order).schedule();
   * }</pre>
   *
   * @param type the job type and its payload class
   * @param <T> the payload type
   * @return a builder for the job
   * @throws IllegalArgumentException if this node already uses the type's name with another payload
   *     class
   */
  <T extends @Nullable Object> JobBuilder<T> newJob(JobType<T> type);

  /**
   * Stops a recurring job (see {@link JobBuilder#schedule(Recurrence)}): unregisters it on this
   * node and deletes its pending occurrence unless that is running right now (a running occurrence
   * ends normally; a node that no longer has the recurring job registered schedules no successor).
   *
   * <p>Meant for recurring jobs that were removed from the code: nodes that still register the
   * recurring job recreate its occurrence when they register it again, e.g. on their next start.
   *
   * @param type the job type of the recurring job
   * @return whether a pending occurrence was deleted
   */
  boolean removeRecurringJob(String type);

  /**
   * Stops a recurring job of a {@link JobType}, see {@link #removeRecurringJob(String)}.
   *
   * @param type the job type of the recurring job
   * @return whether a pending occurrence was deleted
   */
  default boolean removeRecurringJob(JobType<?> type) {
    return removeRecurringJob(type.name());
  }

  /**
   * Starts building an event; {@link EventBuilder#publish()} publishes it to the steps waiting for
   * it (see {@link Job#waitForEvent(String, String, String, Class)}), whose jobs then continue:
   *
   * <pre>{@code
   * bikesched.newEvent("payment-received", orderId)
   *     .payload(payment)
   *     .publish();
   * }</pre>
   *
   * @param eventName the name of the event, e.g. {@code payment-received}, at most 200 characters
   * @param eventKey identifies the event instance, e.g. an order id, at most 500 characters
   * @return a builder for the event
   * @throws IllegalArgumentException if the event name is blank
   */
  @Override
  NodeEventBuilder<Object> newEvent(String eventName, String eventKey);

  /**
   * Starts building an event of an {@link EventType}: {@link EventBuilder#payload(Object)
   * payload(...)} only accepts the type's payload class.
   *
   * <pre>{@code
   * bikesched.newEvent(PAYMENT_RECEIVED, order.id()).payload(payment).publish();
   * }</pre>
   *
   * @param eventType the event's name and payload class
   * @param eventKey identifies the event instance, e.g. an order id, at most 500 characters
   * @param <T> the payload type
   * @return a builder for the event
   */
  @Override
  <T extends @Nullable Object> NodeEventBuilder<T> newEvent(
      EventType<T> eventType, String eventKey);

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
   * Reads the recorded history of a job, in a read-only transaction: every change of the job and of
   * its steps, oldest first.
   *
   * <pre>{@code
   * bikesched.findJobHistory(jobId).forEach(entry -> log.info("{}", entry));
   * }</pre>
   *
   * <p>Changes are only recorded by nodes with the history enabled ({@link
   * BikeschedBuilder#jobHistory(java.time.Duration)}), and kept for its retention period. This
   * method reads the table on any node, also on one that does not record itself.
   *
   * @param jobId the job id
   * @return the job's history; empty if nothing was recorded (or it was deleted)
   * @throws BikeschedException if the database cannot be accessed
   */
  List<JobHistoryEntry> findJobHistory(UUID jobId);

  /**
   * Read-only lookups for operating bikesched: jobs by type and state, their steps, payloads and
   * results, job types with counts per state, events, waiting steps and nodes. The admin UI ({@code
   * bikesched-admin}) is built on them; they also suit custom dashboards and support tools.
   *
   * <pre>{@code
   * bikesched.admin().findJobs(JobQuery.newest(20).withStates(JobState.FAILED));
   * }</pre>
   *
   * <p>Like {@link #findJob(UUID)}, the lookups work on every node, also one that is not polling.
   *
   * @return the lookups of this node
   */
  Admin admin();

  /**
   * Cancels a job, e.g. because the customer withdrew the order. The job is cancelled by the node
   * claiming it next instead of running its handler: right away if it is scheduled or waiting for
   * an event, after its current attempt if it is running (unless that attempt ends it). Its steps
   * that completed with a {@link org.bikesched.job.StepBuilder#compensateWith compensation} are
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
  boolean cancel(UUID jobId, @Nullable String message);

  /**
   * Retries a job that failed permanently ({@code FAILED}), e.g. after fixing the cause: it is
   * scheduled to run right away with all its attempts again. Steps that completed are not executed
   * again (unless they were compensated), so the job continues where it failed.
   *
   * @param jobId the job id
   * @return {@code false} if the job does not exist (e.g. deleted after the job retention) or has
   *     not failed
   * @throws IllegalArgumentException if the job is an occurrence of a recurring job, which cannot
   *     be retried (its next occurrence runs anyway)
   * @throws BikeschedException if the database cannot be accessed
   */
  boolean retry(UUID jobId);

  /**
   * Registers this instance as a node of the cluster and starts its heartbeat, then starts polling
   * for jobs and executing them. Only needed if the instance was built with {@link
   * BikeschedBuilder#autoStartPolling(boolean) autoStartPolling(false)}; calling it while already
   * polling has no effect and returns the same node.
   *
   * <p>The node registers under a new id. Its name need not be unique: an instance restarted after
   * a crash registers next to the stale entry of its previous run, whose jobs the node clean-up
   * releases once that entry missed its heartbeats for the {@link BikeschedBuilder#nodeTimeout(
   * java.time.Duration) node timeout}.
   *
   * <p>Migrates the schema first, or validates its migration history with {@link
   * BikeschedBuilder#migrateSchema(boolean) migrateSchema(false)}, unless initialization already
   * succeeded (e.g. when a job was scheduled).
   *
   * @return the node, with its {@link Node#id() id}
   * @throws IllegalStateException if this instance is closed
   * @throws BikeschedException if migration or validation fails, e.g. the database is not available
   */
  Node start();

  /**
   * Migrates the schema, creating missing tables and applying pending migrations, without
   * registering a node, sending heartbeats or polling for jobs. Always runs the migrator, even
   * after successful initialization or with {@link BikeschedBuilder#migrateSchema(boolean)} off.
   * Use a migration-capable data source and {@code autoStartPolling(false)} for a deploy step.
   *
   * @throws IllegalStateException if this instance is closed
   * @throws BikeschedException if the migration fails, e.g. because the database is not available
   *     or an applied migration checksum does not match this library
   */
  void migrate();

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
