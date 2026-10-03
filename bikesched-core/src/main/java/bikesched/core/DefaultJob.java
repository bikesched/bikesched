package bikesched.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The {@link Job} implementation: a job claimed from {@code bikesched_jobs} for execution on this
 * node. Besides the handler API it carries what bikesched itself needs to schedule, time out and
 * record the job (priority, schedule time, timeout, cancellation state).
 */
final class DefaultJob implements Job {

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

  // Set by the handler, read by bikesched after the handler returned (the handler may use other
  // threads).
  private volatile boolean cancelled;
  private volatile String cancelMessage;
  private volatile JobFailure failure;
  private volatile JobSuspended.Suspension suspension;
  private volatile JobAttempt attempt;
  private volatile Duration defaultEventTimeout;

  DefaultJob(
      Database database,
      UUID id,
      String type,
      String payload,
      int priority,
      Instant scheduledAt,
      int attempts,
      int maxAttempts,
      Duration timeout) {
    this.database = Objects.requireNonNull(database, "database");
    this.id = Objects.requireNonNull(id, "id");
    this.type = Objects.requireNonNull(type, "type");
    this.payload = payload;
    this.priority = priority;
    this.scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
    this.attempts = attempts;
    this.maxAttempts = maxAttempts;
    this.timeout = timeout;
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
    var failure = new JobFailure(message);
    this.failure = failure;
    return failure;
  }

  /** The failure the handler marked the attempt with, {@code null} if none. */
  JobFailure failure() {
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
    Objects.requireNonNull(resultType, "resultType");
    JsonMapper mapper = database.jsonMapper();
    return runStep(name, handler, mapper::toJson, json -> mapper.fromJson(json, resultType));
  }

  @Override
  public String step(String name, StepHandler<String> handler) throws Exception {
    return runStep(name, handler, json -> json, json -> json);
  }

  private <T> T runStep(
      String name,
      StepHandler<T> handler,
      Function<? super T, String> toJson,
      Function<String, T> fromJson)
      throws Exception {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(handler, "handler");
    if (name.isBlank()) {
      throw new IllegalArgumentException("step name must not be blank");
    }
    Runnable restoreStepName = putStepName(name);
    try {
      var completed = database.findCompletedStep(id, name);
      if (completed.isEmpty()) {
        var stepAttempt = database.startStep(id, name);
        if (stepAttempt.isPresent()) {
          return executeStep(name, stepAttempt.get(), handler, toJson);
        }
        completed = database.findCompletedStep(id, name); // completed in the meantime
      }
      LOG.debug("Step {} of job {} already completed, reusing its result", name, id);
      String json = completed.map(Database.CompletedStep::result).orElse(null);
      return json == null ? null : fromJson.apply(json);
    } finally {
      restoreStepName.run();
    }
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
    return new EventWait(this, stepName, eventName, eventKey);
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
      var wait = database.awaitEvent(id, stepName, eventName, eventKey, timeout);
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
      String name, int stepAttempt, StepHandler<T> handler, Function<? super T, String> toJson)
      throws Exception {
    LOG.debug("Executing step {} of job {} (attempt {})", name, id, stepAttempt);
    long start = System.nanoTime();
    T result;
    try {
      result = handler.run(new Step(id, name, stepAttempt));
      database.completeStep(id, name, result == null ? null : toJson.apply(result));
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
      database.failStep(id, name, failure.toString());
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
