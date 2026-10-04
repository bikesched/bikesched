package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import org.bikesched.BikeschedLogContext;
import org.bikesched.JsonMapper;
import org.bikesched.event.Event;
import org.bikesched.event.EventWait;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.JobStore;
import org.bikesched.internal.database.StepStore;
import org.bikesched.internal.event.DefaultEvent;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.Job;
import org.bikesched.job.JobFailure;
import org.bikesched.job.Schedule;
import org.bikesched.job.Step;
import org.bikesched.job.StepBuilder;
import org.bikesched.job.StepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The {@link Job} implementation: a job claimed from {@code bikesched_jobs} for execution on this
 * node. Besides the handler API it carries what bikesched itself needs to schedule, time out and
 * record the job (priority, schedule time, timeout, cancellation state).
 *
 * <p>A job claimed to be compensated runs its handler in compensation mode: steps and event waits
 * only return what they stored, completed steps register their compensations (latest first), and
 * the first one that has not completed ends the replay with {@link CompensationReplayEnded}.
 */
public final class DefaultJob implements Job {

  private static final Logger LOG = LoggerFactory.getLogger(Job.class);

  private final Database database;
  private final UUID id;
  private final String type;
  private final String payload;
  private final int priority;
  private final Instant scheduledAt;
  private final int attempts;
  private final int maxAttempts;
  private final Duration timeout;
  private final String schedule;
  private final Instant fixedRateAt;
  private final boolean compensating;
  private final int compensationAttempts;
  private final boolean cancelRequested;
  private final String cancelRequestMessage;

  /** Compensations registered while replaying for compensation, latest first. */
  private final Deque<PendingCompensation> compensations = new ConcurrentLinkedDeque<>();

  // Set by the handler, read by bikesched after the handler returned (the handler may use other
  // threads).
  private volatile boolean cancelled;
  private volatile String cancelMessage;
  private volatile DefaultJobFailure failure;
  private volatile JobSuspended.Suspension suspension;
  private volatile JobAttempt attempt;
  private volatile Duration defaultEventTimeout;

  /** A job claimed by this node, accessing its steps and events through the database. */
  public DefaultJob(Database database, JobStore.ClaimedJob claimed) {
    this.database = Objects.requireNonNull(database, "database");
    this.id = claimed.id();
    this.type = claimed.type();
    this.payload = claimed.payload();
    this.priority = claimed.priority();
    this.scheduledAt = claimed.scheduledAt();
    this.attempts = claimed.attempts();
    this.maxAttempts = claimed.maxAttempts();
    this.timeout = claimed.timeout();
    this.schedule = claimed.schedule();
    this.fixedRateAt = claimed.fixedRateAt();
    this.compensating = claimed.compensatedState() != null;
    this.compensationAttempts = claimed.compensationAttempts();
    this.cancelRequested = claimed.cancelRequested();
    this.cancelRequestMessage = claimed.cancelMessage();
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public String type() {
    return type;
  }

  @Override
  public int attempts() {
    return attempts;
  }

  @Override
  public int maxAttempts() {
    return maxAttempts;
  }

  @Override
  public String payload() {
    return payload;
  }

  @Override
  public <T> T payload(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return payload == null ? null : database.jsonMapper().fromJson(payload, type);
  }

  /**
   * The description of the recurring job's schedule this job is an occurrence of, {@code null} for
   * a one-off job.
   */
  String schedule() {
    return schedule;
  }

  /**
   * The planned run of an occurrence whose schedule {@link Schedule#isFixedRate() is fixed-rate},
   * {@code null} otherwise.
   */
  Instant fixedRateAt() {
    return fixedRateAt;
  }

  @Override
  public boolean isCompensating() {
    return compensating;
  }

  /** The number of compensation attempts including the current one (while compensating). */
  int compensationAttempts() {
    return compensationAttempts;
  }

  /** Whether the job's cancellation was requested via {@code Bikesched.cancel}. */
  boolean cancelRequested() {
    return cancelRequested;
  }

  /** The message of the requested cancellation, {@code null} if none. */
  String cancelRequestMessage() {
    return cancelRequestMessage;
  }

  /**
   * A compensation registered while replaying the handler for compensation.
   *
   * @param stepName the step to compensate
   * @param call runs the compensation with the step's stored result
   */
  record PendingCompensation(String stepName, StepCall<Void> call) {}

  /** The compensations registered by the replay, in the order to run them (latest step first). */
  List<PendingCompensation> compensations() {
    return List.copyOf(compensations);
  }

  /**
   * Runs a compensation and records it: the step becomes {@code COMPENSATED}.
   *
   * @throws Exception the exception of the compensation, after recording it with the step
   */
  void compensate(PendingCompensation compensation) throws Exception {
    String name = compensation.stepName();
    Runnable restoreStepName = putStepName(name);
    long start = System.nanoTime();
    try {
      LOG.debug("Compensating step {} of job {}", name, id);
      try {
        compensation.call().call();
      } catch (Exception e) {
        LOG.debug(
            "Compensating step {} of job {} FAILED in {} ms: {}",
            name,
            id,
            millisSince(start),
            e.toString());
        try {
          database.steps().failCompensation(id, name, e.toString());
        } catch (RuntimeException recording) {
          e.addSuppressed(recording);
        }
        throw e;
      }
      database.steps().compensateStep(id, name);
      LOG.debug("Step {} of job {} COMPENSATED in {} ms", name, id, millisSince(start));
    } finally {
      restoreStepName.run();
    }
  }

  /** Higher priorities are claimed first. */
  int priority() {
    return priority;
  }

  /** The earliest time the job may run. */
  Instant scheduledAt() {
    return scheduledAt;
  }

  /** The timeout set when the job was scheduled; empty if the node default (if any) applies. */
  Optional<Duration> timeout() {
    return Optional.ofNullable(timeout);
  }

  @Override
  public void cancel(String message) {
    this.cancelMessage = message;
    this.cancelled = true;
  }

  /** Whether the handler cancelled the job. */
  boolean isCancelled() {
    return cancelled;
  }

  /** The message passed to {@link #cancel(String)}, {@code null} if none. */
  String cancelMessage() {
    return cancelMessage;
  }

  @Override
  public JobFailure fail(String message) {
    var failure = new DefaultJobFailure(message);
    this.failure = failure;
    return failure;
  }

  /** The failure the handler marked the attempt with, {@code null} if none. */
  DefaultJobFailure failure() {
    return failure;
  }

  @Override
  public void updateTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
    JobAttempt running = attempt;
    if (running == null) {
      throw new IllegalStateException("Job " + id + " is not running");
    }
    running.startTimeout(timeout);
    LOG.debug("Timeout of job {} updated to {} from now", id, timeout);
  }

  /** Sets the node's default timeout of waits for events without their own timeout. */
  void defaultEventTimeout(Duration timeout) {
    this.defaultEventTimeout = timeout;
  }

  /** The node's default timeout of waits for events, {@code null} for none. */
  Duration defaultEventTimeout() {
    return defaultEventTimeout;
  }

  /** Connects the job to its running attempt, enabling {@link #updateTimeout(Duration)}. */
  void attach(JobAttempt attempt) {
    this.attempt = attempt;
  }

  @Override
  public <T> T step(String name, Class<T> resultType, StepHandler<T> handler) throws Exception {
    return step(name, resultType, handler, null);
  }

  /** Runs a step, with a compensation or without ({@code null}). */
  <T> T step(
      String name,
      Class<T> resultType,
      StepHandler<T> handler,
      CompensationHandler<? super T> compensation)
      throws Exception {
    Objects.requireNonNull(resultType, "resultType");
    JsonMapper mapper = database.jsonMapper();
    return runStep(
        name, handler, mapper::toJson, json -> mapper.fromJson(json, resultType), compensation);
  }

  @Override
  public <T> StepBuilder<T> newStep(String name, Class<T> resultType) {
    requireName(name, "step name");
    Objects.requireNonNull(resultType, "resultType");
    return new DefaultStepBuilder<>(this, name, resultType);
  }

  private <T> T runStep(
      String name,
      StepHandler<T> handler,
      Function<? super T, String> toJson,
      Function<String, T> fromJson,
      CompensationHandler<? super T> compensation)
      throws Exception {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(handler, "handler");
    if (name.isBlank()) {
      throw new IllegalArgumentException("step name must not be blank");
    }
    Runnable restoreStepName = putStepName(name);
    try {
      var completed = database.steps().findCompletedStep(id, name);
      if (compensating) {
        if (completed.isEmpty()) {
          throw new CompensationReplayEnded(name);
        }
        T result = read(completed.get(), fromJson);
        if (compensation != null && !completed.get().compensated()) {
          Step step = new Step(id, name, completed.get().attempts());
          compensations.push(
              new PendingCompensation(
                  name,
                  () -> {
                    compensation.handle(step, result);
                    return null;
                  }));
        }
        return result;
      }
      if (completed.isEmpty()) {
        var stepAttempt = database.steps().startStep(id, name);
        if (stepAttempt.isPresent()) {
          return executeStep(name, stepAttempt.get(), handler, toJson, compensation != null);
        }
        completed = database.steps().findCompletedStep(id, name); // completed in the meantime
      }
      LOG.debug("Step {} of job {} already completed, reusing its result", name, id);
      return completed.isEmpty() ? null : read(completed.get(), fromJson);
    } finally {
      restoreStepName.run();
    }
  }

  private static <T> T read(StepStore.CompletedStep step, Function<String, T> fromJson) {
    return step.result() == null ? null : fromJson.apply(step.result());
  }

  /**
   * Puts the step name into the logging context.
   *
   * @return restores the previous step name (of an enclosing step), or removes it if there was none
   */
  private static Runnable putStepName(String name) {
    String outerStep = MDC.get(BikeschedLogContext.STEP_NAME);
    MDC.put(BikeschedLogContext.STEP_NAME, name);
    return () -> {
      if (outerStep == null) {
        MDC.remove(BikeschedLogContext.STEP_NAME);
      } else {
        MDC.put(BikeschedLogContext.STEP_NAME, outerStep);
      }
    };
  }

  @Override
  public EventWait waitForEvent(String stepName, String eventName, String eventKey) {
    requireName(stepName, "step name");
    requireName(eventName, "event name");
    Objects.requireNonNull(eventKey, "eventKey");
    return new DefaultEventWait(this, stepName, eventName, eventKey);
  }

  /** A call run with the step name in the logging context. */
  @FunctionalInterface
  interface StepCall<R> {
    R call() throws Exception;
  }

  /** Runs the call with the step name in the logging context (event and timeout handlers). */
  <R> R inStep(String stepName, StepCall<R> call) throws Exception {
    Runnable restoreStepName = putStepName(stepName);
    try {
      return call.call();
    } finally {
      restoreStepName.run();
    }
  }

  /** The step the handler suspended the job at, {@code null} if it did not wait for an event. */
  JobSuspended.Suspension suspension() {
    return suspension;
  }

  /**
   * Waits for an event as a step: returns it if received (now or in an earlier run), {@code null}
   * if the wait timed out, or suspends the job by throwing {@link JobSuspended}. The names were
   * validated by {@link #waitForEvent(String, String, String)}.
   */
  Event receiveEvent(String stepName, String eventName, String eventKey, Duration timeout) {
    Runnable restoreStepName = putStepName(stepName);
    try {
      if (compensating) {
        // Replay only: return what the wait stored, never start or time out a wait.
        var step = database.steps().findStep(id, stepName);
        String state = step.map(StepStore.StoredStep::state).orElse("");
        return switch (state) {
          case "SUCCEEDED" ->
              new DefaultEvent(eventName, eventKey, step.get().result(), database.jsonMapper());
          case "TIMED_OUT" -> null;
          default -> throw new CompensationReplayEnded(stepName);
        };
      }
      var wait = database.events().awaitEvent(id, stepName, eventName, eventKey, timeout);
      switch (wait.state()) {
        case "SUCCEEDED" -> {
          LOG.debug("Step {} of job {} received event {}/{}", stepName, id, eventName, eventKey);
          return new DefaultEvent(eventName, eventKey, wait.payload(), database.jsonMapper());
        }
        case "TIMED_OUT" -> {
          LOG.debug(
              "Step {} of job {} timed out waiting for event {}/{}",
              stepName,
              id,
              eventName,
              eventKey);
          return null;
        }
        default -> {
          var waiting =
              new JobSuspended.Suspension(stepName, eventName, eventKey, wait.waitUntil());
          suspension = waiting;
          LOG.debug(
              "Step {} of job {} waiting for event {}/{}{}, suspending the job",
              stepName,
              id,
              eventName,
              eventKey,
              wait.waitUntil() == null ? "" : " until " + wait.waitUntil());
          throw new JobSuspended(waiting);
        }
      }
    } finally {
      restoreStepName.run();
    }
  }

  private static void requireName(String name, String what) {
    Objects.requireNonNull(name, what);
    if (name.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
  }

  private <T> T executeStep(
      String name,
      int stepAttempt,
      StepHandler<T> handler,
      Function<? super T, String> toJson,
      boolean compensable)
      throws Exception {
    LOG.debug("Executing step {} of job {} (attempt {})", name, id, stepAttempt);
    long start = System.nanoTime();
    T result;
    try {
      result = handler.handle(new Step(id, name, stepAttempt));
      database
          .steps()
          .completeStep(id, name, result == null ? null : toJson.apply(result), compensable);
    } catch (Exception e) {
      recordStepFailure(name, stepAttempt, e, start);
      throw e;
    }
    LOG.debug(
        "Step {} of job {} (attempt {}) SUCCEEDED in {} ms",
        name,
        id,
        stepAttempt,
        millisSince(start));
    return result;
  }

  private void recordStepFailure(String name, int stepAttempt, Exception failure, long start) {
    LOG.debug(
        "Step {} of job {} (attempt {}) FAILED in {} ms: {}",
        name,
        id,
        stepAttempt,
        millisSince(start),
        failure.toString());
    try {
      database.steps().failStep(id, name, failure.toString());
    } catch (RuntimeException e) {
      failure.addSuppressed(e);
    }
  }

  private static long millisSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
  }

  @Override
  public String toString() {
    return "Job[id="
        + id
        + ", type="
        + type
        + ", payload="
        + payload
        + ", priority="
        + priority
        + ", scheduledAt="
        + scheduledAt
        + ", attempts="
        + attempts
        + ", maxAttempts="
        + maxAttempts
        + (cancelled ? ", cancelled" + (cancelMessage == null ? "" : "=" + cancelMessage) : "")
        + "]";
  }
}
