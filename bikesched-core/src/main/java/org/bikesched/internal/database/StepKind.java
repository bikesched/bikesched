// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.UUID;

/** What a step does, as stored in {@code bikesched_steps.kind}. */
public enum StepKind {
  /** A regular step running a handler. */
  STEP("a regular step"),
  /** A durable sleep. */
  SLEEP("a sleep"),
  /** A wait for an event. */
  EVENT_WAIT("an event wait"),
  /** An event published as a step. */
  EVENT_PUBLISH("an event publish"),
  /** A child job started as a step. */
  CHILD_JOB("a child job"),
  /** A poll. */
  POLL("a poll");

  private final String description;

  StepKind(String description) {
    this.description = description;
  }

  /** Describes the kind for messages, e.g. {@code "a regular step"}. */
  public String description() {
    return description;
  }

  /**
   * Rejects a step name already used by another kind of step of the job.
   *
   * @param name the step name
   * @param jobId the job
   * @param actual the kind of the stored step
   * @throws IllegalStateException if {@code actual} is not this kind
   */
  public void require(String name, UUID jobId, StepKind actual) {
    if (actual != this) {
      throw new IllegalStateException(
          "Step '"
              + name
              + "' of job "
              + jobId
              + " is "
              + actual.description
              + ", not "
              + description
              + "; step names must be unique within a job");
    }
  }
}
