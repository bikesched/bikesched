// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A published event without its payload (see {@link Admin#findEventPayload(UUID)}).
 *
 * @param id the event id
 * @param name the event name
 * @param key the event key
 * @param idempotencyKey the key it was published with; may be {@code null}
 * @param publishedAt when it was published
 * @param expiresAt when it expires and is deleted
 * @param receivedBy the number of event waits that received it
 * @param latest whether it is the newest unexpired event of its name and key, the one a new wait
 *     receives
 */
public record EventSummary(
    UUID id,
    String name,
    String key,
    @Nullable String idempotencyKey,
    Instant publishedAt,
    Instant expiresAt,
    int receivedBy,
    boolean latest) {}
