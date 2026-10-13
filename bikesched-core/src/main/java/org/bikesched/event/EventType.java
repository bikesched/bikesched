// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import java.util.Objects;
import org.bikesched.internal.util.ValidationUtil;
import org.jspecify.annotations.Nullable;

/**
 * An event name together with the class of its payload, shared by the code that publishes events of
 * the name and the jobs waiting for them:
 *
 * <pre>{@code
 * static final EventType<Payment> PAYMENT_RECEIVED = EventType.of("payment-received", Payment.class);
 *
 * Payment payment = job.awaitEvent("await-payment", PAYMENT_RECEIVED, order.id());
 * bikesched.newEvent(PAYMENT_RECEIVED, order.id()).payload(payment).publish();
 * }</pre>
 *
 * <p>Only the name is stored with the event, so an {@code EventType} is a compile-time contract:
 * events of the name can also be published and received with the string-based API.
 *
 * @param name the event name stored with each event, not blank, at most 200 characters
 * @param payloadType the class payloads are mapped to and from by the {@code JsonMapper}
 * @param <T> the payload type
 */
public record EventType<T extends @Nullable Object>(String name, Class<T> payloadType) {

  /** Validates the type. */
  public EventType {
    ValidationUtil.requireName(name, "event name");
    Objects.requireNonNull(payloadType, "payloadType");
  }

  /**
   * Creates an event type.
   *
   * @param name the event name stored with each event, not blank, at most 200 characters
   * @param payloadType the class payloads are mapped to and from
   * @param <T> the payload type
   * @return the event type
   */
  public static <T extends @Nullable Object> EventType<T> of(String name, Class<T> payloadType) {
    return new EventType<>(name, payloadType);
  }
}
