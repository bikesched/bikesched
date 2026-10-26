// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.StepRef;
import org.bikesched.internal.database.StepStore;
import org.bikesched.internal.database.StepType;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.JobResult;
import org.bikesched.job.Step;
import org.bikesched.job.StepState;
import org.jspecify.annotations.Nullable;

/**
 * The step primitives of a {@link DefaultJob} claimed to be compensated: its handler is replayed,
 * and each primitive only returns what it stored in an earlier run. Completed steps register their
 * compensations (latest first); the first primitive that has not completed ends the replay with
 * {@link CompensationReplayEnded}. Nothing is written: no step starts, waits or times out.
 */
final class CompensationReplay {

  /**
   * A compensation registered while replaying the handler for compensation.
   *
   * @param step the step to compensate
   * @param call runs the compensation with the step's stored result
   */
  record PendingCompensation(StepRef step, DefaultJob.StepCall<@Nullable Void> call) {

    /** The name of the step to compensate. */
    String stepName() {
      return step.name();
    }
  }

  private final Database database;
  private final UUID jobId;

  /** Compensations registered while replaying, latest first. */
  private final Deque<PendingCompensation> compensations = new ConcurrentLinkedDeque<>();

  CompensationReplay(Database database, UUID jobId) {
    this.database = database;
    this.jobId = jobId;
  }

  /** The compensations registered by the replay, in the order to run them (latest step first). */
  List<PendingCompensation> compensations() {
    return List.copyOf(compensations);
  }

  /** A regular step: its stored result, registering its compensation if it has one to run. */
  <T extends @Nullable Object> @Nullable T step(
      String name,
      Function<@Nullable String, @Nullable T> fromJson,
      @Nullable CompensationHandler<? super T> compensation) {
    var completed = database.steps().findCompletedStep(jobId, name);
    if (completed.isEmpty()) {
      throw new CompensationReplayEnded(name);
    }
    StepType.STEP.require(name, jobId, completed.get().type());
    @Nullable T result = fromJson.apply(completed.get().result());
    if (compensation != null && completed.get().compensable() && !completed.get().compensated()) {
      var step = new Step(jobId, name, completed.get().attempts());
      register(completed.get().step(), () -> compensation.handle(step, result));
    }
    return result;
  }

  /** An event published as a step: continues only if it was published. */
  void requirePublished(String stepName) {
    requireSucceeded(stepName, null);
  }

  /** A child job started as a step: the started child, registering the step's compensation. */
  UUID startedChild(String stepName, @Nullable CompensationHandler<? super UUID> compensation) {
    var step = database.steps().findChildJobStep(jobId, stepName);
    if (step.isEmpty()) {
      throw new CompensationReplayEnded(stepName);
    }
    StepType.CHILD_JOB.require(stepName, jobId, step.get().type());
    UUID childJobId =
        Objects.requireNonNull(step.get().childJobId(), "child job of a CHILD_JOB step");
    if (compensation != null && step.get().state() != StepState.COMPENSATED) {
      var compensated = new Step(jobId, stepName, step.get().attempts());
      register(step.get().step(), () -> compensation.handle(compensated, childJobId));
    }
    return childJobId;
  }

  /** A wait for a child job: the outcome it received or its timeout. */
  <T extends @Nullable Object> JobResult<T> childResult(
      UUID childJobId, Function<@Nullable String, @Nullable T> read) {
    var wait = database.steps().findChildWait(jobId, childJobId);
    return switch (wait) {
      case StepStore.ChildWait.ChildEnded ended -> DefaultJob.jobResult(childJobId, ended, read);
      case StepStore.ChildWait.ChildTimedOut ignored -> new JobResult.TimedOut<>(childJobId);
      case StepStore.ChildWait.ChildWaiting ignored ->
          throw new CompensationReplayEnded(wait.step().name());
      case StepStore.ChildWait.NotWaiting ignored ->
          throw new CompensationReplayEnded(wait.step().name());
    };
  }

  /** A sleep: returns only after a completed sleep. */
  void sleep(String stepName) {
    requireSucceeded(stepName, StepType.SLEEP);
  }

  /** A poll: its ready value; a poll that failed or timed out ended the job there. */
  <T extends @Nullable Object> @Nullable T poll(
      String name, Function<@Nullable String, @Nullable T> read) {
    return read.apply(requireSucceeded(name, StepType.POLL).result());
  }

  /** An event wait: the received payload; a wait that timed out ended the job there. */
  <T extends @Nullable Object> @Nullable T event(
      String stepName, Function<@Nullable String, @Nullable T> read) {
    return read.apply(requireSucceeded(stepName, StepType.EVENT_WAIT).result());
  }

  /**
   * The stored step, if it succeeded; otherwise the replay ends there.
   *
   * @param type the expected type, {@code null} to accept any
   */
  private StepStore.StoredStep requireSucceeded(String stepName, @Nullable StepType type) {
    var stored = database.steps().findStep(jobId, stepName);
    if (type != null) {
      stored.ifPresent(step -> type.require(stepName, jobId, step.type()));
    }
    return stored
        .filter(step -> step.state() == StepState.SUCCEEDED)
        .orElseThrow(() -> new CompensationReplayEnded(stepName));
  }

  @FunctionalInterface
  private interface Compensation {
    void run() throws Exception;
  }

  private void register(StepRef step, Compensation compensation) {
    compensations.push(
        new PendingCompensation(
            step,
            () -> {
              compensation.run();
              return null;
            }));
  }
}
