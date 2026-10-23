// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.junit.jupiter.api.Test;

class StepStoreTest extends AbstractStoreTest {

  @Test
  void stepLifecycleStoresResultAndCountsAttempts() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();

    assertThat(database.steps().findCompletedStep(jobId, "a")).isEmpty();
    assertThat(database.steps().startStep(jobId, lock(jobId), "a")).contains(1);
    database.steps().failStep(jobId, lock(jobId), "a", "boom");
    assertThat(database.steps().findCompletedStep(jobId, "a"))
        .as("failed is not completed")
        .isEmpty();
    assertThat(
            countSteps(
                "name = 'a' and state = 'FAILED' and last_error = 'boom' and finished_at is not null"))
        .isEqualTo(1);

    assertThat(database.steps().startStep(jobId, lock(jobId), "a"))
        .as("second attempt")
        .contains(2);
    assertThat(countSteps("name = 'a' and state = 'RUNNING' and finished_at is null")).isEqualTo(1);
    database.steps().completeStep(jobId, lock(jobId), "a", "{\"x\": 1}");

    assertThat(database.steps().findCompletedStep(jobId, "a"))
        .contains(new StepStore.CompletedStep(StepKind.STEP, "{\"x\": 1}", false, false, 2));
    assertThat(database.steps().startStep(jobId, lock(jobId), "a"))
        .as("no restart once succeeded")
        .isEmpty();
    assertThat(
            countSteps(
                "name = 'a' and state = 'SUCCEEDED' and attempts = 2 and finished_at is not null"))
        .isEqualTo(1);

    assertThat(database.steps().startStep(jobId, lock(jobId), "b")).contains(1);
    database.steps().completeStep(jobId, lock(jobId), "b", null);
    assertThat(database.steps().findCompletedStep(jobId, "b"))
        .contains(new StepStore.CompletedStep(StepKind.STEP, null, false, false, 1));
  }

  @Test
  void completeStepRejectsInvalidJson() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    database.steps().startStep(jobId, lock(jobId), "a");

    assertThatThrownBy(() -> database.steps().completeStep(jobId, lock(jobId), "a", "not json"))
        .isInstanceOf(BikeschedException.class)
        .hasMessage("Storing the result of step 'a' of bikesched job " + jobId + " failed");
    assertThat(countSteps("name = 'a' and state = 'RUNNING'")).isEqualTo(1);
  }

  @Test
  void runningStepIsLockedByItsNode() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();

    database.steps().startStep(jobId, lock(jobId), "a");
    assertThat(
            countSteps("name = 'a' and state = 'RUNNING' and locked_by_node_id = '" + node + "'"))
        .isEqualTo(1);
    database.steps().completeStep(jobId, lock(jobId), "a", null);
    assertThat(countSteps("name = 'a' and state = 'SUCCEEDED' and locked_by_node_id is null"))
        .isEqualTo(1);

    database.steps().startStep(jobId, lock(jobId), "b");
    database.steps().failStep(jobId, lock(jobId), "b", "boom");
    assertThat(countSteps("name = 'b' and state = 'FAILED' and locked_by_node_id is null"))
        .isEqualTo(1);
  }

  @Test
  void stepWritesOfANodeThatLostTheJobAreRejected() throws SQLException {
    UUID node = registerNode("node-1");
    UUID other = registerNode("node-2");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    JobLock mine = lock(jobId);
    database.steps().startStep(jobId, mine, "a");
    database.steps().startStep(jobId, mine, "b");
    database.steps().completeStep(jobId, mine, "b", null, true);
    // Released (as for a node considered dead) and claimed by another node.
    execute(
        "update bikesched_jobs set locked_by_node_id = '"
            + other
            + "', lock_token = gen_random_uuid() where id = '"
            + jobId
            + "'");

    assertStepWritesRejected(jobId, mine);
    assertThat(
            countSteps("name = 'a' and state = 'RUNNING' and locked_by_node_id = '" + node + "'"))
        .as("unchanged")
        .isEqualTo(1);

    // The new owner takes the step over.
    JobLock theirs = lock(jobId);
    assertThat(database.steps().startStep(jobId, theirs, "a")).contains(2);
    database.steps().completeStep(jobId, theirs, "a", "1");
    assertThat(countSteps("name = 'a' and state = 'SUCCEEDED' and locked_by_node_id is null"))
        .isEqualTo(1);
  }

  /**
   * The case the lock token exists for: the job is released and claimed again by the same node
   * (e.g. after a long pause), while the first attempt's thread still runs. Same node, new claim:
   * the first attempt's writes are rejected.
   */
  @Test
  void stepWritesOfAnEarlierClaimOfTheSameNodeAreRejected() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    JobLock first = lock(jobId);
    database.steps().startStep(jobId, first, "a");
    database.steps().startStep(jobId, first, "b");
    database.steps().completeStep(jobId, first, "b", null, true);
    assertThat(database.jobs().releaseJobs(node)).isOne();
    JobStore.ClaimedJob again = claimJobs(node, 1).getFirst();
    assertThat(again.id()).isEqualTo(jobId);
    assertThat(again.lock().nodeId()).isEqualTo(first.nodeId());
    assertThat(again.lock().token()).isNotEqualTo(first.token());

    assertStepWritesRejected(jobId, first);
    assertThatThrownBy(() -> database.events().awaitEvent(jobId, first, "w", "e", "k", null))
        .isInstanceOf(JobLockLostException.class);
    assertThat(database.jobs().completeJob(jobId, first))
        .as("the earlier claim cannot end the job either")
        .isFalse();

    assertThat(database.steps().startStep(jobId, again.lock(), "a")).contains(2);
    database.steps().completeStep(jobId, again.lock(), "a", "1");
    assertThat(database.jobs().completeJob(jobId, again.lock())).isTrue();
  }

  private static void assertStepWritesRejected(UUID jobId, JobLock lock) throws SQLException {
    assertThatThrownBy(() -> database.steps().completeStep(jobId, lock, "a", "1"))
        .isInstanceOf(JobLockLostException.class)
        .hasMessageContaining(
            jobId + " is no longer running in this attempt's claim by node " + lock.nodeId());
    assertThatThrownBy(() -> database.steps().failStep(jobId, lock, "a", "boom"))
        .isInstanceOf(JobLockLostException.class);
    assertThatThrownBy(() -> database.steps().startStep(jobId, lock, "c"))
        .isInstanceOf(JobLockLostException.class);
    assertThatThrownBy(() -> database.steps().compensateStep(jobId, lock, "b"))
        .isInstanceOf(JobLockLostException.class);
    assertThatThrownBy(() -> database.steps().failCompensation(jobId, lock, "b", "boom"))
        .isInstanceOf(JobLockLostException.class);
    assertThat(countSteps("job_id = '" + jobId + "' and name = 'c'")).isZero();
  }

  @Test
  void stepWritesOfAnEndedJobAreRejected() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    JobLock ended = lock(jobId);
    execute(
        "update bikesched_jobs set state = 'CANCELLED', locked_by_node_id = null, lock_token = null,"
            + " locked_at = null,"
            + " finished_at = now() where id = '"
            + jobId
            + "'");

    assertThatThrownBy(() -> database.steps().startStep(jobId, ended, "a"))
        .isInstanceOf(JobLockLostException.class);
    assertThatThrownBy(() -> database.events().awaitEvent(jobId, ended, "w", "event", "key", null))
        .isInstanceOf(JobLockLostException.class);
    assertThat(countSteps("job_id = '" + jobId + "'")).isZero();
  }

  @Test
  void pollLifecycleKeepsOneRowAndItsFirstDeadline() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    StepStore steps = database.steps();

    assertThat(steps.startPoll(jobId, lock(jobId), "p", Duration.ofHours(1)))
        .isEqualTo(new StepStore.PollStep.CheckStarted(1, false));
    assertThat(
            countSteps(
                "name = 'p' and kind = 'POLL' and state = 'RUNNING' and locked_by_node_id = '"
                    + node
                    + "' and deadline_at > now() + interval '50 minutes'"))
        .isEqualTo(1);

    StepStore.PollWait wait = steps.waitPoll(jobId, lock(jobId), "p", Duration.ofMinutes(5));
    assertThat(wait.timedOut()).isFalse();
    assertThat(wait.checks()).isEqualTo(1);
    assertThat(
            countSteps(
                "name = 'p' and state = 'WAITING' and locked_by_node_id is null"
                    + " and wake_at between now() + interval '4 minutes'"
                    + " and now() + interval '6 minutes'"))
        .isEqualTo(1);

    assertThat(steps.startPoll(jobId, lock(jobId), "p", Duration.ofHours(1)))
        .as("not due: no check")
        .isEqualTo(new StepStore.PollStep.NotDue(1, wait.waitUntil()));

    execute("update bikesched_steps set wake_at = now() where name = 'p'");
    assertThat(steps.startPoll(jobId, lock(jobId), "p", Duration.ofMillis(1)))
        .isEqualTo(new StepStore.PollStep.CheckStarted(2, true));
    assertThat(countSteps("name = 'p' and deadline_at > now() + interval '50 minutes'"))
        .as("the first deadline wins")
        .isEqualTo(1);

    steps.completePoll(jobId, lock(jobId), "p", "\"ready\"", 2);
    assertThat(steps.startPoll(jobId, lock(jobId), "p", null))
        .isEqualTo(new StepStore.PollStep.Ready("\"ready\""));
    assertThat(countRows("bikesched_job_history", "job_id = '" + jobId + "' and step_name = 'p'"))
        .as("only the outcome is recorded")
        .isOne();
  }

  @Test
  void aPollTimesOutOnceItsDeadlineHasPassed() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    StepStore steps = database.steps();
    steps.startPoll(jobId, lock(jobId), "p", Duration.ofHours(1));
    steps.waitPoll(jobId, lock(jobId), "p", Duration.ofHours(2));
    assertThat(countSteps("name = 'p' and wake_at = deadline_at"))
        .as("the next check is at most at the deadline")
        .isEqualTo(1);

    execute("update bikesched_steps set wake_at = now(), deadline_at = now() where name = 'p'");
    steps.startPoll(jobId, lock(jobId), "p", Duration.ofHours(1));
    StepStore.PollWait wait = steps.waitPoll(jobId, lock(jobId), "p", Duration.ofHours(2));

    assertThat(wait.timedOut()).isTrue();
    assertThat(wait.checks()).isEqualTo(2);
    assertThat(steps.startPoll(jobId, lock(jobId), "p", null))
        .isEqualTo(new StepStore.PollStep.PollTimedOut(2));
    assertThat(
            countRows(
                "bikesched_job_history",
                "job_id = '"
                    + jobId
                    + "' and step_name = 'p' and state = 'TIMED_OUT'"
                    + " and detail = 'timed out after 2 checks'"))
        .isOne();
  }

  @Test
  void pollWritesOfAnEarlierClaimAreRejected() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    StepStore steps = database.steps();
    steps.startPoll(jobId, lock(jobId), "p", null);
    JobLock stale = staleLock(node);

    assertThatThrownBy(() -> steps.startPoll(jobId, stale, "q", null))
        .isInstanceOf(JobLockLostException.class);
    assertThatThrownBy(() -> steps.waitPoll(jobId, stale, "p", Duration.ZERO))
        .isInstanceOf(JobLockLostException.class);
    assertThatThrownBy(() -> steps.completePoll(jobId, stale, "p", null, 1))
        .isInstanceOf(JobLockLostException.class);
    assertThatThrownBy(() -> steps.failPoll(jobId, stale, "p", "boom", 1))
        .isInstanceOf(JobLockLostException.class);
    assertThat(countSteps("name = 'p' and state = 'RUNNING'")).isOne();
    assertThat(countSteps("name = 'q'")).isZero();
  }

  @Test
  void claimsAndSuspensionsOfAWaitingPollAreNotRecorded() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = claimJobs(node, 1).getFirst().id();
    StepStore steps = database.steps();
    steps.startPoll(jobId, lock(jobId), "p", null);
    steps.waitPoll(jobId, lock(jobId), "p", Duration.ZERO);
    assertThat(steps.suspendAtPoll(jobId, lock(jobId), "p", true)).isPresent();
    String jobEntries = "job_id = '" + jobId + "' and step_name is null";
    long recorded = countRows("bikesched_job_history", jobEntries);

    // The next check: claimed while the poll waits, and suspended again.
    assertThat(claimJobs(node, 1)).extracting(JobStore.ClaimedJob::id).containsExactly(jobId);
    steps.startPoll(jobId, lock(jobId), "p", null);
    steps.waitPoll(jobId, lock(jobId), "p", Duration.ZERO);
    assertThat(steps.suspendAtPoll(jobId, lock(jobId), "p", false)).isPresent();

    assertThat(countRows("bikesched_job_history", jobEntries)).isEqualTo(recorded);
    assertThat(count("id = '" + jobId + "' and state = 'WAITING'")).isOne();
  }
}
