// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A change of an event, see {@link HistoryEntry}.
 *
 * @param id the entry's id
 * @param eventId the event
 * @param createdAt when the entry was written
 * @param action what happened
 * @param actorType who made the change
 * @param actorName the user name for {@link ActorType#USER}, otherwise {@code null}
 * @param nodeId the node that wrote the entry, {@code null} if none
 * @param causedById the event, job or node that caused the change, {@code null} if none
 * @param data the names and the action's specifics as a JSON object, see {@link
 *     HistoryEntry#data()}
 */
public record EventEntry(
    UUID id,
    UUID eventId,
    Instant createdAt,
    EventAction action,
    ActorType actorType,
    @Nullable String actorName,
    @Nullable UUID nodeId,
    @Nullable UUID causedById,
    @Nullable String data)
    implements HistoryEntry {
  @Override
  public UUID subjectId() {
    return eventId;
  }
}
