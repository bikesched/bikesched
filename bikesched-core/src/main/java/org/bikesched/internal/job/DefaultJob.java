// SPDX-License-Identifier: Apache-2.0
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
import java.util.function.Supplier;
import org.bikesched.BikeschedLogContext;
import org.bikesched.JsonMapper;
import org.bikesched.event.EventType;
import org.bikesched.event.EventWait;
import org.bikesched.event.TypedEventWait;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.JobLock;
import org.bikesched.internal.database.JobLockLostException;
import org.bikesched.internal.database.JobStore;
import org.bikesched.internal.database.StepStore;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.Job;
import org.bikesched.job.JobFailure;
import org.bikesched.job.Recurrence;
import org.bikesched.job.Step;
import org.bikesched.job.StepBuilder;
import org.bikesched.job.StepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The {@link Job} implementation: a job claimed from {@code bikesched_jobs} for execution on this
 * node. Besides the handler API it carries what bikesched itself needs to schedule, time out and
 * record the job (priority, scheduled time, timeout, cancellation state).
 *
 * <p>A job claimed to be compensated runs its handler in compensation mode: steps and event waits
 * only return what they stored, completed steps register their compensations (latest first), and
 * the first one that has not completed ends the replay with {@link CompensationReplayEnded}.
 */
public final class DefaultJob implements Job {

  private static final Logger LOG = LoggerFactory.getLogger(Job.class);

  private final Database database;
  private final UUID id;

  /** The lock of this claim: the attempt's writes are fenced by it. */
  private final JobLock lock;

  private final String type;
  private final String payload;
  private final int priority;
  private final Instant scheduledAt;
  private final int attempts;
  private final int maxAttempts;
  private final Duration timeout;
  private final String recurrence;
  private final Instant fixedRateAt;
  private final boolean compensating;
  private final int compensationAttempts;
  private final boolean cancelRequested;
  private final String cancelRequestMessage;

  /** Compensations registered while replaying for compensation, latest first. */
  private final Deque<PendingCompensation> compensations = new ConcurrentLinkedDeque<>();

  /** Rejects steps and event waits while a step handler or compensation runs. */
  private final StepGuard stepGuard;

  // Set by the handler, read by bikesched after the handler returned (the handler may use other
  // threads).
  private volatile boolean cancelled;
  private volatile String cancelMessage;
  private volatile DefaultJobFailure failure;
  private volatile JobSuspended.Suspension suspension;
  private volatile JobAttempt attempt;
  private volatile Duration defaultEventTimeout;
  private volatile boolean lockLost;

  /** A job claimed by this node, accessing its steps and events through the database. */
  public DefaultJob(Database database, JobStore.ClaimedJob claimed) {
    this.database = Objects.requireNonNull(database, "database");
    this.id = claimed.id();
    this.lock = Objects.requireNonNull(claimed.lock(), "lock");
    this.type = claimed.type();
    this.payload = claimed.payload();
    this.priority = claimed.priority();
    this.scheduledAt = claimed.scheduledAt();
    this.attempts = claimed.attempts();
    this.maxAttempts = claimed.maxAttempts();
    this.timeout = claimed.timeout();
    this.recurrence = claimed.recurrence();
    this.fixedRateAt = claimed.fixedRateAt();
    this.compensating = claimed.compensatedState() != null;
    this.compensationAttempts = claimed.compensationAttempts();
    this.cancelRequested = claimed.cancelRequested();
    this.cancelRequestMessage = claimed.cancelMessage();
    this.stepGuard = new StepGuard(id);
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
   * The description of the recurring job's recurrence this job is an occurrence of, {@code null}
   * for a one-off job.
   */
  String recurrence() {
    return recurrence;
  }

  /**
   * The planned run of an occurrence whose recurrence {@link Recurrence#isFixedRate() is
   * fixed-rate}, {@code null} otherwise.
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
        stepGuard.run("the compensation of step '" + name + "'", compensation.call());
      } catch (Exception e) {
        LOG.debug(
            "Compensating step {} of job {} FAILED in {} ms: {}",
            name,
            id,
            millisSince(start),
            e.toString());
        try {
          fenced(() -> database.steps().failCompensation(id, lock, name, e.toString()));
        } catch (RuntimeException recording) {
          e.addSuppressed(recording);
        }
        throw e;
      }
      fenced(() -> database.steps().compensateStep(id, lock, name));
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
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(resultType, "resultType");
    stepGuard.requireNoneRunning("create step '" + name + "'");
    return new DefaultStepBuilder<>(this, name, resultType);
  }

  private <T> T runStep(
      String name,
      StepHandler<T> handler,
      Function<? super T, String> toJson,
      Function<String, T> fromJson,
      CompensationHandler<? super T> compensation)
      throws Exception {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(handler, "handler");
    stepGuard.requireNoneRunning("run step '" + name + "'");
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
        var stepAttempt = fenced(() -> database.steps().startStep(id, lock, name));
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
    ValidationUtil.requireName(stepName, "step name");
    ValidationUtil.requireName(eventName, "event name");
    ValidationUtil.requireKey(eventKey, "event key");
    stepGuard.requireNoneRunning("wait for an event as step '" + stepName + "'");
    return new DefaultEventWait(this, stepName, eventName, eventKey);
  }

  @Override
  public <T> TypedEventWait<T> waitForEvent(
      String stepName, EventType<T> eventType, String eventKey) {
    Objects.requireNonNull(eventType, "eventType");
    return new DefaultTypedEventWait<>(
        waitForEvent(stepName, eventType.name(), eventKey), eventType.payloadType());
  }

  @Override
  public <T> T awaitEvent(String stepName, EventType<T> eventType, String eventKey) {
    Objects.requireNonNull(eventType, "eventType");
    return waitForEvent(stepName, eventType.name(), eventKey).receive(eventType.payloadType());
  }

  @Override
  public void sleep(String stepName, Duration duration) {
    ValidationUtil.requireName(stepName, "step name");
    Objects.requireNonNull(duration, "duration");
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("duration must be positive: " + duration);
    }
    sleepStep(stepName, duration, null);
  }

  @Override
  public void sleepUntil(String stepName, Instant time) {
    ValidationUtil.requireName(stepName, "step name");
    Objects.requireNonNull(time, "time");
    sleepStep(stepName, null, time);
  }

  /**
   * Sleeps as a step: returns once the sleep is over (in this or an earlier run), or suspends the
   * job by throwing {@link JobSuspended}. Exactly one of {@code duration} and {@code until} is set.
   */
  private void sleepStep(String stepName, Duration duration, Instant until) {
    stepGuard.requireNoneRunning("sleep as step '" + stepName + "'");
    Runnable restoreStepName = putStepName(stepName);
    try {
      if (compensating) {
        // Replay only: return after a completed sleep, never start or continue one.
        var state = database.steps().findStep(id, stepName).map(StepStore.StoredStep::state);
        if (state.filter("SUCCEEDED"::equals).isPresent()) {
          return;
        }
        throw new CompensationReplayEnded(stepName);
      }
      var sleep = fenced(() -> database.steps().sleep(id, lock, stepName, duration, until));
      if (sleep.awake()) {
        LOG.debug("Step {} of job {} slept until {}", stepName, id, sleep.wakeUpAt());
        return;
      }
      var sleeping = new JobSuspended.SleepSuspension(stepName, sleep.wakeUpAt());
      suspension = sleeping;
      LOG.debug(
          "Step {} of job {} sleeping until {}, suspending the job",
          stepName,
          id,
          sleep.wakeUpAt());
      throw new JobSuspended(sleeping);
    } finally {
      restoreStepName.run();
    }
  }

  /** A call run with the step name in the logging context. */
  @FunctionalInterface
  interface StepCall<R> {
    R call() throws Exception;
  }

  /** The step the handler suspended the job at, {@code null} if it did not wait or sleep. */
  JobSuspended.Suspension suspension() {
    return suspension;
  }

  /**
   * Waits for an event as a step and returns the received event's payload (stored as the step's
   * result when the event arrived, in this or an earlier run), the timeout handler's result if the
   * wait timed out, or suspends the job by throwing {@link JobSuspended}. The names were validated
   * by {@link #waitForEvent(String, String, String)}.
   *
   * @param read reads the payload (JSON text, may be {@code null})
   * @param onTimeout produces the result if the wait timed out; runs on every run that reaches the
   *     timed-out wait, its result is not stored
   */
  <T> T awaitEventStep(
      String stepName,
      String eventName,
      String eventKey,
      Duration timeout,
      Function<String, ? extends T> read,
      StepCall<? extends T> onTimeout)
      throws Exception {
    stepGuard.requireNoneRunning("wait for an event as step '" + stepName + "'");
    Runnable restoreStepName = putStepName(stepName);
    try {
      if (compensating) {
        // Replay only: return what the wait stored, never start or time out a wait.
        var step = database.steps().findStep(id, stepName);
        String state = step.map(StepStore.StoredStep::state).orElse("");
        return switch (state) {
          case "SUCCEEDED" -> read.apply(step.get().result());
          case "TIMED_OUT" -> onTimeout.call();
          default -> throw new CompensationReplayEnded(stepName);
        };
      }
      var wait =
          fenced(
              () -> database.events().awaitEvent(id, lock, stepName, eventName, eventKey, timeout));
      switch (wait.state()) {
        case "SUCCEEDED" -> {
          LOG.debug("Step {} of job {} received event {}/{}", stepName, id, eventName, eventKey);
          return read.apply(wait.json());
        }
        case "TIMED_OUT" -> {
          LOG.debug(
              "Step {} of job {} timed out waiting for event {}/{}",
              stepName,
              id,
              eventName,
              eventKey);
          return onTimeout.call();
        }
        default -> {
          var waiting =
              new JobSuspended.EventSuspension(stepName, eventName, eventKey, wait.waitUntil());
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

  /** Reads a stored result (JSON text, may be {@code null}) as the given type. */
  <T> Function<String, T> reader(Class<T> type) {
    return json -> json == null ? null : database.jsonMapper().fromJson(json, type);
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
      var step = new Step(id, name, stepAttempt);
      result = stepGuard.run("step '" + name + "'", () -> handler.handle(step));
      String json = result == null ? null : toJson.apply(result);
      fenced(() -> database.steps().completeStep(id, lock, name, json, compensable));
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
      fenced(() -> database.steps().failStep(id, lock, name, failure.toString()));
    } catch (RuntimeException e) {
      failure.addSuppressed(e);
    }
  }

  /** The lock of this claim, fencing the attempt's writes. */
  JobLock lock() {
    return lock;
  }

  /**
   * Whether a step write of this attempt found the job no longer running in its claim: it was
   * released (e.g. after missed heartbeats) and may run elsewhere, so the attempt must not record
   * an outcome. Set even if the handler caught the {@link JobLockLostException}.
   */
  boolean isLockLost() {
    return lockLost;
  }

  /** Runs a fenced step write, remembering a lost job lock. */
  private <T> T fenced(Supplier<T> write) {
    try {
      return write.get();
    } catch (JobLockLostException e) {
      lockLost = true;
      throw e;
    }
  }

  private void fenced(Runnable write) {
    fenced(
        () -> {
          write.run();
          return null;
        });
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
