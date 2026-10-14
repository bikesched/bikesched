// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.Bikesched;

/**
 * Jobs using the durable mechanisms beyond plain steps, for the load and restart tests. A parent
 * job runs a step, starts a child job, waits for an event, sleeps, waits for the child and runs a
 * last step. The child's first attempt exceeds its timeout and is retried.
 *
 * <p>Each instance uses job types and an event name of its own. Step bodies and child attempts are
 * recorded in {@link #executions()} ({@code one}, {@code two}, {@code child-attempt-<n>}).
 */
final class MixedJobs {

  /** The child's timeout; its first attempt takes longer. */
  static final Duration CHILD_TIMEOUT = Duration.ofMillis(300);

  /** How long the parent sleeps between the event and waiting for its child. */
  static final Duration SLEEP = Duration.ofMillis(500);

  private final String parentType = "mixed-parent-" + UUID.randomUUID();
  private final String childType = "mixed-child-" + UUID.randomUUID();
  private final String eventName = "mixed-event-" + UUID.randomUUID();
  private final Executions executions = new Executions();

  /** Registers the handlers of both job types on the node. */
  void register(Bikesched node) {
    node.registerJobHandler(
        parentType,
        job -> {
          job.step("one", () -> executions.record(job.id(), "one"));
          UUID child =
              job.newJob(childType)
                  .timeout(CHILD_TIMEOUT)
                  .maxAttempts(10)
                  .stepName("child")
                  .schedule();
          job.waitForEvent("event", eventName, job.id().toString(), String.class).receive();
          job.sleep("sleep", SLEEP);
          if (!job.waitForJob(child, String.class).isSucceeded()) {
            throw new IllegalStateException("child job " + child + " did not succeed");
          }
          job.step("two", () -> executions.record(job.id(), "two"));
        });
    node.registerJobHandler(
        childType,
        job -> {
          executions.record(job.id(), "child-attempt-" + job.attempts());
          if (job.attempts() == 1) {
            Thread.sleep(CHILD_TIMEOUT.multipliedBy(5)); // interrupted by the timeout
          }
        });
  }

  /** Schedules a parent job through the node, which must have the handlers registered. */
  UUID schedule(Bikesched node) {
    return node.newJob(parentType).maxAttempts(100).schedule();
  }

  /** Publishes the event the parent job waits for. */
  void publishEvent(Bikesched node, UUID parentId) {
    node.newEvent(eventName, parentId.toString()).payload("\"go\"").publish();
  }

  /** The step bodies and child attempts run so far. */
  Executions executions() {
    return executions;
  }

  /** Counts the parent jobs in the given state. */
  String countParents(String state) {
    return count(parentType, state);
  }

  /** Counts the child jobs in the given state. */
  String countChildren(String state) {
    return count(childType, state);
  }

  private static String count(String type, String state) {
    return "select count(*) from bikesched_jobs where type = '"
        + type
        + "' and state = '"
        + state
        + "'";
  }
}
