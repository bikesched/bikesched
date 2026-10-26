// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

import org.bikesched.internal.util.ValidationUtil;

/**
 * A named actor recorded on the {@link HistoryEntry} of an action, e.g. the user of an admin UI:
 *
 * <pre>{@code
 * bikesched.newEvent("payment-received", orderId).actor(Actor.user("simon")).publish();
 * bikesched.admin().cancelJob(jobId).message("customer called").actor(Actor.user("simon")).cancel();
 * }</pre>
 *
 * <p>The actor goes only on the entry of the action itself (the {@code PUBLISHED}, the {@code
 * CANCEL_REQUESTED}); its consequences in the same transaction carry the code path's {@link
 * ActorType} and the subject that caused them ({@link HistoryEntry#causedById()}). Without the
 * history ({@link org.bikesched.BikeschedBuilder#history(java.time.Duration)}), an actor has no
 * effect.
 *
 * @param type the actor type, {@link ActorType#USER}
 * @param name the user name
 */
public record Actor(ActorType type, String name) {

  /** Validates the actor: only users are named. */
  public Actor {
    if (type != ActorType.USER) {
      throw new IllegalArgumentException("Only users are named actors: " + type);
    }
    ValidationUtil.requireName(name, "actor name");
  }

  /**
   * A user.
   *
   * @param name the user name, not blank, at most 200 characters
   * @return the actor
   * @throws IllegalArgumentException if the name is blank or too long
   */
  public static Actor user(String name) {
    return new Actor(ActorType.USER, name);
  }
}
