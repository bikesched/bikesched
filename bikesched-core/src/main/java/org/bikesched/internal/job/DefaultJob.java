// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bikesched.BikeschedException;
import org.bikesched.BikeschedLogContext;
import org.bikesched.RetryBackoff;
import org.bikesched.event.EventTimeoutException;
import org.bikesched.event.EventWait;
import org.bikesched.event.StepEventBuilder;
import org.bikesched.event.TypedEvent;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.EventStore;
import org.bikesched.internal.database.JobLock;
import org.bikesched.internal.database.JobLockLostException;
import org.bikesched.internal.database.JobOrigin;
import org.bikesched.internal.database.JobPhase;
import org.bikesched.internal.database.JobStore;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.database.StepRef;
import org.bikesched.internal.database.StepStore;
import org.bikesched.internal.database.StepStore.ChildWait;
import org.bikesched.internal.database.StepType;
import org.bikesched.internal.event.DefaultStepEventBuilder;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.Job;
import org.bikesched.job.JobFailure;
import org.bikesched.job.JobResult;
import org.bikesched.job.Poll;
import org.bikesched.job.PollBuilder;
import org.bikesched.job.PollHandler;
import org.bikesched.job.PollTimeoutException;
import org.bikesched.job.Step;
import org.bikesched.job.StepBuilder;
import org.bikesched.job.StepFailureHandler;
import org.bikesched.job.StepHandler;
import org.bikesched.job.StepJobBuilder;
import org.bikesched.job.TypedJob;
import org.jspecify.annotations.Nullable;
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

  private final String name;
  private final @Nullable String payload;
  private final int priority;
  private final Instant scheduledAt;
  private final int attempts;
  private final int maxAttempts;
  private final @Nullable Duration timeout;
  private final JobPhase phase;
  private final JobOrigin origin;
  private final @Nullable String lastError;
  private final boolean cancelRequested;
  private final @Nullable String cancelRequestMessage;

  /** Replays the handler while the job is claimed to be compensated, {@code null} otherwise. */
  private final @Nullable CompensationReplay replay;

  /** Rejects steps and event waits while a step handler or compensation runs. */
  private final StepGuard stepGuard;

  private final Set<String> eventPublishSteps = ConcurrentHashMap.newKeySet();
  private final Set<String> childJobSteps = ConcurrentHashMap.newKeySet();

  // Set by the handler, read by bikesched after the handler returned (the handler may use other
  // threads).
  private volatile boolean cancelled;
  private volatile @Nullable String cancelMessage;
  private volatile @Nullable DefaultJobFailure failure;
  private volatile @Nullable Result result;
  private volatile JobSuspended.@Nullable Suspension suspension;
  private volatile @Nullable JobAttempt attempt;
  private volatile @Nullable StepRetry stepRetry;
  private volatile boolean failureHandlingPending;
  private volatile @Nullable Duration defaultEventTimeout;
  private volatile boolean lockLost;

  /** The step whose handler runs right now (not its failure handler), {@code null} if none. */
  private volatile @Nullable StepRef runningStep;

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
    this.database = database;
    this.eventListener = eventListener;
    this.scheduler = scheduler;
    this.defaultEventRetention = defaultEventRetention;
    this.failureHandlerAttempts = failureHandlerAttempts;
    this.id = claimed.id();
    this.lock = claimed.lock();
    this.name = claimed.name();
    this.payload = claimed.payload();
    this.priority = claimed.priority();
    this.scheduledAt = claimed.scheduledAt();
    this.attempts = claimed.attempts();
    this.maxAttempts = claimed.maxAttempts();
    this.timeout = claimed.timeout();
    this.phase = claimed.phase();
    this.origin = claimed.origin();
    this.lastError = claimed.lastError();
    this.cancelRequested = claimed.cancelRequested();
    this.cancelRequestMessage = claimed.cancelMessage();
    this.replay =
        phase instanceof JobPhase.Compensate ? new CompensationReplay(database, id) : null;
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
  public <T extends @Nullable Object> StepEventBuilder<T> newEvent(
      TypedEvent<T> typedEvent, String eventKey) {
    Objects.requireNonNull(typedEvent, "typedEvent");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultStepEventBuilder<>(
        typedEvent.name(), eventKey, database.jsonMapper(), this::publishEventStep);
  }

  private UUID publishEventStep(
      String eventName,
      String eventKey,
      @Nullable String eventPayload,
      @Nullable Duration retention,
      @Nullable String requestedStepName) {
    String stepName =
        stepName(requestedStepName, "publish " + eventName, "Default event publishing step name");
    stepGuard.requireNoneRunning("publish an event as step '" + stepName + "'");
    if (!eventPublishSteps.add(stepName)) {
      throw new IllegalStateException(
          "Job "
              + id
              + " publishes more than once with step name '"
              + stepName
              + "'; set a unique stepName(…) for each publish");
    }
    if (replay != null) {
      replay.requirePublished(stepName);
    }
    try (var stepLogContext = stepContext(stepName)) {
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
                  replay != null);
      if (published.publication() != null) {
        notifyPublished(eventName, published.publication());
      }
      return published.eventId();
    }
  }

  /**
   * The name of a step whose name is optional: the requested one, or the default derived from what
   * the step does, which must not exceed the length limit.
   *
   * @param what what the default name is, for the message
   */
  private static String stepName(@Nullable String requested, String defaultName, String what) {
    if (requested == null
        && defaultName.codePointCount(0, defaultName.length()) > ValidationUtil.MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          what
              + " is longer than "
              + ValidationUtil.MAX_NAME_LENGTH
              + " characters; set stepName(…) explicitly");
    }
    return ValidationUtil.requireName(requested == null ? defaultName : requested, "step name");
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
  public String name() {
    return name;
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
  public @Nullable String payload() {
    return payload;
  }

  @Override
  public <T extends @Nullable Object> @Nullable T payload(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return payload == null ? null : database.jsonMapper().fromJson(payload, type);
  }

  /** What the job was claimed for: running its handler, compensating or its failure handler. */
  JobPhase phase() {
    return phase;
  }

  /** Where the job comes from: one-off, a child job or an occurrence of a recurring job. */
  JobOrigin origin() {
    return origin;
  }

  @Override
  public boolean isCompensating() {
    return phase instanceof JobPhase.Compensate;
  }

  /** The stored description of the job's last failure, {@code null} if none. */
  @Nullable String lastError() {
    return lastError;
  }

  /** Whether the job's cancellation was requested via {@code Bikesched.cancel}. */
  boolean cancelRequested() {
    return cancelRequested;
  }

  /** The message of the requested cancellation, {@code null} if none. */
  @Nullable String cancelRequestMessage() {
    return cancelRequestMessage;
  }

  /** The compensations registered by the replay, in the order to run them (latest step first). */
  List<CompensationReplay.PendingCompensation> compensations() {
    return replay == null ? List.of() : replay.compensations();
  }

  /**
   * Runs a compensation and records it: the step becomes {@code COMPENSATED}.
   *
   * @throws Exception the exception of the compensation, after recording it with the step
   */
  void compensate(CompensationReplay.PendingCompensation compensation) throws Exception {
    String name = compensation.stepName();
    long start = System.nanoTime();
    try (var stepLogContext = stepContext(name)) {
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
          fenced(
              () -> database.steps().failCompensation(id, lock, compensation.step(), e.toString()));
        } catch (RuntimeException recording) {
          e.addSuppressed(recording);
        }
        throw e;
      }
      fenced(() -> database.steps().compensateStep(id, lock, compensation.step()));
      LOG.debug("Step {} of job {} COMPENSATED in {} ms", name, id, millisSince(start));
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
  public void cancel(@Nullable String message) {
    this.cancelMessage = message;
    this.cancelled = true;
  }

  /** Whether the handler cancelled the job. */
  boolean isCancelled() {
    return cancelled;
  }

  /** The message passed to {@link #cancel(String)}, {@code null} if none. */
  @Nullable String cancelMessage() {
    return cancelMessage;
  }

  @Override
  public JobFailure fail(@Nullable String message) {
    var failure = new DefaultJobFailure(message);
    this.failure = failure;
    return failure;
  }

  @Override
  public void postpone(Duration delay) {
    ValidationUtil.requirePositive(delay, "delay");
    StepRef step = runningStep;
    if (step == null) {
      stepGuard.requireNoneRunning("postpone");
    }
    if (replay != null) {
      // The replay only collects compensations and runs no new work: nothing to postpone.
      LOG.debug("Job {} replays for compensation, ignoring postpone({})", id, delay);
      return;
    }
    var postponed = new JobSuspended.PostponeSuspension(step, delay);
    suspension = postponed;
    LOG.debug(
        "Job {} postponed by {}{}",
        id,
        delay,
        step == null ? "" : " in step '" + step.name() + "'");
    throw new JobSuspended(postponed);
  }

  /** The failure the handler marked the attempt with, {@code null} if none. */
  @Nullable DefaultJobFailure failure() {
    return failure;
  }

  /** A result set by the handler: JSON text, {@code null} for a {@code null} result. */
  private record Result(@Nullable String json) {}

  @Override
  public void complete(@Nullable Object result) {
    this.result = new Result(toJson(result));
  }

  /** The result set by the handler as JSON text, {@code null} if none. */
  @Nullable String resultJson() {
    Result current = result;
    return current == null ? null : current.json();
  }

  @Override
  public StepJobBuilder<Object> newJob(String name) {
    ValidationUtil.requireName(name, "job name");
    requireChildrenAllowed(name);
    return new DefaultStepJobBuilder<>(this, name, Object.class, database.jsonMapper(), scheduler);
  }

  @Override
  public <T extends @Nullable Object> StepJobBuilder<T> newJob(TypedJob<T> typedJob) {
    Objects.requireNonNull(typedJob, "typedJob");
    requireChildrenAllowed(typedJob.name());
    scheduler.requirePayloadType(typedJob);
    return new DefaultStepJobBuilder<>(
        this, typedJob.name(), typedJob.payloadType(), database.jsonMapper(), scheduler);
  }

  private void requireChildrenAllowed(String name) {
    stepGuard.requireNoneRunning("start child job " + name);
    if (origin instanceof JobOrigin.Occurrence) {
      throw new IllegalStateException(
          "Job "
              + id
              + " is an occurrence of recurring job "
              + this.name
              + " and cannot start child jobs: the next occurrence replaces it, so no single caller"
              + " waits for its children");
    }
  }

  /**
   * Starts a child job as a step, see {@link JobStore#startChildJob}; built by {@link
   * DefaultStepJobBuilder}.
   *
   * @param requestedStepName the step name, {@code null} for the default {@code start <name>}
   * @param child the child's attributes
   * @param compensation the step's compensation, receiving the child's id; {@code null} if none
   * @return the child's id, the same on every run
   */
  UUID startChildJob(
      @Nullable String requestedStepName,
      NewJob child,
      @Nullable CompensationHandler<? super UUID> compensation) {
    String stepName =
        stepName(requestedStepName, "start " + child.name(), "Default child job step name");
    stepGuard.requireNoneRunning("start a child job as step '" + stepName + "'");
    if (!childJobSteps.add(stepName)) {
      throw new IllegalStateException(
          "Job "
              + id
              + " starts more than one child job with step name '"
              + stepName
              + "'; set a unique stepName(…) for each child");
    }
    try (var stepLogContext = stepContext(stepName)) {
      if (replay != null) {
        // Replay only: return the started child, registering the compensation, never start one.
        return replay.startedChild(stepName, compensation);
      }
      var started =
          fenced(
              () -> database.jobs().startChildJob(id, lock, stepName, child, compensation != null));
      if (started.started()) {
        LOG.debug(
            "Step {} of job {} started child job {} named {}",
            stepName,
            id,
            started.id(),
            child.name());
      } else {
        LOG.debug("Step {} of job {} already started child job {}", stepName, id, started.id());
      }
      return started.id();
    }
  }

  @Override
  public <T extends @Nullable Object> JobResult<T> waitForJob(
      UUID childJobId, Class<T> resultType) {
    return waitForChildJob(childJobId, resultType, null);
  }

  @Override
  public <T extends @Nullable Object> JobResult<T> waitForJob(
      UUID childJobId, Class<T> resultType, Duration timeout) {
    ValidationUtil.requirePositive(timeout, "timeout");
    return waitForChildJob(childJobId, resultType, timeout);
  }

  /**
   * Waits for a child job: returns its outcome (stored with its step when it ended), the timed-out
   * wait, or suspends the job by throwing {@link JobSuspended}.
   */
  private <T extends @Nullable Object> JobResult<T> waitForChildJob(
      UUID childJobId, Class<T> resultType, @Nullable Duration timeout) {
    Objects.requireNonNull(childJobId, "childJobId");
    Objects.requireNonNull(resultType, "resultType");
    stepGuard.requireNoneRunning("wait for job " + childJobId);
    if (replay != null) {
      // Replay only: return what the wait stored, never start or time out a wait.
      return replay.childResult(childJobId, reader(resultType));
    }
    var wait = fenced(() -> database.steps().awaitChildJob(id, lock, childJobId, timeout));
    try (var stepLogContext = stepContext(wait.step().name())) {
      return switch (wait) {
        case ChildWait.ChildEnded ended -> {
          LOG.debug(
              "Step {} of job {} received the outcome of job {}: {}",
              ended.step().name(),
              id,
              childJobId,
              ended.childState());
          yield jobResult(childJobId, ended, reader(resultType));
        }
        case ChildWait.ChildTimedOut timedOut -> {
          LOG.debug(
              "Step {} of job {} timed out waiting for job {}",
              timedOut.step().name(),
              id,
              childJobId);
          yield new JobResult.TimedOut<>(childJobId);
        }
        case ChildWait.ChildWaiting(StepRef step, Instant waitUntil) -> {
          LOG.debug(
              "Step {} of job {} waiting for job {}{}, suspending the job",
              step.name(),
              id,
              childJobId,
              waitUntil == null ? "" : " until " + waitUntil);
          throw suspend(new JobSuspended.JobSuspension(step, childJobId, waitUntil));
        }
        case ChildWait.NotWaiting(StepRef step, var state) ->
            throw new IllegalStateException(
                "Step '"
                    + step.name()
                    + "' of job "
                    + id
                    + " starting job "
                    + childJobId
                    + " is "
                    + state
                    + "; start the child again before waiting for it");
      };
    }
  }

  /** The outcome of a child job as stored with its step once the child ended. */
  static <T extends @Nullable Object> JobResult<T> jobResult(
      UUID childJobId, ChildWait.ChildEnded ended, Function<@Nullable String, @Nullable T> read) {
    return switch (ended.childState()) {
      case SUCCEEDED -> new JobResult.Succeeded<>(childJobId, read.apply(ended.result()));
      case FAILED ->
          new JobResult.Failed<>(childJobId, Objects.requireNonNullElse(ended.error(), ""));
      case CANCELLED -> new JobResult.Cancelled<>(childJobId, ended.error());
      case COMPENSATION_FAILED ->
          new JobResult.CompensationFailed<>(
              childJobId, Objects.requireNonNullElse(ended.error(), ""));
      case SCHEDULED, RUNNING, WAITING, COMPENSATING, FAILING ->
          throw new IllegalStateException(
              "Unknown state " + ended.childState() + " of child job " + childJobId);
    };
  }

  @Override
  public void updateTimeout(Duration timeout) {
    ValidationUtil.requirePositive(timeout, "timeout");
    JobAttempt running = attempt;
    if (running == null) {
      throw new IllegalStateException("Job " + id + " is not running");
    }
    running.startTimeout(timeout);
    LOG.debug("Timeout of job {} updated to {} from now", id, timeout);
  }

  /** Sets the node's default timeout of waits for events without their own timeout. */
  void defaultEventTimeout(@Nullable Duration timeout) {
    this.defaultEventTimeout = timeout;
  }

  /** The node's default timeout of waits for events, {@code null} for none. */
  @Nullable Duration defaultEventTimeout() {
    return defaultEventTimeout;
  }

  /** Connects the job to its running attempt, enabling {@link #updateTimeout(Duration)}. */
  void attach(JobAttempt attempt) {
    this.attempt = attempt;
  }

  @Override
  public <T extends @Nullable Object> @Nullable T step(
      String name, Class<T> resultType, StepHandler<T> handler) throws Exception {
    return step(name, resultType, handler, null, null, null);
  }

  /** A failed step's own retry backoff and its number of failed attempts. */
  record StepRetry(RetryBackoff backoff, int failedAttempts) {}

  /** The retry backoff of the step that made the attempt fail, {@code null} if none. */
  @Nullable StepRetry stepRetry() {
    return stepRetry;
  }

  /**
   * Runs a step, with a compensation or without ({@code null}), and with its own retry backoff or
   * without ({@code null}).
   */
  <T extends @Nullable Object> @Nullable T step(
      String name,
      Class<T> resultType,
      StepHandler<T> handler,
      @Nullable CompensationHandler<? super T> compensation,
      @Nullable RetryBackoff retryBackoff,
      @Nullable StepFailureHandler<T> failureHandler)
      throws Exception {
    Objects.requireNonNull(resultType, "resultType");
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
    return runStep(name, run, reader(resultType), compensation, failureHandler);
  }

  /**
   * Whether the failure handler of a failed step threw (or could not be recorded): the job gets an
   * extra attempt to run it again, even if its attempts are used up.
   */
  boolean isFailureHandlingPending() {
    return failureHandlingPending;
  }

  @Override
  public <T extends @Nullable Object> StepBuilder<T> newStep(String name, Class<T> resultType) {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(resultType, "resultType");
    stepGuard.requireNoneRunning("create step '" + name + "'");
    return new DefaultStepBuilder<>(this, name, resultType);
  }

  @Override
  public <T extends @Nullable Object> PollBuilder<T> newPoll(
      String name, Class<T> resultType, Duration interval) {
    ValidationUtil.requireName(name, "step name");
    ValidationUtil.requirePositive(interval, "interval");
    return newPoll(name, resultType, RetryBackoff.fixed(interval));
  }

  @Override
  public <T extends @Nullable Object> PollBuilder<T> newPoll(
      String name, Class<T> resultType, RetryBackoff interval) {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(resultType, "resultType");
    Objects.requireNonNull(interval, "interval");
    stepGuard.requireNoneRunning("create poll '" + name + "'");
    return new DefaultPollBuilder<>(this, name, resultType, interval);
  }

  private <T extends @Nullable Object> @Nullable T runStep(
      String name,
      StepHandler<T> handler,
      Function<@Nullable String, @Nullable T> fromJson,
      @Nullable CompensationHandler<? super T> compensation,
      @Nullable StepFailureHandler<T> failureHandler)
      throws Exception {
    ValidationUtil.requireName(name, "step name");
    Objects.requireNonNull(handler, "handler");
    stepGuard.requireNoneRunning("run step '" + name + "'");
    if (replay != null) {
      return replay.step(name, fromJson, compensation);
    }
    try (var stepLogContext = stepContext(name)) {
      var completed = database.steps().findCompletedStep(id, name);
      completed.ifPresent(step -> StepType.STEP.require(name, id, step.type()));
      if (completed.isEmpty()) {
        if (failureHandler != null) {
          var failing = database.steps().findFailingStep(id, name, StepType.STEP);
          if (failing.isPresent()) {
            // The handler threw or was lost in an earlier attempt: run it again, then fail the job
            // (unless it resolves the step).
            String error = failing.get().error();
            var failure = new BikeschedException("Step '" + name + "' failed: " + error);
            return resolvedOrThrow(
                handleFailingStep(failing.get().step(), failureHandler, error, failure), failure);
          }
        }
        var stepAttempt = fenced(() -> database.steps().startStep(id, lock, name));
        if (stepAttempt.isPresent()) {
          return executeStep(
              stepAttempt.get().step(),
              stepAttempt.get().attempt(),
              handler,
              compensation != null,
              failureHandler);
        }
        // Completed in the meantime, or a step of another type (e.g. still waiting for an event).
        database
            .steps()
            .findStep(id, name)
            .ifPresent(step -> StepType.STEP.require(name, id, step.type()));
        completed = database.steps().findCompletedStep(id, name);
      }
      LOG.debug("Step {} of job {} already completed, reusing its result", name, id);
      return completed.isEmpty() ? null : read(completed.get(), fromJson);
    }
  }

  private static <T extends @Nullable Object> @Nullable T read(
      StepStore.CompletedStep step, Function<@Nullable String, @Nullable T> fromJson) {
    return fromJson.apply(step.result());
  }

  /** The step name in the logging context, see {@link #stepContext(String)}. */
  private record StepContext(String outerStep) implements AutoCloseable {

    /** Restores the step name of an enclosing step, or removes it if there was none. */
    @Override
    public void close() {
      if (outerStep == null) {
        MDC.remove(BikeschedLogContext.STEP_NAME);
      } else {
        MDC.put(BikeschedLogContext.STEP_NAME, outerStep);
      }
    }
  }

  /** Puts the step name into the logging context until the returned context is closed. */
  private static StepContext stepContext(String name) {
    String outerStep = MDC.get(BikeschedLogContext.STEP_NAME);
    MDC.put(BikeschedLogContext.STEP_NAME, name);
    return new StepContext(outerStep);
  }

  @Override
  public <T extends @Nullable Object> EventWait<T> waitForEvent(
      String stepName, String eventName, String eventKey, Class<T> payloadType) {
    ValidationUtil.requireName(stepName, "step name");
    ValidationUtil.requireName(eventName, "event name");
    ValidationUtil.requireKey(eventKey, "event key");
    Objects.requireNonNull(payloadType, "payloadType");
    stepGuard.requireNoneRunning("wait for an event as step '" + stepName + "'");
    return new DefaultEventWait<>(this, stepName, eventName, eventKey, payloadType);
  }

  @Override
  public <T extends @Nullable Object> EventWait<T> waitForEvent(
      String stepName, TypedEvent<T> typedEvent, String eventKey) {
    Objects.requireNonNull(typedEvent, "typedEvent");
    return waitForEvent(stepName, typedEvent.name(), eventKey, typedEvent.payloadType());
  }

  @Override
  public <T extends @Nullable Object> @Nullable T awaitEvent(
      String stepName, TypedEvent<T> typedEvent, String eventKey) {
    return waitForEvent(stepName, typedEvent, eventKey).receive();
  }

  @Override
  public void sleep(String stepName, Duration duration) {
    ValidationUtil.requireName(stepName, "step name");
    ValidationUtil.requirePositive(duration, "duration");
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
  private void sleepStep(String stepName, @Nullable Duration duration, @Nullable Instant until) {
    stepGuard.requireNoneRunning("sleep as step '" + stepName + "'");
    if (replay != null) {
      // Replay only: return after a completed sleep, never start or continue one.
      replay.sleep(stepName);
      return;
    }
    try (var stepLogContext = stepContext(stepName)) {
      var sleep = fenced(() -> database.steps().sleep(id, lock, stepName, duration, until));
      if (sleep.awake()) {
        LOG.debug("Step {} of job {} slept until {}", stepName, id, sleep.wakeUpAt());
        return;
      }
      LOG.debug(
          "Step {} of job {} sleeping until {}, suspending the job",
          stepName,
          id,
          sleep.wakeUpAt());
      throw suspend(new JobSuspended.SleepSuspension(sleep.step(), sleep.wakeUpAt()));
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
  <T extends @Nullable Object> @Nullable T poll(
      String name,
      Class<T> resultType,
      RetryBackoff interval,
      @Nullable Duration timeout,
      @Nullable StepFailureHandler<T> failureHandler,
      PollHandler<T> handler)
      throws Exception {
    stepGuard.requireNoneRunning("poll as step '" + name + "'");
    if (replay != null) {
      // Replay only: return what the poll stored, never start a check.
      return replay.poll(name, reader(resultType));
    }
    try (var stepLogContext = stepContext(name)) {
      var poll = fenced(() -> database.steps().startPoll(id, lock, name, timeout));
      return switch (poll) {
        case StepStore.PollStep.Ready(var step, @Nullable String result) -> {
          LOG.debug("Step {} of job {} already completed, reusing its result", name, id);
          yield reader(resultType).apply(result);
        }
        case StepStore.PollStep.PollTimedOut(StepRef step, int checks) ->
            timedOut(step, failureHandler, pollTimeout(name, checks, timeout));
        case StepStore.PollStep.PollFailing(StepRef step, String error) ->
            stillFailing(
                step,
                failureHandler,
                error,
                new BikeschedException("Poll '" + name + "' failed: " + error));
        case StepStore.PollStep.NotDue(StepRef step, int checks, Instant waitUntil) -> {
          LOG.debug(
              "Step {} of job {}: next check not due until {}, suspending the job",
              name,
              id,
              waitUntil);
          throw suspend(new JobSuspended.PollSuspension(step, checks, waitUntil, true));
        }
        case StepStore.PollStep.CheckStarted started ->
            check(started.step(), interval, timeout, failureHandler, handler, started);
      };
    }
  }

  /** Runs one check of a poll, see {@link #poll}. */
  private <T extends @Nullable Object> @Nullable T check(
      StepRef stepRef,
      RetryBackoff interval,
      @Nullable Duration timeout,
      @Nullable StepFailureHandler<T> failureHandler,
      PollHandler<T> handler,
      StepStore.PollStep.CheckStarted poll)
      throws Exception {
    String name = stepRef.name();
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
          String json = toJson(value);
          fenced(() -> database.steps().completePoll(id, lock, stepRef, json, check));
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
        return resolvedOrThrow(failStepWithHandler(stepRef, check, failureHandler, e, start), e);
      }
      LOG.debug(
          "Step {} of job {} (check {}) FAILED in {} ms: {}",
          name,
          id,
          check,
          millisSince(start),
          e.toString());
      try {
        fenced(() -> database.steps().failPoll(id, lock, stepRef, e.toString(), check));
      } catch (RuntimeException recording) {
        e.addSuppressed(recording);
      }
      throw e;
    }
    Duration delay =
        notReady.retryAfter() != null ? notReady.retryAfter() : nextCheckIn(name, interval, check);
    var wait = fenced(() -> database.steps().waitPoll(id, lock, stepRef, delay));
    if (wait.timedOut()) {
      LOG.debug("Step {} of job {} timed out after check {}", name, id, check);
      return timedOut(stepRef, failureHandler, pollTimeout(name, wait.checks(), timeout));
    }
    LOG.debug(
        "Step {} of job {}: check {} not ready, checking again at {}, suspending the job",
        name,
        id,
        check,
        wait.waitUntil());
    throw suspend(
        new JobSuspended.PollSuspension(stepRef, check, wait.waitUntil(), poll.resumed()));
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

  private static PollTimeoutException pollTimeout(
      String name, int checks, @Nullable Duration timeout) {
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
  private <T extends @Nullable Object> T timedOut(
      StepRef step, @Nullable StepFailureHandler<T> handler, BikeschedException timeout) {
    if (handler != null) {
      String error = ValidationUtil.truncateMessage(timeout.toString());
      LOG.debug("Step {} of job {} is FAILING: {}", step.name(), id, timeout.getMessage());
      fenced(() -> database.steps().markTimedOutStepFailing(id, lock, step, error));
      if (handleFailingStep(step, handler, error, timeout)
          instanceof FailureHandling.Resolved<T>(T result)) {
        return result;
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
  private <T extends @Nullable Object> T stillFailing(
      StepRef step,
      @Nullable StepFailureHandler<T> handler,
      String error,
      BikeschedException failure) {
    if (handler != null
        && handleFailingStep(step, handler, error, failure)
            instanceof FailureHandling.Resolved<T>(T result)) {
      return result;
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

  /**
   * Suspends the job at a waiting step: remembers the suspension for recording it once the handler
   * returned.
   *
   * @return the control-flow error ending the handler's attempt, to throw
   */
  private JobSuspended suspend(JobSuspended.Wait wait) {
    suspension = wait;
    return new JobSuspended(wait);
  }

  /** A call run with the step name in the logging context. */
  @FunctionalInterface
  interface StepCall<R extends @Nullable Object> {
    R call() throws Exception;
  }

  /** The step the handler suspended the job at, {@code null} if it did not wait or sleep. */
  JobSuspended.@Nullable Suspension suspension() {
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
  <T extends @Nullable Object> @Nullable T awaitEventStep(
      String stepName,
      String eventName,
      String eventKey,
      @Nullable Duration timeout,
      Class<T> payloadType,
      @Nullable StepFailureHandler<T> failureHandler,
      String timeoutMessage) {
    Function<@Nullable String, @Nullable T> read = reader(payloadType);
    stepGuard.requireNoneRunning("wait for an event as step '" + stepName + "'");
    if (replay != null) {
      // Replay only: return what the wait stored, never start or time out a wait.
      return replay.event(stepName, read);
    }
    try (var stepLogContext = stepContext(stepName)) {
      var wait =
          fenced(
              () -> database.events().awaitEvent(id, lock, stepName, eventName, eventKey, timeout));
      return switch (wait) {
        case EventStore.EventWaitResult.Received(@Nullable String json) -> {
          LOG.debug("Step {} of job {} received event {}/{}", stepName, id, eventName, eventKey);
          yield read.apply(json);
        }
        case EventStore.EventWaitResult.TimedOut(StepRef step) -> {
          LOG.debug(
              "Step {} of job {} timed out waiting for event {}/{}",
              stepName,
              id,
              eventName,
              eventKey);
          yield timedOut(step, failureHandler, new EventTimeoutException(timeoutMessage));
        }
        case EventStore.EventWaitResult.Failing(StepRef step, String error) ->
            stillFailing(step, failureHandler, error, new EventTimeoutException(timeoutMessage));
        case EventStore.EventWaitResult.Failed ignored ->
            throw failWithoutRetry(new EventTimeoutException(timeoutMessage));
        case EventStore.EventWaitResult.Waiting(StepRef step, Instant waitUntil) -> {
          LOG.debug(
              "Step {} of job {} waiting for event {}/{}{}, suspending the job",
              stepName,
              id,
              eventName,
              eventKey,
              waitUntil == null ? "" : " until " + waitUntil);
          throw suspend(new JobSuspended.EventSuspension(step, eventName, eventKey, waitUntil));
        }
      };
    }
  }

  /** Writes a result as JSON text, {@code null} for {@code null}. */
  private @Nullable String toJson(@Nullable Object value) {
    return value == null ? null : database.jsonMapper().toJson(value);
  }

  /** Reads a stored result (JSON text, may be {@code null}) as the given type. */
  <T extends @Nullable Object> Function<@Nullable String, @Nullable T> reader(Class<T> type) {
    return json -> json == null ? null : database.jsonMapper().fromJson(json, type);
  }

  private <T extends @Nullable Object> @Nullable T executeStep(
      StepRef stepRef,
      int stepAttempt,
      StepHandler<T> handler,
      boolean compensable,
      @Nullable StepFailureHandler<T> failureHandler)
      throws Exception {
    String name = stepRef.name();
    LOG.debug("Executing step {} of job {} (attempt {})", name, id, stepAttempt);
    long start = System.nanoTime();
    T result;
    try {
      var step = new Step(id, name, stepAttempt);
      result =
          stepGuard.run(
              "step '" + name + "'",
              () -> {
                runningStep = stepRef;
                try {
                  return handler.handle(step);
                } finally {
                  runningStep = null;
                }
              });
      if (suspension instanceof JobSuspended.PostponeSuspension postponed
          && stepRef.equals(postponed.step())) {
        // The handler caught the postponement and returned: the step still keeps no result.
        throw new JobSuspended(postponed);
      }
      String json = toJson(result);
      fenced(() -> database.steps().completeStep(id, lock, stepRef, json, compensable));
    } catch (Exception e) {
      if (failureHandler != null && attempts >= maxAttempts) {
        return resolvedOrThrow(
            failStepWithHandler(stepRef, stepAttempt, failureHandler, e, start), e);
      }
      recordStepFailure(stepRef, stepAttempt, e, start);
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

  /** How handling a {@code FAILING} step with its failure handler ended. */
  private sealed interface FailureHandling<T extends @Nullable Object> {

    /**
     * The handler resolved the step: it {@code SUCCEEDED} with this result.
     *
     * @param result the fallback result, may be {@code null}
     */
    record Resolved<T extends @Nullable Object>(@Nullable T result) implements FailureHandling<T> {}

    /**
     * The step is not resolved: it {@code FAILED} after its handler ran or gave up, it was no
     * longer {@code FAILING}, or it could not be marked {@code FAILING}.
     */
    record Unresolved<T extends @Nullable Object>() implements FailureHandling<T> {}

    /**
     * The handler threw (or handling it could not be recorded): the step stays {@code FAILING} and
     * the handler runs again in the job's next attempt, see {@link #isFailureHandlingPending()}.
     */
    record RunAgain<T extends @Nullable Object>() implements FailureHandling<T> {}
  }

  /**
   * The result a failure handler resolved its step with.
   *
   * @throws Exception {@code failure} if the step was not resolved
   */
  private static <T extends @Nullable Object> T resolvedOrThrow(
      FailureHandling<T> handling, Exception failure) throws Exception {
    return switch (handling) {
      case FailureHandling.Resolved<T>(T result) -> result;
      case FailureHandling.Unresolved<T> ignored -> throw failure;
      case FailureHandling.RunAgain<T> ignored -> throw failure;
    };
  }

  /**
   * The step failed in the job's last attempt: marks it {@code FAILING}, runs its failure handler
   * and only then marks it {@code FAILED}, or {@code SUCCEEDED} if the handler resolved it.
   */
  private <T extends @Nullable Object> FailureHandling<T> failStepWithHandler(
      StepRef step, int stepAttempt, StepFailureHandler<T> handler, Exception failure, long start) {
    LOG.debug(
        "Step {} of job {} (attempt {}) is FAILING after {} ms, attempts used up: {}",
        step.name(),
        id,
        stepAttempt,
        millisSince(start),
        failure.toString());
    String error = ValidationUtil.truncateMessage(failure.toString());
    try {
      fenced(() -> database.steps().markStepFailing(id, lock, step, error));
    } catch (RuntimeException e) {
      failure.addSuppressed(e);
      return new FailureHandling.Unresolved<>();
    }
    return handleFailingStep(step, handler, error, failure);
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
   */
  private <T extends @Nullable Object> FailureHandling<T> handleFailingStep(
      StepRef step, StepFailureHandler<T> handler, String error, Exception failure) {
    String name = step.name();
    try {
      var run = fenced(() -> database.steps().startFailureHandler(id, lock, step));
      if (run.isEmpty()) {
        return new FailureHandling.Unresolved<>(); // no longer FAILING: nothing to handle
      }
      if (run.get() > failureHandlerAttempts) {
        // An earlier run was lost (e.g. its node died): the runs are used up.
        return giveUpFailureHandler(step, run.get() - 1, null);
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
        String json = toJson(result);
        fenced(() -> database.steps().resolveFailingStep(id, lock, step, json));
        stepRetry = null;
        LOG.debug("Step {} of job {} SUCCEEDED, resolved by its failure handler", name, id);
        return new FailureHandling.Resolved<>(result);
      }
      if (handlerFailure == null) {
        fenced(() -> database.steps().failFailingStep(id, lock, step, null));
        LOG.debug("Step {} of job {} FAILED after its failure handler ran", name, id);
        return new FailureHandling.Unresolved<>();
      }
      failure.addSuppressed(handlerFailure);
      if (run.get() >= failureHandlerAttempts) {
        return giveUpFailureHandler(step, run.get(), handlerFailure);
      }
      LOG.warn(
          "Failure handler of step {} of job {} failed (run {} of {}), it runs again in the next"
              + " attempt",
          name,
          id,
          run.get(),
          failureHandlerAttempts,
          handlerFailure);
      return runAgain();
    } catch (RuntimeException e) {
      LOG.warn(
          "Handling the failure of step {} of job {} failed, it runs again in the next attempt",
          name,
          id,
          e);
      failure.addSuppressed(e);
      return runAgain();
    }
  }

  /** The step's failure handler runs again: the job gets an extra attempt for it. */
  private <T extends @Nullable Object> FailureHandling<T> runAgain() {
    failureHandlingPending = true;
    return new FailureHandling.RunAgain<>();
  }

  /** The failure handler of a step ran {@code runs} times without success: the step fails. */
  private <T extends @Nullable Object> FailureHandling<T> giveUpFailureHandler(
      StepRef step, int runs, @Nullable Exception lastFailure) {
    LOG.error(
        "Failure handler of step {} of job {} gave up after {} run(s), the step FAILED",
        step.name(),
        id,
        runs,
        lastFailure);
    fenced(
        () ->
            database
                .steps()
                .failFailingStep(
                    id, lock, step, "failure handler gave up after " + runs + " run(s)"));
    return new FailureHandling.Unresolved<>();
  }

  private void recordStepFailure(StepRef step, int stepAttempt, Exception failure, long start) {
    LOG.debug(
        "Step {} of job {} (attempt {}) FAILED in {} ms: {}",
        step.name(),
        id,
        stepAttempt,
        millisSince(start),
        failure.toString());
    try {
      fenced(() -> database.steps().failStep(id, lock, step, failure.toString()));
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
  private <T extends @Nullable Object> T fenced(Supplier<T> write) {
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
        + ", name="
        + name
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
