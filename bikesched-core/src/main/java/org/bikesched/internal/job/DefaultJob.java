// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bikesched.BikeschedException;
import org.bikesched.BikeschedLogContext;
import org.bikesched.JsonMapper;
import org.bikesched.RetryBackoff;
import org.bikesched.event.EventTimeoutException;
import org.bikesched.event.EventType;
import org.bikesched.event.EventWait;
import org.bikesched.event.StepEventBuilder;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.EventStore;
import org.bikesched.internal.database.JobLock;
import org.bikesched.internal.database.JobLockLostException;
import org.bikesched.internal.database.JobStore;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.database.StepStore;
import org.bikesched.internal.event.DefaultStepEventBuilder;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.Job;
import org.bikesched.job.JobFailure;
import org.bikesched.job.JobResult;
import org.bikesched.job.JobType;
import org.bikesched.job.Poll;
import org.bikesched.job.PollBuilder;
import org.bikesched.job.PollHandler;
import org.bikesched.job.PollTimeoutException;
import org.bikesched.job.Recurrence;
import org.bikesched.job.Step;
import org.bikesched.job.StepBuilder;
import org.bikesched.job.StepFailureHandler;
import org.bikesched.job.StepHandler;
import org.bikesched.job.StepJobBuilder;
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
 * the first one that has not completed ends the replay with {@link CompensationReplayEnded}. A
 * child job step counts as completed once the child was started.
 */
public final class DefaultJob implements Job {

  private static final Logger LOG = LoggerFactory.getLogger(Job.class);

  private final Database database;
  private final JobScheduler scheduler;
  private final Duration defaultEventRetention;
  private final int failureHandlerAttempts;
  private final EventListener eventListener;
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
  private final String failingState;
  private final int failureAttempts;
  private final String lastError;
  private final boolean cancelRequested;
  private final String cancelRequestMessage;
  private final UUID parentJobId;

  /** Compensations registered while replaying for compensation, latest first. */
  private final Deque<PendingCompensation> compensations = new ConcurrentLinkedDeque<>();

  /** Rejects steps and event waits while a step handler or compensation runs. */
  private final StepGuard stepGuard;

  private final Set<String> eventPublishSteps = ConcurrentHashMap.newKeySet();
  private final Set<String> childJobSteps = ConcurrentHashMap.newKeySet();

  // Set by the handler, read by bikesched after the handler returned (the handler may use other
  // threads).
  private volatile boolean cancelled;
  private volatile String cancelMessage;
  private volatile DefaultJobFailure failure;
  private volatile Result result;
  private volatile JobSuspended.Suspension suspension;
  private volatile JobAttempt attempt;
  private volatile StepRetry stepRetry;
  private volatile boolean failureHandlingPending;
  private volatile Duration defaultEventTimeout;
  private volatile boolean lockLost;

  /**
   * A job claimed by this node, accessing its steps and events through the database.
   *
   * @param database the database
   * @param claimed the claimed job
   * @param defaultEventRetention the node's default event retention
   * @param failureHandlerAttempts how often a step's failure handler runs before it is given up
   * @param scheduler checks the handlers of the child jobs it starts
   * @param eventListener observes the events the job publishes
   */
  public DefaultJob(
      Database database,
      JobStore.ClaimedJob claimed,
      Duration defaultEventRetention,
      int failureHandlerAttempts,
      JobScheduler scheduler,
      EventListener eventListener) {
    this.database = Objects.requireNonNull(database, "database");
    this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.defaultEventRetention =
        Objects.requireNonNull(defaultEventRetention, "defaultEventRetention");
    this.failureHandlerAttempts = failureHandlerAttempts;
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
    this.compensating = claimed.compensatedState() != null && claimed.failingState() == null;
    this.compensationAttempts = claimed.compensationAttempts();
    this.failingState = claimed.failingState();
    this.failureAttempts = claimed.failureAttempts();
    this.lastError = claimed.lastError();
    this.cancelRequested = claimed.cancelRequested();
    this.cancelRequestMessage = claimed.cancelMessage();
    this.parentJobId = claimed.parentJobId();
    this.stepGuard = new StepGuard(id);
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public StepEventBuilder<Object> newEvent(String eventName, String eventKey) {
    ValidationUtil.requireName(eventName, "event name");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultStepEventBuilder<>(
        eventName, eventKey, database.jsonMapper(), this::publishEventStep);
  }

  @Override
  public <T> StepEventBuilder<T> newEvent(EventType<T> eventType, String eventKey) {
    Objects.requireNonNull(eventType, "eventType");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultStepEventBuilder<>(
        eventType.name(), eventKey, database.jsonMapper(), this::publishEventStep);
  }

  private UUID publishEventStep(
      String eventName,
      String eventKey,
      String eventPayload,
      Duration retention,
      String requestedStepName) {
    String stepName = requestedStepName == null ? "publish " + eventName : requestedStepName;
    if (requestedStepName == null
        && stepName.codePointCount(0, stepName.length()) > ValidationUtil.MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "Default event publishing step name is longer than "
              + ValidationUtil.MAX_NAME_LENGTH
              + " characters; set stepName(…) explicitly");
    }
    ValidationUtil.requireName(stepName, "step name");
    stepGuard.requireNoneRunning("publish an event as step '" + stepName + "'");
    if (!eventPublishSteps.add(stepName)) {
      throw new IllegalStateException(
          "Job "
              + id
              + " publishes more than once with step name '"
              + stepName
              + "'; set a unique stepName(…) for each publish");
    }
    if (compensating
        && database
            .steps()
            .findStep(id, stepName)
            .filter(step -> step.state().equals("SUCCEEDED"))
            .isEmpty()) {
      throw new CompensationReplayEnded(stepName);
    }
    Runnable restore = putStepName(stepName);
    try {
      EventStore.StepPublication published =
          database
              .events()
              .publishEventAsStep(
                  id,
                  lock,
                  stepName,
                  eventName,
                  eventKey,
                  eventPayload,
                  retention == null ? defaultEventRetention : retention,
                  compensating);
      if (published.publication() != null) {
        notifyPublished(eventName, published.publication());
      }
      return published.eventId();
    } finally {
      restore.run();
    }
  }

  /** Reports a committed publication; the listener's exceptions must not affect the job. */
  private void notifyPublished(String eventName, EventStore.Publication publication) {
    try {
      eventListener.onEventPublished(
          new EventPublication(
              publication.eventId(), eventName, EventSource.JOB, publication.received()));
    } catch (RuntimeException e) {
      LOG.warn("Event listener failed for event {}, ignoring it", publication.eventId(), e);
    }
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

  /** Whether the job was claimed to run its failure handler (it is {@code FAILING}). */
  boolean isFailing() {
    return failingState != null;
  }

  /**
   * The state the job ends in once its failure handler ran: {@code FAILED} or {@code
   * COMPENSATION_FAILED}; {@code null} unless {@link #isFailing() failing}.
   */
  String failingState() {
    return failingState;
  }

  /** The number of runs of the failure handler including the current one (while failing). */
  int failureAttempts() {
    return failureAttempts;
  }

  /** The stored description of the job's last failure, {@code null} if none. */
  String lastError() {
    return lastError;
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

  /** A result set by the handler: JSON text, {@code null} for a {@code null} result. */
  private record Result(String json) {}

  @Override
  public void complete(Object result) {
    this.result = new Result(result == null ? null : database.jsonMapper().toJson(result));
  }

  /** The result set by the handler as JSON text, {@code null} if none. */
  String resultJson() {
    Result current = result;
    return current == null ? null : current.json();
  }

  /** The job that started this one as a child, {@code null} if none. */
  UUID parentJobId() {
    return parentJobId;
  }

  @Override
  public StepJobBuilder<Object> newJob(String type) {
    ValidationUtil.requireName(type, "job type");
    requireChildrenAllowed(type);
    return new DefaultStepJobBuilder<>(this, type, Object.class, database.jsonMapper(), scheduler);
  }

  @Override
  public <T> StepJobBuilder<T> newJob(JobType<T> type) {
    Objects.requireNonNull(type, "type");
    requireChildrenAllowed(type.name());
    scheduler.requirePayloadType(type);
    return new DefaultStepJobBuilder<>(
        this, type.name(), type.payloadType(), database.jsonMapper(), scheduler);
  }

  private void requireChildrenAllowed(String type) {
    stepGuard.requireNoneRunning("start a child job of type " + type);
    if (recurrence != null) {
      throw new IllegalStateException(
          "Job "
              + id
              + " is an occurrence of recurring job "
              + this.type
              + " and cannot start child jobs: the next occurrence replaces it, so no single caller"
              + " waits for its children");
    }
  }

  /**
   * Starts a child job as a step, see {@link JobStore#startChildJob}; built by {@link
   * DefaultStepJobBuilder}.
   *
   * @param requestedStepName the step name, {@code null} for the default {@code start <type>}
   * @param child the child's attributes
   * @param compensation the step's compensation, receiving the child's id; {@code null} if none
   * @return the child's id, the same on every run
   */
  UUID startChildJob(
      String requestedStepName, NewJob child, CompensationHandler<? super UUID> compensation) {
    String stepName = requestedStepName == null ? "start " + child.type() : requestedStepName;
    if (requestedStepName == null
        && stepName.codePointCount(0, stepName.length()) > ValidationUtil.MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "Default child job step name is longer than "
              + ValidationUtil.MAX_NAME_LENGTH
              + " characters; set stepName(…) explicitly");
    }
    stepGuard.requireNoneRunning("start a child job as step '" + stepName + "'");
    if (!childJobSteps.add(stepName)) {
      throw new IllegalStateException(
          "Job "
              + id
              + " starts more than one child job with step name '"
              + stepName
              + "'; set a unique stepName(…) for each child");
    }
    Runnable restoreStepName = putStepName(stepName);
    try {
      if (compensating) {
        // Replay only: return the started child, registering the compensation, never start one.
        var step = database.steps().findChildJobStep(id, stepName);
        step.ifPresent(
            stored -> requireStepKind(stepName, stored.kind(), "CHILD_JOB", "a child job"));
        if (step.isEmpty()) {
          throw new CompensationReplayEnded(stepName);
        }
        UUID childJobId = step.get().childJobId();
        if (compensation != null && !step.get().state().equals("COMPENSATED")) {
          Step compensated = new Step(id, stepName, step.get().attempts());
          compensations.push(
              new PendingCompensation(
                  stepName,
                  () -> {
                    compensation.handle(compensated, childJobId);
                    return null;
                  }));
        }
        return childJobId;
      }
      var started =
          fenced(
              () -> database.jobs().startChildJob(id, lock, stepName, child, compensation != null));
      if (started.started()) {
        LOG.debug(
            "Step {} of job {} started child job {} of type {}",
            stepName,
            id,
            started.id(),
            child.type());
      } else {
        LOG.debug("Step {} of job {} already started child job {}", stepName, id, started.id());
      }
      return started.id();
    } finally {
      restoreStepName.run();
    }
  }

  @Override
  public <T> JobResult<T> waitForJob(UUID childJobId, Class<T> resultType) {
    return waitForChildJob(childJobId, resultType, null);
  }

  @Override
  public <T> JobResult<T> waitForJob(UUID childJobId, Class<T> resultType, Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
    return waitForChildJob(childJobId, resultType, timeout);
  }

  /**
   * Waits for a child job: returns its outcome (stored with its step when it ended), the timed-out
   * wait, or suspends the job by throwing {@link JobSuspended}.
   */
  private <T> JobResult<T> waitForChildJob(UUID childJobId, Class<T> resultType, Duration timeout) {
    Objects.requireNonNull(childJobId, "childJobId");
    Objects.requireNonNull(resultType, "resultType");
    stepGuard.requireNoneRunning("wait for job " + childJobId);
    if (compensating) {
      // Replay only: return what the wait stored, never start or time out a wait.
      var wait = database.steps().findChildWait(id, childJobId);
      return switch (wait.state()) {
        case "SUCCEEDED", "TIMED_OUT" -> jobResult(childJobId, wait, resultType);
        default -> throw new CompensationReplayEnded(wait.stepName());
      };
    }
    var wait = fenced(() -> database.steps().awaitChildJob(id, lock, childJobId, timeout));
    Runnable restoreStepName = putStepName(wait.stepName());
    try {
      switch (wait.state()) {
        case "SUCCEEDED", "TIMED_OUT" -> {
          LOG.debug(
              "Step {} of job {} {}",
              wait.stepName(),
              id,
              wait.state().equals("SUCCEEDED")
                  ? "received the outcome of job " + childJobId + ": " + wait.childState()
                  : "timed out waiting for job " + childJobId);
          return jobResult(childJobId, wait, resultType);
        }
        case "WAITING" -> {
          var waiting =
              new JobSuspended.JobSuspension(wait.stepName(), childJobId, wait.waitUntil());
          suspension = waiting;
          LOG.debug(
              "Step {} of job {} waiting for job {}{}, suspending the job",
              wait.stepName(),
              id,
              childJobId,
              wait.waitUntil() == null ? "" : " until " + wait.waitUntil());
          throw new JobSuspended(waiting);
        }
        default ->
            throw new IllegalStateException(
                "Step '"
                    + wait.stepName()
                    + "' of job "
                    + id
                    + " starting job "
                    + childJobId
                    + " is "
                    + wait.state()
                    + "; start the child again before waiting for it");
      }
    } finally {
      restoreStepName.run();
    }
  }

  /**
   * The outcome of a child job as stored with its step ({@code SUCCEEDED} or {@code TIMED_OUT}).
   */
  private <T> JobResult<T> jobResult(UUID childJobId, StepStore.ChildWait wait, Class<T> type) {
    if (wait.state().equals("TIMED_OUT")) {
      return new JobResult.TimedOut<>(childJobId);
    }
    return switch (wait.childState()) {
      case "SUCCEEDED" -> new JobResult.Succeeded<>(childJobId, reader(type).apply(wait.result()));
      case "FAILED" -> new JobResult.Failed<>(childJobId, wait.error());
      case "CANCELLED" -> new JobResult.Cancelled<>(childJobId, wait.error());
      case "COMPENSATION_FAILED" -> new JobResult.CompensationFailed<>(childJobId, wait.error());
      default ->
          throw new IllegalStateException(
              "Unknown state " + wait.childState() + " of child job " + childJobId);
    };
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
    return step(name, resultType, handler, null, null, null);
  }

  /** A failed step's own retry backoff and its number of failed attempts. */
  record StepRetry(RetryBackoff backoff, int failedAttempts) {}

  /** The retry backoff of the step that made the attempt fail, {@code null} if none. */
  StepRetry stepRetry() {
    return stepRetry;
  }

  /**
   * Runs a step, with a compensation or without ({@code null}), and with its own retry backoff or
   * without ({@code null}).
   */
  <T> T step(
      String name,
      Class<T> resultType,
      StepHandler<T> handler,
      CompensationHandler<? super T> compensation,
      RetryBackoff retryBackoff,
      StepFailureHandler<T> failureHandler)
      throws Exception {
    Objects.requireNonNull(resultType, "resultType");
    JsonMapper mapper = database.jsonMapper();
    StepHandler<T> run = handler;
    if (retryBackoff != null && handler != null) {
      run =
          step -> {
            try {
              T result = handler.handle(step);
              stepRetry = null;
              return result;
            } catch (Exception e) {
              stepRetry = new StepRetry(retryBackoff, step.attempts());
              throw e;
            }
          };
    }
    return runStep(
        name,
        run,
        mapper::toJson,
        json -> mapper.fromJson(json, resultType),
        compensation,
        failureHandler);
  }

  /**
   * Whether the failure handler of a failed step threw (or could not be recorded): the job gets an
   * extra attempt to run it again, even if its attempts are used up.
   */
  boolean isFailureHandlingPending() {
    return failureHandlingPending;
  }

  @Override
  public <T> StepBuilder<T> newStep(String name, Class<T> resultType) {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(resultType, "resultType");
    stepGuard.requireNoneRunning("create step '" + name + "'");
    return new DefaultStepBuilder<>(this, name, resultType);
  }

  @Override
  public <T> PollBuilder<T> newPoll(String name, Class<T> resultType, Duration interval) {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(interval, "interval");
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive: " + interval);
    }
    return newPoll(name, resultType, RetryBackoff.fixed(interval));
  }

  @Override
  public <T> PollBuilder<T> newPoll(String name, Class<T> resultType, RetryBackoff interval) {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(resultType, "resultType");
    Objects.requireNonNull(interval, "interval");
    stepGuard.requireNoneRunning("create poll '" + name + "'");
    return new DefaultPollBuilder<>(this, name, resultType, interval);
  }

  private <T> T runStep(
      String name,
      StepHandler<T> handler,
      Function<? super T, String> toJson,
      Function<String, T> fromJson,
      CompensationHandler<? super T> compensation,
      StepFailureHandler<T> failureHandler)
      throws Exception {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(handler, "handler");
    stepGuard.requireNoneRunning("run step '" + name + "'");
    Runnable restoreStepName = putStepName(name);
    try {
      var completed = database.steps().findCompletedStep(id, name);
      completed.ifPresent(step -> requireStepKind(name, step.kind(), "STEP", "a regular step"));
      if (compensating) {
        if (completed.isEmpty()) {
          throw new CompensationReplayEnded(name);
        }
        T result = read(completed.get(), fromJson);
        if (compensation != null
            && completed.get().compensable()
            && !completed.get().compensated()) {
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
        if (failureHandler != null) {
          var failingError = database.steps().findFailingError(id, name, "STEP");
          if (failingError.isPresent()) {
            // The handler threw or was lost in an earlier attempt: run it again, then fail the job
            // (unless it resolves the step).
            var failure =
                new BikeschedException("Step '" + name + "' failed: " + failingError.get());
            Resolved<T> resolved =
                handleFailingStep(name, failureHandler, toJson, failingError.get(), failure);
            if (resolved != null) {
              return resolved.result();
            }
            throw failure;
          }
        }
        var stepAttempt = fenced(() -> database.steps().startStep(id, lock, name));
        if (stepAttempt.isPresent()) {
          return executeStep(
              name, stepAttempt.get(), handler, toJson, compensation != null, failureHandler);
        }
        // Completed in the meantime, or a step of another kind (e.g. still waiting for an event).
        database
            .steps()
            .findStep(id, name)
            .ifPresent(step -> requireStepKind(name, step.kind(), "STEP", "a regular step"));
        completed = database.steps().findCompletedStep(id, name);
      }
      LOG.debug("Step {} of job {} already completed, reusing its result", name, id);
      return completed.isEmpty() ? null : read(completed.get(), fromJson);
    } finally {
      restoreStepName.run();
    }
  }

  /** Rejects a step name already used by another kind of step of this job. */
  private void requireStepKind(String name, String kind, String expected, String expectedText) {
    if (!kind.equals(expected)) {
      throw new IllegalStateException(
          "Step '"
              + name
              + "' of job "
              + id
              + " is "
              + StepStore.describeKind(kind)
              + ", not "
              + expectedText
              + "; step names must be unique within a job");
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
  public <T> EventWait<T> waitForEvent(
      String stepName, String eventName, String eventKey, Class<T> payloadType) {
    ValidationUtil.requireName(stepName, "step name");
    ValidationUtil.requireName(eventName, "event name");
    ValidationUtil.requireKey(eventKey, "event key");
    Objects.requireNonNull(payloadType, "payloadType");
    stepGuard.requireNoneRunning("wait for an event as step '" + stepName + "'");
    return new DefaultEventWait<>(this, stepName, eventName, eventKey, payloadType);
  }

  @Override
  public <T> EventWait<T> waitForEvent(String stepName, EventType<T> eventType, String eventKey) {
    Objects.requireNonNull(eventType, "eventType");
    return waitForEvent(stepName, eventType.name(), eventKey, eventType.payloadType());
  }

  @Override
  public <T> T awaitEvent(String stepName, EventType<T> eventType, String eventKey) {
    return waitForEvent(stepName, eventType, eventKey).receive();
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
        var stored = database.steps().findStep(id, stepName);
        stored.ifPresent(step -> requireStepKind(stepName, step.kind(), "SLEEP", "a sleep"));
        if (stored.map(StepStore.StoredStep::state).filter("SUCCEEDED"::equals).isPresent()) {
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

  /**
   * Polls as a step: returns the ready value (checked in this run, or stored by an earlier one) or
   * the fallback its failure handler resolved it with, or suspends the job by throwing {@link
   * JobSuspended} until the next check. The arguments were validated by the {@link
   * DefaultPollBuilder}.
   *
   * @param timeout how long to poll at most, counted from the first check; {@code null} for no
   *     limit
   * @param failureHandler handles a timeout, and a check failing in the job's last attempt; {@code
   *     null} to fail the job (with a {@link PollTimeoutException} after a timeout)
   */
  <T> T poll(
      String name,
      Class<T> resultType,
      RetryBackoff interval,
      Duration timeout,
      StepFailureHandler<T> failureHandler,
      PollHandler<T> handler)
      throws Exception {
    stepGuard.requireNoneRunning("poll as step '" + name + "'");
    Runnable restoreStepName = putStepName(name);
    try {
      if (compensating) {
        // Replay only: return what the poll stored, never start a check.
        var stored = database.steps().findStep(id, name);
        stored.ifPresent(step -> requireStepKind(name, step.kind(), "POLL", "a poll"));
        String state = stored.map(StepStore.StoredStep::state).orElse("");
        if (state.equals("SUCCEEDED")) {
          return reader(resultType).apply(stored.get().result());
        }
        throw new CompensationReplayEnded(name); // it failed or timed out: the job ended there
      }
      Function<? super T, String> toJson = database.jsonMapper()::toJson;
      var poll = fenced(() -> database.steps().startPoll(id, lock, name, timeout));
      switch (poll.state()) {
        case "SUCCEEDED" -> {
          LOG.debug("Step {} of job {} already completed, reusing its result", name, id);
          return reader(resultType).apply(poll.result());
        }
        case "TIMED_OUT" -> {
          return timedOut(name, failureHandler, toJson, pollTimeout(name, poll.checks(), timeout));
        }
        case "FAILING" -> {
          String error = database.steps().findFailingError(id, name, "POLL").orElse("");
          return stillFailing(
              name,
              failureHandler,
              toJson,
              error,
              new BikeschedException("Poll '" + name + "' failed: " + error));
        }
        case "WAITING" -> {
          LOG.debug(
              "Step {} of job {}: next check not due until {}, suspending the job",
              name,
              id,
              poll.waitUntil());
          throw suspendPoll(
              new JobSuspended.PollSuspension(name, poll.checks(), poll.waitUntil(), true));
        }
        default -> {
          return check(name, interval, timeout, failureHandler, toJson, handler, poll);
        }
      }
    } finally {
      restoreStepName.run();
    }
  }

  /** Runs one check of a poll, see {@link #poll}. */
  private <T> T check(
      String name,
      RetryBackoff interval,
      Duration timeout,
      StepFailureHandler<T> failureHandler,
      Function<? super T, String> toJson,
      PollHandler<T> handler,
      StepStore.PollStep poll)
      throws Exception {
    int check = poll.checks();
    LOG.debug("Checking poll {} of job {} (check {})", name, id, check);
    long start = System.nanoTime();
    Poll.NotReady<T> notReady;
    try {
      var step = new Step(id, name, check);
      Poll<T> outcome = stepGuard.run("poll '" + name + "'", () -> handler.handle(step));
      if (outcome == null) {
        throw new IllegalStateException(
            "The handler of poll '"
                + name
                + "' returned null; return Poll.ready(value) or Poll.notReady()");
      }
      switch (outcome) {
        case Poll.Ready<T> ready -> {
          T value = ready.value();
          String json = value == null ? null : database.jsonMapper().toJson(value);
          fenced(() -> database.steps().completePoll(id, lock, name, json, check));
          LOG.debug(
              "Step {} of job {} ready after check {} in {} ms",
              name,
              id,
              check,
              millisSince(start));
          return value;
        }
        case Poll.NotReady<T> later -> notReady = later;
      }
    } catch (Exception e) {
      if (failureHandler != null && attempts >= maxAttempts) {
        Resolved<T> resolved = failStepWithHandler(name, check, failureHandler, toJson, e, start);
        if (resolved != null) {
          return resolved.result();
        }
        throw e;
      }
      LOG.debug(
          "Step {} of job {} (check {}) FAILED in {} ms: {}",
          name,
          id,
          check,
          millisSince(start),
          e.toString());
      try {
        fenced(() -> database.steps().failPoll(id, lock, name, e.toString(), check));
      } catch (RuntimeException recording) {
        e.addSuppressed(recording);
      }
      throw e;
    }
    Duration delay =
        notReady.retryAfter() != null ? notReady.retryAfter() : nextCheckIn(name, interval, check);
    var wait = fenced(() -> database.steps().waitPoll(id, lock, name, delay));
    if (wait.timedOut()) {
      LOG.debug("Step {} of job {} timed out after check {}", name, id, check);
      return timedOut(name, failureHandler, toJson, pollTimeout(name, wait.checks(), timeout));
    }
    LOG.debug(
        "Step {} of job {}: check {} not ready, checking again at {}, suspending the job",
        name,
        id,
        check,
        wait.waitUntil());
    throw suspendPoll(
        new JobSuspended.PollSuspension(name, check, wait.waitUntil(), poll.resumed()));
  }

  /** The time until the next check: the interval's delay, or zero if it fails. */
  private Duration nextCheckIn(String name, RetryBackoff interval, int check) {
    try {
      Duration delay = interval.delay(check);
      if (delay != null && !delay.isNegative()) {
        return delay;
      }
      LOG.warn(
          "Interval of poll {} of job {} returned {} after check {}, checking again right away",
          name,
          id,
          delay,
          check);
    } catch (RuntimeException e) {
      LOG.warn(
          "Interval of poll {} of job {} failed after check {}, checking again right away",
          name,
          id,
          check,
          e);
    }
    return Duration.ZERO;
  }

  private static PollTimeoutException pollTimeout(String name, int checks, Duration timeout) {
    return new PollTimeoutException(
        "Polling in step '"
            + name
            + "' timed out"
            + (timeout == null ? "" : " after " + timeout)
            + " ("
            + checks
            + (checks == 1 ? " check)" : " checks)"));
  }

  /**
   * A wait or poll that timed out ({@code TIMED_OUT}): its failure handler runs right away (waiting
   * or polling again would time out again) and may resolve it; otherwise the job fails without
   * further attempts with the timeout exception.
   *
   * @return the result the step was resolved with
   */
  private <T> T timedOut(
      String name,
      StepFailureHandler<T> handler,
      Function<? super T, String> toJson,
      BikeschedException timeout) {
    if (handler != null) {
      String error = ValidationUtil.truncateMessage(timeout.toString());
      LOG.debug("Step {} of job {} is FAILING: {}", name, id, timeout.getMessage());
      fenced(() -> database.steps().markTimedOutStepFailing(id, lock, name, error));
      Resolved<T> resolved = handleFailingStep(name, handler, toJson, error, timeout);
      if (resolved != null) {
        return resolved.result();
      }
    }
    throw failWithoutRetry(timeout);
  }

  /**
   * A wait or poll still {@code FAILING}: its failure handler threw or was lost in an earlier
   * attempt, so it runs again; unless it resolves the step, the job fails without further attempts.
   *
   * @return the result the step was resolved with
   */
  private <T> T stillFailing(
      String name,
      StepFailureHandler<T> handler,
      Function<? super T, String> toJson,
      String error,
      BikeschedException failure) {
    if (handler != null) {
      Resolved<T> resolved = handleFailingStep(name, handler, toJson, error, failure);
      if (resolved != null) {
        return resolved.result();
      }
    }
    throw failWithoutRetry(failure);
  }

  /**
   * Fails the job without further attempts, unless a failure handler has to run again (the job then
   * gets an extra attempt).
   *
   * @return the failure, to throw
   */
  private BikeschedException failWithoutRetry(BikeschedException failure) {
    if (!failureHandlingPending) {
      fail(failure.getMessage()).remainingAttempts(0);
    }
    return failure;
  }

  private JobSuspended suspendPoll(JobSuspended.PollSuspension polling) {
    suspension = polling;
    return new JobSuspended(polling);
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
   * result when the event arrived, in this or an earlier run), the fallback its failure handler
   * resolved it with once the wait timed out, or suspends the job by throwing {@link JobSuspended}.
   * The names were validated by {@link #waitForEvent(String, String, String, Class)}.
   *
   * @param timeout how long to wait at most, counted from the first wait; {@code null} for no limit
   * @param failureHandler handles a timeout; {@code null} to fail the job with an {@link
   *     EventTimeoutException}
   * @param timeoutMessage the message of the {@link EventTimeoutException}
   */
  <T> T awaitEventStep(
      String stepName,
      String eventName,
      String eventKey,
      Duration timeout,
      Class<T> payloadType,
      StepFailureHandler<T> failureHandler,
      String timeoutMessage) {
    Function<String, T> read = reader(payloadType);
    Function<? super T, String> toJson = database.jsonMapper()::toJson;
    stepGuard.requireNoneRunning("wait for an event as step '" + stepName + "'");
    Runnable restoreStepName = putStepName(stepName);
    try {
      if (compensating) {
        // Replay only: return what the wait stored, never start or time out a wait.
        var step = database.steps().findStep(id, stepName);
        step.ifPresent(
            stored -> requireStepKind(stepName, stored.kind(), "EVENT_WAIT", "an event wait"));
        if (step.map(StepStore.StoredStep::state).filter("SUCCEEDED"::equals).isPresent()) {
          return read.apply(step.get().result());
        }
        throw new CompensationReplayEnded(stepName); // it timed out: the job ended there
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
          return timedOut(
              stepName, failureHandler, toJson, new EventTimeoutException(timeoutMessage));
        }
        case "FAILING" -> {
          String error = database.steps().findFailingError(id, stepName, "EVENT_WAIT").orElse("");
          return stillFailing(
              stepName, failureHandler, toJson, error, new EventTimeoutException(timeoutMessage));
        }
        case "FAILED" -> throw failWithoutRetry(new EventTimeoutException(timeoutMessage));
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
      boolean compensable,
      StepFailureHandler<T> failureHandler)
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
      if (failureHandler != null && attempts >= maxAttempts) {
        Resolved<T> resolved =
            failStepWithHandler(name, stepAttempt, failureHandler, toJson, e, start);
        if (resolved != null) {
          return resolved.result();
        }
      } else {
        recordStepFailure(name, stepAttempt, e, start);
      }
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

  /** The result a failure handler resolved its step with, may be {@code null}. */
  private record Resolved<T>(T result) {}

  /**
   * The step failed in the job's last attempt: marks it {@code FAILING}, runs its failure handler
   * and only then marks it {@code FAILED}, or {@code SUCCEEDED} if the handler resolved it.
   *
   * @return the result the step was resolved with; {@code null} if it was not resolved
   */
  private <T> Resolved<T> failStepWithHandler(
      String name,
      int stepAttempt,
      StepFailureHandler<T> handler,
      Function<? super T, String> toJson,
      Exception failure,
      long start) {
    LOG.debug(
        "Step {} of job {} (attempt {}) is FAILING after {} ms, attempts used up: {}",
        name,
        id,
        stepAttempt,
        millisSince(start),
        failure.toString());
    String error = ValidationUtil.truncateMessage(failure.toString());
    try {
      fenced(() -> database.steps().markStepFailing(id, lock, name, error));
    } catch (RuntimeException e) {
      failure.addSuppressed(e);
      return null;
    }
    return handleFailingStep(name, handler, toJson, error, failure);
  }

  /**
   * Runs the failure handler of a {@code FAILING} step (counted before it runs), then marks the
   * step {@code SUCCEEDED} if the handler resolved it, otherwise {@code FAILED}. If that does not
   * work, the step stays {@code FAILING} and the job gets an extra attempt, until the handler ran
   * {@code failureHandlerAttempts} times; then the step is {@code FAILED} without it.
   *
   * @param error the stored description of the failure, handed to the handler
   * @param failure the exception failing the job unless the step is resolved; collects the
   *     handler's exceptions
   * @return the result the step was resolved with; {@code null} if it was not resolved
   */
  private <T> Resolved<T> handleFailingStep(
      String name,
      StepFailureHandler<T> handler,
      Function<? super T, String> toJson,
      String error,
      Exception failure) {
    try {
      var run = fenced(() -> database.steps().startFailureHandler(id, lock, name));
      if (run.isEmpty()) {
        return null; // no longer FAILING: nothing to handle
      }
      if (run.get() > failureHandlerAttempts) {
        // An earlier run was lost (e.g. its node died): the runs are used up.
        giveUpFailureHandler(name, run.get() - 1, null);
        return null;
      }
      var failing = new DefaultFailingStep<T>(id, name, error);
      Exception handlerFailure = null;
      try {
        stepGuard.run(
            "the failure handler of step '" + name + "'",
            () -> {
              handler.handle(failing);
              return null;
            });
      } catch (Exception e) {
        handlerFailure = e;
      }
      if (failing.isResolved()) {
        if (handlerFailure != null) {
          LOG.warn(
              "Failure handler of step {} of job {} resolved the step, ignoring the exception it"
                  + " threw afterwards",
              name,
              id,
              handlerFailure);
        }
        T result = failing.result();
        String json = result == null ? null : toJson.apply(result);
        fenced(() -> database.steps().resolveFailingStep(id, lock, name, json));
        stepRetry = null;
        LOG.debug("Step {} of job {} SUCCEEDED, resolved by its failure handler", name, id);
        return new Resolved<>(result);
      }
      if (handlerFailure == null) {
        fenced(() -> database.steps().failFailingStep(id, lock, name, null));
        LOG.debug("Step {} of job {} FAILED after its failure handler ran", name, id);
        return null;
      }
      failure.addSuppressed(handlerFailure);
      if (run.get() >= failureHandlerAttempts) {
        giveUpFailureHandler(name, run.get(), handlerFailure);
        return null;
      }
      LOG.warn(
          "Failure handler of step {} of job {} failed (run {} of {}), it runs again in the next"
              + " attempt",
          name,
          id,
          run.get(),
          failureHandlerAttempts,
          handlerFailure);
      failureHandlingPending = true;
    } catch (RuntimeException e) {
      LOG.warn(
          "Handling the failure of step {} of job {} failed, it runs again in the next attempt",
          name,
          id,
          e);
      failureHandlingPending = true;
      failure.addSuppressed(e);
    }
    return null;
  }

  /** The failure handler of a step ran {@code runs} times without success: the step fails. */
  private void giveUpFailureHandler(String name, int runs, Exception lastFailure) {
    LOG.error(
        "Failure handler of step {} of job {} gave up after {} run(s), the step FAILED",
        name,
        id,
        runs,
        lastFailure);
    fenced(
        () ->
            database
                .steps()
                .failFailingStep(
                    id, lock, name, "failure handler gave up after " + runs + " run(s)"));
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
