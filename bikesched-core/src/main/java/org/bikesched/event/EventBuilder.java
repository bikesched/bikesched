// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.BikeschedBuilder;
import org.bikesched.BikeschedException;
import org.bikesched.JsonMapper;

/**
 * Builds and publishes an event, obtained via {@link org.bikesched.Bikesched#newEvent(String,
 * String)} or, with a typed payload, {@link org.bikesched.Bikesched#newEvent(EventType, String)}:
 *
 * <pre>{@code
 * UUID eventId = bikesched.newEvent("payment-received", orderId)
 *     .payload(payment)
 *     .publish();
 * }</pre>
 *
 * <p>Publishing stores the event and hands it to every step currently waiting for it (same name and
 * key, see {@link org.bikesched.job.Job#waitForEvent(String, String, String)}); their jobs continue
 * right away. The event is kept for its {@link #retention(Duration) retention}, so a step that
 * starts waiting for it later still receives it. Not thread-safe.
 *
 * @param <P> the payload type: {@code Object} for an event name given as a string, the payload
 *     class of an {@link EventType}
 */
public interface EventBuilder<P> {

  /**
   * Sets the event payload (optional, default none), converted to JSON right away by the configured
   * {@link JsonMapper}; waiting steps receive it as their result.
   *
   * @param payload the payload, may be {@code null}
   * @return this builder
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default mapper (no {@code jsonMapper} configured) for types other than {@link
   *     String}
   */
  EventBuilder<P> payload(P payload);

  /**
   * Sets how long the event is kept for steps that start waiting for it later (optional, default
   * the node's {@link BikeschedBuilder#eventRetention(Duration) eventRetention}). Steps waiting
   * when it is published receive it regardless; once the retention has passed, later waits no
   * longer receive it, and the nodes delete it.
   *
   * <pre>{@code
   * bikesched.newEvent("driver-accepted", rideId).payload(offer).retention(Duration.ofMinutes(5)).publish();
   * bikesched.newEvent("location-updated", rideId).retention(Duration.ZERO).publish();   // not kept
   * }</pre>
   *
   * @param retention how long the event is kept, not negative; {@link Duration#ZERO} delivers it
   *     only to the steps waiting when it is published
   * @return this builder
   * @throws IllegalArgumentException if the retention is negative
   */
  EventBuilder<P> retention(Duration retention);

  /**
   * Publishes the event.
   *
   * @return the id of the published event, e.g. to log it or store it with your own data
   * @throws BikeschedException if the event cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if this event cannot be published in the current context
   */
  UUID publish();
}
