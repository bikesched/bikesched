// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A recorded change: of a job ({@link JobEntry}), of one of its steps ({@link StepEntry}), of an
 * event ({@link EventEntry}) or of a node ({@link NodeEntry}). Recorded in the transaction of the
 * change, only by nodes with the history enabled ({@link
 * org.bikesched.BikeschedBuilder#history(java.time.Duration)}), and kept for its retention.
 *
 * <p>Entries are ordered by {@link #createdAt()}, then {@link #id()}.
 *
 * <pre>{@code
 * for (HistoryEntry entry : bikesched.findJobHistory(jobId)) {
 *   switch (entry) {
 *     case JobEntry job -> log.info("{} {} by {}", job.action(), job.state(), job.actorType());
 *     case StepEntry step -> log.info("step {} {}", step.stepId(), step.action());
 *     default -> {}
 *   }
 * }
 * }</pre>
 */
public sealed interface HistoryEntry permits JobEntry, StepEntry, EventEntry, NodeEntry {

  /**
   * Returns the entry's id, a time-ordered UUID unique across all entries.
   *
   * @return the id
   */
  UUID id();

  /**
   * Returns the id of what the entry is about: the job (also for a step entry), the event or the
   * node.
   *
   * @return the subject's id
   */
  UUID subjectId();

  /**
   * Returns when the entry was written, by the database's clock.
   *
   * @return the time
   */
  Instant createdAt();

  /**
   * Returns what happened.
   *
   * @return the action
   */
  HistoryAction action();

  /**
   * Returns who made the change.
   *
   * @return the actor's type
   */
  ActorType actorType();

  /**
   * Returns the user who made the change.
   *
   * @return the user name for {@link ActorType#USER}, otherwise {@code null}
   */
  @Nullable String actorName();

  /**
   * Returns the node that wrote the entry, whoever the actor was.
   *
   * @return the node's id; {@code null} if the writing instance had not registered as a node
   */
  @Nullable UUID nodeId();

  /**
   * Returns the event, job or node that caused the change, if another subject did.
   *
   * @return its id, {@code null} if none
   */
  @Nullable UUID causedById();

  /**
   * Returns the names and the action's specifics as a JSON object, stored with the entry so it
   * reads well after its subject was deleted. What the entry involves gets an object of its own:
   * {@code job} ({@code name}), {@code step} ({@code name}), {@code event} ({@code id}, {@code
   * name}, {@code key}: the event published, or the one a step waits for), {@code node} ({@code
   * name}) and {@code childJob} ({@code id}, {@code state}). The action's specifics are top-level
   * keys, listed with each action (e.g. {@code error}, {@code dueAt}). Fields without a value are
   * left out; texts are cut to 4000 characters.
   *
   * @return the JSON object as text, {@code null} if there is nothing to store
   */
  @Nullable String data();
}
