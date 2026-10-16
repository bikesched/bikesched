// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.StepKind;
import org.bikesched.internal.database.StepState;
import org.bikesched.internal.database.StepStore;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.JobResult;
import org.bikesched.job.Step;
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
   * @param stepName the step to compensate
   * @param call runs the compensation with the step's stored result
   */
  record PendingCompensation(String stepName, DefaultJob.StepCall<@Nullable Void> call) {}

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
    StepKind.STEP.require(name, jobId, completed.get().kind());
    @Nullable T result = fromJson.apply(completed.get().result());
    if (compensation != null && completed.get().compensable() && !completed.get().compensated()) {
      var step = new Step(jobId, name, completed.get().attempts());
      register(name, () -> compensation.handle(step, result));
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
    StepKind.CHILD_JOB.require(stepName, jobId, step.get().kind());
    UUID childJobId =
        Objects.requireNonNull(step.get().childJobId(), "child job of a CHILD_JOB step");
    if (compensation != null && step.get().state() != StepState.COMPENSATED) {
      var compensated = new Step(jobId, stepName, step.get().attempts());
      register(stepName, () -> compensation.handle(compensated, childJobId));
    }
    return childJobId;
  }

  /** A wait for a child job: the outcome it received or its timeout. */
  <T extends @Nullable Object> JobResult<T> childResult(
      UUID childJobId, Function<@Nullable String, @Nullable T> read) {
    var wait = database.steps().findChildWait(jobId, childJobId);
    return switch (wait) {
      case StepStore.ChildWait.ChildEnded ended -> DefaultJob.jobResult(childJobId, ended, read);
      case StepStore.ChildWait.ChildTimedOut _ -> new JobResult.TimedOut<>(childJobId);
      case StepStore.ChildWait.ChildWaiting _, StepStore.ChildWait.NotWaiting _ ->
          throw new CompensationReplayEnded(wait.stepName());
    };
  }

  /** A sleep: returns only after a completed sleep. */
  void sleep(String stepName) {
    requireSucceeded(stepName, StepKind.SLEEP);
  }

  /** A poll: its ready value; a poll that failed or timed out ended the job there. */
  <T extends @Nullable Object> @Nullable T poll(
      String name, Function<@Nullable String, @Nullable T> read) {
    return read.apply(requireSucceeded(name, StepKind.POLL).result());
  }

  /** An event wait: the received payload; a wait that timed out ended the job there. */
  <T extends @Nullable Object> @Nullable T event(
      String stepName, Function<@Nullable String, @Nullable T> read) {
    return read.apply(requireSucceeded(stepName, StepKind.EVENT_WAIT).result());
  }

  /**
   * The stored step, if it succeeded; otherwise the replay ends there.
   *
   * @param kind the expected kind, {@code null} to accept any
   */
  private StepStore.StoredStep requireSucceeded(String stepName, @Nullable StepKind kind) {
    var stored = database.steps().findStep(jobId, stepName);
    if (kind != null) {
      stored.ifPresent(step -> kind.require(stepName, jobId, step.kind()));
    }
    return stored
        .filter(step -> step.state() == StepState.SUCCEEDED)
        .orElseThrow(() -> new CompensationReplayEnded(stepName));
  }

  @FunctionalInterface
  private interface Compensation {
    void run() throws Exception;
  }

  private void register(String stepName, Compensation compensation) {
    compensations.push(
        new PendingCompensation(
            stepName,
            () -> {
              compensation.run();
              return null;
            }));
  }
}
